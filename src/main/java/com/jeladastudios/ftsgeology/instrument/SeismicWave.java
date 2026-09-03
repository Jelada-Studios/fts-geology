package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.tectonics.DepthScale;

/**
 * The seismology a station actually does.
 *
 * <h2>Why a station cannot just be told the answer</h2>
 * A seismograph does not know where an earthquake was. It knows two things it measured off its own
 * drum: how long the ground shook before the second kind of wave arrived, and how far the needle
 * swung. Everything else - distance, magnitude - is <em>worked out</em> from those two numbers, and
 * the direction cannot be worked out at all from one station. That is the whole reason seismic
 * networks exist, and it is why this class exists rather than the block simply printing the
 * magnitude the quake was created with.
 *
 * <h2>The two waves</h2>
 * An earthquake radiates a compressional <b>P wave</b> and a slower shear <b>S wave</b> from the
 * same instant at the same place. The P wave always arrives first, and the gap between them grows
 * with distance - about one second per eight kilometres in the upper crust. Measure the gap, and
 * you have the distance to the hypocentre without knowing anything else. This is the first thing
 * anyone is taught about reading a seismogram, and it works here exactly as it does in the field.
 *
 * <p>Speeds are the standard upper-crust values. They are not tuned for gameplay: at the mod's
 * default horizontal scale of 25 metres to the block, the real numbers already land in a readable
 * range - a quake two thousand blocks off gives a gap of about six seconds.</p>
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
     * Largest swing the drum can record before the pen runs off the paper.
     *
     * <p>Set high on purpose. A real Wood-Anderson drum clips at a few tens of millimetres, and at
     * the distances a Minecraft world spans - the whole playable area is within about a hundred
     * kilometres of anywhere - it would clip on almost every event, so the station would under-read
     * constantly and teach the wrong thing. This one only goes off scale for something enormous
     * more or less underneath it, which keeps the clip as an interesting edge case rather than the
     * normal outcome. It is a real effect, and it is exactly why a big quake is measured from
     * distant stations rather than close ones.</p>
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
     * Straight-line distance from a station to the hypocentre, in metres.
     *
     * <p>Hypocentral, not epicentral: the S-P gap measures the path the waves actually travelled,
     * and for a deep quake that is mostly downward. A slab event three hundred kilometres under
     * your feet gives a long gap even though the epicentre is right there, which is a real and
     * genuinely surprising thing to discover with the instrument in hand.</p>
     */
    public static double hypocentralMetres(double horizontalBlocks, double depthMetres) {
        double flat = horizontalBlocks * DepthScale.metresPerBlockHorizontal();
        return Math.sqrt(flat * flat + depthMetres * depthMetres);
    }

    /**
     * The distance correction, {@code -log10(A0)}: how much of a trace is explained by the distance
     * the waves travelled rather than by the size of the earthquake.
     *
     * <p>This is the whole substance of a magnitude scale. Richter's insight was that two stations
     * at different distances draw wildly different traces for the same event, and that the
     * difference between them follows a curve you can measure once and then subtract forever after.
     * The standard Southern California form is used here - anchored so that a trace of one
     * millimetre at a hundred kilometres is magnitude 3, which is Richter's own definition of the
     * zero point.</p>
     *
     * <p>A straight-line fit in log distance is sometimes quoted instead. It is not used: it is
     * only calibrated over a few hundred kilometres, and at the range a Minecraft world spans it
     * drifts badly enough to put the derived magnitude out by half a unit.</p>
     */
    private static double distanceCorrection(double km) {
        double d = Math.max(0.1, km);
        return 1.110 * Math.log10(d / 100.0) + 0.00189 * (d - 100.0) + 3.0;
    }

    /**
     * How far the needle swings for a quake of this magnitude at this distance, in millimetres.
     *
     * <p>Richter's definition run backwards. He defined magnitude as the log of the trace amplitude
     * corrected for distance, so a station of known distance draws a trace of
     * {@code A = 10^(M - correction)}. Going in this direction is what lets the block record a
     * measurement rather than being handed the answer.</p>
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
     * Trace size the redstone scale starts counting from, in millimetres.
     *
     * <p>Not the noise floor. The floor is where an event stops being visible at all, and it sits
     * far below anything a player will actually see: measured across the range, real readings run
     * from about ten millimetres to the clip, so anchoring the scale at the floor wasted the bottom
     * half of it and every quake from a tremor to a disaster came out between 6 and 15. One
     * millimetre is a trace you can just see on the paper, which is the right place for a signal of
     * 1 to mean "something happened".</p>
     */
    public static final double SIGNAL_FLOOR_MM = 1.0;

    /**
     * Redstone strength for a swing, 1-15, on a log scale.
     *
     * <p>Log rather than linear because amplitude spans several orders of magnitude between a
     * tremor you would not feel and one that flattens a hill - and because that is what a magnitude
     * scale is for in the first place.</p>
     *
     * <p>Keyed to how hard the ground shook <em>here</em>, not to the magnitude the station worked
     * out. A huge earthquake far away and a small one next door can share a magnitude reading and
     * mean completely different things to a building, and it is the shaking that a warning system
     * exists to react to.</p>
     */
    public static int signal(double amplitudeMm) {
        if (!detectable(amplitudeMm)) return 0;
        double t = Math.log10(amplitudeMm / SIGNAL_FLOOR_MM)
                / Math.log10(CLIP_MM / SIGNAL_FLOOR_MM);
        return Math.max(1, Math.min(15, (int) Math.round(t * 15.0)));
    }
}
