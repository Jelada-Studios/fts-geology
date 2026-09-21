package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.util.ColumnCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rivers and lakes, worked out from where the water on the raw ground goes.
 *
 * <p>The old rivers were traced down from sources, each on its own, with no idea where any other river ran. They
 * crossed one another, and a third of them ran out of ground and simply stopped with a dry trench beyond. Now the
 * ground is read on a lattice of valley points ({@link DrainageLattice}): every hollow fills to its spill level, the
 * water takes the steepest way down over the filled surface, and a river is wherever enough ground drains through
 * ({@link RiverPieces}). So rivers branch and join but never cross, every one of them reaches the sea or a lake, and
 * a river widens with what it drains.</p>
 *
 * <p>What a column is told is unchanged: the nearest channel, its width, and the water and the floor over it.
 * Everything is a pure function of the seed and the ground, worked out lazily in squares and kept.</p>
 */
public final class RiverNetwork {

    private RiverNetwork() {}

    // === Settings ===========================================================

    /** Blocks between lattice nodes, at the normal world's layout. */
    private static final double CELL = 32.0;
    /**
     * How many nodes the search round a hollow may take before it is called closed, and its lake left with no way
     * out. The search stops at the first node lower than the hollow's floor, so it is short everywhere but in the
     * lowest hollow of a wide basin; the audits found no closed lake at this number. It is a guard, not a budget.
     */
    private static final int MAX_POPS = 16384;
    /**
     * Nodes of catchment for a river to begin, and where the count stops. Thirty-two nodes is about a fifth of a
     * square kilometre at the normal world's layout; measured over two four-kilometre squares it puts 0.55 to 0.8 per
     * cent of the ground in a channel, near the old rivers' share, and 64 halved it.
     */
    static final int AREA_MIN = 32, AREA_CAP = 2048;
    /** Sea level. */
    private static final int SEA = 63;
    /** How far from the middle of a channel the ground still knows about it, for the caves to keep away. */
    public static final double CAVE_REACH = 8.0;
    /** Blocks an index square covers. */
    private static final int BLOCK = 512;

    /**
     * One length of river, or one disc of a lake: where it runs from and to, the water and the floor at each end,
     * how wide the flat floor is, and how far down its river it lies.
     */
    public record Point(float x, float z, float ex, float ez, float water, float waterEnd,
                        float bed, float bedEnd, float halfWidth, float fromHead, byte kind) {
        public boolean lake() {
            return kind == RiverPieces.LAKE;
        }
    }

    // === State ==============================================================

    private static volatile DrainageLattice lattice;
    private static volatile RiverPieces pieces;
    private static volatile DrainageLattice.Ground ground;
    private static volatile double horizontal = 1.0;
    private static final ColumnCache<Point[]> INDEX = new ColumnCache<>(12);
    /** A square being worked out, so a second thread that asks for it waits for the first instead of repeating it. */
    private static final ConcurrentHashMap<Long, CompletableFuture<Point[]>> BUILDING = new ConcurrentHashMap<>();
    /** Squares worked out, the time they took and the longest one, for the log line. */
    private static final java.util.concurrent.atomic.LongAdder SQUARES = new java.util.concurrent.atomic.LongAdder(),
            SQUARE_NANOS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.AtomicLong SLOWEST = new java.util.concurrent.atomic.AtomicLong();

    /** The ground is handed over once a server, as the noise router is first wired and before any column is asked for. */
    public static void open(DrainageLattice.Ground g, long seed, double h) {
        DrainageLattice l = new DrainageLattice(g, seed, CELL * h, SEA, MAX_POPS, AREA_CAP);
        INDEX.clear();
        horizontal = h;
        pieces = new RiverPieces(l, g, h, AREA_MIN);
        ground = g;
        lattice = l;
    }

    public static void clear() {
        lattice = null;
        pieces = null;
        INDEX.clear();
        BUILDING.clear();
    }

    public static boolean ready() {
        return lattice != null;
    }

    /** Blocks a query looks either side of a channel's middle: the widest channel or lake disc, and the cave reach. */
    private static double reach() {
        DrainageLattice l = lattice;
        double cell = l == null ? CELL * horizontal : l.cell;
        return Math.max(RiverPieces.HALF_GROWN * RiverPieces.POOL_WIDE * horizontal, RiverPieces.LAKE_HALF * cell)
                + CAVE_REACH * horizontal + 4.0 * horizontal;
    }

    // === Queries ============================================================

