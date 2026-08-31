package com.pandabear.geysers.blockentity;

import com.pandabear.geysers.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Lightweight tag entity marking a block that belongs to a geyser chamber's interior shell.
 * Stores a back-reference to its controlling core so scans can be short-circuited.
 */
public class GeyserChamberBlockEntity extends BlockEntity {

    private BlockPos corePos;

    public GeyserChamberBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GEYSER_CHAMBER.get(), pos, state);
    }

    public void setCorePos(BlockPos corePos) {
        this.corePos = corePos;
        setChanged();
    }

    public BlockPos getCorePos() {
        return corePos;
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        if (corePos != null) {
            tag.putLong("CorePos", corePos.asLong());
        }
    }

    @Override
    public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        if (tag.contains("CorePos")) {
            this.corePos = BlockPos.of(tag.getLong("CorePos"));
        }
    }
}
