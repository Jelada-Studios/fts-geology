package com.jeladastudios.ftsgeology.util;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;

/**
 * The mod's running counts and events, logged only when {@code diagnostics.verboseLog} is on. World generation alone
 * wrote a line for every hot spring, every hundred chunks of river and every ten seconds of retrogen, which is what a
 * test reads and what a player's log should not be full of.
 */
public final class Diagnostics {

    private Diagnostics() {}

    /** Whether the verbose log is on. False before the server's config is read. */
    public static boolean on() {
        try {
            return GeyserConfig.VERBOSE_LOG.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** Logs one line at info, if the verbose log is on. */
    public static void info(String format, Object... args) {
        if (on()) GeysersMod.LOGGER.info(format, args);
    }
}
