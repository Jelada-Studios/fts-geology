package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.OptionalSpawns;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Biome modifier types, referenced from the mod's data by id. */
public final class ModBiomeModifiers {

    private ModBiomeModifiers() {}

    public static final DeferredRegister<Codec<? extends BiomeModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, GeysersMod.MODID);

    /** Another mod's animals in the mod's biomes, when that mod is there. See {@link OptionalSpawns}. */
    public static final RegistryObject<Codec<OptionalSpawns>> OPTIONAL_SPAWNS =
            SERIALIZERS.register("optional_spawns", () -> OptionalSpawns.CODEC);
}
