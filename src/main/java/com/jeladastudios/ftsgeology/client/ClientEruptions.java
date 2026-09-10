package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.EruptionPacket;
import com.jeladastudios.ftsgeology.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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

    public static void update(EruptionPacket p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (p.phase() == 0) ACTIVE.remove(p.summit());
        else ACTIVE.put(p.summit(), new Active(p, level.getGameTime() + GRACE_TICKS));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            ACTIVE.clear();   // nothing carries over into the next world
            return;
        }
        if (mc.isPaused() || ACTIVE.isEmpty()) return;
        long now = level.getGameTime();
        ACTIVE.values().removeIf(a -> now > a.expires());
        double setting = switch (mc.options.particles().get()) {
            case ALL -> 1.0;
            case DECREASED -> 0.45;
            case MINIMAL -> 0.15;
        };
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        for (Active a : ACTIVE.values()) emit(level, a.state(), eye, setting, level.random);
    }

    private static void emit(ClientLevel level, EruptionPacket p, Vec3 eye, double setting, RandomSource rng) {
        double sx = p.summit().getX() + 0.5, sy = p.summit().getY() + 1.0, sz = p.summit().getZ() + 0.5;
        double dist = Math.hypot(eye.x - sx, eye.z - sz);
        if (dist > 640) return;
        double lod = setting * (dist < 96 ? 1.0 : dist < 256 ? 0.55 : 0.3);
        double wx = p.windX(), wz = p.windZ();

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
