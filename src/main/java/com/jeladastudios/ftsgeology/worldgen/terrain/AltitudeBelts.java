package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.util.ValueNoise;
import net.minecraft.util.Mth;

/**
 * The belts a mountain is clothed in by height, as every real range is: forest at its foot, the trees thinning and
 * shrinking to the tree line, alpine meadow over it, then scree and bare rock, and snow only at the top. Where each
 * belt begins comes from the climate of the country round the range, cooled with height at the real rate.
 *
 * <p>The mountains were one biome from foot to summit, a cold one, which vanilla's own cooling with height turned to
 * snow a few dozen blocks over the sea: a range was white from the valley floor up. Real ranges are mostly forest and
 * meadow -- the Alps are forest to about two thousand metres and keep their snow over three thousand; the tropical
 * Andes are forest to four thousand. Here the mean yearly temperature at the sea is read off the climate's own
 * temperature; the summer is warmer than the year by more the colder and more continental the country (a tropical
 * summer two degrees over its year, a boreal one ten, an arctic one fourteen), and it falls six and a half degrees a
 * kilometre up, at the world's own scale: ten metres a block in the tall world, twenty-five in the normal one. Trees
 * stop where the summer is about eight degrees, as they do from Lapland to the Andes, and the snow stays where it is
 * below freezing all summer; the lines waver a little from place to place, as real lines do. A boreal range is forest
 * at its foot and snow a kilometre and a half up; the Alps' and the Caucasus' lines come out where theirs are, and a
 * tropical range's tree line over three kilometres up.</p>
 */
public final class AltitudeBelts {

    private AltitudeBelts() {}

    /** The belts, foot to top; the keys they go by in a world preset's biome list. */
    public enum Belt {
        MONTANE("montane_forest"), SUBALPINE("subalpine_forest"), TREELINE("treeline"), MEADOW("alpine_meadow"),
        SCREE("alpine_scree"), SNOW("snowfield");

        public final String key;

        Belt(String key) {
            this.key = key;
        }
    }

    /** Where each belt starts going up, as the mean summer temperature there, degrees Celsius. */
    private static final double SUBALPINE_BELOW = 14.0, TREELINE_BELOW = 10.0, MEADOW_BELOW = 8.0, SCREE_BELOW = 4.0,
            SNOW_BELOW = 0.5;
    /** Degrees lost a metre up. */
    private static final double LAPSE = 0.0065;
    /** How far the lines waver, in degrees, over a long and a short wave, in blocks. */
    private static final double WAVER = 0.9, WAVER_FINE = 0.35, WAVE = 56.0, WAVE_FINE = 14.0;

    /**
     * The mean yearly temperature at the sea under a column, from the climate's temperature parameter (-1 to 1, the
     * value vanilla picks its biomes by): frozen country about ten below, the cold band's taiga near freezing, the
     * temperate band's forests ten to fifteen, the hot band's savanna and desert twenty and more.
     */
    public static double seaLevel(double t) {
        if (t <= -1.0) return -10.0;
        if (t < -0.45) return Mth.lerp((t + 1.0) / 0.55, -10.0, -3.0);
        if (t < -0.15) return Mth.lerp((t + 0.45) / 0.30, -3.0, 4.0);
        if (t < 0.2) return Mth.lerp((t + 0.15) / 0.35, 4.0, 13.0);
        if (t < 0.55) return Mth.lerp((t - 0.2) / 0.35, 13.0, 20.0);
        if (t < 1.0) return Mth.lerp((t - 0.55) / 0.45, 20.0, 28.0);
        return 28.0;
    }

    /**
     * The mean summer temperature over a mean yearly one: warmer by half the year's swing, which is small in the tropics
     * and by the sea and large in cold continental country.
     */
    public static double summer(double year) {
        return year + Mth.clamp(11.0 - 0.3 * year, 2.0, 14.0);
    }

    /** The column's own wavering of the lines, in degrees. */
    public static double waver(int x, int z) {
        return WAVER * ValueNoise.noise(x + 7919, z - 3571, WAVE) + WAVER_FINE * ValueNoise.noise(x - 211, z + 1009, WAVE_FINE);
    }

    /** The temperature at height {@code y} over a sea-level one, {@code metres} a block. */
    public static double at(double seaC, int y, int sea, double metres) {
        return seaC - LAPSE * (y - sea) * metres;
    }

    public static Belt belt(double c) {
        if (c >= SUBALPINE_BELOW) return Belt.MONTANE;
        if (c >= TREELINE_BELOW) return Belt.SUBALPINE;
        if (c >= MEADOW_BELOW) return Belt.TREELINE;
        if (c >= SCREE_BELOW) return Belt.MEADOW;
        if (c >= SNOW_BELOW) return Belt.SCREE;
        return Belt.SNOW;
    }

    /** The height, in blocks, a belt begins at over a sea-level temperature: for reports. */
    public static int lineOf(double seaC, double below, int sea, double metres) {
        return (int) Math.round(sea + (seaC - below) / (LAPSE * metres));
    }

    public static int treeLine(double seaC, int sea, double metres) {
        return lineOf(seaC, MEADOW_BELOW, sea, metres);
    }

    public static int snowLine(double seaC, int sea, double metres) {
        return lineOf(seaC, SNOW_BELOW, sea, metres);
    }
}
