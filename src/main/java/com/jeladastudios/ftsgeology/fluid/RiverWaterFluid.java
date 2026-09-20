package com.jeladastudios.ftsgeology.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.ForgeFlowingFluid;

import javax.annotation.Nonnull;

/**
 * River water, which stands still.
 *
 * <p>Every block of it is a source and none of them spreads: the tick does nothing and nothing may be spread into.
 * That is the whole point. A still fluid can hold a different level in every column, so a river's surface can come
 * down a valley a block at a time instead of being cut into flat pools with a rock bar between them, and a mod that
 * moves water about finds nothing here to move.</p>
 */
public abstract class RiverWaterFluid extends ForgeFlowingFluid {

    protected RiverWaterFluid(Properties properties) {
        super(properties);
    }

    /** Nothing to do, ever: the generator decided where this water is and it stays there. */
    @Override
    public void tick(@Nonnull Level level, @Nonnull BlockPos pos, @Nonnull FluidState state) {}

    @Override
    protected void spread(@Nonnull Level level, @Nonnull BlockPos pos, @Nonnull FluidState state) {}

    @Override
    protected boolean canSpreadTo(@Nonnull BlockGetter level, @Nonnull BlockPos from, @Nonnull BlockState fromState,
                                  @Nonnull Direction direction, @Nonnull BlockPos to, @Nonnull BlockState toState,
                                  @Nonnull FluidState toFluid, @Nonnull Fluid fluid) {
        return false;
    }

    @Override
    protected boolean canConvertToSource(@Nonnull Level level) {
        return false;
    }

    /** No flow, so nothing is pushed along and the block is drawn with the still texture. */
    @Override
    @Nonnull
    public Vec3 getFlow(@Nonnull BlockGetter level, @Nonnull BlockPos pos, @Nonnull FluidState state) {
        return Vec3.ZERO;
    }

    /**
     * Water counts as the same fluid. A river runs into the sea, and the sea would otherwise spend its evenings
     * trying to flow into a river that is already full.
     */
    @Override
    public boolean isSame(@Nonnull Fluid fluid) {
        return fluid == this || fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER
                || fluid instanceof RiverWaterFluid;
    }

    /** The full block. The flowing form exists only because Forge asks for one; nothing ever places it. */
    public static class Source extends RiverWaterFluid {
        public Source(Properties properties) {
            super(properties);
        }

        @Override
        public int getAmount(@Nonnull FluidState state) {
            return 8;
        }

        @Override
        public boolean isSource(@Nonnull FluidState state) {
            return true;
        }
    }

    public static class Flowing extends RiverWaterFluid {
        public Flowing(Properties properties) {
            super(properties);
        }

        @Override
        protected void createFluidStateDefinition(@Nonnull StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getAmount(@Nonnull FluidState state) {
            return state.getValue(LEVEL);
        }

        @Override
        public boolean isSource(@Nonnull FluidState state) {
            return false;
        }
    }
}
