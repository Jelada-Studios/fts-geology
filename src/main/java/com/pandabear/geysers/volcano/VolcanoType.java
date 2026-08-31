package com.pandabear.geysers.volcano;

import com.pandabear.geysers.tectonics.FaultType;
import com.pandabear.geysers.tectonics.HotspotMap;
import com.pandabear.geysers.tectonics.PlateSample;
import com.pandabear.geysers.tectonics.TectonicMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * The shape a volcano takes.
 *
 * <h2>Why this is decided by tectonics</h2>
 * In the real world you do not get to choose: the setting dictates the volcano. Magma that has
 * squeezed through continental crust above a subduction zone is thick and sticky, so it piles up
 * into the steep, layered cone everybody pictures - Fuji, Ararat, St Helens. Magma rising straight
 * from the mantle at a hotspot is runny, so it spreads out into a vast gentle shield like Mauna Loa,
 * or blows its roof off and leaves a caldera like Yellowstone. At a rift the crust is simply pulled
 * open and lava wells out along the crack, building no cone at all - that is Iceland.
 *
 * <h2>What separates them on the ground</h2>
 * The four used to differ only in how wide the crater was and how tall the cone got, which is why
 * they all read as the same mound at different sizes. Each now has its own <b>summit style</b>, its
 * own <b>flank pattern</b> for where lava outlets sit, and its own <b>rock recipe</b>, so you can
 * tell which setting you are standing in without opening a command.
 */
public enum VolcanoType {
    /** Steep layered cone with a narrow funnel crater. Subduction arcs: Fuji, St Helens. */
    STRATOVOLCANO,
    /** Broad, low, gently sloping dome holding a wide shallow lava lake. Hotspots: Mauna Loa. */
    SHIELD,
    /** No cone: a line of spatter ramparts and elongated lava ponds along the crack. Iceland. */
    FISSURE,
    /** A collapsed giant: a huge excavated depression ringed by a fault scarp. Yellowstone. */
    CALDERA;

    /** How a volcano's summit is finished. */
    public enum SummitStyle {
        /** A narrow crater that funnels down to a small lava pool far below the rim. */
        FUNNEL_PIT,
        /** A wide, shallow, irregular lava lake barely contained by a low rim. */
        LAVA_LAKE,
        /** A line of elongated ponds seated along the fault strike. */
        FISSURE_PONDS,
        /** An excavated floor with a ring-fault scarp and a resurgent dome in the middle. */
        COLLAPSE_FLOOR
    }

    /** Where the lava outlets on the flanks are scattered. */
    public enum VentPattern {
        /** Clustered on the upper flanks, near the summit. */
        UPPER_FLANK,
        /** Spread far out in all directions, following lava tubes. */
        RADIAL_FAR,
        /** Strung out along the fault strike. */
        ALONG_STRIKE,
        /** Around the ring fault of a caldera. */
        RING_FAULT
    }

    /** Picks the shape that belongs to this location, with a little variation inside each setting. */
    public static VolcanoType forLocation(ServerLevel level, int x, int z, int magnitude,
                                          RandomSource rng) {
        HotspotMap.Hotspot hot = HotspotMap.sample(level, x, z);
        if (hot.strength() > 0.0) {
            // A really large hotspot volcano has usually emptied its chamber at least once.
            return (magnitude >= 16 && rng.nextInt(3) == 0) ? CALDERA : SHIELD;
        }
        PlateSample s = TectonicMap.sample(level, x, z);
        return switch (s.faultType()) {
            case DIVERGENT -> FISSURE;
            case CONVERGENT_SUBDUCTION -> STRATOVOLCANO;
            // A hotspot trail or anywhere else volcanic enough to get here: modest cones.
            default -> rng.nextInt(3) == 0 ? SHIELD : STRATOVOLCANO;
        };
    }

    public SummitStyle summitStyle() {
        return switch (this) {
            case STRATOVOLCANO -> SummitStyle.FUNNEL_PIT;
            case SHIELD -> SummitStyle.LAVA_LAKE;
            case FISSURE -> SummitStyle.FISSURE_PONDS;
            case CALDERA -> SummitStyle.COLLAPSE_FLOOR;
        };
    }

    public VentPattern ventPattern() {
        return switch (this) {
            case STRATOVOLCANO -> VentPattern.UPPER_FLANK;
            case SHIELD -> VentPattern.RADIAL_FAR;
            case FISSURE -> VentPattern.ALONG_STRIKE;
            case CALDERA -> VentPattern.RING_FAULT;
        };
    }

    /** True when this type digs a depression instead of piling up a cone. */
    public boolean excavates() {
        return this == CALDERA;
    }

    /** Multiplier on the summit crater radius. */
    public double craterScale() {
        return switch (this) {
            case STRATOVOLCANO -> 0.7;
            case SHIELD -> 1.6;
            case FISSURE -> 0.5;
            case CALDERA -> 4.5;   // Yellowstone is sixty kilometres across; this is a landmark
        };
    }

    /** How many blocks of cone are built above the original ground. Zero means no cone. */
    public int coneHeight(int magnitude, RandomSource rng) {
        return switch (this) {
            // Taller than the crater is wide, on purpose. At the old 8 + magnitude/2 a modest cone
            // ended up barely higher than its own crater was deep, so the summit WAS the crater and
            // the thing read as a ring of rock rather than as a mountain with a notch in the top.
            case STRATOVOLCANO -> 12 + magnitude * 4 / 5 + rng.nextInt(8);
            case SHIELD -> 3 + magnitude / 3 + rng.nextInt(4);
            case FISSURE, CALDERA -> 0;    // neither builds an edifice
        };
    }

    /** How wide the cone is relative to its height - a shield is far broader than a stratocone. */
    public double coneSlope() {
        return switch (this) {
            case STRATOVOLCANO -> 1.2;   // steep
            case SHIELD -> 7.0;          // Mauna Loa is a hundred kilometres wide and barely rises
            case CALDERA -> 2.2;
            case FISSURE -> 0.0;
        };
    }

    /**
     * Exponent of the flank profile.
     *
     * <p>Above 1 the flanks are <b>concave</b>: the ground falls away fast just below the summit and
     * flattens out toward the base, which is the classic stratocone silhouette. Below 1 they are
     * convex - the long, almost imperceptible swell of a shield. This is the single number that most
     * separates a Fuji from a Mauna Loa when you look at one from a distance.</p>
     */
    public double flankExponent() {
        return switch (this) {
            case STRATOVOLCANO -> 1.8;
            case SHIELD -> 0.85;
            case CALDERA -> 1.2;
            case FISSURE -> 1.0;
        };
    }

    /** Multiplier on how many lava outlets dot the flanks. */
    public double ventScale() {
        return switch (this) {
            case SHIELD -> 1.6;
            case FISSURE -> 1.9;
            case CALDERA -> 1.2;
            case STRATOVOLCANO -> 0.7;
        };
    }

    /**
     * How far past the edifice its own debris apron reaches, as a multiple of the cone base radius.
     * A shield buries the countryside under vast thin pahoehoe sheets; a stratocone drops a much
     * tighter ring of ash and lahar deposits.
     */
    public double apronReach() {
        return switch (this) {
            case SHIELD -> 0.45;
            case CALDERA -> 0.60;     // the ash fall from a caldera-forming eruption is enormous
            case STRATOVOLCANO -> 0.45;
            case FISSURE -> 0.90;     // a flood-basalt field spreading downslope
        };
    }
}
