package com.jeladastudios.ftsgeology.tectonics;

/**
 * Crust type of a tectonic plate. Decided by sampling the biome at the plate centre through the
 * world's own {@code BiomeSource}, so the classification follows whatever terrain mod is installed
 * (Terralith, Tectonic, vanilla) rather than a guess of our own.
 *
 * <p>The distinction drives what happens where two plates converge: oceanic crust is denser, so it
 * subducts under continental crust and builds a volcanic arc, whereas two continents crumple into a
 * mountain belt with no volcanism.</p>
 */
public enum PlateKind {
    /** Thin, dense crust under an ocean. Subducts when it meets anything else. */
    OCEANIC,
    /** Thick, buoyant crust carrying land. Never subducts; it crumples instead. */
    CONTINENTAL;

    public boolean isOceanic() {
        return this == OCEANIC;
    }
}
