package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.world.level.LevelReader;

import java.util.Locale;

/**
 * Maps Minecraft blocks onto real geological depths.
 *
 * <h2>Why this exists</h2>
 * A vanilla world is only 384 blocks tall, but the crust it is standing in for is tens of
 * kilometres thick and the mantle below it hundreds. Rather than deepening the world - which would
 * mean overriding the dimension height AND the noise settings that Terralith and Tectonic install,
 * breaking both of them and every existing save - the mod keeps the world exactly as it is and
 * treats it as a SCALED crust. One block stands for {@code metresPerBlock} metres.
 *
 * <p>Everything below bedrock is then modelled numerically instead of being built: an earthquake
 * hypocentre at 300 km, a convection cell, the slab pulling a plate along. None of that needs
 * blocks to exist, and reporting it in real units is far more useful for teaching than counting
 * blocks would be.</p>
 */
public final class DepthScale {

    private DepthScale() {}

    /** Real-world thickness of continental crust, in metres - the reference the scale is fitted to. */
    public static final double CONTINENTAL_CRUST_M = 35_000.0;

    /** How many metres one block of depth represents. */
    public static double metresPerBlock() {
        return GeyserConfig.METRES_PER_BLOCK.get();
    }

    /**
     * How many metres one block of HORIZONTAL distance represents.
     *
     * <p>Deliberately a different number from {@link #metresPerBlock()}. The world is squashed
     * vertically - 384 blocks standing in for tens of kilometres of crust - but it is not squashed
     * horizontally: a Minecraft biome is already kilometres across. Feeding the vertical scale into
     * surface-rupture length made an M5.5 rift earthquake tear only 42 blocks, which is why fault
     * ruptures looked far too short for their magnitude.</p>
     */
    public static double metresPerBlockHorizontal() {
        return GeyserConfig.METRES_PER_BLOCK_HORIZONTAL.get();
    }

    /**
     * Depth below the surface, in metres, of a block at {@code y} in a column whose ground level is
     * {@code surfaceY}. Negative above ground.
     */
    public static double depthMetres(int surfaceY, int y) {
        return (surfaceY - y) * metresPerBlock();
    }

    /** Depth in metres of the very bottom of the buildable world, i.e. the base of our scaled crust. */
    public static double crustBaseMetres(LevelReader level, int surfaceY) {
        return (surfaceY - level.getMinBuildHeight()) * metresPerBlock();
    }

    /**
     * Formats a depth for display, switching to kilometres once it gets large - so a shallow
     * transform quake reads as "12.4 km" and a deep slab quake as "310 km".
     */
    public static String format(double metres) {
        double abs = Math.abs(metres);
        if (abs >= 1000.0) return String.format(Locale.ROOT, "%.1f km", metres / 1000.0);
        return String.format(Locale.ROOT, "%.0f m", metres);
    }

    /**
     * Converts a virtual depth in metres back to a block Y, clamped into the world. Used when a
     * modelled event that lives below bedrock still has to show its effects somewhere real.
     */
    public static int blockYForDepth(LevelReader level, int surfaceY, double metres) {
        int y = surfaceY - (int) Math.round(metres / metresPerBlock());
        return Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, y));
    }
}
