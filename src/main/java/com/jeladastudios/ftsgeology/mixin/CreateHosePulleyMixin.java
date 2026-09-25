package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.compat.CreateRivers;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * What a Create hose pulley draws out of a body of water comes up as plain water when the body is a river. See
 * {@link CreateRivers}. Only there when Create is; without it the target does not exist and this is passed over.
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
}
