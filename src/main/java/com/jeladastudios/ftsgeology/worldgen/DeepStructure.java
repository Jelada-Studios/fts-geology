package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gives plate boundaries the deep structure they have in reality, so digging near a fault feels
 * different from digging anywhere else.
 *
 * <ul>
 *   <li><b>Subduction</b> - a slab of cold dense crust diving under the overriding plate, with magma
 *       chambers in the mantle wedge above it and the volcanic arc's own plutonic root higher up.
 *       This is the Wadati-Benioff zone.</li>
 *   <li><b>Rift</b> - crust pulled thin and split by a swarm of near-vertical basalt <b>dykes</b>
 *       that cut the whole column, with open fractures and hot rock near the axis.</li>
 *   <li><b>Collision</b> - a thickened root of FOLDED metamorphic rock and pointedly no magma:
 *       crumpling two continents together makes mountains, not melt.</li>
 *   <li><b>Transform</b> - a narrow, near-vertical scar of shattered rock where the plates grind.</li>
 * </ul>
 *
 * <h2>It reaches daylight now</h2>
 * All of this used to be squeezed between Y=-58 and Y=-30 - a 28-block window in a 384-block world,
 * which was trivially easy to tunnel straight past and conclude nothing had generated. Real boundary
 * rock does not stop at a depth: a collision root outcrops in mountainsides, a dyke swarm cuts the
 * entire crust, a strike-slip damage zone is a scar you can walk along.
 *
 * <p>So the structure now spans from bedrock to the surface - except for the top
 * {@code deepStructureSoilDepth} blocks of every column, which are left exactly as the terrain
 * generator made them. Meadows still look like meadows, and the geology shows in every cliff face,
 * ravine wall, cave and mine shaft. That is also what soil does in reality: it covers the bedrock
 * everywhere, and you only see what is underneath where something has cut through.</p>
 *
 * <p>Player blocks are never replaced, and nothing is ever added above a column's own surface, so
 * this can neither break a build nor change the skyline.</p>
 */
public final class DeepStructure {

    private DeepStructure() {}

