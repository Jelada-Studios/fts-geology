package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.util.ColumnCache;
import com.jeladastudios.ftsgeology.util.SetCache;
import com.jeladastudios.ftsgeology.util.SeedHash;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Where water goes, worked out on a lattice of valley points laid over the ground.
 *
 * <p>Every cell of a square grid holds one node: of four points jittered round the cell's quarters, the lowest, so a
 * node sits in whatever valley crosses its cell. Nodes are joined to their four neighbours and, across each square of
 * four nodes, by the one diagonal that lies inside it. That makes a planar triangulation, and since water only ever
 * moves along its edges, two rivers drawn on it cannot cross.</p>
 *
 * <p>Water leaves a node by its steepest way down. A node with none is a pit, and a pit is a lake: it fills until it
 * spills at the lowest point of its rim. The rim is found by a search out from the pit in order of the height the
 * water has to climb to get there, stopping at the first node lower than the pit's floor. Where that node's own water
 * ends in a lake that stands higher still, the two are one lake at the higher level. Worked out this way, pit by pit,
 * the water surface everywhere is the one a filling of the whole map at once would give, and it can be asked for at
 * any place in any order.</p>
 *
 * <p>Nothing here knows about Minecraft: the ground is a function of two coordinates, so the whole model can be
 * checked against an exact global filling on a made-up island.</p>
 */
public final class DrainageLattice {

    /** The ground, in blocks, with no river cut into it. */
    public interface Ground {
        double heightAt(int x, int z);
    }

    // === Settings ===========================================================

    /** Nodes to a side of a cached tile. */
    static final int TILE = 16;
    /** How far a node's four candidates may wander from their quarter, as a share of the cell. */
    private static final double JITTER = 0.08;
    /** How far under the ground half way along an edge the water may still pass there: a channel cuts that much. */
    private static final double SILL_CUT = 8.0;

    private final Ground ground;
    private final long seed;
    /** Blocks between nodes. */
    final double cell;
    /** Sea level: a node this low is the sea. */
    final double sea;
    /** How many nodes the search round a pit may take before the pit is called closed. */
    private final int maxPops;
    /** Where catchment area stops being counted, in nodes. */
    final int areaCap;

    private final SetCache<Tile> tiles = new SetCache<>(9);
    private final SetCache<Pit> pits = new SetCache<>(10);
    private final SetCache<Lake> lakes = new SetCache<>(8);

    // Counters, for the log line and the audit.
    final LongAdder pitsFound = new LongAdder(), closedPits = new LongAdder(), lakesBuilt = new LongAdder(),
            groundReads = new LongAdder();

    public long pitsFoundCount() {
        return pitsFound.sum();
    }

    public long closedCount() {
        return closedPits.sum();
    }

    public long lakesCount() {
        return lakesBuilt.sum();
    }

    public long readsCount() {
        return groundReads.sum();
    }

    public DrainageLattice(Ground ground, long seed, double cell, double sea, int maxPops, int areaCap) {
        this.ground = ground;
        this.seed = seed;
        this.cell = cell;
        this.sea = sea;
        this.maxPops = maxPops;
        this.areaCap = areaCap;
    }

    // === Nodes ==============================================================

    public static long key(int i, int j) {
        return ColumnCache.key(i, j);
    }

    public static int ki(long key) {
        return (int) key;
    }

    public static int kj(long key) {
        return (int) (key >>> 32);
    }

    /** Neighbour slots: east, north, west, south, then the four diagonals. */
    static final int[] DI = {1, 0, -1, 0, 1, -1, -1, 1};
    static final int[] DJ = {0, 1, 0, -1, 1, 1, -1, -1};

    private static final byte UNKNOWN = -2, NONE = -1;
    /** A raw receiver that says "pit". */
    private static final byte PIT = -3;
    /** A flow receiver that says "ask the lake". */
    private static final byte LAKE = -4;

    static final class Tile {
        final float[] x = new float[TILE * TILE], z = new float[TILE * TILE], g = new float[TILE * TILE];
        /** Sills of the edges a node owns: east, north, and the diagonal of the square it is the south-west corner of. */
        final float[] sillE = new float[TILE * TILE], sillN = new float[TILE * TILE], sillD = new float[TILE * TILE];
        /** Which diagonal that square has: south-west to north-east, or south-east to north-west. */
        final boolean[] rising = new boolean[TILE * TILE];
        // What has been worked out so far. Every value is a pure function of the node, so a race only repeats work.
        final byte[] raw = new byte[TILE * TILE], flow = new byte[TILE * TILE];
        final float[] fill = new float[TILE * TILE];
        final short[] area = new short[TILE * TILE];

