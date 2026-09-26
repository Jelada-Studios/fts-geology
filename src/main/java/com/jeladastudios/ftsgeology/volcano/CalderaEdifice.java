package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import static com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoPlan.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoSummit.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoEdifice.*;
import static com.jeladastudios.ftsgeology.volcano.ApronEdifice.*;

/** A caldera's body: the ring fault, the scarp, the floor and its lava lake, and the plateau of a large one. Split from VolcanoEdifice. */
public final class CalderaEdifice {

    private CalderaEdifice() {}

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
        int pond = Integer.MIN_VALUE;
        int target;
        if (dist <= rr - wallWidth) {
            lake = inLake(c, gx, gz);
            // Floor roughness never goes below the floor: the lake sits one under it and must not run.
            target = lake ? c.calderaFloorY : c.calderaFloorY + Math.max(0, (int) Math.round(noise * 1.2));
            if (!lake && dist < c.domeR) {
                target += (int) Math.round(c.domeH * (1.0 - dist / Math.max(1.0, c.domeR)));
            }
            // The water lake: a shallow bowl in the floor, its surface a block under the floor so it cannot run.
            if (!lake) {
                pond = pondBed(c, gx, gz);
                if (pond != Integer.MIN_VALUE) target = Math.min(target, pond);
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
            BlockState top = pond != Integer.MIN_VALUE ? (rng.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState()
                    : calderaSurface(rng, gx, gz, rock);
            setRock(level, new BlockPos(gx, y, gz), y == target ? top : rock);
        }
        if (pond != Integer.MIN_VALUE) {
            // The bowl was just cleared to air, which setRock leaves alone: the water goes in directly.
            for (int y = target + 1; y <= c.calderaFloorY - 1; y++) {
                BlockPos p = new BlockPos(gx, y, gz);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s)) level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
            }
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
        if (n > -0.05) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (n > -0.3) return (rng.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState();
        return rock;
    }

    /**
     * The bed of a big caldera's water lake at a column, or MIN outside it: a bowl up to five blocks under the floor
     * with a noisy shore, its surface a block below the floor so the floor round it holds it in.
     */
    static int pondBed(Ctx c, int gx, int gz) {
        if (c.pondR <= 0) return Integer.MIN_VALUE;
        double dx = gx - c.pondX, dz = gz - c.pondZ;
        double ang = Math.atan2(dz, dx);
        double edge = c.pondR * (1.0 + 0.16 * Math.sin(3 * ang + c.phaseB) + 0.09 * Math.sin(5 * ang + c.phaseA));
        double d = Math.hypot(dx, dz) / edge;
        if (d >= 1.0) return Integer.MIN_VALUE;
        return c.calderaFloorY - 1 - (int) Math.round(5.0 * (1.0 - d * d));
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

}
