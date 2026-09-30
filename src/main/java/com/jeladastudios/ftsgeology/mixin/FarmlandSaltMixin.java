package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.hydrology.SeaWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FarmBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sea water waters no field: where the game finds water near farmland and all of it is the sea's, it is taken as no
 * water at all (see {@link SeaWater}). At the method's end, so another mod's own test at its start (a watering fluid
 * of its own) stands, and Flowing Fluids, which drains the water a field drinks from at the same end, leaves the sea
 * alone when the field did not drink.
 */
@Mixin(FarmBlock.class)
public abstract class FarmlandSaltMixin {

    @Inject(method = "isNearWater", at = @At("RETURN"), cancellable = true, require = 0)
    private static void fts_geology$saltWater(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && SeaWater.on() && !SeaWater.freshWaterNear(level, pos)) cir.setReturnValue(false);
    }
}
