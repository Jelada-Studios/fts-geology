package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.util.ColumnCache;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.SetCache;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * The channels and lakes drawn over the drainage lattice, as the lengths a column is measured against.
 *
 * <p>Every node owns a cell: the points nearer to it, in the barycentric sense, than to the other two corners of the
 * triangle they lie in. The cells cover the ground without overlapping, and whatever a node draws stays inside its own
 * cell, so the rivers of different nodes cannot cross. Water passes from one cell to the next at the lowest point of
 * the line between them -- where the valley crosses it, not half way along the edge -- and inside a cell it follows
 * the valley, traced over the ground on a fine grid.</p>
 *
 * <p>A river ends where another water begins. A river that joins a bigger one is carried on through the bigger one's
 * cell to its channel; a river running into a lake, to the lake's water; into the sea, to where the ground goes under
 * the sea. The node that receives the water draws those last reaches, so two rivers joining the same channel meet it one
 * after the other and never cross. A lake is not drawn from its nodes but from the hollow itself: the ground under its
 * water, flooded out from its deepest points and no further than a few steps past its own cells.</p>
 *
 * <p>The water comes down the river and never goes up. At every point it is under the filled surface and a block under
 * the lower of the two banks, and no higher than it was just upstream.</p>
 */
final class RiverPieces {

    static final byte CHANNEL = 0, LAKE = 1, JOIN = 2;

    /** Blocks between the points of a length, at the normal world's layout. */
    static final double STEP = 8.0;
    /** The deepest a channel is cut under its water, in blocks at the normal world's layout. */
    static final double DEPTH = 3.0;
    /** A new stream's half width in blocks, in any layout: a rill two blocks across, with no gaps down its middle. */
    static final double HALF_MIN = 0.75;
    /** A grown river's half width, at the normal world's layout. */
    static final double HALF_GROWN = 7.0;
    /**
     * How fast a stream widens with the ground it drains: the power of the drained share its width follows. At 1.5 a
     * river that drained half the most any does was still a third of the width of the widest, and a stream stayed a
     * rill for most of its way down; at 0.6 it is two thirds.
     */
    static final double WIDEN = 0.6;
    /** How deep a stream runs for its width: a rill a block and a half, a river its full depth. */
    static final double DEPTH_LEAST = 1.5, DEPTH_BASE = 0.6, DEPTH_PER_HALF = 0.6;
    /** How steeply a channel's wall climbs out of its bed, and how far over the water it is carried. */
    static final double WALL = 2.0, BANK_RISE = 2.0;
    /** How far under its water a lake's floor lies, where the hollow is not already deeper. */
    static final double LAKE_BED = 2.0;
    /**
     * A hollow shallower than this is not a lake: the river cuts through its rim instead. Most of the hollows the
     * lattice finds on flat ground are a block deep or less -- noise in the ground, not lakes -- and filled, they
     * dotted every plain with ponds and put a tenth of the land under water.
     */
    static final double LAKE_MIN_DEPTH = 4.0;
    /** How far under its raw ground a channel may be cut; the density clamps the cut at this. */
    static final double MAX_SHAVE = 12.0;
    /** The deepest gorge a channel cuts through a hill in its way, in blocks at the normal world's layout. */
    static final double CUT_MAX = 32.0;
    /** The share of springs that rise in an eye, and how wide an eye is, in blocks at the normal world's layout. */
    static final double EYE_SHARE = 0.4, EYE_HALF = 1.8;
    /** How many points a river out of a lake keeps the lake's level for. */
    static final int LAKE_HOLD = 2;
    /**
     * How far from a river's mouth the water it runs into may reach and still be a lagoon, how wide a bar may be cut
     * through, in blocks at the normal world's layout, and the step the water is searched at.
     */
    static final double INLET_REACH = 64.0, INLET_BAR = 16.0, INLET_STEP = 4.0;
    /** The most water a lagoon may hold, in steps of the search, before it is taken for the sea. */
    static final int INLET_BUDGET = 3000;
    /** A fall of this many blocks between two points, and the points below it that get a plunge pool. */
    static final double POOL_FALL = 4.0, POOL_WIDE = 1.8, POOL_DIG = 0.3, POOL_DIG_MAX = 3.0;
    static final int POOL_RUN = 3;
    /** How much of its width a torrent keeps, and the gradients that ramp between a torrent and a lowland river. */
    static final double STEEP_NARROW = 0.7, STEEP_FROM = 0.15, STEEP_OVER = 0.5;
    /**
     * The most a channel's width changes from one point to the next, as a ratio. A torrent is narrower than the river
     * it becomes, but a steep length drawn a third as wide as the gentle ones either side of it read as a river pinched
     * shut and let out again.
     */
    static final double WIDTH_STEP = 1.2;
    /** How much deeper under the sea, per unit of layout past the normal world's, a mouth is carried out to. */
    static final double MOUTH_DEEPER = 2.0;
    /** A river that the ground closes round ends in a pond this wide, as a share of the lattice cell. */
    static final double SINK_HALF = 0.33;
    /** Where a river's length is counted from, for a river that leaves a lake: far enough that no spring opens there. */
    private static final float FROM_LAKE = 1.0e4f;

    /**
     * How a way through a cell is weighed. A step costs its length, times one more for every VALLEY blocks (at the
     * normal layout) it stands over the lower end of the way, times a slow noise that lets a way across flat ground
     * wander; climbing costs UPHILL a block on top. Water keeps to the floor of its valley and, where there is none,
     * meanders.
     */
    private static final double VALLEY = 3.0, UPHILL = 2.0, WIGGLE = 1.5, WIGGLE_WAVE = 3.0;
    /**
     * Blocks of height a slow noise adds to the line between two cells when the crossing is picked. On a slope the
     * ground settles where the water crosses; on flat ground every point of the line is as low as the next, the middle
     * won every time, and a river crossing a plain ran from the middle of one edge to the middle of the next in a
     * straight row of cells.
     */
    private static final double HANDOFF_WANDER = 2.0;
    /** Grid steps a way runs straight along the line between two cells as it leaves and enters one. */
    private static final double LEAD = 1.1;
    /** Corner-cutting passes over a way. */
    private static final int SMOOTH_PASSES = 1;
    /** How far round a river bends, in its own half widths: a bend two to three channel widths across. */
    private static final double FILLET = 5.0;
    /**
     * How far from a straight line the grid's way may stray before it is kept, in half widths: a wide river does not
     * follow every kink of a trace laid out a grid step at a time.
     */
    private static final double STRAIGHTEN = 3.0;
    /** How far over its lower bank the water may be held to meet the water it runs into; the bank is built up. */
    private static final double BANK_HOLD = 2.0;
    /** Grid points to a height tile. */
    private static final int TILE = 8;
    /** A node's neighbour slots in turn round it: east, north-east, north, and on. */
    private static final int[] RING = {0, 4, 1, 5, 2, 6, 3, 7};
    /** Where the water crosses from one cell to the next: this far along the line between them, either way of middle. */
    private static final double HANDOFF_SPAN = 0.8;
    private static final int HANDOFF_SAMPLES = 6;
    /** How far past its own cells a lake's water may reach, in grid steps, following the hollow. */
    private static final int LAKE_REACH = 3;

    private final DrainageLattice lat;
    private final DrainageLattice.Ground ground;
    private final double h;
    final int areaMin;
    /** Blocks between the points a way is traced over and a lake is flooded on. */
    final int grid;
    private final long seed;

    private final SetCache<RiverNetwork.Point[]> made = new SetCache<>(13);
    private final SetCache<Boolean> drawn = new SetCache<>(10), deep = new SetCache<>(10);
    private final SetCache<Cell> cells = new SetCache<>(12);
    private final SetCache<Handoff> handoffs = new SetCache<>(12);
    private final SetCache<float[]> heights = new SetCache<>(13);
    private final SetCache<LakeMask> masks = new SetCache<>(10);

    final LongAdder channels = new LongAdder(), joins = new LongAdder(), fallbacks = new LongAdder(),
            dams = new LongAdder(), gorges = new LongAdder(), eyes = new LongAdder(), heldOver = new LongAdder(), pools = new LongAdder(), inlets = new LongAdder(), inletOpen = new LongAdder(), inletBig = new LongAdder(), inletShut = new LongAdder(), bankClamps = new LongAdder(), lakeMasks = new LongAdder(), sinks = new LongAdder(),
            mouths = new LongAdder(), dryJoins = new LongAdder(), gridReads = new LongAdder(), backwater = new LongAdder(),
            sunk = new LongAdder(), plainLakes = new LongAdder();

    RiverPieces(DrainageLattice lat, DrainageLattice.Ground ground, double horizontal, int areaMin) {
        this.lat = lat;
        this.ground = ground;
        this.h = horizontal;
        this.areaMin = areaMin;
        // Eight grid steps to a cell. Every grid point is a read of the ground, and a finer grid made the rivers cost
        // several times what the lattice did; the ground itself is no finer than this in either world.
        this.grid = Math.max(3, (int) Math.round(4.0 * horizontal));
        this.seed = lat.seed();
    }

    static final RiverNetwork.Point[] NONE = new RiverNetwork.Point[0];

