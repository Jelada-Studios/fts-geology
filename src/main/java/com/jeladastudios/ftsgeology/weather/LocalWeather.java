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

    private static volatile float targetRain, targetThunder, targetWindX, targetWindZ;
    private static float rain, thunder, oRain, oThunder, windX, windZ;
    private static volatile long heard;

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

    public static float rain(float partial) {
        return Mth.lerp(partial, oRain, rain);
    }

    public static float thunder(float partial) {
        return Mth.lerp(partial, oThunder, thunder);
    }

    /** Forgets the server's weather: a new world, or a server without it. */
    public static void reset() {
        heard = 0;
        rain = oRain = thunder = oThunder = targetRain = targetThunder = 0;
        windX = windZ = targetWindX = targetWindZ = 0;
    }

    // === The server: the chunk being ticked =====================================

    /** Rain and thunder over the chunk the server thread is ticking now, or null outside that. */
    public static final ThreadLocal<float[]> CHUNK = new ThreadLocal<>();
}
