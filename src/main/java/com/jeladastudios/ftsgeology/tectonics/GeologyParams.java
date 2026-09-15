package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;

/**
 * Every number the seeded geology model uses: the plate grid, the plumes, and the terrain the plates shape.
 *
 * <p>The model itself is a pure function of the seed and a column, and the density functions call it millions of
 * times while a world generates. Reading the config there would tie the model to a running server and to Forge;
 * instead the numbers are taken once, when a server is about to start, and handed down. That keeps the model
 * testable on its own: {@link #defaults()} builds the same picture with no config and no server.</p>
 */
public record GeologyParams(
        // Plates
        double plateScale,
        double plateJitter,
        double faultWidth,
        /** Share of plates that are oceanic crust, where the mod's own terrain decides crust from the seed. */
        double oceanShare,
        // Plumes
        boolean hotspots,
        double hotspotScale,
        double hotspotDensity,
        double hotspotRadius,
        // Terrain
        /** How far the terrain's shapes reach from a boundary, in fault widths. */
        double beltFactor,
        /** Blocks a collision lifts the ground at the boundary. */
        double uplift) {

    private static final GeologyParams DEFAULTS = new GeologyParams(
            3000.0, 0.8, 220.0, 0.4,
            true, 8500.0, 0.18, 700.0,
            2.5, 80.0);

    /** The numbers with no config behind them: the config's own defaults, for tests and tools. */
    public static GeologyParams defaults() {
        return DEFAULTS;
    }

    public static GeologyParams fromConfig() {
        return new GeologyParams(
                GeyserConfig.PLATE_SCALE.get(),
                GeyserConfig.PLATE_JITTER.get(),
                GeyserConfig.FAULT_WIDTH.get(),
                GeyserConfig.OCEAN_SHARE.get(),
                GeyserConfig.HOTSPOTS_ENABLED.get(),
                GeyserConfig.HOTSPOT_SCALE.get(),
                GeyserConfig.HOTSPOT_DENSITY.get(),
                GeyserConfig.HOTSPOT_RADIUS.get(),
                GeyserConfig.TERRAIN_BELT_FACTOR.get(),
                GeyserConfig.TERRAIN_UPLIFT.get());
    }

    // === The running server's numbers =======================================

    private static volatile GeologyParams current;
    private static volatile boolean warned;

    /** Takes the numbers for a server about to start. Its config is loaded by then. */
    public static void take() {
        current = fromConfig();
    }

    public static void forget() {
        current = null;
    }

    /**
     * The numbers in force. Falls back to the config, and then to {@link #defaults()} with one warning, so a tool
     * or a test that never starts a server still gets a coherent model.
     */
    public static GeologyParams current() {
        GeologyParams p = current;
        if (p != null) return p;
        try {
            p = fromConfig();
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                GeysersMod.LOGGER.warn("Geology asked for its numbers before the config was loaded; using defaults");
            }
            p = DEFAULTS;
        }
        return p;
    }
}