    /** Whatever this node draws: its length of river, the rivers it takes in, a lake's ways in and out, or nothing. */
    RiverNetwork.Point[] of(long u) {
        RiverNetwork.Point[] hit = made.get(u);
        if (hit != null) return hit;
        // Every river above this one is worked out first, highest first, without recursion: the water at the top of
        // a length is the water at the bottom of the ones that run into it.
        Long2ObjectOpenHashMap<RiverNetwork.Point[]> local = new Long2ObjectOpenHashMap<>();
        LongArrayList stack = new LongArrayList();
        stack.add(u);
        while (!stack.isEmpty()) {
            if (stack.size() > 1 << 20) throw new IllegalStateException("river runs in a loop above " + u);
            long v = stack.getLong(stack.size() - 1);
            if (local.containsKey(v) || made.get(v) != null) {
                stack.removeLong(stack.size() - 1);
                continue;
            }
            long pending = Long.MIN_VALUE;
            if (takesRivers(v)) {
                for (long d : lat.donors(v)) {
                    if (!isRiver(d)) continue;
                    if (!local.containsKey(d) && made.get(d) == null) {
                        pending = d;
                        break;
                    }
                }
            }
            if (pending != Long.MIN_VALUE) {
                stack.add(pending);
                continue;
            }
            RiverNetwork.Point[] ps = build(v, local);
            local.put(v, ps);
            made.put(v, ps);
            stack.removeLong(stack.size() - 1);
        }
        RiverNetwork.Point[] out = local.get(u);
        return out != null ? out : made.get(u);
    }

    /** A node that carries a river of its own. */
    boolean isRiver(long v) {
        return !lat.isSea(v) && !underLake(v) && lat.area(v) >= areaMin;
    }

    /** A node that rivers can end in: a river, the sea, or a lake. */
    private boolean takesRivers(long v) {
        return lat.isSea(v) || underLake(v) || isRiver(v);
    }

    private RiverNetwork.Point[] build(long v, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        if (lat.isSea(v)) return sea(v, local);
        if (underLake(v)) return lake(v, local);
        if (lat.area(v) < areaMin) return NONE;
        return channel(v, local);
    }

    private RiverNetwork.Point[] get(long d, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        RiverNetwork.Point[] p = local.get(d);
        return p != null ? p : of(d);
    }

    // === Lakes ==============================================================

    /**
     * Whether a lake is deep enough to be one: its level over the lowest ground under it.
     *
     * <p>Nor is a hollow floored with ground lifted out of the sea line. Its floor is a stand-in for a plain standing
     * a few blocks out of the water, and its water, laid over the offset the plain was read from, flooded the whole
     * plain: a lake of hundreds of thousands of blocks a few deep over the grass, cut into cliffs where its shore met
     * the plain's real height. The river cuts through the low rim instead, as a river crossing a coastal plain
     * does.</p>
     */
    boolean deep(DrainageLattice.Lake lake) {
        Boolean hit = deep.get(lake.owner);
        if (hit != null) return hit;
        double floor = Double.MAX_VALUE;
        boolean lifted = false;
        for (long m : lake.members) {
            floor = Math.min(floor, lat.g(m));
            lifted |= lat.lifted(m);
        }
        boolean yes = lake.level - floor >= LAKE_MIN_DEPTH && !lifted;
        if (lifted && lake.level - floor >= LAKE_MIN_DEPTH) plainLakes.increment();
        deep.put(lake.owner, yes);
        return yes;
    }

    /** Whether a node lies under a lake drawn as one: under water, and the hollow deep enough to hold it. */
    boolean underLake(long v) {
        if (!lat.flooded(v)) return false;
        DrainageLattice.Lake lake = lat.lakeOf(v);
        return lake != null && deep(lake);
    }

    /**
     * The level the water is drawn at over a node: a lake's level under a lake, the sea's over the sea, and
     * everywhere else -- a shallow hollow the river cuts through included -- the node's own ground.
     */
    double level(long v) {
        if (lat.isSea(v)) return lat.sea;
        return underLake(v) ? lat.fill(v) : lat.g(v);
    }

    /** Whether a lake is drawn: only one that a river leaves, so a dimple on a hillside stays a dimple. */
    boolean drawn(DrainageLattice.Lake lake) {
        Boolean hit = drawn.get(lake.owner);
        if (hit != null) return hit;
        boolean yes = false;
        for (long m : lake.members) {
            if (outlet(lake, m) != Long.MIN_VALUE) {
                yes = true;
                break;
            }
        }
        drawn.put(lake.owner, yes);
        return yes;
    }

    /** Where a river leaves a lake from this member, or {@link Long#MIN_VALUE}. */
    private long outlet(DrainageLattice.Lake lake, long m) {
        long out = lake.next.get(m);
        if (out == Long.MIN_VALUE || Arrays.binarySearch(lake.members, out) >= 0 || lat.area(m) < areaMin) {
            return Long.MIN_VALUE;
        }
        return out;
    }

    /** The water a drawn lake stands in, for the index; null for a node under no drawn lake. */
    LakeMask maskOf(long v) {
        if (!lat.flooded(v)) return null;
        DrainageLattice.Lake lake = lat.lakeOf(v);
        if (lake == null || !deep(lake) || !drawn(lake)) return null;
        return mask(lake);
    }

    /** A lake member's share of the drawing: the river out of the lake, if it leaves here, and the rivers into it. */
    private RiverNetwork.Point[] lake(long m, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        DrainageLattice.Lake lake = lat.lakeOf(m);
        if (lake == null || !deep(lake) || !drawn(lake)) return NONE;
        LakeMask mask = mask(lake);
        Cell c = cell(m);
        // The water, and where the cell has none -- a node the lattice put under the lake whose own ground stands a
        // little over the water -- the points beside it, so a river still has somewhere to come in and go out.
        boolean[] wet = new boolean[c.in.length];
        boolean any = false;
        for (int i = 0; i < wet.length; i++) {
            wet[i] = c.in[i] && mask.wet(c.gi(i), c.gk(i));
            any |= wet[i];
        }
        if (!any) {
            for (int i = 0; i < wet.length; i++) {
                wet[i] = c.in[i] && mask.besideWater(c.gi(i), c.gk(i));
                any |= wet[i];
            }
        }
        // A node whose whole cell stands over the water, the lake's shallow edge: the points of the cell nearest the
        // water, and from there a last short step over the lake's own shore into it.
        boolean hop = false;
        if (!any) {
            double[] far = new double[c.in.length];
            double least = Double.MAX_VALUE;
            for (int i = 0; i < far.length; i++) {
                far[i] = c.in[i] ? mask.distanceToWater(c.x(i), c.z(i), lat.cell) : Double.MAX_VALUE;
                least = Math.min(least, far[i]);
            }
            if (least < Double.MAX_VALUE) {
                hop = true;
                for (int i = 0; i < far.length; i++) wet[i] = far[i] <= least + grid;
            }
        }
        List<RiverNetwork.Point> out = new ArrayList<>();
        List<double[]> segs = new ArrayList<>();
        long n = outlet(lake, m);
        if (n != Long.MIN_VALUE) {
            // The way out: from the water to where it crosses into the next cell, at the lake's own level.
            List<double[]> path = route(c, handoff(m, n), null, wet);
            if (path == null) {
                dryJoins.increment();
            } else {
                if (hop) {
                    double[] last = path.get(path.size() - 1);
                    double[] water = mask.nearestWater(last[0], last[1], lat.cell);
                    if (water != null) path.add(water);
                }
                // The way out starts a block deep in the lake, not on its shore.
                intoLake(path, mask);
                java.util.Collections.reverse(path);
                double w = mask.water;
                List<RiverNetwork.Point> pts = lay(tidy(c, new Led(path, false, false), halfFor(lat.area(m))), w, w, w, FROM_LAKE, halfFor(lat.area(m)), JOIN, w, 0);
                out.addAll(pts);
                segments(pts, segs);
                joins.increment();
            }
        }
        for (long d : riverDonors(m, lake)) join(c, d, m, segs, out, local, wet, mask.water, mask, hop);
        return out.toArray(NONE);
    }

