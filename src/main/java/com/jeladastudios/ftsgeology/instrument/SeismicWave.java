package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.tectonics.DepthScale;

/**
 * The seismology a station actually does. A seismograph does not know where a quake was: it measures
 * the gap between the P and S arrivals and how far the needle swung, and works out distance and
 * magnitude from those. Direction needs more than one station.
 *
 * <p>Speeds are standard upper-crust values; at 25 metres to the block a quake two thousand blocks
 * away gives a gap of about six seconds.</p>
 */
public final class SeismicWave {

    private SeismicWave() {}

    /** P-wave speed in the upper crust, metres per second. */
    public static final double VP = 6000.0;

    /** S-wave speed in the upper crust, metres per second. Always slower; that is the point. */
    public static final double VS = 3500.0;

    /**
     * Smallest swing the needle can draw and still be told apart from the drum's own noise, in
     * millimetres. A mechanical station has a floor; below it an event simply is not on the paper.
     */
    public static final double NOISE_FLOOR_MM = 0.05;

    /**
     * Largest swing the drum records before the pen runs off the paper. Set well above a real
     * Wood-Anderson drum, which would clip on almost every event at Minecraft distances.
     */
    public static final double CLIP_MM = 100_000.0;

    /** Seconds between the P and S arrivals for a hypocentre this far away. */
    public static double spSeconds(double distanceMetres) {
        return distanceMetres * (1.0 / VS - 1.0 / VP);
    }

    /** The inverse: the distance a measured S-minus-P gap implies. This is the useful direction. */
    public static double distanceMetres(double spSeconds) {
        return spSeconds / (1.0 / VS - 1.0 / VP);
    }

    /**
     * Straight-line distance to the hypocentre in metres: the S-P gap measures the path, which for a
     * deep quake runs mostly downward.
     */
    public static double hypocentralMetres(double horizontalBlocks, double depthMetres) {
        double flat = horizontalBlocks * DepthScale.metresPerBlockHorizontal();
        return Math.sqrt(flat * flat + depthMetres * depthMetres);
    }

    /**
     * The distance correction, {@code -log10(A0)}, in the standard Southern California form: a
     * one-millimetre trace at a hundred kilometres is magnitude 3. The straight-line log fit is not
     * used; it drifts by half a unit at these ranges.
     */
    private static double distanceCorrection(double km) {
        double d = Math.max(0.1, km);
        return 1.110 * Math.log10(d / 100.0) + 0.00189 * (d - 100.0) + 3.0;
    }

    /**
     * How far the needle swings for this magnitude at this distance, in millimetres: Richter's
     * definition run backwards.
     */
    public static double amplitudeMm(double magnitude, double distanceMetres) {
        return Math.pow(10.0, magnitude - distanceCorrection(distanceMetres / 1000.0));
    }

    /** And back again: the magnitude a swing of this size at this distance implies. */
    public static double magnitude(double amplitudeMm, double distanceMetres) {
        return Math.log10(Math.max(1.0e-6, amplitudeMm))
                + distanceCorrection(distanceMetres / 1000.0);
    }

    /** True when the swing is big enough to be told from the drum's noise. */
    public static boolean detectable(double amplitudeMm) {
        return amplitudeMm >= NOISE_FLOOR_MM;
    }

    /**
     * Trace size the redstone scale starts from, in millimetres. Real readings run from about ten
     * millimetres to the clip, so anchoring at the noise floor wasted half the scale.
     */
    public static final double SIGNAL_FLOOR_MM = 1.0;

    /**
     * Redstone strength for a swing, 1-15, on a log scale. Keyed to how hard the ground shook here,
     * not to the derived magnitude, since the shaking is what a warning system reacts to.
     */
    public static int signal(double amplitudeMm) {
        if (!detectable(amplitudeMm)) return 0;
        double t = Math.log10(amplitudeMm / SIGNAL_FLOOR_MM)
                / Math.log10(CLIP_MM / SIGNAL_FLOOR_MM);
        return Math.max(1, Math.min(15, (int) Math.round(t * 15.0)));
    }
}
