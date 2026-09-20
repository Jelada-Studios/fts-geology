package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * What the terrain needs to know about the world being generated: its seed and the geology numbers. A density
 * function is built from JSON with neither, and the noise wiring keeps its own random source to itself.
 *
 * <p>{@code ServerAboutToStartEvent} is the right moment for both: Forge loads the server config just before it
 * (checked in {@code ServerLifecycleHooks.handleServerAboutToStart}), and the levels and their random state are
 * made just after. One server runs at a time.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class TerrainContext {

    private TerrainContext() {}

    private static volatile long seed;
    private static volatile boolean known;
    private static volatile boolean warned;

    /**
     * How many times wider the tall world type lays its plates, belts and plumes out. As much as its mountains are
     * taller: scaled up only in height, a 740-block peak stood on a 275-block flank as a wall.
     */
    public static final double TALL_HORIZONTAL = 2.5;

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        seed = event.getServer().getWorldData().worldGenOptions().seed();
        known = true;
        GeologyParams.take(isTall(event.getServer()) ? TALL_HORIZONTAL : 1.0);
        TerrainCache.clear();
        GeologyWorld.clear();
        LandmarkSites.clear();
    }

    /** Whether the overworld about to be made is the tall world type: its stem is in the registry before the levels. */
    private static boolean isTall(net.minecraft.server.MinecraftServer server) {
        net.minecraft.world.level.dimension.LevelStem stem = server.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM)
                .get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        return stem != null
                && stem.generator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise
                && noise.generatorSettings().is(GeologyWorld.SETTINGS_TALL);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        known = false;
        GeologyParams.forget();
        TerrainCache.clear();
        GeologyWorld.clear();
        LandmarkSites.clear();
    }

    /** The current world's seed, or 0 with one warning when no server has announced one. */
    public static long seed() {
        if (!known && !warned) {
            warned = true;
            GeysersMod.LOGGER.warn("Terrain asked for the world seed before a server started; using 0");
        }
        return seed;
    }

    /** The numbers the model runs on. */
    public static GeologyParams params() {
        return GeologyParams.current();
    }
}
