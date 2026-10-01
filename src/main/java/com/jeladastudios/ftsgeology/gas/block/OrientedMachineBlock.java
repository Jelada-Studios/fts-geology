package com.jeladastudios.ftsgeology.gas.block;

import com.jeladastudios.ftsgeology.gas.block.entity.GasMachineBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

/**
 * A machine with a front face (pointing at the player when placed) and an "active" LIT state.
 * {@code allDirections} machines can also face up/down.
 */
public class OrientedMachineBlock extends MachineBlock {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    private final boolean allDirections;

    public OrientedMachineBlock(Properties props, Supplier<? extends BlockEntityType<? extends GasMachineBlockEntity>> type, boolean allDirections) {
        super(props, type);
        this.allDirections = allDirections;
        registerDefaultState(stateDefinition.any().setValue(facingProp(), Direction.NORTH).setValue(LIT, false));
    }

    public DirectionProperty facingProp() {
        return directionalProperty();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        // Runs inside the super constructor, before fields are set, so it must not read them.
        b.add(LIT);
        b.add(directionalProperty());
    }

    /** Overridden by the all-direction variant. */
    protected DirectionProperty directionalProperty() {
        return BlockStateProperties.HORIZONTAL_FACING;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction d = allDirections ? ctx.getNearestLookingDirection().getOpposite() : ctx.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(facingProp(), d);
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(facingProp(), rot.rotate(state.getValue(facingProp())));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(facingProp())));
    }

    /** Variant that can face all six directions. */
    public static class AllDirections extends OrientedMachineBlock {
        public AllDirections(Properties props, Supplier<? extends BlockEntityType<? extends GasMachineBlockEntity>> type) {
            super(props, type, true);
        }

        @Override
        protected DirectionProperty directionalProperty() {
            return BlockStateProperties.FACING;
        }
    }
}
