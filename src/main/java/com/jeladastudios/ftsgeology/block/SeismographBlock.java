package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A recording station: a drum of paper and a pen that will not move when the ground does.
 *
 * <p>Right-click to read the paper. What comes back is what the station measured - the gap between
 * the two wave arrivals and how far the pen swung - and the distance and magnitude those imply. It
 * cannot give you a direction, which is the instrument's whole character: see
 * {@link SeismographBlockEntity}.</p>
 *
 * <p>It also holds a redstone signal for a few seconds after an arrival, scaled by how hard the
 * ground shook, so a station can ring a bell before the shaking reaches you. That is not a
 * flourish: an early-warning network is the main thing seismographs are used for, and it works
 * because the warning travels at the speed of light and the shaking does not.</p>
 */
public class SeismographBlock extends BaseEntityBlock {

    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0);

    public SeismographBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext c) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SeismographBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.SEISMOGRAPH.get(),
                SeismographBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SeismographBlockEntity be)) {
            return InteractionResult.PASS;
        }
        List<Component> lines = be.report(level.getGameTime());
        for (Component c : lines) player.sendSystemMessage(c);
        return InteractionResult.CONSUME;
    }

    // --- Redstone -----------------------------------------------------------

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        return level.getBlockEntity(pos) instanceof SeismographBlockEntity be ? be.signal() : 0;
    }

    // getDirectSignal is deliberately left at zero. Weak power only: the station energises dust and
    // components next to it, but does not power a block through a solid one. A detector that
    // strongly powered its neighbours would light up wiring on the far side of the wall it is
    // bolted to, which is not what anyone building a warning line wants.
}
