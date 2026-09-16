package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.worldgen.HotSpringShape;
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
import static com.jeladastudios.ftsgeology.volcano.CalderaEdifice.*;
import static com.jeladastudios.ftsgeology.volcano.ApronEdifice.*;
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
        /** Outer edge of a caldera's lava lake, from the centre. */
        double lakeOuter;
        /** A big caldera's round lava lake: its centre and radius. */
        double lakeX, lakeZ, lakeR;
        /** A big caldera's water lake on the floor, across from the lava lake: its centre and radius. */
        double pondX, pondZ, pondR;
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
        /** Lava levels of a big fissure's ponds, by distance along the line, worked out once each. */
        final java.util.Map<Integer, Integer> pondLevels = new java.util.HashMap<>();
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
        /** Exponent of the flank profile: above 1 concave. */
        double flankExponent = 1.0;
        /** Height of the ridges and gullies down the flank; 0 for a smooth one. */
        double ridgeHeight;
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
        /** The ocean half of the plan, for a volcano rising from the sea floor; null on land. */
        OceanEdifice.Isle isle;
        /** How alive it is: a dormant volcano's crater is sealed, an extinct one has no core at all. */
        VolcanoActivity activity = VolcanoActivity.ACTIVE;
        /** The crater cells a dormant volcano keeps crusted over, which turn to lava only while it erupts. */
        final List<BlockPos> seal = new ArrayList<>();
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
        return plan(level, x, baseY, z, magnitude, type, size, rng, plate, VolcanoActivity.ACTIVE);
    }

    /** {@link #plan} for a volcano of a given activity: an extinct one is worn lower, gullied deeper, and has no flows. */
    static Ctx plan(LevelHeightAccessor level, int x, int baseY, int z, int magnitude, VolcanoType type,
                    VolcanoSize size, RandomSource rng, PlateSample plate, VolcanoActivity activity) {
        Ctx c = new Ctx();
        c.activity = activity;
        c.type = type;
        c.size = size;
        c.magnitude = magnitude;
        c.x = x;
        c.z = z;
        c.baseY = baseY;

        c.craterR = size.craterRadius(type, magnitude, rng);
        c.coneHeight = size.coneHeight(type, magnitude, rng);
        // Rain and ice have taken the top off a mountain that stopped growing.
        if (activity == VolcanoActivity.EXTINCT) c.coneHeight = (int) Math.round(c.coneHeight * 0.85);
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
        // A big stratocone has a gentler profile, so its top does not rise into a spike, and carries
        // ridges and gullies instead of a smooth skin.
        c.flankExponent = type == VolcanoType.STRATOVOLCANO && size == VolcanoSize.LARGE
                ? 1.5 : type.flankExponent();
        c.ridgeHeight = type == VolcanoType.STRATOVOLCANO && size != VolcanoSize.SMALL
                ? Math.min(5.0, c.coneHeight * 0.04) : 0.0;
        if (activity == VolcanoActivity.EXTINCT) c.ridgeHeight *= 2.0;

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
                ? Mth.clamp(c.coneBaseR * share, 20.0, type == VolcanoType.STRATOVOLCANO ? 130.0 : 56.0)
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
        // A stratocone keeps to a few flows; a shield carries a few more, but not so many that a big one is
        // combed with evenly spaced radial stripes.
        int moreFlows = rng.nextInt(3);
        c.flows = type == VolcanoType.STRATOVOLCANO ? 2 + moreFlows : 2 + moreFlows + c.coneBaseR / 80;
        c.flowAim = new double[c.flows];
        c.flowPhase = new double[c.flows];
        double spin = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < c.flows; i++) {
            c.flowAim[i] = spin + i * (Math.PI * 2 / c.flows)
                    + (rng.nextDouble() - 0.5) * 0.9;
            c.flowPhase[i] = rng.nextDouble() * Math.PI * 2;
        }
        c.flowReach = Math.max(10, c.coneBaseR) * (1.05 + rng.nextDouble() * 0.45);
        // A big stratocone's or fissure's flows give out a fifth sooner, around the foot, instead of running on
        // far over the country.
        if (size == VolcanoSize.LARGE && (type == VolcanoType.STRATOVOLCANO || type == VolcanoType.FISSURE)) {
            c.flowReach *= 0.8;
        }
        // Flow width scales with the cone, so a big mountain is not threaded with thin lines. Many
        // narrow flows cover about a tenth of the flank without breaking a centreline.
        // Flows are thin tongues, not bands that widen with the mountain; a big shield's are broad sheets
        // rather than threads.
        c.flowWidth = type == VolcanoType.FISSURE || type == VolcanoType.STRATOVOLCANO ? 0.8
                : type == VolcanoType.SHIELD ? (size == VolcanoSize.LARGE ? 2.4 : 1.1)
                : Math.max(1.0, c.coneBaseR / 34.0);
        // An extinct mountain's flows weathered into its soil long ago.
        if (activity == VolcanoActivity.EXTINCT) c.flows = 0;

        // A small caldera is a pit; a big one's floor lies at the level of the land around it, and its
        // depth comes from the plateau rising round it rather than from digging.
        int depth = size.calderaDepth(rng);
        c.calderaFloorY = size == VolcanoSize.SMALL ? c.baseY - depth : c.baseY;
        c.domeR = Math.max(3, c.craterR / 3);
        c.domeH = size.domeHeight(rng);
        c.lakeAngle = rng.nextDouble() * Math.PI * 2;
        c.lakeWidth = Math.PI * (0.45 + rng.nextDouble() * 0.35);
        // A small caldera's lake is a crescent running most of the way to the ring. A big one has a small
        // round lake out towards the ring fault instead, where its eruptions come from.
        if (size == VolcanoSize.SMALL) {
            c.lakeOuter = c.craterR * 0.85;
        } else {
            double lakeDist = CalderaEdifice.ringRadius(c, c.lakeAngle) * 0.72;
            c.lakeR = 4.0 + 3.0 * (c.lakeWidth - Math.PI * 0.45) / (Math.PI * 0.35);
            c.lakeX = c.x + Math.cos(c.lakeAngle) * lakeDist;
            c.lakeZ = c.z + Math.sin(c.lakeAngle) * lakeDist;
            c.lakeOuter = lakeDist + c.lakeR;
            // The water lake in the low half of the floor, as Yellowstone Lake lies in its caldera's south-east.
            double pondAngle = c.lakeAngle + Math.PI + (rng.nextDouble() - 0.5) * 0.8;
            double pondDist = CalderaEdifice.ringRadius(c, pondAngle) * 0.55;
            c.pondR = c.craterR * (0.28 + rng.nextDouble() * 0.06);
            c.pondX = c.x + Math.cos(pondAngle) * pondDist;
            c.pondZ = c.z + Math.sin(pondAngle) * pondDist;
            if (type == VolcanoType.CALDERA) {
                com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Caldera at {},{}: floor {}, ring {}, pond at {},{} r {}",
                        c.x, c.z, c.calderaFloorY, c.craterR, Math.round(c.pondX), Math.round(c.pondZ), Math.round(c.pondR));
            }
        }
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
     * {@link #plan}, and for a volcano rising from the sea floor its island: how high it stood, how far it has sunk,
     * its rift zones, reef and shore. {@code baseY} is then the sea floor.
     */
    static Ctx plan(LevelHeightAccessor level, int x, int baseY, int z, int magnitude, VolcanoType type,
                    VolcanoSize size, RandomSource rng, PlateSample plate, VolcanoSetting setting, double age,
                    int seaY, double seaTemp, VolcanoActivity activity) {
        Ctx c = plan(level, x, baseY, z, magnitude, type, size, rng, plate, activity);
        if (c == null || !setting.ocean()) return c;
        OceanEdifice.plan(c, rng, setting, age, seaY, seaTemp);
        return c;
    }

    /**
     * Checks the ground can carry this volcano: refuses a shoreline, a mostly wet site, a hot spring
     * anywhere under the cone or apron, or, for a caldera, which excavates a flat floor, seriously
     * broken country.
     */
    static boolean siteIsSuitable(ServerLevel level, Ctx c) {
        int centre = TerrainProbe.groundY(level, c.x, c.z);
        if (centre == Integer.MIN_VALUE) return false;
        if (centre <= level.getSeaLevel() + 2) return false;      // not on a shoreline or seabed
        if (centre <= level.getMinBuildHeight() + 24) return false;

        int radius = Math.max(8, c.coneBaseR);
        // Springs are looked for out to the apron's edge: rock raised round a pool stops at its water and
        // leaves the pool as a pit in the mountain.
        int springRadius = Math.max(radius, c.apronReach);
        int lo = centre, hi = centre, wet = 0, blank = 0, samples = 0;
        int stepSize = Math.max(2, radius / 8);
        for (int dx = -springRadius; dx <= springRadius; dx += stepSize) {
            for (int dz = -springRadius; dz <= springRadius; dz += stepSize) {
                int d2 = dx * dx + dz * dz;
                if (d2 > springRadius * springRadius) continue;
                boolean inner = d2 <= radius * radius;
                int sx = c.x + dx, sz = c.z + dz;
                if (!inner && !level.hasChunk(sx >> 4, sz >> 4)) continue;
                if (inner) samples++;
                int g = TerrainProbe.groundY(level, sx, sz);
                if (g == Integer.MIN_VALUE) {
                    if (inner) blank++;
                    continue;
                }
                boolean fluid = !level.getBlockState(new BlockPos(sx, g + 1, sz)).getFluidState().isEmpty();
                if (HotSpringShape.isSpringGround(level.getBlockState(new BlockPos(sx, g, sz)), fluid)) return false;
                if (!inner) continue;
                if (fluid) wet++;
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
