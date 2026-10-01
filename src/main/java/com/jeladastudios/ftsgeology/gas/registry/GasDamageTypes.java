package com.jeladastudios.ftsgeology.gas.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;

public final class GasDamageTypes {
    public static final ResourceKey<DamageType> ASPHYXIATION = key("asphyxiation");
    public static final ResourceKey<DamageType> GAS_POISONING = key("gas_poisoning");
    public static final ResourceKey<DamageType> GAS_EXPLOSION = key("gas_explosion");
    public static final ResourceKey<DamageType> BURNER_FLAME = key("burner_flame");

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(GeysersMod.MODID, name));
    }

    public static DamageSource source(Level level, ResourceKey<DamageType> key) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key));
    }

    private GasDamageTypes() {
    }
}