    /** What a column knows about the nearest channel or lake. */
    public record At(double distance, double halfWidth, double water, double bed, double fromHead, boolean lake) {
        /** True at the few columns a river rises from: where the ground first gives its water up. */
        public boolean isHead() {
            return !lake && distance <= 0.75 && fromHead <= RiverPieces.STEP * horizontal * 0.5;
        }

        public boolean inChannel() {
            return distance <= halfWidth;
        }

        /** The channel floor over this column: flat across the bed, then up two blocks for every block out. */
        public double floor() {
            return bed + RiverPieces.WALL * Math.max(0.0, distance - halfWidth);
        }
    }

    public static final At NOTHING = new At(Double.MAX_VALUE, 0, 0, 0, 0, false);
    /** The last column asked about: the offset asks twice for every column, and the water again after. */
    private static final ThreadLocal<long[]> LAST_KEY = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});
    private static final ThreadLocal<At> LAST = new ThreadLocal<>();

    public static At at(int x, int z) {
        long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        long[] k = LAST_KEY.get();
        At hit = LAST.get();
        if (k[0] == key && hit != null) return hit;
        At made = look(x, z);
        k[0] = key;
        LAST.set(made);
        return made;
    }

    private static At look(int x, int z) {
        if (lattice == null) return NOTHING;
        double reach = reach();
        double reach2 = reach * reach;
        double bestD2 = reach2;
        boolean bestLake = false;
        double far = reach + RiverPieces.STEP * horizontal + 1.0, far2 = far * far;
        // The surface, the bed and the width are a weighted mean of every length within reach rather than a copy of
        // the nearest one. Weight falls as the fourth power of the distance, so along a lone river the nearest length
        // is all that counts; where two meet, the two blend across the junction instead of swapping there.
        double weight = 0, half = 0, water = 0, bed = 0, head = 0;
        boolean found = false;
        int bx0 = Math.floorDiv(x - (int) reach, BLOCK), bx1 = Math.floorDiv(x + (int) reach, BLOCK);
        int bz0 = Math.floorDiv(z - (int) reach, BLOCK), bz1 = Math.floorDiv(z + (int) reach, BLOCK);
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (Point p : block(bx, bz)) {
                    double sx = p.x - x, sz = p.z - z;
                    if (sx * sx + sz * sz > far2) continue;
                    double ax = p.ex - p.x, az = p.ez - p.z;
                    double len2 = ax * ax + az * az;
                    double t = len2 < 1e-9 ? 0.0
                            : Math.max(0.0, Math.min(1.0, ((x - p.x) * ax + (z - p.z) * az) / len2));
                    double dx = p.x + ax * t - x, dz = p.z + az * t - z;
                    double d2 = dx * dx + dz * dz;
                    if (d2 >= reach2) continue;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        bestLake = p.lake();
                    }
                    found = true;
                    double w = 1.0 / ((d2 + 1.0) * (d2 + 1.0));
                    weight += w;
                    half += w * p.halfWidth;
                    water += w * (p.water + (p.waterEnd - p.water) * t);
                    bed += w * (p.bed + (p.bedEnd - p.bed) * t);
                    head += w * (p.fromHead + Math.sqrt(len2) * t);
                }
            }
        }
        if (!found) return NOTHING;
        return new At(Math.sqrt(bestD2), half / weight, water / weight, bed / weight, head / weight, bestLake);
    }

    /** How far over the water the cut wall is carried before the hillside is left alone: enough to hold the water. */
    private static final double BANK_RISE = RiverPieces.BANK_RISE;

    /** The floor a channel or lake cuts here, in blocks, or {@link Double#MAX_VALUE} where it cuts nothing. */
    public static double floorAt(int x, int z) {
        At a = at(x, z);
        if (a.distance == Double.MAX_VALUE) return Double.MAX_VALUE;
        double f = a.floor();
        return f > a.water + BANK_RISE * horizontal ? Double.MAX_VALUE : f;
    }

    /** Whether the nearest water here is a lake's rather than a channel's. */
    public static boolean lakeAt(int x, int z) {
        return at(x, z).lake();
    }

    /** 1 over a channel and its banks, fading out over a few blocks: where no cave may open. */
    public static double near(int x, int z) {
        At a = at(x, z);
        if (a.distance == Double.MAX_VALUE) return 0.0;
        double over = a.distance - a.halfWidth;
        if (over <= 0) return 1.0;
        return Math.max(0.0, 1.0 - over / (CAVE_REACH * horizontal));
    }

    /** Whether a column is on a river, for the river biome: a lake is not one. */
    public static boolean onRiver(int x, int z, double share) {
        At a = at(x, z);
        return !a.lake && near(x, z) >= share;
    }

    // === The index ==========================================================

    static final Point[] NONE = new Point[0];

    private static Point[] block(int bx, int bz) {
        long key = ColumnCache.key(bx, bz);
        Point[] hit = INDEX.get(key);
        if (hit != null) return hit;
        CompletableFuture<Point[]> mine = new CompletableFuture<>();
        CompletableFuture<Point[]> other = BUILDING.putIfAbsent(key, mine);
        if (other != null) return other.join();
        try {
            long t0 = System.nanoTime();
            Point[] made = buildBlock(bx, bz);
            long spent = System.nanoTime() - t0;
            SQUARES.increment();
            SQUARE_NANOS.add(spent);
            SLOWEST.accumulateAndGet(spent, Math::max);
            INDEX.put(key, made);
            mine.complete(made);
            return made;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            BUILDING.remove(key, mine);
        }
    }

    /** Every length and disc that starts within reach of this square. */
    private static Point[] buildBlock(int bx, int bz) {
        DrainageLattice l = lattice;
        RiverPieces pc = pieces;
        if (l == null || pc == null) return NONE;
        double edge = reach() + RiverPieces.STEP * horizontal + 1.0;
        double x0 = bx * (double) BLOCK - edge, x1 = (bx + 1) * (double) BLOCK + edge;
        double z0 = bz * (double) BLOCK - edge, z1 = (bz + 1) * (double) BLOCK + edge;
        // A node's length reaches no further from it than half its longest edge, well inside two cells.
        double m = 1.7 * l.cell;
        int i0 = (int) Math.floor((x0 - m) / l.cell), i1 = (int) Math.floor((x1 + m) / l.cell);
        int j0 = (int) Math.floor((z0 - m) / l.cell), j1 = (int) Math.floor((z1 + m) / l.cell);
        List<Point> out = new ArrayList<>();
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                for (Point p : pc.of(DrainageLattice.key(i, j))) {
                    if (p.x >= x0 && p.x < x1 && p.z >= z0 && p.z < z1) out.add(p);
                }
            }
        }
        return out.isEmpty() ? NONE : out.toArray(NONE);
    }

    // === Reports ============================================================

    /** What the model has done so far, for the log line. */
    public static String summary() {
        DrainageLattice l = lattice;
        RiverPieces pc = pieces;
        if (l == null || pc == null) return "no river network";
        return String.format(Locale.ROOT,
                "%d channel lengths (%d straightened, %d dry samples, %d held under a bank), %d lake discs, "
                        + "%d mouths, %d sinks; %d hollows (%d closed), %d lakes, %d ground reads; "
                        + "%d squares in %.0f ms, slowest %.0f ms",
                pc.channels.sum(), pc.straightened.sum(), pc.drySamples.sum(), pc.bankClamps.sum(),
                pc.lakePoints.sum(), pc.mouths.sum(), pc.sinks.sum(), l.pitsFoundCount(), l.closedCount(),
                l.lakesCount(), l.readsCount(), SQUARES.sum(), SQUARE_NANOS.sum() / 1e6, SLOWEST.get() / 1e6);
    }

    /** How much of the ground round here the rivers and lakes hold, and how much of that is under water. */
    public static String report(int cx, int cz, int half, int step) {
        int samples = 0, channel = 0, wet = 0, lake = 0;
        for (int x = cx - half; x <= cx + half; x += step) {
            for (int z = cz - half; z <= cz + half; z += step) {
                samples++;
                At a = at(x, z);
                if (a.distance == Double.MAX_VALUE || !a.inChannel()) continue;
                if (a.lake) lake++; else channel++;
                if (a.water > SEA && a.floor() <= a.water - 0.5) wet++;
            }
        }
        return String.format(Locale.ROOT,
                "rivers within %d of %d,%d every %d: %d samples, %d in a channel (%.2f%%), %d under a lake (%.2f%%), "
                        + "%d under water (wet share %.2f); %s",
                half, cx, cz, step, samples, channel, 100.0 * channel / samples, lake, 100.0 * lake / samples, wet,
                channel + lake == 0 ? 0 : wet / (double) (channel + lake), summary());
    }

    /**
     * A fingerprint of every length and disc that starts in a square round here. The same world has to give the same
     * number whichever order its chunks were made in and however many threads made them.
     */
    public static String hash(int cx, int cz, int half) {
        long h = 0xcbf29ce484222325L;
        int n = 0;
        for (int bx = Math.floorDiv(cx - half, BLOCK); bx <= Math.floorDiv(cx + half, BLOCK); bx++) {
            for (int bz = Math.floorDiv(cz - half, BLOCK); bz <= Math.floorDiv(cz + half, BLOCK); bz++) {
                for (Point p : block(bx, bz)) {
                    // A length is listed in every square it reaches; it is counted in the one it starts in.
                    if (Math.floorDiv((int) Math.floor(p.x), BLOCK) != bx || Math.floorDiv((int) Math.floor(p.z), BLOCK) != bz) continue;
                    if (Math.abs(p.x - cx) > half || Math.abs(p.z - cz) > half) continue;
                    n++;
                    for (float v : new float[]{p.x, p.z, p.ex, p.ez, p.water, p.waterEnd, p.bed, p.bedEnd, p.halfWidth, p.fromHead}) {
                        h = (h ^ Float.floatToIntBits(v)) * 0x100000001b3L;
                    }
                    h = (h ^ p.kind) * 0x100000001b3L;
                }
            }
        }
        return String.format(Locale.ROOT, "rivers hash within %d of %d,%d: %d lengths, %016x", half, cx, cz, n, h);
    }

    /**
     * The rivers round here checked against what they promise: no two cross, none ends inland without a lake, the
     * water never rises downstream or stands over its bank, a river is never narrower below a confluence than the
     * widest river that joins it, and every lake drawn has a way out.
     */
    public static String audit(int cx, int cz, int half) {
        DrainageLattice l = lattice;
        RiverPieces pc = pieces;
        if (l == null || pc == null) return "no river network";
        int i0 = (int) Math.floor((cx - half) / l.cell), i1 = (int) Math.floor((cx + half) / l.cell);
        int j0 = (int) Math.floor((cz - half) / l.cell), j1 = (int) Math.floor((cz + half) / l.cell);
        int channels = 0, heads = 0, mouths = 0, intoLakes = 0, sinks = 0, rising = 0, narrower = 0, overBank = 0;
        int lakes = 0, lakesNoWay = 0, land = 0, flooded = 0;
        int[] byArea = new int[12];
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                long k = DrainageLattice.key(i, j);
                if (l.g(k) <= l.sea) continue;
                land++;
                if (l.flooded(k)) { flooded++; continue; }
                int a = l.area(k);
                for (int b = 0; b < byArea.length && a >= (4 << b); b++) byArea[b]++;
            }
        }
        StringBuilder hist = new StringBuilder();
        for (int b = 0; b < byArea.length; b++) hist.append(' ').append(4 << b).append(':').append(byArea[b]);
        // How deep the hollows are: every lake in the square, drawn or not, by the depth of water over its floor.
        int[] byDepth = new int[6];
        int[] nodesByDepth = new int[6];
        java.util.Set<Long> anyLake = new java.util.HashSet<>();
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                long k = DrainageLattice.key(i, j);
                if (l.g(k) <= l.sea || !l.flooded(k)) continue;
                DrainageLattice.Lake lake = l.lakeOf(k);
                if (lake == null || !anyLake.add(lake.owner)) continue;
                double floor = Double.MAX_VALUE;
                for (long m : lake.members) floor = Math.min(floor, l.g(m));
                double deep = lake.level - floor;
                int b = deep < 1 ? 0 : deep < 2 ? 1 : deep < 4 ? 2 : deep < 8 ? 3 : deep < 16 ? 4 : 5;
                byDepth[b]++;
                nodesByDepth[b] += lake.members.length;
            }
        }
        hist.append("; lakes by depth <1,<2,<4,<8,<16,more:");
        for (int b = 0; b < 6; b++) hist.append(' ').append(byDepth[b]).append('/').append(nodesByDepth[b]);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<double[]> segs = new ArrayList<>();
        List<Long> owner = new ArrayList<>();
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                long k = DrainageLattice.key(i, j);
                Point[] ps = pc.of(k);
                if (ps.length == 0) continue;
                if (pc.underLake(k)) {
                    DrainageLattice.Lake lake = l.lakeOf(k);
                    if (lake != null && seen.add(lake.owner)) {
                        lakes++;
                        boolean way = false;
                        for (long m : lake.members) {
                            long out = lake.next.get(m);
                            if (out != Long.MIN_VALUE && java.util.Arrays.binarySearch(lake.members, out) < 0) { way = true; break; }
                        }
                        if (!way) lakesNoWay++;
                    }
                    continue;
                }
                channels++;
                for (int q = 0; q < ps.length; q++) {
                    Point p = ps[q];
                    if (p.lake()) continue;
                    if (p.waterEnd > p.water + 1e-3f || (q > 0 && p.water > ps[q - 1].waterEnd + 1e-3f)) rising++;
                    segs.add(new double[]{p.x, p.z, p.ex, p.ez});
                    owner.add(k);
                    double dx = p.ex - p.x, dz = p.ez - p.z, len = Math.hypot(dx, dz);
                    if (len > 1e-6) {
                        // Where the cut wall reaches the surface: the ground there has to stand over the water.
                        double nx = -dz / len, nz = dx / len, out = p.halfWidth + RiverPieces.DEPTH * horizontal / RiverPieces.WALL + 1.0;
                        double rim = Math.min(readGround(p.x + nx * out, p.z + nz * out),
                                readGround(p.x - nx * out, p.z - nz * out));
                        if (p.water > rim) overBank++;
                    }
                }
                if (ps[0].fromHead < 1e-3f) heads++;
                double widest = 0;
                for (long d : l.donors(k)) {
                    if (l.area(d) < pc.areaMin) continue;
                    double wd;
                    if (pc.underLake(d)) wd = pc.level(d) - 1.0;
                    else {
                        Point[] dp = pc.of(d);
                        if (dp.length == 0) continue;
                        wd = dp[dp.length - 1].waterEnd;
                        widest = Math.max(widest, dp[dp.length - 1].halfWidth);
                    }
                    if (ps[0].water > wd + 1e-3) rising++;
                }
                if (ps[0].halfWidth + 1e-3 < widest * RiverPieces.STEEP_NARROW) narrower++;
                long r = l.receiver(k);
                if (r == Long.MIN_VALUE) sinks++;
                else if (l.g(r) <= l.sea) mouths++;
                else if (pc.underLake(r)) intoLakes++;
            }
        }
        int crossings = 0;
        java.util.Map<Long, List<Integer>> grid = new java.util.HashMap<>();
        for (int s = 0; s < segs.size(); s++) {
            double[] a = segs.get(s);
            for (int gx = (int) Math.floor(Math.min(a[0], a[2]) / 16); gx <= (int) Math.floor(Math.max(a[0], a[2]) / 16); gx++) {
                for (int gz = (int) Math.floor(Math.min(a[1], a[3]) / 16); gz <= (int) Math.floor(Math.max(a[1], a[3]) / 16); gz++) {
                    grid.computeIfAbsent(ColumnCache.key(gx, gz), q -> new ArrayList<>()).add(s);
                }
            }
        }
        java.util.Set<Long> tested = new java.util.HashSet<>();
        for (List<Integer> cell : grid.values()) {
            for (int a = 0; a < cell.size(); a++) {
                for (int b = a + 1; b < cell.size(); b++) {
                    int sa = cell.get(a), sb = cell.get(b);
                    if (owner.get(sa).equals(owner.get(sb))) continue;
                    if (!tested.add(((long) Math.min(sa, sb) << 32) | Math.max(sa, sb))) continue;
                    if (crosses(segs.get(sa), segs.get(sb))) crossings++;
                }
            }
        }
        return String.format(Locale.ROOT,
                "rivers audit within %d of %d,%d: %d channel nodes, %d heads, %d mouths, %d into lakes, %d ending inland; "
                        + "%d lakes (%d with no way out); crossings %d, rising %d, over the bank %d, narrower below a join %d; "
                        + "%d land nodes, %d under water, drained area at least%s; %s",
                half, cx, cz, channels, heads, mouths, intoLakes, sinks, lakes, lakesNoWay, crossings, rising, overBank,
                narrower, land, flooded, hist, summary());
    }

    private static double readGround(double x, double z) {
        DrainageLattice.Ground g = ground;
        return g == null ? Double.MAX_VALUE : g.heightAt((int) Math.floor(x), (int) Math.floor(z));
    }

    /** Whether two lengths cross anywhere but at a shared end. */
    private static boolean crosses(double[] a, double[] b) {
        if (same(a[0], a[1], b[0], b[1]) || same(a[0], a[1], b[2], b[3]) || same(a[2], a[3], b[0], b[1])
                || same(a[2], a[3], b[2], b[3])) return false;
        double d1 = orient(b, a[0], a[1]), d2 = orient(b, a[2], a[3]), d3 = orient(a, b[0], b[1]), d4 = orient(a, b[2], b[3]);
        return d1 * d2 < -1e-6 && d3 * d4 < -1e-6;
    }

    private static boolean same(double x1, double z1, double x2, double z2) {
        return Math.abs(x1 - x2) < 1e-3 && Math.abs(z1 - z2) < 1e-3;
    }

    private static double orient(double[] s, double x, double z) {
        return (s[2] - s[0]) * (z - s[1]) - (s[3] - s[1]) * (x - s[0]);
    }

    /** The lattice and the pieces, for the audit. */
    static DrainageLattice lattice() {
        return lattice;
    }

    static RiverPieces pieces() {
        return pieces;
    }
}
