package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.util.TickBudget;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static com.jeladastudios.ftsgeology.worldgen.SurfaceFeatures.*;
import static com.jeladastudios.ftsgeology.worldgen.HotSpringSites.*;

/**
 * Queues geology for chunks as they load, including chunks from before the mod was installed, and
 * works through the queue on the server tick.
 *
 * <p>Each processed chunk is stamped in its saved NBT and read back into {@link #PROCESSED} on load,
 * so a chunk gets its surface features once. Deep work stays below
 * {@link GeyserConfig#RETROGEN_MAX_Y} and never replaces player blocks.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class RetrogenHandler {

    private RetrogenHandler() {}

    public static final String TAG_KEY = "geyser_system_generated";

    /**
     * Version of the deep geology algorithm, stamped apart from the permanent surface stamp. Bumping
     * it regenerates deep structure in visited chunks, and only that, never a second geyser or volcano.
     */
    public static final int DEEP_VERSION = 2;

    public static final String DEEP_TAG = "fts_deep_version";

    /** How far above the original ground a vent may reach: the height of its calcite chimney. */
    static final int SURFACE_CHIMNEY_HEIGHT = 2;

    /**
     * Dimension-qualified keys of chunks already processed, so equal coordinates in two dimensions
     * never collide.
     */
    static final Set<String> PROCESSED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Chunks whose deep geology is already at {@link #DEEP_VERSION}. */
    static final Set<String> DEEP_CURRENT = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Chunks whose ground was painted while they generated; see {@link GeologySurfaceFeature}. */
    static final Set<String> PAINT_CURRENT = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static final String PAINT_TAG = "fts_surface_painted";

    // === NBT stamp read/write ==============================================

    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        String key = keyOf(event.getLevel(), event.getChunk());
        if (PROCESSED.contains(key)) {
            event.getData().putBoolean(TAG_KEY, true);
        }
        if (DEEP_CURRENT.contains(key)) {
            event.getData().putInt(DEEP_TAG, DEEP_VERSION);
        }
        if (PAINT_CURRENT.contains(key)) {
            event.getData().putBoolean(PAINT_TAG, true);
        }
    }

    @SubscribeEvent
    public static void onChunkDataLoad(ChunkDataEvent.Load event) {
        String key = keyOf(event.getLevel(), event.getChunk());
        if (event.getData().getBoolean(TAG_KEY)) {
            PROCESSED.add(key);
        }
        if (event.getData().getInt(DEEP_TAG) >= DEEP_VERSION) {
            DEEP_CURRENT.add(key);
        }
        if (event.getData().getBoolean(PAINT_TAG)) {
            PAINT_CURRENT.add(key);
        }
    }

    // === Retrogen trigger ===================================================

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        // Any earthquake deformation that was waiting on this chunk lands now. Chunk-local and
        // cheap, so it is safe to do inline. Before the retrogen gate: a quake's far ends are owed
        // whether or not old chunks get geology.
        com.jeladastudios.ftsgeology.quake.PendingEdits.onChunkLoaded(level, chunk.getPos());
        com.jeladastudios.ftsgeology.quake.Weathering.onChunkLoaded(level, chunk.getPos());
        com.jeladastudios.ftsgeology.quake.CaveCollapse.onChunkLoaded(level, chunk.getPos());
        if (!GeyserConfig.RETROGEN_ENABLED.get()) return;

        String key = keyOf(level, chunk);
        boolean surfaceDone = PROCESSED.contains(key);
        boolean deepDone = DEEP_CURRENT.contains(key);
        if (surfaceDone && deepDone) return;   // fully up to date

        // Queued, not run now: features write outside their chunk, and doing that inside a load event
        // loads neighbours and cascades during world creation. A chunk with its surface features but
        // stale deep geology is queued deep-only.
        QUEUE.add(new QueuedChunk(level.dimension(), chunk.getPos(), surfaceDone));
    }

    /** How far a volcano writes from its centre - its field of hot springs reaches furthest. */
    static final int VOLCANO_REACH = 48;

    /**
     * One chunk waiting for its geology, held until the world is running and calm.
     *
     * <p>{@code column} and {@code deep} carry a deep pass that ran out of time part way through the
     * chunk, so it picks up where it stopped instead of starting again.</p>
     */
    record QueuedChunk(ResourceKey<Level> dimension, ChunkPos pos, boolean deepOnly,
                               int column, DeepStructure.Report deep) {
        QueuedChunk(ResourceKey<Level> dimension, ChunkPos pos, boolean deepOnly) {
            this(dimension, pos, deepOnly, 0, null);
        }
    }

    static final java.util.concurrent.ConcurrentLinkedQueue<QueuedChunk> QUEUE =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** How many queued chunks are inspected before picking the one to work on. */
    static final int CANDIDATES = 32;

    /** Rolling counters so the log can say whether the queue is keeping up. */
    static int doneSinceReport;
    static int blocksSinceReport;
    static int reportTimer;
    /** Longest single deep pass since the last report: what one chunk actually cost the tick. */
    static long longestStepNanos;
    /** Chunks that got their deep geology at generation. Bumped from world generation threads. */
    static final java.util.concurrent.atomic.AtomicInteger GENERATED =
            new java.util.concurrent.atomic.AtomicInteger();
    /** And the blocks those chunks were given, so a world that never reaches a boundary shows as 0. */
    static final java.util.concurrent.atomic.AtomicLong GENERATED_BLOCKS =
            new java.util.concurrent.atomic.AtomicLong();
    /** How many of those were ore. */
    static final java.util.concurrent.atomic.AtomicLong GENERATED_ORE =
            new java.util.concurrent.atomic.AtomicLong();
    /** Chunks whose ground was painted at generation. */
    static final java.util.concurrent.atomic.AtomicInteger PAINTED =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Nanoseconds in the whole surface pass since the last report, to set the parts against. */
    static long surfaceNanos;

    /**
     * Chunks around a queued chunk that must be loaded before its springs and geysers are built: a
     * stage 4 pool, its bands, halo and runoff reach about 56 blocks from a centre inside the chunk.
     */
    static final int SURFACE_REACH = 4;

    /** True when every chunk in the box is loaded. Corners first, since they are the likeliest missing. */
    static boolean areaLoaded(ServerLevel level, int minCx, int minCz, int maxCx, int maxCz) {
        if (!level.hasChunk(minCx, minCz) || !level.hasChunk(maxCx, maxCz)
                || !level.hasChunk(minCx, maxCz) || !level.hasChunk(maxCx, minCz)) return false;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Generates queued chunks a few at a time. Server tick events do not fire while the spawn area
     * is being prepared, so this naturally holds everything back until world creation has finished.
     */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (event.getServer() == null) return;

        // One budget for the whole mod; these shares are small so the earthquake handler still gets
        // its turn. See TickBudget.
        TickBudget.open(event.getServer().getTickCount());

        // Volcanoes under construction first, as steps against a wall-clock deadline.
        com.jeladastudios.ftsgeology.volcano.VolcanoJob.drain(event.getServer(),
                TickBudget.slice(0.2));

        // A volcano under construction slows chunk geology rather than stopping it, so the queue
        // cannot grow without bound.
        int budget = GeyserConfig.RETROGEN_CHUNKS_PER_TICK.get();
        if (com.jeladastudios.ftsgeology.volcano.VolcanoJob.busy()) budget = Math.max(1, budget / 2);

        if (!QUEUE.isEmpty()) {
            // A wall-clock brake as well as a chunk count, so the count above is a permission rather
            // than a promise: whichever runs out first stops the tick.
            long deadline = System.nanoTime() + TickBudget.slice(0.3);
            // Chunks in a quiet zone, held aside until the loop ends so the same one is not pulled
            // again this tick.
            List<QueuedChunk> deferred = new ArrayList<>();
            for (int i = 0; i < budget && System.nanoTime() < deadline; i++) {
                QueuedChunk q = pollNearest(event.getServer());
                if (q == null) break;
                ServerLevel level = event.getServer().getLevel(q.dimension());
                if (level == null) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(q.pos().x, q.pos().z);
                if (chunk == null) continue;

                // Ground a quake is still moving: held until it is still, since a chunk gets one
                // surface pass ever.
                if (com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(
                        level, q.pos().getMiddleBlockX(), q.pos().getMiddleBlockZ())) {
                    deferred.add(q);
                    continue;
                }

                String key = keyOf(level, chunk);
                if (DEEP_CURRENT.contains(key) && (q.deepOnly() || PROCESSED.contains(key))) continue;
                boolean finished = true;
                try {
                    // Deep geology first; a chunk generated with the mod already has it from GeologyFeature.
                    if (!DEEP_CURRENT.contains(key)) {
                        DeepStructure.Report deep = q.deep() != null ? q.deep() : new DeepStructure.Report();
                        long started = System.nanoTime();
                        int next = DeepStructure.generate(level, q.pos(), deep, q.column(), deadline);
                        if (next < DeepStructure.DONE) {
                            longestStepNanos = Math.max(longestStepNanos, System.nanoTime() - started);
                            // Out of time part way through: held with its place kept, resumed next tick.
                            deferred.add(new QueuedChunk(q.dimension(), q.pos(), q.deepOnly(), next, deep));
                            finished = false;
                            continue;
                        }
                        RandomSource rng = RandomSource.create(
                                level.getSeed() ^ (((long) q.pos().x) << 32 | (q.pos().z & 0xFFFFFFFFL)));
                        OceanicRidge.generate(level, q.pos(), rng);
                        int ore = OreGenesis.generate(level, q.pos());
                        // Timed together with the last slice of the deep pass, because that is when it runs.
                        longestStepNanos = Math.max(longestStepNanos, System.nanoTime() - started);
                        blocksSinceReport += deep.blocks + ore;
                    }
                    // A retrofit gets the deep pass only: nothing that could put a second geyser or
                    // volcano next to one that is already there.
                    if (!q.deepOnly() && !PROCESSED.contains(key)) {
                        // Springs and geysers read and write past the chunk, which would load an unloaded
                        // neighbour on this thread. Held until the ground around it is in.
                        ChunkPos cp = q.pos();
                        if (!areaLoaded(level, cp.x - SURFACE_REACH, cp.z - SURFACE_REACH,
                                cp.x + SURFACE_REACH, cp.z + SURFACE_REACH)) {
                            DEEP_CURRENT.add(key);
                            deferred.add(new QueuedChunk(q.dimension(), cp, false));
                            finished = false;
                            continue;
                        }
                        long started = System.nanoTime();
                        blocksSinceReport += generateInChunk(level, chunk);
                        surfaceNanos += System.nanoTime() - started;
                    }
                    doneSinceReport++;
                } catch (Exception e) {
                    GeysersMod.LOGGER.warn("Geology retrogen failed for chunk {}: {}", q.pos(), e.toString());
                } finally {
                    if (finished) {
                        if (!q.deepOnly()) PROCESSED.add(key);
                        DEEP_CURRENT.add(key);
                    }
                    chunk.setUnsaved(true);
                }
            }
            QUEUE.addAll(deferred);     // back in the queue, but not before this tick is done
        }

        // Logged, so a structure that did not generate can be told from one that is hard to find.
        if (++reportTimer >= 200) {
            reportTimer = 0;
            int generated = GENERATED.getAndSet(0);
            if (doneSinceReport > 0 || generated > 0 || !QUEUE.isEmpty()) {
                // The longest step is the number that matters with a mod hooking every block change:
                // it has to stay inside retrogen's slice now that a chunk can stop part way through.
                GeysersMod.LOGGER.info("retrogen: {} chunks in the last 10s, {} blocks placed, {} still queued, "
                                + "longest step {} ms; {} chunks got their deep geology at generation ({} blocks, {} of them ore), "
                                + "{} their ground paint",
                        doneSinceReport, blocksSinceReport, QUEUE.size(), ms(longestStepNanos), generated,
                        GENERATED_BLOCKS.getAndSet(0) + GENERATED_ORE.get(), GENERATED_ORE.getAndSet(0),
                        PAINTED.getAndSet(0));
            }
            if (surfaceNanos > 0) {
                long[] p = SurfaceFeatures.PART_NANOS;
                GeysersMod.LOGGER.info("retrogen surface pass, ms: suitability {}, signs {}, basin {}, soil {}, "
                                + "springs {}, volcanoes {}, geysers {}; springs expected {}",
                        ms(p[0]), ms(p[1]), ms(p[2]), ms(p[3]), ms(p[4]), ms(p[5]),
                        ms(surfaceNanos - p[0] - p[1] - p[2] - p[3] - p[4] - p[5]),
                        String.format(java.util.Locale.ROOT, "%.2f", SurfaceFeatures.EXPECTED_SPRINGS.sumThenReset()));
                java.util.Arrays.fill(p, 0L);
                surfaceNanos = 0;
            }
            doneSinceReport = 0;
            blocksSinceReport = 0;
            longestStepNanos = 0;
        }
    }

    /**
     * Takes the queued chunk nearest a player, dropping any that are no longer loaded. Nearest first,
     * so a player who moves on is not left waiting behind an old backlog.
     */
    static QueuedChunk pollNearest(net.minecraft.server.MinecraftServer server) {
        QueuedChunk best = null;
        double bestDist = Double.MAX_VALUE;
        java.util.List<QueuedChunk> parked = new ArrayList<>(CANDIDATES);

        for (int i = 0; i < CANDIDATES; i++) {
            QueuedChunk q = QUEUE.poll();
            if (q == null) break;
            ServerLevel level = server.getLevel(q.dimension());
            // Not loaded any more: drop it. It queues itself again the moment it comes back, and
            // cycling it forever is what let the backlog grow without bound.
            if (level == null || level.getChunkSource().getChunkNow(q.pos().x, q.pos().z) == null) continue;

            double d = distanceToNearestPlayer(level, q.pos());
            // The first valid candidate is taken unconditionally: with nobody online every distance
            // is MAX_VALUE, and a strict comparison would never pick one.
            if (best == null || d < bestDist) {
                if (best != null) parked.add(best);
                bestDist = d;
                best = q;
            } else {
                parked.add(q);
            }
        }
        QUEUE.addAll(parked);
        return best;
    }

    static double distanceToNearestPlayer(ServerLevel level, ChunkPos cp) {
        double bx = cp.getMinBlockX() + 8, bz = cp.getMinBlockZ() + 8;
        double best = Double.MAX_VALUE;
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            double dx = p.getX() - bx, dz = p.getZ() - bz;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    /**
     * Builds a dimension-qualified key. {@code getLevel()} on chunk events is
     * {@link org.jetbrains.annotations.Nullable}; when present it is a {@link Level} whose
     * dimension we fold in. A null level falls back to an "unknown" prefix — acceptable
     * because the authoritative Save event always carries the real level.
     */
    static String keyOf(LevelAccessor levelAccess, ChunkAccess chunk) {
        String dim = (levelAccess instanceof Level lvl)
                ? lvl.dimension().location().toString()
                : "unknown";
        return dim + "@" + chunk.getPos().toLong();
    }

    /**
     * Records that a chunk got its deep geology while it was being generated, so retrogen never goes
     * over it a second time. Called from world generation threads, which the sets are safe for.
     */
    public static void markDeepCurrent(ResourceKey<Level> dimension, ChunkPos pos, int blocks, int ore) {
        DEEP_CURRENT.add(dimension.location() + "@" + pos.toLong());
        GENERATED.incrementAndGet();
        GENERATED_BLOCKS.addAndGet(blocks);
        GENERATED_ORE.addAndGet(ore);
    }

    /** Records that a chunk's ground was painted while it generated, so retrogen leaves the painting out. */
    public static void markPaintCurrent(ResourceKey<Level> dimension, ChunkPos pos) {
        PAINT_CURRENT.add(dimension.location() + "@" + pos.toLong());
        PAINTED.incrementAndGet();
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1e6);
    }
}
