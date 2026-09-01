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
 * Keeps a rupture alive across chunks nobody has loaded yet.
 *
 * <h2>Why the rupture is parked rather than its blocks</h2>
 * A realistic rupture runs for hundreds of blocks, far past the render distance. Planning the whole
 * thing and parking the leftover block edits cannot work, because the planner has to READ terrain to
 * decide what to do and terrain in an unloaded chunk cannot be read. So what is stored is the
 * rupture - its type, size and traced path - and it is replanned against real terrain when one of
 * the chunks it crosses finally loads.
 *
 * <h2>Why none of that happens in the chunk-load event</h2>
 * It used to, and that was a serious mistake: {@code ChunkEvent.Load} can fire off the server thread
 * during chunk I/O, and even on the right thread it catches the chunk mid-transition. Writing blocks
 * there risks corrupting the world or locking the game outright. The event now only FLAGS the chunk;
 * the planning and the block writes happen from the tick loop, on the server thread, inside a time
 * budget.
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

        // Must match the corridor the planner will actually use, not the narrow slipped core: the
        // deformation reaches tens of blocks out for a subduction margin or a collision belt, and a
        // rupture parked against too few chunks simply stops at the edge of the loaded area.
        int band = QuakePlanner.deformationHalfWidth(type, magnitude) + 2;

        // Group the trace by chunk FIRST, so each chunk only remembers the handful of trace points
        // that actually reach it. Storing the whole trace per chunk meant replaying a long rupture
        // walked every segment for every chunk that loaded.
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

        int registered = 0;
        for (Map.Entry<Long, List<QuakePlanner.TracePoint>> e : byChunk.entrySet()) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            if (level.getChunkSource().getChunkNow(cx, cz) != null) continue;   // handled already
            if (WAITING.size() >= limit) break;
            WAITING.computeIfAbsent(key(level.dimension(), cx, cz), k -> new ArrayList<>())
                    .add(new PendingRupture(type, magnitude, depthMetres, seed, mayBreakBuilds,
                            epicentre, List.copyOf(e.getValue())));
            registered++;
        }
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info(
                "quake register: {} chunks parked ({} corridor chunks total)", registered, byChunk.size());
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

        for (PendingRupture r : ruptures) {
            try {
                QuakePlanner.Snapshot snap = QuakePlanner.snapshot(level, r.trace(), r.type(),
                        r.magnitude(), cp);
                QuakePlanner.Plan plan = QuakePlanner.plan(snap, r.trace(), r.epicentre(), r.type(),
                        r.magnitude(), r.depthMetres(), new Random(r.seed()), r.mayBreakBuilds());
                for (QuakePlanner.Edit e : plan.edits()) {
                    if ((e.pos().getX() >> 4) != cp.x || (e.pos().getZ() >> 4) != cp.z) continue;
                    level.setBlock(e.pos(), e.state(), 2);
                }
            } catch (Exception ex) {
                com.jeladastudios.ftsgeology.GeysersMod.LOGGER.warn(
                        "quake replay failed for chunk {}: {}", cp, ex.toString());
            }
        }
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