    /** Builds whatever deep structure this chunk belongs to. Cheap no-op away from boundaries. */
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng) {
        generate(level, cp, rng, null);
    }

    /**
     * As above, but optionally reports what it did. The report is what turns "I could not see
     * anything down there" into a number, so a missing structure can be told apart from one that is
     * present and merely hard to find.
     */
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng, Report report) {
        if (!GeyserConfig.DEEP_STRUCTURE_ENABLED.get()) {
            if (report != null) report.note = "deep structure disabled in the config";
            return;
        }

        PlateSample centre = TectonicMap.sampleCached(level, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
        if (report != null) {
            report.type = centre.faultType().toString();
            report.stress = centre.stress();
        }
        // Cheap early out, and ONLY that.
        //
        // This used to be the real decision: one sample at the chunk's centre, and if its stress
        // was under 0.25 the whole chunk got nothing. A chunk at 0.251 was fully built and its
        // neighbour at 0.249 was untouched, which drew a hard, chunk-aligned edge across the
        // landscape - sixteen blocks of geology, then sixteen blocks of none. Reported three times
        // as "the separate basalt wall outside the volcano"; it was never the volcano.
        //
        // The gate is per column now, further down, and it fades rather than cuts. The margin here
        // is generous because a chunk's corner can be a good deal more stressed than its middle.
        if (centre.stress() < 0.10) {
            if (report != null) report.note = "stress below 0.10 across the chunk - deep interior";
            return;
        }

        int floor = level.getMinBuildHeight() + 6;
        int hardCeiling = GeyserConfig.RETROGEN_MAX_Y.get();
        boolean outcrop = GeyserConfig.DEEP_SURFACE_OUTCROP.get();
        int soil = GeyserConfig.DEEP_SOIL_DEPTH.get();
        double faultWidth = GeyserConfig.FAULT_WIDTH.get();
        int budget = GeyserConfig.DEEP_STRUCTURE_BUDGET.get();

        // Scattered visiting order. 97 is coprime with 256, so this walks all 256 columns of the
        // chunk exactly once in an order that jumps around - which is what stops a budget shortfall
        // from turning into a stripe of geology along one edge.
        for (int i = 0; i < 256 && budget > 0; i++) {
            int k = (i * 97) & 0xFF;
            int x = cp.getMinBlockX() + (k >> 4);
            int z = cp.getMinBlockZ() + (k & 15);
            PlateSample col = TectonicMap.sampleCached(level, x, z);
            if (col.faultType() == com.jeladastudios.ftsgeology.tectonics.FaultType.INTERIOR) continue;
            if (col.stress() < 0.25) continue;   // this column is too far from the boundary

            // How much soil this column keeps over its bedrock, faded by stress.
            //
            // A fixed depth plus a hard stress cut is what produced the wall. Rock that simply
            // retreats deeper as the boundary weakens has no edge at all: at the fault it reaches
            // the surface, and a few hundred blocks out it is buried far enough that nothing shows
            // until something cuts through. Same total structure, no line across the map.
            int localSoil = outcropDepth(col.stress(), soil);
            int top = columnTop(level, x, z, hardCeiling, outcrop, localSoil);
            if (top <= floor + 4) continue;

            int placed = switch (col.faultType()) {
                case CONVERGENT_SUBDUCTION -> subduction(level, x, z, col, floor, top, faultWidth, rng);
                case DIVERGENT -> rift(level, x, z, col, floor, top, faultWidth, rng);
                case CONVERGENT_COLLISION -> collisionRoot(level, x, z, col, floor, top, faultWidth, rng);
                case TRANSFORM -> shearZone(level, x, z, col, floor, top, faultWidth, rng);
                default -> 0;
            };
            budget -= placed;
            if (report != null) {
                report.blocks += placed;
                if (placed > 0) {
                    report.lowest = Math.min(report.lowest, floor);
                    report.highest = Math.max(report.highest, top);
                }
            }
        }
        if (report != null && report.blocks == 0 && report.note == null) {
            report.note = "budget exhausted or nothing matched in this chunk";
        }
    }

    /** What one chunk's worth of generation actually did, for the inspection command. */
    public static final class Report {
        public String type = "?";
        public double stress;
        public int blocks;
        public int lowest = Integer.MAX_VALUE;
        public int highest = Integer.MIN_VALUE;
        public String note;
    }

    /**
    /**
     * The highest block this column may be given boundary rock at.
     *
     * <p>Measured against this column's own soil AND its four neighbours', taking the lowest. A
     * column's rock top can otherwise stand above the ground NEXT to it on a slope, and the band or
     * dyke filling it then sticks out of the hillside as a free-standing pillar - which is what the
     * stray basalt columns were. Taking the lowest neighbour means boundary rock can never rise
     * above the soil beside it, while a cliff face still shows the whole section in the cut.</p>
     */
    private static int columnTop(ServerLevel level, int x, int z, int hardCeiling,
                                 boolean outcrop, int soil) {
        if (!outcrop) return hardCeiling;
        int ground = TerrainProbe.groundY(level, x, z);
        if (ground == Integer.MIN_VALUE) return hardCeiling;
        int lowest = ground;
        for (int[] d : NEIGHBOURS) {
            int n = TerrainProbe.groundY(level, x + d[0], z + d[1]);
            if (n != Integer.MIN_VALUE) lowest = Math.min(lowest, n);
        }
        return lowest - soil;
    }

    private static final int[][] NEIGHBOURS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    /**
     * How deep the soil cover is for a column of this stress: the configured minimum right on an
     * active boundary, deepening to {@link #BURIED_SOIL} as the stress falls away to the 0.25 floor.
     *
     * <p>This is the whole fix for the hard edge. The rock is still generated either way - the
     * question is only how far under the surface its top sits, and pushing that down smoothly means
     * the transition from "outcrops in the meadow" to "invisible without digging" happens over
     * hundreds of blocks instead of at one chunk border. It is also what actually happens: bedrock
     * is everywhere, and you see it where erosion or tectonics has stripped the cover off.</p>
     */
    private static int outcropDepth(double stress, int minSoil) {
        double t = Mth.clamp((stress - 0.25) / 0.75, 0.0, 1.0);
        return (int) Math.round(BURIED_SOIL + (minSoil - BURIED_SOIL) * t);
    }

    /**
     * Soil depth at the quiet end of the fade. Deep enough that the boundary rock is out of sight
     * from the surface, shallow enough that a ravine or a mine still cuts into it.
     */
    private static final int BURIED_SOIL = 24;

    /**
     * The descending slab, the mantle wedge above it, and the arc's plutonic root.
     *
     * <p>The slab itself stays deep, because in reality it is deep - that is the whole point of a
     * Wadati-Benioff zone. What reaches the upper crust is the arc's plumbing: bodies of coarse
     * intrusive rock that cooled from the same magma that feeds the volcanoes, plus the basalt dykes
     * that carried it. Digging under a volcanic arc should find granite, and it now does.</p>
     */
    private static int subduction(ServerLevel level, int x, int z, PlateSample s,
                                  int floor, int top, double faultWidth, RandomSource rng) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);

        // The slab: dips away from the trench, sweeping the deep half of the column.
        int deepTop = Math.min(top, floor + (int) Math.round((top - floor) * 0.45));
        int slabY = Mth.clamp((int) Math.round(deepTop - across * (deepTop - floor)), floor + 3, deepTop);
        for (int dy = 0, thickness = 3 + rng.nextInt(2); dy < thickness; dy++) {
            // Basalt and blackstone, not deepslate: at this depth the surrounding rock IS deepslate,
            // so a deepslate slab was perfectly invisible. Dark volcanic rock reads instantly.
            Block b = rng.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.BASALT;
            if (set(level, x, slabY - dy, z, b, top)) placed++;
        }

        // The arc's plutonic root: granite and diorite bodies in the upper crust, above the wedge.
        if (across < 0.6) {
            int bodies = 2;
            for (int i = 0; i < bodies; i++) {
                double f = 0.55 + 0.35 * ((i + 0.5) / bodies);
                int centre = (int) Math.round(floor + (top - floor) * f
                        + 4.0 * Math.sin(x * 0.048) + 3.0 * Math.sin(z * 0.037));
                if (rng.nextInt(4) == 0) continue;            // patchy, not a continuous sheet
                for (int dy = 0, thick = 2 + rng.nextInt(3); dy < thick; dy++) {
                    Block b = rng.nextInt(3) == 0 ? Blocks.DIORITE : Blocks.GRANITE;
                    if (set(level, x, centre - dy, z, b, top)) placed++;
                }
            }
        }

        // Magma chambers in the mantle wedge above the slab, near the arc.
        if (across < 0.6 && rng.nextInt(150) == 0) {
            int chamberY = Mth.clamp(slabY + 8 + rng.nextInt(8), floor + 2, deepTop);
            placed += blob(level, x, chamberY, z, 2 + rng.nextInt(2), Blocks.MAGMA_BLOCK, top, rng);
        }
        return placed;
    }

    /**
     * Thinned, fractured crust cut by a <b>dyke swarm</b>.
     *
     * <p>A dyke is a sheet of magma frozen in a crack, and at a rift they come in parallel swarms
     * running along the axis - Iceland is built of them. A single column is therefore either inside
     * a dyke, in which case it is basalt from bedrock to daylight, or it is not. That is what makes
     * a rift instantly recognisable in section, and it is far cheaper than filling the whole zone.</p>
     */
    private static int rift(ServerLevel level, int x, int z, PlateSample s,
                            int floor, int top, double faultWidth, RandomSource rng) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);

        // Distance to the boundary, not a projection onto the fault normal.
        //
        // The normal turns as you move, so its level sets are not parallel lines - around a plate
        // centre they fan out, and the swarm came out as a radial starburst on the sea floor. Level
        // sets of the DISTANCE are parallel to the boundary by definition, which is also how a real
        // dyke swarm lies: sheets along the rift axis. A little along-strike wobble keeps them from
        // being perfect arcs.
        double alongStrike = x * s.faultStrikeX() + z * s.faultStrikeZ();
        double u = s.faultDistance() + 3.0 * Math.sin(alongStrike * 0.035);
        double phase = Math.sin(u * 0.52) + 0.4 * Math.sin(u * 1.31 + 2.0);
        boolean inDyke = phase > 1.05 - 0.35 * (1.0 - across);
        if (inDyke) {
            for (int y = floor; y <= top; y++) {
                if (rng.nextInt(14) == 0) continue;           // the odd break in the sheet
                Block b = rng.nextInt(4) == 0 ? Blocks.BLACKSTONE : Blocks.BASALT;
                if (set(level, x, y, z, b, top)) placed++;
            }
        }

        // Open fractures on the axis: the crust really has parted here.
        if (across < 0.35 && rng.nextInt(9) == 0) {
            int height = 6 + rng.nextInt(18);
            int base = floor + rng.nextInt(Math.max(1, (top - floor) - height));
            for (int y = base; y < base + height; y++) {
                if (set(level, x, y, z, Blocks.AIR, top)) placed++;
            }
        }
        // Hot rock riding unusually high, because the mantle has come up to meet the thinned crust.
        if (across < 0.7 && rng.nextInt(60) == 0) {
            int y = floor + rng.nextInt(Math.max(1, (top - floor) / 2));
            if (set(level, x, y, z, Blocks.MAGMA_BLOCK, top)) {
                placed++;
                MagmaSealing.seal(level, new BlockPos(x, y, z), true);
            }
        }
        if (across < 0.5 && rng.nextInt(220) == 0) {
            int y = Mth.clamp(floor + rng.nextInt(12), floor + 2, top - 3);
            placed += blob(level, x, y, z, 2, Blocks.MAGMA_BLOCK, top, rng);
        }
        return placed;
    }

    /**
     * The crustal root under a collision belt: metamorphic rock pushed far deeper than normal, and
     * deliberately without a trace of magma - the Himalaya has the thickest crust on Earth and not
     * one volcano.
     *
     * <p>The banding is <b>folded</b> rather than flat. Collision does not lay rock down in layers,
     * it takes layers that already existed and crumples them, so a cliff face cuts wavy bands rather
     * than a neat horizontal sandwich. Calcite, tuff and diorite stand in for marble, gneiss and the
     * granitic sheets that intrude them.</p>
     */
    private static int collisionRoot(ServerLevel level, int x, int z, PlateSample s,
                                     int floor, int top, double faultWidth, RandomSource rng) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);
        // The fold: the whole sequence rises and falls across the landscape on three wavelengths.
        double fold = 5.0 * Math.sin(x * 0.055) + 4.0 * Math.sin(z * 0.041)
                + 2.5 * Math.sin((x + z) * 0.017);

        int bands = 3 + rng.nextInt(2);
        for (int i = 0; i < bands; i++) {
            // Thinning outward: at the margin of the belt only the deepest bands survive.
            if (rng.nextDouble() < across * (i / (double) bands)) continue;
            double f = (i + 0.5) / bands;
            int centre = (int) Math.round(floor + (top - floor) * f + fold);
            int thick = 2 + rng.nextInt(3);
            for (int dy = 0; dy < thick; dy++) {
                Block b = switch (Math.floorMod(i * 2 + dy, 5)) {
                    case 0, 3 -> Blocks.CALCITE;
                    case 1 -> Blocks.DIORITE;
                    default -> Blocks.TUFF;
                };
                if (set(level, x, centre - dy, z, b, top)) placed++;
            }
        }
        return placed;
    }

    /**
     * The shear zone: a narrow, near-vertical scar of shattered rock where the plates grind past one
     * another. No melt, because sliding sideways generates none.
     *
     * <p>Deliberately narrow. A strike-slip damage zone is a small fraction of the boundary's width
     * in reality, and keeping it that way is also what lets it run the full height of the crust
     * without costing more than the wide, shallow structures do.</p>
     */
    private static int shearZone(ServerLevel level, int x, int z, PlateSample s,
                                 int floor, int top, double faultWidth, RandomSource rng) {
        if (s.faultDistance() > faultWidth * 0.06) return 0;
        int placed = 0;
        for (int y = floor; y <= top; y++) {
            if (rng.nextInt(4) != 0) continue;
            Block b = switch (rng.nextInt(4)) {
                case 0 -> Blocks.GRAVEL;
                case 1 -> Blocks.TUFF;
                default -> Blocks.COBBLED_DEEPSLATE;
            };
            if (set(level, x, y, z, b, top)) placed++;
        }
        return placed;
    }

    // === Helpers ============================================================

    /** A rough, lobed pocket of one material, used for magma chambers. */
    private static int blob(ServerLevel level, int x, int y, int z, int r, Block block,
                            int top, RandomSource rng) {
        int placed = 0;
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        // Lobed rather than spherical: a smooth ball of magma blocks reads as a placed object, and
        // the flat-sided result was what players were seeing as "cubes of magma" in cave walls.
        double px = rng.nextDouble() * Math.PI * 2, pz = rng.nextDouble() * Math.PI * 2;
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r - 1; dz <= r + 1; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double ang = Math.atan2(dz, dx);
                    double rr = r * (1.0 + 0.3 * Math.sin(2 * ang + px) + 0.2 * Math.sin(3 * dy + pz));
                    if (dist > rr) continue;
                    if (set(level, x + dx, y + dy, z + dz, block, top)) {
                        placed++;
                        cells.add(new BlockPos(x + dx, y + dy, z + dz));
                    }
                }
            }
        }
        // Never let a chamber glow out of a cave wall.
        if (block == Blocks.MAGMA_BLOCK) {
            for (BlockPos c : cells) MagmaSealing.seal(level, c, true);
        }
        return placed;
    }

    /** Writes one block, refusing to breach this column's ceiling, bedrock, or anything a player made. */
    private static boolean set(ServerLevel level, int x, int y, int z, Block block, int top) {
        if (y > top || y <= level.getMinBuildHeight()) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return false;
        // Never eat something the mod itself relies on. A geyser is heated by a slab of magma and
        // driven by a block entity; replacing either with metamorphic banding would silently kill it,
        // which matters on the retrofit path where deep structure can run over ground that already
        // has a geyser system in it.
        if (s.is(Blocks.MAGMA_BLOCK) || s.hasBlockEntity()) return false;
        // Only ever replace rock. Air, water and lava are left alone so caves, aquifers and the
        // shape of the terrain are untouched - the structure shows IN a cave wall, it does not
        // fill the cave in.
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        level.setBlock(p, block.defaultBlockState(), 2);
        return true;
    }
}
