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

    /** Sea cliffs on a basalt coast. See {@link com.jeladastudios.ftsgeology.worldgen.terrain.CoastCliffs}. */
    public static final RegistryObject<Codec<? extends DensityFunction>> COAST_CLIFFS =
            DENSITY_FUNCTIONS.register("coast_cliffs", () -> com.jeladastudios.ftsgeology.worldgen.terrain.CoastCliffs.CODEC.codec());

    /** Caves kept shut above the snow line. See {@link com.jeladastudios.ftsgeology.worldgen.terrain.SummitShield}. */
    public static final RegistryObject<Codec<? extends DensityFunction>> SUMMIT_SHIELD =
            DENSITY_FUNCTIONS.register("summit_shield", () -> com.jeladastudios.ftsgeology.worldgen.terrain.SummitShield.CODEC.codec());

    /** The highest summits rounded off under the top of the world. See {@link com.jeladastudios.ftsgeology.worldgen.terrain.SummitCeiling}. */
    public static final RegistryObject<Codec<? extends DensityFunction>> SUMMIT_CEILING =
            DENSITY_FUNCTIONS.register("summit_ceiling", () -> com.jeladastudios.ftsgeology.worldgen.terrain.SummitCeiling.CODEC.codec());

    /** The plate model's continents, erosion, ridge factor and relief. See {@link PlateDensity}. */
    public static final RegistryObject<Codec<? extends DensityFunction>> PLATE =
            DENSITY_FUNCTIONS.register("plate", () -> PlateDensity.CODEC.codec());
}
