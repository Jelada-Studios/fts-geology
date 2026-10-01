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

    /** One ore of a kind: other mods' features placing an ore the kept mod already places there. See {@link com.jeladastudios.ftsgeology.compat.OreUnification}. */
    public static final RegistryObject<Codec<com.jeladastudios.ftsgeology.compat.OreUnification.Remove>> UNIFY_ORES =
            SERIALIZERS.register("unify_ores", () -> com.jeladastudios.ftsgeology.compat.OreUnification.Remove.CODEC);

    /** Jurassic Reborn's fossils, amber and gypsum laid by the geology instead. See {@link com.jeladastudios.ftsgeology.worldgen.FossilBeds}. */
    public static final RegistryObject<Codec<com.jeladastudios.ftsgeology.worldgen.FossilBeds.Remove>> FOSSIL_BEDS =
            SERIALIZERS.register("fossil_beds", () -> com.jeladastudios.ftsgeology.worldgen.FossilBeds.Remove.CODEC);
}
