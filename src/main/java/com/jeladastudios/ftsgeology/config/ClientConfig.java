package com.jeladastudios.ftsgeology.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The player's own settings, kept on their machine (config/fts_geology-client.toml): how loud the rain and the wind are,
 * and how the sky is drawn. The world's weather itself is the server's (see {@link GeyserConfig}).
 */
public final class ClientConfig {

    private ClientConfig() {}

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue RAIN_VOLUME;
    public static final ForgeConfigSpec.DoubleValue WIND_VOLUME;
    public static final ForgeConfigSpec.BooleanValue STORM_CLOUDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("sound");
        RAIN_VOLUME = b
                .comment("How loud the rain recordings play, 1 as mixed; also under the game's own Weather slider.")
                .defineInRange("rainVolume", 1.0, 0.0, 1.5);
        WIND_VOLUME = b
                .comment("How loud the wind is heard in the open, 1 as mixed (a breath of it); 0 turns it off.")
                .defineInRange("windVolume", 1.0, 0.0, 3.0);
        b.pop();
        b.push("sky");
        STORM_CLOUDS = b
                .comment("Draw the weather's own clouds, which drift with the wind and gather into storms, in place of",
                        "vanilla's flat cloud layer. A shader pack that draws its own clouds keeps them, darkened by the rain.")
                .define("stormClouds", true);
        b.pop();
        SPEC = b.build();
    }
}
