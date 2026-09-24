package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.lithology.LithologyRule;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Surface rule types, referenced from the mod's noise settings by id. */
public final class ModSurfaceRules {

    private ModSurfaceRules() {}

    public static final DeferredRegister<Codec<? extends SurfaceRules.RuleSource>> MATERIAL_RULES =
            DeferredRegister.create(Registries.MATERIAL_RULE, GeysersMod.MODID);

    /** The rock under the ground, from the plates. See {@link LithologyRule}. */
    public static final RegistryObject<Codec<? extends SurfaceRules.RuleSource>> LITHOLOGY =
            MATERIAL_RULES.register("lithology", () -> LithologyRule.CODEC.codec());

    /** The overworld's surface where a mod changed it. See {@link com.jeladastudios.ftsgeology.worldgen.terrain.OverworldSurfaceRule}. */
    public static final RegistryObject<Codec<? extends SurfaceRules.RuleSource>> OVERWORLD_SURFACE =
            MATERIAL_RULES.register("overworld_surface",
                    () -> com.jeladastudios.ftsgeology.worldgen.terrain.OverworldSurfaceRule.CODEC.codec());
}
