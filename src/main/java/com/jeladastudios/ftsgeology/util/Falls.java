package com.jeladastudios.ftsgeology.util;

import com.jeladastudios.ftsgeology.GeysersMod;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * A block coming down. Where the ground's entities are ticking it falls as vanilla's falling block does. Where the chunk
 * is loaded but its entities stand still -- past the simulation distance, inside the view distance -- a falling block
 * stayed in the air where it was made until a player came near: seen hanging there, and a great quake's shaking, felt
 * far out, left eleven thousand of them for the server to track. There the block comes down at once, where it would
 * have landed.
 *
 * <p>The shaking's own blocks come down here. The sand and gravel the shaking takes the ground from under fall as
 * vanilla makes them, and in those chunks they hung the same way: so where the ground was shaken a short while ago, a
 * falling block of vanilla's that cannot move is set down at once as well.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Falls {

    private Falls() {}

    /** The most blocks a block made to land at once looks down for its ground. */
    private static final int DEEPEST = 384;
    /** How long after the shaking a chunk's sand and gravel are still set down at once, ticks. */
    private static final int SHAKEN_FOR = 1200;

    /** Chunks shaken lately, to the game time they stay so; one level's at a time (the overworld's, where quakes are). */
    private static final Long2LongOpenHashMap SHAKEN = new Long2LongOpenHashMap();

    /** The ground at a place has been shaken: sand and gravel coming down there now are set down at once. */
    public static void shaken(ServerLevel level, BlockPos at) {
        if (SHAKEN.size() > 65_536) SHAKEN.clear();
        SHAKEN.put(ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4), level.getGameTime() + SHAKEN_FOR);
    }

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
        shaken(level, at);
        level.setBlock(at, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        land(level, at, state, dropItem);
        return null;
    }

    /** Sets a block down where it lands from {@code at}: down through air, plants and water to what holds it. */
    private static void land(ServerLevel level, BlockPos at, BlockState state, boolean dropItem) {
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
    }

    /** Vanilla's falling sand and gravel in ground shaken lately, where it could not move: set down at once instead. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (SHAKEN.isEmpty() || event.loadedFromDisk() || event.getEntity().getClass() != FallingBlockEntity.class
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FallingBlockEntity f = (FallingBlockEntity) event.getEntity();
        BlockPos at = f.blockPosition();
        long until = SHAKEN.get(ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4));
        if (until == 0L || level.getGameTime() > until || level.isPositionEntityTicking(at)) return;
        BlockState state = f.getBlockState();
        Block b = state.getBlock();
        // What changes as it lands, or does something else than land, falls as vanilla has it.
        if (!(b instanceof FallingBlock) || b instanceof ConcretePowderBlock || b instanceof AnvilBlock || b instanceof DragonEggBlock) return;
        event.setCanceled(true);
        land(level, at, state, f.dropItem);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SHAKEN.clear();
    }
}
