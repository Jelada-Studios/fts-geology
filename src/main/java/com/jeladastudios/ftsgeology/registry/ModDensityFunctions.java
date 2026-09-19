package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.terrain.PlateDensity;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Density function types, referenced from the mod's noise settings by id
 * ({@code data/fts_geology/worldgen/noise_settings/geology.json} and the density functions beside it).
 */
public final class ModDensityFunctions {

    private ModDensityFunctions() {}

    public static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_FUNCTIONS =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, GeysersMod.MODID);

    /** The rivers traced down the raw ground. See {@link com.jeladastudios.ftsgeology.worldgen.terrain.RiverDensity}. */
    public static final RegistryObject<Codec<? extends DensityFunction>> RIVERS =
            DENSITY_FUNCTIONS.register("rivers", () -> com.jeladastudios.ftsgeology.worldgen.terrain.RiverDensity.CODEC.codec());

    /** The plate model's continents, erosion, ridge factor and relief. See {@link PlateDensity}. */
    public static final RegistryObject<Codec<? extends DensityFunction>> PLATE =
            DENSITY_FUNCTIONS.register("plate", () -> PlateDensity.CODEC.codec());
}
