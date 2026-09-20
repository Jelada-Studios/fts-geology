package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.util.SeedHash;
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
 * surface the trace gives it, and nothing has to be proved about its neighbours.</p>
 */
public final class RiverWater {

    private RiverWater() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * How far over the traced surface the real floor of a channel may come out before the column is given up on.
     *
     * <p>The trace reads a two-dimensional ground -- the offset with no three-dimensional noise on it -- and the
     * chunk is then built with that noise added, so the floor the generator actually lays wanders a block or two
     * either side of what the trace predicted. Where it came out over the water the old rule simply dropped the
     * column, and a dry island was left standing in the river. Those islands are what grew the grass, the flowers
     * and the melons a player found in the middle of a river: vanilla planted them at the vegetation step, quite
     * correctly, on ground we had left dry. Inside the channel the floor is ours to set, so it is shaved instead.
     */
    private static final int LEVEL_SHAVE = 4;

    /** How deep a spring's bore runs under the bed it rises through, and how much of it may be cut short. */
    private static final int SPRING_DEEP = 18, SPRING_VARY = 12, SPRING_LEAST = 5;

    private static final LongAdder CANDIDATES = new LongAdder(), KEPT = new LongAdder(), BLOCKS = new LongAdder(),
            DROPPED = new LongAdder(), LEVELLED = new LongAdder(), SPRINGS = new LongAdder(), WET = new LongAdder();
    private static final AtomicLong CHUNKS = new AtomicLong();

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel server = level.getLevel();
        if (TfcCompat.active() || !GeologyWorld.isOwn(server) || !RiverNetwork.ready()) return 0;
        if (!GeyserConfig.RIVERS.get()) return 0;
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
                if (g == Integer.MIN_VALUE) {
                    DROPPED.increment();
                    continue;
                }
                if (g >= w) {
                    int bedY = level(level, at, x, z, g, w, a);
                    if (bedY == Integer.MIN_VALUE) {
                        DROPPED.increment();
                        continue;
                    }
                    g = bedY;
                    LEVELLED.increment();
                }
                // Nothing is poured over a hole: the block under the water has to be solid.
                if (!level.getBlockState(at.set(x, g, z)).isSolidRender(level, at)) {
                    DROPPED.increment();
                    continue;
                }
                int here = 0;
                for (int y = g + 1; y <= w; y++) {
                    BlockState was = level.getBlockState(at.set(x, y, z));
                    if (!was.isAir() && was.getFluidState().isEmpty() && !TerrainProbe.isVegetation(was)) break;
                    level.setBlock(at, water, FLAGS);
                    placed++;
                    here++;
                }
                KEPT.increment();
                // Counted apart: a column can be kept, have its bed swapped for gravel, and still take no water
                // because the first block over its floor turned out to be solid. That is a dry gravel stripe
                // beside the river, and the old single counter called it filled.
                if (here > 0) WET.increment();
                // A river bed is gravel in the mountains and sand lower down, not the meadow the surface rules laid.
                BlockState bed = level.getBlockState(at.set(x, g, z));
                if (bed.is(BlockTags.DIRT)) {
                    level.setBlock(at, (w - sea > 12 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState(), FLAGS);
                }
                if (a.isHead()) placed += spring(level, at, x, z, g, water);
            }
        }
        BLOCKS.add(placed);
        if (CHUNKS.incrementAndGet() % 100 == 0) {
            GeysersMod.LOGGER.info("River water over {} chunks: {} columns in a channel, {} kept, {} of them wet, "
                            + "{} levelled, {} let go, {} springs, {} blocks, {} traces cut, {} joined, {} looped",
                    CHUNKS.get(), CANDIDATES.sum(), KEPT.sum(), WET.sum(), LEVELLED.sum(), DROPPED.sum(),
                    SPRINGS.sum(), BLOCKS.sum(), RiverNetwork.tracesCut(), RiverNetwork.joined(),
                    RiverNetwork.looped());
        }
        return placed;
    }

    /**
     * Takes the floor of a channel down to where the trace put it, where the noise left it standing over the water.
     * Only inside the flat bed, only by a few blocks, and never through anything a player or a structure put there.
     *
     * @return the new ground, or {@link Integer#MIN_VALUE} to leave the column alone
     */
    private static int level(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int g, int w,
                             RiverNetwork.At a) {
        // The bank ring counts too. The water is laid out to where the channel's own wall reaches the surface,
        // about a block past the flat bed, but the shave used to stop at the bed -- so every column in that ring
        // whose floor the noise left high was dropped, and the river got a ragged stair down both its sides.
        // That ring is a quarter of the channel, and it is exactly where the factor boost that pins the noise
        // has decayed to nothing.
        if (g - w > LEVEL_SHAVE) return Integer.MIN_VALUE;
        int bedY = Math.min(w - 1, (int) Math.floor(a.floor()));
        if (bedY < level.getMinBuildHeight() + 1) return Integer.MIN_VALUE;
        // The floor of the channel has to be there to stand on, and everything over it has to be ours to take.
        if (!level.getBlockState(at.set(x, bedY, z)).isSolidRender(level, at)) return Integer.MIN_VALUE;
        for (int y = bedY + 1; y <= g; y++) {
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(at.set(x, y, z)))) return Integer.MIN_VALUE;
        }
        for (int y = bedY + 1; y <= g; y++) {
            level.setBlock(at.set(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        return bedY;
    }

    /**
     * The mouth a river rises from: one block wide, running well under the bed, so that the water is seen to come
     * out of the ground rather than to begin in a puddle. It stops above the first thing that is not solid, so a
     * spring never opens into a cave.
     */
    private static int spring(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int g,
                              BlockState water) {
        long hash = SeedHash.hash(level.getSeed(), x, z, 0x5B10L);
        int want = SPRING_DEEP + (int) (SeedHash.rand01(hash) * SPRING_VARY);
        int floor = level.getMinBuildHeight() + 2;
        int deepest = g;
        while (deepest > g - want && deepest > floor
                && level.getBlockState(at.set(x, deepest - 1, z)).isSolidRender(level, at)) {
            deepest--;
        }
        if (g - deepest < SPRING_LEAST) return 0;
        int placed = 0;
        for (int y = g; y >= deepest; y--) {
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(at.set(x, y, z)))) break;
            level.setBlock(at.set(x, y, z), water, FLAGS);
            placed++;
        }
        if (placed > 0) SPRINGS.increment();
        return placed;
    }
}
