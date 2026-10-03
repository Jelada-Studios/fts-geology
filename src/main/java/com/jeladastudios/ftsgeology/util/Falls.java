package com.jeladastudios.ftsgeology.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A block coming down. Where the ground's entities are ticking it falls as vanilla's falling block does. Where the chunk
 * is loaded but its entities stand still -- past the simulation distance, inside the view distance -- a falling block
 * stayed in the air where it was made until a player came near: seen hanging there, and a great quake's shaking, felt
 * far out, left eleven thousand of them for the server to track. There the block comes down at once, where it would
 * have landed.
 */
public final class Falls {

    private Falls() {}

    /** The most blocks a block made to land at once looks down for its ground. */
    private static final int DEEPEST = 384;

    /**
     * Brings a block down from {@code at}, the place cleared as vanilla's falling block clears it: the falling block,
     * or null where it landed at once. {@code dropItem}: whether it comes back as an item where it cannot be set down.
     */
    @Nullable
    public static FallingBlockEntity fall(ServerLevel level, BlockPos at, BlockState state, boolean dropItem) {
        if (level.isPositionEntityTicking(at)) {
            FallingBlockEntity f = FallingBlockEntity.fall(level, at, state);
            f.dropItem = dropItem;
            return f;
        }
        level.setBlock(at, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        // Down through air, plants and water to what holds it.
        BlockPos.MutableBlockPos m = at.mutable();
        int floor = Math.max(level.getMinBuildHeight(), at.getY() - DEEPEST), y = at.getY();
        while (y - 1 > floor && FallingBlock.isFree(level.getBlockState(m.set(at.getX(), y - 1, at.getZ())))) y--;
        m.set(at.getX(), y, at.getZ());
        BlockState there = level.getBlockState(m);
        if (FallingBlock.isFree(there) || there.canBeReplaced()) {
            level.setBlock(m, state, Block.UPDATE_ALL);
        } else if (dropItem) {
            Block.popResource(level, m, new ItemStack(state.getBlock()));
        }
        return null;
    }
}
