package com.pandabear.geysers.block;

import com.pandabear.geysers.blockentity.GeyserIgniterBlockEntity;
import com.pandabear.geysers.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Player-placeable block that becomes a geyser. Plant it, wait a few seconds (it smokes while it
 * charges), and it forms a full geyser system in the column below, then removes itself.
 */
public class GeyserIgniterBlock extends BaseEntityBlock {

    public GeyserIgniterBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GeyserIgniterBlockEntity(pos, state);
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
        return createTickerHelper(type, ModBlockEntities.GEYSER_IGNITER.get(),
                GeyserIgniterBlockEntity::serverTick);
    }
}
