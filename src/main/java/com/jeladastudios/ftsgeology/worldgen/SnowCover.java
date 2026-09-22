package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.concurrent.atomic.LongAdder;

/**
 * The snow that lies on a mountain above the snow line, in the mod's own world types.
 *
 * <p>Vanilla gives a cold mountain a single thin layer of snow, which lies on a cliff face as readily as on a
 * shoulder. On a real mountain the snowpack is metres deep on the gentle ground above the snow line and does not
 * hold on steep rock at all. So where the ground is cold enough to snow and stands over the snow line, a column
 * whose ground is level with its neighbours' to within a block takes a block of snow on top, two a good way further
 * up; a steep column keeps its bare rock. Run after vanilla's freezing, so its thin layer lies over the top.</p>
 */
public final class SnowCover {

    private SnowCover() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** The snow line in the normal world's layout, and how much higher the second block starts. */
    private static final int SNOW_LINE = 165, DEEPER = 40;
    /** The most a column may differ from a neighbour and still count as gentle ground. */
    private static final int GENTLE = 1;

    private static final LongAdder COLUMNS = new LongAdder(), SNOWED = new LongAdder(), STEEP = new LongAdder();

    public static void generate(WorldGenLevel level, ChunkPos cp) {
        if (!GeologyWorld.isOwn(level.getLevel())) return;
        double h = TerrainContext.params().horizontal();
        int line = (int) Math.round(SNOW_LINE * (1.0 + (h - 1.0) / 1.5));
        int deeper = (int) Math.round(DEEPER * h);
        int[][] top = new int[16][16];
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                top[dx][dz] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        cp.getMinBlockX() + dx, cp.getMinBlockZ() + dz) - 1;
            }
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int g = top[dx][dz];
                if (g < line) continue;
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                BlockState ground = level.getBlockState(at.set(x, g, z));
                if (!natural(ground)) continue;
                if (!level.getBiome(at.set(x, g + 1, z)).value().coldEnoughToSnow(at)) continue;
                COLUMNS.increment();
                // Steepness inside the chunk only; at its edge the one neighbour inside counts twice.
                int rise = 0;
                rise = Math.max(rise, Math.abs(g - top[dx > 0 ? dx - 1 : dx + 1][dz]));
                rise = Math.max(rise, Math.abs(g - top[dx < 15 ? dx + 1 : dx - 1][dz]));
                rise = Math.max(rise, Math.abs(g - top[dx][dz > 0 ? dz - 1 : dz + 1]));
                rise = Math.max(rise, Math.abs(g - top[dx][dz < 15 ? dz + 1 : dz - 1]));
                if (rise > GENTLE) {
                    STEEP.increment();
                    continue;
                }
                int depth = g >= line + deeper ? 2 : 1;
                for (int k = 0; k < depth; k++) {
                    BlockState s = level.getBlockState(at.set(x, g - k, z));
                    if (!natural(s)) break;
                    level.setBlock(at, Blocks.SNOW_BLOCK.defaultBlockState(), FLAGS);
                }
                SNOWED.increment();
            }
        }
    }

    /** Ground snow may lie on: rock, soil and gravel the world made, not water, ice, wood or anything built. */
    private static boolean natural(BlockState s) {
        if (EruptionHandler.isPlayerPlaced(s)) return false;
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE);
    }

    /** Columns over the snow line seen, how many took snow and how many were too steep, for a test to read. */
    public static String summary() {
        return String.format(java.util.Locale.ROOT, "snow: %d columns over the snow line, %d snowed, %d steep",
                COLUMNS.sum(), SNOWED.sum(), STEEP.sum());
    }
}
