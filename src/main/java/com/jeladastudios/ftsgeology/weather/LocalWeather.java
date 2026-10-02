package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.network.LocalWeatherPacket;
import net.minecraft.util.Mth;

/**
 * The weather at one place, where the world's one flag would otherwise answer: on the client, the player's own (sent by
 * {@link Storms} every two seconds, eased in as vanilla eases its rain); on the server, the chunk being ticked, while its
 * snow, cauldrons and lightning are worked out. Nothing here loads client classes.
 */
public final class LocalWeather {

    private LocalWeather() {}

    // === The client: the player's own weather ===================================

    private static volatile float targetRain, targetThunder, targetWindX, targetWindZ, targetFog;
    private static float rain, thunder, oRain, oThunder, windX, windZ, fog;
    private static volatile long heard;
    /**
     * How much of the storm's rain reaches the player where they stand against its cloud deck, eased: all of it under
     * the deck, some in the mist inside it, none above it, where the sky is clear (the client tells it each tick).
     */
    private static float overhead = 1f, oOverhead = 1f, targetOverhead = 1f;

    public static void set(LocalWeatherPacket p) {
        if (heard == 0) {
            rain = oRain = p.rain();
            thunder = oThunder = p.thunder();
            windX = p.windX();
            windZ = p.windZ();
        }
        targetRain = p.rain();
        targetThunder = p.thunder();
        targetWindX = p.windX();
        targetWindZ = p.windZ();
        targetFog = p.fog();
        heard = System.currentTimeMillis();
    }

    /**
     * Whether the server tells this client its own weather: a server with regional rain, heard from lately. Half a
     * minute's grace: a server stalled for a few seconds let the rain fall back to the world's one weather and back.
     */
    public static boolean active() {
        return heard != 0 && System.currentTimeMillis() - heard < 30000;
    }

    /**
     * Eases the weather drawn towards the weather told, a tick at a time: rain comes on over some twenty seconds from
     * dry to a downpour and goes off a little slower, as a shower's edge passes; eased at vanilla's pace, the rain and
     * its sound came and went in a second or two.
     */
    public static void tick() {
        oRain = rain;
        oThunder = thunder;
        rain += Mth.clamp(targetRain - rain, -0.002f, 0.0025f);
        thunder += Mth.clamp(targetThunder - thunder, -0.003f, 0.004f);
        windX += (targetWindX - windX) * 0.01f;
        windZ += (targetWindZ - windZ) * 0.01f;
        // A fog gathers over some ten seconds and lifts as slowly (the fog told is itself slow to come).
        fog += Mth.clamp(targetFog - fog, -0.004f, 0.004f);
        oOverhead = overhead;
        overhead += Mth.clamp(targetOverhead - overhead, -0.02f, 0.02f);
    }

    /** Where the player stands against the cloud deck: 1 under it, less in it, 0 above it (see {@link #overhead}). */
    public static void overhead(float target) {
        targetOverhead = Mth.clamp(target, 0f, 1f);
    }

    /** How hard it is to rain here now, as the server tells it, before the easing: what the rain is coming to. */
    public static float rainComing() {
        return targetRain;
    }

    /** The wind where the player stands, blocks a second towards +x and +z. */
    public static float windX() {
        return windX;
    }

    public static float windZ() {
        return windZ;
    }

    /** The rain where the player is: the storm's, as much of it as reaches them under, in or over its cloud deck. */
    public static float rain(float partial) {
        return Mth.lerp(partial, oRain, rain) * Mth.lerp(partial, oOverhead, overhead);
    }

    public static float thunder(float partial) {
        return Mth.lerp(partial, oThunder, thunder) * Mth.lerp(partial, oOverhead, overhead);
    }

    /** Forgets the server's weather: a new world, or a server without it. */
    public static void reset() {
        heard = 0;
        overhead = oOverhead = targetOverhead = 1f;
        rain = oRain = thunder = oThunder = targetRain = targetThunder = 0;
        windX = windZ = targetWindX = targetWindZ = 0;
        fog = targetFog = 0;
    }

    /** How thick a ground fog lies round the player now, 0 to 1, eased; 0 without the server's weather. */
    public static float fog() {
        return active() ? fog : 0f;
    }

    // === The server: the chunk being ticked =====================================

    /** Rain and thunder over the chunk the server thread is ticking now, or null outside that. */
    public static final ThreadLocal<float[]> CHUNK = new ThreadLocal<>();
}