        Tile() {
            Arrays.fill(raw, UNKNOWN);
            Arrays.fill(flow, UNKNOWN);
            Arrays.fill(fill, Float.NaN);
            Arrays.fill(area, (short) -1);
        }
    }

    /**
     * How far the ground under a node is lifted or lowered by a smooth micro-relief, in blocks, and over how many nodes
     * it rolls.
     *
     * <p>Vanilla's height curves have flat steps, and on one of them the ground is exactly level: node after node the
     * same height to the last bit, so none has a lower neighbour and the water has nowhere to go. A river that reached
     * such a plain stopped in the middle of the grass. A third of a block of gentle roll is what steers a river over
     * a real floodplain too; the hollows it makes are a fraction of a block deep, so they are cut through, not
     * filled into lakes.</p>
     */
    private static final double ROLL = 0.35, ROLL_NODES = 5.0;

    /** Where a node stands and how high: the lowest of four jittered points in its cell. {x, z, height} */
    private double[] place(int i, int j) {
        long h = SeedHash.hash(seed, i, j, 0x7A3DL);
        double best = Double.MAX_VALUE, bx = 0, bz = 0;
        for (int c = 0; c < 4; c++) {
            long hc = SeedHash.mix(h ^ (0x51L * (c + 1)));
            double ox = ((c & 1) == 0 ? -0.25 : 0.25) + (SeedHash.rand01(hc) * 2 - 1) * JITTER;
            double oz = ((c & 2) == 0 ? -0.25 : 0.25) + (SeedHash.rand01(SeedHash.mix(hc ^ 0x9EL)) * 2 - 1) * JITTER;
            int px = (int) Math.floor((i + 0.5 + ox) * cell), pz = (int) Math.floor((j + 0.5 + oz) * cell);
            double y = read(px, pz);
            if (y < best) {
                best = y;
                bx = px + 0.5;
                bz = pz + 0.5;
            }
        }
        // The roll, and a last hair of the node's own hash so that no two nodes are ever exactly level.
        double roll = ROLL * roll(i, j) + 1e-3 * (SeedHash.rand01(SeedHash.mix(h ^ 0x3C1L)) - 0.5);
        return new double[]{bx, bz, best + roll};
    }

