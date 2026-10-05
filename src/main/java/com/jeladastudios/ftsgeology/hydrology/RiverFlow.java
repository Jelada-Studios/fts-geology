package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.util.SetCache;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * What the rivers carry: the long-run discharge of every length of the network, and where its water comes into it.
 *
 * <p>A node's discharge is its catchment counted in nodes, at {@link #PER_NODE} a node: what the channels the network
 * draws can carry. A river's width is drawn from its catchment alone, not its climate, and the water a channel holds
 * at rest is its width's; weighed by the climate's runoff, a wet country's springs brought more water than their rills
 * could take (a stream six blocks wide at its head in the tall world was given sixty cubic metres a second). So the
 * discharge follows the drawn river, and the weather's part is the forcing's ({@link RiverForcing}). Below the
 * catchment's cap the sums are exact: what leaves a node is what came into it and its own ground, so a channel carries
 * what came down to it, and at a join the two rivers' discharges add. At the cap a node is only known to drain at least
 * so much, and is marked so.</p>
 *
 * <p>The water comes in at the head of a river, at the way out of a lake (what the lake's own ground gives, the rivers
 * into it carried on their own), and along the way where a channel crosses into a node's cell: there the ground of the
 * cell and the streams too small to draw add to it. A river that sinks into soluble rock leaves at its swallow holes and
 * comes back where its cave ends, the same water; a river the ground closes round ends in a pond and goes no further.</p>
 */
public final class RiverFlow {

    private RiverFlow() {}

    /**
     * Cubic metres a second a node of catchment adds, in either world. A spring's rill, at the 32 nodes a river starts
     * from, gets about 1.6 (a rill two blocks across carries 1 to 2 at its slope); a river six blocks across in the
     * normal world, some 165 nodes, about 8; one at the cap, about 100. Under what the drawn channels carry everywhere
     * but at the cap of the normal world, where it is about what they carry; the tall world's wider rivers carry more.
     */
    static final double PER_NODE = 0.05;
    /** How near the water already drawn a joining river's end lies when it joins it, blocks. */
    private static final double JOINS = 0.75;

    public enum Kind { HEAD, LAKE_OUTLET, LATERAL, SINK, RESURGENCE }

    /** Where water comes into the network or leaves it: a point on a channel's middle line, and what it brings. */
    public record Source(int x, int z, double discharge, Kind kind, boolean capped, long linkId, double linkLength) {}

    private static final SetCache<Drawing> DRAWINGS = new SetCache<>(13);

    public static void clear() {
        DRAWINGS.clear();
        READY.clear();
    }

    // === A node's discharge =================================================

    /** A node's long-run discharge, m^3/s: its catchment's nodes, every one of them counted alike. */
    public static double discharge(ServerLevel level, long k) {
        return PER_NODE * RiverNetwork.lattice().area(k);
    }

    /** Whether a node drains as much as the network counts, and so is only known to drain at least what it says. */
    public static boolean capped(long k) {
        DrainageLattice lat = RiverNetwork.lattice();
        return lat.area(k) >= lat.areaCap;
    }

    /** A node's catchment in blocks squared (at least, where capped). */
    public static double catchment(long k) {
        DrainageLattice lat = RiverNetwork.lattice();
        return lat.area(k) * lat.cell * lat.cell;
    }

    // === One node's drawing =================================================

    /** What one node draws, with the discharge of each of its lengths and the water that comes in or leaves there. */
    static final class Drawing {
        final RiverNetwork.Point[] pts;
        final double[] q;
        final List<Source> sources;

        Drawing(RiverNetwork.Point[] pts, double[] q, List<Source> sources) {
            this.pts = pts;
            this.q = q;
            this.sources = sources;
        }

        /** Where a length is in this drawing, or -1. */
        int indexOf(RiverNetwork.Point p) {
            for (int i = 0; i < pts.length; i++) if (pts[i] == p || pts[i].equals(p)) return i;
            return -1;
        }
    }

    static Drawing drawing(ServerLevel level, long owner) {
        Drawing hit = DRAWINGS.get(owner);
        if (hit != null) return hit;
        Drawing made = draw(level, owner);
        DRAWINGS.put(owner, made);
        return made;
    }

