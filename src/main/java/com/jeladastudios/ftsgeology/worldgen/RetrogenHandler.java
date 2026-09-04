package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.SpringSourceBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.util.TickBudget;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import com.jeladastudios.ftsgeology.volcano.VolcanoBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int SURFACE_CHIMNEY_HEIGHT = 2;

    /**
     * Dimension-qualified chunk keys already known to be processed (loaded stamp or freshly
     * done). Keying includes the dimension so identical chunk coordinates in different
     * dimensions (e.g. Overworld vs Nether (0,0)) never collide.
     */
    private static final Set<String> PROCESSED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Chunks whose deep geology is already at {@link #DEEP_VERSION}. */
    private static final Set<String> DEEP_CURRENT = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
    private static final int VOLCANO_REACH = 48;


    /** One chunk waiting for its geology, held until the world is running and calm. */
    private record QueuedChunk(ResourceKey<Level> dimension, ChunkPos pos, boolean deepOnly) {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<QueuedChunk> QUEUE =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** How many queued chunks are inspected before picking the one to work on. */
    private static final int CANDIDATES = 32;

    /** Rolling counters so the log can say whether the queue is keeping up. */
    private static int doneSinceReport;
    private static int blocksSinceReport;
    private static int reportTimer;

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
            for (int i = 0; i < budget && System.nanoTime() < deadline; i++) {
                QueuedChunk q = pollNearest(event.getServer());
                if (q == null) break;
                ServerLevel level = event.getServer().getLevel(q.dimension());
                if (level == null) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(q.pos().x, q.pos().z);
                if (chunk == null) continue;

                String key = keyOf(level, chunk);
                try {
                    if (q.deepOnly()) {
                        // Retrofit only: the boundary structure, nothing that could put a second
                        // geyser or volcano next to one that is already there.
                        if (DEEP_CURRENT.contains(key)) continue;
                        RandomSource rng = RandomSource.create(
                                level.getSeed() ^ (((long) q.pos().x) << 32 | (q.pos().z & 0xFFFFFFFFL)));
                        DeepStructure.Report r = new DeepStructure.Report();
                        DeepStructure.generate(level, q.pos(), rng, r);
                        OceanicRidge.generate(level, q.pos(), rng);
                        blocksSinceReport += r.blocks;
                    } else {
                        if (PROCESSED.contains(key)) continue;
                        blocksSinceReport += generateInChunk(level, chunk);
                    }
                    doneSinceReport++;
                } catch (Exception e) {
                    GeysersMod.LOGGER.warn("Geology retrogen failed for chunk {}: {}", q.pos(), e.toString());
                } finally {
                    if (!q.deepOnly()) PROCESSED.add(key);
                    DEEP_CURRENT.add(key);
                    chunk.setUnsaved(true);
                }
            }
        }

        // Say out loud whether the queue is keeping up. Without this there was no way to tell a
        // structure that had not generated from one that had and was simply hard to find, which is
        // exactly the question testing kept running into.
        if (++reportTimer >= 200) {
            reportTimer = 0;
            if (doneSinceReport > 0 || !QUEUE.isEmpty()) {
                GeysersMod.LOGGER.info("retrogen: {} chunks in the last 10s, {} blocks placed, {} still queued",
                        doneSinceReport, blocksSinceReport, QUEUE.size());
            }
            doneSinceReport = 0;
            blocksSinceReport = 0;
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
    private static QueuedChunk pollNearest(net.minecraft.server.MinecraftServer server) {
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

    private static double distanceToNearestPlayer(ServerLevel level, ChunkPos cp) {
        double bx = cp.getMinBlockX() + 8, bz = cp.getMinBlockZ() + 8;
        double best = Double.MAX_VALUE;
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            double dx = p.getX() - bx, dz = p.getZ() - bz;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }
    // === Generation =========================================================

    private static int generateInChunk(ServerLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        RandomSource rng = RandomSource.create(
                level.getSeed() ^ (((long) cp.x) << 32 | (cp.z & 0xFFFFFFFFL)));

        int maxY = GeyserConfig.RETROGEN_MAX_Y.get();   // e.g. -30 (exclusive ceiling)
        int minY = GeyserConfig.RETROGEN_MIN_Y.get();   // e.g. -60
        int chamberH = GeyserConfig.CHAMBER_TARGET_HEIGHT.get();

        // Ask the tectonic model what belongs here. One sample per chunk, at its centre.
        // This is what stops geysers appearing in places real geology would never put them: a
        // continental collision zone gets hot springs but no geysers or volcanoes, a strike-slip
        // fault likewise, and plate interiors stay quiet. See GeothermalSuitability for the
        // reasoning behind each number.
        int centreX = cp.getMinBlockX() + 8, centreZ = cp.getMinBlockZ() + 8;
        GeothermalSuitability.Suitability fit = GeyserConfig.TECTONIC_PLACEMENT.get()
                ? GeothermalSuitability.at(level, centreX, centreZ)
                : new GeothermalSuitability.Suitability(1.0, 1.0, 1.0, "Tectonic placement disabled.");

        // Deep geology first: the boundary structure the surface features then sit on top of.
        DeepStructure.Report deep = new DeepStructure.Report();
        DeepStructure.generate(level, cp, rng, deep);

        // Where that boundary is under water, it is a spreading ridge rather than a rift valley.
        OceanicRidge.generate(level, cp, rng);

        // Surface features do read and write across chunk borders, which can pull a neighbour in.
        // That is fine now: this runs from the tick queue, so a forced load simply queues more work
        // for a later tick instead of recursing. Demanding a fully loaded neighbourhood instead was
        // far too strict - a freshly loaded chunk is almost always at the EDGE of the loaded area,
        // so features stopped generating altogether.

        // Independent surface feature: an occasional hot-spring pool.
        if (rng.nextDouble() < GeyserConfig.HOT_SPRING_SPAWN_CHANCE.get() * fit.hotSpring()) {
            generateHotSpring(level, cp, rng);
        }
        // Volcanoes are rare landmarks and only exist where magma is actually generated.
        //
        // The "wait until the whole neighbourhood is loaded" guard that used to sit here is gone. It
        // was added when generation ran inside the chunk-load event and a volcano writing a hundred
        // blocks out could cascade; but a chunk that has just loaded is almost always at the EDGE of
        // the loaded area, so the condition was essentially never true while exploring and natural
        // volcanoes stopped appearing altogether. Generation now runs from the tick queue and the
        // build itself is spread over ticks by VolcanoJob, so a neighbour being pulled in merely
        // queues more work for a later tick instead of recursing.
        if (fit.volcano() > 0 && rng.nextDouble() < GeyserConfig.VOLCANO_SPAWN_CHANCE.get() * fit.volcano()) {
            generateVolcano(level, cp, rng);
        }


        // One candidate column per chunk keeps density low and cost bounded.
        double chance = GeyserConfig.CHAMBER_SPAWN_CHANCE.get() * fit.geyser();
        if (chance <= 0 || rng.nextDouble() >= chance) return deep.blocks;

        int localX = rng.nextInt(12) + 2; // keep away from chunk borders (2..13)
        int localZ = rng.nextInt(12) + 2;
        int worldX = cp.getMinBlockX() + localX;
        int worldZ = cp.getMinBlockZ() + localZ;

        // Choose a core Y that leaves room for the chamber below the safety ceiling.
        int coreY = minY + 1;
        int chamberTop = coreY + chamberH; // must stay strictly below maxY
        if (chamberTop >= maxY) return deep.blocks;

        BlockPos corePos = new BlockPos(worldX, coreY, worldZ);

        if (!columnIsCarvable(level, corePos, chamberH, maxY)) return deep.blocks;

        int magnitude = pickMagnitude(rng);
        buildSystem(level, corePos, chamberH, magnitude, rng, false, true); // natural: build-safe deep shaft + branches
        GeysersMod.LOGGER.debug("Geyser system (magnitude {}) placed at {}", magnitude, corePos);
        return deep.blocks;
    }

    /**
     * Forcibly places a full geyser system with its core at {@code corePos} (deep placement).
     * Runs the same build-safety check as natural generation: returns false without touching
     * anything if the column can't host it. Kept for completeness; deliberate placements should
     * prefer {@link #forcePlaceNearSurface}, which is far more reliable.
     */
    public static boolean forcePlace(ServerLevel level, BlockPos corePos, int magnitude, RandomSource rng) {
        int maxY = GeyserConfig.RETROGEN_MAX_Y.get();
        int chamberH = GeyserConfig.CHAMBER_TARGET_HEIGHT.get();
        if (corePos.getY() + chamberH + 1 >= maxY) return false;        // must fit below the ceiling
        if (corePos.getY() <= level.getMinBuildHeight() + 1) return false; // no room beneath for the heat source
        buildSystem(level, corePos, chamberH, magnitude, rng, true, true);
        return true;
    }

    /**
     * Builds a geyser <em>just below the surface</em> at the given column — the reliable path for a
     * deliberately-placed igniter or the spawn command. The core sits only a few blocks under the
     * ground (so it's findable, glowing), its chamber holds water right there, and the vent breaks
     * the surface with a tiny 1–2 block shaft that can't fail. This deliberately ignores the deep
     * "-30 ceiling" rule (that's for hidden natural generation); when you plant an igniter you want a
     * working, visible geyser at your feet — exactly how it behaved when it worked.
     *
     * @return true if a geyser was built, false if the column had no room (e.g. bedrock too close).
     */
    public static boolean forcePlaceNearSurface(ServerLevel level, int x, int z, int magnitude, RandomSource rng) {
        int chamberH = GeyserConfig.CHAMBER_TARGET_HEIGHT.get();
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z); // air just above topmost solid
        // Bury the whole structure a few blocks down: chamber top ends ~2 below the surface, leaving
        // a 1–2 block shaft up to daylight.
        int coreY = surfaceY - chamberH - 3;
        if (coreY <= level.getMinBuildHeight() + 2) return false; // not enough room beneath for the heat bed
        BlockPos corePos = new BlockPos(x, coreY, z);
        // Aggressive short shaft (clears the last couple of natural blocks to the surface); no root
        // branches near the surface — they'd scar the ground with holes.
        buildSystem(level, corePos, chamberH, magnitude, rng, true, false);
        return true;
    }

    /**
     * Carves a small flush hot-spring pool on the surface: a shallow water basin with a calcite
     * floor and a hidden {@code HotSpring} bed, warmed by a contained lava/magma cell a couple of
     * blocks below (which also reads as warm to Tough As Nails). Aborts near builds or on unsuitable
     * ground so it never scars terrain badly.
     */
    private static void generateHotSpring(ServerLevel level, ChunkPos cp, RandomSource rng) {
        placeHotSpringAt(level, cp.getMinBlockX() + rng.nextInt(12) + 2, cp.getMinBlockZ() + rng.nextInt(12) + 2);
    }

    /**
     * Attempts a natural volcano somewhere in this chunk. Only ever called where the tectonic model
     * says magma is actually being generated - a subduction arc, a spreading rift or a hotspot -
     * so volcanoes never appear along a collision belt or a strike-slip fault, matching the real
     * world where the Himalaya and the San Andreas have none.
     *
     * <p>Reuses {@link VolcanoBuilder#build}, which already refuses sites without the vertical room
     * for a cone, so unsuitable flat ground is rejected for free.</p>
     */
    private static void generateVolcano(ServerLevel level, ChunkPos cp, RandomSource rng) {
        int x = cp.getMinBlockX() + rng.nextInt(12) + 2;
        int z = cp.getMinBlockZ() + rng.nextInt(12) + 2;
        // Real ground, not the tree canopy: WORLD_SURFACE returns the topmost non-air block, which
        // in a forest is a leaf. That is what used to leave lava pools floating above the trees.
        int summitY = TerrainProbe.groundY(level, x, z);
        if (summitY == Integer.MIN_VALUE) return;
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;   // never in a lake or the sea
        if (summitY <= level.getMinBuildHeight() + 20) return;
        BlockPos summit = new BlockPos(x, summitY, z);
        if (EruptionHandler.isPlayerPlaced(level.getBlockState(summit))) return;

        int magnitude = 8 + rng.nextInt(12);
        if (VolcanoBuilder.build(level, summit, magnitude)) {
            GeysersMod.LOGGER.debug("Natural volcano (magnitude {}) placed at {}", magnitude, summit);
        }
    }

    /**
     * Builds an irregular hot-spring pool at the surface of the given column, ringed with calcite.
     * Public so volcanoes can dot their slopes with thermal pools. Returns true if one was placed.
     */
    public static boolean placeHotSpringAt(ServerLevel level, int x, int z) {
        return placeHotSpringAt(level, x, z, HotSpringShape.MAX_STAGE);
    }

    /** @param stage how old the springs come out; the generator always asks for a finished one. */
    public static boolean placeHotSpringAt(ServerLevel level, int x, int z, int stage) {
        // --- Site analysis ---------------------------------------------------
        // Everything that went wrong before came from skipping this step: pools appeared on
        // shorelines, half in the sea, with their magma bed hanging out of a cliff and the water
        // draining away. So the ground is inspected first and unsuitable spots are simply refused.
        int centre = TerrainProbe.groundY(level, x, z);
        if (centre == Integer.MIN_VALUE) return false;
        if (centre <= level.getSeaLevel() + 2) return false;      // no beaches, no sea floor
        if (centre <= level.getMinBuildHeight() + 8) return false;

        int scan = 8;
        int lo = centre, hi = centre;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                int g = TerrainProbe.groundY(level, x + dx, z + dz);
                if (g == Integer.MIN_VALUE) return false;          // a cliff edge or open air
                if (TerrainProbe.hasFluidAbove(level, x + dx, z + dz)) return false;  // lake or sea
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        int relief = hi - lo;
        // A spring system needs level ground, but the old six-block limit rejected almost every
        // wooded hillside - which is why springs away from a volcano were so scarce. Broken ground
        // now gets a chain of smaller terraces instead of a flat refusal.
        if (relief > 12) return false;

        // --- Layout: one broad basin on the flat, terraces on a slope ---------
        // Flat ground gives a single wide pool; a gentle slope gives the stepped travertine
        // terraces you see at Pamukkale, each pool a little lower than the one above it.
        //
        // Two blocks of relief is noise on any forest floor, not a slope. Chaining on that put a
        // terrace system on ground that had nowhere to step down to, which is what drove the
        // descent below into the ground.
        boolean terraced = relief >= 4;
        int terraces = terraced ? 2 + level.random.nextInt(3) : 1;

        // Downhill direction, as an ANGLE rather than a compass point.
        //
        // This used to be two calls to Integer.compare, so the step was one of -1, 0, +1 on each
        // axis: eight possible directions, and the chain then walked that heading in a dead straight
        // line with a near-constant stride. Seen from above, a field of springs came out in rows and
        // columns, which is the "sıra sıra, sütun sütun" report - the regularity was not in where
        // the systems were placed, it was inside each one.
        //
        // The gradient gives a real bearing, and the walk below re-reads it at every step, so a
        // chain now follows the actual fall of the hill and wanders as the hill does.
        int gxPlus = TerrainProbe.groundY(level, x + scan, z);
        int gxMinus = TerrainProbe.groundY(level, x - scan, z);
        int gzPlus = TerrainProbe.groundY(level, x, z + scan);
        int gzMinus = TerrainProbe.groundY(level, x, z - scan);
        double bearing = Math.atan2(gzMinus - gzPlus, gxMinus - gxPlus);
        if (gxPlus == gxMinus && gzPlus == gzMinus) {
            bearing = level.random.nextDouble() * Math.PI * 2;   // dead flat: any way will do
        }

        int placed = 0;
        int px = x, pz = z;
        int ground = centre, waterY = centre - 1;       // recessed: water sits BELOW the rim
        // Every water cell cut so far. Each pool hands it to the next so a lower terrace cannot
        // clear, or tile sinter over, the water of the one above it.
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        BlockPos firstBed = null;
        for (int i = 0; i < terraces; i++) {
            int radius = terraced
                    ? Math.max(2, 5 - relief / 3) + level.random.nextInt(3)
                    : 5 + level.random.nextInt(3);
            // Every pool is built by a mineral water line, at generation exactly as after a quake -
            // the line is seated, its vent opened, and then run straight to maturity. There is no
            // second pool builder any more, so the two paths cannot drift apart, and what a new
            // world contains is what a recovered spring grows back into.
            if (openSpring(level, px, pz, radius, stage)) placed++;
            // Step downhill for the next pool in the chain. A full diameter plus a gap: the old
            // stride was one radius, so consecutive pools sat on top of each other. The bearing is
            // nudged and the stride varied at every step so the chain reads as a stream of pools
            // following a slope rather than a row of them on a ruler.
            int stride = radius * 2 + 2 + level.random.nextInt(4);
            bearing += (level.random.nextDouble() - 0.5) * 0.9;
            px += (int) Math.round(Math.cos(bearing) * stride);
            pz += (int) Math.round(Math.sin(bearing) * stride);
            int nextGround = TerrainProbe.groundY(level, px, pz);
            if (nextGround == Integer.MIN_VALUE) break;
            // The chain follows the hill; it never digs one for itself.
            //
            // It used to drop the water a block per terrace whatever the ground did
            // (`min(waterY - 1, nextGround - 1)`), while carveTerrace only ever opens TWO blocks
            // above the water. On level ground the third pool onward was therefore roofed over by
            // the original surface: the water was cut, it just had a lid on it, and the colour
            // bands were then painted across that lid. That is the "terraced springs are dry"
            // report - and, further down the chain, the pit in the ground.
            //
            // Now every pool sits exactly one block under ITS OWN ground, so two blocks of
            // clearance is always enough, and the chain simply stops where the hill does.
            if (nextGround >= ground) break;
            ground = nextGround;
            waterY = nextGround - 1;
            if (waterY <= lo - 6) break;
        }
        if (placed == 0) return false;
        GeysersMod.LOGGER.debug("Hot spring ({} pools) at {}, {}, {}", placed, x, centre, z);
        return true;
    }

    /**
     * How far under the pool the source sits.
     *
     * <p>Must clear the quake's reach, which is {@code QuakePlanner.MAX_CAPTURE_DEPTH} = 24. It sits
     * at twice that rather than just past it, so that the deep end reads as deep when a player digs
     * for it, and so a quake that drops the surface still cannot get near it.</p>
     */
    private static final int SOURCE_DEPTH = 50;

    /** The shallowest the source may sit under the surface and still be out of a quake's reach. */
    private static final int MIN_SOURCE_DEPTH = 28;

    /**
     * Puts in a mineral water line at this spot and runs it up to a finished spring.
     *
     * <p>This is the only way a hot spring is ever built. At generation it is run to maturity at
     * once, so a new world has old springs in it rather than a field of day-old puddles; after an
     * earthquake the same line grows the same spring back over a few in-game days. One builder, so
     * a repaired spring and a generated one cannot look different.</p>
     */
    private static boolean openSpring(ServerLevel level, int x, int z, int radius, int stage) {
        int ground = TerrainProbe.groundY(level, x, z);
        if (ground == Integer.MIN_VALUE) return false;
        if (ground <= level.getSeaLevel() + 2) return false;

        BlockPos source = place(level, new BlockPos(x, ground, z), ground, radius);
        if (source == null) return false;
        if (!(level.getBlockEntity(source) instanceof SpringSourceBlockEntity be)) return false;

        BlockPos vent = new BlockPos(x, ground, z);
        be.setVent(vent);
        boolean built = be.growTo(level, stage);
        if (built) boreConduit(level, source, ground);
        return built;
    }

    /**
     * Cuts the channel from the reservoir up to the pool.
     *
     * <h2>Why it has to be cut here</h2>
     * The mod says a hot spring is water rising from a chamber below, and until now that was only
     * ever said. {@code openSpring} built the reservoir 28 blocks down and then wrote the vent
     * straight to the surface, so nothing touched the rock in between: dig under a spring and there
     * was no channel, because there was no channel. The conduit is bored gradually by
     * {@link com.jeladastudios.ftsgeology.eruption.VentPathfinder} for a spring that has to work its
     * way up after being blocked, but a spring that arrives with the world has already done that
     * work, so it is cut in one go.
     *
     * <h2>Why the top of it is choked</h2>
     * A real vent narrows towards its mouth, because the mineral coming out of solution is deposited
     * fastest where the water first meets the air. Leaving it open would also drop anything that dug
     * into it straight down onto the magma bed, which is a nastier surprise than the feature is
     * worth.
     */
    private static void boreConduit(ServerLevel level, BlockPos source, int groundY) {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState choke = Blocks.CALCITE.defaultBlockState();
        int x = source.getX(), z = source.getZ();

        for (int y = source.getY() + 4; y < groundY - 1; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
            // The last few blocks under the pool floor are the throat, sealed with the spring's own
            // deposit. Everything below that is the water column itself.
            level.setBlock(p, y >= groundY - 4 ? choke : water, 2);
            // Skin the wall so the column does not open into a cave it happens to pass.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = p.relative(d);
                BlockState ws = level.getBlockState(w);
                if (ws.isAir() || !ws.getFluidState().isEmpty()) {
                    if (!EruptionHandler.isPlayerPlaced(ws)) level.setBlock(w, choke, 2);
                }
            }
        }
    }

    /**
     * Seats a mineral water line where there is no spring yet, so it can open one for itself.
     *
     * <p>Used when an earthquake has changed the plumbing somewhere that now qualifies. There is no
     * vent to inherit, so the line bores its way up and then grows a spring through its stages -
     * which is why a quake-opened spring announces itself as a small pool that gets bigger over the
     * following days rather than appearing finished.</p>
     */
    public static BlockPos seedSourceAt(ServerLevel level, int x, int z, int groundY) {
        return place(level, new BlockPos(x, groundY, z), groundY,
                5 + level.random.nextInt(3));
    }

    /**
     * Puts the deep end of a spring in, well below anything that can disturb it.
     *
     * @param at     the surface point the line belongs to
     * @param groundY surface height there, which fixes how deep the line is seated
     */
    private static BlockPos place(ServerLevel level, BlockPos at, int groundY, int radius) {
        // Pulled up rather than refused where the world floor is close: a shallow site still gets a
        // spring, just one whose deep end is nearer. It is only abandoned if it cannot be seated
        // deeper than a quake can dig.
        int y = Math.max(groundY - SOURCE_DEPTH, level.getMinBuildHeight() + 5);
        if (groundY - y < MIN_SOURCE_DEPTH) return null;           // too shallow a world here
        BlockPos src = new BlockPos(at.getX(), y, at.getZ());
        BlockState existing = level.getBlockState(src);
        if (existing.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(existing)) return null;

        buildReservoir(level, src, ModBlocks.SPRING_SOURCE.get().defaultBlockState(), 2, 3);
        if (level.getBlockEntity(src) instanceof SpringSourceBlockEntity be) {
            be.setTargetRadius(radius);
        }
        return src;
    }

    /**
     * The reservoir under a geothermal feature: rock floor, magma heat bed, a walled body of water,
     * and a natural rock cap over it.
     *
     * <h2>Why a spring has one at all</h2>
     * A hot spring and a geyser are the same machine. Both are water sitting on hot rock with a way
     * up; the only difference is that a geyser's conduit is constricted enough for pressure to build
     * before it lets go, and a spring's is not - so a spring simply seeps, steadily, forever. Giving
     * a spring only a marker block was the thing that made every version of it feel invented: the
     * pool had nothing feeding it, so it had to be conjured at the surface instead of arriving from
     * below.
     *
     * <p>There is deliberately no pre-carved shaft. The cap is natural rock, and the conduit is bored
     * upward a few blocks at a time by {@link com.jeladastudios.ftsgeology.eruption.VentPathfinder} -
     * which is also what lets a blocked spring go looking for a different way out.</p>
     */
    static void buildReservoir(ServerLevel level, BlockPos core, BlockState coreBlock,
                               int rad, int chamberH) {
        int waterDepth = Math.max(1, chamberH - 1);

        // Containment floor, then a SOLID magma bed. Fluid lava would mix with the chamber water
        // into cobblestone, or drain away into a cave, and take the heat with it.
        fillLayer(level, core.below(2), rad + 1, Blocks.DEEPSLATE);
        fillLayer(level, core.below(1), rad, Blocks.MAGMA_BLOCK);
        MagmaSealing.sealSlab(level, core.below(1), rad);

        // Core level: a rock ring keeping the heat off the water, with the core in the middle.
        fillLayer(level, core, rad, Blocks.DEEPSLATE);
        level.setBlock(core, coreBlock, 2);

        // The water itself, walled so it cannot leak into a cave alongside.
        for (int dy = 1; dy <= chamberH; dy++) {
            fillLayer(level, core.above(dy), rad, dy <= waterDepth ? Blocks.WATER : Blocks.AIR);
            ringWall(level, core.above(dy), rad + 1);
        }
    }

    /** Lays the microbial colour bands around a finished pool. Called by the spring line. */
    public static void paintRings(ServerLevel level, List<BlockPos> pool, int cx, int cz, int waterY, int stage) {
        paintThermalRings(level, pool, cx, cz, waterY, stage);
    }

    /**
     * Cuts one travertine pool into the ground, at world generation.
     *
     * <p>The pool is <b>recessed</b>: its water sits a block below the surrounding ground, so it is
     * physically incapable of spilling out - the containment comes from the shape of the land rather
     * than from a wall built around it afterwards. The calcite floor hides a magma bed, and that bed
     * is wrapped on every exposed face so it can never show through a slope.</p>
     *
     * <h2>Generation only</h2>
     * A spring that has already been placed does <b>not</b> rebuild itself with this. It was tried,
     * and the strictness that is right here - refuse an awkward site, there are thousands of other
     * chunks - is exactly wrong for a spring that has already earned its place. Worse, cutting a
     * pool lowers the ground, so a source that re-cut on a timer walked its own pool one block
     * downhill every two seconds. {@code SpringSourceBlockEntity} builds by deposition instead, and
     * never removes anything.
     *
     * @return where the warm bed went, or null if this spot will not hold a pool
     */
    private static BlockPos carveTerrace(ServerLevel level, int cx, int cz, int waterY, int radius,
                                        java.util.Set<BlockPos> taken) {
        double phaseA = level.random.nextDouble() * Math.PI * 2;
        double phaseB = level.random.nextDouble() * Math.PI * 2;

        // Never cut at or below the waterline.
        //
        // A basin dug below sea level beside the sea is a drain. Everything here is written with
        // flag 2, so nothing moves at generation - but the moment any block update reaches it the
        // ocean empties into the hole, which is the "a spring next to water destroys the water
        // around it" report. The sea was not being deleted; it was running into the pit the spring
        // had just dug for it.
        if (waterY <= level.getSeaLevel() + 1) return null;

        int reach = radius + 2;

        // Standing water anywhere in the footprint OR one block outside it refuses the whole
        // terrace. One block out matters because the rim is written there, and the rim used to
        // overwrite water with sinter - so a spring on a shoreline chewed into the sea before it
        // had even finished being built.
        for (int dx = -reach - 1; dx <= reach + 1; dx++) {
            for (int dz = -reach - 1; dz <= reach + 1; dz++) {
                if (TerrainProbe.hasFluidAbove(level, cx + dx, cz + dz)) return null;
            }
        }

        List<BlockPos> pool = new ArrayList<>();
        int footprint = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                double ang = Math.atan2(dz, dx);
                double rr = radius * (1.0 + 0.32 * Math.sin(2 * ang + phaseA)
                        + 0.18 * Math.sin(3 * ang + phaseB));
                if (dist > rr) continue;
                int gx = cx + dx, gz = cz + dz;
                int g = TerrainProbe.groundY(level, gx, gz);
                if (g == Integer.MIN_VALUE) continue;
                footprint++;
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(new BlockPos(gx, g, gz)))) continue;
                // Cut down to the basin, or build its floor up - but only over a few blocks. The
                // old rule accepted a cell ONLY if its ground already happened to sit within two
                // blocks of the water level, so on any uneven ground most of the footprint was
                // rejected and what you got was a couple of water blocks in the middle of a large
                // sinter field. The basin is levelled now instead of being hunted for.
                if (g < waterY - 3 || g > waterY + 4) continue;
                pool.add(new BlockPos(gx, waterY, gz));
            }
        }
        // A pool has to be most of its own footprint. Anything less is a puddle in a colour field,
        // and with finite-water mods installed it would drain to nothing and read as empty.
        if (pool.size() < 6 || pool.size() < footprint * 0.6) {
            return null;
        }

        // Take the canopy off the whole terrace - basin and colour bands - before any of it is cut.
        clearCanopy(level, cx, cz, radius + 18);

        for (BlockPos w : pool) {
            // Clear anything standing over the basin, then cut it: water, calcite floor, magma bed.
            TerrainProbe.clearVegetation(level, w.getX(), waterY, w.getZ(), 3);
            // Open the cell to its OWN ground, not to a fixed two blocks.
            //
            // The cell filter accepts ground up to three blocks above the water
            // (|g - (waterY+1)| <= 2), so on lumpy terrain a fixed two-block clear still left some
            // cells of the same pool roofed over by the original surface - water underneath, lid on
            // top. That is the dryness that survived the last round, and why it was "some of them"
            // rather than all: it needs bumpy ground, which is exactly where it was reported.
            // Clamped at 4: the cell filter only admits ground up to waterY+3, so anything larger
            // means the terrain moved under us between building the pool and cutting it. Bounded so
            // a surprise can never turn into a shaft driven up through the hillside.
            int cellGround = TerrainProbe.groundY(level, w.getX(), w.getZ());
            int open = cellGround == Integer.MIN_VALUE
                    ? 2 : Mth.clamp(cellGround - waterY, 2, 5);
            for (int up = 1; up <= open; up++) {
                BlockPos a = w.above(up);
                // Never clear a cell an upstream pool is using as its water. Terraces step down one
                // block at a time and their reaches overlap, so this loop used to empty the pool
                // above it - which is why terraced springs came out dry.
                if (taken.contains(a)) continue;
                BlockState as = level.getBlockState(a);
                if (!as.isAir() && !EruptionHandler.isPlayerPlaced(as)) {
                    level.setBlock(a, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            // Build the floor UP where the ground fell away below the basin. Without this a cell
            // whose ground sat two or three blocks low got a single calcite block with a void under
            // it, and the water above had nothing to rest on - so the pool came out part full.
            int floorFrom = cellGround == Integer.MIN_VALUE
                    ? waterY - 1 : Math.min(waterY - 1, cellGround);
            for (int y = floorFrom; y <= waterY - 1; y++) {
                level.setBlock(new BlockPos(w.getX(), y, w.getZ()),
                        Blocks.CALCITE.defaultBlockState(), 2);
            }
            level.setBlock(w, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(w.below(2), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
            MagmaSealing.seal(level, w.below(2), false);   // never visible from a slope or cave
        }

        // Rim: the terrace lip, one block proud of the water so nothing can run out, and the
        // sinter shelf that a spring actually armours itself with.
        for (BlockPos w : pool) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos edge = w.relative(d);
                if (pool.contains(edge)) continue;
                for (int up = 0; up <= 1; up++) {
                    BlockPos p = edge.above(up);
                    // The rim writes sinter unconditionally at up == 0, which would tile straight
                    // over the water of the pool one terrace up.
                    if (taken.contains(p)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
                    if (up == 0 || s.isAir() || !s.getFluidState().isEmpty()
                            || TerrainProbe.isVegetation(s)) {
                        level.setBlock(p, ModBlocks.SINTER.get().defaultBlockState(), 2);
                    }
                }
            }
        }

        paintThermalRings(level, pool, cx, cz, waterY, HotSpringShape.MAX_STAGE);

        // Seal the magma bed on every face that could otherwise be exposed on a slope.
        for (BlockPos w : pool) {
            BlockPos magma = w.below(2);
            for (Direction d : Direction.values()) {
                if (d == Direction.UP) continue;
                BlockPos p = magma.relative(d);
                if (pool.contains(p.above(2))) continue;
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.BEDROCK) || s.is(Blocks.MAGMA_BLOCK)) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                if (s.isAir() || !s.getFluidState().isEmpty()) {
                    level.setBlock(p, Blocks.CALCITE.defaultBlockState(), 2);
                }
            }
        }

        // A bed under EVERY terrace, not just the first.
        //
        // It used to be gated on `core`, so in a chain of four pools only the top one was warm -
        // the rest were cold water in a sinter basin, with no steam and no regeneration, which is
        // not a spring system, it is one spring and three puddles.
        BlockPos bed = pool.contains(new BlockPos(cx, waterY, cz))
                ? new BlockPos(cx, waterY - 1, cz)
                : pool.get(pool.size() / 2).below();
        level.setBlock(bed, ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
        taken.addAll(pool);

        // Count the water back.
        //
        // Terraced springs have come out dry three rounds in a row, and each round I found a real
        // cause, fixed it, measured the fix, and it was still not the whole story. So rather than
        // guess a fourth time: the pool knows how many cells it filled, and if any of them are not
        // water by the time the method returns, something in here took them and the log will say
        // how many and where. Cheap - one block read per pool cell, once, at generation.
        int wet = 0;
        for (BlockPos w : pool) {
            if (!level.getBlockState(w).getFluidState().isEmpty()) wet++;
        }
        if (wet < pool.size()) {
            GeysersMod.LOGGER.warn(
                    "hot spring lost water: {} of {} cells dry at {},{},{} (biome {})",
                    pool.size() - wet, pool.size(), cx, waterY, cz,
                    level.getBiome(new BlockPos(cx, waterY, cz)).unwrapKey()
                            .map(k -> k.location().toString()).orElse("?"));
        }
        return bed;
    }


    /**
     * Strips the canopy off a terrace before it is cut.
     *
     * <p>{@link TerrainProbe#clearVegetation} deliberately never touches logs or leaves - a cabin is
     * made of logs - and it stops dead at the first block that is not ground cover. It was the only
     * clearing a spring ever did, so a wooded site kept its trees: {@code groundY} walks past a
     * trunk, so the basin was cut <em>underneath</em> one, and the crowns roofed the whole colour
     * field over. Both are visible in the test shots.</p>
     *
     * <p>The edge frays the same way a volcano's does: the basin is taken outright, and further out
     * more and more trees are left alone. A spared tree is spared <b>whole</b> - stripping the
     * leaves and leaving the trunk was tried and reads as a bug rather than as dead timber - and the
     * decision comes from a smooth field rather than a per-column roll, because a tree covers a
     * dozen columns and rolling per column would leave half a canopy standing.</p>
     */
    public static void clearCanopy(ServerLevel level, int cx, int cz, int radius) {
        double solid = radius * 0.5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (dist > radius) continue;
                int gx = cx + dx, gz = cz + dz;
                int g = TerrainProbe.groundY(level, gx, gz);
                if (g == Integer.MIN_VALUE) continue;

                // The further out, the likelier a whole tree survives.
                double out = Mth.clamp((dist - solid) / Math.max(1.0, radius - solid), 0.0, 1.0);
                if (dist > solid && spareField(gx, gz) < out) continue;

                // Stop at the top of whatever actually stands here rather than always walking a
                // fixed 24 blocks of air: on open ground that is one lookup instead of two dozen,
                // and a spring field is mostly open ground.
                int top = Math.min(g + 24, level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz));
                for (int y = g + 1; y <= top; y++) {
                    BlockPos p = new BlockPos(gx, y, gz);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    boolean tree = s.is(net.minecraft.tags.BlockTags.LOGS)
                            || s.is(net.minecraft.tags.BlockTags.LEAVES);
                    if (!tree && !TerrainProbe.isVegetation(s)) break;   // something real: stop
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * A smooth 0..1 field used to decide whether a tree is spared, coherent over roughly twenty
     * blocks so a whole tree lands on one side of the threshold.
     */
    private static double spareField(int x, int z) {
        double n = Math.sin(x * 0.17) * Math.cos(z * 0.21)
                + 0.5 * Math.sin((x + z) * 0.09)
                + 0.35 * Math.sin((x - z) * 0.29);
        return Mth.clamp((n + 1.85) / 3.7, 0.0, 1.0);
    }

    /**
     * Rings a hot spring with the coloured bands that make one recognisable from the air.
     *
     * <h2>The colours are alive</h2>
     * Nothing grows in the boiling centre, so the water there is clear blue. Working outward the
     * runoff cools through one temperature range after another, and each range belongs to a
     * different community of heat-loving microorganisms with its own pigment: orange nearest the
     * heat, then yellow, brown, and finally ordinary green algae at the cool edge. The rings are a
     * thermometer you can see - which is exactly why Grand Prismatic looks the way it does, and why
     * these are laid down in order rather than scattered.
     *
     * <p>Band widths are rolled per spring and the edges wobble with the angle, so no two rings look
     * alike. Ground more than a couple of blocks off the water's level is skipped, so the bands stay
     * on the apron instead of climbing a bank.</p>
     */
    private static void paintThermalRings(ServerLevel level, List<BlockPos> pool,
                                          int cx, int cz, int waterY, int stage) {
        if (pool.isEmpty()) return;

        // Band order runs OUTWARD from the water, and it is the order Grand Prismatic actually
        // shows: a white sinter shelf, a narrow green fringe at the waterline, then yellow, then
        // orange, and rust-brown at the dry edge.
        //
        // It used to end on green, which put the brightest colour in the mod hard against the
        // grass and made the whole spring read as painted on. In the ground the outermost ring is
        // the brown one, and it blends into bare earth on its own - the transition needs no help
        // once the order is right. The green fringe is kept narrow because in a real spring it is
        // not really a mat at all: it is blue water seen shallow over the yellow one.
        // How many of those bands exist depends on how old the spring is.
        //
        // A mat is a living thing. It needs a pool that has been warm, wet and the same shape for
        // long enough to be colonised, and the outer bands need the widest, coolest, most settled
        // fringe of all - so on a spring that opened a few days ago there is nothing but its own
        // bare deposit. The colours arriving one at a time is what makes the stages read as ages
        // rather than as the same spring at different sizes.
        int[] band = {
                1 + level.random.nextInt(2),   // the sinter shelf
                stage >= 2 ? 1 + level.random.nextInt(2) : 0,   // green, the shallow fringe
                stage >= 3 ? 2 + level.random.nextInt(3) : 0,   // yellow
                stage >= 4 ? 2 + level.random.nextInt(3) : 0,   // orange
                stage >= 4 ? 2 + level.random.nextInt(4) : 0,   // brown, coolest and driest
        };
        int bandReach = 0;
        for (int b : band) bandReach += b;
        // Beyond the last mat, the ground a spring poisons.
        //
        // The colours used to stop dead and ordinary soil began at the next block, which read as
        // the spring having been dropped onto the landscape. The bare ring is real - silica,
        // sulfate and arsenic in the runoff kill the soil and the heat finishes the roots - but it
        // has to LOOK killed rather than look unfinished. So it is a pale crusted skin that thins
        // outward into whatever was there, and the trees standing in it are dead.
        int halo = stage >= 4 ? 5 + level.random.nextInt(6) : 0;
        int reach = bandReach + halo;
        double phase = level.random.nextDouble() * Math.PI * 2;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : pool) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }

        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                double nearest = Double.MAX_VALUE;
                for (BlockPos p : pool) {
                    double dx = x - p.getX(), dz = z - p.getZ();
                    nearest = Math.min(nearest, dx * dx + dz * dz);
                }
                double d = Math.sqrt(nearest);
                if (d < 0.9) continue;                       // that is the pool itself

                // Irregular edges, so the rings are not a bullseye.
                double ang = Math.atan2(z - cz, x - cx);
                double wobble = 1.0 + 0.28 * Math.sin(3 * ang + phase)
                        + 0.15 * Math.sin(5 * ang - phase);
                double scaled = d / Math.max(0.4, wobble);
                Block b = bandFor(scaled, band);
                // Past the last mat: the sterile halo, thinning out into the countryside.
                boolean inHalo = false;
                if (b == null) {
                    double out = (scaled - bandReach) / halo;   // 0 at the brown edge, 1 outside
                    if (out < 0.0 || out > 1.0) continue;
                    // Fades rather than ends: nearly every cell right against the mats, hardly any
                    // at the far edge, so the crust breaks up instead of drawing another ring.
                    if (level.random.nextDouble() > 1.0 - out) continue;
                    b = haloBlock(level);
                    inHalo = true;
                }

                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE) continue;
                // The mats keep to the flat apron. The halo may climb a little further, because
                // ground poisoned by the runoff does not stop at a contour line.
                if (Math.abs(g - waterY) > (inHalo ? 4 : 2)) continue;
                // Never repaint the floor of standing water. groundY walks past a fluid, so the
                // cell it returns inside a neighbouring pool is that pool's calcite bed - painting
                // a mat there leaves the water sitting on a microbial mat instead of sinter.
                if (!level.getBlockState(new BlockPos(x, g + 1, z)).getFluidState().isEmpty()) continue;
                BlockPos p = new BlockPos(x, g, z);
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
                if (!s.getFluidState().isEmpty()) continue;
                TerrainProbe.clearVegetation(level, x, g, z, 2);
                level.setBlock(p, b.defaultBlockState(), 2);
                if (inHalo && level.random.nextInt(30) == 0) deadTree(level, p);
            }
        }
    }

    /**
     * The crust of the sterile halo: pale, dry and broken.
     *
     * <p>No new block for it. What is wanted is bare poisoned ground, and coarse dirt with gravel
     * through it and the odd patch of the spring's own sinter already reads as exactly that -
     * lighter and drier than the soil around it, without being another flat colour.</p>
     */
    private static Block haloBlock(ServerLevel level) {
        int r = level.random.nextInt(10);
        if (r < 4) return Blocks.COARSE_DIRT;
        if (r < 7) return Blocks.GRAVEL;
        if (r < 9) return ModBlocks.SINTER.get();
        return Blocks.TUFF;
    }

    /**
     * A dead tree standing in the halo.
     *
     * <h2>Why bare trunks are right here and were wrong around a volcano</h2>
     * Stripping the leaves off a volcano's trees and leaving the trunks read as a bug, and it was
     * removed. The difference is the ground: a leafless oak standing in green grass looks like
     * something failed to finish, while a barkless, bleached trunk standing on pale dead crust is
     * the single most recognisable thing about a geothermal basin - Yellowstone's "bobby socks"
     * trees, killed by silica-laden water and left white to the knee where it wicked up the wood.
     *
     * <p>So this only ever runs on a cell that has just been turned into halo crust, and it uses
     * stripped logs, which are already pale and barkless, with sinter around the base.</p>
     */
    private static void deadTree(ServerLevel level, BlockPos ground) {
        Block trunk = level.random.nextBoolean() ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.STRIPPED_OAK_LOG;
        int height = 3 + level.random.nextInt(4);
        for (int h = 1; h <= height; h++) {
            BlockPos p = ground.above(h);
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !TerrainProbe.isVegetation(s)) return;   // something is in the way
            level.setBlock(p, trunk.defaultBlockState(), 2);
        }
        // The white foot: silica drawn up out of the ground, which is where the name comes from.
        level.setBlock(ground, ModBlocks.SINTER.get().defaultBlockState(), 2);
    }

    /** Which band a given distance from the water falls in, or null past the last one. */
    private static Block bandFor(double d, int[] band) {
        double edge = band[0];
        if (d <= edge) return ModBlocks.SINTER.get();
        edge += band[1];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_GREEN.get();
        edge += band[2];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_YELLOW.get();
        edge += band[3];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_ORANGE.get();
        edge += band[4];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_BROWN.get();
        return null;
    }
    /**
     * Weighted size roll: most vents are small short-lived spouters, a few are mid-sized,
     * and large hour-long geysers are rare landmarks.
     */
    private static int pickMagnitude(RandomSource rng) {
        double r = rng.nextDouble();
        if (r < 0.75) return 5 + rng.nextInt(3);   // 5–7   common
        if (r < 0.95) return 8 + rng.nextInt(5);   // 8–12  uncommon
        return 13 + rng.nextInt(8);                // 13–20 rare landmark
    }

    /**
     * Verifies the whole vertical extent (heat source cell up through chamber + rock cap) is
     * natural rock/fluid only. If any player-placed block is present, or any cell sits at/above
     * the safety ceiling, the column is rejected.
     */
    private static boolean columnIsCarvable(ServerLevel level, BlockPos core, int chamberH, int maxY) {
        for (int dy = -1; dy <= chamberH + 1; dy++) {
            BlockPos p = core.above(dy);
            if (p.getY() >= maxY) return false;                 // never breach the ceiling
            BlockState s = level.getBlockState(p);
            if (EruptionHandler.isPlayerPlaced(s)) return false; // respect player builds
            // Require solid-ish natural matrix around the chamber shell for realism.
            if (dy == chamberH + 1 && !(s.is(Blocks.DEEPSLATE) || s.is(Blocks.STONE))) {
                return false; // need a real rock cap on top
            }
        }
        return true;
    }

    private static void buildSystem(ServerLevel level, BlockPos core, int chamberH, int magnitude,
                                    RandomSource rng, boolean aggressiveShaft, boolean growBranches) {
        // A proper reservoir: a wide water basin sitting over a natural lava pool, capped by rock.
        // Width and depth scale with magnitude, so bigger geysers are genuinely bigger structures
        // (not a single-block tube) — and the lava pool + water volume give it the thermal mass to
        // keep cycling instead of dying the first time cold surface water pours back in.
        int rad = Mth.clamp(magnitude / 5, 1, 3);          // 1 -> 3x3, 3 -> 7x7
        // Mostly water with just a shallow air gap under the cap — a water reservoir, not an air
        // tank — so V_su stays high and pressure actually builds.
        int waterDepth = Math.max(1, chamberH - 1);

        // 1. Containment floor, then a SOLID magma heat-bed just under the core. (A fluid-lava pool
        //    mixes with the chamber water into cobblestone/obsidian — or drains into caves/aquifers —
        //    which is what was silently killing the heat AND the water. Magma blocks are inert and
        //    read as full heat, so the geyser reliably warms up.)
        fillLayer(level, core.below(2), rad + 1, Blocks.DEEPSLATE);
        fillLayer(level, core.below(1), rad, Blocks.MAGMA_BLOCK);
        // The bed is a heat source, not scenery. If the geyser happened to form beside a cave, that
        // slab used to glow out of the cave wall as an obvious block of magma; skin whatever faces
        // are open so it stays hidden while still heating the chamber above it.
        MagmaSealing.sealSlab(level, core.below(1), rad);

        // 2. Core level: rock ring separating lava from water, with the core at its centre.
        fillLayer(level, core, rad, Blocks.DEEPSLATE);
        level.setBlock(core, ModBlocks.GEYSER_CORE.get().defaultBlockState(), 2);
        GeyserCoreBlockEntity coreBe =
                level.getBlockEntity(core) instanceof GeyserCoreBlockEntity be ? be : null;
        if (coreBe != null) coreBe.setMagnitude(magnitude);

        // 3. Wide water basin, then an air gap; walled so it doesn't leak into caves. NO solid cap:
        //    the chamber opens straight into the vent shaft, so steam wisps up to the surface while
        //    it pressurises and the eruption spouts from daylight — no sealed lid to get stuck under.
        for (int dy = 1; dy <= chamberH; dy++) {
            fillLayer(level, core.above(dy), rad, dy <= waterDepth ? Blocks.WATER : Blocks.AIR);
            ringWall(level, core.above(dy), rad + 1);
        }

        // 4. NO pre-carved shaft — the whole point of the "forms deep, then drills up" behaviour.
        //    The chamber is sealed by the natural rock cap above it; during eruptions the
        //    VentPathfinder bores upward only a few blocks per second, so the vent works its way to
        //    daylight over time. When it breaks into a cave on the way it erupts THERE first (water +
        //    steam into the cave), walls that opening with calcite, then carries on straight up until
        //    it reaches the surface. We stamp a FIXED ceiling = the original ground surface plus a
        //    short chimney allowance, so the vent knows where to stop. (Without a fixed target it
        //    would chase its own rising calcite chimney — which lifts the heightmap — into the sky.)
        //    ({@code aggressiveShaft} is now unused but kept for the API.)
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, core.getX(), core.getZ());
        if (coreBe != null) {
            coreBe.setVentMouthY(surfaceY + SURFACE_CHIMNEY_HEIGHT);
        }

        // 6. Grow root-like side vents; record cave/air breakthroughs as secondary fumaroles.
        //    Skipped for near-surface (igniter) geysers — branches would poke holes in the ground.
        if (growBranches) {
            List<BlockPos> tips = VentNetwork.growBranches(level, core, chamberH, magnitude, rng);
            if (coreBe != null && !tips.isEmpty()) {
                coreBe.setFumaroleTips(tips);
            }
        }
    }

    /** Fills a solid square layer of side (2*rad+1), skipping player-placed blocks. */
    private static void fillLayer(ServerLevel level, BlockPos center, int rad, net.minecraft.world.level.block.Block block) {
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                BlockPos p = center.offset(dx, 0, dz);
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(p))) continue;
                level.setBlock(p, block.defaultBlockState(), 2);
            }
        }
    }

    /** Seals the perimeter ring of a chamber layer with rock where it would otherwise leak (air/fluid). */
    private static void ringWall(ServerLevel level, BlockPos center, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // perimeter cells only
                BlockPos p = center.offset(dx, 0, dz);
                BlockState s = level.getBlockState(p);
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                if (s.isAir() || !s.getFluidState().isEmpty()) {
                    level.setBlock(p, Blocks.DEEPSLATE.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * Carves a thin (1-wide) vent from just above the rock cap up toward the surface. Carves
     * through natural terrain <em>and</em> vegetation (grass, trees, snow), and <b>stops</b> — it
     * does not abort — the moment it meets a clearly built block, returning the highest cell it
     * cleared (or {@link Integer#MIN_VALUE} if it couldn't start). Whatever it doesn't finish, the
     * eruption pathfinder bores through afterwards, so a tree or a bit of terrain no longer leaves
     * the geyser sealed underground. Build-safe: it never breaks manufactured blocks.
     */
    private static int carveVentShaft(ServerLevel level, BlockPos core, int chamberH, boolean aggressive) {
        if (!GeyserConfig.CARVE_SURFACE_SHAFT.get()) return Integer.MIN_VALUE;

        int startY = core.getY() + chamberH + 1; // straight above the open chamber top
        int groundTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, core.getX(), core.getZ()) - 1;
        if (groundTop <= startY) return Integer.MIN_VALUE; // already open, or too shallow to bother

        // Reach the actual surface even on very tall mountains. We enforce at least a 500-block
        // allowance regardless of the (possibly stale, per-world) config value, so an old
        // shaftMaxLength=160 can't leave the vent stuck inside a Terralith peak.
        int cap = Math.max(GeyserConfig.SHAFT_MAX_LENGTH.get(), 500);
        int endY = Math.min(groundTop, startY + cap);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        int reached = Integer.MIN_VALUE;
        for (int y = startY; y <= endY; y++) {
            m.set(core.getX(), y, core.getZ());
            BlockState s = level.getBlockState(m);
            // Natural gen stops at builds; an aggressive (command/igniter) carve clears everything
            // except bedrock so it always reaches daylight.
            boolean blocked = aggressive ? s.is(Blocks.BEDROCK) : !isShaftClearable(s);
            if (blocked) break;
            if (!s.isAir()) {
                level.setBlock(m.immutable(), Blocks.AIR.defaultBlockState(), 2);
            }
            reached = y;
        }
        return reached; // highest cleared cell (surface opening), or MIN_VALUE if none
    }

    /** Natural terrain plus vegetation/plants the shaft may burn through (but not manufactured blocks). */
    private static boolean isShaftClearable(BlockState s) {
        return EruptionHandler.isNaturalTerrain(s)
                || s.is(net.minecraft.tags.BlockTags.LOGS)
                || s.is(net.minecraft.tags.BlockTags.LEAVES)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS)
                || s.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || s.is(net.minecraft.tags.BlockTags.CROPS)
                || s.is(net.minecraft.world.level.block.Blocks.GRASS)
                || s.is(net.minecraft.world.level.block.Blocks.TALL_GRASS)
                || s.is(net.minecraft.world.level.block.Blocks.FERN)
                || s.is(net.minecraft.world.level.block.Blocks.LARGE_FERN)
                || s.is(net.minecraft.world.level.block.Blocks.VINE)
                || s.is(net.minecraft.world.level.block.Blocks.SNOW);
    }

    /**
     * Builds a dimension-qualified key. {@code getLevel()} on chunk events is
     * {@link org.jetbrains.annotations.Nullable}; when present it is a {@link Level} whose
     * dimension we fold in. A null level falls back to an "unknown" prefix — acceptable
     * because the authoritative Save event always carries the real level.
     */
    private static String keyOf(LevelAccessor levelAccess, ChunkAccess chunk) {
        String dim = (levelAccess instanceof Level lvl)
                ? lvl.dimension().location().toString()
                : "unknown";
        return dim + "@" + chunk.getPos().toLong();
    }
}
