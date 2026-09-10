package com.jeladastudios.ftsgeology.worldgen;

import static com.jeladastudios.ftsgeology.util.ValueNoise.noise3D;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
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
 *   <li><b>Rift</b> - crust pulled thin and intruded from below by bodies of gabbro and basalt,
 *       with hot rock riding high near the axis.</li>
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
 *
 * <h2>Written as the world is generated, where it can be</h2>
 * A chunk made after the mod is installed gets this from {@link GeologyFeature}, inside world
 * generation, where the writes land in a chunk nobody can see yet: no block-change hooks, no
 * lighting, nothing to send to a client, and no share of the server tick. Retrogen still does it for
 * chunks that already existed. Both go through exactly this code.
 */
public final class DeepStructure {

    private DeepStructure() {}

    /** Columns in a chunk, and what {@link #generate} returns once the last of them is done. */
    public static final int DONE = 256;

    /** Builds whatever deep structure this chunk belongs to, in one go. Cheap no-op away from boundaries. */
    public static void generate(WorldGenLevel level, ChunkPos cp, Report report) {
        generate(level, cp, report, 0, Long.MAX_VALUE);
    }

    /**
     * Builds the deep structure of a chunk, or as much of it as fits before {@code deadline}.
     *
     * <h2>Why it can stop part way</h2>
     * A chunk used to be done in one go, with the time budget only looked at between chunks. That is
     * harmless while a couple of thousand rock swaps take well under a millisecond - until something
     * else hooks every block change. On a server running Sable each write carried a physics query, a
     * single chunk took several milliseconds on its own, and the budget could do nothing about it
     * because it was never asked (GitHub #1). The work is column by column anyway, so this returns
     * the column it reached and the caller brings it back next tick.
     *
     * @param report   carries the running block count between calls, so the per-chunk budget still
     *                 holds across a resume, and turns "I could not see anything down there" into a
     *                 number; may be null for a pass that is never interrupted
     * @param start    first column to visit, 0 for a fresh chunk
     * @param deadline {@link System#nanoTime()} to stop at; at least one column is always done
     * @return the next column to visit, or {@link #DONE}
     */
    public static int generate(WorldGenLevel level, ChunkPos cp, Report report, int start, long deadline) {
        if (!GeyserConfig.DEEP_STRUCTURE_ENABLED.get()) {
            if (report != null) report.note = "deep structure disabled in the config";
            return DONE;
        }

        ServerLevel world = level.getLevel();
        PlateSample centre = TectonicMap.sampleCached(world, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
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
            return DONE;
        }

        int floor = level.getMinBuildHeight() + 6;
        int hardCeiling = GeyserConfig.RETROGEN_MAX_Y.get();
        boolean outcrop = GeyserConfig.DEEP_SURFACE_OUTCROP.get();
        int soil = GeyserConfig.DEEP_SOIL_DEPTH.get();
        double faultWidth = GeyserConfig.FAULT_WIDTH.get();
        // The budget is for the whole chunk, so a resumed pass starts with what is left of it.
        int budget = GeyserConfig.DEEP_STRUCTURE_BUDGET.get() - (report != null ? report.blocks : 0);
        long seed = level.getSeed();
        RandomSource rng = RandomSource.create(0L);

        // Scattered visiting order. 97 is coprime with 256, so this walks all 256 columns of the
        // chunk exactly once in an order that jumps around - which is what stops a budget shortfall
        // from turning into a stripe of geology along one edge.
        for (int i = Math.max(0, start); i < DONE && budget > 0; i++) {
            if (i > start && System.nanoTime() >= deadline) return i;
            int k = (i * 97) & 0xFF;
            int x = cp.getMinBlockX() + (k >> 4);
            int z = cp.getMinBlockZ() + (k & 15);
            PlateSample col = TectonicMap.sampleCached(world, x, z);
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

            // Each column rolls its own dice, seeded from the world and the column alone. The chunk
            // used to share one stream, so where a pass stopped changed every column after it: a
            // chunk interrupted by the budget came out different from one that was not, and the copy
            // built at world generation would have disagreed with the copy built by retrogen.
            rng.setSeed(columnSeed(seed, x, z));

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
        return DONE;
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
     * The highest block this column may be given boundary rock at.
     *
     * <p>Measured against this column's own soil AND its four neighbours', taking the lowest. A
     * column's rock top can otherwise stand above the ground NEXT to it on a slope, and the band or
     * dyke filling it then sticks out of the hillside as a free-standing pillar - which is what the
     * stray basalt columns were. Taking the lowest neighbour means boundary rock can never rise
     * above the soil beside it, while a cliff face still shows the whole section in the cut.</p>
     */
    private static int columnTop(WorldGenLevel level, int x, int z, int hardCeiling,
                                 boolean outcrop, int soil) {
        if (!outcrop) return hardCeiling;
        int ground = TerrainProbe.groundY(level, x, z);
        if (ground == Integer.MIN_VALUE) return hardCeiling;
        int lowest = ground;
        for (int[] d : NEIGHBOURS) {
            // A neighbour whose chunk is not there is left out rather than asked for. Asking loads it
            // on the server thread, and at the edge of the loaded area that is a whole chunk load in
            // the middle of a single column, which no time budget can interrupt.
            if (!level.hasChunk((x + d[0]) >> 4, (z + d[1]) >> 4)) continue;
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
    private static int subduction(WorldGenLevel level, int x, int z, PlateSample s,
                                  int floor, int top, double faultWidth, RandomSource rng) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);

        // The slab: dips away from the trench, sweeping the deep half of the column.
        int deepTop = Math.min(top, floor + (int) Math.round((top - floor) * 0.45));
        int slabY = Mth.clamp((int) Math.round(deepTop - across * (deepTop - floor)), floor + 3, deepTop);
        for (int dy = 0, thickness = 3 + rng.nextInt(2); dy < thickness; dy++) {
            // An ophiolite: the slab is old sea floor, so it carries the sea floor's own section -
            // mantle peridotite and serpentinite at the bottom, gabbro, chert and basalt above. Dark
            // rock either way, which is what makes it read against the deepslate around it.
            int y = slabY - dy;
            Block b;
            if (y < floor + 8) {
                b = rng.nextBoolean() ? ModBlocks.PERIDOTITE.get() : ModBlocks.SERPENTINITE.get();
            } else {
                int roll = rng.nextInt(5);
                b = roll == 0 ? ModBlocks.GABBRO.get()
                        : roll == 1 ? ModBlocks.CHERT.get()
                        : roll == 2 ? Blocks.BLACKSTONE : Blocks.BASALT;
            }
            if (set(level, x, y, z, b, top)) placed++;
        }

        // The arc's plutonic root: granite, diorite and gabbro bodies in the upper crust, above the wedge.
        if (across < 0.6) {
            int bodies = 2;
            for (int i = 0; i < bodies; i++) {
                double f = 0.55 + 0.35 * ((i + 0.5) / bodies);
                int centre = (int) Math.round(floor + (top - floor) * f
                        + 4.0 * Math.sin(x * 0.048) + 3.0 * Math.sin(z * 0.037));
                if (rng.nextInt(3) == 0) continue;            // patchy, not a continuous sheet
                for (int dy = 0, thick = 6 + rng.nextInt(5); dy < thick; dy++) {
                    int roll = rng.nextInt(4);
                    Block b = roll == 0 ? Blocks.DIORITE : roll == 1 ? ModBlocks.GABBRO.get() : Blocks.GRANITE;
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
     * Thinned crust intruded from below by bodies of gabbro and basalt.
     *
     * <p>This used to be a dike swarm: a column was either inside a dike, basalt from bedrock to
     * daylight, or it was not. In section that read as vertical one-block stripes a hundred blocks
     * tall, repeating across the whole zone, which is not what a rift looks like from the inside.
     * Magma that stalls in thinned crust collects in pods of bounded height, so that is what this
     * lays down, from a 3D noise field: a deep level of gabbro where the melt crystallised slowly,
     * and a shallower level of basalt around the conduits that fed the surface.</p>
     */
    private static int rift(WorldGenLevel level, int x, int z, PlateSample s,
                            int floor, int top, double faultWidth, RandomSource rng) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);
        if (across > 0.75) return 0;

        // The deep level: plutonic pods from a few blocks above bedrock up to about thirty.
        int deepMinY = floor + 6;
        int deepMaxY = Math.min(top, floor + 32);
        for (int y = deepMinY; y <= deepMaxY; y++) {
            double n = noise3D(x, y, z, 20.0, 10.0);
            if (n <= 0.36 + 0.32 * across) continue;
            Block b;
            if (n > 0.60) {
                b = (y < -20 && rng.nextInt(3) == 0) ? ModBlocks.COOLING_LAVA_CRUST.get() : ModBlocks.GABBRO.get();
            } else if (n > 0.48) {
                b = rng.nextBoolean() ? ModBlocks.GABBRO.get() : Blocks.BASALT;
            } else {
                b = rng.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.BASALT;
            }
            if (set(level, x, y, z, b, top)) placed++;
        }
        // The shallow level: basalt around the conduits, between Y=-15 and Y=18 where the crust
        // allows, and only nearer the axis, where melt still reaches that high.
        int midMinY = Math.max(deepMinY + 10, -15);
        int midMaxY = Math.min(top - 4, 18);
        if (midMaxY > midMinY && across < 0.60) {
            for (int y = midMinY; y <= midMaxY; y++) {
                double n = noise3D(x + 1024, y, z - 1024, 18.0, 9.0);
                if (n <= 0.40 + 0.35 * across) continue;
                Block b;
                if (n > 0.62) {
                    b = rng.nextBoolean() ? ModBlocks.COOLING_LAVA_CRUST.get() : ModBlocks.GABBRO.get();
                } else if (n > 0.50) {
                    b = Blocks.BASALT;
                } else {
                    b = rng.nextBoolean() ? Blocks.BLACKSTONE : Blocks.BASALT;
                }
                if (set(level, x, y, z, b, top)) placed++;
            }
        }

        // Hot rock riding unusually high, because the mantle has come up to meet the thinned crust.
        if (across < 0.6 && rng.nextInt(75) == 0) {
            int y = floor + 4 + rng.nextInt(Math.max(1, (top - floor) / 2));
            if (set(level, x, y, z, Blocks.MAGMA_BLOCK, top)) {
                placed++;
                MagmaSealing.seal(level, new BlockPos(x, y, z), true);
            }
        }
        if (across < 0.45 && rng.nextInt(260) == 0) {
            int y = Mth.clamp(floor + rng.nextInt(14), floor + 2, top - 3);
            placed += blob(level, x, y, z, 2 + rng.nextInt(2), Blocks.MAGMA_BLOCK, top, rng);
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
     * than a neat horizontal sandwich. Marble, gneiss, schist, slate and quartzite are the sequence
     * itself: what limestone, granite, mudstone and sandstone become when a collision buries them.</p>
     */
    private static int collisionRoot(WorldGenLevel level, int x, int z, PlateSample s,
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
            int thick = 5 + rng.nextInt(5);
            for (int dy = 0; dy < thick; dy++) {
                Block b = switch (Math.floorMod(i * 2 + dy, 5)) {
                    case 0 -> ModBlocks.MARBLE.get();
                    case 1 -> ModBlocks.GNEISS.get();
                    case 2 -> ModBlocks.SCHIST.get();
                    case 3 -> ModBlocks.SLATE.get();
                    default -> ModBlocks.QUARTZITE.get();
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
    private static int shearZone(WorldGenLevel level, int x, int z, PlateSample s,
                                 int floor, int top, double faultWidth, RandomSource rng) {
        if (s.faultDistance() > faultWidth * 0.06) return 0;
        int placed = 0;
        for (int y = floor; y <= top; y++) {
            if (rng.nextInt(4) != 0) continue;
            Block b = switch (rng.nextInt(5)) {
                case 0 -> Blocks.GRAVEL;
                case 1 -> ModBlocks.SHALE.get();
                case 2 -> ModBlocks.SLATE.get();
                case 3 -> Blocks.TUFF;
                default -> Blocks.COBBLED_DEEPSLATE;
            };
            if (set(level, x, y, z, b, top)) placed++;
        }
        return placed;
    }

    // === Helpers ============================================================

    /**
     * Dice for one column: the same whichever pass builds it and wherever that pass stopped. Not
     * {@code SeedHash.columnSeed}: this formula laid down the deep rock of existing worlds.
     */
    private static long columnSeed(long worldSeed, int x, int z) {
        long h = worldSeed ^ 0x6A09E667F3BCC909L;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        return h ^ (h >>> 31);
    }

    /** A rough, lobed pocket of one material, used for magma chambers. */
    private static int blob(WorldGenLevel level, int x, int y, int z, int r, Block block,
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
    private static boolean set(WorldGenLevel level, int x, int y, int z, Block block, int top) {
        if (y > top || y <= level.getMinBuildHeight()) return false;
        // A magma pocket near the edge reaches into the next chunk. Never load one to do it.
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        // Already this rock: nothing to write. On the retrogen path every write goes through the live
        // chunk, which other mods hook - Sable ran a physics query on each one - so a write that
        // changes nothing still costs the full price. It matters most when a chunk is gone over
        // again, where almost every column is already what it would become.
        if (s.is(block)) return false;
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
        // Clients told, neighbour shapes not recalculated. Rock swapped for rock has no shape to
        // update, and asking for it made vanilla read all six neighbours - which for a column on the
        // edge of the loaded area meant loading the next chunk on the server thread, part way through
        // one column, where no time budget can reach. Generation never ran those updates either, so
        // this is also what keeps the two paths writing the same thing.
        level.setBlock(p, block.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        return true;
    }
}
