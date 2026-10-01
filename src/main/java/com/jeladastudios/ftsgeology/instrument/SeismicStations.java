package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The seismographs running, as a network. One station reads how far a quake was from the gap between its P and S
 * waves, and roughly which way from how the ground first moved; three or more, each with its own distance, fix where it
 * was, and the more of them the better. And a network that has caught a quake's P wave at two stations knows it is
 * coming before the waves reach anyone further out: the early warning (see {@code FeltShaking}).
 *
 * <p>Only stations whose chunks are loaded are listening. Session memory only, as the network's log is.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class SeismicStations {

    private SeismicStations() {}

    /** How far from the nearest station a player is covered by the network's warning, in blocks. */
    public static final double COVERS = 3000;
    /** Events whose picks are kept. */
    private static final int KEPT = 64;

    private static final Map<ResourceKey<Level>, LongOpenHashSet> RUNNING = new HashMap<>();

    /** One station's pick of an event: where it stands and the distance to the source it read, in metres. */
    private record Pick(BlockPos station, double metres) {}

    private static final Long2ObjectLinkedOpenHashMap<List<Pick>> PICKS = new Long2ObjectLinkedOpenHashMap<>();

    public static synchronized void running(Level level, BlockPos pos) {
        RUNNING.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(pos.asLong());
    }

    public static synchronized void stopped(Level level, BlockPos pos) {
        LongOpenHashSet set = RUNNING.get(level.dimension());
        if (set != null) set.remove(pos.asLong());
    }

    /** The stations listening in a dimension now. */
    public static synchronized List<BlockPos> listening(ResourceKey<Level> dimension) {
        LongOpenHashSet set = RUNNING.get(dimension);
        List<BlockPos> out = new ArrayList<>();
        if (set != null) for (long p : set) out.add(BlockPos.of(p));
        return out;
    }

    /** A station read an event at so many metres. */
    public static synchronized void pick(long eventId, BlockPos station, double metres) {
        List<Pick> picks = PICKS.getAndMoveToLast(eventId);
        if (picks == null) {
            picks = new ArrayList<>();
            PICKS.put(eventId, picks);
            while (PICKS.size() > KEPT) PICKS.removeFirst();
        }
        for (Pick p : picks) if (p.station().equals(station)) return;
        picks.add(new Pick(station.immutable(), metres));
        if (picks.size() >= 3) {
            double[] f = fix(eventId);
            if (f != null) com.jeladastudios.ftsgeology.util.Diagnostics.info("seismic network: event {} fixed by {} stations at {} {}, give or take {} blocks, {} m deep",
                    eventId, (int) f[3], Math.round(f[0]), Math.round(f[1]), Math.round(f[2]), Math.round(f[4]));
        }
    }

    /** How far, in metres, a station's distance may be off from its S-P pick alone: some 0.03 s. */
    private static final double PICK_METRES = 300.0;
    /** Starts round the stations' middle besides the middle itself, and how much each step is held back. */
    private static final int STARTS = 8;
    private static final double DAMP = 0.1;
    /** A fix is not given when it may be off by more than this share of the stations' distance to it, or this far. */
    private static final double TRUSTED = 0.25, TRUSTED_MOST = 400.0;
    /** The depth is given when it may be off by no more than this, in metres, and has not run to the shallowest. */
    private static final double DEPTH_TOLD = 5000.0, SHALLOWEST = 500.0;

    /**
     * Where the network puts an event, from its stations' distances: {x, z, give or take in blocks, stations, depth in
     * metres or -1 when they cannot tell it}, or null with fewer than three or when the stations cannot tell. Each distance is a circle round its
     * station; the source is where they cross, found by least squares. Stations standing to one side of a far quake see
     * their circles cross in two places, so it is sought from the stations' middle and from a ring round it as far out
     * as the distances say, and the best kept, its depth with it (three stations give that only roughly). How far the
     * fix may be off follows from how far each distance may be and how the stations stand round the source: a line of
     * stations fixes a quake off its end poorly.
     */
    public static synchronized double[] fix(long eventId) {
        List<Pick> picks = PICKS.get(eventId);
        if (picks == null || picks.size() < 3) return null;
        double h = DepthScale.metresPerBlockHorizontal();
        int n = picks.size();
        double[] sx = new double[n], sz = new double[n], d = new double[n];
        double cx = 0, cz = 0, reach = 0;
        for (int i = 0; i < n; i++) {
            sx[i] = picks.get(i).station().getX() * h;
            sz[i] = picks.get(i).station().getZ() * h;
            d[i] = picks.get(i).metres();
            cx += sx[i] / n;
            cz += sz[i] / n;
            reach += Math.sqrt(Math.max(0.0, d[i] * d[i] - 1.0e8)) / n;
        }
        double[] best = null;
        for (int start = 0; start <= STARTS; start++) {
            double a = 2.0 * Math.PI * start / STARTS;
            double x0 = start == 0 ? cx : cx + reach * Math.cos(a), z0 = start == 0 ? cz : cz + reach * Math.sin(a);
            double[] f = solveFrom(sx, sz, d, x0, z0);
            if (f == null || Math.hypot(f[0] - cx, f[1] - cz) > 4.0 * reach + 100_000.0) continue;
            if (best == null || f[3] < best[3]) best = f;
        }
        if (best == null) return null;
        double sigma = Math.max(PICK_METRES, Math.sqrt(best[3] / Math.max(1, n - 3)));
        double err = Math.max(2.0, 2.0 * sigma * Math.sqrt(best[4]) / h);
        double far = 0;
        for (int i = 0; i < n; i++) far += Math.hypot(best[0] - sx[i], best[1] - sz[i]) / h / n;
        if (!(err <= Math.min(TRUSTED_MOST, Math.max(100.0, TRUSTED * far)))) return null;
        // A quake far off the stations' spread is fixed on the map but not in depth.
        double depthErr = 2.0 * sigma * Math.sqrt(best[5]);
        double depth = depthErr <= DEPTH_TOLD && best[2] > SHALLOWEST + 100.0 ? best[2] : -1.0;
        return new double[]{best[0] / h, best[1] / h, err, n, depth};
    }

    /**
     * Damped Gauss-Newton on the misfit of each station's distance from one start: {x, z, depth, the misfit squared,
     * how far the fix moves for a metre off in every distance, squared, and its depth}, or null if the stations cannot tell.
     */
    private static double[] solveFrom(double[] sx, double[] sz, double[] d, double x, double z) {
        int n = d.length, k = 3;
        double depth = 10_000.0;
        for (int iter = 0; iter < 60; iter++) {
            double[][] a = new double[3][3];
            double[] b = new double[3];
            for (int i = 0; i < n; i++) {
                double ex = x - sx[i], ez = z - sz[i];
                double r = Math.max(1.0, Math.sqrt(ex * ex + ez * ez + depth * depth));
                double f = r - d[i];
                double[] j = {ex / r, ez / r, depth / r};
                for (int p = 0; p < 3; p++) {
                    b[p] -= j[p] * f;
                    for (int q = 0; q < 3; q++) a[p][q] += j[p] * j[q];
                }
            }
            for (int p = 0; p < k; p++) a[p][p] *= 1.0 + DAMP;
            double[] step = solve(a, b, k);
            if (step == null) return null;
            x += step[0];
            z += step[1];
            depth = Math.max(500.0, Math.min(100_000.0, depth + step[2]));
            if (Math.abs(step[0]) + Math.abs(step[1]) < 1.0) break;
        }
        double sum = 0;
        double[][] a = new double[3][3];
        for (int i = 0; i < n; i++) {
            double ex = x - sx[i], ez = z - sz[i];
            double r = Math.max(1.0, Math.sqrt(ex * ex + ez * ez + depth * depth));
            sum += (r - d[i]) * (r - d[i]);
            double[] j = {ex / r, ez / r, depth / r};
            for (int p = 0; p < 3; p++) for (int q = 0; q < 3; q++) a[p][q] += j[p] * j[q];
        }
        double[] ux = solve(a, new double[]{1, 0, 0}, k), uz = solve(a, new double[]{0, 1, 0}, k), ud = solve(a, new double[]{0, 0, 1}, k);
        if (ux == null || uz == null || ud == null) return null;
        return new double[]{x, z, depth, sum, Math.max(0.0, ux[0]) + Math.max(0.0, uz[1]), Math.max(0.0, ud[2])};
    }

    /** Solves the first k rows of a small symmetric system, or null if it is singular. */
    private static double[] solve(double[][] a, double[] b, int k) {
        double[][] m = new double[k][k + 1];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) m[i][j] = a[i][j];
            m[i][k] = b[i];
        }
        for (int c = 0; c < k; c++) {
            int piv = c;
            for (int r = c + 1; r < k; r++) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r;
            if (Math.abs(m[piv][c]) < 1e-9) return null;
            double[] t = m[c];
            m[c] = m[piv];
            m[piv] = t;
            for (int r = 0; r < k; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                for (int j = c; j <= k; j++) m[r][j] -= f * m[c][j];
            }
        }
        double[] out = new double[3];
        for (int i = 0; i < k; i++) out[i] = m[i][k] / m[i][i];
        return out;
    }

    /**
     * When the network has caught a quake at {@code (x, z)} leaving at {@code leaves}: a moment after its P wave has
     * reached the second station listening. Long.MAX_VALUE with fewer than two, or none near enough to hear it.
     */
    public static long detects(ResourceKey<Level> dimension, double x, double z, double depthMetres, long leaves, double hears) {
        List<BlockPos> stations = listening(dimension);
        if (stations.size() < 2) return Long.MAX_VALUE;
        long first = Long.MAX_VALUE, second = Long.MAX_VALUE;
        for (BlockPos s : stations) {
            double flat = Math.hypot(s.getX() - x, s.getZ() - z);
            if (flat > hears) continue;
            long at = leaves + Math.round(SeismicWave.hypocentralMetres(flat, depthMetres) / SeismicWave.VP * 20.0);
            if (at < first) {
                second = first;
                first = at;
            } else if (at < second) {
                second = at;
            }
        }
        return second == Long.MAX_VALUE ? Long.MAX_VALUE : second + 20;
    }

    /** Whether a place is near enough a listening station for the network's warning to reach it. */
    public static boolean covers(ResourceKey<Level> dimension, double x, double z) {
        for (BlockPos s : listening(dimension)) {
            if (Math.hypot(s.getX() - x, s.getZ() - z) <= COVERS) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (SeismicStations.class) {
            RUNNING.clear();
            PICKS.clear();
        }
    }
}
