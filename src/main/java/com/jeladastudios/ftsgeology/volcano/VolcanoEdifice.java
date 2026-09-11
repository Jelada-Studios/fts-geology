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
        double innerR = c.craterR * (1.0 + 0.10 * Math.sin(2 * ang + c.phaseB));
        if (dist >= baseR) return Integer.MIN_VALUE;
        if (dist <= innerR) return c.summitY;
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

    /**
     * Ridges and gullies running down a flank, in -1..1: noise stretched along the radius so each is
     * long and narrow, and narrowing towards the summit the way real ones converge. Blended across the
     * bearing where the angle wraps, so there is no seam on the west side.
     */
    static double radialRidges(Ctx c, double ang, double dist) {
        double u = ang < 0 ? ang + Math.PI * 2 : ang;
        double around = c.coneBaseR * 4.0;
        int ox = (int) (c.phaseC * 4096);
        int d = (int) Math.round(dist);
        double a = com.jeladastudios.ftsgeology.util.ValueNoise.noise((int) Math.round(u * around) + ox, d, 48.0);
        double wrap = u - (Math.PI * 2 - 0.5);
        if (wrap <= 0) return a;
        double b = com.jeladastudios.ftsgeology.util.ValueNoise.noise(
                (int) Math.round((u - Math.PI * 2) * around) + ox, d, 48.0);
        return Mth.lerp(wrap / 0.5, a, b);
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
        // Stop at the water's edge rather than walling a lake in.
        if (!worldgen && !level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) {
            return;
        }

        int target = coneTargetY(c, gx, gz, ground, dist, ang);
        if (target == Integer.MIN_VALUE) return;
        // Ground already above the mountain's profile is left alone.
        if (ground >= target) return;

        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        // A flow is a skin: only the top course is crust.
        boolean flow = flowAt(c, ang, dist);
        for (int y = ground + 1; y <= target; y++) {
            BlockState rock = coneRock(rng, c, gx, y, gz);
            if (y == target) {
                if (flow) rock = flowRock();
                else if (c.type == VolcanoType.STRATOVOLCANO) rock = stratoSurface(rng, c, gx, y, gz, rock);
                else if (c.type == VolcanoType.SHIELD) rock = shieldSurface(rng, c, gx, y, gz, rock);
            }
            setRock(level, new BlockPos(gx, y, gz), rock);
        }
        if (worldgen) clearCover(level, gx, target, gz);
    }

    /** Clears plants and neighbouring trees' crowns left above a column's new top during generation. */
    static void clearCover(LevelAccessor level, int x, int fromY, int z) {
        int top = Math.min(fromY + 40, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        for (int y = fromY + 1; y <= top; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (!(s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || TerrainProbe.isVegetation(s))) return;
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
        double amp = 2.0 + Math.min(16.0, dist * 0.08);
        for (int i = 0; i < c.flows; i++) {
            double phase = c.flowPhase[i];
            double wave = 60.0 + 30.0 * (phase / (Math.PI * 2));
            double wander = amp * (Math.sin(dist * Math.PI * 2 / wave + phase)
                    + 0.35 * Math.sin(dist * Math.PI * 2 / (wave * 0.43) - phase));
            // Wrapped to -PI..PI so a flow near due west is not cut in two.
            double delta = Math.atan2(Math.sin(ang - c.flowAim[i]), Math.cos(ang - c.flowAim[i]));
            double across = Math.abs(delta * dist - wander);  // blocks measured across the flow
            if (across <= (0.9 + 2.0 * t * t) * c.flowWidth) return true;
        }
        return false;
    }

    /** The skin of a flow: dark, and still warm enough to show at night. */
    static BlockState flowRock() {
        return com.jeladastudios.ftsgeology.registry.ModBlocks.COOLING_LAVA_CRUST.get()
                .defaultBlockState();
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
     * then grows trees, coarse scree above a ragged tree line, and bare rock higher up.
     */
    static BlockState stratoSurface(RandomSource rng, Ctx c, int gx, int y, int gz, BlockState rock) {
        double h = (y - c.baseY) / (double) Math.max(1, c.coneHeight);
        double line = 0.24 + 0.10 * com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx, gz, 40.0);
        if (h < line) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (h < line + 0.12 && rng.nextInt(3) != 0) return Blocks.COARSE_DIRT.defaultBlockState();
        return rock;
    }

    /**
     * The skin of a shield column by age: fresh dark basalt up high, weathered smooth basalt and tuff with
     * scree on the middle flank, and low down islands of soil and grass among the old flows, where the
     * vegetation step grows trees, like the kipukas on Hawaii's shields.
     */
    static BlockState shieldSurface(RandomSource rng, Ctx c, int gx, int y, int gz, BlockState rock) {
        double h = (y - c.baseY) / (double) Math.max(1, c.coneHeight);
        if (h > 0.6) return rock;
        double patch = com.jeladastudios.ftsgeology.util.ValueNoise.noise(gx + 977, gz - 977, 50.0);
        if (h < 0.3 && patch > 0.05) return Blocks.GRASS_BLOCK.defaultBlockState();
        int r = rng.nextInt(10);
        if (patch > -0.2) {
            return (r < 5 ? Blocks.COARSE_DIRT : r < 8 ? Blocks.SMOOTH_BASALT : Blocks.TUFF).defaultBlockState();
        }
        return (r < 6 ? Blocks.SMOOTH_BASALT : Blocks.BASALT).defaultBlockState();
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
            // Recessed one block: the lake is the lowest point of its basin.
            BlockPos molten = new BlockPos(gx, target - 1, gz);
            setRock(level, molten, Blocks.LAVA.defaultBlockState());
            clearNatural(level, new BlockPos(gx, target, gz));
            // Meant to stay lava; a generated lake is listed later, by collectCalderaLake.
            if (!worldgen) c.molten.add(molten);
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
        // Speckled: certain near the edifice, sparse at the edge. Flows and hills are never speckled.
        if (!flow && hill < 1.0 && rng.nextDouble() > Mth.clamp(t * 1.7, 0.0, 1.0)) return;

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // Checked directly: hasFluidAbove would walk the column a second time.
        int water = 0;
        while (water < 8 && !level.getBlockState(new BlockPos(gx, ground + 1 + water, gz)).getFluidState().isEmpty()) {
            water++;
        }
        // Under water the apron only carries on while the chunk generates; a live build stops at the shore.
        if (water > 0 && !worldgen) return;

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
        // A shield's skirt thins faster, so its long outer edge fades into the ground instead of a rim.
        double fade = c.type == VolcanoType.SHIELD ? 2.5 : 1.5;
        double nativeBand = c.type == VolcanoType.SHIELD ? 0.5 : 0.3;
        int thickness = Math.max(0, (int) Math.round(seamHeight(c) * Math.pow(1.0 - u, fade) * band));

        if (water > 0) {
            // A thin skin down the shore instead of the edifice ending on a step into the water, never
            // built up to the surface, with black sand where the water is shallow.
            int top = Math.min(thickness, Math.max(0, water - 2));
            for (int h = 0; h <= top; h++) {
                setRock(level, new BlockPos(gx, ground + h, gz), h == top && water <= 3
                        ? ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState()
                        : apronRock(rng, t, null, nativeBand));
            }
            return;
        }

        int lift = (int) Math.round(hill);
        int top = thickness + lift;
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
        BlockState native0 = level.getBlockState(new BlockPos(gx, ground, gz));
        for (int h = 0; h <= top; h++) {
            BlockState b;
            if (flow && h == top) b = flowRock();
            // A foothill is soil over rock, so the vegetation step grows grass and trees on it.
            else if (lift > 0 && h == top) b = Blocks.GRASS_BLOCK.defaultBlockState();
            else if (lift > 0 && h >= top - 2) b = Blocks.DIRT.defaultBlockState();
            // Only the surface cell may keep the native block; above it the block would float.
            else b = apronRock(rng, t, h == 0 ? native0 : null, nativeBand);
            setRock(level, new BlockPos(gx, ground + h, gz), b);
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
            setRock(level, new BlockPos(gx, ground - 1, gz), flowRock());
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
     * Apron material, mixed rather than banded: basalt more likely near the edifice, tephra towards the
     * edge, and native ground mixed in at the very edge so the apron fades out.
     *
     * @param t       1 at the apron's inner edge, 0 at its outer edge
     * @param native0    the surface block here, or null above the surface where it must not be reused
     * @param nativeBand the outer share of the apron, by {@code t}, where the native block mixes in
     */
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
        double solid = Math.max(c.coneBaseR, c.craterR) * 0.9;
        for (int dz = -radius; dz <= radius; dz++) {
            double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
            if (dist > radius) continue;
            int g = TerrainProbe.groundY(level, c.x + dx, c.z + dz);
            if (g == Integer.MIN_VALUE) continue;

            // Spared by the surface noise, not a per-column roll, so a tree is kept or cleared whole.
            double out = Mth.clamp((dist - solid) / Math.max(1.0, radius - solid), 0.0, 1.0);
            double spare = (surfaceNoise(c, c.x + dx, c.z + dz) + 1.0) / 2.0;
            if (dist > solid && spare < out) continue;

            // Walk only as high as something stands in this column.
            int top = Math.min(g + 24,
                    level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                            c.x + dx, c.z + dz));
            for (int y = g + 1; y <= top; y++) {
                BlockPos p = new BlockPos(c.x + dx, y, c.z + dz);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                boolean tree = s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
                if (!tree && !TerrainProbe.isVegetation(s)) break;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }
}
