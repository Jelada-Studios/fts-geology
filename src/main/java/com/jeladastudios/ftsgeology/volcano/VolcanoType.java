package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * The shape a volcano takes, decided by its setting: sticky arc magma builds a steep stratocone
 * (Fuji), runny hotspot magma a shield (Mauna Loa) or a caldera (Yellowstone), and a rift a line of
 * fissure ponds (Iceland). Each has its own summit style, flank pattern and rock recipe.
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
            case STRATOVOLCANO -> 1.1;   // was 0.7: a 6-block crater held a 13-cell lava pool
            case SHIELD -> 1.6;
            case FISSURE -> 0.5;
            case CALDERA -> 4.5;   // Yellowstone is sixty kilometres across; this is a landmark
        };
    }

    /** How many blocks of cone are built above the original ground. Zero means no cone. */
    public int coneHeight(int magnitude, RandomSource rng) {
        return switch (this) {
            // Taller than the crater is wide, so the summit reads as a mountain with a notch in it.
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
     * <p>Above 1 the flanks are concave, the stratocone silhouette; below 1 convex, the swell of a
     * shield.</p>
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
