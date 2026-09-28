package com.jeladastudios.ftsgeology.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;

/**
 * Whether ground may be read without the server waiting for it. {@code Level.hasChunk} and {@code isLoaded} answer
 * whether a chunk is meant to be loaded -- it has a ticket -- not whether it has finished loading, and reading a chunk
 * still on its way in makes the server thread stop and wait for it. At the edge of the ground round a moving player
 * that is most chunks, and a round of hot springs being laid there held a live server for a sixth of its time. On the
 * server thread a chunk counts only once it is in memory and finished; elsewhere the ticket is all there is to go on.
 */
public final class Loaded {

    private Loaded() {}

    public static boolean chunk(LevelReader level, int cx, int cz) {
        if (level instanceof ServerLevel server && server.getServer().isSameThread()) {
            return server.getChunkSource().getChunkNow(cx, cz) != null;
        }
        return level.hasChunk(cx, cz);
    }

    public static boolean at(LevelReader level, BlockPos pos) {
        return chunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean at(LevelReader level, int x, int z) {
        return chunk(level, x >> 4, z >> 4);
    }
}
