package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.SkyPacket;
import com.jeladastudios.ftsgeology.weather.LocalWeather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The sky over the player as the server tells it (see {@link SkyPacket}): the storms within sight and the highs and lows
 * round them, moved on between the server's word; how much of the sky is cloud at a place, how dark and stormy, how
 * high it towers; and the lightning flickering inside the thunderclouds, with its thunder coming after.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientSky {

    private ClientSky() {}

    private static volatile SkyPacket last;
    /** How far the cloud field has drifted on the wind aloft, blocks. */
    private static double driftX, driftZ;
    private static final List<Flash> FLASHES = new ArrayList<>();
    private static final List<Thunder> THUNDER = new ArrayList<>();

    public static void set(SkyPacket p) {
        last = p;
    }

    /** Whether the server tells this client its sky. */
    static boolean ready() {
        return last != null && LocalWeather.active();
    }

    static double driftX(float partial) {
        return driftX + windAloft()[0] * partial / 20.0;
    }

    static double driftZ(float partial) {
        return driftZ + windAloft()[1] * partial / 20.0;
    }

    /** The wind the clouds ride, blocks a second: the ground's wind, stronger aloft. */
    private static float[] windAloft() {
        return new float[]{LocalWeather.windX() * 1.6f + 0.4f, LocalWeather.windZ() * 1.6f};
    }

    /**
     * The sky at a place and time: {@code out[0]} how much of it is cloud, 0 to 1; {@code out[1]} how dark and heavy the
     * cloud, 0 fair-weather white to 1 a storm's; {@code out[2]} how high it towers, 1 a thunderstorm's anvil;
     * {@code out[3]} how hard it rains under it, 0 to 1, as the server's storms rain (without their bands).
     */
    static void sample(double x, double z, long when, float[] out) {
        SkyPacket p = last;
        float cover = 0.22f, dark = 0, tower = 0, rain = 0;
        if (p != null) {
            long dt = when - p.time();
            float[] sy = p.systems();
            for (int i = 0; i + SkyPacket.SYSTEM <= sy.length; i += SkyPacket.SYSTEM) {
                double cx = sy[i] + sy[i + 4] * dt, cz = sy[i + 1] + sy[i + 5] * dt, s = sy[i + 2] * 0.5;
                double g = Math.exp(-((x - cx) * (x - cx) + (z - cz) * (z - cz)) / (2 * s * s));
                float depth = sy[i + 3];
                // A low's rising air is a grey deck, a high's sinking air clears it to a few fair-weather clouds.
                if (depth < 0) {
                    cover += (float) (0.5 * g * Math.min(1, -depth / 15));
                    dark += (float) (0.3 * g * Math.min(1, -depth / 20));
                } else {
                    cover -= (float) (0.18 * g * Math.min(1, depth / 12));
                }
            }
            float[] st = p.storms();
            for (int i = 0; i + SkyPacket.STORM <= st.length; i += SkyPacket.STORM) {
                double cx = st[i] + st[i + 4] * dt, cz = st[i + 1] + st[i + 5] * dt, r = st[i + 2] * 1.35;
                double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                if (d >= r) continue;
                float e = smooth((float) (1 - d / r) * 2f);
                float s = st[i + 3];
                cover = Math.max(cover, 0.55f + 0.45f * e * Math.min(1f, s / 0.5f));
                dark = Math.max(dark, e * Math.min(1f, s / 0.7f));
                if (st[i + 6] > 0) tower = Math.max(tower, e * Math.min(1f, s / 0.6f));
                rain = Math.max(rain, s * smooth((float) (1 - d / st[i + 2]) * 1.8f));
            }
        }
        // Rain falls from a closed deck: wherever it rains in earnest the sky is shut, broken only at the rain's edge,
        // where a drizzle still lets a little through. Left at nine tenths or so, a steady rain fell through gaps.
        if (rain > 0.02f) cover = Math.max(cover, 0.9f + 0.1f * Math.min(1f, rain / 0.25f));
        out[0] = Mth.clamp(cover, 0.04f, rain > 0.2f ? 1f : 0.97f);
        out[1] = Mth.clamp(dark, 0f, 1f);
        out[2] = tower;
        if (out.length > 3) out[3] = rain;
    }

    // === Under, in or over the cloud ===============================================

    private static final float[] DECK = new float[2];

    /**
     * How much of the rain reaches an eye at this height against the cloud over it, as drawn: all of it under the
     * cloud's underside, some in the mist inside it, fading out over the six blocks above its top, where a mountain stands
     * clear over a sea of cloud. All of it where this mod's clouds are not drawn.
     */
    static float overhead(ClientLevel level, double x, double eyeY, double z) {
        if (!com.jeladastudios.ftsgeology.config.ClientConfig.STORM_CLOUDS.get()) return 1f;
        if (!CloudRenderer.deckAt(level, x, z, 1f, DECK)) return 1f;
        float base = DECK[0], top = DECK[1];
        if (eyeY <= base) return 1f;
        if (eyeY <= top) return 0.6f;
        return 0.6f * Mth.clamp(1f - (float) (eyeY - top) / 6f, 0f, 1f);
    }

    /** The underside of the cloud over a place, in world height, or NaN where the sky is clear or the clouds not ours. */
    static float deckBase(ClientLevel level, double x, double z) {
        if (!com.jeladastudios.ftsgeology.config.ClientConfig.STORM_CLOUDS.get()) return Float.NaN;
        return CloudRenderer.deckAt(level, x, z, 1f, DECK) ? DECK[0] : Float.NaN;
    }

    /** How deep in a cloud an eye is, 0 outside to 1 a few blocks in: for the mist. */
    static float inCloud(ClientLevel level, double x, double eyeY, double z) {
        if (!com.jeladastudios.ftsgeology.config.ClientConfig.STORM_CLOUDS.get()) return 0f;
        if (!CloudRenderer.deckAt(level, x, z, 1f, DECK)) return 0f;
        float in = (float) Math.min(eyeY - DECK[0], DECK[1] - eyeY);
        return Mth.clamp(in / 3f, 0f, 1f);
    }

    // === Lightning among the clouds ===============================================

    /** A flash inside a cloud: where, how bright, and its flicker's age. */
    record Flash(double x, double y, double z, float strength, int[] age, int length) {
        /** How bright now, 0 to 1: a flicker, two or three pulses. */
        float now() {
            int a = age[0];
            if (a >= length) return 0;
            float pulse = (a % 3 == 0 ? 1f : a % 3 == 1 ? 0.35f : 0.7f);
            return strength * pulse * (1f - a / (float) length * 0.6f);
        }
    }

    private record Thunder(double x, double y, double z, int[] delay, float volume, float pitch) {}

    static List<Flash> flashes() {
        return FLASHES;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || mc.isPaused()) return;
        float[] w = windAloft();
        driftX += w[0] / 20.0;
        driftZ += w[1] / 20.0;
        for (Iterator<Flash> it = FLASHES.iterator(); it.hasNext(); ) {
            Flash f = it.next();
            if (++f.age()[0] > f.length()) it.remove();
        }
        for (Iterator<Thunder> it = THUNDER.iterator(); it.hasNext(); ) {
            Thunder t = it.next();
            if (--t.delay()[0] > 0) continue;
            // Heard from its direction, a little way off: from where it was, a sound fades out long before a mile.
            double dx = t.x() - player.getX(), dy = t.y() - player.getY(), dz = t.z() - player.getZ();
            double len = Math.max(1e-3, Math.sqrt(dx * dx + dy * dy + dz * dz));
            level.playLocalSound(player.getX() + dx / len * 12, player.getY() + dy / len * 12, player.getZ() + dz / len * 12,
                    SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, t.volume(), t.pitch(), false);
            it.remove();
        }
        SkyPacket p = last;
        if (p == null || !ready()) return;
        RandomSource rnd = level.random;
        long dt = level.getGameTime() - p.time();
        float[] st = p.storms();
        float cloudBase = level.effects().getCloudHeight();
        if (Float.isNaN(cloudBase)) return;
        for (int i = 0; i + SkyPacket.STORM <= st.length; i += SkyPacket.STORM) {
            if (st[i + 6] <= 0) continue;
            double cx = st[i] + st[i + 4] * dt, cz = st[i + 1] + st[i + 5] * dt;
            double d = Math.hypot(cx - player.getX(), cz - player.getZ());
            if (d > st[i + 2] + 1500) continue;
            // A thunderstorm flickers inside every several seconds, the heavier the more.
            float s = st[i + 3];
            if (rnd.nextFloat() > 0.008f * Math.min(1f, s / 0.6f)) continue;
            double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * st[i + 2] * 0.7;
            double fx = cx + Math.cos(a) * r, fz = cz + Math.sin(a) * r, fy = cloudBase + 8 + rnd.nextDouble() * 30;
            float strength = 0.55f + 0.45f * rnd.nextFloat();
            FLASHES.add(new Flash(fx, fy, fz, strength, new int[]{0}, 4 + rnd.nextInt(6)));
            double far = Math.sqrt((fx - player.getX()) * (fx - player.getX()) + (fz - player.getZ()) * (fz - player.getZ())
                    + (fy - player.getY()) * (fy - player.getY()));
            // Near, the whole sky lights up for a moment.
            if (far < 450 && strength > 0.7f) level.setSkyFlashTime(2);
            // Its thunder after, as far behind as sound takes, rolling low from afar.
            if (far < 1600) {
                float volume = (float) (0.9 * (1 - far / 1600) + 0.1) * strength;
                THUNDER.add(new Thunder(fx, fy, fz, new int[]{(int) (far / 343.0 * 20)}, Math.min(1f, volume), (float) (0.9 - far / 1600 * 0.35)));
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        last = null;
        FLASHES.clear();
        THUNDER.clear();
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3 - 2 * t);
    }
}
