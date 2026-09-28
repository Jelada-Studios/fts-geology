package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.EruptionPacket;
import com.jeladastudios.ftsgeology.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * An eruption as the person watching it sees it: the column, the fumaroles and the falling ash,
 * drawn here from the state the volcano's core sends.
 *
 * <h2>Detail where it is seen</h2>
 * The column is sized as it always was - thirty blocks plus twenty a magnitude, leaning downwind the
 * higher it goes - but what fills it depends on the viewer. On the slopes it is dense; from the far
 * ridge it is a silhouette, which is all that distance can show anyway. The player's own particle
 * setting scales everything on top of that, so "minimal" means minimal here too.
 *
 * <p>Particles are added with force, which skips vanilla's 32-block cut-off: a column meant to be
 * seen from three hundred blocks is useless if it is only drawn from thirty.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientEruptions {

    private ClientEruptions() {}

    /** Two missed heartbeats and the eruption is taken to be over. */
    private static final int GRACE_TICKS = 90;

    private record Active(EruptionPacket state, long expires) {}

    private static final Map<BlockPos, Active> ACTIVE = new HashMap<>();

    /** A pyroclastic flow as the client sees it: the way its front has come, for the cloud rising off its path. */
    private static final class FlowView {
        final ArrayDeque<Vec3> trail = new ArrayDeque<>();
        float width;
        boolean done;
        long expires;
    }

    private static final Map<Integer, FlowView> FLOWS = new HashMap<>();
    /** Points of a flow's path kept for its cloud. */
    private static final int TRAIL = 48;

    /** How thick the ash is in the air round the viewer, 0 to 1, eased towards where it should be. */
    private static double fog;

    public static void update(EruptionPacket p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (p.phase() == 0) ACTIVE.remove(p.summit());
        else ACTIVE.put(p.summit(), new Active(p, level.getGameTime() + GRACE_TICKS));
    }

    public static void flow(com.jeladastudios.ftsgeology.network.FlowPacket p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        FlowView v = FLOWS.computeIfAbsent(p.id(), k -> new FlowView());
        Vec3 at = new Vec3(p.x(), p.y(), p.z());
        if (v.trail.isEmpty() || v.trail.peekLast().distanceToSqr(at) > 0.25) {
            v.trail.addLast(at);
            while (v.trail.size() > TRAIL) v.trail.removeFirst();
        }
        v.width = p.width();
        v.done = p.done();
        // A stopped flow's cloud goes on rising and drifting off for a while.
        v.expires = level.getGameTime() + (p.done() ? 160 : 40);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            ACTIVE.clear();   // nothing carries over into the next world
            FLOWS.clear();
            fog = 0;
            com.jeladastudios.ftsgeology.compat.dh.DhAshFog.update(0.0);
            return;
        }
        if (mc.isPaused()) return;
        if (ACTIVE.isEmpty() && FLOWS.isEmpty()) {
            fog = Math.max(0.0, fog - 0.015);
            com.jeladastudios.ftsgeology.compat.dh.DhAshFog.update(fog);
            return;
        }
        long now = level.getGameTime();
        ACTIVE.values().removeIf(a -> now > a.expires());
        FLOWS.values().removeIf(v -> now > v.expires);
        double setting = switch (mc.options.particles().get()) {
            case ALL -> 1.0;
            case DECREASED -> 0.45;
            case MINIMAL -> 0.15;
        };
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        for (Active a : ACTIVE.values()) emit(level, a.state(), eye, setting, level.random);
        for (FlowView v : FLOWS.values()) emitFlow(level, v, eye, setting, level.random);
        double target = ashInAir(eye);
        fog += (target - fog) * (target > fog ? 0.03 : 0.015);
        // Distant Horizons turns the game fog off unless told otherwise.
        com.jeladastudios.ftsgeology.compat.dh.DhAshFog.update(fog);
    }

    /**
     * How thick the ash is where the viewer stands: in an eruption's downwind lobe, heaviest near the mountain, and
     * thickest of all in a pyroclastic flow's cloud.
     */
    private static double ashInAir(Vec3 eye) {
        double most = 0.0;
        for (Active a : ACTIVE.values()) {
            EruptionPacket p = a.state();
            if (p.phase() != 2 || !p.ashfall()) continue;
            double sx = p.summit().getX() + 0.5, sz = p.summit().getZ() + 0.5;
            double dist = Math.hypot(eye.x - sx, eye.z - sz);
            double reach = Math.min(40 + p.magnitude() * 8, 160);
            if (dist > reach) continue;
            double align = dist < 1 ? 1.0 : ((eye.x - sx) / dist) * p.windX() + ((eye.z - sz) / dist) * p.windZ();
            double lobe = 0.15 + 0.85 * Math.pow((align + 1.0) * 0.5, 2.2);
            most = Math.max(most, Math.min(1.0, 1.1 * lobe * (0.35 + 0.65 * (1.0 - dist / reach))));
        }
        for (FlowView v : FLOWS.values()) {
            int i = 0;
            for (Vec3 p : v.trail) {
                if (i++ % 3 != 0 && p != v.trail.peekLast()) continue;
                double d = Math.sqrt(p.distanceToSqr(eye));
                double inside = 1.0 - (d - v.width - 4) / 10.0;
                if (inside > 0) most = Math.max(most, Math.min(1.0, inside) * (v.done ? 0.7 : 1.0));
            }
        }
        return most;
    }

    /**
     * A pyroclastic flow: a dark, ground-hugging avalanche at its front, and pale ash boiling up off the whole of the way
     * it has come, as the hot cloud lifts off it.
     */
    private static void emitFlow(ClientLevel level, FlowView v, Vec3 eye, double setting, RandomSource rng) {
        if (v.trail.isEmpty()) return;
        Vec3 front = v.trail.peekLast();
        double dist = Math.hypot(eye.x - front.x, eye.z - front.z);
        if (dist > 640) return;
        double lod = setting * (dist < 96 ? 1.0 : dist < 256 ? 0.55 : 0.3);
        double w = v.width;
        if (!v.done) {
            Vec3 back = v.trail.size() > 2 ? (Vec3) v.trail.toArray()[v.trail.size() - 3] : front;
            double hx = front.x - back.x, hz = front.z - back.z, h = Math.max(0.01, Math.hypot(hx, hz));
            hx /= h;
            hz /= h;
            spawn(level, ModParticles.VOLCANIC_SMOKE.get(), 3.0 * lod, front.x, front.y + 0.8, front.z, w * 0.5, 0.6, rng,
                    hx * 0.18, 0.03, hz * 0.18);
            spawn(level, ModParticles.ASH_CLOUD.get(), 3.5 * lod, front.x, front.y + 2.5, front.z, w * 0.6, 1.2, rng,
                    hx * 0.12, 0.05, hz * 0.12);
        }
        int i = 0;
        for (java.util.Iterator<Vec3> it = v.trail.descendingIterator(); it.hasNext(); i++) {
            Vec3 p = it.next();
            if (i % 4 != 0) continue;
            double rise = 2.5 + i * 0.25;
            spawn(level, ModParticles.ASH_CLOUD.get(), (v.done ? 0.35 : 0.5) * lod, p.x, p.y + rise, p.z, w * 0.7,
                    1.5, rng, 0.0, 0.07, 0.0);
        }
    }

    /** Ash in the air closes the view in and greys it: what standing under an eruption's fallout looks like. */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (fog < 0.01 || event.getCamera().getFluidInCamera() != FogType.NONE) return;
        float far = event.getFarPlaneDistance();
        float thick = Math.max(12.0f, far * 0.08f);
        float end = Mth.lerp((float) fog, far, thick);
        event.setFarPlaneDistance(end);
        event.setNearPlaneDistance(Mth.lerp((float) fog, event.getNearPlaneDistance(), 0.0f));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (fog < 0.01) return;
        float k = (float) fog * 0.85f;
        event.setRed(Mth.lerp(k, event.getRed(), 0.42f));
        event.setGreen(Mth.lerp(k, event.getGreen(), 0.40f));
        event.setBlue(Mth.lerp(k, event.getBlue(), 0.37f));
    }

    private static void emit(ClientLevel level, EruptionPacket p, Vec3 eye, double setting, RandomSource rng) {
        double sx = p.summit().getX() + 0.5, sy = p.summit().getY() + 1.0, sz = p.summit().getZ() + 0.5;
        double dist = Math.hypot(eye.x - sx, eye.z - sz);
        if (dist > 640) return;
        double lod = setting * (dist < 96 ? 1.0 : dist < 256 ? 0.55 : 0.3);
        double wx = p.windX(), wz = p.windZ();

        if (p.phase() == 3) {
            // Restless: a thin plume off the crater, and the fumaroles smoking harder than they do asleep.
            spawn(level, ModParticles.VOLCANIC_SMOKE.get(), 1.2 * lod, sx, sy + 1.5, sz, 0.8, 0.5, rng, wx * 0.02, 0.08, wz * 0.02);
            if (dist < 256) {
                for (long f : p.fumaroles()) {
                    BlockPos v = BlockPos.of(f);
                    spawn(level, ModParticles.VENT_SMOKE.get(), 1.2 * lod, v.getX() + 0.5, v.getY() + 2.2, v.getZ() + 0.5,
                            0.3, 0.2, rng, wx * 0.02, 0.2, wz * 0.02);
                }
            }
            return;
        }
        if (p.phase() == 1) {
            // Rumbling: a dark throat and no column yet.
            spawn(level, ModParticles.VOLCANIC_SMOKE.get(), 3 * lod, sx, sy + 1.5, sz, 0.8, 0.5, rng, wx * 0.02, 0.10, wz * 0.02);
            return;
        }

        // The throat, black and dense, so the column grows out of something already thick.
        spawn(level, ModParticles.VOLCANIC_SMOKE.get(), 6 * lod, sx, sy + 2.5, sz, 1.6, 1.2, rng, wx * 0.03, 0.22, wz * 0.03);

        int height = (int) Math.min(30 + p.magnitude() * 20, level.getMaxBuildHeight() - sy - 2);
        if (height >= 12) {
            int segments = 6;
            for (int i = 0; i < segments; i++) {
                double t = (i + 0.5) / segments;
                double lean = t * t * height * 0.45;
                double spread = 1.0 + t * height * 0.10;
                boolean upper = t > 0.45;
                double drift = 0.04 + 0.12 * t;
                spawn(level, upper ? ModParticles.ASH_CLOUD.get() : ModParticles.VOLCANIC_SMOKE.get(),
                        (upper ? 1.2 : 1.6) * lod,
                        sx + wx * lean, sy + t * height, sz + wz * lean, spread, height / (double) segments * 0.4,
                        rng, wx * drift, 0.02, wz * drift);
            }
        }

        if (dist < 256) {
            for (long f : p.fumaroles()) {
                BlockPos v = BlockPos.of(f);
                // The chimney is three blocks of stack, so the smoke leaves from above the cap.
                spawn(level, ModParticles.VENT_SMOKE.get(), 0.8 * lod, v.getX() + 0.5, v.getY() + 2.2, v.getZ() + 0.5,
                        0.3, 0.2, rng, wx * 0.02, 0.18, wz * 0.02);
            }
        }

        if (p.ashfall()) {
            // Ash in the air around the viewer, heaviest in the downwind lobe the deposit follows.
            double reach = Math.min(40 + p.magnitude() * 8, 160);
            if (dist <= reach + 32) {
                double align = dist < 1 ? 1.0 : ((eye.x - sx) / dist) * wx + ((eye.z - sz) / dist) * wz;
                spawn(level, ModParticles.ASH_FLAKE.get(), 4.0 * setting * (0.25 + 0.75 * (align + 1.0) * 0.5),
                        eye.x, eye.y + 9, eye.z, 12.0, 5.0, rng, wx * 0.05, -0.03, wz * 0.05);
            }
        }
    }

    /** {@code expected} particles on average - fractions carry over as a chance of one more. */
    private static void spawn(ClientLevel level, ParticleOptions type, double expected,
                              double x, double y, double z, double spreadXZ, double spreadY,
                              RandomSource rng, double vx, double vy, double vz) {
        int n = (int) expected;
        if (rng.nextDouble() < expected - n) n++;
        for (int i = 0; i < n; i++) {
            level.addParticle(type, true,
                    x + rng.nextGaussian() * spreadXZ, y + rng.nextGaussian() * spreadY, z + rng.nextGaussian() * spreadXZ,
                    vx + rng.nextGaussian() * 0.01, vy + rng.nextGaussian() * 0.01, vz + rng.nextGaussian() * 0.01);
        }
    }
}
