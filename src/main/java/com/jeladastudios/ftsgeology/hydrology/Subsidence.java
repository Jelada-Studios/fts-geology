package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.quake.PlayerBuilt;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The ground settling over clay the wells have drawn the water out of, as the Konya plain does, and Mexico City.
 *
 * <p>Water in the pores of a clay holds part of the weight of the ground over it. Drawn down, it holds that much less,
 * and the clay squeezes: slowly, over years, as the water finds its way out of it, and for good -- the table coming back
 * does not lift the ground again. So the settling follows the deepest the water has ever been drawn down here, and the
 * thickness of soft ground under it: clay, mud, silt and soil settle; sand and gravel hardly, rock not at all. It comes
 * a block at a time, column by column in rings round the wells, the soil cracking first; what was built there stands
 * where it stood, as well casings stand out of the ground round a pumped field and houses on deep footings rise out of
 * a sinking city.</p>
 *
 * <p>Looked at with the ground's water, a chunk at a time ({@link SoilWater}); nothing is done where no well draws.</p>
 */
public final class Subsidence {

    private Subsidence() {}

    /** Drawdown, in blocks, below which the ground is not looked at. */
    private static final double MIN_DRAWN = 0.5;
    /** How deep under a cell the soft ground is counted, in blocks. */
    private static final int REACH = 24;
    /** Soft ground under a cell, in blocks, below which it does not settle a block for a player to see. */
    private static final int THIN = 4;
    /**
     * Blocks a cell settles, in the end, for each block the water was drawn down and each block of soft ground under it:
     * a soft lake clay's under the water's weight, some times a real one's: a field pumped for a few game weeks draws the
     * water down a block or two where a real plain took decades to lose tens of metres, and it should show.
     */
    private static final double PER_BLOCK = 0.04;
    /** How long the clay takes to squeeze out most of what it will, in the ground's hours (a month and a half). */
    private static final double TAU_HOURS = 24 * 45;
    /** How much of a block of settling has to come before the ground cracks over it. */
    private static final double CRACKS_AT = 0.5;

    private static long settled, cracked;

