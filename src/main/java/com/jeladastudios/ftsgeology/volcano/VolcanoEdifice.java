package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;

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
import static com.jeladastudios.ftsgeology.volcano.CalderaEdifice.*;
import static com.jeladastudios.ftsgeology.volcano.ApronEdifice.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoPlan.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoSummit.*;

/** Raises a volcano's body column by column: cone, caldera, apron, flows and fissure ramparts. */
public final class VolcanoEdifice {

    private VolcanoEdifice() {}

    // === The edifice ========================================================

    /** Thickness where the apron meets the cone's foot: the cone bottoms out at it and the apron starts from it. */
    static double seamHeight(Ctx c) {
        return 1.0 + c.magnitude / 8.0;
    }

    /**
     * Height the finished mountain reaches at this column, or {@link Integer#MIN_VALUE} outside it.
     * Measured from the column's own ground, so the cone adds a decreasing amount of rock, and the flank
     * falls to {@link #seamHeight} at the foot rather than to zero, so it meets the apron without a gap.
     */
    static int coneTargetY(Ctx c, int gx, int gz, int localGround, double dist, double ang) {
        if (c.coneHeight <= 0) return Integer.MIN_VALUE;
        double baseR = coneRadius(c, ang);
        double innerR = craterEdge(c, ang);
        if (dist >= baseR) return Integer.MIN_VALUE;
        if (dist <= innerR) {
            // An extinct cone's crater has weathered into a bowl; a live one's is carved when its summit is finished.
            if (c.activity != VolcanoActivity.EXTINCT) return c.summitY;
            double s = dist / Math.max(1.0, innerR);
            return c.summitY - (int) Math.round(craterBowlDepth(c) * (1.0 - s * s));
        }
        double t = (dist - innerR) / Math.max(1.0, baseR - innerR);
        double frac = Math.pow(1.0 - t, c.flankExponent);
        // Roughness fades out at the rim so the edge still meets the apron cleanly.
        double rough = surfaceNoise(c, gx, gz) * Math.min(3.0, 1.0 + c.coneHeight * 0.02) * (1.0 - t);
        // Ridges and gullies down the flank, gone at the rim and at the foot.
        double ridges = c.ridgeHeight > 0 ? c.ridgeHeight * radialRidges(c, ang, dist) * 4.0 * t * (1.0 - t) : 0.0;
        // From this column's own ground, so a volcano on a hill does not become a plateau.
        double seam = seamHeight(c);
        double span = Math.max(0.0, c.baseY - localGround + c.coneHeight - seam);
        return localGround + (int) Math.round(span * frac + seam + rough + ridges);
    }

    /** How far the crater reaches on this bearing: its rim wanders a tenth either way. */
    static double craterEdge(Ctx c, double ang) {
        return c.craterR * (1.0 + 0.10 * Math.sin(2 * ang + c.phaseB));
    }

    /** How deep an extinct cone's weathered crater bowl is at its middle: deep enough to hold a lake. */
    static double craterBowlDepth(Ctx c) {
        return c.type == VolcanoType.STRATOVOLCANO ? Math.max(7.0, c.craterR * 0.5) : 7.0;
    }

    /**
     * Where rain stands in an extinct crater's bowl: four under the summit, below any dip the rim's roughness can make,
     * so the lake is held on every side.
     */
    static int craterLakeY(Ctx c) {
        return c.summitY - 4;
    }

    /**
     * Ridges and gullies running down a flank, in -1..1: broad and rounded, some 60 blocks across at the
     * foot and converging towards the summit the way real ones do.
     */
    static double radialRidges(Ctx c, double ang, double dist) {
        return polarNoise(ang, dist, c.coneBaseR * 1.5, (int) (c.phaseC * 4096), 1.0, 90.0);
    }

