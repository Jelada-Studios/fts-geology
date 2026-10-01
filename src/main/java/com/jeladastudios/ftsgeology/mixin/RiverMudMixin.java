package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.client.ClientRiverMud;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A river's water drawn as muddy as the rivers are after heavy rain (see {@link ClientRiverMud}): the water colour the
 * game asks of a place, where the water there is a river's. Lakes, the sea and other water keep the biome's colour.
 */
@Mixin(BiomeColors.class)
public abstract class RiverMudMixin {

    @Inject(method = "getAverageWaterColor", at = @At("RETURN"), cancellable = true)
    private static void fts_geology$muddy(BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (ClientRiverMud.muddiness() <= 0f || level == null || pos == null) return;
        if (!(level.getFluidState(pos).getType() instanceof RiverWaterFluid)) return;
        cir.setReturnValue(ClientRiverMud.tint(cir.getReturnValueI()));
    }
}
