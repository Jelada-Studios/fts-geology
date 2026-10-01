package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Rain that gathers in hollows. The water a soaked ground cannot take in runs off; most of it finds its way to the
 * rivers, but what runs into a closed hollow stays there as a pond until it soaks in and dries up. So it is not laid
 * wherever the ground is wet: it is reckoned first, as the water that ran off a chunk, and laid only in the hollow it
 * gathers in, as deep as the hollow holds up to the lowest point of its rim and no more than gathered, and taken back
 * a layer at a time once the rain is over (as a flood is, see {@link Floods}, leaving a little mud).
 */
public final class HollowPonds {

    private HollowPonds() {}

    /** How many times the chunk's own ground the water running into its hollows comes off: the slopes round it. */
    private static final double CATCHMENT = 4.0;
    /** Ground hours over which the water gathered soaks in and dries, most of it. */
    private static final double DRIES_HOURS = 168.0;
    /** Blocks of water gathered before a pond is laid, and below which one standing is let go. */
    private static final double LAY = 6.0, KEEP = 2.0;
    /** How far round its lowest point a hollow is looked at, and the most water one pond is. */
    private static final int REACH = 12, MOST = 400;
    /** Ticks a pond is held for after the last look that found water to keep it, and between its layers going. */
    private static final int HOLD = 2400, RECEDE = 600;

    private static long laid, kept;

    /**
     * A chunk's look: the water that ran off it in {@code hours} gathered, what has soaked in and dried taken off, and
     * a pond laid in its hollow, or the one standing kept, while there is enough.
     */
    static void look(ServerLevel level, LevelChunk chunk, SoilWater.Cells c, double hours, double runoff) {
        if (!GeyserConfig.HOLLOW_PONDS.get() || hours <= 0) return;
        // Millimetres off four-by-four cells, in cubic metres: blocks of water.
        c.gathered = (float) (c.gathered * Math.exp(-hours / DRIES_HOURS) + runoff * 16.0 / 1000.0 * CATCHMENT);
        ChunkPos p = chunk.getPos();
        String key = "pond@" + p.x + "," + p.z;
        if (c.gathered < KEEP) return;
        if (Floods.extend(level, key, HOLD)) {
            kept++;
            return;
        }
        if (c.gathered < LAY) return;
        int[] low = lowest(level, chunk);
        if (low == null) return;
        List<LongOpenHashSet> layers = basin(level, low[0], low[1], low[2], (int) Math.min(MOST, c.gathered));
        if (layers.isEmpty()) return;
        int n = Floods.surge(level, layers, HOLD, RECEDE, "rain gathered in a hollow at " + low[0] + " " + low[2], key);
        if (n > 0) laid++;
    }

    /** The lowest dry soil in a chunk, at its cells' middles: {x, ground y, z}, or null. */
    private static int[] lowest(ServerLevel level, LevelChunk chunk) {
        ChunkPos p = chunk.getPos();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[] best = null;
        for (int i = 0; i < 16; i++) {
            int x = p.getMinBlockX() + (i & 3) * 4 + 2, z = p.getMinBlockZ() + (i >> 2) * 4 + 2;
            int g = TerrainProbe.groundY(level, x, z);
            if (g == Integer.MIN_VALUE) continue;
            BlockState s = chunk.getBlockState(m.set(x, g, z));
            if (!soil(s) || !chunk.getFluidState(m.set(x, g + 1, z)).isEmpty()) continue;
            if (best == null || g < best[1]) best = new int[]{x, g, z};
        }
        return best;
    }

    private static boolean soil(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.GRAVEL)
                || s.is(SoilBlocks.NATURAL_GROUND);
    }

    /**
     * The water a hollow round {@code (x, z)} holds, a layer at a time from its floor up: each layer the open columns
     * joined to the lowest whose ground is under it. It stops at the layer that would run out over the rim -- reach the
     * edge of what is looked at, or a column lower than the floor -- and at {@code most} blocks; and it lays nothing
     * where the hollow already holds water or reaches into a chunk not loaded.
     */
    static List<LongOpenHashSet> basin(ServerLevel level, int x, int y0, int z, int most) {
        List<LongOpenHashSet> layers = new ArrayList<>();
        java.util.Map<Long, Integer> ground = new java.util.HashMap<>();
        int total = 0;
        for (int y = y0 + 1; total < most; y++) {
            // The columns joined to the lowest one whose ground is under this level.
            LongOpenHashSet region = new LongOpenHashSet();
            LongArrayFIFOQueue todo = new LongArrayFIFOQueue();
            long start = key(x, z);
            region.add(start);
            todo.enqueue(start);
            boolean spills = false;
            while (!todo.isEmpty() && !spills) {
                long k = todo.dequeueLong();
                int cx = (int) (k >> 32), cz = (int) k;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int nx = cx + d.getStepX(), nz = cz + d.getStepZ();
                    long nk = key(nx, nz);
                    if (region.contains(nk)) continue;
                    Integer g = ground.get(nk);
                    if (g == null) {
                        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, nx, nz)) return List.of();
                        g = surface(level, nx, nz);
                        ground.put(nk, g);
                    }
                    if (g == Integer.MAX_VALUE) return List.of();          // water in it already: a pond or a lake
                    if (g >= y) continue;                                  // the rim, here
                    if (g < y0 || Math.max(Math.abs(nx - x), Math.abs(nz - z)) >= REACH) {
                        spills = true;                                     // over the rim, or down off a drop
                        break;
                    }
                    region.add(nk);
                    todo.enqueue(nk);
                }
            }
            if (spills) break;
            LongOpenHashSet layer = new LongOpenHashSet();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (long k : region) {
                int cx = (int) (k >> 32), cz = (int) k;
                if (Floods.open(level.getBlockState(m.set(cx, y, cz)))) layer.add(BlockPos.asLong(cx, y, cz));
            }
            if (layer.isEmpty() || total + layer.size() > most) break;
            total += layer.size();
            layers.add(layer);
        }
        return layers;
    }

    /** A column's ground height, Integer.MAX_VALUE where water stands on it. */
    private static int surface(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return Integer.MAX_VALUE;
        if (!level.getFluidState(new BlockPos(x, g + 1, z)).isEmpty()) return Integer.MAX_VALUE;
        return g;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "hollow ponds: %d laid, %d looks kept one standing", laid, kept);
    }

    public static void clear() {
        laid = kept = 0;
    }
}
