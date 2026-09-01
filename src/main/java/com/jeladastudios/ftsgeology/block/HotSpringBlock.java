package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.blockentity.HotSpringBlockEntity;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
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
 * The bed of a hot spring — sits under a pool of water and radiates warmth (see
 * {@link HotSpringBlockEntity}). Generated at the bottom of natural hot-spring pools, and also
 * placeable so players can build their own spa (put water on top for the effect).
 */
public class HotSpringBlock extends BaseEntityBlock {

    public HotSpringBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HotSpringBlockEntity(pos, state);
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
        return createTickerHelper(type, ModBlockEntities.HOT_SPRING.get(),
                HotSpringBlockEntity::serverTick);
    }
}
