package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.hydrology.SoilWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Grass does not spread onto ground in a drought ({@link SoilWater#parched}): without it the grass the drought killed
 * grew straight back from the next patch, a few minutes after it died. Only with {@code soilWaterChangesGround}.
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class GrassSpreadMixin {

    @Inject(method = "canPropagate", at = @At("HEAD"), cancellable = true, require = 0)
    private static void fts_geology$parched(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (state.is(Blocks.GRASS_BLOCK) && SoilWater.parched(level, pos)) cir.setReturnValue(false);
    }
}
