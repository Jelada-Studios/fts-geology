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
 * Retroactively injects geyser systems into chunks — including pre-existing chunks in a
 * world created before the mod was installed.
 *
 * <h2>Tagging</h2>
 * Each processed chunk is stamped in its saved NBT with {@code geyser_system_generated = true}
 * (via {@link ChunkDataEvent.Save}). On load ({@link ChunkDataEvent.Load}) we read the stamp
 * into {@link #PROCESSED}. When a chunk is fully loaded on the server
 * ({@link ChunkEvent.Load}) and is <em>not</em> stamped, we scan and (maybe) build a system,
 * then mark it processed so the next save persists the stamp.
 *
 * <h2>Safety invariants</h2>
 * <ul>
 *   <li>Never touches any block at or above {@link GeyserConfig#RETROGEN_MAX_Y} (default -30).</li>
 *   <li>Only carves through naturally occurring deep rock — player blocks abort the column
 *       (see {@link EruptionHandler#isPlayerPlaced}).</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class RetrogenHandler {

    private RetrogenHandler() {}

    public static final String TAG_KEY = "geyser_system_generated";

    /**
     * Version of the DEEP geology algorithm, stamped separately from the surface pass.
     *
     * <h2>Why a version and not another boolean</h2>
     * The surface stamp is permanent on purpose: a chunk must never get a second geyser or a second
     * volcano just because the mod was updated. But the deep boundary structure - the slab, the
     * metamorphic root, the shear zone, the rift dykes - is invisible from the surface, replaces
     * nothing a player made, and lives entirely below {@code retrogenMaxY}. When its algorithm is
     * fixed there is no reason for a world to keep the broken version forever.
     *
     * <p>That is exactly what was happening: every chunk visited in an earlier session carried the
     * permanent stamp, so {@link DeepStructure} never ran there again and successive rounds of fixes
     * to it were invisible in the one world being used to test them. Bumping this constant makes
     * already-visited chunks regenerate their deep geology, and only their deep geology, in the
     * background as you travel.</p>
     */
    public static final int DEEP_VERSION = 2;

    public static final String DEEP_TAG = "fts_deep_version";

    /**
     * How many blocks above the original ground surface the vent is allowed to reach — i.e. the
     * maximum height of the raised calcite chimney at the surface. Small, so a surfaced geyser gets
     * a tidy 2–3 block cone/chimney, not a tower into the sky.
     */
    static final int SURFACE_CHIMNEY_HEIGHT = 2;

    /**
     * Dimension-qualified chunk keys already known to be processed (loaded stamp or freshly
     * done). Keying includes the dimension so identical chunk coordinates in different
     * dimensions (e.g. Overworld vs Nether (0,0)) never collide.
     */
    static final Set<String> PROCESSED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Chunks whose deep geology is already at {@link #DEEP_VERSION}. */
    static final Set<String> DEEP_CURRENT = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
    }

    // === Retrogen trigger ===================================================

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!GeyserConfig.RETROGEN_ENABLED.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        // Any earthquake deformation that was waiting on this chunk lands now. Chunk-local and
        // cheap, so it is safe to do inline.
        com.jeladastudios.ftsgeology.quake.PendingEdits.onChunkLoaded(level, chunk.getPos());
        com.jeladastudios.ftsgeology.quake.Weathering.onChunkLoaded(level, chunk.getPos());
        com.jeladastudios.ftsgeology.quake.CaveCollapse.onChunkLoaded(level, chunk.getPos());

        String key = keyOf(level, chunk);
        boolean surfaceDone = PROCESSED.contains(key);
        boolean deepDone = DEEP_CURRENT.contains(key);
        if (surfaceDone && deepDone) return;   // fully up to date

        // Queue it rather than running it now. Generating a feature writes blocks well outside its
        // own chunk - a volcano field reaches over a hundred blocks - and doing that from inside a
        // chunk-load event forces neighbouring chunks to load, which fires more load events, which
        // generate more features. During world creation that cascade never settles and the world
        // never finishes generating. Draining a queue on the server tick instead means nothing runs
        // until the world is actually up, and the work is bounded per tick.
        //
        // A chunk that already has its surface features but stale deep geology is queued DEEP ONLY,
        // so re-running the fixed boundary structure can never duplicate a geyser or a volcano.
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

    /**
     * Generates queued chunks a few at a time. Server tick events do not fire while the spawn area
     * is being prepared, so this naturally holds everything back until world creation has finished.
     */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (event.getServer() == null) return;

        // One budget for the whole mod, opened by whichever tick handler runs first. Both of these
        // shares are small on purpose: this is background construction, and it must not be able to
        // empty the tick before the earthquake handler - which a player is actually watching - gets
        // its turn. See TickBudget for what went wrong when every system kept its own deadline.
        TickBudget.open(event.getServer().getTickCount());

        // Volcanoes under construction get their slice first. They are the heaviest thing the mod
        // builds, so they are emitted as steps and drained against a wall-clock deadline rather than
        // raised in one tick - a large shield covers thousands of columns.
        com.jeladastudios.ftsgeology.volcano.VolcanoJob.drain(event.getServer(),
                TickBudget.slice(0.3));

        // A volcano under construction slows chunk geology down rather than stopping it. Blocking
        // outright looked tidier but would let the chunk queue grow without bound while exploring a
        // hotspot, where volcanoes are common enough to arrive faster than they finish.
        int budget = GeyserConfig.RETROGEN_CHUNKS_PER_TICK.get();
        if (com.jeladastudios.ftsgeology.volcano.VolcanoJob.busy()) budget = Math.max(1, budget / 2);

        if (!QUEUE.isEmpty()) {
            // A wall-clock brake as well as a chunk count, so the count above is a permission rather
            // than a promise: whichever runs out first stops the tick.
            long deadline = System.nanoTime() + TickBudget.slice(0.3);
            // Chunks inside a quiet zone, held aside until the loop is over.
            //
            // They used to go straight back into the queue, which made the tick spin: pollNearest
            // picks the chunk closest to a player, a player standing near a quake is closest to the
            // quiet chunks, so the very next iteration pulled the same one back out. One chunk could
            // absorb the entire per-tick budget, every tick, and starve retrogen for the whole
            // server. Held aside, each is looked at once.
            List<QueuedChunk> deferred = new ArrayList<>();
            for (int i = 0; i < budget && System.nanoTime() < deadline; i++) {
                QueuedChunk q = pollNearest(event.getServer());
                if (q == null) break;
                ServerLevel level = event.getServer().getLevel(q.dimension());
                if (level == null) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(q.pos().x, q.pos().z);
                if (chunk == null) continue;

                // Ground a quake is still working on, or still shedding debris into. Building a
                // volcano or a spring field into it wastes the feature - this chunk gets exactly
                // one geology pass ever, and spending it on land that is about to move is spending
                // it on wreckage. Put it back and take it again when the ground is still.
                if (com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(
                        level, q.pos().getMiddleBlockX(), q.pos().getMiddleBlockZ())) {
                    deferred.add(q);
                    continue;
                }

                String key = keyOf(level, chunk);
                if (DEEP_CURRENT.contains(key) && (q.deepOnly() || PROCESSED.contains(key))) continue;
                boolean finished = true;
                try {
                    // Deep geology first, whether or not the surface is still to come: the boundary
                    // structure is what the surface features then sit on top of. A chunk generated
                    // with the mod installed already got it from GeologyFeature and skips this.
                    if (!DEEP_CURRENT.contains(key)) {
                        DeepStructure.Report deep = q.deep() != null ? q.deep() : new DeepStructure.Report();
                        long started = System.nanoTime();
                        int next = DeepStructure.generate(level, q.pos(), deep, q.column(), deadline);
                        if (next < DeepStructure.DONE) {
                            longestStepNanos = Math.max(longestStepNanos, System.nanoTime() - started);
                            // Out of time part way through. A chunk used to be finished regardless, and
                            // with another mod taxing every block change one chunk alone overran the
                            // whole slice (GitHub #1). Held with its place kept, resumed next tick.
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
                        blocksSinceReport += generateInChunk(level, chunk);
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

        // Say out loud whether the queue is keeping up. Without this there was no way to tell a
        // structure that had not generated from one that had and was simply hard to find, which is
        // exactly the question testing kept running into.
        if (++reportTimer >= 200) {
            reportTimer = 0;
            int generated = GENERATED.getAndSet(0);
            if (doneSinceReport > 0 || generated > 0 || !QUEUE.isEmpty()) {
                // The longest step is the number that matters with a mod hooking every block change:
                // it has to stay inside retrogen's slice now that a chunk can stop part way through.
                GeysersMod.LOGGER.info("retrogen: {} chunks in the last 10s, {} blocks placed, {} still queued, "
                                + "longest step {} ms; {} chunks got their deep geology at generation ({} blocks, {} of them ore)",
                        doneSinceReport, blocksSinceReport, QUEUE.size(),
                        String.format(java.util.Locale.ROOT, "%.2f", longestStepNanos / 1e6), generated,
                        GENERATED_BLOCKS.getAndSet(0) + GENERATED_ORE.get(), GENERATED_ORE.getAndSet(0));
            }
            doneSinceReport = 0;
            blocksSinceReport = 0;
            longestStepNanos = 0;
        }
    }

    /**
     * Takes the queued chunk nearest a player, dropping any that are no longer loaded.
     *
     * <p>A plain FIFO was the reason geology so often seemed missing. At sixteen chunks a tick the
     * throughput is fine, but the order was not: after world creation the queue already held the
     * whole spawn area, and teleporting somewhere new put those chunks <em>behind</em> it. By the
     * time their turn came the player had moved on, the chunk had unloaded, and it was skipped -
     * only to be queued again behind an even longer backlog next visit. Working outward from
     * wherever somebody actually is fixes that completely.</p>
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
            // `best == null` first, and it is not a tidiness detail - it is the whole reason this
            // worked in single player and did nothing at all on a dedicated server.
            //
            // With nobody online, distanceToNearestPlayer has no player to measure to and returns
            // Double.MAX_VALUE for every chunk. `d < bestDist` is then MAX_VALUE < MAX_VALUE, which
            // is false, so no candidate was ever chosen, everything went straight back on the queue
            // and this returned null forever. A server that generated its spawn area before anyone
            // joined sat there with 529 chunks queued and placed nothing, for as long as it ran.
            //
            // Taking the first valid candidate unconditionally degrades to plain queue order when
            // there is no player to sort by - which is the right behaviour anyway. An idle server
            // has nothing better to do than get its geology in before the first player arrives.
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
}
