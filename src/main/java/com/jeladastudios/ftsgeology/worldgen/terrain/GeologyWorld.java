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
            boolean own = level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise
                    && (noise.generatorSettings().is(SETTINGS) || noise.generatorSettings().is(SETTINGS_TALL));
            GeysersMod.LOGGER.info("{}: own {}; {}", k, own, describe(level));
            return own;
        });
    }

    /**
     * What the level's generator is made of, for telling when another mod has swapped a piece of it: the generator
     * and biome source classes, the noise settings' key (or "direct" when the holder carries no key), and whether the
     * settings are the very object the registry holds under the mod's keys.
     */
    public static String describe(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        StringBuilder s = new StringBuilder("generator ").append(generator.getClass().getName())
                .append(", biomes ").append(generator.getBiomeSource().getClass().getName());
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            var holder = noise.generatorSettings();
            s.append(", settings ").append(holder.unwrapKey().map(k -> k.location().toString()).orElse("direct"));
            var registry = level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
            for (ResourceKey<NoiseGeneratorSettings> key : new ResourceKey[] {SETTINGS, SETTINGS_TALL}) {
                NoiseGeneratorSettings ours = registry.get(key);
                if (ours != null && ours == holder.value()) s.append(", same object as ").append(key.location());
            }
            s.append(", router ").append(level.getChunkSource().randomState().router().finalDensity().getClass().getSimpleName());
        }
        // What the default preset and ours would build, as the registry holds them now.
        level.registryAccess().registry(Registries.WORLD_PRESET).ifPresent(presets -> {
            for (String id : new String[] {"minecraft:normal", GeysersMod.MODID + ":geology_tall"}) {
                var preset = presets.get(new ResourceLocation(id));
                if (preset == null) {
                    s.append("; preset ").append(id).append(" missing");
                    continue;
                }
                preset.overworld().ifPresent(stem -> {
                    s.append("; preset ").append(id).append(" -> ").append(stem.generator().getClass().getSimpleName());
                    if (stem.generator() instanceof NoiseBasedChunkGenerator n)
                        s.append(" ").append(n.generatorSettings().unwrapKey().map(k -> k.location().toString()).orElse("direct"));
                });
            }
        });
        return s.toString();
    }

    public static void clear() {
        OWN.clear();
    }
}
