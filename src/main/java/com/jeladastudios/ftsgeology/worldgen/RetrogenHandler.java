package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
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

        // Volcanoes under construction get their slice first. They are the heaviest thing the mod
        // builds, so they are emitted as steps and drained against a wall-clock deadline rather than
        // raised in one tick - a large shield covers thousands of columns.
        com.jeladastudios.ftsgeology.volcano.VolcanoJob.drain(event.getServer(),
                GeyserConfig.QUAKE_TICK_BUDGET_MS.get() * 1_000_000L);

        // A volcano under construction slows chunk geology down rather than stopping it. Blocking
        // outright looked tidier but would let the chunk queue grow without bound while exploring a
        // hotspot, where volcanoes are common enough to arrive faster than they finish.
        int budget = GeyserConfig.RETROGEN_CHUNKS_PER_TICK.get();
        if (com.jeladastudios.ftsgeology.volcano.VolcanoJob.busy()) budget = Math.max(1, budget / 2);

        if (!QUEUE.isEmpty()) {
            // A wall-clock brake as well as a chunk count, so the count above is a permission rather
            // than a promise: whichever runs out first stops the tick.
            long deadline = System.nanoTime() + GeyserConfig.QUAKE_TICK_BUDGET_MS.get() * 1_000_000L;
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
            if (d < bestDist) {
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
        boolean terraced = relief >= 2;
        int terraces = terraced ? 2 + level.random.nextInt(3) : 1;

        // Downhill direction, from the height difference across the site.
        int gxPlus = TerrainProbe.groundY(level, x + scan, z);
        int gxMinus = TerrainProbe.groundY(level, x - scan, z);
        int gzPlus = TerrainProbe.groundY(level, x, z + scan);
        int gzMinus = TerrainProbe.groundY(level, x, z - scan);
        int stepX = Integer.compare(gxMinus, gxPlus);   // +1 means downhill toward +x
        int stepZ = Integer.compare(gzMinus, gzPlus);
        if (stepX == 0 && stepZ == 0) stepX = 1;        // pick a direction on dead-flat ground

        int placed = 0;
        int px = x, pz = z, waterY = centre - 1;        // recessed: water sits BELOW the rim
        for (int i = 0; i < terraces; i++) {
            int radius = terraced
                    ? Math.max(2, 5 - relief / 3) + level.random.nextInt(3)
                    : 5 + level.random.nextInt(3);
            if (carveTerrace(level, px, pz, waterY, radius, i == 0)) placed++;
            // Step downhill for the next pool in the chain.
            int stride = radius + 2 + level.random.nextInt(2);
            px += stepX * stride;
            pz += stepZ * stride;
            int nextGround = TerrainProbe.groundY(level, px, pz);
            if (nextGround == Integer.MIN_VALUE) break;
            waterY = Math.min(waterY - 1, nextGround - 1);   // always one step further down
            if (waterY <= lo - 6) break;
        }
        if (placed == 0) return false;
        GeysersMod.LOGGER.debug("Hot spring ({} pools) at {}, {}, {}", placed, x, centre, z);
        return true;
    }

    /**
     * Cuts one travertine pool into the ground.
     *
     * <p>The pool is <b>recessed</b>: its water sits a block below the surrounding ground, so it is
     * physically incapable of spilling out - the containment comes from the shape of the land rather
     * than from a wall built around it afterwards. The calcite floor hides a magma bed, and that bed
     * is wrapped on every exposed face so it can never show through a slope.</p>
     *
     * @param core true for the first pool of a system, which gets the ticking hot-spring block
     */
    private static boolean carveTerrace(ServerLevel level, int cx, int cz, int waterY, int radius,
                                        boolean core) {
        double phaseA = level.random.nextDouble() * Math.PI * 2;
        double phaseB = level.random.nextDouble() * Math.PI * 2;

        List<BlockPos> pool = new ArrayList<>();
        int reach = radius + 2;
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
                if (Math.abs(g - (waterY + 1)) > 2) continue;    // keep the basin at one level
                if (TerrainProbe.hasFluidAbove(level, gx, gz)) continue;
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(new BlockPos(gx, g, gz)))) continue;
                pool.add(new BlockPos(gx, waterY, gz));
            }
        }
        if (pool.size() < 6) return false;

        for (BlockPos w : pool) {
            // Clear anything standing over the basin, then cut it: water, calcite floor, magma bed.
            TerrainProbe.clearVegetation(level, w.getX(), waterY, w.getZ(), 3);
            for (int up = 1; up <= 2; up++) {
                BlockPos a = w.above(up);
                BlockState as = level.getBlockState(a);
                if (!as.isAir() && !EruptionHandler.isPlayerPlaced(as)) {
                    level.setBlock(a, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            level.setBlock(w, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(w.below(), Blocks.CALCITE.defaultBlockState(), 2);
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
                    BlockState s = level.getBlockState(p);
                    if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
                    if (up == 0 || s.isAir() || !s.getFluidState().isEmpty()
                            || TerrainProbe.isVegetation(s)) {
                        level.setBlock(p, ModBlocks.SINTER.get().defaultBlockState(), 2);
                    }
                }
            }
        }

        paintThermalRings(level, pool, cx, cz, waterY);

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

        if (core) {
            BlockPos bed = new BlockPos(cx, waterY - 1, cz);
            if (pool.contains(new BlockPos(cx, waterY, cz))) {
                level.setBlock(bed, ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
            } else {
                BlockPos any = pool.get(pool.size() / 2);
                level.setBlock(any.below(), ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
            }
        }
        return true;
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
                                          int cx, int cz, int waterY) {
        if (pool.isEmpty()) return;

        int[] band = {
                1 + level.random.nextInt(2),   // the sinter shelf
                2 + level.random.nextInt(3),   // orange, hottest
                2 + level.random.nextInt(3),   // yellow
                2 + level.random.nextInt(4),   // brown
                2 + level.random.nextInt(4),   // green, coolest
        };
        int reach = 0;
        for (int b : band) reach += b;
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
                Block b = bandFor(d / Math.max(0.4, wobble), band);
                if (b == null) continue;

                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE) continue;
                if (Math.abs(g - waterY) > 2) continue;      // stays on the flat apron
                BlockPos p = new BlockPos(x, g, z);
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
                if (!s.getFluidState().isEmpty()) continue;
                TerrainProbe.clearVegetation(level, x, g, z, 2);
                level.setBlock(p, b.defaultBlockState(), 2);
            }
        }
    }

    /** Which band a given distance from the water falls in, or null past the last one. */
    private static Block bandFor(double d, int[] band) {
        double edge = band[0];
        if (d <= edge) return ModBlocks.SINTER.get();
        edge += band[1];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_ORANGE.get();
        edge += band[2];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_YELLOW.get();
        edge += band[3];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_BROWN.get();
        edge += band[4];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_GREEN.get();
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
