package com.jeladastudios.ftsgeology.registry;
import net.neoforged.neoforge.registries.DeferredHolder;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyBiomeSource;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Biome source types. The mod's world preset names {@code fts_geology:geology} and gives it the source it wraps,
 * so the geology decides only what the climate cannot. See {@link GeologyBiomeSource}.
 */
public final class ModBiomeSources {

    private ModBiomeSources() {}

    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, GeysersMod.MODID);

    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<? extends BiomeSource>> GEOLOGY =
            BIOME_SOURCES.register("geology", () -> GeologyBiomeSource.CODEC);
}
