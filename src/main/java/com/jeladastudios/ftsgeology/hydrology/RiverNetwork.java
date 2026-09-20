package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.util.ColumnCache;
import com.jeladastudios.ftsgeology.util.SeedHash;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The rivers, traced down the ground the world generator would make if there were none.
 *
 * <p>A source is a point on a wide grid, high enough and inland; from it the trace takes the steepest way down,
 * step by step. It never climbs more than a block or two, and where the ground closes in front of it the trace ends
 * there rather than cut back uphill, which is what every uphill stretch of the old rivers was.</p>
 *
 * <p>The surface of the water comes down with it and never rises: at every point it is a block under the ground
 * there, or the level it already had, whichever is lower. Between two traced points it is read along the line, so
 * the river falls by a block at a time rather than in flats with a rock bar across each step. It can do this because
 * the water is {@code fts_geology:river_water}, which never moves; vanilla water is a horizontal surface by
 * definition and a river made of it has to be a staircase.</p>
 *
 * <p>Every length here is in blocks and scales with the world's layout; the heights do not, both world types
 * putting the ground at {@code 128 + 128 * offset}.</p>
 */
public final class RiverNetwork {

    private RiverNetwork() {}

    /** The ground the generator would make with no river in it, in blocks. */
    public interface Ground {
        double heightAt(int x, int z);
    }

    // === The shape of a river ==============================================

    /** Blocks between the points of a trace. */
    private static final double STEP = 8.0;
    /** Blocks between source points, and the share of them that carry a river. */
    private static final double SOURCE_GRID = 224.0, SOURCE_SHARE = 0.65;
    /** A source stands at least this far above the sea. */
    private static final double SOURCE_RISE = 20.0;
    /** How far a trace may run before it is given up on. */
    private static final double MAX_LENGTH = 2600.0;
    /** The least a trace may run and still be a river, where it does not reach the sea. */
    private static final double MIN_LENGTH = 320.0;
    /** How much the ground may rise in front of a trace before it stops there. */
    private static final double CLIMB_OK = 2.0;
    /** Sea level: where a trace has arrived. */
    private static final int SEA = 63;
    /** Blocks of water in a channel. */
    private static final double DEPTH = 3.0;
    /** How far under the ground beside it a channel may be cut: past this the trace ends rather than cut a gorge. */
    private static final double MAX_CUT = 10.0;
    /** Channel half width, from a young river to a grown one, and the length it grows over. */
    private static final double HALF_NEW = 2.0, HALF_GROWN = 7.0, WIDTH_AT = 1600.0;
    /** How far from the middle of a channel the ground still knows about it, for the caves to keep away. */
    public static final double CAVE_REACH = 8.0;

    /**
     * One length of a traced river, from where it is to the next point along: the water's level and the channel's
     * floor at each end, and how wide the flat floor is. A column reads them along the line.
     */
    public record Point(float x, float z, float ex, float ez, float water, float waterEnd,
                        float bed, float bedEnd, float halfWidth, float fromHead) {}

    private record Trace(Point[] points) {}

    /** Blocks a query looks either side of a channel's middle: the widest channel, its banks and the cave reach. */
    private static final double REACH = HALF_GROWN + CAVE_REACH + 4.0;

    // === State =============================================================

    private static volatile Ground ground;
    private static volatile long forSeed = Long.MIN_VALUE;
    private static volatile double horizontal = 1.0;
    private static final ConcurrentHashMap<Long, Trace> TRACES = new ConcurrentHashMap<>();
    /** Ground already read, on a four-block grid: a trace and its ways round a hollow ask for the same columns. */
    private static final ConcurrentHashMap<Long, Double> HEIGHTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Point[]> INDEX = new ConcurrentHashMap<>();
    /**
     * The raw lines and the cells they cover. Fixed tables rather than maps: joining walks every source cell for a
     * good way round and asks for the same neighbours over and over, so these want to be kept, but a map that only
     * grows would hold on to every river the generator has ever passed. A line is a pure function of the seed and
     * its cell, so an entry that falls out is worked out again to the block.
     */
    private static final ColumnCache<double[][]> RAWS = new ColumnCache<>(14);
    private static final ColumnCache<LongOpenHashSet> SPREAD = new ColumnCache<>(13);
    /** Blocks an index square covers. */
    private static final int BLOCK = 512;

    /** The generator hands the ground over as soon as the noise router is wired, before any column is asked for. */
    public static void useGround(Ground g, long seed, double h) {
        if (seed != forSeed || horizontal != h) {
            TRACES.clear();
            HEIGHTS.clear();
            INDEX.clear();
            RAWS.clear();
            SPREAD.clear();
            forSeed = seed;
            horizontal = h;
        }
        ground = g;
    }

