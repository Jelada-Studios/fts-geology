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

    /** Old lava tongues on a stratocone's flank, in -1..1: a few blocks to twenty wide, long down the slope. */
    static double oldLava(Ctx c, int gx, int gz) {
        int dx = gx - c.x, dz = gz - c.z;
        return polarNoise(Math.atan2(dz, dx), Math.sqrt((double) dx * dx + (double) dz * dz),
                c.coneBaseR * 0.8, (int) (c.phaseA * 4096) + 7919, 4.0, 14.0);
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
        int dx = gx - c.x, dz = gz - c.z;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        double ang = Math.atan2(dz, dx);

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
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
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
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
            case CALDERA -> (rng.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.TUFF)
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
        if (!extinct && c.size != VolcanoSize.SMALL && shieldTongue(c, gx, gz) > 0.5) {
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
     * Younger lava tongues down a shield, in -1..1: long down the slope, a few blocks to twenty across. Read at a
     * position pushed about by a coarse field, so a tongue winds down the flank instead of running out as a
     * straight spoke; the idea of warping where ridge noise is read comes from Tectonic's mountain ridges.
     */
    static double shieldTongue(Ctx c, int gx, int gz) {
        int ox = (int) (c.phaseB * 4096), oz = (int) (c.phaseC * 4096);
        int wx = gx + (int) Math.round(24.0 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + ox, gz - oz, 90.0));
        int wz = gz + (int) Math.round(24.0 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx - oz, gz + ox, 90.0));
        int dx = wx - c.x, dz = wz - c.z;
        return polarNoise(Math.atan2(dz, dx), Math.sqrt((double) dx * dx + (double) dz * dz),
                c.coneBaseR * 0.7, (int) (c.phaseA * 4096) + 4271, 4.0, 16.0);
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

    /** Radius of a caldera's ring fault at one bearing, shared by the floor, the scarp and the apron. */
    static double ringRadius(Ctx c, double ang) {
        return c.craterR * (1.0 + 0.22 * Math.sin(2 * ang + c.phaseA)
                + 0.12 * Math.sin(3 * ang + c.phaseB));
    }

    /** How far out the ring fault can possibly reach, scarp included. */
    static int calderaRingReach(Ctx c) {
        return (int) Math.ceil(c.craterR * 1.34) + c.rimWidth + 1;
    }

    /** A caldera is a collapse structure: an excavated floor with a resurgent dome, ringed by a fault scarp. */
    static void carveCalderaRow(ServerLevel level, Ctx c, int dx) {
        int reach = calderaRingReach(c);
        for (int dz = -reach; dz <= reach; dz++) {
            calderaColumn(level, c, c.x + dx, c.z + dz, level.random, false);
        }
    }

    /** One column of a caldera: its floor inside the ring fault, its scarp outside. See {@link #coneColumn}. */
    static void calderaColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                      boolean worldgen) {
        if (c.size != VolcanoSize.SMALL) {
            plateauCalderaColumn(level, c, gx, gz, rng, worldgen);
            return;
        }
        int dx = gx - c.x, dz = gz - c.z;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        double ang = Math.atan2(dz, dx);
        double rr = ringRadius(c, ang);
        if (dist > rr + c.rimWidth) return;
        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // Never cut below open water, which would drain the lake. A generated large caldera floors over
        // it instead, but still raises no scarp through a lake.
        boolean wet = !level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty();
        if (wet && !(worldgen && dist <= rr)) return;
        // Floor roughness, never below the base floor: the lake sits one under it and must not run.
        int rough = Math.max(0, (int) Math.round(surfaceNoise(c, gx, gz) * 1.2));

        if (dist <= rr) {
            boolean lake = inLakeSector(c, dist, ang);
            // The lake is at exactly the base level, so all of it is level.
            int target = lake ? c.calderaFloorY : c.calderaFloorY + rough;
            if (!lake && dist < c.domeR) {
                target += (int) Math.round(c.domeH * (1.0 - dist / Math.max(1.0, c.domeR)));
            }
            int clearTop = ground + 2;
            if (worldgen) {
                // Up through any water and whatever grows here, not only the two cells above ground.
                clearTop = Math.max(clearTop, Math.min(ground + 40,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz)));
            } else {
                TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
            }
            for (int y = target + 1; y <= clearTop; y++) {
                clearNatural(level, new BlockPos(gx, y, gz));
            }
            for (int y = Math.min(ground, target); y <= target; y++) {
                setRock(level, new BlockPos(gx, y, gz), coneRock(rng, c, gx, y, gz));
            }
            if (lake) {
                // Recessed one block: the lake is the lowest point of its basin.
                BlockPos molten = new BlockPos(gx, target - 1, gz);
                setRock(level, molten, Blocks.LAVA.defaultBlockState());
                clearNatural(level, new BlockPos(gx, target, gz));
                // Meant to stay lava; a generated lake is listed later, by collectCalderaLake.
                if (!worldgen) c.molten.add(molten);
            } else if (rng.nextInt(6) == 0) {
                setRock(level, new BlockPos(gx, target, gz), Blocks.TUFF.defaultBlockState());
            }
            return;
        }

        // The ring scarp, varying round the circle and breached where the modulation bottoms out.
        double gate = 0.5 + 0.5 * Math.sin(3 * ang + c.phaseC)
                + 0.25 * Math.sin(5 * ang + c.phaseA);
        if (gate < 0.22) return;                     // a breach in the ring
        double t = 1.0 - (dist - rr) / c.rimWidth;
        int lift = (int) Math.round(c.rimLift * t
                * (0.3 + 0.7 * Mth.clamp(gate, 0.0, 1.0))) + rough;
        if (lift < 1) return;
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        for (int h = 1; h <= lift; h++) {
            setRock(level, new BlockPos(gx, ground + h, gz), coneRock(rng, c, gx, ground + h, gz));
        }
        if (worldgen) clearCover(level, gx, ground + lift, gz);
    }

    /**
     * One column of a big caldera, as at Yellowstone: a plateau rising gently from the country to a rim,
     * a slumped inner wall, a floor at the level of the land around it with a resurgent dome, and a small
     * lava lake near the ring fault. Nothing is dug far below the surrounding ground.
     */
    static void plateauCalderaColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                     boolean worldgen) {
        int dx = gx - c.x, dz = gz - c.z;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        double ang = Math.atan2(dz, dx);
        double rr = ringRadius(c, ang);
        if (dist > rr + c.rimWidth) return;
        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // A generated caldera floors over standing water inside the ring; nothing else builds into it.
        boolean inside = dist <= rr;
        boolean wet = !level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty();
        if (wet && !(worldgen && inside)) return;

        int rimY = c.calderaFloorY + (int) Math.round(c.rimLift);
        double wallWidth = rr * 0.15;
        double noise = surfaceNoise(c, gx, gz);
        boolean lake = false;
        int target;
        if (dist <= rr - wallWidth) {
            lake = inLake(c, gx, gz);
            // Floor roughness never goes below the floor: the lake sits one under it and must not run.
            target = lake ? c.calderaFloorY : c.calderaFloorY + Math.max(0, (int) Math.round(noise * 1.2));
            if (!lake && dist < c.domeR) {
                target += (int) Math.round(c.domeH * (1.0 - dist / Math.max(1.0, c.domeR)));
            }
        } else if (inside) {
            // The inner wall: slumped into terraces rather than cut sheer.
            double s = (dist - (rr - wallWidth)) / wallWidth;
            double rise = Mth.clamp(s * s * (3.0 - 2.0 * s) + 0.06 * Math.sin(s * Math.PI * 3.0), 0.0, 1.0);
            target = c.calderaFloorY + (int) Math.round((rimY - c.calderaFloorY) * rise + noise * 2.0);
        } else {
            // The outer flank: a plateau of welded ash falling gently from the rim to the country.
            double s = (dist - rr) / c.rimWidth;
            target = ground + (int) Math.round(Math.max(0, rimY - ground) * Math.pow(1.0 - s, 1.5) + noise * 1.5);
            if (target <= ground) return;
        }

        if (inside) {
            int clearTop = worldgen
                    ? Math.min(ground + 40, level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz))
                    : ground + 2;
            if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
            for (int y = target + 1; y <= clearTop; y++) clearNatural(level, new BlockPos(gx, y, gz));
        } else if (!worldgen) {
            TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        }
        for (int y = Math.min(ground, target); y <= target; y++) {
            BlockState rock = coneRock(rng, c, gx, y, gz);
            setRock(level, new BlockPos(gx, y, gz), y == target ? calderaSurface(rng, gx, gz, rock) : rock);
        }
        if (lake) {
            // Recessed one block: the lake is the lowest point of its basin. Lava on a live caldera, a crust over the
            // vent on a sleeping one, and rainwater in a dead one.
            BlockPos molten = new BlockPos(gx, target - 1, gz);
            BlockState fill = switch (c.activity) {
                case ACTIVE -> Blocks.LAVA.defaultBlockState();
                case DORMANT -> (rng.nextBoolean() ? Blocks.BLACKSTONE : Blocks.TUFF).defaultBlockState();
                case EXTINCT -> Blocks.WATER.defaultBlockState();
            };
            setRock(level, molten, fill);
            clearNatural(level, new BlockPos(gx, target, gz));
            // Meant to stay lava; a generated lake is listed later, by collectCalderaLake.
            if (!worldgen && c.activity == VolcanoActivity.ACTIVE) c.molten.add(molten);
        }
        if (worldgen) clearCover(level, gx, target, gz);
    }

    /** A big caldera's ground: welded tuff and scree, and grass, where forest grows, over the old floor and plateau. */
    static BlockState calderaSurface(RandomSource rng, int gx, int gz, BlockState rock) {
        double n = com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx - 613, gz + 613, 45.0);
        if (n > 0.1) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (n > -0.2) return (rng.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState();
        return rock;
    }

    /** True in a big caldera's lava lake: a small round pool near the ring fault. */
    static boolean inLake(Ctx c, int gx, int gz) {
        double dx = gx - c.lakeX, dz = gz - c.lakeZ;
        return dx * dx + dz * dz <= c.lakeR * c.lakeR;
    }

    /** True inside the crescent of the caldera floor that holds the lava lake. */
    static boolean inLakeSector(Ctx c, double dist, double ang) {
        double rel = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(ang - c.lakeAngle)));
        return Math.abs(rel) <= c.lakeWidth * 0.5
                && dist > c.domeR + 1 && dist < c.lakeOuter;
    }

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
                setRock(level, new BlockPos(gx, ground + h, gz), h == skin ? seaBed(rng, water - skin, t, rock) : rock);
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
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                // A two-by-two trunk goes whole: half a dark oak's trunk left at the edge still held its crown.
                if (s.is(BlockTags.LOGS)) {
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos q = p.relative(d);
                        if (level.getBlockState(q).is(BlockTags.LOGS)) level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
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
