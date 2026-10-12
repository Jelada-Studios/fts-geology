package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Locale;

/**
 * A river let out through its bank. The rivers' water stands still (see {@link RiverWaterFluid}): it is laid at its
 * river's level and never runs. When a block beside it is taken away -- a bank dug through, blown open, or dropped
 * by a quake -- the river gives running water into the gap, ordinary water that runs down and fills the hole the way
 * water does, and keeps giving it for as long as the hole takes it (a hole under its bed it fills with its own still
 * water): with Flowing Fluids, whose water is counted, the water at the gap is kept full from the river. The river
 * itself is not drawn down; its upkeep keeps it at its level. Nothing happens where the water is taken out on purpose
 * without a block update (the mod's own reservoirs and floods).
 */
public final class RiverSpill {

    private RiverSpill() {}

    private static long opened, kept;

    /** The gaps the rivers have run into, the latest kept: only these are kept full, not any water that comes by. */
    private static final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet GAPS = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private static final int GAPS_KEPT = 4096;

    /** A block next to a river's water at {@code pos} changed, at {@code from}. */
    public static void consider(ServerLevel level, BlockPos pos, BlockPos from) {
        if (!GeyserConfig.RIVER_SPILLS.get() || HydraulicsHooks.active(level)) return;
        if (from.getY() > pos.getY() || Math.abs(from.getX() - pos.getX()) + Math.abs(from.getY() - pos.getY()) + Math.abs(from.getZ() - pos.getZ()) != 1) return;
        FluidState here = level.getFluidState(pos);
        if (!(here.getType() instanceof RiverWaterFluid) || !here.isSource()) return;
        // Only a river lets water out: a block of its water standing alone, pushed up out of the channel on top of
        // something put into it, is no river, and kept a spring of water running off it without end.
        if (!inRiver(level, pos)) return;
        BlockState s = level.getBlockState(from);
        FluidState f = s.getFluidState();
        if (from.getY() < pos.getY()) {
            // A hole under the bed is filled by the river itself, with its own still water: ordinary water there was
            // pushed up over the river when the hole was closed again, and lay on it.
            if (s.isAir()) {
                level.setBlock(from, here.createLegacyBlock(), Block.UPDATE_ALL);
                opened++;
            }
            return;
        }
        if (s.isAir()) {
            // A gap just opened in the bank: the river runs into it.
            level.setBlock(from, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            opened++;
            GAPS.add(from.asLong());
            if (GAPS.size() > GAPS_KEPT) GAPS.removeFirstLong();
        } else if ((f.getType() == Fluids.FLOWING_WATER || f.getType() == Fluids.WATER && !f.isSource()) && GAPS.contains(from.asLong())) {
            // The water run out through the gap, drawn down by another mod's counted water: the river tops it up. Water
            // that came to the river from elsewhere (a stream, a spring's outflow, a bucket) is not made a spring by it.
            level.setBlock(from, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            kept++;
        }
    }

    /** Whether the rivers' water at a block is part of a river: more of it beside it at the same height. */
    static boolean inRiver(net.minecraft.world.level.LevelReader level, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(pos.relative(d)).getType() instanceof RiverWaterFluid) return true;
        }
        return false;
    }

    public static String summary() {
        return String.format(Locale.ROOT, "river spills: %d gaps opened, %d kept full", opened, kept);
    }

    public static boolean any() {
        return opened + kept > 0;
    }

    public static void clear() {
        opened = kept = 0;
        GAPS.clear();
    }
}
