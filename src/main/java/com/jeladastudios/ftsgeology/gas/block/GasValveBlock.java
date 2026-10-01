package com.jeladastudios.ftsgeology.gas.block;

import com.jeladastudios.ftsgeology.gas.block.entity.GasValveBlockEntity;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import com.jeladastudios.ftsgeology.gas.world.GasIgniter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Gas valve / burner nozzle. The back takes gas (from a pipe, tank or the room behind it), the
 * front lets it out. Opened with right-click or redstone; lit with flint and steel it becomes a
 * burner that keeps a jet flame going as long as fuel flows.
 */
public class GasValveBlock extends MachineBlock implements GasIgniter {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public GasValveBlock(Properties props) {
        super(props, GasBlockEntities.GAS_VALVE);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPEN, false).setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, OPEN, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // The nozzle points at the player; the intake goes into the wall/room behind.
        return defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
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
    public boolean ignitesGas(BlockState state, @Nullable Direction side) {
        return state.getValue(LIT) && (side == null || side == state.getValue(FACING));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        super.neighborChanged(state, level, pos, block, from, moving);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof GasValveBlockEntity be) {
            be.onRedstone(level.hasNeighborSignal(pos));
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
        if (!state.getValue(LIT)) return;
        Direction f = state.getValue(FACING);
        double x = pos.getX() + 0.5 + f.getStepX() * 0.55, y = pos.getY() + 0.5 + f.getStepY() * 0.55, z = pos.getZ() + 0.5 + f.getStepZ() * 0.55;
        level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, f.getStepX() * 0.05, f.getStepY() * 0.05, f.getStepZ() * 0.05);
    }
}
