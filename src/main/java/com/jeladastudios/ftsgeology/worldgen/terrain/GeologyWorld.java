package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a level's terrain is the mod's own: generated with the {@code fts_geology:geology} noise settings of the
 * "FT's Geology" world type. There the plates shaped the ground, so the crust type of a plate is the one the seed
 * gave it; in any other world the plates are laid over ground made without them and read their crust from the
 * biomes as before.
 */
public final class GeologyWorld {

    private GeologyWorld() {}

    public static final ResourceKey<NoiseGeneratorSettings> SETTINGS =
            ResourceKey.create(Registries.NOISE_SETTINGS, new ResourceLocation(GeysersMod.MODID, "geology"));
    /** The tall world type: the same terrain with its belts scaled up into an 864-block world. */
    public static final ResourceKey<NoiseGeneratorSettings> SETTINGS_TALL =
            ResourceKey.create(Registries.NOISE_SETTINGS, new ResourceLocation(GeysersMod.MODID, "geology_tall"));

    private static final Map<String, Boolean> OWN = new ConcurrentHashMap<>();

    public static boolean isOwn(ServerLevel level) {
        return OWN.computeIfAbsent(level.dimension().location().toString(), k -> {
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise)) return false;
            return noise.generatorSettings().is(SETTINGS) || noise.generatorSettings().is(SETTINGS_TALL);
        });
    }

    public static void clear() {
        OWN.clear();
    }
}