    public static void clear() {
        TRACES.clear();
        HEIGHTS.clear();
        INDEX.clear();
        RAWS.clear();
        SPREAD.clear();
        ground = null;
        forSeed = Long.MIN_VALUE;
    }

    public static boolean ready() {
        return ground != null;
    }

    public static int tracesCut() {
        return TRACES.size();
    }

    /** How many rivers have been cut short: into a bigger one, and back into themselves. */
    private static final LongAdder JOINED = new LongAdder(), LOOPED = new LongAdder();

    /**
     * Why a river stopped, and how long the ones that made it to the sea were. Asked for longer rivers, the first
     * thing to know is what is actually ending them: the length budget is 2600 blocks a trace and the traces come
     * out at a fifth of that, so the budget is not the answer and guessing at the constants would be.
     */
    private static final LongAdder TO_SEA = new LongAdder(), DAMMED = new LongAdder(), RAN_OUT = new LongAdder(),
            TOO_SHORT = new LongAdder(), SEA_BLOCKS = new LongAdder(), POOLS = new LongAdder(),
            LAKES = new LongAdder();

    /** Why the traces ended and what they ran, for the log line. */
    public static String endings() {
        long sea = TO_SEA.sum();
        return String.format(java.util.Locale.ROOT,
                "%d reached the sea (mean %d blocks), %d dammed, %d ran out, %d too short, %d joined, %d looped; "
                        + "%d falls pooled, %d lakes",
                sea, sea == 0 ? 0 : SEA_BLOCKS.sum() / sea, DAMMED.sum(), RAN_OUT.sum(), TOO_SHORT.sum(),
                JOINED.sum(), LOOPED.sum(), POOLS.sum(), LAKES.sum());
    }

    public static long joined() {
        return JOINED.sum();
    }

    public static long looped() {
        return LOOPED.sum();
    }

    // === Queries ===========================================================

    /** What a column knows about the nearest channel. */
    public record At(double distance, double halfWidth, double water, double bed, double fromHead) {
        /**
         * True at the few columns a river rises from: where the ground first gives its water up.
         *
         * <p>A block along was too fine a target. The source is jittered to a fractional place inside its cell and
         * then moved again by the smoothing and the meander, so a window a block and a half by one often had no
         * whole column in it at all: ten springs in fourteen hundred chunks, most rivers with none. Half a traced
         * step along is still a spring mouth rather than a line of them, and a river almost always has one.</p>
         */
        public boolean isHead() {
            return distance <= 0.75 && fromHead <= STEP * horizontal * 0.5;
        }

        public boolean inChannel() {
            return distance <= halfWidth;
        }

        /** The channel floor over this column: flat across the bed, then up two blocks for every block out. */
        public double floor() {
            return bed + WALL * Math.max(0.0, distance - halfWidth);
        }
    }

    /** How steeply a channel's wall climbs out of its bed. At one for one the bank stood only a block over the
     * water two columns out, and the noise on top of it let the pool go. */
    private static final double WALL = 2.0;

    /** How far over the water the cut wall is carried before the hillside is left alone: enough to hold the pool. */
    private static final double BANK_RISE = 2.0;

