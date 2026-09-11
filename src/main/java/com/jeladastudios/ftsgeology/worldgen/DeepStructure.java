package com.jeladastudios.ftsgeology.worldgen;

import static com.jeladastudios.ftsgeology.util.ValueNoise.lattice3D;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise3D;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.SeedHash;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Gives plate boundaries the deep structure they have in reality, so digging near a fault feels
 * different from digging anywhere else.
 *
 * <ul>
 *   <li><b>Subduction</b> - a slab of old sea floor diving under the overriding plate with its layers in order,
 *       magma chambers in the mantle wedge above it, and the arc's plutons higher up.</li>
 *   <li><b>Rift</b> - thinned crust intruded from below by gabbro and basalt.</li>
 *   <li><b>Collision</b> - a thickened root of folded metamorphic units and no magma.</li>
 *   <li><b>Transform</b> - a clay gouge and breccia core on the fault, with sheared slate beside it.</li>
 * </ul>
 *
 * <p>Rock comes in bodies, as it does underground. Which rock goes where is read off noise fields over the
 * block's position, so a pluton or a unit is one rock for tens of blocks and carries on across chunk borders.
 * Dice are kept for the rare magma pockets.</p>
 *
 * <p>Spans bedrock to the surface, except a soil cover left as the generator made it, so the
 * geology shows in cliffs, ravines and caves. Player blocks and ore are never replaced and nothing is added
 * above a column's surface. New chunks get this from {@link GeologyFeature} during generation;
 * retrogen runs the same code for chunks that already existed.</p>
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
     * Builds the deep structure of a chunk, or as much as fits before {@code deadline}, column by
     * column, so a slow block-change hook (Sable, GitHub #1) cannot hold up a tick.
     *
     * @param report   running block count across calls; may be null for a pass never interrupted
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
        // Cheap early out only; the real gate is per column and fades. Generous, because a chunk's
        // corner can be more stressed than its middle.
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
        int o = fieldOffset(seed);
        RandomSource rng = RandomSource.create(0L);
        Map<Long, Double> corners = new HashMap<>();

        // 97 is coprime with 256: every column once, in a scattered order, so a budget stop leaves no stripe.
        for (int i = Math.max(0, start); i < DONE && budget > 0; i++) {
            if (i > start && System.nanoTime() >= deadline) return i;
            int k = (i * 97) & 0xFF;
            int x = cp.getMinBlockX() + (k >> 4);
            int z = cp.getMinBlockZ() + (k & 15);
            PlateSample col = TectonicMap.sampleCached(world, x, z);
            if (col.faultType() == com.jeladastudios.ftsgeology.tectonics.FaultType.INTERIOR) continue;
            if (col.stress() < 0.25) continue;   // this column is too far from the boundary

            // Soil cover over the rock, deepening as stress falls, so the structure fades out without an edge.
            int localSoil = outcropDepth(col.stress(), soil);
            int top = columnTop(level, x, z, hardCeiling, outcrop, localSoil);
            if (top <= floor + 4) continue;

            // Dice seeded per column, so where a pass stopped cannot change the result.
            rng.setSeed(columnSeed(seed, x, z));

            int placed = switch (col.faultType()) {
                case CONVERGENT_SUBDUCTION -> subduction(level, x, z, col, floor, top, faultWidth, o, rng);
                case DIVERGENT -> rift(level, x, z, col, floor, top, faultWidth, o, rng);
                case CONVERGENT_COLLISION -> collisionRoot(level, x, z, col, floor, top, faultWidth, o);
                case TRANSFORM -> shearZone(level, world, x, z, col, floor, top, faultWidth, o, corners);
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
     * The highest block this column may be given boundary rock at: the lowest of its own and its
     * four neighbours' soil tops, so rock never sticks out of a gentle hillside as a pillar. On a steep
     * face erosion has stripped the soil, so there the rock comes up to just under the surface and the
     * whole cliff shows it.
     */
    private static int columnTop(WorldGenLevel level, int x, int z, int hardCeiling,
                                 boolean outcrop, int soil) {
        if (!outcrop) return hardCeiling;
        int ground = TerrainProbe.groundY(level, x, z);
        if (ground == Integer.MIN_VALUE) return hardCeiling;
        int lowest = ground, highest = ground;
        for (int[] d : NEIGHBOURS) {
            // A neighbour whose chunk is not loaded is left out; asking would load it.
            if (!level.hasChunk((x + d[0]) >> 4, (z + d[1]) >> 4)) continue;
            int n = TerrainProbe.groundY(level, x + d[0], z + d[1]);
            if (n == Integer.MIN_VALUE) continue;
            lowest = Math.min(lowest, n);
            highest = Math.max(highest, n);
        }
        if (highest - lowest >= STEEP) return ground - 1;
        return lowest - soil;
    }

    /** Height difference across a column's neighbours at which the ground counts as a bare face. */
    private static final int STEEP = 3;

    private static final int[][] NEIGHBOURS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    /**
     * Soil depth for a column of this stress: the configured minimum on an active boundary,
     * deepening to {@link #BURIED_SOIL} as stress falls to the 0.25 floor.
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
     * The descending slab, the mantle wedge above it, and the arc's plutonic root: granite, diorite and
     * gabbro bodies in the upper crust.
     */
    private static int subduction(WorldGenLevel level, int x, int z, PlateSample s,
                                  int floor, int top, double faultWidth, int o, RandomSource rng) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);

        // The slab: dips away from the trench, sweeping the deep half of the column. It is old sea floor, an
        // ophiolite, with its layers in the order they formed: mantle peridotite at the base, serpentinite where
        // sea water got into it, gabbro, the basalt of its dykes and pillows, and deep-sea chert on top. It
        // swells and thins over tens of blocks rather than column by column.
        int deepTop = Math.min(top, floor + (int) Math.round((top - floor) * 0.45));
        int slabY = Mth.clamp((int) Math.round(deepTop - across * (deepTop - floor)), floor + 3, deepTop);
        int thickness = 6 + (int) Math.round(1.5 + 1.5 * noise(x + o, z - o, 40.0));
        for (int dy = 0; dy < thickness; dy++) {
            int y = slabY - dy;
            Block b;
            if (thickness - 1 - dy <= 1) {
                b = noise3D(x - o, y, z + o, 18.0, 6.0) > 0.1 ? ModBlocks.SERPENTINITE.get() : ModBlocks.PERIDOTITE.get();
            } else if (dy == 0) {
                b = noise(x + 2 * o, z, 24.0) > -0.2 ? ModBlocks.CHERT.get() : Blocks.BLACKSTONE;
            } else if (dy <= 2) {
                b = noise3D(x, y + o, z, 16.0, 8.0) > 0.3 ? Blocks.BLACKSTONE : Blocks.BASALT;
            } else {
                b = ModBlocks.GABBRO.get();
            }
            if (set(level, x, y, z, b, top)) placed++;
        }

        // The arc's plutonic root in the upper crust, above the wedge: separate bodies, each one rock for tens of
        // blocks - granite, diorite or gabbro - thickest in the middle, with a contact that wanders a block.
        if (across < 0.6) {
            double body = noise(x - 3 * o, z + 3 * o, 56.0) - across * 0.5;
            if (body > 0.05) {
                int thick = 4 + (int) Math.round(10.0 * Math.min(1.0, (body - 0.05) / 0.5));
                int centre = (int) Math.round(floor + (top - floor) * 0.72
                        + 4.0 * Math.sin(x * 0.048) + 3.0 * Math.sin(z * 0.037));
                for (int dy = -thick / 2; dy < thick - thick / 2; dy++) {
                    int y = centre + dy;
                    double kind = noise3D(x + 4 * o, y, z - 4 * o, 44.0, 22.0) + 0.07 * lattice3D(x, y + o, z);
                    Block b = kind < -0.2 ? ModBlocks.GABBRO.get() : kind < 0.15 ? Blocks.DIORITE : Blocks.GRANITE;
                    if (set(level, x, y, z, b, top)) placed++;
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
     * Thinned crust intruded from below: pods of bounded height from a 3D noise field, gabbro at
     * the deep level and basalt around the conduits higher up.
     */
    private static int rift(WorldGenLevel level, int x, int z, PlateSample s,
                            int floor, int top, double faultWidth, int o, RandomSource rng) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);
        if (across > 0.75) return 0;

        // The deep level: plutonic pods from a few blocks above bedrock up to about thirty. Which rock inside a
        // pod comes from a second, finer field, so gabbro and basalt lie in patches rather than grains.
        int deepMinY = floor + 6;
        int deepMaxY = Math.min(top, floor + 32);
        for (int y = deepMinY; y <= deepMaxY; y++) {
            double n = noise3D(x, y, z, 20.0, 10.0);
            if (n <= 0.36 + 0.32 * across) continue;
            double m = rockField(x, y, z, o);
            Block b;
            if (n > 0.60) {
                b = (y < -20 && m > 0.35) ? ModBlocks.COOLING_LAVA_CRUST.get() : ModBlocks.GABBRO.get();
            } else if (n > 0.48) {
                b = m > 0.0 ? ModBlocks.GABBRO.get() : Blocks.BASALT;
            } else {
                b = m > 0.4 ? Blocks.BLACKSTONE : Blocks.BASALT;
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
                double m = rockField(x + 512, y, z - 512, o);
                Block b;
                if (n > 0.62) {
                    b = m > 0.0 ? ModBlocks.COOLING_LAVA_CRUST.get() : ModBlocks.GABBRO.get();
                } else if (n > 0.50) {
                    b = Blocks.BASALT;
                } else {
                    b = m > 0.0 ? Blocks.BLACKSTONE : Blocks.BASALT;
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
     * The crustal root under a collision belt: metamorphic rock pushed deep, with no magma, in three folded
     * units. Each unit is one rock through its thickness and for tens of blocks along the belt, as a mapped
     * sequence runs: gneiss or schist at the bottom, cooked hardest; schist or marble; and slate, quartzite
     * or marble near the top, from the mud, sand and limestone they started as.
     */
    private static int collisionRoot(WorldGenLevel level, int x, int z, PlateSample s,
                                     int floor, int top, double faultWidth, int o) {
        int placed = 0;
        double across = Mth.clamp(s.faultDistance() / faultWidth, 0.0, 1.0);
        // The fold: the whole sequence rises and falls across the landscape on three wavelengths.
        double fold = 5.0 * Math.sin(x * 0.055) + 4.0 * Math.sin(z * 0.041)
                + 2.5 * Math.sin((x + z) * 0.017);

        for (int unit = 0; unit < 3; unit++) {
            // Pinching out in lenses, and thinning towards the margin of the belt, the upper units first.
            double lens = noise(x + o + unit * 7919, z - o, 64.0);
            if (lens < -0.15 + across * (0.5 + 0.35 * unit)) continue;
            int thick = 4 + (int) Math.round(2.5 + 2.5 * noise(x - o - unit * 977, z + o, 36.0));
            int centre = (int) Math.round(floor + (top - floor) * (0.25 + 0.25 * unit) + fold);
            Block rock = unitRock(unit, x, z, o);
            Block leaf = unitRock(unit == 2 ? 1 : unit + 1, x, z, o);
            for (int dy = 0; dy < thick; dy++) {
                int y = centre - dy;
                // Now and then a leaf of the neighbouring unit's rock, a block or two thick, as real sequences have.
                Block b = noise3D(x + 5 * o, y, z, 22.0, 3.0) > 0.6 ? leaf : rock;
                if (set(level, x, y, z, b, top)) placed++;
            }
        }
        return placed;
    }

    /** The rock of a collision unit, which changes along the belt every hundred blocks or so. */
    private static Block unitRock(int unit, int x, int z, int o) {
        double region = noise(x - 6 * o, z + 6 * o, 90.0);
        return switch (unit) {
            case 0 -> region > 0.35 ? ModBlocks.SCHIST.get() : ModBlocks.GNEISS.get();
            case 1 -> region < -0.3 ? ModBlocks.MARBLE.get() : ModBlocks.SCHIST.get();
            default -> region > 0.25 ? ModBlocks.QUARTZITE.get()
                    : region < -0.35 ? ModBlocks.MARBLE.get() : ModBlocks.SLATE.get();
        };
    }

    /**
     * The shear zone along a transform: a core right on the fault, clay gouge ground out of the rock with
     * breccia either side of it, and one sheared band of slate out in the damage zone on each side. All of it
     * runs up the fault plane in long lenses, as far as caves and ravines reach.
     */
    private static int shearZone(WorldGenLevel level, ServerLevel world, int x, int z, PlateSample s,
                                 int floor, int top, double faultWidth, int o, Map<Long, Double> corners) {
        // The cached sample is a four-block cell; nothing this narrow can be near unless that cell is.
        if (s.faultDistance() > SHEAR_BAND + 4) return 0;
        double d = faultDistanceAt(world, x, z, corners);
        boolean core = d <= 1.5;
        boolean band = !core && Math.abs(d - SHEAR_BAND) <= 0.5 && d <= faultWidth * 0.06;
        if (!core && !band) return 0;

        int placed = 0;
        int ceiling = Math.min(top, SHEAR_CEILING);
        for (int y = floor; y <= ceiling; y++) {
            double lens = noise3D(x + 7 * o, y, z - 7 * o, 20.0, 10.0);
            if (lens < (core ? 0.25 : 0.45)) continue;
            Block b;
            if (band) b = ModBlocks.SLATE.get();
            else if (d <= 0.75) b = Blocks.CLAY;
            else b = lattice3D(x, y + o, z) > 0.1 ? Blocks.COBBLED_DEEPSLATE : ModBlocks.SHALE.get();
            if (set(level, x, y, z, b, top)) placed++;
        }
        return placed;
    }

    /** How far out from a transform's core its sheared band lies. */
    private static final double SHEAR_BAND = 7.0;
    /** Highest a shear zone is drawn: caves and ravines reach about this far up, and above it the soil takes over. */
    private static final int SHEAR_CEILING = 48;

    /**
     * Distance to the fault at a column, blended from exact samples at the corners of its four-block cell. The
     * cached samples are too coarse for a feature a block or two wide, and each is taken wherever its cell was
     * first asked about, which a neighbouring chunk may have done from a different block.
     *
     * @param corners samples already taken for this chunk
     */
    static double faultDistanceAt(ServerLevel world, int x, int z, Map<Long, Double> corners) {
        int qx = x >> 2, qz = z >> 2;
        double fx = (x - (qx << 2)) / 4.0, fz = (z - (qz << 2)) / 4.0;
        double d00 = corner(world, qx, qz, corners), d10 = corner(world, qx + 1, qz, corners);
        double d01 = corner(world, qx, qz + 1, corners), d11 = corner(world, qx + 1, qz + 1, corners);
        return Mth.lerp(fz, Mth.lerp(fx, d00, d10), Mth.lerp(fx, d01, d11));
    }

    private static double corner(ServerLevel world, int qx, int qz, Map<Long, Double> corners) {
        long key = ((long) qx << 32) ^ (qz & 0xFFFFFFFFL);
        Double hit = corners.get(key);
        if (hit == null) {
            hit = TectonicMap.sample(world, qx << 2, qz << 2).faultDistance();
            corners.put(key, hit);
        }
        return hit;
    }

    // === Helpers ============================================================

    /** Shifts the noise fields per world, so two worlds do not share their plutons. */
    private static int fieldOffset(long seed) {
        return (int) (SeedHash.hash(seed, 0, 0, 0xB0D1E5L) & 0xFFFFF);
    }

    /** A field for choosing between rocks inside one body: patches several blocks across, with a contact that wanders a block. */
    private static double rockField(int x, int y, int z, int o) {
        return noise3D(x + o, y, z - o, 10.0, 5.0) + 0.08 * lattice3D(x - o, y, z + o);
    }

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
        // Lobed rather than spherical, so a chamber does not read as a placed object.
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

    /** Writes one block, refusing to breach this column's ceiling, bedrock, ore, or anything a player made. */
    private static boolean set(WorldGenLevel level, int x, int y, int z, Block block, int top) {
        if (y > top || y <= level.getMinBuildHeight()) return false;
        // A magma pocket near the edge reaches into the next chunk. Never load one to do it.
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        // Already this rock: skip, since a no-op write through the live chunk still pays every hook.
        if (s.is(block)) return false;
        if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return false;
        // Ore the generator placed before this step stays: the rock grows round it instead of replacing it.
        if (s.is(net.minecraftforge.common.Tags.Blocks.ORES)) return false;
        // Never eat what the mod relies on: a geyser's magma slab and its block entities.
        if (s.is(Blocks.MAGMA_BLOCK) || s.hasBlockEntity()) return false;
        // Only ever replace rock: caves, aquifers and the shape of the terrain are left alone.
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        // Clients told, neighbour shapes not recalculated: rock for rock has no shape to update, and
        // the update would load neighbouring chunks at the edge of the loaded area.
        level.setBlock(p, block.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        return true;
    }
}
