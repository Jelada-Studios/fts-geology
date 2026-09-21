package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.util.SetCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * The channels and lakes drawn over the drainage lattice, as the lengths a column is measured against.
 *
 * <p>A node that enough ground drains through carries a river. Its length of river runs from half way along the edge
 * the water came in by, past the node, to half way along the edge it leaves by, as a curve that the node pulls
 * towards itself. Every point of that curve stays in the corner of the node's own triangles, so the curves of
 * different nodes cannot cross; where a curve would leave that corner it is drawn straight along the two half edges
 * instead. A node under a lake is drawn as a disc of water at the lake's level, and so is every edge between two of
 * them.</p>
 *
 * <p>The water comes down the river and never goes up. At every point it is a block under the filled surface, and a
 * block under the lower of the two banks, and no higher than it was just upstream -- where two rivers meet, no higher
 * than the lower of the two.</p>
 */
final class RiverPieces {

    static final byte CHANNEL = 0, LAKE = 1;

    /** Blocks between the points of a length. */
    static final double STEP = 8.0;
    /** Blocks of water in a channel. */
    static final double DEPTH = 3.0;
    /** Channel half width, from a new river to a grown one. */
    static final double HALF_NEW = 2.0, HALF_GROWN = 7.0;
    /** How steeply a channel's wall climbs out of its bed, and how far over the water it is carried. */
    static final double WALL = 2.0, BANK_RISE = 2.0;
    /**
     * The half width of a lake's disc, as a share of the lattice cell: wide enough that neighbouring discs meet and
     * reach the shore half way to the next node. A lake cuts almost nothing ({@code RiverDensity}'s lake shave), so
     * the water only fills the hollow that is there and a wide disc costs nothing on the hillside.
     */
    static final double LAKE_HALF = 0.55;
    /** How far under its water a lake's floor is cut, where the hollow is not already deeper. */
    static final double LAKE_BED = 2.0;
    /**
     * A hollow shallower than this is not a lake: the river cuts through its rim instead. Most of the hollows the
     * lattice finds on flat ground are a block deep or less -- noise in the ground, not lakes -- and filled, they
     * dotted every plain with ponds and put a tenth of the land under water.
     */
    static final double LAKE_MIN_DEPTH = 4.0;
    /** How far under its raw ground a channel may be cut; the density clamps the cut at this. */
    static final double MAX_SHAVE = 12.0;
    /** A fall of this many blocks between two points, and the points below it that get a plunge pool. */
    static final double FALL_MIN = 10.0, POOL_WIDE = 1.8, POOL_DEEP = 1.6;
    static final int POOL_RUN = 3;
    /** How much of its width a torrent keeps, and the gradients that ramp between a torrent and a lowland river. */
    static final double STEEP_NARROW = 0.35, STEEP_FROM = 0.15, STEEP_OVER = 0.5;
    /** Where a river's length is counted from, for a river that leaves a lake: far enough that no spring opens there. */
    private static final float FROM_LAKE = 1.0e4f;

    private final DrainageLattice lat;
    private final DrainageLattice.Ground ground;
    private final double h;
    final int areaMin;

    private final SetCache<RiverNetwork.Point[]> made = new SetCache<>(13);
    private final SetCache<Boolean> drawn = new SetCache<>(10), deep = new SetCache<>(10);

    final LongAdder channels = new LongAdder(), straightened = new LongAdder(), drySamples = new LongAdder(),
            bankClamps = new LongAdder(), lakePoints = new LongAdder(), sinks = new LongAdder(),
            mouths = new LongAdder();

    RiverPieces(DrainageLattice lat, DrainageLattice.Ground ground, double horizontal, int areaMin) {
        this.lat = lat;
        this.ground = ground;
        this.h = horizontal;
        this.areaMin = areaMin;
    }

    static final RiverNetwork.Point[] NONE = new RiverNetwork.Point[0];

