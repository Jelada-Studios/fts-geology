package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.GeologyFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * World generation features.
 *
 * <p>Registering the feature is only half of it: it reaches the world through
 * {@code data/fts_geology/forge/biome_modifier/geology.json}, which adds it to every overworld biome,
 * and the configured and placed feature files beside it.</p>
 */
public final class ModFeatures {

    private ModFeatures() {}

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, GeysersMod.MODID);

    /** The deep geology of a chunk, written while it is generated. See {@link GeologyFeature}. */
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> GEOLOGY =
            FEATURES.register("geology", () -> new GeologyFeature(NoneFeatureConfiguration.CODEC));

    /**
     * Large volcanoes, raised a chunk at a time as terrain generates. See
     * {@link com.jeladastudios.ftsgeology.worldgen.VolcanoFieldFeature}.
     */
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> VOLCANO_FIELD =
            FEATURES.register("volcano_field", () -> new com.jeladastudios.ftsgeology.worldgen
                    .VolcanoFieldFeature(NoneFeatureConfiguration.CODEC));

    /**
     * Geothermal ground and soil colour, painted while a chunk generates. See
     * {@link com.jeladastudios.ftsgeology.worldgen.GeologySurfaceFeature}.
     */
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> GEOLOGY_SURFACE =
            FEATURES.register("geology_surface", () -> new com.jeladastudios.ftsgeology.worldgen
                    .GeologySurfaceFeature(NoneFeatureConfiguration.CODEC));
}