    private static Drawing draw(ServerLevel level, long owner) {
        DrainageLattice lat = RiverNetwork.lattice();
        RiverPieces pc = RiverNetwork.pieces();
        RiverNetwork.Point[] pts = pc.of(owner);
        double[] q = new double[pts.length];
        List<Source> src = new ArrayList<>();
        if (pts.length == 0) return new Drawing(pts, q, src);
        // The ways the drawing is made of: runs of lengths that carry one node's river, of one kind.
        List<int[]> ways = new ArrayList<>();
        for (int i = 0; i < pts.length; ) {
            int j = i + 1;
            if (pts[i].kind() != RiverPieces.LAKE) {
                while (j < pts.length && pts[j].node() == pts[i].node() && pts[j].kind() == pts[i].kind()) j++;
            }
            ways.add(new int[]{i, j});
            i = j;
        }
        int n = ways.size();
        // Where each joining way ends on a way drawn before it: {way, length}, or none where it ends in still water.
        int[][] onto = new int[n][];
        for (int w = 0; w < n; w++) {
            RiverNetwork.Point last = pts[ways.get(w)[1] - 1];
            if (last.kind() != RiverPieces.JOIN) continue;
            double best = JOINS;
            for (int a = 0; a < w; a++) {
                int[] wa = ways.get(a);
                if (pts[wa[0]].kind() == RiverPieces.LAKE) continue;
                for (int i = wa[0]; i < wa[1]; i++) {
                    double d = toSegment(pts[i], last.ex(), last.ez());
                    if (d < best) {
                        best = d;
                        onto[w] = new int[]{a, i};
                    }
                }
            }
        }
        boolean riverNode = pc.isRiver(owner);
        double qOwn = discharge(level, owner);
        // What the joining rivers bring, by the node whose river each carries.
        double joined = 0;
        LongOpenHashSet joining = new LongOpenHashSet();
        for (int[] w : ways) {
            RiverNetwork.Point p = pts[w[0]];
            if (p.kind() == RiverPieces.JOIN && p.node() != owner && joining.add(p.node())) joined += discharge(level, p.node());
        }
        double[] base = new double[n], out = new double[n];
        for (int w = 0; w < n; w++) {
            RiverNetwork.Point p = pts[ways.get(w)[0]];
            if (p.kind() == RiverPieces.CHANNEL) base[w] = Math.max(0, qOwn - joined);
            else if (p.kind() == RiverPieces.LAKE) base[w] = qOwn;
            else if (p.node() != owner) base[w] = discharge(level, p.node());
            else base[w] = qOwn;     // the way out of a lake: what leaves it, less what joins it on the way (below)
        }
        // What each way carries out at its end: its own and everything joined on, the latest drawn first.
        for (int w = n - 1; w >= 0; w--) {
            double joinedOn = 0;
            for (int v = w + 1; v < n; v++) if (onto[v] != null && onto[v][0] == w) joinedOn += out[v];
            RiverNetwork.Point p = pts[ways.get(w)[0]];
            if (p.kind() == RiverPieces.JOIN && p.node() == owner) base[w] = Math.max(0, base[w] - joinedOn);
            out[w] = base[w] + joinedOn;
        }
        // A join counts from the length after the one it meets, or from that one where it meets its far half; the way's
        // last length carries all of it out, wherever on it the join came in.
        for (int w = 0; w < n; w++) {
            int[] wa = ways.get(w);
            for (int i = wa[0]; i < wa[1]; i++) {
                double v = base[w];
                for (int a = w + 1; a < n; a++) {
                    if (onto[a] == null || onto[a][0] != w) continue;
                    int at = onto[a][1];
                    RiverNetwork.Point end = pts[ways.get(a)[1] - 1];
                    boolean far = alongSegment(pts[at], end.ex(), end.ez()) >= 0.5;
                    if (at < i || at == i && far || i == wa[1] - 1) v += out[a];
                }
                q[i] = v;
            }
        }
        boolean capped = capped(owner);
        if (riverNode) {
            int c0 = -1, c1 = -1;
            for (int[] w : ways) {
                if (pts[w[0]].kind() == RiverPieces.CHANNEL && pts[w[0]].node() == owner) {
                    c0 = w[0];
                    c1 = w[1];
                    break;
                }
            }
            if (c0 >= 0) {
                RiverNetwork.Point first = pts[c0], last = pts[c1 - 1];
                boolean head = first.fromHead() < 1e-3f;
                boolean sunk = first.sunk();
                if (head) {
                    src.add(new Source(block(first.x()), block(first.z()), qOwn, Kind.HEAD, capped, 0L, 0));
                } else {
                    double main = 0;
                    for (long d : pc.riverDonors(owner, null)) {
                        if (joining.contains(d)) continue;
                        main = discharge(level, d);
                        break;
                    }
                    double lateral = qOwn - main - joined;
                    int lx = block(sunk ? last.ex() : first.x()), lz = block(sunk ? last.ez() : first.z());
                    if (!capped && lateral > 1e-9) src.add(new Source(lx, lz, lateral, Kind.LATERAL, false, 0L, 0));
                    if (sunk) {
                        double length = 0;
                        for (int i = c0; i < c1; i++) length += Math.hypot(pts[i].ex() - pts[i].x(), pts[i].ez() - pts[i].z());
                        double sinks = main;
                        src.add(new Source(block(first.x()), block(first.z()), main, Kind.SINK, capped, owner, length));
                        for (int[] w : ways) {
                            RiverNetwork.Point p = pts[w[0]];
                            if (p.kind() != RiverPieces.JOIN || p.node() == owner || !p.sunk()) continue;
                            double d = discharge(level, p.node());
                            sinks += d;
                            src.add(new Source(block(p.x()), block(p.z()), d, Kind.SINK, capped(p.node()), owner, length));
                        }
                        src.add(new Source(block(last.ex()), block(last.ez()), sinks, Kind.RESURGENCE, capped, owner, length));
                    }
                }
            }
            // A river the ground closes round: it ends in a pond, and its water goes nowhere further.
            for (int[] w : ways) {
                RiverNetwork.Point p = pts[w[0]];
                if (p.kind() == RiverPieces.LAKE && p.node() == owner) {
                    src.add(new Source(block(p.x()), block(p.z()), qOwn, Kind.SINK, capped, 0L, 0));
                }
            }
        } else if (pc.underLake(owner)) {
            // The way out of a lake starts at its outlet member: what the lake's own ground gives.
            int[] outWay = null;
            for (int[] w : ways) {
                RiverNetwork.Point p = pts[w[0]];
                if (p.kind() == RiverPieces.JOIN && p.node() == owner) {
                    outWay = w;
                    break;
                }
            }
            DrainageLattice.Lake lake = lat.lakeOf(owner);
            if (outWay != null && lake != null) {
                double into = 0;
                for (long m : lake.members) {
                    LongOpenHashSet seen = new LongOpenHashSet();
                    for (RiverNetwork.Point p : pc.of(m)) {
                        if (p.kind() == RiverPieces.JOIN && p.node() != m && seen.add(p.node())) into += discharge(level, p.node());
                    }
                }
                RiverNetwork.Point p = pts[outWay[0]];
                double own = Math.max(0, qOwn - into);
                src.add(new Source(block(p.x()), block(p.z()), own, Kind.LAKE_OUTLET, capped, 0L, 0));
            }
        }
        return new Drawing(pts, q, src);
    }

