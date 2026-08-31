package com.pandabear.geysers.block;

import com.pandabear.geysers.blockentity.VolcanoIgniterBlockEntity;
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

/** Player-placeable seed that carves a volcano beneath it after a short charge. */
public class VolcanoIgniterBlock extends BaseEntityBlock {

    public VolcanoIgniterBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VolcanoIgniterBlockEntity(pos, state);
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
        return createTickerHelper(type, ModBlockEntities.VOLCANO_IGNITER.get(),
                VolcanoIgniterBlockEntity::serverTick);
    }
}
