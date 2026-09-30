package com.jeladastudios.ftsgeology.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puddles &amp; Floods (MIT) lays a rain puddle wherever its noise says one goes on a flat, open surface. With the
 * ground's water known, it lays none where the ground would drink the rain: see
 * {@link com.jeladastudios.ftsgeology.client.ClientSoilWet}. Only where that mod is installed.
 */
@Pseudo
@Mixin(targets = "pigcart.puddleflood.ClientBlockPlacementUtil", remap = false)
public abstract class PuddleRainMixin {

    @Inject(method = "hasRainPuddle", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void fts_geology$wherePuddles(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!com.jeladastudios.ftsgeology.client.ClientSoilWet.allowsPuddle(level, pos)) cir.setReturnValue(false);
    }
}
