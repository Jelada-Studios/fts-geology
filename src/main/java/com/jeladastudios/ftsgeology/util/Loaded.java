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

    /**
     * Whether a block and the four beside it may be read: a block set at a chunk's edge updates its neighbours, and
     * one of them in a chunk still on its way in would hold the server up.
     */
    public static boolean around(LevelReader level, BlockPos pos) {
        int x = pos.getX(), z = pos.getZ();
        return chunk(level, x >> 4, z >> 4) && chunk(level, (x + 1) >> 4, z >> 4) && chunk(level, (x - 1) >> 4, z >> 4)
                && chunk(level, x >> 4, (z + 1) >> 4) && chunk(level, x >> 4, (z - 1) >> 4);
    }

    /**
     * The biome at a place without waiting for its chunk: read off the chunk where it is in, worked out from the biome
     * source where it is not. {@code Level.getBiome} on the server thread waits for a chunk that is on its way in.
     */
    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || at(level, pos)) return level.getBiome(pos);
        return server.getUncachedNoiseBiome(net.minecraft.core.QuartPos.fromBlock(pos.getX()), net.minecraft.core.QuartPos.fromBlock(pos.getY()),
                net.minecraft.core.QuartPos.fromBlock(pos.getZ()));
    }
}
