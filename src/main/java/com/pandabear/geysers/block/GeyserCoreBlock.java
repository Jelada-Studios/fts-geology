package com.pandabear.geysers.block;

import com.pandabear.geysers.blockentity.GeyserCoreBlockEntity;
import com.pandabear.geysers.registry.ModBlockEntities;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

public class GeyserCoreBlock extends BaseEntityBlock {

    public GeyserCoreBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GeyserCoreBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Looks like ordinary deepslate; the model json handles appearance.
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null; // server-authoritative simulation only
        return createTickerHelper(type, ModBlockEntities.GEYSER_CORE.get(),
                GeyserCoreBlockEntity::serverTick);
    }
}
