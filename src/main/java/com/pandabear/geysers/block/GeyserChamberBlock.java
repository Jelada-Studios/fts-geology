package com.pandabear.geysers.block;

import com.pandabear.geysers.blockentity.GeyserChamberBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Passive marker block lining the accumulation chamber. Holds no tick logic of its own;
 * it exists so the core can quickly identify chamber bounds and so the chamber volume
 * survives chunk save/load. The core reads/writes its {@link GeyserChamberBlockEntity}s.
 */
public class GeyserChamberBlock extends BaseEntityBlock {

    public GeyserChamberBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GeyserChamberBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
