package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.worldgen.terrain.KeepOverworld;
import net.minecraft.core.Registry;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Where the chosen world type meets the data packs' dimensions: see {@link KeepOverworld}. */
@Mixin(WorldDimensions.class)
public abstract class WorldDimensionsMixin {

    @ModifyVariable(method = "bake", at = @At("HEAD"), argsOnly = true)
    private Registry<LevelStem> fts_geology$keepOverworld(Registry<LevelStem> packs) {
        return KeepOverworld.keep(((WorldDimensions) (Object) this).dimensions(), packs);
    }
}
