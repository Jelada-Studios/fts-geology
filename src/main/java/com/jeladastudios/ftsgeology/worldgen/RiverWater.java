package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Still water in the rivers above the sea. {@link RiverNetwork} traces the rivers down the raw ground and the offset
 * cuts their channels; this fills the pools, which the trace has already made flat and stepped one below the next,
 * with a rib of rock between them.
 *
 * <p>Nothing here flows. A column takes water only where the pool is walled in beside it — every neighbour either
 * stands as high as the surface or is a column of the same pool that is walled in itself — and only where the block
 * under the water is solid. The test is the same wherever it is asked from, so two chunks sharing a border answer
 * the same for the columns along it. With no flowing water and none of it over a hole, a fluid mod that moves water
 * about finds every pool already at rest.</p>
 */
public final class RiverWater {

    private RiverWater() {}

    /** How far past the chunk the window reaches: two blocks for the test itself, and room to spare. */
    private static final int MARGIN = 4, SIZE = 16 + 2 * MARGIN;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    private static final LongAdder CANDIDATES = new LongAdder(), KEPT = new LongAdder(), BLOCKS = new LongAdder(),
            DROPPED = new LongAdder();
    private static final AtomicLong CHUNKS = new AtomicLong();

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel server = level.getLevel();
        if (TfcCompat.active() || !GeologyWorld.isOwn(server) || !RiverNetwork.ready()) return 0;
        int sea = level.getSeaLevel();
        int x0 = cp.getMinBlockX() - MARGIN, z0 = cp.getMinBlockZ() - MARGIN;

        // Every column of the window: the pool's surface over it, or MIN where it is no pool at all.
        int[] want = new int[SIZE * SIZE], ground = new int[SIZE * SIZE];
        boolean any = false;
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                int k = dx * SIZE + dz;
                want[k] = Integer.MIN_VALUE;
                ground[k] = Integer.MIN_VALUE;
                RiverNetwork.At a = RiverNetwork.at(x0 + dx, z0 + dz);
                if (a.distance() == Double.MAX_VALUE || a.dry()) continue;
                int w = (int) Math.floor(a.water());
                if (w <= sea) continue;                    // below the sea the ocean fills the channel itself
                // The water reaches out to where the channel's floor climbs to its surface, not to the edge of the
                // flat bed: the two columns between were dry ground a block under the water, and let it out.
                if (a.floor() > w - 0.5) continue;
                int g = TerrainProbe.groundY(level, x0 + dx, z0 + dz);
                ground[k] = g;
                if (g == Integer.MIN_VALUE || g >= w) continue;
                want[k] = w;
                any = true;
            }
        }
        if (!any) return 0;

        int placed = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = MARGIN; dx < MARGIN + 16; dx++) {
            for (int dz = MARGIN; dz < MARGIN + 16; dz++) {
                int k = dx * SIZE + dz;
                int w = want[k];
                if (w == Integer.MIN_VALUE) continue;
                CANDIDATES.increment();
                if (!walled(level, want, ground, x0, z0, dx, dz, w, true)) {
                    DROPPED.increment();
                    continue;
                }
                int x = x0 + dx, z = z0 + dz, g = ground[k];
                // Nothing is poured over a hole: the block under the water has to be solid.
                if (!level.getBlockState(at.set(x, g, z)).isSolidRender(level, at)) {
                    DROPPED.increment();
                    continue;
                }
                for (int y = g + 1; y <= w; y++) {
                    BlockState was = level.getBlockState(at.set(x, y, z));
                    if (!was.isAir() && was.getFluidState().isEmpty()) break;
                    level.setBlock(at, Blocks.WATER.defaultBlockState(), FLAGS);
                    placed++;
                }
                KEPT.increment();
                // A river bed is gravel in the mountains and sand lower down, not the meadow the surface rules laid.
                BlockState bed = level.getBlockState(at.set(x, g, z));
                if (bed.is(BlockTags.DIRT)) {
                    level.setBlock(at, (w - sea > 12 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState(), FLAGS);
                }
            }
        }
        BLOCKS.add(placed);
        if (CHUNKS.incrementAndGet() % 100 == 0) {
            GeysersMod.LOGGER.info("River water over {} chunks: {} columns in a pool, {} filled, {} let go, {} blocks, {} traces cut",
                    CHUNKS.get(), CANDIDATES.sum(), KEPT.sum(), DROPPED.sum(), BLOCKS.sum(), RiverNetwork.tracesCut());
        }
        return placed;
    }

    /**
     * Whether the pool at {@code w} is walled in beside this column: every neighbour stands as high as the surface,
     * or is a column of the same pool which is itself walled in. Asking one step further is enough for the edge of a
     * pool to retreat off ground that cannot hold it without the retreat running away along the whole river.
     */
    private static boolean walled(WorldGenLevel level, int[] want, int[] ground, int x0, int z0, int dx, int dz,
                                  int w, boolean deeper) {
        for (int d = 0; d < 4; d++) {
            int nx = dx + DX[d], nz = dz + DZ[d];
            int g;
            int wantN = Integer.MIN_VALUE;
            if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) {
                g = TerrainProbe.groundY(level, x0 + nx, z0 + nz);
            } else {
                int n = nx * SIZE + nz;
                wantN = want[n];
                g = ground[n];
                if (g == Integer.MIN_VALUE && wantN == Integer.MIN_VALUE) {
                    g = ground[n] = TerrainProbe.groundY(level, x0 + nx, z0 + nz);
                }
            }
            if (g != Integer.MIN_VALUE && g >= w) continue;
            if (wantN == w && (!deeper || walled(level, want, ground, x0, z0, nx, nz, w, false))) continue;
            return false;
        }
        return true;
    }
    /**
     * Vanilla freezes the top of still water in a cold biome, and a river is still water. Once it has, the ice over
     * a channel goes back to water and the snow laid on that ice goes back to air: the mod's rivers do not freeze.
     */
    public static void thaw(WorldGenLevel level, ChunkPos cp) {
        if (TfcCompat.active() || !GeologyWorld.isOwn(level.getLevel()) || !RiverNetwork.ready()) return;
        int sea = level.getSeaLevel();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                RiverNetwork.At a = RiverNetwork.at(x, z);
                if (a.distance() == Double.MAX_VALUE || a.dry()) continue;
                int w = (int) Math.floor(a.water());
                if (w <= sea || a.floor() > w - 0.5) continue;
                BlockState was = level.getBlockState(at.set(x, w, z));
                if (!was.is(Blocks.ICE) && !was.is(Blocks.FROSTED_ICE)) continue;
                level.setBlock(at, Blocks.WATER.defaultBlockState(), FLAGS);
                if (level.getBlockState(at.set(x, w + 1, z)).is(Blocks.SNOW)) {
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
        }
    }
}