    private static int block(float v) {
        return (int) Math.floor(v);
    }

    /** How far along a length, 0 to 1, the point on it nearest {@code (x, z)} lies. */
    private static double alongSegment(RiverNetwork.Point p, double x, double z) {
        double ax = p.ex() - p.x(), az = p.ez() - p.z(), len2 = ax * ax + az * az;
        return len2 < 1e-12 ? 1.0 : Math.max(0, Math.min(1, ((x - p.x()) * ax + (z - p.z()) * az) / len2));
    }

    private static double toSegment(RiverNetwork.Point p, double x, double z) {
        double ax = p.ex() - p.x(), az = p.ez() - p.z(), len2 = ax * ax + az * az;
        double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - p.x()) * ax + (z - p.z()) * az) / len2));
        return Math.hypot(p.x() + ax * t - x, p.z() + az * t - z);
    }

    // === Asked of a column, or a chunk ======================================

    /** The discharge a length carries, m^3/s, and whether it is only known as at least that; NaN where not found. */
    static double[] dischargeOf(ServerLevel level, RiverNetwork.Point p) {
        DrainageLattice lat = RiverNetwork.lattice();
        long[] owners = {p.node(), lat.receiver(p.node())};
        for (long o : owners) {
            if (o == Long.MIN_VALUE) continue;
            Drawing d = drawing(level, o);
            int i = d.indexOf(p);
            if (i >= 0) return new double[]{d.q[i], capped(p.node()) ? 1 : 0};
        }
        return new double[]{discharge(level, p.node()), capped(p.node()) ? 1 : 0};
    }

    /** Whether a length is the way out of a lake: drawn by the lake's own outlet member. */
    static boolean outletWay(RiverNetwork.Point p) {
        RiverPieces pc = RiverNetwork.pieces();
        return p.kind() == RiverPieces.JOIN && pc.underLake(p.node()) && p.fromHead() >= 1.0e3f
                && indexIn(pc.of(p.node()), p) >= 0;
    }

    private static int indexIn(RiverNetwork.Point[] pts, RiverNetwork.Point p) {
        for (int i = 0; i < pts.length; i++) if (pts[i] == p || pts[i].equals(p)) return i;
        return -1;
    }

    /** The water that leaves a lake, m^3/s: its outlet member's discharge. */
    static double lakeOutflow(ServerLevel level, long lakeOwner) {
        DrainageLattice lat = RiverNetwork.lattice();
        DrainageLattice.Lake lake = lat.lakeOf(lakeOwner);
        if (lake == null) return 0;
        RiverPieces pc = RiverNetwork.pieces();
        for (long m : lake.members) {
            for (RiverNetwork.Point p : pc.of(m)) if (p.kind() == RiverPieces.JOIN && p.node() == m) return discharge(level, m);
        }
        double best = 0;
        for (long m : lake.members) best = Math.max(best, discharge(level, m));
        return best;
    }

    /** The lake's outlet member, the node the way out of it is drawn from, or MIN_VALUE. */
    static long lakeOutlet(long lakeOwner) {
        DrainageLattice lat = RiverNetwork.lattice();
        DrainageLattice.Lake lake = lat.lakeOf(lakeOwner);
        if (lake == null) return Long.MIN_VALUE;
        RiverPieces pc = RiverNetwork.pieces();
        for (long m : lake.members) {
            for (RiverNetwork.Point p : pc.of(m)) if (p.kind() == RiverPieces.JOIN && p.node() == m) return m;
        }
        return Long.MIN_VALUE;
    }

    /** What a lake's own ground gives, m^3/s: its way out's LAKE_OUTLET source; 0 for a lake no river leaves. */
    static double lakeOwn(ServerLevel level, long lakeOwner) {
        long out = lakeOutlet(lakeOwner);
        if (out == Long.MIN_VALUE) return 0;
        for (Source s : drawing(level, out).sources) if (s.kind() == Kind.LAKE_OUTLET) return s.discharge();
        return 0;
    }

    /** The water that comes in or leaves in a chunk, from every node whose cell may reach into it. */
    static List<Source> sourcesIn(ServerLevel level, int cx, int cz) {
        DrainageLattice lat = RiverNetwork.lattice();
        double cell = lat.cell;
        int x0 = cx << 4, z0 = cz << 4;
        int i0 = (int) Math.floor((x0 - 2 * cell) / cell), i1 = (int) Math.floor((x0 + 16 + 2 * cell) / cell);
        int j0 = (int) Math.floor((z0 - 2 * cell) / cell), j1 = (int) Math.floor((z0 + 16 + 2 * cell) / cell);
        List<Source> out = new ArrayList<>();
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                for (Source s : drawing(level, DrainageLattice.key(i, j)).sources) {
                    if (s.x() >> 4 == cx && s.z() >> 4 == cz) out.add(s);
                }
            }
        }
        return out;
    }

    /** The node whose river a column's nearest length carries, a few steps up its main river: for its weather. */
    static long[] upstream(long node, int steps) {
        DrainageLattice lat = RiverNetwork.lattice();
        RiverPieces pc = RiverNetwork.pieces();
        LongArrayList out = new LongArrayList();
        long k = node;
        for (int s = 0; s < steps && k != Long.MIN_VALUE; s++) {
            out.add(k);
            long[] ds = pc.riverDonors(k, null);
            if (ds.length == 0) break;
            // A few nodes up, not one: a cell is small against a catchment.
            long next = ds[0];
            for (int hop = 0; hop < 3; hop++) {
                long[] more = pc.riverDonors(next, null);
                if (more.length == 0) break;
                next = more[0];
            }
            k = next;
        }
        return out.toLongArray();
    }

    /** Where a lattice node lies. */
    static double[] at(long k) {
        DrainageLattice lat = RiverNetwork.lattice();
        return new double[]{lat.x(k), lat.z(k)};
    }

    // === The answers the API gives ==========================================

    public static final int PRESENT = 0, ABSENT = 1, NOT_READY = 2;

    /** A column's channel as the API tells it; {@code status} one of PRESENT, ABSENT, NOT_READY. */
    public record Asked(int status, double distance, double halfWidth, double water, double bed, double flowX, double flowZ,
                        boolean lake, long lakeId, double lakeLevel, boolean lakeOutlet, boolean head, double fromHead,
                        boolean sunk, double discharge, double catchment, boolean capped, double baseFactor,
                        double stormFactor, double lakeArea, double lakeOwnDischarge) {}

    private static Asked only(int status) {
        return new Asked(status, 0, 0, 0, 0, 0, 0, false, 0L, 0, false, false, 0, false, 0, 0, false, 1, 1, 0, 0);
    }

    private static double finite(double v, double or) {
        return Double.isFinite(v) ? v : or;
    }

    /**
     * The channel or lake at a column, within {@code within} blocks past its bed. On the server thread it never waits
     * for the network: a square not worked out yet answers NOT_READY, and is worked out in the background. Elsewhere it
     * waits up to {@code waitMs} (below zero, for as long as it takes), and answers NOT_READY past it.
     */
    public static Asked ask(ServerLevel level, int x, int z, double within, long waitMs) {
        if (!RiverNetwork.ready()) return only(ABSENT);
        long wait = RiverNetwork.serverThread() ? 0 : waitMs;
        return RiverNetwork.within(wait, () -> {
            RiverNetwork.At a = RiverNetwork.lookNow(x, z);
            if (RiverNetwork.cutShort()) return only(NOT_READY);
            if (a.distance() == Double.MAX_VALUE || !a.within(within)) return only(ABSENT);
            double water = a.water(), bed = a.bed();
            if (a.lake()) {
                RiverPieces.LakeMask m = RiverNetwork.lakeAt(x, z);
                if (RiverNetwork.cutShort()) return only(NOT_READY);
                long lakeId = m == null ? 0L : m.owner;
                double lakeLevel = finite(m == null ? water : m.water, water);
                long out = m == null ? Long.MIN_VALUE : lakeOutlet(m.owner);
                double q = out == Long.MIN_VALUE ? 0 : discharge(level, out);
                double[] f = out == Long.MIN_VALUE ? RiverForcing.factors(level, x, z) : RiverForcing.upstream(level, out);
                water = finite(water, lakeLevel);
                bed = finite(bed, water - 2);
                if (!Double.isFinite(water)) return only(ABSENT);
                double area = m == null ? 0 : m.area(), own = m == null ? 0 : lakeOwn(level, m.owner);
                return new Asked(PRESENT, finite(a.distance(), 0), finite(a.halfWidth(), 0), water, bed, 0, 0, true, lakeId,
                        lakeLevel, false, false, 0, false, finite(q, 0), out == Long.MIN_VALUE ? 0 : catchment(out),
                        out != Long.MIN_VALUE && capped(out), finite(f[0], 1), finite(f[1], 1), finite(area, 0), finite(own, 0));
            }
            RiverNetwork.Point p = RiverNetwork.nearest(x, z);
            if (RiverNetwork.cutShort()) return only(NOT_READY);
            if (p == null) return only(ABSENT);
            // A level the blend could not make a number of: the nearest length's own.
            water = finite(water, p.water());
            bed = finite(bed, p.bed());
            if (!Double.isFinite(water) || !Double.isFinite(bed)) return only(ABSENT);
            double[] dq = dischargeOf(level, p);
            double[] f = RiverForcing.upstream(level, p.node());
            double fx = finite(a.fx(), 0), fz = finite(a.fz(), 0), len = Math.hypot(fx, fz);
            if (len > 1e-9) {
                fx /= len;
                fz /= len;
            }
            return new Asked(PRESENT, finite(a.distance(), 0), finite(a.halfWidth(), 0), water, bed, fx, fz, false, 0L, 0,
                    outletWay(p), a.isHead(), finite(a.fromHead(), 0), a.sunk(), finite(dq[0], 0), catchment(p.node()),
                    dq[1] > 0, finite(f[0], 1), finite(f[1], 1), 0, 0);
        });
    }

    /**
     * A check of the flow over a square {@code half} blocks round a point, off the server thread: the API's answers on
     * a grid (none NaN, how many there are), and every node's drawing -- a channel carries out what came into it and its
     * own ground, a lateral or a lake's own never had to be clipped at nought, a join ends on the water it joins, a
     * cave's resurgence brings what went into its sinks.
     */
    public static String audit(ServerLevel level, int x0, int z0, int half) {
        long t0 = System.nanoTime();
        DrainageLattice lat = RiverNetwork.lattice();
        RiverPieces pc = RiverNetwork.pieces();
        if (lat == null) return "no network";
        int present = 0, absent = 0, notReady = 0, nan = 0, lakes = 0, capped = 0, heads = 0, sunk = 0;
        List<Double> qs = new ArrayList<>();
        for (int x = x0 - half; x <= x0 + half; x += 4) {
            for (int z = z0 - half; z <= z0 + half; z += 4) {
                Asked a = ask(level, x, z, 0, -1L);
                if (a.status() == NOT_READY) { notReady++; continue; }
                if (a.status() == ABSENT) { absent++; continue; }
                present++;
                double[] all = {a.distance(), a.halfWidth(), a.water(), a.bed(), a.flowX(), a.flowZ(), a.lakeLevel(),
                        a.fromHead(), a.discharge(), a.catchment(), a.baseFactor(), a.stormFactor(), a.lakeArea(),
                        a.lakeOwnDischarge()};
                for (double v : all) if (!Double.isFinite(v)) { nan++; break; }
                if (a.lake()) lakes++;
                if (a.capped()) capped++;
                if (a.head()) heads++;
                if (a.sunk()) sunk++;
                qs.add(a.discharge());
            }
        }
        java.util.Collections.sort(qs);
        int i0 = (int) Math.floor((x0 - half) / lat.cell) - 1, i1 = (int) Math.floor((x0 + half) / lat.cell) + 1;
        int j0 = (int) Math.floor((z0 - half) / lat.cell) - 1, j1 = (int) Math.floor((z0 + half) / lat.cell) + 1;
        int nodes = 0, rivers = 0, mismatched = 0, clipped = 0, loose = 0, links = 0, badLinks = 0, closed = 0;
        double worst = 0, clippedQ = 0;
        String firstOff = "";
        java.util.EnumMap<Kind, double[]> byKind = new java.util.EnumMap<>(Kind.class);
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                long v = DrainageLattice.key(i, j);
                nodes++;
                Drawing d = drawing(level, v);
                for (Source s : d.sources) {
                    double[] t = byKind.computeIfAbsent(s.kind(), k -> new double[2]);
                    t[0]++;
                    t[1] += s.discharge();
                    if (s.kind() == Kind.SINK && s.linkId() == 0) closed++;
                }
                if (!pc.isRiver(v) || d.pts.length == 0) continue;
                rivers++;
                boolean cap = capped(v);
                double qv = discharge(level, v);
                // The channel's last length carries the node's whole discharge.
                int last = -1;
                for (int k = 0; k < d.pts.length; k++) if (d.pts[k].kind() == RiverPieces.CHANNEL && d.pts[k].node() == v) last = k;
                if (last >= 0 && !cap) {
                    double rel = Math.abs(d.q[last] - qv) / Math.max(1e-9, qv);
                    worst = Math.max(worst, rel);
                    if (rel > 1e-6 && mismatched++ == 0) {
                        firstOff = String.format(java.util.Locale.ROOT, " (first at %.0f %.0f: %.2f of %.2f, %d lengths)",
                                d.pts[last].ex(), d.pts[last].ez(), d.q[last], qv, d.pts.length);
                    }
                }
                // What came in from above: the main river, the joins; the rest is the cell's own.
                if (!cap) {
                    LongOpenHashSet joining = new LongOpenHashSet();
                    double joined = 0;
                    for (RiverNetwork.Point p : d.pts) {
                        if (p.kind() == RiverPieces.JOIN && p.node() != v && joining.add(p.node())) joined += discharge(level, p.node());
                    }
                    double main = 0;
                    boolean head = last >= 0 && d.pts[0].fromHead() < 1e-3f;
                    if (!head) {
                        for (long dd : pc.riverDonors(v, null)) {
                            if (joining.contains(dd)) continue;
                            main = discharge(level, dd);
                            break;
                        }
                        double lateral = qv - main - joined;
                        if (lateral < -1e-9 * Math.max(1, qv)) {
                            clipped++;
                            clippedQ += -lateral;
                        }
                    }
                }
                // Every joining way ends on water: drawn before it in this cell, or a lake's or the sea's.
                List<int[]> ways = new ArrayList<>();
                for (int a = 0; a < d.pts.length; ) {
                    int b = a + 1;
                    if (d.pts[a].kind() != RiverPieces.LAKE) {
                        while (b < d.pts.length && d.pts[b].node() == d.pts[a].node() && d.pts[b].kind() == d.pts[a].kind()) b++;
                    }
                    ways.add(new int[]{a, b});
                    a = b;
                }
                for (int w = 1; w < ways.size(); w++) {
                    RiverNetwork.Point end = d.pts[ways.get(w)[1] - 1];
                    if (end.kind() != RiverPieces.JOIN) continue;
                    double best = Double.MAX_VALUE;
                    for (int a = 0; a < w; a++) {
                        for (int k = ways.get(a)[0]; k < ways.get(a)[1]; k++) {
                            if (d.pts[k].kind() == RiverPieces.LAKE) continue;
                            best = Math.min(best, toSegment(d.pts[k], end.ex(), end.ez()));
                        }
                    }
                    if (best > JOINS) loose++;
                }
                // A cave gives back what went into it.
                double in = 0, out = -1;
                for (Source s : d.sources) {
                    if (s.linkId() == 0) continue;
                    if (s.kind() == Kind.SINK) in += s.discharge();
                    if (s.kind() == Kind.RESURGENCE) out = s.discharge();
                }
                if (out >= 0) {
                    links++;
                    if (Math.abs(out - in) > 1e-6 * Math.max(1, in)) badLinks++;
                }
            }
        }
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "river flow round %d %d (%d): columns %d with a channel, %d without, %d not ready, %d with a NaN; lakes %d, capped %d, heads %d, sunk %d",
                x0, z0, half, present, absent, notReady, nan, lakes, capped, heads, sunk));
        if (!qs.isEmpty()) {
            sb.append(String.format(java.util.Locale.ROOT, "; discharge m3/s min %.3f, median %.3f, 90%% %.2f, max %.1f",
                    qs.get(0), qs.get(qs.size() / 2), qs.get((int) (qs.size() * 0.9)), qs.get(qs.size() - 1)));
        }
        sb.append(String.format(java.util.Locale.ROOT,
                "; nodes %d, rivers %d: channel end off its discharge %d (worst %.2e)%s, lateral clipped %d (%.3f m3/s), joins ending on no water %d, caves %d (%d not giving back what went in), closed ponds %d",
                nodes, rivers, mismatched, worst, firstOff, clipped, clippedQ, loose, links, badLinks, closed));
        for (var e : byKind.entrySet()) {
            sb.append(String.format(java.util.Locale.ROOT, "; %s %d (%.2f m3/s)", e.getKey().name().toLowerCase(java.util.Locale.ROOT),
                    (int) e.getValue()[0], e.getValue()[1]));
        }
        sb.append(String.format(java.util.Locale.ROOT, "; %.1f s", (System.nanoTime() - t0) / 1e9));
        return sb.toString();
    }

    /** What comes in or leaves in a chunk, worked out on a background thread for the server thread. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, List<Source>> READY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final LongOpenHashSet WORKING = new LongOpenHashSet();

    /**
     * A chunk's sources, or null where the asking server thread is not to wait for them: they are worked out in the
     * background, and the next asking has them.
     */
    public static List<Source> sources(ServerLevel level, int cx, int cz) {
        if (!RiverNetwork.ready()) return List.of();
        if (!RiverNetwork.serverThread()) return sourcesIn(level, cx, cz);
        long k = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        List<Source> done = READY.get(k);
        if (done != null) return done;
        synchronized (WORKING) {
            if (!WORKING.add(k)) return null;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                List<Source> made = sourcesIn(level, cx, cz);
                if (READY.size() > 4096) READY.clear();
                READY.put(k, made);
            } finally {
                synchronized (WORKING) {
                    WORKING.remove(k);
                }
            }
        }, net.minecraft.Util.backgroundExecutor());
        return null;
    }
}
