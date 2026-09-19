package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.material.Fluids;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Water in the rivers and mountain streams above the sea. The mod's own world type cuts a channel along vanilla's river
 * lines and along the floor of a belt's V valleys ({@link TerrainFields#riverChannel}), six blocks into the ground or down
 * to a bed that comes down from the mountains to the sea; vanilla fills only what lies under the sea's level, so the
 * channel above it would be dry. This puts two blocks of water over the channel's floor: still pools where the bed is
 * flat, a stream running down its course where it follows the ground, and the water over each step settles into a
 * rapid once the chunk is loaded.
 *
 * <p>The channel is read at the corners of the 4-block cells and interpolated between them, the way the terrain data
 * reads it, so the water lies where the ground was cut. A pool has to be walled in. A neighbour that is neither wet, nor as high as the water, nor already holding water lower
 * down is a gap: up to {@link #PLUG} blocks deep it is filled as a bank, the column's owner filling it; deeper, the
 * pool is dropped, and the check repeats since its neighbours may now be open. It looks eight blocks past the chunk,
 * so two chunks sharing a border come to the same answer for the columns along it. Runs with the deep geology, before
 * trees are planted, so none grows in the river.</p>
 */
public final class RiverWater {

    private RiverWater() {}

    private static final int MARGIN = 8, SIZE = 16 + 2 * MARGIN, CELLS = SIZE / 4 + 1;
    /** The deepest gap in a pool's wall that is filled as a bank rather than draining the pool. */
    private static final int PLUG = 3;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    private static final LongAdder CANDIDATES = new LongAdder(), KEPT = new LongAdder(), BLOCKS = new LongAdder(),
            PLUGGED = new LongAdder();
    private static final AtomicLong CHUNKS = new AtomicLong();

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel server = level.getLevel();
        if (TfcCompat.active() || !GeologyWorld.isOwn(server)) return 0;
        long seed = TerrainContext.seed();
        GeologyParams p = TerrainContext.params();
        int sea = level.getSeaLevel();
        DensityFunction ridges = server.getChunkSource().randomState().router().ridges();
        int x0 = cp.getMinBlockX() - MARGIN, z0 = cp.getMinBlockZ() - MARGIN;

        double[] channel = new double[CELLS * CELLS];
        boolean any = false;
        for (int i = 0; i < CELLS; i++) {
            for (int j = 0; j < CELLS; j++) {
                int x = x0 + 4 * i, z = z0 + 4 * j;
                double r = ridges.compute(new DensityFunction.SinglePointContext(x, 0, z));
                channel[i * CELLS + j] = TerrainFields.riverChannel(seed, p, x, z, r);
                any |= channel[i * CELLS + j] > 0.0;
            }
        }
        if (!any) return 0;

        // Every column of the window: its water top (MIN when it takes none) and its ground.
        int[] water = new int[SIZE * SIZE], ground = new int[SIZE * SIZE];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                int k = dx * SIZE + dz;
                water[k] = Integer.MIN_VALUE;
                ground[k] = TerrainProbe.groundY(level, x0 + dx, z0 + dz);
                if (ground[k] == Integer.MIN_VALUE) continue;
                // On the channel's floor, two blocks of water over its own ground: flat where the bed lies on its level, a
                // stream running down its course where it follows the ground. Under the sea's level vanilla fills it.
                if (lerp(channel, dx, dz) <= 0.0) continue;
                // Flats at the sea's level are the sea's: a river reaching them is vanilla's water under that level.
                if (ground[k] < sea) continue;
                int w = ground[k] + 2;
                // A column already holding water, the sea in a bay or a lake, is left as it is: raised to the river's
                // level it was the sea standing two blocks over its own shore. A pool beside it runs into it instead.
                if (!level.getFluidState(at.set(x0 + dx, ground[k] + 1, z0 + dz)).isEmpty()) continue;
                water[k] = w;
                if (dx >= MARGIN && dx < MARGIN + 16 && dz >= MARGIN && dz < MARGIN + 16) CANDIDATES.increment();
            }
        }

        // Keep only walled-in pools, repeating while columns drop out. A shallow gap does not count: it becomes a bank.
        boolean changed = true;
        for (int pass = 0; changed && pass < MARGIN + 2; pass++) {
            changed = false;
            for (int dx = 0; dx < SIZE; dx++) {
                for (int dz = 0; dz < SIZE; dz++) {
                    int k = dx * SIZE + dz, w = water[k];
                    if (w == Integer.MIN_VALUE) continue;
                    for (int d = 0; d < 4; d++) {
                        if (!sealed(level, x0, z0, water, ground, dx + DX[d], dz + DZ[d], w)) {
                            water[k] = Integer.MIN_VALUE;
                            changed = true;
                            break;
                        }
                    }
                }
            }
        }

        // Banks: every shallow gap beside a pool that stayed, raised to the highest pool beside it.
        int[] bank = new int[SIZE * SIZE];
        java.util.Arrays.fill(bank, Integer.MIN_VALUE);
        for (int dx = 1; dx < SIZE - 1; dx++) {
            for (int dz = 1; dz < SIZE - 1; dz++) {
                int w = water[dx * SIZE + dz];
                if (w == Integer.MIN_VALUE) continue;
                for (int d = 0; d < 4; d++) {
                    int nx = dx + DX[d], nz = dz + DZ[d], n = nx * SIZE + nz;
                    if (water[n] == Integer.MIN_VALUE && ground[n] != Integer.MIN_VALUE && ground[n] < w
                            && !holdsWater(level, x0 + nx, w, z0 + nz)) {
                        bank[n] = Math.max(bank[n], w);
                    }
                }
            }
        }

        int placed = 0;
        for (int dx = MARGIN; dx < MARGIN + 16; dx++) {
            for (int dz = MARGIN; dz < MARGIN + 16; dz++) {
                int k = dx * SIZE + dz, x = x0 + dx, z = z0 + dz;
                if (bank[k] != Integer.MIN_VALUE && ground[k] != Integer.MIN_VALUE && bank[k] > ground[k]) {
                    BlockState rock = level.getBlockState(at.set(x, ground[k], z));
                    BlockState fill = rock.is(BlockTags.DIRT) || rock.isAir() ? Blocks.GRAVEL.defaultBlockState() : rock;
                    for (int y = ground[k] + 1; y <= bank[k]; y++) {
                        BlockState s = level.getBlockState(at.set(x, y, z));
                        if (s.isAir() || TerrainProbe.isVegetation(s)) level.setBlock(at, fill, FLAGS);
                    }
                    PLUGGED.increment();
                    continue;
                }
                int w = water[k];
                if (w == Integer.MIN_VALUE) continue;
                KEPT.increment();
                int g = ground[k];
                boolean wet = false;
                for (int y = g + 1; y <= w; y++) {
                    BlockState s = level.getBlockState(at.set(x, y, z));
                    if (!s.isAir() && !TerrainProbe.isVegetation(s)) continue;
                    level.setBlock(at, Blocks.WATER.defaultBlockState(), FLAGS);
                    placed++;
                    wet = true;
                }
                if (!wet) continue;
                // A river bed is gravel in the mountains and sand lower down, not the meadow the surface rules laid.
                BlockState bed = level.getBlockState(at.set(x, g, z));
                if (bed.is(BlockTags.DIRT)) {
                    level.setBlock(at, (w - sea > 12 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState(), FLAGS);
                }
                // Where the next pool down starts beside this one, the water runs over the step once it is loaded.
                for (int d = 0; d < 4; d++) {
                    int n = (dx + DX[d]) * SIZE + dz + DZ[d];
                    boolean lower = water[n] != Integer.MIN_VALUE ? water[n] < w : bank[n] < w && ground[n] != Integer.MIN_VALUE && ground[n] < w;
                    if (lower) {
                        level.scheduleTick(at.set(x, w, z), Fluids.WATER, 4);
                        break;
                    }
                }
            }
        }
        BLOCKS.add(placed);
        if (CHUNKS.incrementAndGet() % 500 == 0) {
            GeysersMod.LOGGER.info("River water over {} chunks: {} columns in a carved valley under the level, {} wet, {} banked, {} blocks",
                    CHUNKS.get(), CANDIDATES.sum(), KEPT.sum(), PLUGGED.sum(), BLOCKS.sum());
        }
        return placed;
    }

    /**
     * Whether a pool at level {@code w} is walled in on the side of window column (nx, nz): the neighbour is wet itself,
     * stands as high as the water, holds water lower down, or is a gap shallow enough to bank.
     */
    private static boolean sealed(WorldGenLevel level, int x0, int z0, int[] water, int[] ground, int nx, int nz, int w) {
        int x = x0 + nx, z = z0 + nz, g;
        if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) {
            g = TerrainProbe.groundY(level, x, z);
        } else {
            if (water[nx * SIZE + nz] != Integer.MIN_VALUE) return true;
            g = ground[nx * SIZE + nz];
        }
        if (g == Integer.MIN_VALUE || g >= w || w - g <= PLUG) return true;
        return holdsWater(level, x, w, z);
    }

    /** Water already standing a block or more below this level in the column, a pool this one can run into. */
    private static boolean holdsWater(WorldGenLevel level, int x, int w, int z) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = w; y >= w - 4; y--) {
            if (!level.getFluidState(at.set(x, y, z)).isEmpty()) return true;
            if (!level.getBlockState(at).isAir()) return false;
        }
        return false;
    }

    private static double lerp(double[] v, int dx, int dz) {
        int i = dx >> 2, j = dz >> 2;
        double fx = (dx & 3) / 4.0, fz = (dz & 3) / 4.0;
        double a = v[i * CELLS + j], b = v[(i + 1) * CELLS + j], c = v[i * CELLS + j + 1], d = v[(i + 1) * CELLS + j + 1];
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }
}
