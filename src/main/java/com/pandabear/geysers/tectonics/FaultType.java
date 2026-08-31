package com.pandabear.geysers.tectonics;

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

    public boolean isConvergent() {
        return this == CONVERGENT_SUBDUCTION || this == CONVERGENT_COLLISION;
    }

    /** True where magma reaches the surface: subduction arcs and spreading rifts. */
    public boolean isVolcanic() {
        return this == CONVERGENT_SUBDUCTION || this == DIVERGENT;
    }

    /** Rough depth character of quakes on this kind of boundary; 0 when it is not seismic. */
    public int typicalQuakeDepth() {
        return switch (this) {
            case CONVERGENT_SUBDUCTION -> 300;
            case CONVERGENT_COLLISION -> 120;
            case TRANSFORM, DIVERGENT -> 40;
            case INTERIOR -> 0;
        };
    }
}
