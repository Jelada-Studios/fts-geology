package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import java.util.ArrayList;
import java.util.List;
import static com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoEdifice.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoSummit.*;

/** Works out a volcano's dimensions from a random source, and checks the ground can carry it. */
public final class VolcanoPlan {

    private VolcanoPlan() {}

    // === Layout =============================================================

    /** Everything the steps need to know, computed once up front and then shared by all of them. */
    static final class Ctx {
        VolcanoType type;
        VolcanoSize size = VolcanoSize.SMALL;
        double coneSlope;
        /** Half the length of a fissure's line. */
        int fissureHalf;
        /** A caldera's ring scarp: how high it stands at its crest, and how far out it comes down. */
        double rimLift;
        int rimWidth = 6;
        /** Outer edge of a caldera's lava lake crescent. */
        double lakeOuter;
        /** Length of one en-echelon segment of a fissure. */
        int segLen = 8;
        /**
         * How far from the centre the live finishing of a large volcano may reach. Zero means no
         * limit, which is right for anything built in one go into an area that is already loaded.
         */
        int liveReach;
        int magnitude;
        int x, z, baseY;
        int coneHeight, summitY, craterR, coneBaseR;
        int reservoirY, reservoirR;
        int clearReach, apronReach;
        /** How far the apron runs out past the edifice's own foot at each bearing. */
        double apronLen;
        int calderaFloorY, domeR, domeH;
        double lakeAngle, lakeWidth;
        double phaseA, phaseB, phaseC;
        int bandSeed;
        /**
         * The frozen lava flows down the flanks: a bearing and a wander phase each, and how far
         * they run. See {@link #flowAt}.
         */
        int flows;
        double[] flowAim = new double[0];
        double[] flowPhase = new double[0];
        double flowReach;
        /** Widens the tongues with the mountain, so a shield is not threaded rather than striped. */
        double flowWidth = 1.0;
        double strikeX = 1, strikeZ = 0;
        int ventCount;
        /** Filled in by the summit step: the lava cell the core sits under. */
        BlockPos vent;
        int coreCraterR = 3;
        final List<BlockPos> ventSites = new ArrayList<>();
        final List<BlockPos> vents = new ArrayList<>();
        /** Steam-vent chimneys on the flanks: quiet most of the time, filthy during an eruption. */
        final List<BlockPos> fumaroles = new ArrayList<>();
        /**
         * Cells meant to be lava between eruptions: the crater pool, a caldera's lake, every pond of
         * a fissure swarm. Listed because a radius is the wrong shape for most of them.
         */
        final List<BlockPos> molten = new ArrayList<>();
    }

    static Ctx layout(ServerLevel level, BlockPos base, int magnitude, VolcanoType type,
                              VolcanoSize size) {
        Ctx c = plan(level, base.getX(), base.getY(), base.getZ(), magnitude, type, size,
                level.random, TectonicMap.sample(level, base.getX(), base.getZ()));
        // The site check scales with what we are actually about to occupy.
        return c != null && siteIsSuitable(level, c) ? c : null;
    }

