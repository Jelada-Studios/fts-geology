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

    /**
     * Where the network puts an event, from its stations' distances: {x, z, give or take in blocks, stations}, or null
     * with fewer than three. Each distance is a circle round its station; the source is where they cross, found by least
     * squares from the stations' middle. With four and more the depth is solved for too; with three it is taken for ten
     * kilometres.
     */
    public static synchronized double[] fix(long eventId) {
        List<Pick> picks = PICKS.get(eventId);
        if (picks == null || picks.size() < 3) return null;
        double h = DepthScale.metresPerBlockHorizontal();
        int n = picks.size();
        double[] sx = new double[n], sz = new double[n], d = new double[n];
        double x = 0, z = 0;
        for (int i = 0; i < n; i++) {
            sx[i] = picks.get(i).station().getX() * h;
            sz[i] = picks.get(i).station().getZ() * h;
            d[i] = picks.get(i).metres();
            x += sx[i] / n;
            z += sz[i] / n;
        }
        double depth = 10_000.0;
        boolean solveDepth = n >= 4;
        for (int iter = 0; iter < 30; iter++) {
            // Gauss-Newton on the misfit of each station's distance.
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
            int k = solveDepth ? 3 : 2;
            double[] step = solve(a, b, k);
            if (step == null) break;
            x += step[0];
            z += step[1];
            if (solveDepth) depth = Math.max(500.0, Math.min(100_000.0, depth + step[2]));
            if (Math.abs(step[0]) + Math.abs(step[1]) < 1.0) break;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double r = Math.sqrt((x - sx[i]) * (x - sx[i]) + (z - sz[i]) * (z - sz[i]) + depth * depth);
            sum += (r - d[i]) * (r - d[i]);
        }
        double err = Math.max(2.0, 2.0 * Math.sqrt(sum / n) / h + 60.0 / (n * n));
        return new double[]{x / h, z / h, err, n, depth};
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
