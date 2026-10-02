package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.worldgen.PetroleumFields;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Small quakes a producing oil field brings on itself. As its oil is taken the reservoir's pressure falls, the rock
 * over it carries more of the weight and compacts, and the old faults through it slip: Groningen's gas field, quiet for
 * thirty years, has shaken since the 1990s, the largest a magnitude 3.6 in 2012. Here, the more of a field's oil is
 * gone, the likelier each barrel taken sets one off -- none in a new field, a felt one every hour or two of steady
 * production in one half spent -- magnitudes 2 to 3.6 by Gutenberg-Richter, under the field near the well. They go the
 * way of the faults' small quakes (see {@link Earthquake#tremor}): felt, recorded, the ground left as it is. The
 * ground's settling over a spent field is centimetres a decade, under a block, and is not shown.
 */
public final class InducedQuakes {

    private InducedQuakes() {}

    /** The chance a millibucket of oil sets one off, in a spent field (it goes as the square of the share taken). */
    private static final double PER_MB = 2.0e-5;
    /** The magnitudes, least to greatest. */
    private static final double SMALLEST = 2.0, LARGEST = 3.6;

    private static long induced;

    /** A well has taken {@code mb} millibuckets of oil from a field of which {@code spent} is gone. */
    public static void produced(ServerLevel level, BlockPos well, PetroleumFields.Field field, double mb, double spent) {
        if (!GeyserConfig.INDUCED_QUAKES.get() || spent < 0.1 || mb <= 0) return;
        if (level.random.nextDouble() >= mb * PER_MB * GeyserConfig.INDUCED_QUAKE_RATE.get() * spent * spent) return;
        double u = level.random.nextDouble();
        double m = SMALLEST - Math.log10(1.0 - u * (1.0 - Math.pow(10.0, -(LARGEST - SMALLEST))));
        double a = level.random.nextDouble() * Math.PI * 2, r = level.random.nextDouble() * Math.min(200.0, Math.max(field.a(), field.b()));
        BlockPos at = well.offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r));
        double s = level.random.nextDouble() * Math.PI;
        induced++;
        com.jeladastudios.ftsgeology.util.Diagnostics.info("induced quake M{} under the oil field at {} {} ({}% of its oil taken)",
                String.format(java.util.Locale.ROOT, "%.1f", m), at.getX(), at.getZ(), Math.round(spent * 100));
        // The faults through a sinking reservoir are normal faults.
        Earthquake.tremor(level, at, FaultType.DIVERGENT, m, Math.cos(s), Math.sin(s), false);
    }

    public static String summary() {
        return "induced quakes under oil fields: " + induced;
    }
}
