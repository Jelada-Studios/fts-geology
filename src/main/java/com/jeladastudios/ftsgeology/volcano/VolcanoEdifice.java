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
        double frac = Math.pow(1.0 - t, c.type.flankExponent());
        // Roughness fades out at the rim so the edge still meets the apron cleanly.
        double rough = surfaceNoise(c, gx, gz) * Math.max(1.0, c.coneHeight * 0.09) * (1.0 - t);
        // From this column's own ground, so a volcano on a hill does not become a plateau.
        double seam = seamHeight(c);
        double span = Math.max(0.0, c.baseY - localGround + c.coneHeight - seam);
        return localGround + (int) Math.round(span * frac + seam + rough);
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

    /** Roughness added before rounding, so the smooth profile does not show contour terraces. */
    static double surfaceNoise(Ctx c, int gx, int gz) {
        return Math.sin(gx * 0.19 + c.phaseA) * Math.cos(gz * 0.23 + c.phaseB)
                + 0.5 * Math.sin((gx + gz) * 0.11 + c.phaseC)
                + 0.35 * Math.sin((gx - gz) * 0.31 + c.phaseA);
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
            setRock(level, new BlockPos(gx, y, gz),
                    flow && y == target ? flowRock() : coneRock(rng, c, y));
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
        for (int i = 0; i < c.flows; i++) {
            // The channel snakes as it descends rather than running down a radius like a seam.
            double centre = c.flowAim[i]
                    + 0.26 * Math.sin(dist / 9.0 + c.flowPhase[i])
                    + 0.12 * Math.sin(dist / 4.0 - c.flowPhase[i]);
            // Wrapped to -PI..PI so a flow near due west is not cut in two.
            double delta = Math.atan2(Math.sin(ang - centre), Math.cos(ang - centre));
            double across = Math.abs(delta) * dist;          // blocks measured across the flow
            if (across <= (0.9 + 2.0 * t * t) * c.flowWidth) return true;
        }
        return false;
    }

    /** The skin of a flow: dark, and still warm enough to show at night. */
    static BlockState flowRock() {
        return com.jeladastudios.ftsgeology.registry.ModBlocks.COOLING_LAVA_CRUST.get()
                .defaultBlockState();
    }

    /** The edifice rock: interbedded bands on a stratocone, basalt on a shield, welded tuff in a caldera. */
    static BlockState coneRock(RandomSource rng, Ctx c, int y) {
        return switch (c.type) {
            case STRATOVOLCANO -> switch (Math.floorMod((y + c.bandSeed) / 3, 4)) {
                case 0 -> Blocks.TUFF.defaultBlockState();
                case 2 -> Blocks.BLACKSTONE.defaultBlockState();
                default -> (rng.nextInt(5) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                        .defaultBlockState();
            };
            case SHIELD -> (rng.nextInt(3) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
            case CALDERA -> (rng.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.TUFF)
                    .defaultBlockState();
            case FISSURE -> (rng.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
        };
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
                setRock(level, new BlockPos(gx, y, gz), coneRock(rng, c, y));
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
            setRock(level, new BlockPos(gx, ground + h, gz), coneRock(rng, c, ground + h));
        }
        if (worldgen) clearCover(level, gx, ground + lift, gz);
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
        double edge = reach * (0.84 + 0.16 * Math.sin(3 * ang + c.phaseC));
        if (dist > edge || localInner >= edge) return;

        double t = 1.0 - (dist - localInner) / Math.max(1.0, edge - localInner);
        // Flows carry on across the apron rather than stopping on a contour.
        boolean flow = flowAt(c, ang, Math.sqrt((double) dx * dx + (double) dz * dz));
        // Speckled: certain near the edifice, sparse at the edge. Flows are never speckled.
        if (!flow && rng.nextDouble() > Mth.clamp(t * 1.7, 0.0, 1.0)) return;

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // Checked directly: hasFluidAbove would walk the column a second time.
        if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) return;

        // Starts at the seam height the flank came down to and thins with a 1.5 power.
        double u = 1.0 - t;                       // 0 at the seam, 1 at the outer edge
        int thickness = Math.max(0, (int) Math.round(seamHeight(c) * Math.pow(1.0 - u, 1.5)));
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
        BlockState native0 = level.getBlockState(new BlockPos(gx, ground, gz));
        for (int h = 0; h <= thickness; h++) {
            // Only the surface cell may keep the native block; above it the block would float.
            setRock(level, new BlockPos(gx, ground + h, gz),
                    flow && h == thickness
                            ? flowRock()
                            : apronRock(rng, t, h == 0 ? native0 : null));
        }
        // Only plants: a tree beside the apron still stands on real ground.
        if (worldgen) TerrainProbe.clearVegetation(level, gx, ground + thickness, gz, 2);
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

    /** A big fissure's line outside its central ponds: an open crack between low spatter ramparts, stepping sideways. */
    static void fissureRampartColumn(LevelAccessor level, Ctx c, int gx, int gz, boolean worldgen) {
        int dx = gx - c.x, dz = gz - c.z;
        double along = dx * c.strikeX + dz * c.strikeZ;
        double out = Math.abs(along);
        if (out > c.fissureHalf || out < POND_SEGMENT) return;
        double across = -dx * c.strikeZ + dz * c.strikeX;
        // The same en-echelon offsets carveFissureLine steps its ponds through.
        int seg = Math.floorDiv((int) Math.round(along) + c.fissureHalf, c.segLen);
        double lateral = ((seg % 2 == 0) ? 1 : -1) * (1 + seg % 3);
        double off = Math.abs(across - lateral);
        if (off > 3.5) return;
        // Dies away over the last quarter towards each tip.
        double tip = Mth.clamp((1.0 - out / c.fissureHalf) * 4.0, 0.0, 1.0);
        if (tip <= 0.0) return;

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) return;
        if (off < 0.75) {
            // The crack: open two blocks down and no more, so there is nowhere far to fall.
            TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
            for (int y = ground; y > ground - 2; y--) clearNatural(level, new BlockPos(gx, y, gz));
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
     * Apron material, mixed rather than banded: basalt more likely near the edifice, tephra towards the
     * edge, and native ground mixed in at the very edge so the apron fades out.
     *
     * @param t       1 at the apron's inner edge, 0 at its outer edge
     * @param native0 the surface block here, or null above the surface where it must not be reused
     */
    static BlockState apronRock(RandomSource rng, double t, BlockState native0) {
        double r = rng.nextDouble();

        // A share of the outermost cells keep the native block.
        if (native0 != null && t < 0.3 && r > t / 0.3 * 0.7 + 0.3) return native0;

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
            double spare = (surfaceNoise(c, c.x + dx, c.z + dz) + 1.85) / 3.7;
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