    /**
     * Value noise round the vent, in -1..1: {@code around} noise units per radian across the flank and
     * {@code dist / stretch} along it, so features run downslope and narrow towards the summit. Blended
     * across the bearing where the angle wraps, so there is no seam on the west side.
     */
    static double polarNoise(double ang, double dist, double around, int offset, double stretch, double scale) {
        double u = ang < 0 ? ang + Math.PI * 2 : ang;
        int d = (int) Math.round(dist / stretch);
        double a = com.jeladastudios.ftsgeology.util.ValueNoise.noise((int) Math.round(u * around) + offset, d, scale);
        double wrap = u - (Math.PI * 2 - 0.5);
        if (wrap <= 0) return a;
        double b = com.jeladastudios.ftsgeology.util.ValueNoise.noise(
                (int) Math.round((u - Math.PI * 2) * around) + offset, d, scale);
        return Mth.lerp(wrap / 0.5, a, b);
    }

    /**
     * Old lava tongues on a stratocone's flank, in -1..1: a handful round the mountain, each tens of blocks wide
     * and long down the slope. Few noise cells round the circle, or the flank is combed with thin spokes.
     */
    static double oldLava(Ctx c, int gx, int gz) {
        int dx = gx - c.x, dz = gz - c.z;
        return polarNoise(Math.atan2(dz, dx), Math.sqrt((double) dx * dx + (double) dz * dz),
                c.coneBaseR * 0.25, (int) (c.phaseA * 4096) + 7919, 4.0, 14.0);
    }

    /** Radius of the cone's lobed foot at one bearing. The cone and the apron both use it, so they meet. */
    static double coneRadius(Ctx c, double ang) {
        return c.coneBaseR * (1.0 + 0.14 * Math.sin(3 * ang + c.phaseA)
                + 0.07 * Math.sin(5 * ang + c.phaseB));
    }

    /** How far the cone's foot can possibly reach, lobes included. */
    static int coneReach(Ctx c) {
        return (int) Math.ceil(c.coneBaseR * 1.21) + 2;
    }