    /** Whatever this node draws: its length of river, its share of a lake, or nothing. */
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
            if (isChannel(v)) {
                for (long d : lat.donors(v)) {
                    if (lat.area(d) < areaMin || underLake(d)) continue;
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

    private boolean isChannel(long v) {
        return lat.g(v) > lat.sea && !underLake(v) && lat.area(v) >= areaMin;
    }

    private RiverNetwork.Point[] build(long v, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        if (lat.g(v) <= lat.sea) return NONE;
        if (underLake(v)) return lake(v);
        if (lat.area(v) < areaMin) return NONE;
        return channel(v, local);
    }

    private RiverNetwork.Point[] get(long d, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        RiverNetwork.Point[] p = local.get(d);
        return p != null ? p : of(d);
    }

    // === Lakes ==============================================================

    /** Whether a lake is deep enough to be one: its level over the lowest ground under it. */
    boolean deep(DrainageLattice.Lake lake) {
        Boolean hit = deep.get(lake.owner);
        if (hit != null) return hit;
        double floor = Double.MAX_VALUE;
        for (long m : lake.members) floor = Math.min(floor, lat.g(m));
        boolean yes = lake.level - floor >= LAKE_MIN_DEPTH;
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
        if (lat.g(v) <= lat.sea) return lat.sea;
        return underLake(v) ? lat.fill(v) : lat.g(v);
    }

    /** Whether a lake is drawn: only one that a river leaves, so a dimple on a hillside stays a dimple. */
    boolean drawn(DrainageLattice.Lake lake) {
        Boolean hit = drawn.get(lake.owner);
        if (hit != null) return hit;
        boolean yes = false;
        for (long m : lake.members) {
            long out = lake.next.get(m);
            if (out == Long.MIN_VALUE) continue;
            if (java.util.Arrays.binarySearch(lake.members, out) < 0 && lat.area(m) >= areaMin) {
                yes = true;
                break;
            }
        }
        drawn.put(lake.owner, yes);
        return yes;
    }

    private RiverNetwork.Point[] lake(long v) {
        DrainageLattice.Lake lake = lat.lakeOf(v);
        if (lake == null || !deep(lake) || !drawn(lake)) return NONE;
        float w = (float) (lake.level - 1.0);
        float bed = (float) (lake.level - 1.0 - LAKE_BED * h);
        float half = (float) (LAKE_HALF * lat.cell);
        List<RiverNetwork.Point> out = new ArrayList<>(5);
        float x = (float) lat.x(v), z = (float) lat.z(v);
        out.add(new RiverNetwork.Point(x, z, x, z, w, w, bed, bed, half, FROM_LAKE, LAKE));
        // The edges between two nodes under the same lake, drawn once each, so the discs close up into one sheet.
        for (int s = 0; s < 8; s++) {
            if (!lat.has(v, s)) continue;
            long n = DrainageLattice.step(v, s);
            if (n <= v || lat.weight(v, s) > lake.level) continue;
            if (java.util.Arrays.binarySearch(lake.members, n) < 0) continue;
            float mx = (float) ((lat.x(v) + lat.x(n)) * 0.5), mz = (float) ((lat.z(v) + lat.z(n)) * 0.5);
            out.add(new RiverNetwork.Point(mx, mz, mx, mz, w, w, bed, bed, half, FROM_LAKE, LAKE));
        }
        lakePoints.add(out.size());
        return out.toArray(NONE);
    }

    // === Channels ===========================================================

    private RiverNetwork.Point[] channel(long u, Long2ObjectOpenHashMap<RiverNetwork.Point[]> local) {
        channels.increment();
        double fu = level(u);
        long r = lat.receiver(u);
        // Where the water comes in: the biggest of the rivers above, and the lowest water any of them brings.
        long main = Long.MIN_VALUE;
        int mainArea = -1;
        double wIn = Double.MAX_VALUE, lenIn = 0;
        for (long d : lat.donors(u)) {
            int a = lat.area(d);
            if (a < areaMin) continue;
            double wd, ld;
            if (underLake(d)) {
                wd = level(d) - 1.0;
                ld = FROM_LAKE;
            } else {
                RiverNetwork.Point[] ps = get(d, local);
                if (ps.length == 0) continue;
                RiverNetwork.Point last = ps[ps.length - 1];
                wd = last.waterEnd();
                ld = last.fromHead() + Math.hypot(last.ex() - last.x(), last.ez() - last.z());
            }
            wIn = Math.min(wIn, wd);
            if (a > mainArea || (a == mainArea && d < main)) {
                mainArea = a;
                main = d;
                lenIn = ld;
            }
        }
        double ux = lat.x(u), uz = lat.z(u);
        double sx = ux, sz = uz, tS = fu - 1.0;
        if (main != Long.MIN_VALUE) {
            sx = (lat.x(main) + ux) * 0.5;
            sz = (lat.z(main) + uz) * 0.5;
            tS = (level(main) + fu) * 0.5 - 1.0;
        } else {
            wIn = fu - 1.0;
        }
        double ex = ux, ez = uz, tE = fu - 1.0;
        if (r != Long.MIN_VALUE) {
            ex = (ux + lat.x(r)) * 0.5;
            ez = (uz + lat.z(r)) * 0.5;
            tE = (fu + level(r)) * 0.5 - 1.0;
            if (lat.g(r) <= lat.sea) mouths.increment();
        }
        double half = halfFor(lat.area(u));
        List<RiverNetwork.Point> pts = lay(u, true, sx, sz, ux, uz, ex, ez, tS, fu - 1.0, tE, wIn, lenIn, half);
        if (pts == null) {
            straightened.increment();
            pts = lay(u, false, sx, sz, ux, uz, ex, ez, tS, fu - 1.0, tE, wIn, lenIn, half);
        }
        if (r == Long.MIN_VALUE) {
            // A river the ground closes round: it ends in a pond of its own.
            sinks.increment();
            RiverNetwork.Point last = pts.get(pts.size() - 1);
            float w = last.waterEnd();
            float bed = (float) (w - LAKE_BED * h);
            pts.add(new RiverNetwork.Point((float) ux, (float) uz, (float) ux, (float) uz, w, w, bed, bed,
                    (float) (LAKE_HALF * lat.cell * 0.6), FROM_LAKE, LAKE));
        }
        return pts.toArray(NONE);
    }

    /** Half width from the ground a river drains: a new river at the threshold, a grown one at the cap. */
    private double halfFor(int area) {
        double t = (Math.sqrt(area / (double) areaMin) - 1.0) / (Math.sqrt(lat.areaCap / (double) areaMin) - 1.0);
        t = Math.max(0.0, Math.min(1.0, t));
        return (HALF_NEW + (HALF_GROWN - HALF_NEW) * t) * h;
    }

    /**
     * The points of one length, along the curve or, with {@code curved} off, straight along the two half edges.
     * Null where the curve leaves the node's own triangles or runs over ground the channel cannot be cut through.
     */
    private List<RiverNetwork.Point> lay(long u, boolean curved, double sx, double sz, double ux, double uz,
                                         double ex, double ez, double tS, double tU, double tE, double wIn,
                                         double lenIn, double half) {
        // Sample the path finely enough to measure it, then set points every STEP blocks along it.
        int fine = 32;
        double[] px = new double[fine + 1], pz = new double[fine + 1], pt = new double[fine + 1];
        for (int k = 0; k <= fine; k++) {
            double t = k / (double) fine;
            double x, z, target;
            if (curved) {
                double a = (1 - t) * (1 - t), b = 2 * t * (1 - t), c = t * t;
                x = a * sx + b * ux + c * ex;
                z = a * sz + b * uz + c * ez;
                target = a * tS + b * tU + c * tE;
            } else if (t <= 0.5) {
                double f = t * 2;
                x = sx + (ux - sx) * f;
                z = sz + (uz - sz) * f;
                target = tS + (tU - tS) * f;
            } else {
                double f = (t - 0.5) * 2;
                x = ux + (ex - ux) * f;
                z = uz + (ez - uz) * f;
                target = tU + (tE - tU) * f;
            }
            px[k] = x;
            pz[k] = z;
            pt[k] = target;
            if (curved && k > 0 && k < fine && !inStar(u, x, z)) return null;
        }
        double[] cum = new double[fine + 1];
        for (int k = 1; k <= fine; k++) cum[k] = cum[k - 1] + Math.hypot(px[k] - px[k - 1], pz[k] - pz[k - 1]);
        double len = cum[fine];
        int n = Math.max(1, (int) Math.ceil(len / (STEP * h)));
        double[] qx = new double[n + 1], qz = new double[n + 1], qw = new double[n + 1];
        int seg = 0;
        double w = wIn;
        int dry = 0;
        for (int q = 0; q <= n; q++) {
            double want = len * q / n;
            while (seg < fine - 1 && cum[seg + 1] < want) seg++;
            double span = cum[seg + 1] - cum[seg];
            double f = span < 1e-9 ? 0 : (want - cum[seg]) / span;
            double x = px[seg] + (px[seg + 1] - px[seg]) * f, z = pz[seg] + (pz[seg + 1] - pz[seg]) * f;
            double target = pt[seg] + (pt[seg + 1] - pt[seg]) * f;
            // The banks: the lowest ground either side, just past where the cut wall stops. How wide the channel is
            // drawn depends on how fast the water falls, which is what is being worked out, so both the narrowest
            // and the widest it can be drawn are read.
            double dx, dz;
            double tx = px[Math.min(fine, seg + 1)] - px[seg], tz = pz[Math.min(fine, seg + 1)] - pz[seg];
            double tl = Math.hypot(tx, tz);
            if (tl < 1e-9) { dx = 0; dz = 0; } else { dx = -tz / tl; dz = tx / tl; }
            double in = half * STEEP_NARROW + BANK_RISE * h + 1.0, out = half * POOL_WIDE + BANK_RISE * h + 1.0;
            double rim = Math.min(Math.min(read(x + dx * out, z + dz * out), read(x - dx * out, z - dz * out)),
                    Math.min(read(x + dx * in, z + dz * in), read(x - dx * in, z - dz * in)));
            double lw = Math.min(w, Math.min(target, rim - 1.0));
            if (rim - 1.0 < Math.min(w, target)) bankClamps.increment();
            w = lw;
            // Where the ground stands too high over the water for the cut to reach, the curve has wandered off the
            // valley: the straight path through the node keeps to it.
            if (read(x, z) > w + MAX_SHAVE - 2.0) dry++;
            qx[q] = x;
            qz[q] = z;
            qw[q] = w;
        }
        if (dry > 0) {
            if (curved) return null;
            drySamples.add(dry);
        }
        List<RiverNetwork.Point> pts = new ArrayList<>(n + 1);
        double depth = DEPTH * h;
        double[] wide = new double[n + 1], deep = new double[n + 1];
        java.util.Arrays.fill(wide, 1.0);
        java.util.Arrays.fill(deep, 1.0);
        for (int q = 0; q < n; q++) {
            if (qw[q] - qw[q + 1] < FALL_MIN) continue;
            for (int k = q + 1; k <= Math.min(n, q + POOL_RUN); k++) {
                wide[k] = POOL_WIDE;
                deep[k] = POOL_DEEP;
            }
        }
        double step = len / n;
        for (int q = 0; q < n; q++) {
            double fall = (qw[q] - qw[q + 1]) / Math.max(step, 1e-6);
            double t = Math.max(0.0, Math.min(1.0, 1.0 - (fall - STEEP_FROM) / STEEP_OVER));
            double gentle = t * t * (3.0 - 2.0 * t);
            double narrow = STEEP_NARROW + (1.0 - STEEP_NARROW) * gentle;
            double hw = half * narrow * Math.max(wide[q], wide[q + 1]);
            pts.add(new RiverNetwork.Point((float) qx[q], (float) qz[q], (float) qx[q + 1], (float) qz[q + 1],
                    (float) qw[q], (float) qw[q + 1],
                    (float) (qw[q] - depth * deep[q]), (float) (qw[q + 1] - depth * deep[q + 1]),
                    (float) hw, (float) (lenIn + step * q), CHANNEL));
        }
        return pts;
    }

    private double read(double x, double z) {
        return ground.heightAt((int) Math.floor(x), (int) Math.floor(z));
    }

    /** Whether a point lies in one of the node's own triangles. */
    private boolean inStar(long u, double x, double z) {
        double ux = lat.x(u), uz = lat.z(u);
        double ang = Math.atan2(z - uz, x - ux);
        // The neighbours round the node, in order of their bearing; the point lies between two consecutive ones.
        double[] bx = new double[8], bz = new double[8], ba = new double[8];
        int n = 0;
        for (int s = 0; s < 8; s++) {
            if (!lat.has(u, s)) continue;
            long k = DrainageLattice.step(u, s);
            bx[n] = lat.x(k);
            bz[n] = lat.z(k);
            ba[n] = Math.atan2(bz[n] - uz, bx[n] - ux);
            n++;
        }
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a == b) continue;
                // b follows a going anticlockwise with nothing between them.
                double span = norm(ba[b] - ba[a]);
                boolean next = true;
                for (int c = 0; c < n; c++) {
                    if (c == a || c == b) continue;
                    double o = norm(ba[c] - ba[a]);
                    if (o > 0 && o < span) { next = false; break; }
                }
                if (!next) continue;
                double o = norm(ang - ba[a]);
                if (o < 0 || o > span) continue;
                // Inside the triangle u, a, b: on u's side of the edge a-b.
                double cx = bx[b] - bx[a], cz = bz[b] - bz[a];
                double su = cx * (uz - bz[a]) - cz * (ux - bx[a]);
                double sp = cx * (z - bz[a]) - cz * (x - bx[a]);
                return su * sp > 0 || Math.abs(sp) < 1e-9;
            }
        }
        return false;
    }

    private static double norm(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }
}