    /**
     * The lake's water: flooded outwards from the deepest point of each of its nodes' cells over every grid point
     * whose ground lies under the water, through the lake's own cells and no more than a few steps past them. A
     * rim the ground holds stops it; a rim the lattice's sill only assumed -- half way along an edge, eight blocks
     * under the ground -- does too, because the ground is read, so the water never runs out over an outlet onto
     * the hillside below.
     */
    private LakeMask mask(DrainageLattice.Lake lake) {
        LakeMask hit = masks.get(lake.owner);
        if (hit != null && hit.owner == lake.owner) return hit;
        double water = lake.level - 1.0;
        LongOpenHashSet own = new LongOpenHashSet();
        LongArrayList seeds = new LongArrayList();
        for (long m : lake.members) {
            Cell c = cell(m);
            int low = -1;
            for (int i = 0; i < c.in.length; i++) {
                if (!c.in[i]) continue;
                own.add(ColumnCache.key(c.gi(i), c.gk(i)));
                if (low < 0 || c.hgt[i] < c.hgt[low]) low = i;
            }
            if (low >= 0 && c.hgt[low] < water) seeds.add(ColumnCache.key(c.gi(low), c.gk(low)));
        }
        Long2FloatOpenHashMap depth = new Long2FloatOpenHashMap();
        Long2FloatOpenHashMap shore = new Long2FloatOpenHashMap();
        Long2IntOpenHashMap outside = new Long2IntOpenHashMap();
        LongArrayList queue = new LongArrayList();
        for (int i = 0; i < seeds.size(); i++) {
            long k = seeds.getLong(i);
            if (depth.containsKey(k)) continue;
            int gi = (int) k, gk = (int) (k >>> 32);
            depth.put(k, (float) (water - height(gi, gk)));
            outside.put(k, 0);
            queue.add(k);
        }
        for (int q = 0; q < queue.size(); q++) {
            long k = queue.getLong(q);
            int gi = (int) k, gk = (int) (k >>> 32), steps = outside.get(k);
            for (int di = -1; di <= 1; di++) {
                for (int dk = -1; dk <= 1; dk++) {
                    if (di == 0 && dk == 0) continue;
                    long nk = ColumnCache.key(gi + di, gk + dk);
                    if (depth.containsKey(nk)) continue;
                    int st = own.contains(nk) ? 0 : steps + 1;
                    // The shore is kept too, as how far the ground stands over the water, so the edge of the water is
                    // read off the ground between a wet point and a dry one instead of stopping short at the wet one.
                    if (st > LAKE_REACH) {
                        shore.putIfAbsent(nk, -1.0f);
                        continue;
                    }
                    double gh = height(gi + di, gk + dk);
                    if (gh >= water) {
                        shore.putIfAbsent(nk, (float) (water - gh));
                        continue;
                    }
                    depth.put(nk, (float) (water - gh));
                    outside.put(nk, st);
                    queue.add(nk);
                }
            }
        }
        if (!depth.isEmpty()) {
            for (Long2FloatOpenHashMap.Entry e : shore.long2FloatEntrySet()) depth.putIfAbsent(e.getLongKey(), e.getFloatValue());
        }
        int gi0 = Integer.MAX_VALUE, gk0 = Integer.MAX_VALUE, gi1 = Integer.MIN_VALUE, gk1 = Integer.MIN_VALUE;
        for (long k : depth.keySet()) {
            int gi = (int) k, gk = (int) (k >>> 32);
            gi0 = Math.min(gi0, gi);
            gk0 = Math.min(gk0, gk);
            gi1 = Math.max(gi1, gi);
            gk1 = Math.max(gk1, gk);
        }
        LakeMask made;
        if (depth.isEmpty()) {
            made = new LakeMask(lake.owner, water, water - LAKE_BED * h, grid, 0, 0, 0, 0, new float[0]);
        } else {
            int wi = gi1 - gi0 + 1, wk = gk1 - gk0 + 1;
            float[] d = new float[wi * wk];
            Arrays.fill(d, Float.NaN);
            for (Long2FloatOpenHashMap.Entry e : depth.long2FloatEntrySet()) {
                long k = e.getLongKey();
                d[((int) k - gi0) + wi * ((int) (k >>> 32) - gk0)] = e.getFloatValue();
            }
            made = new LakeMask(lake.owner, water, water - LAKE_BED * h, grid, gi0, gk0, wi, wk, d);
        }
        masks.put(lake.owner, made);
        lakeMasks.increment();
        return made;
    }

    /**
     * A drawn lake's water on the grid: how deep it stands over each point it covers. Between the points the depth is
     * read off the four round the column, so the shore follows the ground between them.
     */
    static final class LakeMask {
        final long owner;
        final double water, bed;
        final int grid, gi0, gk0, wi, wk;
        private final float[] depth;

        LakeMask(long owner, double water, double bed, int grid, int gi0, int gk0, int wi, int wk, float[] depth) {
            this.owner = owner;
            this.water = water;
            this.bed = bed;
            this.grid = grid;
            this.gi0 = gi0;
            this.gk0 = gk0;
            this.wi = wi;
            this.wk = wk;
            this.depth = depth;
        }

        boolean covers(double x0, double z0, double x1, double z1) {
            if (wi == 0) return false;
            return (gi0 - 1.0) * grid < x1 && (gi0 + wi) * (double) grid > x0
                    && (gk0 - 1.0) * grid < z1 && (gk0 + wk) * (double) grid > z0;
        }

        boolean wet(int gi, int gk) {
            return raw(gi, gk) > 0;
        }

        /** How far the nearest water is from here, looking no further than {@code reach}; MAX_VALUE where none is. */
        double distanceToWater(double x, double z, double reach) {
            double[] w = nearestWater(x, z, reach);
            return w == null ? Double.MAX_VALUE : Math.hypot(w[0] - x, w[1] - z);
        }

        /** The nearest grid point under the water, no further than {@code reach}, or null. */
        double[] nearestWater(double x, double z, double reach) {
            int r = (int) Math.ceil(reach / grid);
            int ci = (int) Math.round(x / grid), ck = (int) Math.round(z / grid);
            double best = Double.MAX_VALUE;
            double[] out = null;
            for (int gk = ck - r; gk <= ck + r; gk++) {
                for (int gi = ci - r; gi <= ci + r; gi++) {
                    if (!wet(gi, gk)) continue;
                    double d = Math.hypot(gi * (double) grid - x, gk * (double) grid - z);
                    if (d < best) {
                        best = d;
                        out = new double[]{gi * (double) grid, gk * (double) grid};
                    }
                }
            }
            return best <= reach ? out : null;
        }

        /** Water on this grid point or on one of the eight round it. */
        boolean besideWater(int gi, int gk) {
            for (int di = -1; di <= 1; di++) {
                for (int dk = -1; dk <= 1; dk++) if (wet(gi + di, gk + dk)) return true;
            }
            return false;
        }

        private double raw(int gi, int gk) {
            int a = gi - gi0, b = gk - gk0;
            if (a < 0 || b < 0 || a >= wi || b >= wk) return -grid;
            float v = depth[a + wi * b];
            return Float.isNaN(v) ? -grid : v;
        }

        /** How deep the water stands here, from the four grid points round the column; at or under 0 it is dry. */
        double depthAt(double x, double z) {
            if (wi == 0) return -grid;
            double fx = x / grid, fz = z / grid;
            int i0 = (int) Math.floor(fx), k0 = (int) Math.floor(fz);
            if (i0 < gi0 - 1 || k0 < gk0 - 1 || i0 >= gi0 + wi || k0 >= gk0 + wk) return -grid;
            double tx = fx - i0, tz = fz - k0;
            double a = raw(i0, k0), b = raw(i0 + 1, k0), c = raw(i0, k0 + 1), d = raw(i0 + 1, k0 + 1);
            double top = a + (b - a) * tx, bottom = c + (d - c) * tx;
            return top + (bottom - top) * tz;
        }

        /** A fingerprint of the water, for the determinism check. */
        long hash(long h) {
            h = (h ^ Double.doubleToLongBits(water)) * 0x100000001b3L;
            h = (h ^ gi0) * 0x100000001b3L;
            h = (h ^ gk0) * 0x100000001b3L;
            for (float v : depth) h = (h ^ Float.floatToIntBits(v)) * 0x100000001b3L;
            return h;
        }
    }

    // === The sea ============================================================

    /** A sea node's share: every river that reaches it, carried on to where its ground goes under the sea. */
    private RiverNetwork.Point[] sea(long s, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        long[] ds = riverDonors(s, null);
        if (ds.length == 0) return NONE;
        Cell c = cell(s);
        boolean[] wet = new boolean[c.in.length];
        boolean any = false;
        // Out to where the raw ground lies deeper under the sea in the tall world: its noise lifts a shore two and a
        // half times as far over the raw ground, and a river that stopped where the raw ground first went under the sea
        // still had a bank of sand, and a drowned ruin on it, between its end and the waves.
        double deep = lat.sea - MOUTH_DEEPER * Math.max(0.0, h - 1.0);
        for (int i = 0; i < wet.length; i++) {
            wet[i] = c.in[i] && c.hgt[i] <= deep;
            any |= wet[i];
        }
        if (!any) {
            for (int i = 0; i < wet.length; i++) {
                wet[i] = c.in[i] && c.hgt[i] <= lat.sea;
                any |= wet[i];
            }
        }
        // A sea node on a shore that stands a little over the water everywhere in its cell: its lowest ground, then.
        if (!any) {
            for (int i = 0; i < wet.length; i++) wet[i] = c.in[i] && c.hgt[i] <= c.low + 1.0;
        }
        List<RiverNetwork.Point> out = new ArrayList<>();
        List<double[]> segs = new ArrayList<>();
        for (long d : ds) {
            int before = out.size();
            join(c, d, s, segs, out, local, wet, lat.sea, null, false);
            mouths.increment();
            if (out.size() > before) {
                List<RiverNetwork.Point> cut = inlet(out.get(out.size() - 1));
                out.addAll(cut);
                segments(cut, segs);
            }
        }
        return out.toArray(NONE);
    }

