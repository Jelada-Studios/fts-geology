package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.worldgen.SnowCover;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Thin Air thins the air by the block height alone, set for the vanilla world's: clean up to 256. The tall world type
 * stands its mountains far higher over the same sea, and there the air ran short on every plateau. Heights above the
 * sea are handed to Thin Air shrunk by as much as the tall world's snow line stands higher over the sea, so its
 * settings mean the same ground in either world. Depths are left alone: the tall world is no deeper.
 */
public final class ThinAirHeights {

    private ThinAirHeights() {}

    private static volatile double stretch = 1.0;
    private static volatile int sea = 63;

    /** For the world about to start: only the overworld of the tall world type is stretched. */
    public static void open(boolean tall, double horizontal, int seaLevel) {
        sea = seaLevel;
        stretch = tall ? (SnowCover.lineAt(horizontal) - seaLevel) / (double) (SnowCover.lineAt(1.0) - seaLevel) : 1.0;
    }

    public static void close() {
        stretch = 1.0;
    }

    /** The height Thin Air should read for {@code y} in {@code dimension}. */
    public static int height(ResourceKey<Level> dimension, int y) {
        double s = stretch;
        if (s == 1.0 || y <= sea || !Level.OVERWORLD.equals(dimension)) return y;
        return sea + (int) Math.round((y - sea) / s);
    }
}
