package com.jeladastudios.ftsgeology.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
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

    /**
     * Which way the river runs here: 0 still (a lake, a mouth, a pool), 1 to 8 the eight compass ways, a quarter turn
     * of a right angle apart. The water still never moves; this only tells the renderer which way to run the
     * surface's texture and tells whatever swims or floats in it which way it is carried.
     */
    public static final IntegerProperty FLOW = IntegerProperty.create("flow", 0, 8);

    /** How hard the current carries a player: a gentle drift, not the push of a waterfall. */
    private static final double CURRENT = 0.5;

    protected RiverWaterFluid(Properties properties) {
        super(properties);
    }

    @Override
    protected void createFluidStateDefinition(@Nonnull StateDefinition.Builder<Fluid, FluidState> builder) {
        super.createFluidStateDefinition(builder);
        builder.add(FLOW);
    }

    /** The way numbered {@code flow} as a unit vector in x and z, or zero for still water. */
    public static Vec3 way(int flow) {
        if (flow <= 0) return Vec3.ZERO;
        double a = (flow - 1) * Math.PI / 4.0;
        return new Vec3(Math.cos(a), 0.0, Math.sin(a));
    }

    /** The way nearest the direction {@code (dx, dz)}, 1 to 8, or 0 where there is none. */
    public static int wayOf(double dx, double dz) {
        if (dx * dx + dz * dz < 1e-6) return 0;
        double a = Math.atan2(dz, dx);
        int k = (int) Math.round(a / (Math.PI / 4.0));
        return Math.floorMod(k, 8) + 1;
    }

    @Override
    @Nonnull
    protected BlockState createLegacyBlock(@Nonnull FluidState state) {
        return super.createLegacyBlock(state).setValue(FLOW, state.getValue(FLOW));
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

    /**
     * The way the river runs, if it runs: the surface is drawn with the flowing texture moving that way and a swimmer
     * or a boat is carried gently along. Still water has none.
     */
    @Override
    @Nonnull
    public Vec3 getFlow(@Nonnull BlockGetter level, @Nonnull BlockPos pos, @Nonnull FluidState state) {
        return way(state.getValue(FLOW)).scale(CURRENT);
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

    /** The full block. */
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

    /** The falling form, laid down the face of a step in a river so the step reads as a little fall of water. */
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