    /**
     * Works out every dimension of a volcano from a random source. Kept apart from the site check so
     * a large volcano can be planned from its seed: every chunk, on any thread, gets the same mountain.
     *
     * @return null when the volcano cannot stand here at all
     */
    static Ctx plan(LevelHeightAccessor level, int x, int baseY, int z, int magnitude,
                            VolcanoType type, VolcanoSize size, RandomSource rng, PlateSample plate) {
        Ctx c = new Ctx();
        c.type = type;
        c.size = size;
        c.magnitude = magnitude;
        c.x = x;
        c.z = z;
        c.baseY = baseY;

        c.craterR = size.craterRadius(type, magnitude, rng);
        c.coneHeight = size.coneHeight(type, magnitude, rng);
        c.coneSlope = size.coneSlope(type);
        int ceiling = level.getMaxBuildHeight() - 12;
        if (c.baseY + c.coneHeight >= ceiling) {
            // A giant on high ground is cut down to fit rather than lost, as long as most of it still
            // stands. Anything else is refused, as it always was.
            int fit = ceiling - 1 - c.baseY;
            if (size != VolcanoSize.LARGE || c.coneHeight == 0 || fit < c.coneHeight * 0.6) return null;
            c.coneHeight = fit;
        }
        c.summitY = c.baseY + c.coneHeight;

        c.coneBaseR = c.coneHeight > 0
                ? (int) Math.round(c.craterR + c.coneHeight * c.coneSlope)
                : c.craterR;
        c.fissureHalf = size.fissureHalfLength(magnitude, rng);
        if (type == VolcanoType.FISSURE) {
            // A fissure has no cone, but it is not a point either: the swarm runs for tens of blocks
            // along the strike and floods the ground around it, so the footprint is the LINE.
            c.coneBaseR = c.fissureHalf;
        }
        c.rimLift = size.rimLift(magnitude);
        c.rimWidth = size.rimWidth();
        c.liveReach = size == VolcanoSize.LARGE ? LARGE_LIVE_REACH : 0;
        // The apron is measured from the edifice's own foot at each bearing, so a lobe swinging out
        // can never swallow it and leave the cone ending on a step. A large one is capped so the whole
        // footprint stays inside VolcanoField's cell margin.
        double share = size.apronReach(type);
        c.apronLen = size == VolcanoSize.LARGE
                ? Mth.clamp(c.coneBaseR * share, 20.0, 56.0)
                : c.coneBaseR * share + 6;
        double foot = switch (type) {
            case CALDERA -> c.craterR * 1.34 + c.rimWidth;
            case FISSURE -> 0.0;
            default -> c.coneBaseR * 1.21;
        };
        c.apronReach = type == VolcanoType.FISSURE
                ? c.coneBaseR + (int) Math.round(c.coneBaseR * share) + 6
                : (int) Math.ceil(foot + c.apronLen) + 1;
        // Clear everything the volcano lays rock on, apron included, so no debris is spread under a
        // standing forest.
        c.clearReach = Math.max(Math.max(c.coneBaseR, c.craterR), c.apronReach) + 6;

        c.reservoirR = GeyserConfig.VOLCANO_RESERVOIR_RADIUS.get() + rng.nextInt(1 + magnitude / 3);
        c.reservoirY = Math.max(level.getMinBuildHeight() + 5, c.baseY - 45 - rng.nextInt(25));
        if (c.baseY - c.reservoirY < 12) return null;

        c.phaseA = rng.nextDouble() * Math.PI * 2;
        c.phaseB = rng.nextDouble() * Math.PI * 2;
        c.phaseC = rng.nextDouble() * Math.PI * 2;
        c.bandSeed = rng.nextInt(64);

        // Where this mountain's flows went. Two to four of them, spread around the circle with
        // enough jitter that they are not symmetrical, each running a little past the foot of the
        // cone so the tongue carries on over the apron instead of stopping at a contour.
        c.flows = 2 + rng.nextInt(3) + c.coneBaseR / 30;
        c.flowAim = new double[c.flows];
        c.flowPhase = new double[c.flows];
        double spin = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < c.flows; i++) {
            c.flowAim[i] = spin + i * (Math.PI * 2 / c.flows)
                    + (rng.nextDouble() - 0.5) * 0.9;
            c.flowPhase[i] = rng.nextDouble() * Math.PI * 2;
        }
        c.flowReach = Math.max(10, c.coneBaseR) * (1.05 + rng.nextDouble() * 0.45);
        // Flow width scales with the cone, so a big mountain is not threaded with thin lines. Many
        // narrow flows cover about a tenth of the flank without breaking a centreline.
        c.flowWidth = Math.max(1.0, c.coneBaseR / 34.0);

        c.calderaFloorY = c.baseY - size.calderaDepth(rng);
        c.domeR = Math.max(3, c.craterR / 3);
        c.domeH = size.domeHeight(rng);
        c.lakeAngle = rng.nextDouble() * Math.PI * 2;
        c.lakeWidth = Math.PI * (0.45 + rng.nextDouble() * 0.35);
        // A small caldera's lake runs most of the way to the ring. A big one's would then be thousands
        // of lava cells, every one of them for the core to keep molten, so it stays a band by the dome.
        c.lakeOuter = size == VolcanoSize.SMALL
                ? c.craterR * 0.85 : Math.min(c.craterR * 0.85, c.domeR + 11.0);
        c.segLen = 6 + rng.nextInt(5);

        if (plate.onFault()) {
            double len = Math.hypot(plate.faultStrikeX(), plate.faultStrikeZ());
            if (len > 1.0e-6) {
                c.strikeX = plate.faultStrikeX() / len;
                c.strikeZ = plate.faultStrikeZ() / len;
            }
        }

        c.ventCount = Math.max(3, (int) Math.round(
                Mth.clamp(magnitude, 8, 22) * type.ventScale()));
        return c;
    }

    /**
     * Checks the ground can carry this volcano: refuses a shoreline, a mostly wet site, or, for a
     * caldera, which excavates a flat floor, seriously broken country.
     */
    static boolean siteIsSuitable(ServerLevel level, Ctx c) {
        int centre = TerrainProbe.groundY(level, c.x, c.z);
        if (centre == Integer.MIN_VALUE) return false;
        if (centre <= level.getSeaLevel() + 2) return false;      // not on a shoreline or seabed
        if (centre <= level.getMinBuildHeight() + 24) return false;

        int radius = Math.max(8, c.coneBaseR);
        int lo = centre, hi = centre, wet = 0, blank = 0, samples = 0;
        int stepSize = Math.max(2, radius / 8);
        for (int dx = -radius; dx <= radius; dx += stepSize) {
            for (int dz = -radius; dz <= radius; dz += stepSize) {
                if (dx * dx + dz * dz > radius * radius) continue;
                samples++;
                int g = TerrainProbe.groundY(level, c.x + dx, c.z + dz);
                if (g == Integer.MIN_VALUE) { blank++; continue; }
                if (TerrainProbe.hasFluidAbove(level, c.x + dx, c.z + dz)) wet++;
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        if (samples == 0) return false;
        // Water is refused: a cone at a shoreline leaves a rampart, and a caldera below a lake drains it.
        if (wet * 10 > samples) return false;
        if (blank * 8 > samples) return false;      // riddled with voids: no
        // A caldera cuts a flat floor and wants even ground; the others grow out of a slope.
        int allowed = c.type.excavates() ? 8 + radius / 6 : 24 + radius;
        return (hi - lo) <= allowed;
    }
}
