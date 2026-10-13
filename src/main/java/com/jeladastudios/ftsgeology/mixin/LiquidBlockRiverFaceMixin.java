package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * No face between a river's water and the sea's, on NeoForge (see {@link RiverFlowRenderMixin}). Its fluid renderer, and
 * Sodium's, ask the block next to a fluid whether it hides that fluid's face; plain water said no to a river's, so the
 * edge of a river's water reaching out over the top of the sea stood in it as a pane of glass. Water now hides it.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockRiverFaceMixin {

    public boolean shouldHideAdjacentFluidFace(BlockState state, Direction selfFace, FluidState adjacentFluid) {
        return state.getFluidState().getType().isSame(adjacentFluid.getType())
                || RiverWaterFluid.seamless(adjacentFluid.getType(), state.getFluidState().getType());
    }
}
