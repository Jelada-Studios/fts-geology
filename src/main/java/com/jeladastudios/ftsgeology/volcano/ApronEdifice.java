package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import static com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoPlan.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoSummit.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoEdifice.*;
import static com.jeladastudios.ftsgeology.volcano.CalderaEdifice.*;

/** The skirt round an edifice: the apron of its own debris, and a fissure's ramparts and lava ponds. Split from VolcanoEdifice. */
public final class ApronEdifice {

    private ApronEdifice() {}

    /** Skirts the edifice with its own debris, speckled towards the edge so it dissolves into the ground. */
    static void buildApronRow(ServerLevel level, Ctx c, int dx) {
        int reach = c.apronReach;
        for (int dz = -reach; dz <= reach; dz++) {
            apronColumn(level, c, c.x + dx, c.z + dz, level.random, false);
        }
    }

    /** One column of the apron. See {@link #coneColumn}. */
    static void apronColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                    boolean worldgen) {
        apronColumn(level, c, gx, gz, rng, worldgen, Integer.MIN_VALUE);
    }

    static void apronColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                    boolean worldgen, int floor) {
        int dx = gx - c.x, dz = gz - c.z;
        int reach = c.apronReach;
        double dist = apronDistance(c, dx, dz);
        if (dist > reach) return;
        double ang = Math.atan2(dz, dx);
        // The apron starts where the edifice ends at this bearing: outside a caldera's scarp, at a
        // fissure's crack, at a cone's lobed foot.
        double localInner = switch (c.type) {
            case CALDERA -> ringRadius(c, ang) + c.rimWidth;
            case FISSURE -> 0;
            default -> coneRadius(c, ang);
        };
        if (dist <= localInner) return;
        double wobble = 0.84 + 0.16 * Math.sin(3 * ang + c.phaseC);
        double edge = c.type == VolcanoType.FISSURE ? reach * wobble : localInner + c.apronLen * wobble;
        if (dist > edge || localInner >= edge) return;

        double t = 1.0 - (dist - localInner) / Math.max(1.0, edge - localInner);
        // Flows carry on across the apron rather than stopping on a contour.
        boolean flow = flowAt(c, ang, Math.sqrt((double) dx * dx + (double) dz * dz));
        double hill = foothillHeight(c, gx, gz, t);

        // A big fissure's crack and ponds stand on their own ground: towards the line the apron thins to
        // a single course of rock, so it neither buries them nor leaves a strip of grass beside them.
        double band = 1.0;
        if (hasRamparts(c)) {
            double along = dx * c.strikeX + dz * c.strikeZ;
            double across = -dx * c.strikeZ + dz * c.strikeX;
            if (Math.abs(along) <= c.fissureHalf) {
                band = Mth.clamp((Math.abs(across - fissureLateral(c, along)) - 4.0) / 8.0, 0.0, 1.0);
            }
        }
        // Starts at the seam height the flank came down to and thins with a 1.5 power.
        double u = 1.0 - t;                       // 0 at the seam, 1 at the outer edge
        // A shield's and a fissure's skirt thin faster, so the long outer edge fades into the ground.
        double fade = c.type == VolcanoType.SHIELD ? 2.5 : c.type == VolcanoType.FISSURE ? 2.2 : 1.5;
        double nativeBand = c.type == VolcanoType.SHIELD ? 0.5 : 0.3;
        int thickness = Math.max(0, (int) Math.round(seamHeight(c) * Math.pow(1.0 - u, fade) * band));
        int lift = (int) Math.round(hill);
        int top = thickness + lift;
        // Where nothing is raised any more the apron only picks the ground's material, speckled towards the
        // edge so it dissolves into the land. A raised column is never skipped, or natural ground would be
        // left sunk between its neighbours.
        if (top == 0 && !flow && rng.nextDouble() > Mth.clamp(t * 1.7, 0.0, 1.0)) return;

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        if (floor != Integer.MIN_VALUE) ground = Math.max(ground, floor);
        // Checked directly: hasFluidAbove would walk the column a second time.
        int water = 0;
        while (water < 8 && !level.getBlockState(new BlockPos(gx, ground + 1 + water, gz)).getFluidState().isEmpty()) {
            water++;
        }
        // Under water the apron only carries on while the chunk generates; a live build stops at the shore.
        if (water > 0 && !worldgen) return;

        if (water > 0) {
            // A thin skin down the shore instead of the edifice ending on a step into the water, never
            // built up to the surface, with black sand in the shallows near the mountain.
            int skin = Math.min(thickness, Math.max(0, water - 2));
            BlockState bed0 = level.getBlockState(new BlockPos(gx, ground, gz));
            for (int h = 0; h <= skin; h++) {
                BlockState rock = apronBody(rng, c, gx, ground + h, gz, t, h == 0 ? bed0 : null, nativeBand);
                setRock(level, new BlockPos(gx, ground + h, gz), h == skin ? seaBed(rng, gx, gz, water - skin, t, rock) : rock);
            }
            return;
        }

        // Low ground by the sea stays low, so the skirt does not end on a step where the shore meets the water.
        int aboveSea = ground - level.getSeaLevel();
        if (aboveSea < 3 && lift == 0) {
            thickness = (int) Math.round(thickness * Mth.clamp(aboveSea / 3.0, 0.0, 1.0));
            top = thickness;
            if (top == 0 && !flow && rng.nextDouble() > Mth.clamp(t * 1.7, 0.0, 1.0)) return;
        }
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
        BlockState native0 = level.getBlockState(new BlockPos(gx, ground, gz));
        for (int h = 0; h <= top; h++) {
            int y = ground + h;
            BlockState b;
            if (flow && h == top) b = flowRock(c);
            // A foothill is soil over rock, so the vegetation step grows grass and trees on it.
            else if (lift > 0 && h == top) b = Blocks.GRASS_BLOCK.defaultBlockState();
            else if (lift > 0 && h >= top - 2) b = Blocks.DIRT.defaultBlockState();
            else {
                // Only the surface cell may keep the native block; above it the block would float.
                b = apronBody(rng, c, gx, y, gz, t, h == 0 ? native0 : null, nativeBand);
                // The top carries on the flank's own skin, so there is no second ring at the foot, and at the sea
                // the strand.
                if (h == top && b != native0) b = shoreSkin(level, rng, gx, y, gz, apronSurface(rng, c, gx, y, gz, b));
            }
            setRock(level, new BlockPos(gx, y, gz), b);
        }
        // Only plants: a tree beside the apron still stands on real ground.
        if (worldgen) TerrainProbe.clearVegetation(level, gx, ground + top, gz, 2);
    }

    /** Apron distance. A big fissure spreads along its crack, so its field is an ellipse three times as long as wide. */
    static double apronDistance(Ctx c, int dx, int dz) {
        if (!hasRamparts(c)) return Math.sqrt((double) dx * dx + (double) dz * dz);
        double along = dx * c.strikeX + dz * c.strikeZ;
        double across = -dx * c.strikeZ + dz * c.strikeX;
        return Math.hypot(along, across * 3.0);
    }

    static boolean hasRamparts(Ctx c) {
        return c.type == VolcanoType.FISSURE && c.size != VolcanoSize.SMALL;
    }

    /**
     * Where the ponds along a big fissure's line begin. Past the eruption's cooling sweep, which reaches
     * 20 + magnitude blocks from the summit and would drain lava resting on rock the volcano laid.
     */
    static final int POND_START = POND_SEGMENT + 16;

    /**
     * Sideways offset of a big fissure's line at a distance along its strike: a slow wander of 12 to 20
     * blocks, plus short en-echelon steps whose lengths come from noise, so no stretch of it repeats.
     */
    static double fissureLateral(Ctx c, double along) {
        int a = (int) Math.round(along);
        int ox = (int) (c.phaseA * 4096), oz = (int) (c.phaseB * 4096);
        double amp = 12.0 + 8.0 * (c.phaseC / (Math.PI * 2));
        double wander = amp * com.jeladastudios.ftsgeology.util.ValueNoise.noise(a + ox, oz, 70.0);
        double step = com.jeladastudios.ftsgeology.util.ValueNoise.noise(a + oz, ox, 13.0) >= 0 ? 1.5 : -1.5;
        return wander + step;
    }

    /** A big fissure's line outside its central ponds: ponds strung along it, a crusted crack between low ramparts. */
    static void fissureRampartColumn(WorldGenLevel level, Ctx c, int gx, int gz, boolean worldgen) {
        int dx = gx - c.x, dz = gz - c.z;
        double along = dx * c.strikeX + dz * c.strikeZ;
        double out = Math.abs(along);
        if (out > c.fissureHalf || out < POND_SEGMENT) return;
        double across = -dx * c.strikeZ + dz * c.strikeX;
        double off = Math.abs(across - fissureLateral(c, along));
        if (off > 6.0) return;
        // Dies away over the last quarter towards each tip.
        double tip = Mth.clamp((1.0 - out / c.fissureHalf) * 4.0, 0.0, 1.0);
        if (tip <= 0.0) return;
        if (pondColumn(level, c, gx, gz, along, across)) return;
        if (off > 3.5) return;

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) return;
        if (off < 0.9) {
            // The crack: its top course gone and freshly skinned lava glowing one block down.
            TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
            clearNatural(level, new BlockPos(gx, ground, gz));
            setRock(level, new BlockPos(gx, ground - 1, gz), flowRock(c));
            return;
        }
        int lift = (int) Math.round((off < 2.25 ? 3.0 : 1.5) * tip);
        if (lift < 1) return;
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        for (int h = 1; h <= lift; h++) {
            boolean dark = h == lift && Math.floorMod(gx * 31 + gz, 5) == 0;
            setRock(level, new BlockPos(gx, ground + h, gz),
                    (dark ? Blocks.BLACKSTONE : Blocks.BASALT).defaultBlockState());
        }
        if (worldgen) TerrainProbe.clearVegetation(level, gx, ground + lift, gz, 2);
    }

    /**
     * One column of a lava pond on a big fissure's line, if it falls in one; the pond then owns the column.
     * Ponds sit 16 to 24 blocks apart along the whole line, and each holds its lava at a level read from
     * the generator's own terrain, so every chunk a pond crosses agrees on it and the lava stays walled in.
     */
    static boolean pondColumn(WorldGenLevel level, Ctx c, int gx, int gz, double along, double across) {
        // A dead fissure's ponds froze long ago; its line is only crack and ramparts.
        if (c.activity == VolcanoActivity.EXTINCT) return false;
        int side = along < 0 ? -1 : 1;
        long key = Double.doubleToLongBits(c.phaseA) * 31 + Double.doubleToLongBits(c.phaseB);
        int k = 0;
        for (double a = POND_START; a <= c.fissureHalf - 12; k++) {
            final double ap = side * a;
            double da = along - ap;
            if (Math.abs(da) <= 4.0) {
                double dl = across - fissureLateral(c, ap);
                if ((da / 4.0) * (da / 4.0) + (dl / 2.8) * (dl / 2.8) <= 1.0) {
                    int h = c.pondLevels.computeIfAbsent((int) Math.round(ap), i -> pondLevel(level, c, ap));
                    if (h == Integer.MIN_VALUE) return false;
                    boolean lava = (da / 2.5) * (da / 2.5) + (dl / 1.3) * (dl / 1.3) <= 1.0;
                    if (lava) {
                        // Recessed into the ground on a basalt floor, open to the sky.
                        setRock(level, new BlockPos(gx, h - 1, gz), Blocks.BASALT.defaultBlockState());
                        setRock(level, new BlockPos(gx, h, gz), Blocks.LAVA.defaultBlockState());
                        for (int y = h + 1; y <= h + 4; y++) clearNatural(level, new BlockPos(gx, y, gz));
                    } else {
                        // The spatter rim: solid from under the lava to one above it, filled down to the
                        // column's own ground so it never floats.
                        int ground = TerrainProbe.groundY(level, gx, gz);
                        int from = ground == Integer.MIN_VALUE ? h - 1 : Math.min(h - 1, ground + 1);
                        for (int y = from; y <= h + 1; y++) {
                            setRock(level, new BlockPos(gx, y, gz), Blocks.BASALT.defaultBlockState());
                        }
                    }
                    return true;
                }
            }
            a += 16 + (int) (8 * com.jeladastudios.ftsgeology.util.SeedHash.rand01(
                    com.jeladastudios.ftsgeology.util.SeedHash.hash(key, k, side, 0xF0DL)));
        }
        return false;
    }

    /**
     * The Y a pond at this point of a big fissure's line holds its lava at: the generator's surface there,
     * or {@link Integer#MIN_VALUE} where five probes across the pond are not level, or it would be in water.
     */
    static int pondLevel(WorldGenLevel level, Ctx c, double ap) {
        ServerLevel model = level.getLevel();
        net.minecraft.world.level.chunk.ChunkGenerator gen = model.getChunkSource().getGenerator();
        net.minecraft.world.level.levelgen.RandomState rs = model.getChunkSource().randomState();
        double lp = fissureLateral(c, ap);
        final double[][] probes = { {0, 0}, {4, 0}, {-4, 0}, {0, 3}, {0, -3} };
        int h = Integer.MIN_VALUE;
        for (double[] p : probes) {
            double a = ap + p[0], l = lp + p[1];
            int x = c.x + (int) Math.round(c.strikeX * a - c.strikeZ * l);
            int z = c.z + (int) Math.round(c.strikeZ * a + c.strikeX * l);
            int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, model, rs) - 1;
            int surface = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, model, rs) - 1;
            if (surface > floor) return Integer.MIN_VALUE;
            if (h == Integer.MIN_VALUE) h = floor;
            else if (Math.abs(floor - h) > 1) return Integer.MIN_VALUE;
        }
        return h <= gen.getSeaLevel() + 1 ? Integer.MIN_VALUE : h;
    }

    /**
     * Apron rock: the edifice's own palette, so the skirt carries on from the flank, with native ground
     * mixed in at the very edge so it fades out. A fissure's field keeps its basalt, see {@link #apronRock}.
     *
     * @param t       1 at the apron's inner edge, 0 at its outer edge
     * @param native0    the surface block here, or null above the surface where it must not be reused
     * @param nativeBand the outer share of the apron, by {@code t}, where the native block mixes in
     */
    static BlockState apronBody(RandomSource rng, Ctx c, int gx, int y, int gz, double t,
                                BlockState native0, double nativeBand) {
        if (c.type == VolcanoType.FISSURE) return apronRock(rng, t, native0, nativeBand);
        if (native0 != null && t < nativeBand && rng.nextDouble() > t / nativeBand * 0.7 + 0.3) return native0;
        return coneRock(rng, c, gx, y, gz);
    }

    /** The top of an apron column, continuing the edifice's own skin down past its foot. */
    static BlockState apronSurface(RandomSource rng, Ctx c, int gx, int y, int gz, BlockState rock) {
        return switch (c.type) {
            case STRATOVOLCANO -> stratoSurface(rng, c, gx, y, gz, rock);
            case SHIELD -> shieldSurface(rng, c, gx, y, gz, rock);
            case CALDERA -> calderaSurface(rng, gx, gz, rock);
            case FISSURE -> rock;
        };
    }

    /** A fissure's apron: basalt more likely near the crack, tephra towards the edge, native ground at the very edge. */
    static BlockState apronRock(RandomSource rng, double t, BlockState native0, double nativeBand) {
        double r = rng.nextDouble();

        // A share of the outermost cells keep the native block.
        if (native0 != null && t < nativeBand && r > t / nativeBand * 0.7 + 0.3) return native0;

        // Basalt dominates near the cone, tephra at the rim; both are present throughout.
        double basalt = Mth.clamp(t * 1.15, 0.0, 1.0);
        if (rng.nextDouble() < basalt) {
            return (rng.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
        }
        return (rng.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
    }
}
