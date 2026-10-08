package com.jeladastudios.ftsgeology.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;

/**
 * A biome looked up during world generation, wherever it is. On 1.21 a generating region throws where a feature asks
 * for the biome of a chunk further off than its step may read (two chunks or more), where 1.20 quietly asked the biome
 * source instead. Here the near chunks give their own biomes and the far ones the biome source's, as 1.20 did.
 */
public final class GenBiomes {

    private GenBiomes() {}

    public static Holder<Biome> at(LevelReader level, BlockPos pos) {
        if (!(level instanceof WorldGenRegion region)) return level.getBiome(pos);
        int cx = region.getCenter().x, cz = region.getCenter().z;
        return region.getBiomeManager().withDifferentSource((qx, qy, qz) -> {
            int sx = QuartPos.toSection(qx), sz = QuartPos.toSection(qz);
            return Math.max(Math.abs(sx - cx), Math.abs(sz - cz)) <= 1
                    ? region.getNoiseBiome(qx, qy, qz)
                    : region.getUncachedNoiseBiome(qx, qy, qz);
        }).getBiome(pos);
    }
}
