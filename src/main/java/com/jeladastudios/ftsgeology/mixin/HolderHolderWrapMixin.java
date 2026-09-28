package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.worldgen.terrain.WrapMemo;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A branch the noise router names is wrapped once while a noise chunk sets up, and handed back each further time the
 * router uses it; see {@link WrapMemo}.
 */
@Mixin(DensityFunctions.HolderHolder.class)
public abstract class HolderHolderWrapMixin {

    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true, require = 0)
    private void fts_geology$wrapped(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        DensityFunction done = WrapMemo.known(visitor, ((DensityFunctions.HolderHolder) (Object) this).function().value());
        if (done != null) cir.setReturnValue(done);
    }

    @Inject(method = "mapAll", at = @At("RETURN"), require = 0)
    private void fts_geology$remember(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        WrapMemo.remember(visitor, ((DensityFunctions.HolderHolder) (Object) this).function().value(), cir.getReturnValue());
    }
}
