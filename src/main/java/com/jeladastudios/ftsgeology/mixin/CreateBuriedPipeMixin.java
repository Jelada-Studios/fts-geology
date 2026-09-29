package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.compat.CreateRivers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Create pipe whose end is buried in ground the groundwater fills has an open end there: Create takes any solid face
 * for a closed one. What it draws is the aquifer's ({@link CreateRivers#drawFromGround}); pushed the other way, Create
 * already puts nothing into a solid block. Only there when Create is.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.FluidPropagator", remap = false)
public abstract class CreateBuriedPipeMixin {

    @Inject(method = "isOpenEnd", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void fts_geology$buried(BlockGetter reader, BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (CreateRivers.buriedInWater(reader, pos.relative(side))) cir.setReturnValue(true);
    }
}
