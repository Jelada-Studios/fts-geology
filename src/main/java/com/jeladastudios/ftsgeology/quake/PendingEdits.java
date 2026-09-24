package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps a rupture alive across chunks nobody has loaded yet. The rupture itself (type, size, path) is
 * stored rather than its edits, since planning has to read terrain; it is replanned when a chunk it
 * crosses loads. The load event only flags the chunk; planning and writes run from the tick loop.
 */
public final class PendingEdits {

    private PendingEdits() {}

    /** One earthquake still waiting to express itself on chunks that were not loaded at the time. */
    private record PendingRupture(FaultType type, double magnitude, double depthMetres,
                                  long seed, boolean mayBreakBuilds,
                                  BlockPos epicentre, List<QuakePlanner.TracePoint> trace) {}

    /** dimension + chunk -> ruptures that still have to be applied there. */
    private static final Map<String, List<PendingRupture>> WAITING = new ConcurrentHashMap<>();

    /** Chunks that have loaded and are waiting for the tick loop to replay their deformation. */
    private record ReadyChunk(ResourceKey<Level> dimension, ChunkPos pos) {}

    private static final ConcurrentLinkedQueue<ReadyChunk> READY = new ConcurrentLinkedQueue<>();

    private static String key(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return dim.location() + "@" + chunkX + ":" + chunkZ;
    }

    /**
     * Registers a rupture against every chunk its corridor crosses that is not loaded right now.
     * Loaded chunks are handled immediately by {@link Earthquake} and are not queued.
     */
    public static void register(ServerLevel level, BlockPos epicentre, FaultType type,
                                double magnitude, double depthMetres, long seed,
                                boolean mayBreakBuilds, List<QuakePlanner.TracePoint> trace) {
        int limit = GeyserConfig.QUAKE_PENDING_LIMIT.get();
        if (limit <= 0) return;

        Map<Long, List<QuakePlanner.TracePoint>> byChunk = segmentsByChunk(type, magnitude, trace);
        int registered = 0;
        for (Map.Entry<Long, List<QuakePlanner.TracePoint>> e : byChunk.entrySet()) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            if (level.getChunkSource().getChunkNow(cx, cz) != null) continue;   // handled already
            if (WAITING.size() >= limit) break;
            WAITING.computeIfAbsent(key(level.dimension(), cx, cz), k -> new ArrayList<>())
                    .add(new PendingRupture(type, magnitude, depthMetres, seed, mayBreakBuilds,
                            epicentre, e.getValue()));
            registered++;
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info(
                "quake register: {} chunks parked ({} corridor chunks total)", registered, byChunk.size());
    }

    /**
     * The trace points that reach each chunk of the corridor, by chunk. The planner's full corridor,
     * not the slipped core, or the rupture would stop at the loaded edge.
     */
    public static Map<Long, List<QuakePlanner.TracePoint>> segmentsByChunk(FaultType type, double magnitude,
                                                                          List<QuakePlanner.TracePoint> trace) {
        int band = QuakePlanner.deformationHalfWidth(type, magnitude) + 2;
        Map<Long, List<QuakePlanner.TracePoint>> byChunk = new HashMap<>();
        for (int i = 0; i < trace.size(); i++) {
            QuakePlanner.TracePoint tp = trace.get(i);
            double nx = -tp.strikeZ(), nz = tp.strikeX();
            Set<Long> touched = new HashSet<>();
            for (int w = -band; w <= band; w += 4) {
                int cx = (int) Math.round(tp.x() + nx * w) >> 4;
                int cz = (int) Math.round(tp.z() + nz * w) >> 4;
                touched.add(ChunkPos.asLong(cx, cz));
            }
            for (long c : touched) {
                List<QuakePlanner.TracePoint> seg = byChunk.computeIfAbsent(c, k -> new ArrayList<>());
                seg.add(tp);
                if (i + 1 < trace.size()) seg.add(trace.get(i + 1));
            }
        }
        for (Map.Entry<Long, List<QuakePlanner.TracePoint>> e : byChunk.entrySet()) {
            e.setValue(List.copyOf(e.getValue()));
        }
        return byChunk;
    }