    public static final At NOTHING = new At(Double.MAX_VALUE, 0, 0, 0, 0);
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
        if (ground == null) return NOTHING;
        double reach = REACH * horizontal;
        double bestD2 = reach * reach;
        // A length is kept by where it starts, so a column within reach of any part of it is within reach plus its
        // length of the start. Most of the lengths in an index square are nowhere near, and this throws them out
        // for five operations instead of projecting onto every one of them.
        double far = reach + STEP * horizontal + 1.0, far2 = far * far;
        double half = 0, water = 0, bed = 0, head = 0;
        boolean found = false;
        int bx0 = Math.floorDiv(x - (int) reach, BLOCK), bx1 = Math.floorDiv(x + (int) reach, BLOCK);
        int bz0 = Math.floorDiv(z - (int) reach, BLOCK), bz1 = Math.floorDiv(z + (int) reach, BLOCK);
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (Point p : block(bx, bz)) {
                    double sx = p.x - x, sz = p.z - z;
                    if (sx * sx + sz * sz > far2) continue;
                    // How far along this length of river the column lies, and how far off it.
                    double ax = p.ex - p.x, az = p.ez - p.z;
                    double len2 = ax * ax + az * az;
                    double t = len2 < 1e-9 ? 0.0
                            : Math.max(0.0, Math.min(1.0, ((x - p.x) * ax + (z - p.z) * az) / len2));
                    double dx = p.x + ax * t - x, dz = p.z + az * t - z;
                    double d2 = dx * dx + dz * dz;
                    if (d2 >= bestD2) continue;
                    bestD2 = d2;
                    found = true;
                    half = p.halfWidth;
                    water = p.water + (p.waterEnd - p.water) * t;
                    bed = p.bed + (p.bedEnd - p.bed) * t;
                    head = p.fromHead + STEP * horizontal * t;
                }
            }
        }
        return found ? new At(Math.sqrt(bestD2), half, water, bed, head) : NOTHING;
    }

    /**
     * The floor a channel cuts here, in blocks, or {@link Double#MAX_VALUE} where it cuts nothing.
     *
     * <p>The cut stops once the wall has climbed a little over the water. The offset takes the lower of the ground
     * and this, so past the bank the channel can only shave the hillside the river runs along, never fill it -- and
     * the query looks {@link #REACH} out, which in the tall world is a shave sixty blocks deep. That is what took
     * most of a mountain away and left a sheer face where the shave stopped.</p>
     */
    public static double floorAt(int x, int z) {
        At a = at(x, z);
        if (a.distance == Double.MAX_VALUE) return Double.MAX_VALUE;
        double f = a.floor();
        return f > a.water + BANK_RISE * horizontal ? Double.MAX_VALUE : f;
    }

    /** 1 over a channel and its banks, fading out over a few blocks: where no cave may open. */
    public static double near(int x, int z) {
        At a = at(x, z);
        if (a.distance == Double.MAX_VALUE) return 0.0;
        double over = a.distance - a.halfWidth;
        if (over <= 0) return 1.0;
        return Math.max(0.0, 1.0 - over / (CAVE_REACH * horizontal));
    }

    /**
     * What the rivers round here look like: how much of the ground their channels hold, how much of that is under
     * water, and for every trace that passes through, its length, how steeply its surface comes down and how much
     * it winds.
     */
    public static String report(int cx, int cz, int half, int step) {
        int samples = 0, channel = 0, wet = 0;
        for (int x = cx - half; x <= cx + half; x += step) {
            for (int z = cz - half; z <= cz + half; z += step) {
                samples++;
                At a = at(x, z);
                if (a.distance == Double.MAX_VALUE || !a.inChannel()) continue;
                channel++;
                if (a.water > SEA && a.floor() <= a.water - 0.5) wet++;
            }
        }
        int traces = 0, uphill = 0, steps = 0;
        double length = 0, sinuosity = 0, fall = 0, biggest = 0;
        for (Trace t : TRACES.values()) {
            if (t.points.length < 2) continue;
            boolean here = false;
            for (Point p : t.points) {
                if (Math.abs(p.x - cx) <= half && Math.abs(p.z - cz) <= half) { here = true; break; }
            }
            if (!here) continue;
            traces++;
            double len = 0;
            Point a = t.points[0], b = t.points[t.points.length - 1];
            for (Point p : t.points) {
                double dx = p.ex - p.x, dz = p.ez - p.z;
                double run = Math.sqrt(dx * dx + dz * dz);
                len += run;
                double drop = p.water - p.waterEnd;
                if (drop < -0.01) uphill++;
                if (run > 0.5) {
                    // How far the surface comes down over one block of river: a staircase of pools showed whole
                    // blocks at a time, a river that falls with the ground shows a fraction.
                    steps++;
                    fall += drop / run;
                    biggest = Math.max(biggest, drop / run);
                }
            }
            length += len;
            double straight = Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z));
            if (straight > 1) sinuosity += len / straight;
        }
        return String.format(java.util.Locale.ROOT,
                "rivers within %d of %d,%d every %d: %d samples, %d in a channel (%.2f%%), %d under water (%.2f%%), "
                        + "wet share of channel %.2f; %d traces through, mean length %.0f, mean sinuosity %.2f, "
                        + "mean fall %.3f blocks per block, steepest %.2f, uphill lengths %d (%d traces cut, %d joined, %d looped)",
                half, cx, cz, step, samples, channel, 100.0 * channel / samples, wet, 100.0 * wet / samples,
                channel == 0 ? 0 : wet / (double) channel, traces, traces == 0 ? 0 : length / traces,
                traces == 0 ? 0 : sinuosity / traces, steps == 0 ? 0 : fall / steps, biggest, uphill,
                TRACES.size(), joined(), looped());
    }

    // === The index =========================================================

    private static final Point[] NONE = new Point[0];

    private static Point[] block(int bx, int bz) {
        long key = (((long) bx) << 32) ^ (bz & 0xFFFFFFFFL);
        Point[] hit = INDEX.get(key);
        if (hit != null) return hit;
        Point[] made = buildBlock(bx, bz);
        INDEX.put(key, made);
        return made;
    }

    /** Every traced length that falls in this square, from all the sources whose river could reach it. */
    private static Point[] buildBlock(int bx, int bz) {
        double h = horizontal;
        double grid = SOURCE_GRID * h;
        double span = MAX_LENGTH * h + BLOCK;
        int x0 = bx * BLOCK, z0 = bz * BLOCK;
        int gx0 = (int) Math.floor((x0 - span) / grid), gx1 = (int) Math.floor((x0 + BLOCK + span) / grid);
        int gz0 = (int) Math.floor((z0 - span) / grid), gz1 = (int) Math.floor((z0 + BLOCK + span) / grid);
        List<Point> out = new ArrayList<>();
        // A length is kept by where it starts, so the square has to reach a length further out than the query does.
        double edge = REACH * h + STEP * h + 1.0;
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gz = gz0; gz <= gz1; gz++) {
                for (Point p : trace(gx, gz).points) {
                    if (p.x >= x0 - edge && p.x < x0 + BLOCK + edge && p.z >= z0 - edge && p.z < z0 + BLOCK + edge) {
                        out.add(p);
                    }
                }
            }
        }
        return out.isEmpty() ? NONE : out.toArray(new Point[0]);
    }

    private static final Trace EMPTY = new Trace(NONE);

    private static Trace trace(int gx, int gz) {
        long key = (((long) gx) << 32) ^ (gz & 0xFFFFFFFFL);
        Trace hit = TRACES.get(key);
        if (hit != null) return hit;
        Trace made = cut(gx, gz);
        if (made == null) made = EMPTY;
        TRACES.put(key, made);
        return made;
    }

    // === Tracing ===========================================================

    /**
     * The river from one source cell, or null where that cell has none or its river gets nowhere.
     *
     * <p>Two steps, and the split matters. {@link #raw} traces the ground and knows nothing of any other river, so
     * it depends on nothing but the seed and its own cell and can be worked out by any thread in any order. This
     * then joins that raw line to the bigger river it runs into, which it can only do by looking at raw lines --
     * never at finished ones -- so the answer is still the same whichever chunk asked first.</p>
     */
    private static Trace cut(int gx, int gz) {
        Ground g = ground;
        if (g == null) return null;
        double[][] source = raw(gx, gz);
        if (source == null) return null;
        double h = horizontal;
        Joined joined = join(g, gx, gz, source);
        double[][] pts = joined.pts();
        boolean merged = joined.merged();
        if (pts.length < 2) return null;

        // 3. The surface: a block under the ground, and never higher than it already was. Where the ground rises
        //    more than the channel may be cut into, the river ends rather than carry its level through in a gorge.
        double depth = DEPTH * h, cut = MAX_CUT * h;
        double[] level = new double[pts.length];
        int last = -1;
        for (int i = 0; i < pts.length; i++) {
            double want = Math.min(pts[i][2] - 1.0, rim(g, pts, i, h) - 1.0);
            double lv = i == 0 ? want : Math.min(level[i - 1], want);
            if (i > 0 && want > lv + cut) break;
            level[i] = lv;
            last = i;
        }
        if (last < 1) return null;
        boolean dammed = last < pts.length - 1;
        if (dammed) DAMMED.increment();

        // 4. Where the water falls, it lands in something; where a river simply ends inland, it ends in a lake.
        //    Both only ever widen and deepen the channel and never lift the water, so neither can leave water
        //    standing over the ground: the level a point was given is the one it keeps.
        double[] wide = new double[pts.length], deep = new double[pts.length];
        java.util.Arrays.fill(wide, 1.0);
        java.util.Arrays.fill(deep, 1.0);
        for (int i = 0; i < last; i++) {
            if (level[i] - level[i + 1] < FALL_MIN) continue;
            POOLS.increment();
            for (int k = i + 1; k <= Math.min(last, i + POOL_RUN); k++) {
                wide[k] = Math.max(wide[k], POOL_WIDE);
                deep[k] = Math.max(deep[k], POOL_DEEP);
            }
        }
        // A river the ground dammed is the one that most needs a lake: that is what the rise in front of it is.
        if (!merged && level[last] > SEA + LAKE_OVER) {
            LAKES.increment();
            double lake = level[last];
            for (int k = Math.max(1, last - LAKE_RUN); k <= last; k++) {
                level[k] = lake;
                wide[k] = Math.max(wide[k], LAKE_WIDE);
                deep[k] = Math.max(deep[k], LAKE_DEEP);
            }
        }

        List<Point> out = new ArrayList<>(last + 1);
        double step = STEP * h;
        for (int i = 0; i < last; i++) {
            // How fast the water is falling over this length, in blocks of drop per block of run.
            double fall = (level[i] - level[i + 1]) / step;
            double t = Math.max(0.0, Math.min(1.0, 1.0 - (fall - STEEP_FROM) / STEEP_OVER));
            double gentle = t * t * (3.0 - 2.0 * t);
            double narrow = STEEP_NARROW + (1.0 - STEEP_NARROW) * gentle;
            double half = (HALF_NEW + (HALF_GROWN - HALF_NEW) * Math.min(1.0, i * STEP / WIDTH_AT)) * h
                    * narrow * Math.max(wide[i], wide[i + 1]);
            double d0 = depth * deep[i], d1 = depth * deep[i + 1];
            out.add(new Point((float) pts[i][0], (float) pts[i][1], (float) pts[i + 1][0], (float) pts[i + 1][1],
                    (float) level[i], (float) level[i + 1],
                    (float) (level[i] - d0), (float) (level[i + 1] - d1), (float) half,
                    (float) (i * STEP * h)));
        }
        return new Trace(out.toArray(new Point[0]));
    }

    /**
     * A fall and what it lands in: a drop of this many blocks between two traced points, the points below it that
     * are given a plunge pool, and how much wider and deeper that pool is than the channel.
     */
    private static final double FALL_MIN = 10.0, POOL_WIDE = 1.8, POOL_DEEP = 1.6;
    private static final int POOL_RUN = 3;

    /**
     * How much of its width a river keeps where it is running steeply, and the two gradients that ramp between.
     *
     * <p>A channel's width came from its length alone, so a river that had run far enough to be wide stayed that
     * wide when it went over the edge of a mountain: thirty-five blocks of water in a straight band down a face,
     * the same width all the way, which is the one thing no river does. A torrent is narrow and a lowland river
     * is wide, and the difference is the gradient it runs at.</p>
     */
    private static final double STEEP_NARROW = 0.35, STEEP_FROM = 0.15, STEEP_OVER = 0.5;

    /**
     * The lake a river ends in when it ends inland: how far over the sea its last water has to stand for there to
     * be one, how many of its last points become the lake, and how much wider and deeper they are.
     *
     * <p>A river that reaches the sea has somewhere to go; one that runs out of ground does not, and before this
     * it simply stopped in its own trench. The lake only ever lowers the water to the level of the last point and
     * widens the channel, so it can hold nothing higher than the ground already allowed, and the shave clamp in
     * {@code RiverDensity} keeps it from biting a basin out of a hillside that has none.</p>
     */
    private static final double LAKE_OVER = 1.0, LAKE_WIDE = 3.0, LAKE_DEEP = 1.8;
    private static final int LAKE_RUN = 4;

    // === Joining ===========================================================

    /**
     * How close two rivers come before the smaller of them counts as having run into the larger. A grown channel is
     * fourteen blocks across, so anything nearer than this is not two rivers but one drawn twice.
     */
    private static final double MERGE_DIST = 12.0;
    /** How far out, in source cells, a river looks for the one it might be a tributary of. */
    private static final int MERGE_CELLS = 8;
    /** How far apart along its own line a river has to be before it may be said to have met itself. */
    private static final int LOOP_GAP = 8;

    /**
     * The raw line, cut short where it runs into a bigger river or back into itself, with its last point moved onto
     * the river it joins so the two channels meet.
     *
     * <p>Nothing here merged before, and it showed in two ways. Two sources on one hillside ran the same descent
     * down the same fall line -- eight compass headings and a momentum term that holds a heading will do that --
     * and neither knew the other was there, so the world got a pair of channels ten blocks apart running side by
     * side: a dual carriageway. And a line that came back near ground it had already crossed drew a closed ring.</p>
     *
     * <p>Which of two rivers gives way is decided by length, then by cell, so it is the tributary that ends at the
     * trunk and never the other way about, and the answer does not depend on which was traced first.</p>
     */
    /** A raw line after joining, and whether it was cut short into another river or back into itself. */
    private record Joined(double[][] pts, boolean merged) {}

    private static Joined join(Ground g, int gx, int gz, double[][] source) {
        double h = horizontal;
        double cell = MERGE_DIST * h;
        int at = -1;
        double[] onto = null;

        // Itself first: a ring is a line that came back to ground it had already covered. The river ends where it
        // came back -- at the point it returned TO, not the one it returned WITH. Cutting at the returning end
        // left the whole ring in the line, and since the two arms are nearer than a grown channel is wide they
        // then ran into one another and closed it: a river round an island.
        outer:
        for (int i = LOOP_GAP; i < source.length; i++) {
            for (int k = 0; k <= i - LOOP_GAP; k++) {
                double dx = source[k][0] - source[i][0], dz = source[k][1] - source[i][1];
                if (dx * dx + dz * dz <= cell * cell) {
                    at = k;
                    break outer;
                }
            }
        }

        long mine = key(gx, gz);
        int reach = at < 0 ? source.length : at;
        for (int ox = -MERGE_CELLS; ox <= MERGE_CELLS; ox++) {
            for (int oz = -MERGE_CELLS; oz <= MERGE_CELLS; oz++) {
                if (ox == 0 && oz == 0) continue;
                int nx = gx + ox, nz = gz + oz;
                double[][] other = raw(nx, nz);
                if (other == null) continue;
                if (!yields(source.length, mine, other.length, key(nx, nz))) continue;
                LongOpenHashSet near = spread(nx, nz, other, cell);
                for (int i = 2; i < reach; i++) {
                    if (!near.contains(cellKey(source[i][0], source[i][1], cell))) continue;
                    double best = Double.MAX_VALUE;
                    double[] pick = null;
                    for (double[] q : other) {
                        double dx = q[0] - source[i][0], dz = q[1] - source[i][1];
                        double d2 = dx * dx + dz * dz;
                        if (d2 < best) { best = d2; pick = q; }
                    }
                    at = i;
                    onto = pick;
                    reach = i;
                    break;
                }
            }
        }

        if (at < 0) return new Joined(source, false);
        if (onto == null) LOOPED.increment(); else JOINED.increment();
        // The joining point is kept and the last stretch to the river itself is walked out in the trace's own
        // steps, so the two channels meet instead of stopping a dozen blocks short of each other. The ground is
        // read at every one of them: a single long jump can hop a bank, and then the water is asked to stand over
        // ground the trace never looked at.
        int keep = at + 1;
        if (onto == null) {
            double[][] shut = new double[keep][];
            System.arraycopy(source, 0, shut, 0, keep);
            return new Joined(shut, true);
        }
        double step = STEP * h;
        double gap = Math.hypot(onto[0] - source[at][0], onto[1] - source[at][1]);
        int walk = Math.max(1, (int) Math.ceil(gap / step));
        double[][] out = new double[keep + walk][];
        System.arraycopy(source, 0, out, 0, keep);
        for (int k = 1; k <= walk; k++) {
            double f = k / (double) walk;
            double px = source[at][0] + (onto[0] - source[at][0]) * f;
            double pz = source[at][1] + (onto[1] - source[at][1]) * f;
            out[keep + k - 1] = new double[]{px, pz, height(g, px, pz)};
        }
        return new Joined(out, true);
    }

    /** True where the first river is the one that gives way: the shorter, or on a tie the one with the lower cell. */
    private static boolean yields(int myLength, long myKey, int otherLength, long otherKey) {
        if (myLength != otherLength) return myLength < otherLength;
        return myKey > otherKey;
    }

    private static long cellKey(double x, double z, double cell) {
        return ColumnCache.key((int) Math.floor(x / cell), (int) Math.floor(z / cell));
    }

    /** The cells a river covers, each with its eight neighbours, so one lookup answers "is it near here". */
    private static LongOpenHashSet spread(int gx, int gz, double[][] pts, double cell) {
        long key = key(gx, gz);
        LongOpenHashSet hit = SPREAD.get(key);
        if (hit != null) return hit;
        LongOpenHashSet made = new LongOpenHashSet(pts.length * 9);
        for (double[] p : pts) {
            int cx = (int) Math.floor(p[0] / cell), cz = (int) Math.floor(p[1] / cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) made.add(ColumnCache.key(cx + dx, cz + dz));
            }
        }
        SPREAD.put(key, made);
        return made;
    }

    // === Tracing ===========================================================

    private static final double[][] NO_SOURCE = new double[0][];

    /** The bare line down the ground from one source cell, before any other river is taken into account. */
    private static double[][] raw(int gx, int gz) {
        long key = key(gx, gz);
        double[][] hit = RAWS.get(key);
        if (hit != null) return hit == NO_SOURCE ? null : hit;
        double[][] made = trail(gx, gz);
        RAWS.put(key, made == null ? NO_SOURCE : made);
        return made;
    }

    private static double[][] trail(int gx, int gz) {
        Ground g = ground;
        if (g == null) return null;
        long seed = forSeed;
        double h = horizontal;
        double grid = SOURCE_GRID * h, step = STEP * h;
        long hash = SeedHash.hash(seed, gx, gz, 0x5217L);
        if (SeedHash.rand01(hash) > SOURCE_SHARE) return null;
        double x = (gx + 0.2 + 0.6 * SeedHash.rand01(SeedHash.mix(hash ^ 0xA1L))) * grid;
        double z = (gz + 0.2 + 0.6 * SeedHash.rand01(SeedHash.mix(hash ^ 0xB2L))) * grid;
        double y = height(g, x, z);
        if (y < SEA + SOURCE_RISE) return null;

        // 1. The way down: the steepest of the ways that do not turn back, with the ground two steps on weighed in
        //    as well, so a hollow one step wide does not end the river.
        List<double[]> path = new ArrayList<>();
        path.add(new double[]{x, z, y});
        double dirX = 0, dirZ = 0;
        boolean sea = false;
        int steps = (int) (MAX_LENGTH / STEP);
        for (int i = 0; i < steps; i++) {
            double bestScore = Double.MAX_VALUE, bx = 0, bz = 0, by = 0, bdx = 0, bdz = 0;
            for (int d = 0; d < 8; d++) {
                double a = d * Math.PI / 4.0;
                double ux = Math.cos(a), uz = Math.sin(a);
                double dot = dirX * ux + dirZ * uz;
                if ((dirX != 0 || dirZ != 0) && dot < -0.3) continue;   // anything but straight back
                double nx = x + ux * step, nz = z + uz * step;
                double ny = height(g, nx, nz);
                double ahead = height(g, x + ux * step * 2, z + uz * step * 2);
                double score = ny + 0.5 * ahead - 1.5 * dot;
                if (score < bestScore) {
                    bestScore = score;
                    bx = nx;
                    bz = nz;
                    by = ny;
                    bdx = ux;
                    bdz = uz;
                }
            }
            if (bestScore == Double.MAX_VALUE) break;
            if (by > y + CLIMB_OK) {
                // Ground in front of it: a river fills the hollow and leaves by the lowest lip round it, so the
                // trace looks for a way round before it gives up. Stopping at the first rise left every river a
                // few hundred blocks long.
                List<double[]> round = escape(g, x, z, y, step);
                if (round == null) break;
                for (double[] q : round) path.add(q);
                double[] end = round.get(round.size() - 1);
                double[] before = round.size() > 1 ? round.get(round.size() - 2) : new double[]{x, z, y};
                dirX = end[0] - before[0];
                dirZ = end[1] - before[1];
                double dl = Math.sqrt(dirX * dirX + dirZ * dirZ);
                if (dl > 1e-6) { dirX /= dl; dirZ /= dl; }
                x = end[0];
                z = end[1];
                y = end[2];
                i += round.size();
                if (y <= SEA) { sea = true; break; }
                continue;
            }
            x = bx;
            z = bz;
            y = by;
            dirX = bdx;
            dirZ = bdz;
            path.add(new double[]{x, z, y});
            if (y <= SEA) {
                sea = true;
                break;
            }
        }
        if (!sea && (path.size() - 1) * step < MIN_LENGTH * h) {
            TOO_SHORT.increment();
            return null;
        }
        if (sea) {
            TO_SEA.increment();
            SEA_BLOCKS.add((long) ((path.size() - 1) * step));
        } else {
            RAN_OUT.increment();
        }

        // 2. Rounded off, and winding where the ground is flat enough to let it.
        double[][] pts = smooth(path);
        for (double[] p : pts) p[2] = height(g, p[0], p[1]);
        meander(g, pts, seed, gx, gz, h);
        for (double[] p : pts) p[2] = height(g, p[0], p[1]);
        return pts;
    }

    /**
     * The lower of the two banks beside a traced point, in blocks of ground.
     *
     * <p>The offset takes the lower of the land and the channel, so a channel only ever cuts and never fills. On a
     * hillside that means the downhill side of a river is whatever the hill happened to be -- and where the hill
     * is under the water the river is left standing in the air with nothing holding it in. The water may stand no
     * higher than the lower of its two banks, and this is where that is read. It is read from the same analytic
     * ground the trace is cut from, so it costs a cached lookup and stays a pure function of the seed: a block
     * read would have had to cross a chunk line, and then the answer would depend on which chunk came first.</p>
     */
    private static double rim(Ground g, double[][] pts, int i, double h) {
        double half = (HALF_NEW + (HALF_GROWN - HALF_NEW) * Math.min(1.0, i * STEP / WIDTH_AT)) * h;
        double out = half + BANK_RISE * h;
        int a = Math.max(i - 1, 0), b = Math.min(i + 1, pts.length - 1);
        double ax = pts[b][0] - pts[a][0], az = pts[b][1] - pts[a][1];
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1e-6) return Double.MAX_VALUE;
        double nx = -az / len * out, nz = ax / len * out;
        return Math.min(height(g, pts[i][0] + nx, pts[i][1] + nz),
                height(g, pts[i][0] - nx, pts[i][1] - nz));
    }

    /** How many cells the search round a hollow may look at before the river is given up on. */
    private static final int ESCAPE_CELLS = 400;

    /**
     * The way out of a hollow: the cells round it are taken lowest first until one lies below where the river
     * stopped, and the path to it comes back. Null where there is none within reach, and the river ends.
     */
    private static List<double[]> escape(Ground g, double x0, double z0, double y0, double step) {
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingDouble(c -> Double.longBitsToDouble(c[2])));
        java.util.HashMap<Long, long[]> seen = new java.util.HashMap<>();
        long start = key(0, 0);
        open.add(new long[]{0, 0, Double.doubleToRawLongBits(y0), Long.MIN_VALUE});
        seen.put(start, new long[]{0, 0, Double.doubleToRawLongBits(y0), Long.MIN_VALUE});
        int looked = 0;
        while (!open.isEmpty() && looked++ < ESCAPE_CELLS) {
            long[] cell = open.poll();
            int ci = (int) cell[0], cj = (int) cell[1];
            double cy = Double.longBitsToDouble(cell[2]);
            if (cy < y0 - 0.5 && (ci != 0 || cj != 0)) {
                java.util.ArrayList<double[]> path = new java.util.ArrayList<>();
                long[] at = cell;
                while (at != null && at[3] != Long.MIN_VALUE) {
                    path.add(0, new double[]{x0 + (int) at[0] * step, z0 + (int) at[1] * step,
                            Double.longBitsToDouble(at[2])});
                    at = seen.get(at[3]);
                }
                return path.isEmpty() ? null : path;
            }
            for (int d = 0; d < 8; d++) {
                double a = d * Math.PI / 4.0;
                int ni = ci + (int) Math.round(Math.cos(a)), nj = cj + (int) Math.round(Math.sin(a));
                if (Math.abs(ni) > 24 || Math.abs(nj) > 24) continue;
                long k = key(ni, nj);
                if (seen.containsKey(k)) continue;
                double ny = height(g, x0 + ni * step, z0 + nj * step);
                long[] made = new long[]{ni, nj, Double.doubleToRawLongBits(ny), key(ci, cj)};
                seen.put(k, made);
                open.add(made);
            }
        }
        return null;
    }

    /** The ground at a point, on a four-block grid and kept: the same columns are asked for again and again. */
    private static double height(Ground g, double x, double z) {
        int ix = ((int) Math.floor(x)) & ~3, iz = ((int) Math.floor(z)) & ~3;
        long k = key(ix >> 2, iz >> 2);
        Double hit = HEIGHTS.get(k);
        if (hit != null) return hit;
        double y = g.heightAt(ix, iz);
        if (HEIGHTS.size() < 4_000_000) HEIGHTS.put(k, y);
        return y;
    }

    private static long key(int i, int j) {
        return (((long) i) << 32) ^ (j & 0xFFFFFFFFL);
    }

    /** Chaikin's corner cutting, twice: a path of steps on a compass turns into a curve. */
    private static double[][] smooth(List<double[]> path) {
        double[][] p = path.toArray(new double[0][]);
        for (int pass = 0; pass < 2 && p.length > 3; pass++) {
            double[][] q = new double[(p.length - 1) * 2][];
            for (int i = 0; i < p.length - 1; i++) {
                q[2 * i] = new double[]{p[i][0] * 0.75 + p[i + 1][0] * 0.25, p[i][1] * 0.75 + p[i + 1][1] * 0.25, 0};
                q[2 * i + 1] = new double[]{p[i][0] * 0.25 + p[i + 1][0] * 0.75, p[i][1] * 0.25 + p[i + 1][1] * 0.75, 0};
            }
            // Every other one kept, so the step between points stays about what it was.
            double[][] r = new double[q.length / 2 + 1][];
            for (int i = 0; i < r.length - 1; i++) r[i] = q[i * 2];
            r[r.length - 1] = q[q.length - 1];
            p = r;
        }
        return p;
    }

    /**
     * The winding. On flat ground a river swings well off the line it was traced along; against a slope it would
     * only cut a trench across the hillside, so the swing is cut back to what the ground either side allows.
     */
    private static void meander(Ground g, double[][] pts, long seed, int gx, int gz, double h) {
        int n = pts.length;
        if (n < 10) return;
        double[] ox = new double[n], oz = new double[n];
        double phase = SeedHash.rand01(SeedHash.hash(seed, gx, gz, 0x3BE1L)) * Math.PI * 2;
        double wave = 22.0 * STEP * h;                  // blocks of river to a full swing
        double amp = 7.0 * h;
        for (int i = 2; i < n - 2; i++) {
            double px = pts[i + 1][0] - pts[i - 1][0], pz = pts[i + 1][1] - pts[i - 1][1];
            double len = Math.sqrt(px * px + pz * pz);
            if (len < 1e-6) continue;
            double nx = -pz / len, nz = px / len;
            double s = i * STEP * h;
            double swing = 0.7 * Math.sin(phase + s * 2 * Math.PI / wave)
                    + 0.3 * Math.sin(phase * 1.7 + s * 2 * Math.PI / (wave * 0.37));
            // How much room the swing has: the ground a swing's width either side, against the ground here.
            double here = pts[i][2];
            double left = height(g, pts[i][0] + nx * amp, pts[i][1] + nz * amp);
            double right = height(g, pts[i][0] - nx * amp, pts[i][1] - nz * amp);
            double rise = Math.max(left, right) - here;
            double room = rise <= 2.0 ? 1.0 : Math.max(0.0, 1.0 - (rise - 2.0) / 6.0);
            ox[i] = nx * amp * swing * room;
            oz[i] = nz * amp * swing * room;
        }
        // Eased in at both ends, so the mouth and the source stay where they were traced.
        for (int i = 0; i < n; i++) {
            double ease = Math.min(1.0, Math.min(i, n - 1 - i) / 6.0);
            pts[i][0] += ox[i] * ease;
            pts[i][1] += oz[i] * ease;
        }
    }
}