    /**
     * Roughness in -1..1 added before rounding, so the smooth profile does not show contour terraces.
     * Two octaves of value noise offset per volcano: sine sums repeat, and on a big cone the repeats
     * stood out as rows of identical ridges.
     */
    static double surfaceNoise(Ctx c, int gx, int gz) {
        int ox = (int) (c.phaseA * 4096), oz = (int) (c.phaseB * 4096);
        return (com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + ox, gz + oz, 13.0)
                + 0.5 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx - oz, gz + ox, 5.0)) / 1.5;
    }

    static void buildConeRow(ServerLevel level, Ctx c, int dx) {
        // The lobed foot reaches a fifth past coneBaseR.
        int reach = coneReach(c);
        for (int dz = -reach; dz <= reach; dz++) {
            coneColumn(level, c, c.x + dx, c.z + dz, level.random, false);
        }
    }

    /**
     * One column of the cone. Every shaping step works a column at a time and reads nothing outside it,
     * so world generation can build a large volcano chunk by chunk, in any order, into one mountain.
     *
     * @param worldgen true while the chunk is being generated: standing water under the cone is filled
     *                 over rather than walled around, and cover left on top of the column is cleared
     */
    static void coneColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                   boolean worldgen) {
        coneColumn(level, c, gx, gz, rng, worldgen, Integer.MIN_VALUE);
    }

    /** @param floor the ground to build up from at generation ({@link TerrainProbe#buildGround}), or MIN_VALUE */
    static void coneColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                   boolean worldgen, int floor) {
        int dx = gx - c.x, dz = gz - c.z;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        double ang = Math.atan2(dz, dx);

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // A hole into a cave is roofed over at its rim, not filled from its floor as a pillar.
        if (floor != Integer.MIN_VALUE) ground = Math.max(ground, floor);
        int water = 0;
        while (water < 32 && !level.getBlockState(new BlockPos(gx, ground + 1 + water, gz)).getFluidState().isEmpty()) {
            water++;
        }

        int target = coneTargetY(c, gx, gz, ground, dist, ang);
        if (target == Integer.MIN_VALUE) return;
        int surface = ground + water;
        boolean underWater;
        if (water >= 4 && worldgen) {
            // A lake or the sea: the flank carries on under the water on its own profile and turns to land only
            // where the profile rises above the surface, so the shore slopes instead of standing as a wall.
            underWater = target <= surface + 1;
            if (underWater) target = Math.min(target - 1, surface - 1);
        } else {
            // A stream or pond low on the flank keeps its water: its bed is raised to a block under the surface
            // instead of the channel being filled flat with rock. Where the profile is lower still, it is followed:
            // raising every shallow column to the surface laid a flat shelf round a volcano at the sea.
            underWater = water > 0 && target <= surface + 3;
            if (underWater) target = Math.min(target, surface - 1);
            // Higher up, a live build stops at the shore rather than walling a lake in.
            else if (water > 0 && !worldgen) return;
        }
        // Ground already above the mountain's profile is left alone, save for rain standing over it in a dead crater.
        if (ground >= target) {
            if (worldgen && !underWater) craterLake(level, c, gx, gz, ground, dist, ang);
            return;
        }

        if (!worldgen && !underWater) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        // A flow is a skin: only the top course is crust.
        boolean flow = !underWater && flowAt(c, ang, dist);
        for (int y = ground + 1; y <= target; y++) {
            BlockState rock = coneRock(rng, c, gx, y, gz);
            if (y == target) {
                if (underWater) rock = seaBed(rng, surface - target, 1.0, rock);
                else if (flow) rock = flowRock(c);
                else if (c.type == VolcanoType.STRATOVOLCANO) rock = shoreSkin(level, rng, gx, y, gz, stratoSurface(rng, c, gx, y, gz, rock));
                else if (c.type == VolcanoType.SHIELD) rock = shoreSkin(level, rng, gx, y, gz, shieldSurface(rng, c, gx, y, gz, rock));
            }
            setRock(level, new BlockPos(gx, y, gz), rock);
        }
        if (worldgen && !underWater) {
            clearCover(level, gx, target, gz);
            craterLake(level, c, gx, gz, target, dist, ang);
        }
    }

    /**
     * Rain standing in an extinct cone's crater over this column's top. The bowl deepens steadily to its middle and its
     * rim stands at the summit, so the columns round the lake are at or over its level and hold it in.
     */
    private static void craterLake(LevelAccessor level, Ctx c, int gx, int gz, int top, double dist, double ang) {
        if (c.activity != VolcanoActivity.EXTINCT || dist > craterEdge(c, ang) || top >= craterLakeY(c)) return;
        clearCover(level, gx, top, gz);
        for (int y = top + 1; y <= craterLakeY(c); y++) {
            setRock(level, new BlockPos(gx, y, gz), Blocks.WATER.defaultBlockState());
        }
    }

    /**
     * The top of a volcano's flank or apron under the sea: black sand in the shallows the waves work, thinning out
     * away from the mountain, and its own rock and shingle deeper down.
     *
     * @param depth water over this top, in blocks
     * @param t     1 at the edifice, falling to 0 at the apron's outer edge
     */
    static BlockState seaBed(RandomSource rng, int depth, double t, BlockState rock) {
        double black = depth <= 2 ? 0.25 + 0.6 * t : depth <= 4 ? 0.4 * t : 0.0;
        if (rng.nextDouble() < black) return ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState();
        return rng.nextInt(4) == 0 ? Blocks.GRAVEL.defaultBlockState() : rock;
    }

    /**
     * The strand where a volcano's flank meets the sea: black sand and shingle at the water, giving way to soil and
     * then the flank's own skin over a few blocks along a ragged line. A real black sand beach is bare where the
     * waves reach and grows over behind, first with dune grass and scrub.
     */
    static BlockState shoreSkin(LevelAccessor level, RandomSource rng, int gx, int y, int gz, BlockState skin) {
        return shoreSkin(level.getSeaLevel(), rng, gx, y, gz, skin, 1.5, 1.5, 3.0);
    }

    /**
     * {@link #shoreSkin} with the strand's height set: bare to {@code lift} blocks over the sea, give or take
     * {@code wobble}, and grown over across the {@code fade} blocks above that. An island's strand runs higher.
     */
    static BlockState shoreSkin(int sea, RandomSource rng, int gx, int y, int gz, BlockState skin,
                                double lift, double wobble, double fade) {
        if (y < sea - 1 || y > sea + lift + wobble + fade) return skin;
        double edge = sea + lift + wobble * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + 101, gz - 101, 9.0);
        if (y <= edge) {
            int roll = rng.nextInt(10);
            return (roll < 6 ? ModBlocks.VOLCANIC_BLACK_SAND.get() : roll < 9 ? Blocks.GRAVEL : Blocks.COARSE_DIRT)
                    .defaultBlockState();
        }
        if (rng.nextDouble() < (edge + fade - y) / fade) {
            return (rng.nextBoolean() ? Blocks.COARSE_DIRT : ModBlocks.VOLCANIC_BLACK_SAND.get()).defaultBlockState();
        }
        return skin;
    }

    /** Clears plants and neighbouring trees' crowns left above a column's new top during generation. */
    static void clearCover(LevelAccessor level, int x, int fromY, int z) {
        int top = Math.min(fromY + 40, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        for (int y = fromY + 1; y <= top; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (!(TerrainProbe.isTreePart(s) || TerrainProbe.isVegetation(s))) return;
            level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 2);
        }
    }

    /**
     * Is this column inside one of the frozen lava flows down the flanks? A pure function of bearing and
     * distance, so it drapes over the cone whatever order the columns are built in. Narrow near the vent
     * and widening at the toe, like an a'a flow.
     */
    static boolean flowAt(Ctx c, double ang, double dist) {
        if (c.flows == 0) return false;
        // The crater is molten, not crusted; its own lava is placed by the summit step.
        if (dist < c.craterR * 0.9 || dist > c.flowReach) return false;

        double t = dist / c.flowReach;                       // 0 at the vent, 1 at the toe
        // The channel winds as it runs downhill. The wander is in blocks, not in angle, so a flow far
        // down the flank bends a few blocks either way instead of swinging round the mountain.
        // Growing with distance, so a flow leaves the vent nearly straight and meanders lower down.
        for (int i = 0; i < c.flows; i++) {
            double wander = flowWander(c, i, dist);
            // Wrapped to -PI..PI so a flow near due west is not cut in two.
            double delta = Math.atan2(Math.sin(ang - c.flowAim[i]), Math.cos(ang - c.flowAim[i]));
            double across = Math.abs(delta * dist - wander);  // blocks measured across the flow
            if (across <= (0.9 + 2.0 * t * t) * c.flowWidth) return true;
        }
        return false;
    }

    /**
     * How far flow {@code i} has wandered sideways at a distance from the vent, in blocks. Growing with distance, so a
     * flow leaves the vent nearly straight and meanders lower down.
     */
    static double flowWander(Ctx c, int i, double dist) {
        double amp = 2.0 + Math.min(16.0, dist * 0.08);
        double phase = c.flowPhase[i];
        double wave = 60.0 + 30.0 * (phase / (Math.PI * 2));
        return amp * (Math.sin(dist * Math.PI * 2 / wave + phase)
                + 0.35 * Math.sin(dist * Math.PI * 2 / (wave * 0.43) - phase));
    }

    /** The skin of a flow: dark, and still warm enough to show at night. */
    static BlockState flowRock() {
        return com.jeladastudios.ftsgeology.registry.ModBlocks.COOLING_LAVA_CRUST.get()
                .defaultBlockState();
    }

    /** A flow's skin by how long ago it ran: still warm on a live volcano, cold basalt on a sleeping or dead one. */
    static BlockState flowRock(Ctx c) {
        return c.activity == VolcanoActivity.ACTIVE ? flowRock() : Blocks.BASALT.defaultBlockState();
    }

    /** The edifice rock: andesite and tuff on a stratocone, basalt on a shield, welded tuff in a caldera. */
    static BlockState coneRock(RandomSource rng, Ctx c, int gx, int y, int gz) {
        return switch (c.type) {
            case STRATOVOLCANO -> stratoRock(rng, c, gx, y, gz);
            case SHIELD -> (rng.nextInt(3) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
            // Welded ash and the rhyolite it came from, as the Yellowstone plateau is.
            case CALDERA -> (rng.nextInt(3) == 0 ? com.jeladastudios.ftsgeology.registry.ModBlocks.RHYOLITE.get() : Blocks.TUFF)
                    .defaultBlockState();
            case FISSURE -> (rng.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
        };
    }

    /**
     * A stratocone by height: scoria and dark lava near the summit, andesite and tuff down the upper
     * flank, andesite, stone and gravel lower down. Tuff comes in lenses from 3D noise rather than in
     * bands on a contour, which drew stripes round the mountain.
     */
    static BlockState stratoRock(RandomSource rng, Ctx c, int gx, int y, int gz) {
        double h = (y - c.baseY) / (double) Math.max(1, c.coneHeight);
        if (h > 0.85) {
            int r = rng.nextInt(10);
            return (r < 4 ? Blocks.BLACKSTONE : r < 7 ? Blocks.BASALT : Blocks.TUFF).defaultBlockState();
        }
        double lens = com.jeladastudios.ftsgeology.util.ValueNoise.noise3D(gx, y, gz, 14.0, 5.0);
        if (lens > (h > 0.55 ? 0.30 : 0.45)) return Blocks.TUFF.defaultBlockState();
        int r = rng.nextInt(20);
        if (h > 0.55) return (r < 3 ? Blocks.STONE : Blocks.ANDESITE).defaultBlockState();
        return (r < 11 ? Blocks.ANDESITE : r < 17 ? Blocks.STONE : Blocks.GRAVEL).defaultBlockState();
    }

    /**
     * The skin of a stratocone column: grass low on the flank, where world generation's vegetation step
     * then grows trees, thinning through scree above a ragged tree line, bare rock higher up, and on a
     * medium or large cone dark tongues of old lava down the middle and upper flank.
     */
    static BlockState stratoSurface(RandomSource rng, Ctx c, int gx, int y, int gz, BlockState rock) {
        return stratoSkin(rng, c, gx, gz, (y - c.baseY) / (double) Math.max(1, c.coneHeight), rock);
    }

    /** {@link #stratoSurface} at a share {@code h} of the way up the cone: 0 at its foot, 1 at the summit. */
    static BlockState stratoSkin(RandomSource rng, Ctx c, int gx, int gz, double h, BlockState rock) {
        // An extinct cone has grown over far up its flanks, and its old flows have weathered into its soil.
        boolean extinct = c.activity == VolcanoActivity.EXTINCT;
        double line = (extinct ? 0.55 : 0.24) + 0.10 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx, gz, 40.0);
        if (!extinct && h > line && c.size != VolcanoSize.SMALL
                && oldLava(c, gx, gz) > 0.38 - 0.12 * rng.nextDouble()) {
            int r = rng.nextInt(10);
            return (r < 5 ? Blocks.BASALT : r < 8 ? Blocks.BLACKSTONE : Blocks.SMOOTH_BASALT).defaultBlockState();
        }
        // Fine noise rather than a dice roll decides the mix, so grass and scree break up in small patches.
        double jitter = 0.5 + 0.5 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + 57, gz - 57, 4.0);
        double grass = Mth.clamp(1.0 - (h - line + 0.06) / 0.14, 0.0, 1.0);
        if (jitter < grass) return Blocks.GRASS_BLOCK.defaultBlockState();
        double scree = Mth.clamp(1.0 - (h - line) / 0.20, 0.0, 1.0);
        if (jitter < scree) {
            return (rng.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState();
        }
        // Weathered rock and scree to the top of a dead cone, not the bare fresh rock of a live one.
        if (extinct && rng.nextBoolean()) {
            return (rng.nextBoolean() ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState();
        }
        return rock;
    }

    /**
     * The skin of a shield column. Old flows weather to soil long before a shield stops growing, so most of
     * one is green: grass, where the vegetation step grows forest, below a ragged tree line half way up,
     * scree and weathered basalt above it, fresh basalt near the summit, and on a medium or large shield dark
     * tongues of younger lava winding down through the forest.
     */
    static BlockState shieldSurface(RandomSource rng, Ctx c, int gx, int y, int gz, BlockState rock) {
        return shieldSkin(rng, c, gx, gz, (y - c.baseY) / (double) Math.max(1, c.coneHeight), rock);
    }

    /** {@link #shieldSurface} at a share {@code h} of the way up the shield: 0 at its foot, 1 at the summit. */
    static BlockState shieldSkin(RandomSource rng, Ctx c, int gx, int gz, double h, BlockState rock) {
        // An extinct shield is forest nearly to its top, with no young lava left bare.
        boolean extinct = c.activity == VolcanoActivity.EXTINCT;
        if (!extinct && c.size != VolcanoSize.SMALL && shieldTongue(c, gx, gz) > 0.55) {
            int r = rng.nextInt(10);
            return (r < 6 ? Blocks.BASALT : r < 9 ? Blocks.SMOOTH_BASALT : Blocks.BLACKSTONE).defaultBlockState();
        }
        double line = (extinct ? 0.82 : 0.62) + 0.1 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + 311, gz - 311, 45.0);
        if (h > line + 0.18) {
            int r = rng.nextInt(10);
            return (r < 5 ? Blocks.BASALT : r < 8 ? Blocks.SMOOTH_BASALT : Blocks.BLACKSTONE).defaultBlockState();
        }
        // Fine noise rather than a dice roll decides the mix, so grass and scree break up in small patches.
        double jitter = 0.5 + 0.5 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx - 97, gz + 97, 4.0);
        double grass = Mth.clamp(1.0 - (h - line + 0.06) / 0.14, 0.0, 1.0);
        if (jitter < grass) return Blocks.GRASS_BLOCK.defaultBlockState();
        double scree = Mth.clamp(1.0 - (h - line) / 0.22, 0.0, 1.0);
        if (jitter < scree) {
            int r = rng.nextInt(10);
            return (r < 4 ? Blocks.COARSE_DIRT : r < 7 ? Blocks.SMOOTH_BASALT : r < 9 ? Blocks.TUFF : Blocks.GRAVEL)
                    .defaultBlockState();
        }
        return (rng.nextInt(3) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT).defaultBlockState();
    }

    /**
     * Younger lava tongues down a shield, in -1..1: three to six round the mountain, each tens of blocks across and
     * long down the slope. Read at a position pushed about by a coarse field, so a tongue winds down the flank
     * instead of running out as a straight spoke; the idea of warping where ridge noise is read comes from
     * Tectonic's mountain ridges. Few noise cells round the circle: with seventy of them the flank was a comb of
     * evenly spaced spokes, whatever the flow count said.
     */
    static double shieldTongue(Ctx c, int gx, int gz) {
        int ox = (int) (c.phaseB * 4096), oz = (int) (c.phaseC * 4096);
        int wx = gx + (int) Math.round(24.0 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + ox, gz - oz, 90.0));
        int wz = gz + (int) Math.round(24.0 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx - oz, gz + ox, 90.0));
        int dx = wx - c.x, dz = wz - c.z;
        return polarNoise(Math.atan2(dz, dx), Math.sqrt((double) dx * dx + (double) dz * dz),
                c.coneBaseR * 0.1, (int) (c.phaseA * 4096) + 4271, 6.0, 16.0);
    }

    /**
     * Height of the foothills at this point of a big stratocone's apron: low rounded hills that are 0 at
     * both edges of the apron, so the mountain runs out into a range instead of ending on a skirt.
     */
    static double foothillHeight(Ctx c, int gx, int gz, double t) {
        if (c.type != VolcanoType.STRATOVOLCANO || c.size != VolcanoSize.LARGE) return 0.0;
        int ox = (int) (c.phaseB * 4096), oz = (int) (c.phaseC * 4096);
        double n = com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + ox, gz - oz, 60.0)
                + 0.4 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx - oz, gz + ox, 23.0);
        return 14.0 * Math.max(0.0, n) * 4.0 * t * (1.0 - t);
    }


    // === Clearing the site ================================================

    /**
     * Strips trees and ground cover from the footprint. Past the edifice more and more trees are spared,
     * whole, so the clearing frays into the forest instead of ending on a circle.
     */
    static void clearSiteRow(ServerLevel level, Ctx c, int dx) {
        int radius = c.clearReach;
        for (int dz = -radius; dz <= radius; dz++) {
            if (!stripped(c, dx, dz)) continue;
            int g = TerrainProbe.groundY(level, c.x + dx, c.z + dz);
            if (g == Integer.MIN_VALUE) continue;

            // Walk only as high as something stands in this column; a huge mushroom or a dark oak counts.
            int top = Math.min(g + 40,
                    level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                            c.x + dx, c.z + dz));
            for (int y = g + 1; y <= top; y++) {
                BlockPos p = new BlockPos(c.x + dx, y, c.z + dz);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                if (!TerrainProbe.isTreePart(s) && !TerrainProbe.isVegetation(s)) break;
                level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 2);
                // A two-by-two trunk goes whole: half a dark oak's trunk left at the edge still held its crown.
                if (s.is(BlockTags.LOGS)) {
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos q = p.relative(d);
                        if (level.getBlockState(q).is(BlockTags.LOGS)) level.setBlock(q, TfcCompat.translate(level, q, Blocks.AIR.defaultBlockState()), 2);
                    }
                }
            }
        }
    }

    /**
     * Whether the site clearing strips this column. Past the edifice more and more is spared, by the surface
     * noise rather than a per-column roll, so a tree is mostly kept or cleared whole.
     */
    static boolean stripped(Ctx c, int dx, int dz) {
        int radius = c.clearReach;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (dist > radius) return false;
        double solid = Math.max(c.coneBaseR, c.craterR) * 0.9;
        if (dist <= solid) return true;
        double out = Mth.clamp((dist - solid) / Math.max(1.0, radius - solid), 0.0, 1.0);
        double spare = (surfaceNoise(c, c.x + dx, c.z + dz) + 1.0) / 2.0;
        return spare >= out;
    }

    /**
     * Takes the crowns the clearing cut from their trunks, in the columns it spared beside ones it stripped and just
     * past its edge. See {@link TerrainProbe#dropLooseCrowns}.
     */
    static void dropLooseCrownsRow(ServerLevel level, Ctx c, int dx) {
        int reach = c.clearReach + TerrainProbe.LEAF_REACH + 2;
        for (int dz = -reach; dz <= reach; dz++) {
            if (dx * dx + dz * dz > reach * reach || stripped(c, dx, dz) || !besideStripped(c, dx, dz)) continue;
            TerrainProbe.dropLooseCrowns(level, c.x + dx, c.z + dz);
        }
    }

    /** True when a column the clearing strips lies within a crown's reach of this one. */
    private static boolean besideStripped(Ctx c, int dx, int dz) {
        int r = TerrainProbe.LEAF_REACH;
        for (int ox = -r; ox <= r; ox++) {
            for (int oz = -r; oz <= r; oz++) {
                if (stripped(c, dx + ox, dz + oz)) return true;
            }
        }
        return false;
    }
}
