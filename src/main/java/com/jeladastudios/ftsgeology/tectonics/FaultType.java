package com.jeladastudios.ftsgeology.tectonics;

/**
 * What a plate boundary is doing, derived from the relative motion of the two plates that meet
 * there plus their {@link PlateKind}. This is the classification later features hang off: where to
 * put volcanoes, which faults produce deep versus shallow earthquakes, where rift geysers belong.
 */
public enum FaultType {
    /** Plates pulling apart: a rift valley on land, a spreading ridge at sea. Shallow quakes. */
    DIVERGENT,
    /** Oceanic crust diving under the other plate: a volcanic arc and deep, violent quakes. */
    CONVERGENT_SUBDUCTION,
    /** Two continents crumpling together: a high mountain belt, big quakes, no volcanism. */
    CONVERGENT_COLLISION,
    /** Plates grinding past each other: a strike-slip fault, shallow but sharp quakes. */
    TRANSFORM,
    /** Not near a boundary at all: stable plate interior. */
    INTERIOR;

    /**
     * The usual depth of this boundary's quakes in kilometres; 0 when it is not seismic. Most break in the brittle
     * upper crust: a strike-slip fault at ten kilometres, a rift shallower still, a collision's thrust at fifteen, a
     * subduction zone's megathrust at thirty -- where its great quakes are -- though the slab under it quakes as deep
     * as seven hundred.
     */
    public int typicalQuakeDepth() {
        return switch (this) {
            case CONVERGENT_SUBDUCTION -> 30;
            case CONVERGENT_COLLISION -> 15;
            case TRANSFORM -> 10;
            case DIVERGENT -> 8;
            case INTERIOR -> 0;
        };
    }
}
