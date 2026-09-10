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
         * Cells that are MEANT to be lava between eruptions - the crater pool, a caldera's lake
         * crescent, every pond of a fissure swarm.
         *
         * <p>{@code coolScatteredLava} used to protect them with a radius, {@code coreCraterR}, and
         * a radius is the wrong shape for two of the four summit styles. A caldera's lake is a
         * crescent reaching {@code craterR * 0.85} while its keep radius was {@code craterR / 3}, and
         * a fissure's ponds are strung along a line while its keep radius was 2 - so after the first
         * eruption most of the lava a volcano was built with had been turned to basalt and never
         * refilled. That is the "hardly any lava in the crater" report. Listing the actual cells
         * makes the protection exactly the shape of the thing it is protecting.</p>
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
     * Works out every dimension of a volcano from a random source.
     *
     * <p>Kept apart from the site check so a large volcano can be planned from its own seed. World
     * generation raises one a chunk at a time, on several threads, and every one of those chunks has
     * to arrive at the same mountain; given the same seed this returns the same numbers, so they do.
     * </p>
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
        c.apronReach = c.coneBaseR + (int) Math.round(c.coneBaseR * size.apronReach(type)) + 6;
        // Everything the volcano will lay rock on, not just the edifice. The clearing used to stop
        // at the cone while the apron ran a third further out, so the outer band of debris was
        // spread UNDER a standing forest - buildApronRow only calls clearVegetation, which by
        // design leaves logs and leaves alone. That is the forest growing out of the basalt in the
        // test shots. The fringe of snags in clearSiteRow scales with the radius, so widening it
        // frays the edge further rather than mowing a bigger circle.
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
        // A tongue measured in absolute blocks covers a share of the flank that falls off as 1/r:
        // measured, a 20-block cone came out 21% flow and a 69-block shield only 6%, so the biggest
        // mountains - the ones worth looking at - were the ones wearing threads. Scaling the width
        // with the cone holds the proportion roughly steady, and it is what the real thing does:
        // Mauna Loa's flows are kilometres across, not the same few metres a cinder cone's are.
        //
        // Thinned after testing called the first cut too heavy: it covered 22% of the flank in a few
        // broad bands. More flows and narrower ones takes it to about 10%, which is also the truer
        // arrangement - a cone is built from many thin flows over a long time, not a handful of wide
        // ones. Continuity was re-measured alongside, because narrowing a channel is exactly how you
        // perforate it: still no break in any of 492 centrelines.
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
     * Checks the ground can actually carry this volcano.
     *
     * <p>Far more forgiving than it used to be, because the height-field edifice copes with slopes
     * that the old ring builder could not: what it still refuses is ground that would make the
     * volcano wrong rather than merely awkward - a shoreline, a site that is mostly water, or (for a
     * caldera, which has to excavate a flat floor) seriously broken country.</p>
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
        // Water is refused hard now. A cone that reaches the shoreline leaves a sheer rampart around
        // it, and a caldera cut below a lake drains it, so a site with any real amount of water in
        // its footprint is simply not a volcano site.
        if (wet * 10 > samples) return false;
        if (blank * 8 > samples) return false;      // riddled with voids: no
        // A caldera has to cut a flat floor, so it wants reasonable ground; the others grow happily
        // out of a hillside now that they fill downward to meet it.
        // A caldera has to cut a flat floor over eighty blocks across, so it wants genuinely even
        // ground - the old tolerance turned a hillside into a quarry. The others grow happily out of
        // a slope now that the cone fills downward to meet it.
        int allowed = c.type.excavates() ? 8 + radius / 6 : 24 + radius;
        return (hi - lo) <= allowed;
    }
}
