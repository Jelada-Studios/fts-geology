package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The seed of the world being generated, for the density functions: a density function is built from JSON with no
 * seed of its own, and the noise wiring keeps its random source to itself. {@code ServerAboutToStartEvent} fires
 * before the levels and their random state are made, on the dedicated and the integrated server alike, and one
 * server runs at a time.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class WorldSeed {

    private WorldSeed() {}

    private static volatile long seed;
    private static volatile boolean known;
    private static volatile boolean warned;

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        seed = event.getServer().getWorldData().worldGenOptions().seed();
        known = true;
        TerrainCache.clear();
        GeologyWorld.clear();
    }

    /** The current world's seed, or 0 with one warning when no server has announced one. */
    public static long current() {
        if (!known && !warned) {
            warned = true;
            GeysersMod.LOGGER.warn("Terrain asked for the world seed before a server started; using 0");
        }
        return seed;
    }
}