    /**
     * Parks one chunk of a rupture that was being applied when the chunk went away, so its edits are
     * replayed when it comes back instead of being lost. Once per chunk and rupture.
     */
    public static void park(ServerLevel level, int cx, int cz, BlockPos epicentre, FaultType type,
                            double magnitude, double depthMetres, long seed, boolean mayBreakBuilds,
                            List<QuakePlanner.TracePoint> segment) {
        int limit = GeyserConfig.QUAKE_PENDING_LIMIT.get();
        if (limit <= 0 || segment == null || segment.isEmpty()) return;
        List<PendingRupture> here = WAITING.computeIfAbsent(key(level.dimension(), cx, cz), k -> new ArrayList<>());
        for (PendingRupture r : here) {
            if (r.seed() == seed) return;
        }
        if (WAITING.size() > limit) return;
        here.add(new PendingRupture(type, magnitude, depthMetres, seed, mayBreakBuilds, epicentre, segment));
    }

    /**
     * Notes that a chunk carrying parked deformation has loaded. Deliberately does NOT touch the
     * world - see the class note on why writing blocks from the chunk-load event is unsafe.
     */
    public static void onChunkLoaded(ServerLevel level, ChunkPos cp) {
        if (!WAITING.containsKey(key(level.dimension(), cp.x, cp.z))) return;
        READY.add(new ReadyChunk(level.dimension(), cp));
    }

    /**
     * Replays parked ruptures for chunks that have loaded, on the server thread and inside a time
     * budget. However many chunks arrive at once, the tick never blows out: whatever is left simply
     * waits for the next one.
     */
    public static void drain(MinecraftServer server, long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        while (System.nanoTime() < deadline) {
            ReadyChunk rc = READY.poll();
            if (rc == null) return;
            ServerLevel level = server.getLevel(rc.dimension());
            if (level == null) continue;
            ChunkPos cp = rc.pos();
            // Never force a load: if it went away again, its deformation waits for the next visit.
            if (level.getChunkSource().getChunkNow(cp.x, cp.z) == null) continue;
            applyFor(level, cp);
        }
    }

    /**
     * Plans and applies the parked ruptures for one loaded chunk. The snapshot is taken now, so it
     * sees real terrain; only edits landing inside this chunk are kept, and neighbouring chunks get
     * theirs when they load in turn.
     */
    private static void applyFor(ServerLevel level, ChunkPos cp) {
        List<PendingRupture> ruptures = WAITING.remove(key(level.dimension(), cp.x, cp.z));
        if (ruptures == null || ruptures.isEmpty()) return;

        // Everything written here is weathered afterwards, exactly as a live rupture's corridor is:
        // a replayed chunk left as written keeps its trees and vines standing over the cut.
        List<QuakePlanner.Edit> applied = new ArrayList<>();
        for (PendingRupture r : ruptures) {
            try {
                QuakePlanner.Snapshot snap = QuakePlanner.snapshot(level, r.trace(), r.type(),
                        r.magnitude(), cp);
                QuakePlanner.Plan plan = QuakePlanner.plan(snap, r.trace(), r.epicentre(), r.type(),
                        r.magnitude(), r.depthMetres(), new Random(r.seed()), r.mayBreakBuilds(), cp);
                for (QuakePlanner.Edit e : plan.edits()) {
                    if ((e.pos().getX() >> 4) != cp.x || (e.pos().getZ() >> 4) != cp.z) continue;
                    level.setBlock(e.pos(), com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, e.pos(), e.state()), Earthquake.FLAGS);
                    applied.add(e);
                }
            } catch (Exception ex) {
                com.jeladastudios.ftsgeology.GeysersMod.LOGGER.warn(
                        "quake replay failed for chunk {}: {}", cp, ex.toString());
            }
        }
        if (!applied.isEmpty()) Weathering.enqueue(level, applied);
    }

    /** Drops everything; called when a server stops, and by the cancel command. */
    public static void clear() {
        WAITING.clear();
        READY.clear();
    }

    public static int pendingChunks() {
        return WAITING.size();
    }
}
