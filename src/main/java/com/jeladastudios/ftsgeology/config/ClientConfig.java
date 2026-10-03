package com.jeladastudios.ftsgeology.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The player's own settings, kept on their machine (config/fts_geology-client.toml): how the sky and the rain are drawn,
 * what is shown on the rivers, and how many sounds the game may stream. The world's weather itself is the server's (see
 * {@link GeyserConfig}). The rain and the wind are heard through the Feel the Nature addon, which has settings of its own.
 */
public final class ClientConfig {

    private ClientConfig() {}

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue STORM_CLOUDS;
    public static final ForgeConfigSpec.BooleanValue SHADER_OVERCAST;
    public static final ForgeConfigSpec.DoubleValue RAIN_SLANT;
    public static final ForgeConfigSpec.BooleanValue RIVER_FOAM;
    public static final ForgeConfigSpec.BooleanValue RIVER_FOAM_SHEET;
    public static final ForgeConfigSpec.BooleanValue RIVER_DEBRIS;
    public static final ForgeConfigSpec.IntValue SOUND_STREAMS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("sky");
        STORM_CLOUDS = b
                .comment("Draw the weather's own clouds, which drift with the wind and gather into storms, in place of",
                        "vanilla's flat cloud layer. A shader pack that draws its own clouds keeps them, darkened by the rain.")
                .define("stormClouds", true);
        SHADER_OVERCAST = b
                .comment("Under a shader pack, a sky that is overcast wherever it rains: the pack is told the rain is full as",
                        "soon as it really rains where you stand, as vanilla's one weather tells it, and draws its cloud",
                        "cover, darkness and wet ground from that. Off, it is told how hard it rains, and a steady rain",
                        "is half a sky of cloud. The rain itself follows how hard it rains either way.")
                .define("shaderOvercast", true);
        RAIN_SLANT = b
                .comment("How far the wind may drive the rain aslant: the most a drop moves sideways for each block it falls",
                        "(0.35 is about 20 degrees, in a gale; 0 keeps it upright).")
                .defineInRange("rainSlant", 0.35, 0.0, 1.0);
        b.pop();
        b.push("rivers");
        RIVER_FOAM = b
                .comment("Foam on the rivers where they come down a step, and a little on fast water and in rain.")
                .define("riverFoam", true);
        RIVER_FOAM_SHEET = b
                .comment("Draw the foam as a sheet lying on the water, thick at the foot of a fall and drawn out down the",
                        "current, in place of foam particles. Off, the foam is particles, as before.")
                .define("riverFoamSheet", true);
        RIVER_DEBRIS = b
                .comment("Leaves and bits of twig drifting down the rivers, more under trees and in wind and rain.")
                .define("riverDebris", true);
        b.pop();
        b.push("sound");
        SOUND_STREAMS = b
                .comment("How many sounds the game may stream at once: long loops, music and records. Vanilla keeps eight, and",
                        "a pack with ambience, music and weather mods fills them, after which a new loop -- the rain's, the",
                        "wind's -- is not heard at all. Taken from the plain sounds, of which there are some 240. 8 is vanilla.",
                        "Takes effect when the sound engine starts (on launch, or F3+T).")
                .defineInRange("soundStreams", 16, 8, 32);
        b.pop();
        SPEC = b.build();
    }
}
