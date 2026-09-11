package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.util.RandomSource;

/**
 * How big a volcano is, on top of what shape it is.
 *
 * <h2>Small is the volcano the mod always had</h2>
 * Every small figure below is the formula the builder used before sizes existed, so a small volcano
 * comes out exactly as one used to. Medium roughly doubles the edifice and is still built into a
 * loaded world from the retrogen queue. Large is mountain-range sized - a stratocone about 150 blocks
 * tall, a shield about 70 tall and 300 out, a caldera 400 across - and is only ever raised while new
 * terrain is being generated, because building one into a live world means rewriting a few hundred
 * chunks that players may be standing in.
 */
public enum VolcanoSize {
    SMALL, MEDIUM, LARGE;

    /** A magnitude for this size from a 0..1 roll. It drives how hard the volcano erupts. */
    public int magnitude(double roll) {
        return switch (this) {
            case SMALL -> 8 + (int) (roll * 9);      // 8..16
            case MEDIUM -> 17 + (int) (roll * 3);    // 17..19
            case LARGE -> 22 + (int) (roll * 7);     // 22..28
        };
    }

    /** The size a volcano placed after generation gets for its magnitude. Never large. */
    public static VolcanoSize forMagnitude(int magnitude) {
        return magnitude >= 17 ? MEDIUM : SMALL;
    }

    int craterRadius(VolcanoType type, int magnitude, RandomSource rng) {
        if (this == SMALL) {
            return Math.max(2, (int) Math.round(
                    (GeyserConfig.VOLCANO_CRATER_RADIUS.get() + magnitude / 3.0 + 1)
                            * type.craterScale() * (0.85 + rng.nextDouble() * 0.3)));
        }
        int base = switch (type) {
            case STRATOVOLCANO -> this == MEDIUM ? 13 : 22;
            // Capped well short of what the width would suggest: the whole summit lake is molten, and
            // a lake the size of the mountain would be thousands of lava cells for the core to track.
            case SHIELD -> this == MEDIUM ? 16 : 18;
            case CALDERA -> this == MEDIUM ? 70 : 200;
            case FISSURE -> this == MEDIUM ? 3 : 4;
        };
        return Math.max(2, (int) Math.round(base * (0.9 + rng.nextDouble() * 0.2)));
    }

    int coneHeight(VolcanoType type, int magnitude, RandomSource rng) {
        if (this == SMALL) return type.coneHeight(magnitude, rng);
        return switch (type) {
            case STRATOVOLCANO -> this == MEDIUM ? 44 + rng.nextInt(12) : 112 + rng.nextInt(15);
            case SHIELD -> this == MEDIUM ? 22 + rng.nextInt(6) : 62 + rng.nextInt(12);
            case FISSURE, CALDERA -> 0;
        };
    }

    /**
     * Width against height. A shield keeps most of its breadth as it grows but not all of it: at the
     * small shield's 1:7 a large one would run 500 blocks out, and a medium one would be too wide to
     * build after generation at all. A large one at 1:3.4 is some 250 blocks to its foot.
     */
    double coneSlope(VolcanoType type) {
        if (type == VolcanoType.SHIELD && this != SMALL) return this == MEDIUM ? 5.0 : 3.4;
        // A big stratocone twice as wide as it is tall, so its flanks are not a spike.
        if (type == VolcanoType.STRATOVOLCANO && this == LARGE) return 2.0;
        return type.coneSlope();
    }

    /**
     * How far the debris apron runs past the foot, as a share of the base radius. It is there to blend
     * the mountain into the ground, which needs a few tens of blocks and not a third of a shield's width.
     * A caldera keeps its own share because its apron starts outside the ring fault and has to clear it.
     */
    double apronReach(VolcanoType type) {
        if (this == SMALL || type == VolcanoType.CALDERA) return type.apronReach();
        if (this == MEDIUM) return Math.min(type.apronReach(), 0.35);
        return switch (type) {
            case FISSURE -> 0.30;
            case STRATOVOLCANO -> 0.45;      // room for its foothills
            default -> 0.22;
        };
    }

    /** Half the length of a fissure's line. */
    int fissureHalfLength(int magnitude, RandomSource rng) {
        return switch (this) {
            case SMALL -> 6 + magnitude;
            case MEDIUM -> 70 + rng.nextInt(11);
            case LARGE -> 190 + rng.nextInt(21);
        };
    }

    /** Height of a caldera's ring scarp at its highest. */
    double rimLift(int magnitude) {
        return switch (this) {
            case SMALL -> 3 + magnitude / 5.0;
            case MEDIUM -> 12;
            case LARGE -> 28;
        };
    }

    /** How far out a caldera's ring scarp takes to come back down to the ground. */
    int rimWidth() {
        return switch (this) {
            case SMALL -> 6;
            // A big caldera's rim is a plateau, not a wall.
            case MEDIUM -> 30;
            case LARGE -> 90;
        };
    }

    /** How far a caldera's floor is sunk below the surrounding ground. */
    int calderaDepth(RandomSource rng) {
        return switch (this) {
            case SMALL -> 3 + rng.nextInt(5);
            case MEDIUM -> 6 + rng.nextInt(6);
            case LARGE -> 12 + rng.nextInt(10);
        };
    }

    /** Height of the resurgent dome in a caldera's floor. */
    int domeHeight(RandomSource rng) {
        return switch (this) {
            case SMALL -> 3 + rng.nextInt(4);
            case MEDIUM -> 6 + rng.nextInt(6);
            case LARGE -> 14 + rng.nextInt(10);
        };
    }
}
