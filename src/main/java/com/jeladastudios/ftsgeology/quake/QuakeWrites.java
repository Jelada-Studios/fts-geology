package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.compat.DynamicTreesFelling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * How a quake writes a block: straight into its chunk, and to the clients, and nothing else. Through the level, every
 * write was also seen by other mods' hooks on it -- Flowing Fluids moving the water a block displaced, which asked for
 * the chunk next door and waited for it to load; and a Dynamic Trees branch taken away had its whole tree worked out
 * the same way. A quake's writes are many, and all on the server thread.
 *
 * <p>Only a chunk already in memory is written. A Dynamic Trees tree near the edge of what is loaded is left alone:
 * taking its branch out would have its tree read into chunks that are not there.</p>
 */
public final class QuakeWrites {

    private QuakeWrites() {}

    /** How far a Dynamic Trees tree reaches from the branch that is taken. */
    private static final int TREE_REACH = 8;

    /** Writes one block; false if its chunk is not in memory or the write was left undone. */
    public static boolean set(ServerLevel level, BlockPos pos, BlockState state) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return false;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return false;
        BlockState old = chunk.getBlockState(pos);
        if (old == state) return true;
        boolean branch = DynamicTreesFelling.isBranch(old);
        if (branch && !around(level, pos)) return false;
        // A block that holds contents or works, written over: it is given back as what it drops, its contents with it,
        // not lost. One being moved has had its block entity taken out first (see #move), and drops nothing here.
        net.minecraft.world.level.block.entity.BlockEntity be = old.hasBlockEntity() && !state.is(old.getBlock())
                && !com.jeladastudios.ftsgeology.eruption.EruptionHandler.isNaturalTerrain(old) ? chunk.getBlockEntity(pos) : null;
        if (be != null) Block.dropResources(old, level, pos.immutable(), be);
        // Over a Dynamic Trees branch, without its tree tearing itself down: the quake moves trees, it does not fell them.
        BlockState was = branch ? DynamicTreesFelling.quietly(() -> chunk.setBlockState(pos, state, false))
                : chunk.setBlockState(pos, state, false);
        if (was == null) return false;
        BlockPos at = pos.immutable();   // the point of interest update runs later, and a mutable position moves on
        level.sendBlockUpdated(at, was, state, Block.UPDATE_CLIENTS);
        level.onBlockStateChange(at, was, state);
        return true;
    }

    /**
     * Takes a block's contents and settings out of the world, to be laid again with {@link #lay} where its block is
     * moved; null for a block without them. Its block entity is gone after, so writing over the block drops nothing.
     */
    public static net.minecraft.nbt.CompoundTag lift(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(pos);
        if (be == null) return null;
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        chunk.removeBlockEntity(pos);
        return tag;
    }

    /** Lays what {@link #lift} took into the block now at {@code pos}. */
    public static void lay(ServerLevel level, BlockPos pos, net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) return;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        be.load(tag);
        be.setChanged();
    }

    /** Whether every chunk within a tree's reach of a position is in memory. */
    public static boolean around(ServerLevel level, BlockPos pos) {
        for (int cx = (pos.getX() - TREE_REACH) >> 4; cx <= (pos.getX() + TREE_REACH) >> 4; cx++) {
            for (int cz = (pos.getZ() - TREE_REACH) >> 4; cz <= (pos.getZ() + TREE_REACH) >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }
}
