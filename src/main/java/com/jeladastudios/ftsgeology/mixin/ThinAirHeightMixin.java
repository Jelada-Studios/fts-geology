package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.compat.ThinAirHeights;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The height Thin Air reads its air quality at, in the tall world's overworld measured as the vanilla world would. See
 * {@link ThinAirHeights}. Only there when Thin Air is; without it the target does not exist and this is passed over.
 */
@Pseudo
@Mixin(targets = "fuzs.thinair.config.ServerConfig", remap = false)
public abstract class ThinAirHeightMixin {

    @ModifyVariable(method = "getAirQualityAtLevelByDimension", at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0, remap = false)
    private static int fts_geology$vanillaHeight(int height, ResourceKey<Level> dimension) {
        return ThinAirHeights.height(dimension, height);
    }
}
