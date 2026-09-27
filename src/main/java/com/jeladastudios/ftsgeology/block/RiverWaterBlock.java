package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * The block of river water. A plain liquid block, carrying the way the river runs as well as its level: the fluid a
 * liquid block hands out is looked up from the level alone, so the way has to live on the block and be put back on
 * the fluid here.
 */
public class RiverWaterBlock extends LiquidBlock {

    public RiverWaterBlock(Supplier<? extends FlowingFluid> fluid, Properties properties) {
        super(fluid, properties);
    }

    @Override
    protected void createBlockStateDefinition(@Nonnull StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(RiverWaterFluid.FLOW);
    }

    @Override
    @Nonnull
    public FluidState getFluidState(@Nonnull BlockState state) {
        return super.getFluidState(state).setValue(RiverWaterFluid.FLOW, state.getValue(RiverWaterFluid.FLOW));
    }
}
