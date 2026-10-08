package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyChunkGenerator;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegistryObject;

/** Chunk generator types, referenced from the mod's world presets by id. */
public final class ModChunkGenerators {

    private ModChunkGenerators() {}

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, GeysersMod.MODID);

    /** The noise generator that keeps villages out of the rivers. See {@link GeologyChunkGenerator}. */
    public static final RegistryObject<Codec<? extends ChunkGenerator>> NOISE =
            CHUNK_GENERATORS.register("noise", () -> GeologyChunkGenerator.CODEC);
}
