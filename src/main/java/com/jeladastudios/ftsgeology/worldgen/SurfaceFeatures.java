package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import com.jeladastudios.ftsgeology.volcano.VolcanoBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.List;
import static com.jeladastudios.ftsgeology.worldgen.RetrogenHandler.*;
import static com.jeladastudios.ftsgeology.worldgen.HotSpringSites.*;

/** Surface features placed once a chunk has loaded: hot springs, geysers and small volcanoes, where the setting allows them. */
public final class SurfaceFeatures {

    private SurfaceFeatures() {}

    // === Generation =========================================================

    /** How much denser hot springs get on properly geothermal ground. */
    static final double GEOTHERMAL_SPRING_BOOST = 1.25;

    /**
     * 0 on ordinary country, rising to 1 on ground that is genuinely geothermal.
     *
     * <p>The same three settings the fumarole fields and the basin floor now answer to - a mantle
     * plume, a spreading ridge, a subduction arc - so the places that look geothermal are the places
     * that have the springs to go with it, rather than the two things disagreeing.</p>
     */
    static double geothermalGround(ServerLevel level, int x, int z) {
        double plume = com.jeladastudios.ftsgeology.tectonics.HotspotMap.sample(level, x, z).strength();
        com.jeladastudios.ftsgeology.tectonics.PlateSample plate =
                com.jeladastudios.ftsgeology.tectonics.TectonicMap.sampleCached(level, x, z);
        double boundary = switch (plate.faultType()) {
            case DIVERGENT, CONVERGENT_SUBDUCTION -> plate.stress();
            default -> 0.0;
        };
        // Doubled before clamping, so the full boost covers the working part of a corridor rather
        // than only its centre line. Without this the ramp reached its peak on the fault itself and
        // was already half gone a few dozen blocks out, which averaged to a ninth of what was asked
        // for - a ramp that only touches its maximum at a single point is not really a boost.
        return Math.min(1.0, Math.max(plume, boundary) * 2.0);
    }

    static int generateInChunk(ServerLevel level, LevelChunk chunk) {
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

        // Deep geology is no longer done here. It runs first, from the queue, where it can stop part
        // way through a chunk and resume next tick - or it was already written at generation.

        // The ground over a mantle plume says so, more loudly the closer you get. This is how a
        // hotspot is meant to be found: by reading the landscape rather than by walking twenty
        // thousand blocks and being lucky. Silent everywhere else - one map sample and out.
        HotspotSigns.generate(level, cp, rng);

        // And inside a basin, the ground the springs stand ON. The pools and their colour bands have
        // been right for a while, but each halo stopped ten blocks out and meadow began, so a geyser
        // basin read as hot springs dropped onto a field. The sinter flat is the floor of the whole
        // basin, not a ring around each pool. Silent everywhere else - four map samples and out.
        GeothermalBasin.generate(level, cp, rng);

        // And everywhere the mod has put named rock, the soil that rock weathers into. Four probes
        // and out over ordinary country - see the class note for why that gate matters.
        SoilProfile.generate(level, cp, rng);

        // Surface features do read and write across chunk borders, which can pull a neighbour in.
        // That is fine now: this runs from the tick queue, so a forced load simply queues more work
        // for a later tick instead of recursing. Demanding a fully loaded neighbourhood instead was
        // far too strict - a freshly loaded chunk is almost always at the EDGE of the loaded area,
        // so features stopped generating altogether.

        // Independent surface feature: an occasional hot-spring pool.
        //
        // A quarter denser on genuinely geothermal ground - over a plume, along a spreading ridge,
        // or in a subduction arc. Those are the places the mod now dresses properly, with a sinter
        // floor and fumarole fields, and a field of that kind wants springs in it rather than two;
        // everywhere else keeps the density it had.
        // Ramped by how geothermal the ground is rather than switched on at a line, so the density
        // does not step up as you cross an invisible threshold.
        double springBoost = 1.0 + (GEOTHERMAL_SPRING_BOOST - 1.0) * geothermalGround(level, centreX, centreZ);
        if (rng.nextDouble() < GeyserConfig.HOT_SPRING_SPAWN_CHANCE.get() * fit.hotSpring() * springBoost) {
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
        if (chance <= 0 || rng.nextDouble() >= chance) return 0;

        int localX = rng.nextInt(12) + 2; // keep away from chunk borders (2..13)
        int localZ = rng.nextInt(12) + 2;
        int worldX = cp.getMinBlockX() + localX;
        int worldZ = cp.getMinBlockZ() + localZ;

        // Choose a core Y that leaves room for the chamber below the safety ceiling.
        int coreY = minY + 1;
        int chamberTop = coreY + chamberH; // must stay strictly below maxY
        if (chamberTop >= maxY) return 0;

        BlockPos corePos = new BlockPos(worldX, coreY, worldZ);

        if (!columnIsCarvable(level, corePos, chamberH, maxY)) return 0;

        int magnitude = pickMagnitude(rng);
        buildSystem(level, corePos, chamberH, magnitude, rng, false, true); // natural: build-safe deep shaft + branches
        GeysersMod.LOGGER.debug("Geyser system (magnitude {}) placed at {}", magnitude, corePos);
        return 0;
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
    static void generateHotSpring(ServerLevel level, ChunkPos cp, RandomSource rng) {
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
    static void generateVolcano(ServerLevel level, ChunkPos cp, RandomSource rng) {
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

        // Not on the flank of a large one. The field is pure arithmetic, so this needs no record of
        // where they were built - only the same question the generator asked.
        if (com.jeladastudios.ftsgeology.volcano.VolcanoField.nearLarge(level, x, z, 96)) return;

        int magnitude = 8 + rng.nextInt(12);
        com.jeladastudios.ftsgeology.volcano.VolcanoSize size =
                com.jeladastudios.ftsgeology.volcano.VolcanoSize.forMagnitude(magnitude);
        if (VolcanoBuilder.build(level, summit, magnitude, size)) {
            GeysersMod.LOGGER.debug("Natural {} volcano (magnitude {}) placed at {}", size, magnitude, summit);
        }
    }

    /**
     * Weighted size roll: most vents are small short-lived spouters, a few are mid-sized,
     * and large hour-long geysers are rare landmarks.
     */
    static int pickMagnitude(RandomSource rng) {
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
    static boolean columnIsCarvable(ServerLevel level, BlockPos core, int chamberH, int maxY) {
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

    static void buildSystem(ServerLevel level, BlockPos core, int chamberH, int magnitude,
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
    static void fillLayer(ServerLevel level, BlockPos center, int rad, net.minecraft.world.level.block.Block block) {
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                BlockPos p = center.offset(dx, 0, dz);
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(p))) continue;
                level.setBlock(p, block.defaultBlockState(), 2);
            }
        }
    }

    /** Seals the perimeter ring of a chamber layer with rock where it would otherwise leak (air/fluid). */
    static void ringWall(ServerLevel level, BlockPos center, int r) {
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
    static int carveVentShaft(ServerLevel level, BlockPos core, int chamberH, boolean aggressive) {
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
    static boolean isShaftClearable(BlockState s) {
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
}
