package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.neoforged.fml.ModList;

/**
 * Whose rivers a world has: the mod's own, or Streams Reflowing's streams round the mod's big rivers (round 163).
 * Chosen when the world is made, on the world creation screen or by the server config, and kept with the world beside
 * its worldgen revision, so a world never changes system under its player: one made with the mod's rivers keeps them
 * if Streams is added later.
 *
 * <p>With Streams the mod keeps only its trunks: the rivers as wide as the river biome is painted on, and its lakes,
 * cut and filled with its own running water. The smaller courses are neither cut nor filled; those are Streams' to
 * draw. Streams was tried on the mod's whole network left dry: its streams ran beside the channels, which stood empty;
 * and with the trunks in plain water, which Streams was expected to set running, the water stood still.</p>
 */
public final class RiverSystem {

    private RiverSystem() {}

    public enum Kind { OURS, STREAMS }

    /** Streams Reflowing's mod id. */
    public static final String STREAMS_MOD = "streamsreflowing";

    private static volatile Kind current = Kind.OURS;
    /** What the world creation screen chose for the next world made in this game; null when it was not asked. */
    private static volatile Kind chosen;

    public static boolean streamsLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(STREAMS_MOD);
    }

    public static Kind current() {
        return current;
    }

    /** Whether the mod lays its water in the channels it cuts (off, they are left dry for another mod to fill). */
    public static boolean ourWater() {
        return GeyserConfig.RIVER_WATER.get();
    }

    /** Whether only the trunks and lakes are the mod's in the world running, the streams Streams'. */
    public static boolean trunksOnly() {
        return current == Kind.STREAMS;
    }

    /** From the world creation screen: the system the next world made in this game gets. */
    public static void choose(Kind kind) {
        chosen = kind;
    }

    /** A new world: what the screen chose, or the server config; the mod's own without Streams. */
    static Kind forNewWorld() {
        Kind c = chosen;
        chosen = null;
        if (!streamsLoaded()) return Kind.OURS;
        if (c != null) return c;
        return GeyserConfig.NEW_WORLD_RIVERS.get();
    }

    static void set(Kind kind) {
        current = kind;
    }
}