    /**
     * Where a river has come out into water cut off from the sea by a narrow bar -- a lagoon behind a spit, the drowned
     * end of its own valley behind a beach -- a way on through the bar into the open water beyond. The lattice sees
     * the ground a cell at a time, and a bar a few blocks wide between two waters that both lie under sea level is
     * lost in it: the river ran into the lagoon and the lagoon stood shut off from the sea by a strip of sand.
     *
     * <p>The water the river came out into is flooded outwards over the raw ground, a few blocks at a time. If it
     * runs on past the reach of the search it is the open sea, or near enough, and nothing is cut. If it closes, it is
     * a lagoon: its shore is searched for the narrowest dry crossing into water outside it, and the river is carried
     * through the lagoon along the flood's own way and cut through there, at the sea's level.</p>
     */
    private List<RiverNetwork.Point> inlet(RiverNetwork.Point last) {
        double ex = last.ex(), ez = last.ez();
        double s = INLET_STEP, reach = INLET_REACH * h, bar = INLET_BAR * h;
        int r = (int) Math.ceil(reach / s);
        if (read(ex, ez) > lat.sea) return List.of();
        Long2IntOpenHashMap from = new Long2IntOpenHashMap();
        from.defaultReturnValue(Integer.MIN_VALUE);
        LongArrayList queue = new LongArrayList();
        long start = ColumnCache.key(0, 0);
        from.put(start, -1);
        queue.add(start);
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int head = 0; head < queue.size(); head++) {
            long k = queue.getLong(head);
            int i = (int) (k >> 32), j = (int) k;
            for (int[] d : four) {
                int ni = i + d[0], nj = j + d[1];
                long nk = ColumnCache.key(ni, nj);
                if (from.containsKey(nk)) continue;
                if (read(ex + ni * s, ez + nj * s) > lat.sea) continue;
                // Out past the reach, or more water than a lagoon holds: this is the sea.
                if (ni * ni + nj * nj > r * r) { inletOpen.increment(); return List.of(); }
                if (queue.size() >= INLET_BUDGET) { inletBig.increment(); return List.of(); }
                from.put(nk, head);
                queue.add(nk);
            }
        }
        // A closed water: the narrowest way out of it over dry ground, into water it does not hold.
        double bestDry = Double.MAX_VALUE, bestD = Double.MAX_VALUE;
        int bestCell = -1;
        double bdx = 0, bdz = 0, bLen = 0;
        double diag = Math.sqrt(0.5);
        double[][] eight = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {diag, diag}, {diag, -diag}, {-diag, diag}, {-diag, -diag}};
        for (int c = 0; c < queue.size(); c++) {
            long k = queue.getLong(c);
            int i = (int) (k >> 32), j = (int) k;
            boolean shore = false;
            for (int[] d : four) if (!from.containsKey(ColumnCache.key(i + d[0], j + d[1]))) { shore = true; break; }
            if (!shore) continue;
            double cx = ex + i * s, cz = ez + j * s;
            for (double[] d : eight) {
                double dryFrom = -1;
                for (double t = 1.0; t <= bar + s; t += 1.0) {
                    double px = cx + d[0] * t, pz = cz + d[1] * t;
                    boolean wet = read(px, pz) <= lat.sea;
                    if (!wet) {
                        if (dryFrom < 0) dryFrom = t;
                        if (t - dryFrom > bar) break;
                        continue;
                    }
                    if (dryFrom < 0) continue;
                    long pk = ColumnCache.key((int) Math.round((px - ex) / s), (int) Math.round((pz - ez) / s));
                    if (from.containsKey(pk)) break;
                    double dry = t - dryFrom;
                    double dist = Math.hypot(cx - ex, cz - ez);
                    if (dry < bestDry - 1e-6 || (dry < bestDry + 1e-6 && dist < bestD)) {
                        bestDry = dry;
                        bestD = dist;
                        bestCell = c;
                        bdx = d[0];
                        bdz = d[1];
                        // On into the water past the bar, but no further than the water goes.
                        bLen = t;
                        for (double u = t + 1.0; u <= t + 3.0 * s; u += 1.0) {
                            if (read(cx + d[0] * u, cz + d[1] * u) > lat.sea) break;
                            bLen = u;
                        }
                    }
                    break;
                }
            }
        }
        if (bestCell < 0) { inletShut.increment(); return List.of(); }
        // The flood's own way from the river's end to the crossing, then across.
        List<double[]> path = new ArrayList<>();
        for (int c = bestCell; c >= 0; c = from.get(queue.getLong(c))) {
            long k = queue.getLong(c);
            path.add(new double[]{ex + (int) (k >> 32) * s, ez + (int) k * s});
        }
        java.util.Collections.reverse(path);
        path.set(0, new double[]{ex, ez});
        double[] edge = path.get(path.size() - 1);
        path.add(new double[]{edge[0] + bdx * bLen, edge[1] + bdz * bLen});
        boolean[] keep = new boolean[path.size()];
        keep[0] = true;
        keep[path.size() - 1] = true;
        keep[Math.max(0, path.size() - 2)] = true;
        simplify(path, 0, path.size() - 1, s, keep);
        List<double[]> way = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) if (keep[i]) way.add(path.get(i));
        inlets.increment();
        // At the river's own last level or the sea's, whichever is lower: water never rises on its way out.
        double w = Math.min(lat.sea, last.waterEnd());
        return lay(way, w, w, w, last.fromHead(), last.halfWidth(), JOIN, w, 0);
    }

    // === Channels ===========================================================

    /** The rivers that come into a node, biggest first: rivers of their own, and lakes that spill into it. */
    private long[] riverDonors(long u, DrainageLattice.Lake ownLake) {
        LongArrayList out = new LongArrayList();
        for (long d : lat.donors(u)) {
            if (lat.area(d) < areaMin) continue;
            if (ownLake != null && Arrays.binarySearch(ownLake.members, d) >= 0) continue;
            if (isRiver(d) || (underLake(d) && maskOf(d) != null)) out.add(d);
        }
        long[] ds = out.toLongArray();
        // Biggest first, and the lower key first between two the same size: every thread draws them in one order.
        Long[] boxed = new Long[ds.length];
        for (int i = 0; i < ds.length; i++) boxed[i] = ds[i];
        Arrays.sort(boxed, (a, b) -> lat.area(a) != lat.area(b) ? Integer.compare(lat.area(b), lat.area(a)) : Long.compare(a, b));
        for (int i = 0; i < ds.length; i++) ds[i] = boxed[i];
        return ds;
    }

    /** Where a river that comes in ends: its water, its width and how far down its river it is. */
    private record End(double water, double half, double length) {}

    private End end(long d, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        if (underLake(d)) {
            LakeMask mask = maskOf(d);
            return mask == null ? null : new End(mask.water, halfFor(lat.area(d)), FROM_LAKE);
        }
        RiverNetwork.Point[] ps = get(d, local);
        RiverNetwork.Point last = null;
        for (RiverNetwork.Point p : ps) if (p.kind() == CHANNEL) last = p;
        if (last == null) return null;
        return new End(last.waterEnd(), last.halfWidth(),
                last.fromHead() + Math.hypot(last.ex() - last.x(), last.ez() - last.z()));
    }

    private RiverNetwork.Point[] channel(long u, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        channels.increment();
        double fu = level(u);
        long r = lat.receiver(u);
        long[] ds = riverDonors(u, null);
        // Where the water comes in: the biggest of the rivers above, and the lowest water any of them brings.
        long main = Long.MIN_VALUE;
        double wIn = fu - 1.0, lenIn = 0;
        for (long d : ds) {
            End e = end(d, local);
            if (e == null) continue;
            if (main == Long.MIN_VALUE) {
                main = d;
                lenIn = e.length();
                wIn = e.water();
            } else {
                wIn = Math.min(wIn, e.water());
            }
        }
        Cell c = cell(u);
        double[] s = main == Long.MIN_VALUE ? new double[]{lat.x(u), lat.z(u)} : handoff(main, u);
        double tS = main == Long.MIN_VALUE ? fu - 1.0 : (level(main) + fu) * 0.5 - 1.0;
        double[] e;
        double tE;
        if (r != Long.MIN_VALUE) {
            e = handoff(u, r);
            tE = (fu + level(r)) * 0.5 - 1.0;
        } else {
            e = new double[]{lat.x(u), lat.z(u)};
            tE = fu - 1.0;
        }
        List<double[]> path = route(c, s, e, null);
        if (path == null) {
            fallbacks.increment();
            path = new ArrayList<>(List.of(s, new double[]{lat.x(u), lat.z(u)}, e));
        } else {
            path = tidy(c, lead(c, path, main == Long.MIN_VALUE ? null : across(main, u),
                    r == Long.MIN_VALUE ? null : across(u, r)), halfFor(lat.area(u)));
        }
        List<RiverNetwork.Point> pts = lay(path, tS, tE, wIn, lenIn, halfFor(lat.area(u)), CHANNEL,
                r == Long.MIN_VALUE ? Double.NEGATIVE_INFINITY : level(r) - 1.0,
                main != Long.MIN_VALUE && underLake(main) ? LAKE_HOLD : 0);
        if (main == Long.MIN_VALUE) eye(pts);
        if (r == Long.MIN_VALUE) {
            // A river the ground closes round: it ends in a pond of its own.
            sinks.increment();
            RiverNetwork.Point last = pts.get(pts.size() - 1);
            float w = last.waterEnd();
            float bed = (float) (w - LAKE_BED * h);
            float ux = (float) lat.x(u), uz = (float) lat.z(u);
            pts.add(new RiverNetwork.Point(ux, uz, ux, uz, w, w, bed, bed, (float) (SINK_HALF * lat.cell), FROM_LAKE,
                    0f, 0f, LAKE, (byte) 0));
        }
        // The other rivers that come in, biggest first, each carried to the nearest water already drawn in this cell.
        List<double[]> segs = new ArrayList<>();
        segments(pts, segs);
        for (long d : ds) {
            if (d == main) continue;
            join(c, d, u, segs, pts, local, null, 0, null, false);
        }
        // Through soluble rock the river may sink here and run on in a cave to the end of the cell.
        tunnel(u, r, main, ds, pts, local);
        return pts.toArray(NONE);
    }

    /**
     * Whether a river sinks through this cell ({@link RiverNetwork.Point#sunk}): where it crosses into soluble rock it
     * falls into a swallow hole, runs on in a cave under its valley and comes out where it leaves the cell, into the
     * river it feeds at that river's own level. Only a modest river, only where it falls far enough across the cell for
     * the cave to have a roof -- the cave slopes down with it and can go no lower than where it comes out --
     * only where none of the rivers coming in is underground already, and only where it runs on into another river:
     * never into a lake or the sea, never at its source. The rivers that join it in the cell sink with it, each at a
     * swallow hole of its own, their caves meeting its cave at its water.
     *
     * <p>A cave run on under the river's bed to the cell's end came up again the height of the shaft under the river it
     * fed, and the river was seen to climb out of its cave.</p>
     */
    private void tunnel(long u, long r, long main, long[] donors, List<RiverNetwork.Point> pts,
                        Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        if (main == Long.MIN_VALUE || r == Long.MIN_VALUE || underLake(main) || lat.isSea(r) || underLake(r)) return;
        if (lat.area(u) > Karst.SINK_AREA_MAX || !lat.soluble(u)) return;
        double first = Double.NaN, last = Double.NaN;
        int lengths = 0;
        for (RiverNetwork.Point p : pts) {
            if (p.lake()) return;
            if (p.kind() != CHANNEL) continue;
            if (lengths++ == 0) first = p.water();
            last = p.waterEnd();
        }
        if (lengths < Karst.TUNNEL_MIN || first - last < Karst.CAVE_DROP * h) return;
        for (long d : donors) {
            if (underLake(d)) continue;
            for (RiverNetwork.Point p : get(d, local)) if (p.sunk()) return;
        }
        sunk.increment();
        for (int i = 0; i < pts.size(); i++) pts.set(i, pts.get(i).withUnder((byte) 1));
    }

    /**
     * One more river brought into this cell's water, from where it crosses into the cell to the nearest water drawn
     * here or, where {@code wet} is given, to any of those points. It is a way through the cell like any other, so it
     * follows the ground; and it ends at the first point beside water already drawn, so it cannot cross any.
     */
    private void join(Cell c, long d, long owner, List<double[]> segs, List<RiverNetwork.Point> out,
                      Long2ObjectOpenHashMap<RiverNetwork.Point[]> local, boolean[] wet, double wetWater,
                      LakeMask lake, boolean hop) {
        End from = end(d, local);
        if (from == null) return;
        double[] s = handoff(d, owner);
        boolean[] target = new boolean[c.in.length];
        boolean any = false;
        for (int i = 0; i < target.length; i++) {
            if (!c.in[i]) continue;
            target[i] = (wet != null && wet[i]) || nearSegs(segs, c.x(i), c.z(i), grid);
            any |= target[i];
        }
        if (!any) {
            dryJoins.increment();
            return;
        }
        List<double[]> path = route(c, s, null, target);
        if (path == null) {
            dryJoins.increment();
            return;
        }
        double[] last = path.get(path.size() - 1);
        double endWater = wetWater;
        double[] snap = nearestOnSegs(segs, last[0], last[1]);
        boolean ontoWater = wet != null && c.in.length > 0 && isWetPoint(c, wet, last);
        if (!ontoWater && snap != null) {
            path.add(new double[]{snap[0], snap[1]});
            endWater = snap[2];
        } else if (ontoWater && hop && lake != null) {
            // Over the lake's dry edge into its water.
            double[] w = lake.nearestWater(last[0], last[1], lat.cell);
            if (w != null) path.add(w);
        }
        if (ontoWater && lake != null) intoLake(path, lake);
        path = tidy(c, lead(c, path, across(d, owner), null), from.half());
        List<RiverNetwork.Point> pts = lay(path, from.water(), Math.min(from.water(), endWater), from.water(),
                from.length(), from.half(), JOIN, endWater, 0);
        out.addAll(pts);
        segments(pts, segs);
        joins.increment();
    }

    /**
     * Carries a way that ends at a lake on until it is a block deep in the lake's water. It used to stop at the first
     * grid point the lake reached, which lies at the lake's very edge: a stream ended there with the last block or
     * two of shore still standing between it and the lake.
     */
    private void intoLake(List<double[]> path, LakeMask lake) {
        double[] end = path.get(path.size() - 1);
        if (lake.depthAt(end[0], end[1]) >= 1.0) return;
        double[] water = lake.nearestWater(end[0], end[1], 3.0 * lat.cell);
        if (water == null) return;
        double dx = water[0] - end[0], dz = water[1] - end[1], l = Math.hypot(dx, dz);
        if (l < 1e-6) {
            // On the edge's grid point already: on towards the lake's deeper water, where the next grid point is wetter.
            double best = lake.depthAt(end[0], end[1]);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                double v = lake.depthAt(end[0] + d[0] * grid, end[1] + d[1] * grid);
                if (v > best) { best = v; dx = d[0]; dz = d[1]; }
            }
            l = Math.hypot(dx, dz);
            if (l < 1e-6) return;
        }
        dx /= l;
        dz /= l;
        // A block deep, or where a shallow lake is deepest along the way: never out the other side onto its shore.
        double bestDepth = lake.depthAt(end[0], end[1]), bestT = 0.0;
        for (double t = 1.0; t <= l + 2.0 * grid; t += 1.0) {
            double v = lake.depthAt(end[0] + dx * t, end[1] + dz * t);
            if (v > bestDepth) {
                bestDepth = v;
                bestT = t;
            }
            if (v >= 1.0) break;
        }
        if (bestT > 0 && bestDepth > 0) path.add(new double[]{end[0] + dx * bestT, end[1] + dz * bestT});
    }

    private boolean isWetPoint(Cell c, boolean[] wet, double[] p) {
        int i = c.index((int) Math.round(p[0] / grid), (int) Math.round(p[1] / grid));
        return i >= 0 && wet[i] && Math.abs(c.x(i) - p[0]) < 1e-6 && Math.abs(c.z(i) - p[1]) < 1e-6;
    }

    /** The lengths as segments with their water, for joins to find. */
    private static void segments(List<RiverNetwork.Point> pts, List<double[]> segs) {
        for (RiverNetwork.Point p : pts) {
            if (p.kind() == LAKE) continue;
            segs.add(new double[]{p.x(), p.z(), p.ex(), p.ez(), p.water(), p.waterEnd()});
        }
    }

    private static boolean nearSegs(List<double[]> segs, double x, double z, double reach) {
        for (double[] s : segs) {
            if (distToSeg(s, x, z) <= reach) return true;
        }
        return false;
    }

    /** The nearest point on the segments and the water there, or null. */
    private static double[] nearestOnSegs(List<double[]> segs, double x, double z) {
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (double[] s : segs) {
            double ax = s[2] - s[0], az = s[3] - s[1], len2 = ax * ax + az * az;
            double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - s[0]) * ax + (z - s[1]) * az) / len2));
            double px = s[0] + ax * t, pz = s[1] + az * t, d = Math.hypot(px - x, pz - z);
            if (d < best) {
                best = d;
                out = new double[]{px, pz, s[4] + (s[5] - s[4]) * t};
            }
        }
        return out;
    }

    private static double distToSeg(double[] s, double x, double z) {
        double ax = s[2] - s[0], az = s[3] - s[1], len2 = ax * ax + az * az;
        double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - s[0]) * ax + (z - s[1]) * az) / len2));
        return Math.hypot(s[0] + ax * t - x, s[1] + az * t - z);
    }

    /**
     * Half width from the ground a river drains: a rill a block or two across where it rises, whatever the layout,
     * and a grown river at the cap. It stays narrow a good way down, as a stream does until it has gathered a valley's
     * worth of water.
     */
    private double halfFor(int area) {
        double t = (Math.sqrt(area / (double) areaMin) - 1.0) / (Math.sqrt(lat.areaCap / (double) areaMin) - 1.0);
        t = Math.max(0.0, Math.min(1.0, t));
        return HALF_MIN + (HALF_GROWN * h - HALF_MIN) * Math.pow(t, WIDEN);
    }

    /** How deep a channel this wide runs: a rill a block, a grown river the full depth. */
    private double depthFor(double half) {
        return Math.max(DEPTH_LEAST, Math.min(DEPTH * h, DEPTH_BASE + DEPTH_PER_HALF * half));
    }

    /**
     * The points of one length along a path, every STEP blocks: the water at each, held under the target and a block
     * under the lower bank, and never higher than just upstream.
     */
    private List<RiverNetwork.Point> lay(List<double[]> path, double tS, double tE, double wIn, double lenIn,
                                         double half, byte kind, double least, int hold) {
        // Never under the water this length runs into, and never over where it started: the floor is only a floor.
        double floorW = Math.min(least, wIn);
        int m = path.size();
        double[] cum = new double[m];
        for (int k = 1; k < m; k++) {
            cum[k] = cum[k - 1] + Math.hypot(path.get(k)[0] - path.get(k - 1)[0], path.get(k)[1] - path.get(k - 1)[1]);
        }
        double len = cum[m - 1];
        int n = Math.max(1, (int) Math.ceil(len / (STEP * h)));
        double[] qx = new double[n + 1], qz = new double[n + 1], qw = new double[n + 1], qg = new double[n + 1];
        double[] qr = new double[n + 1];
        int seg = 0;
        double w = wIn;
        for (int q = 0; q <= n; q++) {
            double want = len * q / n;
            while (seg < m - 2 && cum[seg + 1] < want) seg++;
            int nextSeg = Math.min(m - 1, seg + 1);
            double span = cum[nextSeg] - cum[seg];
            double f = span < 1e-9 ? 0 : (want - cum[seg]) / span;
            double[] a = path.get(seg), b = path.get(nextSeg);
            double x = a[0] + (b[0] - a[0]) * f, z = a[1] + (b[1] - a[1]) * f;
            // Out of a lake the river keeps the lake's level for its first few points, so the drop comes where the
            // river leaves the water behind, not at the lake's edge.
            double target = q < hold ? wIn : tS + (tE - tS) * q / n;
            // The banks: the lowest ground either side, just past where the cut wall stops. How wide the channel is
            // drawn depends on how fast the water falls, which is what is being worked out, so both the narrowest
            // and the widest it can be drawn are read.
            double tx = b[0] - a[0], tz = b[1] - a[1], tl = Math.hypot(tx, tz);
            double dx = tl < 1e-9 ? 0 : -tz / tl, dz = tl < 1e-9 ? 0 : tx / tl;
            double narrowest = Math.max(HALF_MIN, half * STEEP_NARROW), widest = half * POOL_WIDE;
            double in = narrowest + BANK_RISE * h + 1.0, out = widest + BANK_RISE * h + 1.0;
            double rim = Math.min(Math.min(read(x + dx * out, z + dz * out), read(x - dx * out, z - dz * out)),
                    Math.min(read(x + dx * in, z + dz * in), read(x - dx * in, z - dz * in)));
            // Held up to the floor only as far as a bank can be built up to hold it; past that the water steps down.
            double lw = Math.min(w, Math.max(Math.min(floorW, rim - 1.0 + BANK_HOLD), Math.min(target, rim - 1.0)));
            if (rim - 1.0 < Math.min(w, target)) bankClamps.increment();
            if (lw > rim - 1.0 + 1e-6) heldOver.increment();
            w = lw;
            qx[q] = x;
            qz[q] = z;
            qw[q] = w;
            qg[q] = read(x, z);
            qr[q] = rim;
        }
        // Backwater. A river whose water comes to its end lower than the water it runs into -- a lake filled to its
        // spill level, a river standing higher at the join -- does not step up into it: the lake reaches back up the
        // channel, level, as far as the channel's banks hold that level and the river's own water is under it. Left
        // as it was, the river ended a few blocks under the lake with falling water hung from the lake's edge down to
        // it, drawn running the way the river runs: water climbing a waterfall.
        // Only where the floor is the water run into: a length out of a lake passes its own start as the floor, and
        // raising that would lift a lake's outflow back up to the lake. The banks may be built up as far as the water
        // may be held over them anywhere else.
        if ((kind == CHANNEL || least != wIn) && least > qw[n] + 0.5) {
            for (int q = n; q >= 0; q--) {
                if (qw[q] >= least) break;
                if (qr[q] - 1.0 + BANK_HOLD < least) break;
                qw[q] = least;
                backwater.increment();
            }
        }
        List<RiverNetwork.Point> pts = new ArrayList<>(n);
        double[] wide = new double[n + 1], deeper = new double[n + 1];
        Arrays.fill(wide, 1.0);
        // A plunge pool at the foot of every fall: where the water has come down far in the last two points' length
        // and runs on about level, the bed is scoured deeper for a few points. Counted over two points, because a fall
        // no longer has to land inside one length; the water's own level is the pool's, only the floor goes down.
        for (int q = 1; q <= n; q++) {
            double fall = qw[Math.max(0, q - 2)] - qw[q];
            if (fall < POOL_FALL * h) continue;
            if (q < n && qw[q] - qw[q + 1] > fall * 0.5) continue;
            double dig = Math.min(POOL_DIG * fall, POOL_DIG_MAX * h);
            pools.increment();
            for (int k = q; k <= Math.min(n, q + POOL_RUN - 1); k++) {
                wide[k] = POOL_WIDE;
                deeper[k] = Math.max(deeper[k], dig);
            }
        }
        double step = len / n;
        double[] hws = new double[n];
        for (int q = 0; q < n; q++) {
            double fall = (qw[q] - qw[q + 1]) / Math.max(step, 1e-6);
            double t = Math.max(0.0, Math.min(1.0, 1.0 - (fall - STEEP_FROM) / STEEP_OVER));
            double gentle = t * t * (3.0 - 2.0 * t);
            double narrow = STEEP_NARROW + (1.0 - STEEP_NARROW) * gentle;
            hws[q] = Math.max(HALF_MIN, half * narrow) * Math.max(wide[q], wide[q + 1]);
        }
        // No point narrower than its neighbours allow: a width comes down and goes back up gradually.
        for (int q = 1; q < n; q++) hws[q] = Math.max(hws[q], hws[q - 1] / WIDTH_STEP);
        for (int q = n - 2; q >= 0; q--) hws[q] = Math.max(hws[q], hws[q + 1] / WIDTH_STEP);
        for (int q = 0; q < n; q++) {
            double hw = hws[q];
            double depth = depthFor(hw);
            double bedS = qw[q] - depth - deeper[q], bedE = qw[q + 1] - depth - deeper[q + 1];
            double cutS = gorge(qg[q], qw[q], bedS, hw), cutE = gorge(qg[q + 1], qw[q + 1], bedE, hw);
            if (cutS > 0) gorges.increment();
            pts.add(new RiverNetwork.Point((float) qx[q], (float) qz[q], (float) qx[q + 1], (float) qz[q + 1],
                    (float) qw[q], (float) qw[q + 1], (float) bedS, (float) bedE,
                    (float) hw, (float) (lenIn + step * q), (float) cutS, (float) cutE, kind, (byte) 0));
        }
        return pts;
    }

    /**
     * A spring's eye: at some of the places a river rises, a small round pool a little way up the valley from where the
     * stream sets off, cut back into the slope. The water is seen to well up out of the hillside and run off, rather
     * than to begin as a thread in the grass. It is a length of no length, so a column is measured against it as a
     * disc, and it stands at the stream's own water or not at all: where the ground round it would not hold the pool,
     * there is no eye.
     */
    private void eye(List<RiverNetwork.Point> pts) {
        if (pts.isEmpty()) return;
        RiverNetwork.Point p = pts.get(0);
        double dx = p.ex() - p.x(), dz = p.ez() - p.z(), l = Math.hypot(dx, dz);
        if (l < 1e-6) return;
        long hash = SeedHash.hash(seed, (int) Math.floor(p.x()), (int) Math.floor(p.z()), 0xE7E1L);
        if (SeedHash.rand01(hash) >= EYE_SHARE) return;
        double half = EYE_HALF * h;
        double cx = p.x() - dx / l * half * 0.8, cz = p.z() - dz / l * half * 0.8;
        double out = half + BANK_RISE * h + 1.0;
        double rim = Math.min(Math.min(read(cx + out, cz), read(cx - out, cz)), Math.min(read(cx, cz + out), read(cx, cz - out)));
        double w = p.water();
        if (rim - 1.0 < w) return;
        float bed = (float) (w - depthFor(half));
        pts.add(0, new RiverNetwork.Point((float) cx, (float) cz, (float) cx, (float) cz, (float) w, (float) w,
                bed, bed, (float) half, 0f, 0f, 0f, CHANNEL, (byte) 0));
        eyes.increment();
    }

    /**
     * How far under its raw ground the channel may go at a sample, where the way the water has to take runs through
     * a hill: down to its bed, so the hill is cut through as a gorge rather than left standing across the river with
     * the river going on behind it. 0 where the ordinary shave already reaches the bed.
     *
     * <p>Two things hold it back. A gorge deeper than {@link #CUT_MAX} is not cut: that is a mountain, not a hill.
     * And its walls, stepping up at the channel wall's slope, have to finish inside the reach a column looks for a
     * channel over, or the gorge would end in a face at the edge of that reach.</p>
     */
    private double gorge(double groundHere, double water, double bed, double hw) {
        if (groundHere <= water + MAX_SHAVE) return 0;
        double need = groundHere - bed + 1.0;
        double reach = Math.max(HALF_GROWN * POOL_WIDE * h, SINK_HALF * lat.cell) + RiverNetwork.CAVE_REACH * h + 4.0 * h;
        double allowed = Math.min(CUT_MAX * h, WALL * (reach - 3.0 - hw));
        if (need > allowed) dams.increment();
        return Math.max(0.0, Math.min(need, allowed));
    }

    private double read(double x, double z) {
        return ground.heightAt((int) Math.floor(x), (int) Math.floor(z));
    }

    // === Cells ==============================================================

    /** The node's neighbour slots in turn round it; each two in a row make one of its triangles. */
    private int[] ring(long u) {
        int[] out = new int[8];
        int n = 0;
        for (int s : RING) if (lat.has(u, s)) out[n++] = s;
        return Arrays.copyOf(out, n);
    }

    private Cell cell(long u) {
        Cell hit = cells.get(u);
        if (hit != null && hit.u == u) return hit;
        Cell c = new Cell(u);
        cells.put(u, c);
        return c;
    }

    /** A node's cell and the grid points inside it, with the ground at each. */
    final class Cell {
        final long u;
        final double ux, uz;
        final double[] ax, az;
        final int gi0, gk0, wi, wk;
        final boolean[] in;
        final float[] hgt;
        final float low;

        Cell(long u) {
            this.u = u;
            ux = lat.x(u);
            uz = lat.z(u);
            int[] slots = ring(u);
            int n = slots.length;
            ax = new double[n];
            az = new double[n];
            double minx = ux, maxx = ux, minz = uz, maxz = uz;
            for (int i = 0; i < n; i++) {
                long a = DrainageLattice.step(u, slots[i]);
                ax[i] = lat.x(a);
                az[i] = lat.z(a);
            }
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double mx = (ux + ax[i]) * 0.5, mz = (uz + az[i]) * 0.5;
                double cx = (ux + ax[i] + ax[j]) / 3.0, cz = (uz + az[i] + az[j]) / 3.0;
                minx = Math.min(minx, Math.min(mx, cx));
                maxx = Math.max(maxx, Math.max(mx, cx));
                minz = Math.min(minz, Math.min(mz, cz));
                maxz = Math.max(maxz, Math.max(mz, cz));
            }
            gi0 = (int) Math.ceil(minx / grid);
            gk0 = (int) Math.ceil(minz / grid);
            wi = Math.max(0, (int) Math.floor(maxx / grid) - gi0 + 1);
            wk = Math.max(0, (int) Math.floor(maxz / grid) - gk0 + 1);
            in = new boolean[wi * wk];
            hgt = new float[wi * wk];
            float lo = Float.MAX_VALUE;
            for (int i = 0; i < in.length; i++) {
                in[i] = contains(x(i), z(i));
                hgt[i] = in[i] ? height(gi(i), gk(i)) : Float.NaN;
                if (in[i]) lo = Math.min(lo, hgt[i]);
            }
            low = lo;
        }

        int gi(int i) {
            return gi0 + i % wi;
        }

        int gk(int i) {
            return gk0 + i / wi;
        }

        double x(int i) {
            return gi(i) * (double) grid;
        }

        double z(int i) {
            return gk(i) * (double) grid;
        }

        int index(int gi, int gk) {
            int a = gi - gi0, b = gk - gk0;
            return a < 0 || b < 0 || a >= wi || b >= wk ? -1 : a + wi * b;
        }

        /** Whether a point lies in this node's cell: in one of its triangles, and nearer to it than to the other two. */
        boolean contains(double x, double z) {
            int n = ax.length;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double det = (ax[i] - ux) * (az[j] - uz) - (ax[j] - ux) * (az[i] - uz);
                if (Math.abs(det) < 1e-9) continue;
                double la = ((x - ux) * (az[j] - uz) - (ax[j] - ux) * (z - uz)) / det;
                double lb = ((ax[i] - ux) * (z - uz) - (x - ux) * (az[i] - uz)) / det;
                double lu = 1.0 - la - lb;
                if (la < -1e-9 || lb < -1e-9 || lu < -1e-9) continue;
                return lu > la + 1e-9 && lu > lb + 1e-9;
            }
            return false;
        }
    }

    private float height(int gi, int gk) {
        int ti = Math.floorDiv(gi, TILE), tk = Math.floorDiv(gk, TILE);
        long key = ColumnCache.key(ti, tk);
        float[] t = heights.get(key);
        if (t == null || t.length != TILE * TILE + 2 || t[TILE * TILE] != ti || t[TILE * TILE + 1] != tk) {
            t = new float[TILE * TILE + 2];
            for (int b = 0; b < TILE; b++) {
                for (int a = 0; a < TILE; a++) {
                    t[a + TILE * b] = (float) ground.heightAt((ti * TILE + a) * grid, (tk * TILE + b) * grid);
                }
            }
            t[TILE * TILE] = ti;
            t[TILE * TILE + 1] = tk;
            gridReads.add(TILE * TILE);
            heights.put(key, t);
        }
        return t[Math.floorMod(gi, TILE) + TILE * Math.floorMod(gk, TILE)];
    }

    // === Where water crosses between cells ==================================

    private record Handoff(long lo, long hi, double x, double z) {}

    /**
     * Where water crosses from one node's cell into its neighbour's: the lowest ground on the line between the two
     * cells, which runs from the middle of their edge to the middle of each triangle either side. Both nodes work it
     * out alike -- from the lower key, in one order -- so a river leaves one cell exactly where it enters the next.
     */
    double[] handoff(long a, long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        long key = SeedHash.mix(lo * 0x9E3779B97F4A7C15L + hi);
        Handoff hit = handoffs.get(key);
        if (hit != null && hit.lo == lo && hit.hi == hi) return new double[]{hit.x, hit.z};
        double mx = (lat.x(lo) + lat.x(hi)) * 0.5, mz = (lat.z(lo) + lat.z(hi)) * 0.5;
        double bx = mx, bz = mz;
        int[] slots = ring(lo);
        int at = -1;
        for (int i = 0; i < slots.length; i++) {
            if (DrainageLattice.step(lo, slots[i]) == hi) {
                at = i;
                break;
            }
        }
        if (at >= 0) {
            int n = slots.length;
            double[] c1 = centroid(lo, hi, DrainageLattice.step(lo, slots[(at - 1 + n) % n]));
            double[] c2 = centroid(lo, hi, DrainageLattice.step(lo, slots[(at + 1) % n]));
            double best = Double.MAX_VALUE;
            for (int k = -HANDOFF_SAMPLES; k <= HANDOFF_SAMPLES; k++) {
                double f = Math.abs(k) / (double) HANDOFF_SAMPLES * HANDOFF_SPAN;
                double[] c = k < 0 ? c1 : c2;
                double x = mx + (c[0] - mx) * f, z = mz + (c[1] - mz) * f;
                double score = read(x, z) + HANDOFF_WANDER * wiggle(x / grid, z / grid) + 1e-3 * Math.abs(k);
                if (score < best) {
                    best = score;
                    bx = x;
                    bz = z;
                }
            }
        }
        handoffs.put(key, new Handoff(lo, hi, bx, bz));
        return new double[]{bx, bz};
    }

    /** A triangle's middle, summed in the order of its keys so every node that asks gets the same number. */
    private double[] centroid(long a, long b, long c) {
        long[] k = {a, b, c};
        Arrays.sort(k);
        return new double[]{(lat.x(k[0]) + lat.x(k[1]) + lat.x(k[2])) / 3.0,
                (lat.z(k[0]) + lat.z(k[1]) + lat.z(k[2])) / 3.0};
    }

    // === Ways through a cell ================================================

    /**
     * The cheapest way through a cell over its grid, from a point on its edge (or the node) to a point on its edge, or,
     * with {@code target}, to the first grid point it marks. The points are the start, the grid points on the way and
     * the end; null where the cell gives no way.
     */
    private List<double[]> route(Cell c, double[] s, double[] e, boolean[] target) {
        int n = c.in.length;
        if (n == 0) return null;
        boolean[] end = target;
        if (end == null) {
            end = near(c, e[0], e[1]);
            if (end == null) return null;
        }
        boolean[] start = near(c, s[0], s[1]);
        if (start == null) return null;
        double base = e != null ? Math.min(read(s[0], s[1]), read(e[0], e[1])) : c.low;
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        Heap heap = new Heap(n);
        for (int i = 0; i < n; i++) {
            if (!start[i]) continue;
            dist[i] = Math.hypot(c.x(i) - s[0], c.z(i) - s[1]) * factor(c, i, base);
            heap.push(dist[i], i);
        }
        int hit = -1;
        while (!heap.empty()) {
            double d = heap.topKey();
            int i = heap.pop();
            if (d > dist[i]) continue;
            if (end[i]) {
                hit = i;
                break;
            }
            int gi = c.gi(i), gk = c.gk(i);
            for (int di = -1; di <= 1; di++) {
                for (int dk = -1; dk <= 1; dk++) {
                    if (di == 0 && dk == 0) continue;
                    int j = c.index(gi + di, gk + dk);
                    if (j < 0 || !c.in[j]) continue;
                    double len = (di != 0 && dk != 0) ? grid * Math.sqrt(2.0) : grid;
                    double nd = d + len * factor(c, j, base) + UPHILL * Math.max(0.0, c.hgt[j] - c.hgt[i]);
                    if (nd < dist[j]) {
                        dist[j] = nd;
                        prev[j] = i;
                        heap.push(nd, j);
                    }
                }
            }
        }
        if (hit < 0) return null;
        List<double[]> path = new ArrayList<>();
        for (int i = hit; i >= 0; i = prev[i]) path.add(new double[]{c.x(i), c.z(i)});
        path.add(s);
        java.util.Collections.reverse(path);
        if (e != null) path.add(e);
        return path;
    }

    /** What a step onto a grid point costs, per block. */
    private double factor(Cell c, int i, double base) {
        double valley = 1.0 + Math.max(0.0, c.hgt[i] - base) / (VALLEY * h);
        return valley * (1.0 + WIGGLE * wiggle(c.gi(i), c.gk(i)));
    }

    /** A slow noise over the grid, 0 to 1, for a way across flat ground to wander by. */
    private double wiggle(double gi, double gk) {
        double fx = gi / WIGGLE_WAVE, fz = gk / WIGGLE_WAVE;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = SeedHash.rand01(SeedHash.hash(seed, x0, z0, 0x61A7L)), b = SeedHash.rand01(SeedHash.hash(seed, x0 + 1, z0, 0x61A7L));
        double cc = SeedHash.rand01(SeedHash.hash(seed, x0, z0 + 1, 0x61A7L)), d = SeedHash.rand01(SeedHash.hash(seed, x0 + 1, z0 + 1, 0x61A7L));
        double top = a + (b - a) * tx, bottom = cc + (d - cc) * tx;
        return top + (bottom - top) * tz;
    }

    /** The grid points of the cell near a point on its edge, where a way can start or end. Null where there are none. */
    private boolean[] near(Cell c, double x, double z) {
        for (double reach : new double[]{1.6 * grid, 3.2 * grid}) {
            boolean[] out = new boolean[c.in.length];
            boolean any = false;
            int i0 = (int) Math.floor((x - reach) / grid), i1 = (int) Math.ceil((x + reach) / grid);
            int k0 = (int) Math.floor((z - reach) / grid), k1 = (int) Math.ceil((z + reach) / grid);
            for (int gk = k0; gk <= k1; gk++) {
                for (int gi = i0; gi <= i1; gi++) {
                    int i = c.index(gi, gk);
                    if (i < 0 || !c.in[i]) continue;
                    if (Math.hypot(c.x(i) - x, c.z(i) - z) > reach) continue;
                    out[i] = true;
                    any = true;
                }
            }
            if (any) return out;
        }
        return null;
    }

    /**
     * A way made fit to draw: the grid's zigzags straightened where they stray less than half a step from a straight
     * line, then the corners cut, three times over. A corner is only cut where both new points stay in the cell. The
     * leads along the cell edge are kept through the straightening, so the water still sets off the way the next cell
     * takes it on.
     */
    private List<double[]> tidy(Cell c, Led way, double half) {
        List<double[]> path = way.path();
        if (path.size() <= 2) return path;
        boolean[] keep = new boolean[path.size()];
        keep[0] = true;
        keep[path.size() - 1] = true;
        if (way.in()) keep[1] = true;
        if (way.out()) keep[path.size() - 2] = true;
        simplify(path, 0, path.size() - 1, Math.max(0.5 * grid, STRAIGHTEN * half), keep);
        List<double[]> p = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) if (keep[i]) p.add(path.get(i));
        p = fillet(c, p, FILLET * Math.max(half, grid));
        for (int pass = 0; pass < SMOOTH_PASSES && p.size() > 2; pass++) {
            List<double[]> out = new ArrayList<>();
            out.add(p.get(0));
            for (int i = 1; i < p.size() - 1; i++) {
                double[] a = p.get(i - 1), b = p.get(i), d = p.get(i + 1);
                double[] q = {b[0] + (a[0] - b[0]) * 0.25, b[1] + (a[1] - b[1]) * 0.25};
                double[] r = {b[0] + (d[0] - b[0]) * 0.25, b[1] + (d[1] - b[1]) * 0.25};
                if (c.contains(q[0], q[1]) && c.contains(r[0], r[1])) {
                    out.add(q);
                    out.add(r);
                } else {
                    out.add(b);
                }
            }
            out.add(p.get(p.size() - 1));
            p = out;
        }
        return p;
    }

    /**
     * Every corner of a way rounded on a circle of radius {@code radius}, where the two sides are long enough for it
     * and the arc stays in the cell; otherwise on the largest circle they leave room for. A river bends on a curve
     * a few times as wide as itself; cut once at a quarter of each side, a corner between two long straight reaches
     * of a wide river was still a street corner.
     */
    private List<double[]> fillet(Cell c, List<double[]> p, double radius) {
        if (p.size() <= 2) return p;
        List<double[]> out = new ArrayList<>();
        out.add(p.get(0));
        for (int i = 1; i < p.size() - 1; i++) {
            double[] a = p.get(i - 1), b = p.get(i), d = p.get(i + 1);
            double l1 = Math.hypot(b[0] - a[0], b[1] - a[1]), l2 = Math.hypot(d[0] - b[0], d[1] - b[1]);
            if (l1 < 1e-6 || l2 < 1e-6) { out.add(b); continue; }
            double ux = (b[0] - a[0]) / l1, uz = (b[1] - a[1]) / l1, vx = (d[0] - b[0]) / l2, vz = (d[1] - b[1]) / l2;
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, ux * vx + uz * vz)));
            if (turn < 0.05 || turn > 3.0) { out.add(b); continue; }
            double side = Math.signum(ux * vz - uz * vx);
            double tan = Math.tan(turn * 0.5);
            double t = Math.min(radius * tan, 0.45 * Math.min(l1, l2));
            List<double[]> arc = null;
            for (int tries = 0; tries < 3 && arc == null; tries++, t *= 0.5) {
                double r = t / tan;
                double sx = b[0] - ux * t, sz = b[1] - uz * t;
                double cx = sx - uz * side * r, cz = sz + ux * side * r;
                double ax = sx - cx, az = sz - cz;
                int steps = Math.max(2, (int) Math.ceil(turn * r / Math.max(1.0, 0.5 * grid)));
                List<double[]> pts = new ArrayList<>(steps + 1);
                boolean fits = true;
                for (int k = 0; k <= steps && fits; k++) {
                    double ang = side * turn * k / steps, cs = Math.cos(ang), sn = Math.sin(ang);
                    double px = cx + ax * cs - az * sn, pz = cz + ax * sn + az * cs;
                    if (!c.contains(px, pz)) fits = false;
                    pts.add(new double[]{px, pz});
                }
                if (fits) arc = pts;
            }
            if (arc == null) out.add(b);
            else out.addAll(arc);
        }
        out.add(p.get(p.size() - 1));
        return out;
    }

    /** A way through a cell, and whether it was given a lead along the cell edge at its start and at its end. */
    private record Led(List<double[]> path, boolean in, boolean out) {}

    /**
     * Which way water crosses from one node's cell into the next, worked out alike from both: half way between the
     * way in from the first node to the crossing and the way on from the crossing to the second. Along the straight
     * line between the two nodes the crossing, which sits at the lowest point of the edge rather than its middle, was
     * met at a slant, and the turn it forced went inside the cell instead.
     */
    private double[] across(long from, long to) {
        double[] hh = handoff(from, to);
        double ax = hh[0] - lat.x(from), az = hh[1] - lat.z(from), al = Math.hypot(ax, az);
        double cx = lat.x(to) - hh[0], cz = lat.z(to) - hh[1], cl = Math.hypot(cx, cz);
        if (al < 1e-9 || cl < 1e-9) return null;
        double tx = ax / al + cx / cl, tz = az / al + cz / cl, tl = Math.hypot(tx, tz);
        return tl < 1e-9 ? null : new double[]{tx / tl, tz / tl};
    }

    /**
     * A way through a cell made to leave and enter along the line between the two cells, so that where one cell's
     * way ends and the next one's begins the river runs straight on. Each cell used to reach the crossing from
     * wherever its own grid led, and the two came in at an angle: the sharp turns were all at the cell edges.
     * {@code in} and {@code out} are the directions at the start and the end, null where there is nothing to match.
     */
    private Led lead(Cell c, List<double[]> path, double[] in, double[] out) {
        double len = LEAD * grid;
        List<double[]> p = new ArrayList<>(path);
        boolean led = false, ledOut = false;
        if (in != null && p.size() >= 2) {
            double[] s = p.get(0);
            double[] q = {s[0] + in[0] * len, s[1] + in[1] * len};
            if (c.contains(q[0], q[1])) {
                // Grid points short of the lead would make the way double back on itself.
                while (p.size() > 2 && Math.hypot(p.get(1)[0] - s[0], p.get(1)[1] - s[1]) < len * 1.25) p.remove(1);
                p.add(1, q);
                led = true;
            }
        }
        if (out != null && p.size() >= 2) {
            double[] e = p.get(p.size() - 1);
            double[] q = {e[0] - out[0] * len, e[1] - out[1] * len};
            if (c.contains(q[0], q[1])) {
                // Never the lead just put in at the start.
                int keepFrom = led ? 2 : 1;
                while (p.size() - 2 >= keepFrom
                        && Math.hypot(p.get(p.size() - 2)[0] - e[0], p.get(p.size() - 2)[1] - e[1]) < len * 1.25) {
                    p.remove(p.size() - 2);
                }
                p.add(p.size() - 1, q);
                ledOut = true;
            }
        }
        // Too short a way for both leads: the one at the end would sit on the one at the start.
        if (led && ledOut && p.size() < 4) ledOut = false;
        return new Led(p, led, ledOut);
    }

    private static void simplify(List<double[]> p, int a, int b, double tol, boolean[] keep) {
        if (b - a < 2) return;
        double[] s = {p.get(a)[0], p.get(a)[1], p.get(b)[0], p.get(b)[1]};
        double worst = -1;
        int at = -1;
        for (int i = a + 1; i < b; i++) {
            double d = distToSeg(s, p.get(i)[0], p.get(i)[1]);
            if (d > worst) {
                worst = d;
                at = i;
            }
        }
        if (worst <= tol) return;
        keep[at] = true;
        simplify(p, a, at, tol, keep);
        simplify(p, at, b, tol, keep);
    }

    /** A plain binary heap of grid points by cost. */
    private static final class Heap {
        private double[] key;
        private int[] val;
        private int size;

        Heap(int n) {
            key = new double[Math.max(16, n)];
            val = new int[Math.max(16, n)];
        }

        boolean empty() {
            return size == 0;
        }

        double topKey() {
            return key[0];
        }

        void push(double k, int v) {
            if (size == key.length) {
                key = Arrays.copyOf(key, size * 2);
                val = Arrays.copyOf(val, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) / 2;
                if (key[p] <= k) break;
                key[i] = key[p];
                val[i] = val[p];
                i = p;
            }
            key[i] = k;
            val[i] = v;
        }

        int pop() {
            int top = val[0];
            double k = key[--size];
            int v = val[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int m = l + 1 < size && key[l + 1] < key[l] ? l + 1 : l;
                if (key[m] >= k) break;
                key[i] = key[m];
                val[i] = val[m];
                i = m;
            }
            key[i] = k;
            val[i] = v;
            return top;
        }
    }
}
