package com.jeladastudios.ftsgeology.gas.block;

import com.jeladastudios.ftsgeology.gas.block.entity.GasMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Common behaviour: server ticking, status on right-click, cleanup on removal. */
public abstract class MachineBlock extends BaseEntityBlock {
    private final Supplier<? extends BlockEntityType<? extends GasMachineBlockEntity>> type;

    protected MachineBlock(Properties props, Supplier<? extends BlockEntityType<? extends GasMachineBlockEntity>> type) {
        super(props);
        this.type = type;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return type.get().create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> t) {
        if (level.isClientSide || t != type.get()) return null;
        return (l, p, s, be) -> ((GasMachineBlockEntity) be).serverTick();
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof GasMachineBlockEntity be) return be.onUse(player, hand, hit);
        return InteractionResult.PASS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof GasMachineBlockEntity be) {
            be.onRemoved();
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
