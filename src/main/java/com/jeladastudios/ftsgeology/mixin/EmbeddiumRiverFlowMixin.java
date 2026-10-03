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
}