    /**
     * A chunk's cells brought on {@code hours}: the deepest drawdown yet taken from what the wells do now, each cell's
     * settling eased toward what that and its soft ground will come to, and any whole block of it laid down.
     */
    static void look(ServerLevel level, LevelChunk chunk, SoilWater.Cells c, double hours) {
        if (GeyserConfig.SUBSIDENCE.get() <= 0) return;
        if (c.lowered > c.deepest) c.deepest = c.lowered;
        if (c.deepest < MIN_DRAWN || hours <= 0) return;
        ChunkPos p = chunk.getPos();
        double ease = 1.0 - Math.exp(-hours / TAU_HOURS);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int i = 0; i < 16; i++) {
            int x = p.getMinBlockX() + (i & 3) * 4 + 2, z = p.getMinBlockZ() + (i >> 2) * 4 + 2;
            int g = TerrainProbe.groundY(level, x, z);
            if (g == Integer.MIN_VALUE) continue;
            int soft = soft(chunk, x, g, z, m);
            if (soft < THIN) continue;
            double target = PER_BLOCK * GeyserConfig.SUBSIDENCE.get() * soft * c.deepest;
            float was = c.compacted[i];
            if (was >= target) continue;
            float now = (float) (was + (target - was) * ease);
            c.compacted[i] = now;
            any = true;
            // The soil cracks as the first half block of each comes on, before any column of the cell goes down.
            if (was % 1.0 < CRACKS_AT && now % 1.0 >= CRACKS_AT) {
                cracks(level, chunk, p.getMinBlockX() + (i & 3) * 4, p.getMinBlockZ() + (i >> 2) * 4, m);
            }
        }
        if (!any) return;
        // Each column goes down as the settling under it, read between the cells' middles, comes to a whole block: a
        // bowl's rings, not a pavement of four-by-four slabs.
        int n = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double here = between(c.compacted, lx, lz);
                int col = lz * 16 + lx;
                if (here - c.settled[col] < 1.0) continue;
                if (settle(level, chunk, p.getMinBlockX() + lx, p.getMinBlockZ() + lz, m)) n++;
                // Kept as laid down even where the column could not go (built on): it is not tried again for it.
                c.settled[col]++;
            }
        }
        if (n > 0) {
            settled += n;
            level.playSound(null, p.getMiddleBlockX(), TerrainProbe.groundY(level, p.getMiddleBlockX(), p.getMiddleBlockZ()),
                    p.getMiddleBlockZ(), SoundEvents.GRAVEL_HIT, SoundSource.BLOCKS, 0.8F, 0.5F);
            com.jeladastudios.ftsgeology.util.Diagnostics.info("subsidence: {} columns of the ground at {} {} settle a block",
                    n, p.getMiddleBlockX(), p.getMiddleBlockZ());
        }
    }

    /** The settling at a column, read between the middles of the four cells round it; the edge cells held out to the chunk's edge. */
    private static double between(float[] cells, int lx, int lz) {
        double fx = Math.max(0, Math.min(3, (lx - 1.5) / 4.0)), fz = Math.max(0, Math.min(3, (lz - 1.5) / 4.0));
        int x0 = (int) Math.min(2, Math.floor(fx)), z0 = (int) Math.min(2, Math.floor(fz));
        double tx = fx - x0, tz = fz - z0;
        double a = cells[z0 * 4 + x0], b = cells[z0 * 4 + x0 + 1], c = cells[(z0 + 1) * 4 + x0], d = cells[(z0 + 1) * 4 + x0 + 1];
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    /** Blocks of soft ground under a column, down to {@link #REACH}: what squeezes when the water in it is drawn. */
    private static int soft(LevelChunk chunk, int x, int g, int z, BlockPos.MutableBlockPos m) {
        int n = 0;
        for (int y = g; y > g - REACH; y--) {
            if (compressible(chunk.getBlockState(m.set(x, y, z)))) n++;
        }
        return n;
    }

    static boolean compressible(BlockState s) {
        if (s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(BlockTags.DIRT)) return true;
        if (s.is(BlockTags.TERRACOTTA)) return false;
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.contains("brick") || path.contains("tile") || path.contains("polished")) return false;
        return path.contains("clay") || path.contains("silt") || path.contains("loam") || path.contains("mud")
                || path.contains("peat") || path.endsWith("_dirt");
    }

    /**
     * One block of settling in a column: a block of the soil under its top goes, and the top and what grows on it come
     * down onto what is left. A column with anything built on it, a tree, water, or no soil under its top stays as it was.
     */
    private static boolean settle(ServerLevel level, LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos m) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;
        BlockState top = chunk.getBlockState(m.set(x, g, z)), under = chunk.getBlockState(m.set(x, g - 1, z));
        if (!natural(level, x, g, z, top) || !natural(level, x, g - 1, z, under) || !compressible(under)) return false;
        // What stands on it: plants and snow only, two blocks at most; anything else and the column stays.
        int cover = 0;
        boolean clear = true;
        for (int y = g + 1; y <= g + 3; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir()) break;
            if (!TerrainProbe.isVegetation(s) || TerrainProbe.isTreePart(s) || y == g + 3) {
                clear = false;
                break;
            }
            cover++;
        }
        if (!clear) return false;
        // Bottom up, so nothing is ever left without what it stands on.
        for (int y = g - 1; y <= g + cover; y++) {
            level.setBlock(m.set(x, y, z), chunk.getBlockState(new BlockPos(x, y + 1, z)),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        level.setBlock(m.set(x, g + cover + 1, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        return true;
    }

    /** The soil over a cell about to settle cracks: a few of its grass and dirt blocks go coarse, in a line. */
    private static void cracks(ServerLevel level, LevelChunk chunk, int x0, int z0, BlockPos.MutableBlockPos m) {
        boolean alongX = level.random.nextBoolean();
        int line = level.random.nextInt(4), n = 0;
        for (int i = 0; i < 4; i++) {
            if (level.random.nextInt(4) == 0) continue;
            int x = x0 + (alongX ? i : line), z = z0 + (alongX ? line : i);
            int g = TerrainProbe.groundY(level, x, z);
            if (g == Integer.MIN_VALUE) continue;
            BlockState s = chunk.getBlockState(m.set(x, g, z));
            if (!natural(level, x, g, z, s) || !s.is(BlockTags.DIRT) || s.is(Blocks.COARSE_DIRT)) continue;
            BlockState over = chunk.getBlockState(m.set(x, g + 1, z));
            if (!over.isAir() && !TerrainProbe.isVegetation(over)) continue;
            if (!over.isAir()) level.setBlock(m.set(x, g + 1, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(m.set(x, g, z), Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
            n++;
        }
        if (n > 0) cracked++;
    }

    /** Ground nobody built: not placed by a player, not holding contents, not a liquid. */
    private static boolean natural(ServerLevel level, int x, int y, int z, BlockState s) {
        if (s.isAir() || s.hasBlockEntity() || !s.getFluidState().isEmpty() || EruptionHandler.isPlayerPlaced(s)) return false;
        return !PlayerBuilt.inChunk(level, x >> 4, z >> 4).contains(BlockPos.asLong(x, y, z));
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "subsidence: %d columns settled a block, %d cells cracked", settled, cracked);
    }

    public static void clear() {
        settled = cracked = 0;
    }
}
