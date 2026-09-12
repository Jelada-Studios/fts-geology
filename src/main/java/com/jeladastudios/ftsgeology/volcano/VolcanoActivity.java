package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.util.SeedHash;

/**
 * How alive a large volcano is. Most of the mountains a plate boundary has built stopped erupting long ago: an arc
 * carries a few live cones among dormant ones and the worn stumps of extinct ones, and a plume leaves its old
 * volcanoes behind as the plate carries them off.
 */
public enum VolcanoActivity {
    /** Erupts on its own cycle, with lava in its crater. */
    ACTIVE,
    /** Magma sealed under a cold crater floor: fumaroles and hot ground, and now and then, or after a big quake, it wakes. */
    DORMANT,
    /** Cold for good: no core, no lava, weathered and grown over. */
    EXTINCT;

    /** The activity of a large volcano planned at this point, from the seed alone, so every chunk agrees on it. */
    public static VolcanoActivity of(long seed, int x, int z, VolcanoType type, VolcanoSetting setting) {
        return switch (setting) {
            case ERODED, ATOLL, GUYOT -> EXTINCT;
            // The shield over its plume is the live one; a flooded caldera's young cone sleeps between eruptions.
            case ISLAND -> type == VolcanoType.SHIELD ? ACTIVE : type == VolcanoType.CALDERA ? DORMANT
                    : roll(seed, x, z, 0.4, 0.3);
            case LAND -> switch (type) {
                // Most of an arc's cones are quiet: the Cascades have erupted from two in a century.
                case STRATOVOLCANO -> roll(seed, x, z, 0.4, 0.3);
                case SHIELD -> roll(seed, x, z, 0.6, 0.2);
                case CALDERA -> roll(seed, x, z, 0.4, 0.45);
                // A fissure has no vent to seal: it is still erupting or long over.
                case FISSURE -> roll(seed, x, z, 0.75, 0.0);
            };
        };
    }

    private static VolcanoActivity roll(long seed, int x, int z, double active, double dormant) {
        double r = SeedHash.rand01(SeedHash.hash(seed, x, z, 0xAC71L));
        return r < active ? ACTIVE : r < active + dormant ? DORMANT : EXTINCT;
    }
}
