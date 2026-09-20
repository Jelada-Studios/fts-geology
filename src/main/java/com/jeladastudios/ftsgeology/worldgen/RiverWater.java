package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
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
 * The water standing in the rivers above the sea. {@link RiverNetwork} traces them down the raw ground and the
 * offset cuts their channels; this fills what the channel holds.
 *
 * <p>The water is {@code fts_geology:river_water}, which never moves. That is what lets the surface come down the
 * valley continuously instead of in flat pools: a column takes water wherever the channel's floor lies under the
 * surface the trace gives it, and nothing has to be proved about its neighbours. The old still-water rule asked
 * every column to be walled in and stepped back from its own bank wherever it was not, which left a dry rim of
 * gravel round every pool -- a quarter of the channel stood empty.</p>
 *
 * <p>The one thing still checked is that the block under the water is solid, so that nothing is poured over the
 * mouth of a cave. The caves are already kept away from a channel by {@code river_near}.</p>
 */
public final class RiverWater {

    private RiverWater() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final LongAdder CANDIDATES = new LongAdder(), KEPT = new LongAdder(), BLOCKS = new LongAdder(),
            DROPPED = new LongAdder();
    private static final AtomicLong CHUNKS = new AtomicLong();

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel server = level.getLevel();
        if (TfcCompat.active() || !GeologyWorld.isOwn(server) || !RiverNetwork.ready()) return 0;
        int sea = level.getSeaLevel();
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState();
        int placed = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                RiverNetwork.At a = RiverNetwork.at(x, z);
                if (a.distance() == Double.MAX_VALUE) continue;
                int w = (int) Math.floor(a.water());
                if (w <= sea) continue;                    // below the sea the ocean fills the channel itself
                // Out to where the channel's own floor climbs to the surface, and no further: past that the bank
                // stands over the water and the water would be lying on the hillside.
                if (a.floor() > w - 0.5) continue;
                CANDIDATES.increment();
                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE || g >= w) {
                    DROPPED.increment();
                    continue;
                }
                // Nothing is poured over a hole: the block under the water has to be solid.
                if (!level.getBlockState(at.set(x, g, z)).isSolidRender(level, at)) {
                    DROPPED.increment();
                    continue;
                }
                for (int y = g + 1; y <= w; y++) {
                    BlockState was = level.getBlockState(at.set(x, y, z));
                    if (!was.isAir() && was.getFluidState().isEmpty()) break;
                    level.setBlock(at, water, FLAGS);
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
            GeysersMod.LOGGER.info("River water over {} chunks: {} columns in a channel, {} filled, {} let go, {} blocks, {} traces cut",
                    CHUNKS.get(), CANDIDATES.sum(), KEPT.sum(), DROPPED.sum(), BLOCKS.sum(), RiverNetwork.tracesCut());
        }
        return placed;
    }
}
