package com.jeladastudios.ftsgeology.mixin;

import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A furnace's fire and its kind of cooking, for the gas burnt under it (see {@code gas.GasFurnaces}). */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceAccessor {

    @Accessor("litTime")
    int fts_geology$litTime();

    @Accessor("litTime")
    void fts_geology$setLitTime(int ticks);

    @Accessor("litDuration")
    void fts_geology$setLitDuration(int ticks);

    @Accessor("quickCheck")
    net.minecraft.world.item.crafting.RecipeManager.CachedCheck<net.minecraft.world.Container, ? extends AbstractCookingRecipe> fts_geology$quickCheck();
}
