package com.jeladastudios.ftsgeology.gas.block;

import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Fixed gas detector head: measures the space in front of it and emits a redstone signal. */
public class GasSensorBlock extends MachineBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    public GasSensorBlock(Properties props) {
        super(props, GasBlockEntities.GAS_SENSOR);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, POWER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Placed against a wall, the sensor looks away from it.
        return defaultBlockState().setValue(FACING, ctx.getClickedFace());
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        return state.getValue(POWER);
    }
}
