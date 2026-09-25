package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.compat.CreateRivers;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where a Create pipe's open end draws from the world: from a river it draws plain water and leaves the river standing.
 * See {@link CreateRivers}. Only there when Create is; without it the target does not exist and this is passed over.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.OpenEndedPipe", remap = false)
public abstract class CreateOpenPipeMixin {

    @Inject(method = "removeFluidFromSpace", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void fts_geology$riverIsWater(boolean simulate, CallbackInfoReturnable<FluidStack> cir) {
        FluidStack water = CreateRivers.drawFromRiver(this);
        if (water != null) cir.setReturnValue(water);
    }
}
