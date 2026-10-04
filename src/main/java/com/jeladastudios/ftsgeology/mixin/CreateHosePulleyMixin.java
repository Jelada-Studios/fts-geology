package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.compat.CreateRivers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What a Create hose pulley draws out of a body of water comes up as plain water when the body is a river; where
 * another mod runs that river's water, the pulley draws on that mod (see {@link CreateRivers#pulleyFromHydraulics}).
 * Only there when Create is; without it the target does not exist and this is passed over.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour", remap = false)
public abstract class CreateHosePulleyMixin {

    @ModifyArg(method = {"pullNext", "getDrainableFluid"},
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fluids/FluidStack;<init>(Lnet/minecraft/world/level/material/Fluid;I)V", remap = false),
            index = 0, require = 0, remap = false)
    private Fluid fts_geology$riverIsWater(Fluid fluid) {
        return CreateRivers.asWater(fluid);
    }

    @Inject(method = "pullNext", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void fts_geology$pullFromHydraulics(BlockPos root, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        int got = CreateRivers.pulleyFromHydraulics(this, root, simulate);
        if (got >= 0) cir.setReturnValue(got >= 1000);
    }

    @Inject(method = "getDrainableFluid", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void fts_geology$drainableFromHydraulics(BlockPos root, CallbackInfoReturnable<FluidStack> cir) {
        int got = CreateRivers.pulleyFromHydraulics(this, root, true);
        if (got >= 0) cir.setReturnValue(got >= 1000 ? new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000) : FluidStack.EMPTY);
    }
}
