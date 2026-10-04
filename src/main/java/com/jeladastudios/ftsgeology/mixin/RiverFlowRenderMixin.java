package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.material.Fluid;

/**
 * A river's water drawn running, down the way its river goes, with Flowing Fluids. That mod, with its
 * {@code hideFlowingTexture} on (as it comes), sets the flow vanilla's fluid renderer reads to nothing for every fluid,
 * so that its levelled water lies still; the rivers, all of them still sources, were drawn as standing water. Each time
 * the renderer reads the flow, which is after that mod has set it, a river's own is given back. Without that mod this
 * gives the flow it already had.
 */
@Mixin(LiquidBlockRenderer.class)
public abstract class RiverFlowRenderMixin {

    @ModifyVariable(method = "tesselate", at = @At("LOAD"), ordinal = 0, require = 0)
    private Vec3 fts_geology$riverFlow(Vec3 flow, BlockAndTintGetter level, BlockPos pos, VertexConsumer consumer,
                                       BlockState block, FluidState fluid) {
        return fluid.getType() instanceof RiverWaterFluid ? fluid.getFlow(level, pos) : flow;
    }

    /** A river block's water as high as a registered hook says it stands (see the API's {@code registerSurface}). */
    @Inject(method = "getHeight(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void fts_geology$riverSurface(BlockAndTintGetter level, Fluid fluid, BlockPos pos, BlockState block, FluidState state,
                                          CallbackInfoReturnable<Float> cir) {
        if (!(state.getType() instanceof RiverWaterFluid) || !fluid.isSame(state.getType())) return;
        float h = com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.surface(level, pos, state);
        if (!Float.isNaN(h)) cir.setReturnValue(h);
    }
}
