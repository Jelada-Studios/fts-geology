package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

/**
 * A whole river read from the biome source, four blocks to a cell, without loading a chunk: where it runs, where
 * it meets the sea, which way it flows and how much it carries. Minecraft's rivers lie flat at sea level, so the
 * only thing that says which way the water goes is the shape of the network: it goes to the sea. Every cell gets
 * its distance to the mouth along the river, and the flow at a cell points to the neighbour nearer the mouth.
 * Discharge is the number of cells upstream, which is what a real river's discharge grows with. A river with no
 * mouth in reach is told apart at its two ends by the generator's ground: the higher end is the source.
 *
 * <p>Read once per river on a worker thread and kept in memory; each chunk's survey keeps what it needs, so a
 * restart never has to read it again for a bend already planned.</p>
 */
public final class RiverNetwork {

    private RiverNetwork() {}

    /** Cells one river may have: some eighty kilometres of channel. */
    private static final int MAX_CELLS = 30_000;
    /** Half a channel this wide, in cells, is a lake: sixteen blocks either side of the centre. */
    static final int LAKE_HALF = 4;
    /**
     * How far out from a lake's core its shores still count as lake, in cells. The cells round an island in a
     * lake are narrow, and without this they passed for a channel and had bends planned on the island's shore.
     */
    static final int LAKE_SHORE = 6;
    /** Cells from the mouth that are the delta, where a bend is never planned. */
    static final int MOUTH_ZONE = 8;

    /**
     * One river cell: distance to the mouth along the river, cells upstream of it, its distance to the bank, and
     * whether it is part of a lake (wide water, or its shore).
     */
    public record Node(int dist, int upstream, int halfWidth, boolean lake, long river) {
        public boolean directed() { return dist >= 0; }
    }

    /** All the cells of every river read so far, by cell key, each pointing at its river's nodes. */
    private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<Node>> BY_CELL = new Long2ObjectOpenHashMap<>();
    /** The one river being read now, if any. Read one at a time: two chunks of one river would read it twice. */
    private static CompletableFuture<Long2ObjectOpenHashMap<Node>> inFlight;

    static long key(int qx, int qz) {
        return ((long) qx << 32) ^ (qz & 0xFFFFFFFFL);
    }

    /**
     * The node of the river cell holding a block, or null while its river is still being read. A river not read
     * yet is started here; the caller asks again next tick. Server thread only.
     */
    public static Node at(ServerLevel level, int blockX, int blockZ, int waterY) {
        int qx = QuartPos.fromBlock(blockX), qz = QuartPos.fromBlock(blockZ), qy = QuartPos.fromBlock(waterY);
        long k = key(qx, qz);
        Long2ObjectOpenHashMap<Node> river = BY_CELL.get(k);
        if (river != null) return river.get(k);
        if (inFlight != null) {
            if (!inFlight.isDone()) return null;
            Long2ObjectOpenHashMap<Node> done = inFlight.join();
            inFlight = null;
            if (done != null) for (long c : done.keySet()) BY_CELL.put(c, done);
            river = BY_CELL.get(k);
            if (river != null) return river.get(k);
        }
        // Not a river cell at all (the water is there but the biome is not): known, and nothing.
        if (!RiverSurvey.river(biome(level, qx, qy, qz))) {
            Long2ObjectOpenHashMap<Node> none = new Long2ObjectOpenHashMap<>();
            BY_CELL.put(k, none);
            return null;
        }
        inFlight = CompletableFuture.supplyAsync(() -> {
            try {
                return read(level, qx, qz, qy);
            } catch (RuntimeException e) {
                GeysersMod.LOGGER.warn("river network at {},{}: {}", blockX, blockZ, e.toString());
                return null;
            }
        }, Util.backgroundExecutor());
        return null;
    }

