package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyBiomeSource;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Biome source types. The mod's world preset names {@code fts_geology:geology} and gives it the source it wraps,
 * so the geology decides only what the climate cannot. See {@link GeologyBiomeSource}.
 */
public final class ModBiomeSources {

    private ModBiomeSources() {}

    public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, GeysersMod.MODID);

    public static final RegistryObject<Codec<? extends BiomeSource>> GEOLOGY =
            BIOME_SOURCES.register("geology", () -> GeologyBiomeSource.CODEC);
}
