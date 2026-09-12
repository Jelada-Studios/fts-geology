package com.jeladastudios.ftsgeology.volcano;

/**
 * Where a large volcano stands, on top of its shape: on land, or in the sea at some point of an island's life.
 *
 * <p>An ocean volcano rises from the sea floor. Over a plume or on an island arc it is a live island. Carried off
 * its plume by the plate it goes quiet, is cut down by rain and waves and sinks, until a reef ring round a lagoon
 * is left in warm water, or a flat top drowned under cold water.</p>
 */
public enum VolcanoSetting {
    /** On land. */
    LAND,
    /** A live island rising from the sea floor, over a plume or on an island arc. */
    ISLAND,
    /** An extinct island carried off its plume: valleys, sea cliffs, a fallen flank and, in warm water, a reef. */
    ERODED,
    /** A sunken island in warm water: a ring of reef round a lagoon. */
    ATOLL,
    /** A sunken island in cold water: the flat top the waves cut, now under the sea. */
    GUYOT;

    public boolean ocean() {
        return this != LAND;
    }

    /** True while it still has a live core and erupts. */
    public boolean active() {
        return this == LAND || this == ISLAND;
    }
}
