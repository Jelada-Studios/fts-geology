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

    private static volatile float targetRain, targetThunder;
    private static float rain, thunder, oRain, oThunder;
    private static volatile long heard;

    public static void set(LocalWeatherPacket p) {
        if (heard == 0) {
            rain = oRain = p.rain();
            thunder = oThunder = p.thunder();
        }
        targetRain = p.rain();
        targetThunder = p.thunder();
        heard = System.currentTimeMillis();
    }

    /** Whether the server tells this client its own weather: a server with regional rain, heard from lately. */
    public static boolean active() {
        return heard != 0 && System.currentTimeMillis() - heard < 10000;
    }

    /** Eases the weather drawn towards the weather told, a tick at a time, as vanilla eases its rain in and out. */
    public static void tick() {
        oRain = rain;
        oThunder = thunder;
        rain += Mth.clamp(targetRain - rain, -0.01f, 0.01f);
        thunder += Mth.clamp(targetThunder - thunder, -0.01f, 0.01f);
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
    }

    // === The server: the chunk being ticked =====================================

    /** Rain and thunder over the chunk the server thread is ticking now, or null outside that. */
    public static final ThreadLocal<float[]> CHUNK = new ThreadLocal<>();
}
