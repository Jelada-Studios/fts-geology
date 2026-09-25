package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import com.jeladastudios.ftsgeology.tectonics.ThermalBiomes;
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

    /** No neighbour shape updates: at the edge of the loaded area they load the next chunk on the server thread. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // === Generation =========================================================

    /** How much denser hot springs get on properly geothermal ground. */
    static final double GEOTHERMAL_SPRING_BOOST = 1.25;

    /**
     * How much denser they get deep on a painted basin floor. It comes on top of the plume's own boost, and
     * above this the colour bands of neighbouring pools run into one another.
     */
    static final double BASIN_SPRING_BOOST = 1.7;
    /** How far past a large volcano's body its heat still floors the hot-spring fit, in blocks. */
    static final int APRON_SPRINGS = 250;
    /** The hot-spring fit on a large volcano's lower flank: a few pools, not a field. */
    static final double BODY_SPRINGS = 0.15;
    /** The chance of a spring in a chunk inside a foot cluster; four or so come of a cluster's fourteen chunks. */
    static final double FOOT_CLUSTER_CHANCE = 0.35;

    /** Springs the surface pass expected since the last report: the sum of its per-chunk chances. */
    static final java.util.concurrent.atomic.DoubleAdder EXPECTED_SPRINGS = new java.util.concurrent.atomic.DoubleAdder();

    /** 0 on ordinary country, rising to 1 over a plume, a spreading ridge or a subduction arc. */
    static double geothermalGround(ServerLevel level, int x, int z) {
        double plume = com.jeladastudios.ftsgeology.tectonics.HotspotMap.sample(level, x, z).strength();
        com.jeladastudios.ftsgeology.tectonics.PlateSample plate =
                com.jeladastudios.ftsgeology.tectonics.TectonicMap.sampleCached(level, x, z);
        double boundary = switch (plate.faultType()) {
            case DIVERGENT, CONVERGENT_SUBDUCTION -> plate.stress();
            default -> 0.0;
        };
        // Doubled before clamping, so the full boost covers a corridor's working width, not only its
        // centre line.
        return Math.min(1.0, Math.max(plume, boundary) * 2.0);
    }

    /**
     * Nanoseconds spent on each part of the surface pass since the last report: suitability, signs, basin,
     * soil, springs, volcanoes. Geysers are what is left of the whole pass.
     */
    static final long[] PART_NANOS = new long[6];

    private static long lap(int part, long since) {
        long now = System.nanoTime();
        PART_NANOS[part] += now - since;
        return now;
    }

    static int generateInChunk(ServerLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        // Mixed rather than the seed with the chunk position XORed in: the legacy generator's first number then
        // barely changed along z, so whole columns of chunks passed the spring roll together and springs stood in rows.
        RandomSource rng = RandomSource.create(com.jeladastudios.ftsgeology.util.SeedHash.mix(
                com.jeladastudios.ftsgeology.util.SeedHash.columnSeed(level.getSeed(), cp.x, cp.z) ^ 0x5F7A1CEL));

        int maxY = GeyserConfig.RETROGEN_MAX_Y.get();   // e.g. -30 (exclusive ceiling)
        int minY = GeyserConfig.RETROGEN_MIN_Y.get();   // e.g. -60
        int chamberH = GeyserConfig.CHAMBER_TARGET_HEIGHT.get();

        // One tectonic sample per chunk decides what belongs here; see GeothermalSuitability.
        long t = System.nanoTime();
        int centreX = cp.getMinBlockX() + 8, centreZ = cp.getMinBlockZ() + 8;
        GeothermalSuitability.Suitability fit = GeyserConfig.TECTONIC_PLACEMENT.get()
                ? GeothermalSuitability.at(level, centreX, centreZ)
                : new GeothermalSuitability.Suitability(1.0, 1.0, 1.0, "Tectonic placement disabled.");
        t = lap(0, t);

        // Deep geology runs first, from the queue, or was already written at generation. So is the
        // painting for any chunk generated since GeologySurfaceFeature existed.
        if (!RetrogenHandler.PAINT_CURRENT.contains(RetrogenHandler.keyOf(level, chunk))) {
            // Fumarole fields over geothermal ground.
            HotspotSigns.generate(level, cp);
            t = lap(1, t);
            // The basin floor the springs stand on.
            GeothermalBasin.generate(level, cp);
            t = lap(2, t);
            // The soil each named rock weathers into; four probes and out over ordinary country.
            SoilProfile.generate(level, cp);
            lap(3, t);
        }

        // Features may read and write across chunk borders; from the tick queue, a forced load only
        // queues more work.

        // An occasional hot spring, up to a quarter denser on geothermal ground, ramped rather than
        // switched on at a line.
        t = System.nanoTime();
        double springBoost = 1.0 + (GEOTHERMAL_SPRING_BOOST - 1.0) * geothermalGround(level, centreX, centreZ);
        // A painted basin floor is meant to be crowded with pools, so there it climbs to BASIN_SPRING_BOOST.
        double floor = net.minecraft.util.Mth.clamp(
                (GeothermalBasin.basin(level, centreX, centreZ) - 0.30) / 0.30, 0.0, 1.0);
        springBoost = Math.max(springBoost, 1.0 + (BASIN_SPRING_BOOST - 1.0) * floor);
        double springFit = fit.hotSpring();
        // A large volcano's body is its own ground; its summit builds any springs it gets. A caldera's floor is a
        // basin. The apron and the country out to APRON_SPRINGS blocks past the body is where springs cluster, as
        // they do round Fuji, Hakone or Beppu: the mountain's own heat carries them there whatever the arc's stress
        // says, so the fit is floored and fades out with distance.
        double margin = com.jeladastudios.ftsgeology.volcano.VolcanoField.bodyMargin(level, centreX, centreZ);
        if (margin < 0) {
            if (com.jeladastudios.ftsgeology.volcano.VolcanoField.onCalderaFloor(level, centreX, centreZ)) {
                // a basin: as it is
            } else if (com.jeladastudios.ftsgeology.volcano.VolcanoField.bodyShare(level, centreX, centreZ) >= 0.5) {
                springFit = BODY_SPRINGS;   // the lower flank: a few, where the heat is nearest the surface
            } else {
                springFit = 0.0;
            }
        } else if (margin < APRON_SPRINGS) {
            springFit = Math.max(springFit, 0.6 * (1.0 - margin / APRON_SPRINGS));
        }
        double springChance = GeyserConfig.HOT_SPRING_SPAWN_CHANCE.get() * springFit * springBoost
                * ThermalBiomes.springScale(level, centreX, centreZ);
        // At a big mountain's foot the springs come in groups: inside a cluster most chunks get one.
        if (com.jeladastudios.ftsgeology.volcano.VolcanoField.footCluster(level, centreX, centreZ)
                <= com.jeladastudios.ftsgeology.volcano.VolcanoField.FOOT_CLUSTER_R) {
            springChance = Math.max(springChance, FOOT_CLUSTER_CHANCE);
        }
        EXPECTED_SPRINGS.add(Math.min(1.0, springChance));
        // The water has to be there too: on a spring line the table reaches the surface and every spring can; the
        // deeper the dry ground above it, the fewer come up. Asked only once the dice have fallen for a spring: a
        // table reading costs a stack of generator columns, and under Terralith's terrain that was two fifths of
        // the server thread when every chunk asked.
        if (rng.nextDouble() < springChance && waterAllows(level, centreX, centreZ, rng)) {
            generateHotSpring(level, cp, rng);
        }
        t = lap(4, t);
        // Volcanoes are rare and only where magma is generated; VolcanoJob spreads the build over ticks.
        if (fit.volcano() > 0 && rng.nextDouble() < GeyserConfig.VOLCANO_SPAWN_CHANCE.get() * fit.volcano()) {
            generateVolcano(level, cp, rng);
        }
        lap(5, t);

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
     * Places a full deep geyser system with its core at {@code corePos}, after the same safety check
     * as natural generation. Prefer {@link #forcePlaceNearSurface} for deliberate placements.
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
     * Builds a geyser just below the surface at this column, for an igniter or the spawn command: a
     * shallow core and chamber and a 1-2 block shaft to daylight. Ignores the deep Y ceiling on purpose.
     *
     * @return true if a geyser was built, false if the column had no room
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
    /** Does the groundwater let a spring come up here: always on a spring line, with falling odds over dry ground. */
    private static boolean waterAllows(ServerLevel level, int x, int z, RandomSource rng) {
        if (!GeyserConfig.WATER_TABLE_ENABLED.get()) return true;
        com.jeladastudios.ftsgeology.hydrology.WaterTable.Sample table =
                com.jeladastudios.ftsgeology.hydrology.WaterTable.sampleCached(level, x, z);
        if (table.isSpringLine(level.getSeaLevel())) return true;
        return rng.nextDouble() < net.minecraft.util.Mth.clamp(1.0 - table.depthToWater() / 24.0, 0.3, 1.0);
    }

    static void generateHotSpring(ServerLevel level, ChunkPos cp, RandomSource rng) {
        placeHotSpringAt(level, cp.getMinBlockX() + rng.nextInt(12) + 2, cp.getMinBlockZ() + rng.nextInt(12) + 2);
    }

    /**
     * Attempts a natural volcano in this chunk. Only called where the tectonic model generates magma,
     * so collision belts and strike-slip faults have none. {@link VolcanoBuilder#build} refuses sites
     * without room for a cone.
     */
    static void generateVolcano(ServerLevel level, ChunkPos cp, RandomSource rng) {
        int x = cp.getMinBlockX() + rng.nextInt(12) + 2;
        int z = cp.getMinBlockZ() + rng.nextInt(12) + 2;
        // Real ground, not the tree canopy.
        int summitY = TerrainProbe.groundY(level, x, z);
        if (summitY == Integer.MIN_VALUE) return;
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;   // never in a lake or the sea
        if (summitY <= level.getMinBuildHeight() + 20) return;
        BlockPos summit = new BlockPos(x, summitY, z);
        if (EruptionHandler.isPlayerPlaced(level.getBlockState(summit))) return;

        // Not on the flank of a large one; the field is pure arithmetic, so no record is needed.
        if (com.jeladastudios.ftsgeology.volcano.VolcanoField.nearLarge(level, x, z, 96)) return;

        // The cone's size follows the heat under it: a busy arc or rift builds big, a plume bigger, a quiet
        // margin small.
        double stress = com.jeladastudios.ftsgeology.tectonics.TectonicMap.sampleCached(level, x, z).stress();
        double plume = com.jeladastudios.ftsgeology.tectonics.HotspotMap.plumeStrength(level, x, z);
        int magnitude = Math.min(19, 8 + (int) Math.round(8 * stress) + rng.nextInt(4) + (int) Math.round(4 * plume));
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
            if (dy == chamberH + 1 && !(s.is(Blocks.DEEPSLATE) || s.is(Blocks.STONE) || com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.isRock(s))) {
                return false; // need a real rock cap on top
            }
        }
        return true;
    }

    static void buildSystem(ServerLevel level, BlockPos core, int chamberH, int magnitude,
                                    RandomSource rng, boolean aggressiveShaft, boolean growBranches) {
        // A water basin over a heat bed, capped by rock; width and depth scale with magnitude.
        int rad = Mth.clamp(magnitude / 5, 1, 3);          // 1 -> 3x3, 3 -> 7x7
        // Mostly water with just a shallow air gap under the cap — a water reservoir, not an air
        // tank — so V_su stays high and pressure actually builds.
        int waterDepth = Math.max(1, chamberH - 1);

        // 1. Containment floor, then a solid magma heat bed under the core. Fluid lava would mix with
        //    the chamber water or drain away; magma is inert and reads as full heat.
        fillLayer(level, core.below(2), rad + 1, Blocks.DEEPSLATE);
        fillLayer(level, core.below(1), rad, Blocks.MAGMA_BLOCK);
        // Skin the bed's open faces, so a nearby cave does not show a slab of magma.
        MagmaSealing.sealSlab(level, core.below(1), rad);

        // 2. Core level: rock ring separating lava from water, with the core at its centre.
        fillLayer(level, core, rad, Blocks.DEEPSLATE);
        level.setBlock(core, ModBlocks.GEYSER_CORE.get().defaultBlockState(), FLAGS);
        GeyserCoreBlockEntity coreBe =
                level.getBlockEntity(core) instanceof GeyserCoreBlockEntity be ? be : null;
        if (coreBe != null) coreBe.setMagnitude(magnitude);

        // 3. Water basin, then an air gap, walled against caves. No cap: the chamber opens into the vent.
        for (int dy = 1; dy <= chamberH; dy++) {
            fillLayer(level, core.above(dy), rad, dy <= waterDepth ? Blocks.WATER : Blocks.AIR);
            ringWall(level, core.above(dy), rad + 1);
        }

        // 4. No pre-carved shaft: during eruptions VentPathfinder bores up a few blocks a second,
        //    erupting into any cave on the way, up to a fixed ceiling of the original ground plus a
        //    short chimney. ({@code aggressiveShaft} is unused but kept for the API.)
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
                // The heat bed stays vanilla magma, which is what the cores read as heat; it is sealed out of sight.
                BlockState put = block == Blocks.MAGMA_BLOCK ? block.defaultBlockState()
                        : com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, p, block.defaultBlockState());
                level.setBlock(p, put, FLAGS);
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
                    level.setBlock(p, com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, p, Blocks.DEEPSLATE.defaultBlockState()), FLAGS);
                }
            }
        }
    }

    /**
     * Carves a one-wide vent from above the rock cap toward the surface, through terrain and
     * vegetation, stopping at a built block. Returns the highest cell cleared, or
     * {@link Integer#MIN_VALUE}; the eruption pathfinder bores the rest.
     */
    static int carveVentShaft(ServerLevel level, BlockPos core, int chamberH, boolean aggressive) {
        if (!GeyserConfig.CARVE_SURFACE_SHAFT.get()) return Integer.MIN_VALUE;

        int startY = core.getY() + chamberH + 1; // straight above the open chamber top
        int groundTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, core.getX(), core.getZ()) - 1;
        if (groundTop <= startY) return Integer.MIN_VALUE; // already open, or too shallow to bother

        // At least 500 blocks whatever the config says, so a tall peak cannot trap the vent.
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
                level.setBlock(m.immutable(), Blocks.AIR.defaultBlockState(), FLAGS);
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
