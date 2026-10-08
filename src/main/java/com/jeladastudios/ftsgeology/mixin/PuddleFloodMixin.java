package com.jeladastudios.ftsgeology.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puddles &amp; Floods (MIT) floods every flat, open block at sea level while it rains, drawn on the client. In the mod's
 * worlds the banks of a river near the sea stand at sea level, and whole banks were drawn under a sheet of water that
 * was not there. With the ground's water known it floods only where water stands on the ground: see
 * {@link com.jeladastudios.ftsgeology.client.ClientSoilWet}. Only where that mod is installed.
 */
@Pseudo
@Mixin(targets = "pigcart.puddleflood.BlockPlacementUtil", remap = false)
public abstract class PuddleFloodMixin {

    @Inject(method = "canFlood", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void fts_geology$whereFloods(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide() && !com.jeladastudios.ftsgeology.client.ClientSoilWet.allowsFlood(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