    /** Smooth value noise over the node lattice, in [-1, 1]. */
    private double roll(int i, int j) {
        double fx = i / ROLL_NODES, fz = j / ROLL_NODES;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = lattice(x0, z0), b = lattice(x0 + 1, z0), c = lattice(x0, z0 + 1), d = lattice(x0 + 1, z0 + 1);
        double top = a + (b - a) * tx, bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private double lattice(int x, int z) {
        return SeedHash.rand01(SeedHash.hash(seed, x, z, 0x2A11L)) * 2 - 1;
    }

    private double read(int x, int z) {
        groundReads.increment();
        return ground.heightAt(x, z);
    }

    private Tile tile(int ti, int tj) {
        long k = key(ti, tj);
        Tile t = tiles.get(k);
        if (t != null) return t;
        t = buildTile(ti, tj);
        tiles.put(k, t);
        return t;
    }

    private Tile buildTile(int ti, int tj) {
        int n = TILE + 1;
        double[][] ring = new double[n * n][];
        for (int b = 0; b < n; b++) {
            for (int a = 0; a < n; a++) ring[a + n * b] = place(ti * TILE + a, tj * TILE + b);
        }
        Tile t = new Tile();
        for (int b = 0; b < TILE; b++) {
            for (int a = 0; a < TILE; a++) {
                int l = a + TILE * b;
                double[] p = ring[a + n * b], e = ring[a + 1 + n * b], no = ring[a + n * (b + 1)],
                        ne = ring[a + 1 + n * (b + 1)];
                t.x[l] = (float) p[0];
                t.z[l] = (float) p[1];
                t.g[l] = (float) p[2];
                t.sillE[l] = sill(p, e);
                t.sillN[l] = sill(p, no);
                // The square's diagonal: whichever lies inside it, and where both do, the lower one, so water crossing
                // the square keeps to the valley.
                boolean ac = inside(p, ne, e, no), bd = inside(e, no, p, ne);
                boolean rising;
                if (ac && bd) rising = Math.max(p[2], ne[2]) <= Math.max(e[2], no[2]);
                else rising = ac || !bd;
                t.rising[l] = rising;
                t.sillD[l] = rising ? sill(p, ne) : sill(e, no);
            }
        }
        return t;
    }

    /** Whether the segment a-c runs inside the square a, b, c, d: b and d lie on opposite sides of it. */
    private static boolean inside(double[] a, double[] c, double[] b, double[] d) {
        double cx = c[0] - a[0], cz = c[1] - a[1];
        double sb = cx * (b[1] - a[1]) - cz * (b[0] - a[0]);
        double sd = cx * (d[1] - a[1]) - cz * (d[0] - a[0]);
        return sb * sd < 0;
    }

    private float sill(double[] a, double[] b) {
        int mx = (int) Math.floor((a[0] + b[0]) * 0.5), mz = (int) Math.floor((a[1] + b[1]) * 0.5);
        return (float) (read(mx, mz) - SILL_CUT);
    }

    // === Node reads =========================================================

    private Tile tileOf(int i, int j) {
        return tile(Math.floorDiv(i, TILE), Math.floorDiv(j, TILE));
    }

    private static int local(int i, int j) {
        return Math.floorMod(i, TILE) + TILE * Math.floorMod(j, TILE);
    }

    public double x(long k) {
        int i = ki(k), j = kj(k);
        return tileOf(i, j).x[local(i, j)];
    }

    public double z(long k) {
        int i = ki(k), j = kj(k);
        return tileOf(i, j).z[local(i, j)];
    }

    public double g(long k) {
        int i = ki(k), j = kj(k);
        return tileOf(i, j).g[local(i, j)];
    }

    /** The sill of the edge from (i, j) in a slot, or NaN where that slot has no edge. */
    private double sill(int i, int j, int s) {
        switch (s) {
            case 0: return tileOf(i, j).sillE[local(i, j)];
            case 1: return tileOf(i, j).sillN[local(i, j)];
            case 2: return tileOf(i - 1, j).sillE[local(i - 1, j)];
            case 3: return tileOf(i, j - 1).sillN[local(i, j - 1)];
            case 4: {
                Tile t = tileOf(i, j);
                int l = local(i, j);
                return t.rising[l] ? t.sillD[l] : Double.NaN;
            }
            case 5: {
                Tile t = tileOf(i - 1, j);
                int l = local(i - 1, j);
                return t.rising[l] ? Double.NaN : t.sillD[l];
            }
            case 6: {
                Tile t = tileOf(i - 1, j - 1);
                int l = local(i - 1, j - 1);
                return t.rising[l] ? t.sillD[l] : Double.NaN;
            }
            default: {
                Tile t = tileOf(i, j - 1);
                int l = local(i, j - 1);
                return t.rising[l] ? Double.NaN : t.sillD[l];
            }
        }
    }

    /** Whether a node has an edge in this slot. */
    public boolean has(long k, int s) {
        return s < 4 || !Double.isNaN(sill(ki(k), kj(k), s));
    }

    public static long step(long k, int s) {
        return key(ki(k) + DI[s], kj(k) + DJ[s]);
    }

    /** The height water has to reach to pass along an edge: its two ends and its sill. */
    public double weight(long k, int s) {
        return Math.max(Math.max(g(k), g(step(k, s))), sill(ki(k), kj(k), s));
    }

    private double dist(long a, long b) {
        double dx = x(a) - x(b), dz = z(a) - z(b);
        return Math.sqrt(dx * dx + dz * dz);
    }

    // === The raw way down ==================================================

    /** The slot of a node's steepest way down over the bare ground, {@link #NONE} for the sea, {@link #PIT}. */
    private byte raw(long k) {
        int i = ki(k), j = kj(k);
        Tile t = tileOf(i, j);
        int l = local(i, j);
        byte hit = t.raw[l];
        if (hit != UNKNOWN) return hit;
        double gk = t.g[l];
        byte best = gk <= sea ? NONE : PIT;
        if (best == PIT) {
            double steep = 0;
            for (int s = 0; s < 8; s++) {
                double sl = sill(i, j, s);
                if (Double.isNaN(sl) || sl > gk) continue;
                long n = step(k, s);
                double gn = g(n);
                if (gn >= gk) continue;
                double v = (gk - gn) / dist(k, n);
                if (v > steep) {
                    steep = v;
                    best = (byte) s;
                }
            }
        }
        t.raw[l] = best;
        return best;
    }

    // === Pits ===============================================================

    /**
     * What the search round a pit found: the level it spills at, the first node below its floor, and every node
     * under that level -- the lake it holds, if it keeps its own water.
     */
    static final class Pit {
        final double spill;
        final long below;
        final boolean closed;
        final long[] under;

        Pit(double spill, long below, boolean closed, long[] under) {
            this.spill = spill;
            this.below = below;
            this.closed = closed;
            this.under = under;
        }
    }

    Pit pit(long p) {
        Pit hit = pits.get(p);
        if (hit != null) return hit;
        Pit made = search(p);
        pits.put(p, made);
        return made;
    }

    /** Out from a pit in order of the level the water must reach, until it finds ground lower than the pit's floor. */
    private Pit search(long p) {
        pitsFound.increment();
        double floor = g(p);
        Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        best.defaultReturnValue(Double.MAX_VALUE);
        LongOpenHashSet done = new LongOpenHashSet();
        Heap open = new Heap();
        LongArrayList under = new LongArrayList();
        best.put(p, floor);
        open.push(floor, p);
        int pops = 0;
        while (!open.isEmpty()) {
            double lv = open.topLevel();
            long v = open.pop();
            if (!done.add(v)) continue;
            if (g(v) < floor) {
                // The lake holds everything the water reached below its spill level.
                long[] u = under.toLongArray();
                int keep = 0;
                for (long w : u) if (best.get(w) < lv) u[keep++] = w;
                return new Pit(lv, v, false, Arrays.copyOf(u, keep));
            }
            if (++pops > maxPops) break;
            under.add(v);
            for (int s = 0; s < 8; s++) {
                if (!has(v, s)) continue;
                long n = step(v, s);
                if (done.contains(n)) continue;
                double nl = Math.max(lv, weight(v, s));
                if (nl < best.get(n)) {
                    best.put(n, nl);
                    open.push(nl, n);
                }
            }
        }
        closedPits.increment();
        return new Pit(floor, p, true, new long[0]);
    }

    // === The filled surface ================================================

    /**
     * The level water stands at over a node once every hollow has filled and spilled: the node's own height where it
     * drains freely, a lake's level where it lies under one, the sea's over the sea.
     */
    public double fill(long k) {
        double known = memoFill(k);
        if (!Double.isNaN(known)) return known;
        LongArrayList todo = new LongArrayList();
        LongArrayList chain = new LongArrayList();
        todo.add(k);
        while (!todo.isEmpty()) {
            long w = todo.getLong(todo.size() - 1);
            if (!Double.isNaN(memoFill(w))) {
                todo.removeLong(todo.size() - 1);
                continue;
            }
            chain.clear();
            long cur = w;
            double base = Double.NaN;
            while (true) {
                double m = memoFill(cur);
                if (!Double.isNaN(m)) {
                    base = m;
                    break;
                }
                double gc = g(cur);
                if (gc <= sea) {
                    setFill(cur, sea);
                    base = sea;
                    break;
                }
                byte r = raw(cur);
                if (r == PIT) {
                    Pit pit = pit(cur);
                    if (pit.closed) {
                        setFill(cur, gc);
                        base = gc;
                        break;
                    }
                    double below = memoFill(pit.below);
                    if (Double.isNaN(below)) {
                        // The node it spills to has to be settled first; it lies lower, so this always ends.
                        todo.add(pit.below);
                        break;
                    }
                    double level = Math.max(pit.spill, below);
                    setFill(cur, level);
                    base = level;
                    break;
                }
                chain.add(cur);
                cur = step(cur, r);
            }
            if (Double.isNaN(base)) continue;
            double f = base;
            for (int c = chain.size() - 1; c >= 0; c--) {
                long u = chain.getLong(c);
                f = Math.max(g(u), f);
                setFill(u, f);
            }
            todo.removeLong(todo.size() - 1);
        }
        return memoFill(k);
    }

    private double memoFill(long k) {
        int i = ki(k), j = kj(k);
        return tileOf(i, j).fill[local(i, j)];
    }

    private void setFill(long k, double v) {
        int i = ki(k), j = kj(k);
        tileOf(i, j).fill[local(i, j)] = (float) v;
    }

    /** True where a node lies under standing water. */
    public boolean flooded(long k) {
        return fill(k) > g(k);
    }

    // === Lakes ==============================================================

    /**
     * The pit whose own search found the level a lake stands at. A pit whose water spills into a lake standing higher
     * than its own spill belongs to that lake.
     */
    long owner(long p) {
        long cur = p;
        for (int guard = 0; guard < 100_000; guard++) {
            Pit pit = pit(cur);
            if (pit.closed) return cur;
            if (fill(cur) <= pit.spill) return cur;
            long next = pit.below;
            while (true) {
                if (g(next) <= sea) return cur;
                byte r = raw(next);
                if (r == PIT) break;
                next = step(next, r);
            }
            cur = next;
        }
        return cur;
    }

    /** A lake: its level, what lies under it, and the way each of those nodes goes to leave it. */
    public static final class Lake {
        public final long owner;
        public final double level;
        public final long[] members;
        final Long2LongOpenHashMap next;

        Lake(long owner, double level, long[] members, Long2LongOpenHashMap next) {
            this.owner = owner;
            this.level = level;
            this.members = members;
            this.next = next;
        }
    }

    public Lake lake(long owner) {
        Lake hit = lakes.get(owner);
        if (hit != null) return hit;
        Lake made = buildLake(owner);
        lakes.put(owner, made);
        return made;
    }

    /**
     * The ways out of a lake. A node on the shore with an edge the water can cross to a node not under the lake is an
     * outlet; every other node under the lake is led to the nearest outlet across the lake, breadth first, so a flat
     * of water drains without a loop.
     */
    private Lake buildLake(long o) {
        lakesBuilt.increment();
        Pit pit = pit(o);
        double level = fill(o);
        long[] members = pit.under.clone();
        Arrays.sort(members);
        LongOpenHashSet in = new LongOpenHashSet(members);
        Long2LongOpenHashMap next = new Long2LongOpenHashMap(members.length);
        next.defaultReturnValue(Long.MIN_VALUE);
        LongArrayList queue = new LongArrayList();
        for (long m : members) {
            long out = Long.MIN_VALUE;
            double outFill = Double.MAX_VALUE;
            for (int s = 0; s < 8; s++) {
                if (!has(m, s)) continue;
                long n = step(m, s);
                if (in.contains(n) || weight(m, s) > level) continue;
                double fn = fill(n);
                // Not into another lake standing at the same level: two of them could hand their water back and forth.
                if (fn > level || (fn == level && fn > g(n))) continue;
                if (fn < outFill || (fn == outFill && n < out)) {
                    outFill = fn;
                    out = n;
                }
            }
            if (out != Long.MIN_VALUE) {
                next.put(m, out);
                queue.add(m);
            }
        }
        for (int q = 0; q < queue.size(); q++) {
            long m = queue.getLong(q);
            for (int s = 0; s < 8; s++) {
                if (!has(m, s)) continue;
                long n = step(m, s);
                if (!in.contains(n) || next.containsKey(n) || weight(m, s) > level) continue;
                next.put(n, m);
                queue.add(n);
            }
        }
        return new Lake(o, level, members, next);
    }

    /** The lake a flooded node lies under: down its raw way to the pit, and from there to the pit that owns the lake. */
    public Lake lakeOf(long k) {
        long cur = k;
        while (true) {
            byte r = raw(cur);
            if (r == PIT) break;
            if (r == NONE) return null;
            cur = step(cur, r);
        }
        return lake(owner(cur));
    }

    // === Flow ===============================================================

    /**
     * Where the water at a node goes, as the node it flows to, or {@link Long#MIN_VALUE} at the sea and in a closed
     * sink. Over the filled surface: the steepest way to a lower level along an edge the water can cross, and across a
     * lake, the lake's own way out.
     */
    public long receiver(long k) {
        int i = ki(k), j = kj(k);
        Tile t = tileOf(i, j);
        int l = local(i, j);
        byte hit = t.flow[l];
        if (hit >= 0) return step(k, hit);
        if (hit == NONE) return Long.MIN_VALUE;
        if (hit == LAKE) return lakeNext(k);
        double f = fill(k);
        double gk = t.g[l];
        if (gk <= sea) {
            t.flow[l] = NONE;
            return Long.MIN_VALUE;
        }
        if (f > gk) {
            t.flow[l] = LAKE;
            return lakeNext(k);
        }
        byte best = NONE;
        double steep = 0;
        for (int s = 0; s < 8; s++) {
            if (!has(k, s) || weight(k, s) > f) continue;
            long n = step(k, s);
            double fn = fill(n);
            if (fn >= f) continue;
            double v = (f - fn) / dist(k, n);
            if (v > steep) {
                steep = v;
                best = (byte) s;
            }
        }
        t.flow[l] = best;
        return best >= 0 ? step(k, best) : Long.MIN_VALUE;
    }

    private long lakeNext(long k) {
        Lake lake = lakeOf(k);
        if (lake == null) return Long.MIN_VALUE;
        return lake.next.get(k);
    }

    // === Catchment ==========================================================

    /** How many nodes drain through this one, itself included, up to {@link #areaCap}. */
    public int area(long k) {
        int known = memoArea(k);
        if (known >= 0) return known;
        // Depth first up the donors, without recursion: a big river has thousands of nodes above it.
        LongArrayList stack = new LongArrayList();
        stack.add(k);
        while (!stack.isEmpty()) {
            if (stack.size() > 1 << 20) throw new IllegalStateException("drainage runs in a loop above " + k);
            long u = stack.getLong(stack.size() - 1);
            if (memoArea(u) >= 0) {
                stack.removeLong(stack.size() - 1);
                continue;
            }
            int sum = 1;
            boolean waiting = false;
            for (long d : donors(u)) {
                int a = memoArea(d);
                if (a < 0) {
                    stack.add(d);
                    waiting = true;
                    break;
                }
                sum += a;
                if (sum >= areaCap) break;
            }
            if (waiting) continue;
            setArea(u, Math.min(sum, areaCap));
            stack.removeLong(stack.size() - 1);
        }
        return memoArea(k);
    }

    /** The nodes whose water comes straight to this one: its neighbours that flow here, and, for a lake, its shore. */
    public long[] donors(long k) {
        LongArrayList out = new LongArrayList(8);
        for (int s = 0; s < 8; s++) {
            if (!has(k, s)) continue;
            long n = step(k, s);
            if (receiver(n) == k) out.add(n);
        }
        return out.toLongArray();
    }

    private int memoArea(long k) {
        int i = ki(k), j = kj(k);
        return tileOf(i, j).area[local(i, j)];
    }

    private void setArea(long k, int v) {
        int i = ki(k), j = kj(k);
        tileOf(i, j).area[local(i, j)] = (short) v;
    }

    // === A small binary heap, level first and key second, so ties break the same way every time ============

    private static final class Heap {
        private double[] lv = new double[64];
        private long[] ks = new long[64];
        private int n;

        boolean isEmpty() {
            return n == 0;
        }

        double topLevel() {
            return lv[0];
        }

        void push(double l, long k) {
            if (n == lv.length) {
                lv = Arrays.copyOf(lv, n * 2);
                ks = Arrays.copyOf(ks, n * 2);
            }
            int i = n++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (!less(l, k, lv[p], ks[p])) break;
                lv[i] = lv[p];
                ks[i] = ks[p];
                i = p;
            }
            lv[i] = l;
            ks[i] = k;
        }

        long pop() {
            long top = ks[0];
            double l = lv[--n];
            long k = ks[n];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= n) break;
                if (c + 1 < n && less(lv[c + 1], ks[c + 1], lv[c], ks[c])) c++;
                if (!less(lv[c], ks[c], l, k)) break;
                lv[i] = lv[c];
                ks[i] = ks[c];
                i = c;
            }
            lv[i] = l;
            ks[i] = k;
            return top;
        }

        private static boolean less(double a, long ka, double b, long kb) {
            return a < b || (a == b && ka < kb);
        }
    }
}