    /** The node of a cell whose river has already been read, or null. Never starts a read; for the debug view. */
    public static Node peek(int blockX, int blockZ) {
        long k = key(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockZ));
        Long2ObjectOpenHashMap<Node> river = BY_CELL.get(k);
        return river == null ? null : river.get(k);
    }

    /** True when the cell's river is known, whether or not the cell is in one. */
    public static boolean known(int blockX, int blockZ) {
        return BY_CELL.containsKey(key(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockZ)));
    }

    /** The flow at a cell: a unit vector towards the neighbour nearest the mouth, or null where the way is unknown. */
    public static double[] flow(int blockX, int blockZ) {
        int qx = QuartPos.fromBlock(blockX), qz = QuartPos.fromBlock(blockZ);
        Long2ObjectOpenHashMap<Node> river = BY_CELL.get(key(qx, qz));
        if (river == null) return null;
        Node here = river.get(key(qx, qz));
        if (here == null || !here.directed()) return null;
        int best = here.dist();
        int bx = 0, bz = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Node n = river.get(key(qx + dx, qz + dz));
                if (n == null || n.dist() < 0 || n.dist() >= best) continue;
                best = n.dist();
                bx = dx;
                bz = dz;
            }
        }
        if (bx == 0 && bz == 0) return null;
        double l = Math.hypot(bx, bz);
        return new double[] {bx / l, bz / l};
    }

    public static void clear() {
        BY_CELL.clear();
        inFlight = null;
    }

    /** The biome at a quart, sampled at the river's own water level: a hanging valley's river is not at the sea's. */
    private static Holder<Biome> biome(ServerLevel level, int qx, int qy, int qz) {
        BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        return biomes.getNoiseBiome(qx, qy, qz, sampler);
    }

    /** Reads the river a cell belongs to. Worker thread; touches only the biome source and the generator. */
    private static Long2ObjectOpenHashMap<Node> read(ServerLevel level, int qx0, int qz0, int qy) {
        long started = System.nanoTime();
        LongOpenHashSet river = new LongOpenHashSet();
        LongOpenHashSet notRiver = new LongOpenHashSet();
        LongOpenHashSet mouths = new LongOpenHashSet();
        LongArrayList order = new LongArrayList();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        river.add(key(qx0, qz0));
        queue.add(new long[] {qx0, qz0});
        int samples = 0;
        while (!queue.isEmpty() && river.size() < MAX_CELLS) {
            long[] c = queue.poll();
            int qx = (int) c[0], qz = (int) c[1];
            order.add(key(qx, qz));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = qx + dx, nz = qz + dz;
                    long nk = key(nx, nz);
                    if (river.contains(nk) || notRiver.contains(nk)) continue;
                    Holder<Biome> b = biome(level, nx, qy, nz);
                    samples++;
                    if (RiverSurvey.river(b)) {
                        river.add(nk);
                        queue.add(new long[] {nx, nz});
                    } else if ((dx == 0 || dz == 0) && RiverSurvey.river(biome(level, nx + dx, qy, nz + dz))) {
                        // A one-cell gap in a narrow river's biome is bridged, or the network falls apart into
                        // reaches of a few cells and none knows which way it runs.
                        samples++;
                        river.add(nk);
                        queue.add(new long[] {nx, nz});
                    } else {
                        if (dx == 0 || dz == 0) samples++;
                        notRiver.add(nk);
                        if (RiverSurvey.ocean(b)) mouths.add(key(qx, qz));
                    }
                }
            }
        }
        boolean cut = river.size() >= MAX_CELLS;

        // Distance to the bank: the cells next to land first, then inward.
        Long2ObjectOpenHashMap<int[]> half = new Long2ObjectOpenHashMap<>();
        ArrayDeque<Long> bfs = new ArrayDeque<>();
        for (long c : river) {
            int qx = (int) (c >> 32), qz = (int) c;
            boolean edge = false;
            for (int dx = -1; dx <= 1 && !edge; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (!river.contains(key(qx + dx, qz + dz))) { edge = true; break; }
            }
            if (edge) { half.put(c, new int[] {1}); bfs.add(c); }
        }
        spread(river, half, bfs);

        // Distance to the mouth, or to the lower end of a river that has no mouth.
        Long2ObjectOpenHashMap<int[]> dist = new Long2ObjectOpenHashMap<>();
        bfs.clear();
        if (mouths.isEmpty()) {
            long mouth = lowerEnd(level, river, order);
            if (mouth != Long.MIN_VALUE) mouths.add(mouth);
        }
        for (long m : mouths) { dist.put(m, new int[] {0}); bfs.add(m); }
        spread(river, dist, bfs);

        // What each cell carries: the cells upstream of it, handed down towards the mouth.
        Long2ObjectOpenHashMap<int[]> up = new Long2ObjectOpenHashMap<>();
        if (!dist.isEmpty()) {
            long[] cells = river.toLongArray();
            it.unimi.dsi.fastutil.longs.LongArrays.quickSort(cells, (a, b) -> Integer.compare(
                    dist.containsKey(b) ? dist.get(b)[0] : -1, dist.containsKey(a) ? dist.get(a)[0] : -1));
            for (long c : cells) {
                int[] d = dist.get(c);
                if (d == null) continue;
                int[] mine = up.computeIfAbsent(c, k -> new int[] {0});
                int qx = (int) (c >> 32), qz = (int) c;
                long down = Long.MIN_VALUE;
                int best = d[0];
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long nk = key(qx + dx, qz + dz);
                    int[] nd = dist.get(nk);
                    if (nd != null && nd[0] < best) { best = nd[0]; down = nk; }
                }
                if (down != Long.MIN_VALUE) up.computeIfAbsent(down, k -> new int[] {0})[0] += mine[0] + 1;
            }
        }

        // Lakes: the wide water, and its shores out to LAKE_SHORE cells, islands' shores included.
        Long2ObjectOpenHashMap<int[]> lake = new Long2ObjectOpenHashMap<>();
        bfs.clear();
        for (long c : river) {
            int[] h = half.get(c);
            if (h != null && h[0] >= LAKE_HALF) { lake.put(c, new int[] {0}); bfs.add(c); }
        }
        spread(river, lake, bfs);

        Long2ObjectOpenHashMap<Node> out = new Long2ObjectOpenHashMap<>(river.size());
        for (long c : river) {
            int[] d = dist.get(c), u = up.get(c), h = half.get(c), l = lake.get(c);
            out.put(c, new Node(d == null ? -1 : d[0], u == null ? 0 : u[0], h == null ? 1 : h[0],
                    l != null && l[0] <= LAKE_SHORE, key(qx0, qz0)));
        }
        GeysersMod.LOGGER.info("river network from {},{}: {} cells{}, {} mouths, {} biome samples, {} ms",
                qx0 * 4, qz0 * 4, river.size(), cut ? " (cut short)" : "", mouths.size(), samples,
                (System.nanoTime() - started) / 1_000_000);
        return out;
    }

    /** Breadth-first from the seeded cells over the river, each unseen neighbour one more than its seed. */
    private static void spread(LongOpenHashSet river, Long2ObjectOpenHashMap<int[]> value, ArrayDeque<Long> bfs) {
        while (!bfs.isEmpty()) {
            long c = bfs.poll();
            int v = value.get(c)[0];
            int qx = (int) (c >> 32), qz = (int) c;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long nk = key(qx + dx, qz + dz);
                    if (!river.contains(nk) || value.containsKey(nk)) continue;
                    value.put(nk, new int[] {v + 1});
                    bfs.add(nk);
                }
            }
        }
    }

    /**
     * For a river that reaches no sea: the end its water leaves by, which is the lower of its two ends by the
     * generator's ground round them; MIN where the two are level and nothing can be said.
     */
    private static long lowerEnd(ServerLevel level, LongOpenHashSet river, LongArrayList order) {
        if (order.isEmpty()) return Long.MIN_VALUE;
        long a = farthest(river, order.getLong(0));
        long b = farthest(river, a);
        int ha = groundRound(level, a), hb = groundRound(level, b);
        if (Math.abs(ha - hb) < 2) return Long.MIN_VALUE;
        return ha > hb ? b : a;
    }

    private static long farthest(LongOpenHashSet river, long from) {
        Long2ObjectOpenHashMap<int[]> d = new Long2ObjectOpenHashMap<>();
        ArrayDeque<Long> bfs = new ArrayDeque<>();
        d.put(from, new int[] {0});
        bfs.add(from);
        spread(river, d, bfs);
        long best = from;
        int far = -1;
        for (var e : d.long2ObjectEntrySet()) {
            if (e.getValue()[0] > far) { far = e.getValue()[0]; best = e.getLongKey(); }
        }
        return best;
    }

    /** The generator's ground at eight points thirty blocks round a cell: the valley the river's end sits in. */
    private static int groundRound(ServerLevel level, long cell) {
        int x = ((int) (cell >> 32)) * 4 + 2, z = ((int) cell) * 4 + 2;
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            sum += gen.getBaseHeight(x + (int) Math.round(Math.cos(a) * 30), z + (int) Math.round(Math.sin(a) * 30),
                    Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        }
        return sum / 8;
    }
}
