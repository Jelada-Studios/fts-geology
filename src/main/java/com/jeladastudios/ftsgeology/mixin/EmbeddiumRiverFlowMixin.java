package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The same as {@link RiverFlowRenderMixin} in Embeddium's own fluid renderer, which reads the flow in its
 * {@code render}. Only there when Embeddium is; without it the target does not exist and this is passed over.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer", remap = false)
public abstract class EmbeddiumRiverFlowMixin {

    @ModifyVariable(method = "render", at = @At("LOAD"), ordinal = 0, require = 0, remap = false)
    private Vec3 fts_geology$riverFlow(Vec3 flow, WorldSlice world, FluidState fluid, BlockPos pos,
                                       BlockPos offset, ChunkBuildBuffers buffers) {
        return fluid.getType() instanceof RiverWaterFluid ? fluid.getFlow(world, pos) : flow;
    }

    /** A river block's water as high as a registered hook says it stands, in Embeddium's corner heights. */
    @Inject(method = "fluidHeight", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void fts_geology$riverSurface(net.minecraft.world.level.BlockAndTintGetter world, net.minecraft.world.level.material.Fluid fluid,
                                          BlockPos pos, net.minecraft.core.Direction direction,
                                          org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        FluidState state = world.getFluidState(pos);
        if (!(state.getType() instanceof RiverWaterFluid) || !fluid.isSame(state.getType())) return;
        float h = com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.surface(world, pos, state);
        if (!Float.isNaN(h)) cir.setReturnValue(h);
    }
}
