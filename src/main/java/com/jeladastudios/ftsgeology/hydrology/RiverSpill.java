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
 * river's level and never runs. When a block beside it or under it is taken away -- a bank dug through, blown open, or
 * dropped by a quake -- the river gives running water into the gap, ordinary water that runs down and fills the hole
 * the way water does, and keeps giving it for as long as the hole takes it: with Flowing Fluids, whose water is
 * counted, the water at the gap is kept full from the river. The river itself is not drawn down; its upkeep keeps it
 * at its level. Nothing happens where the water is taken out on purpose without a block update (the mod's own
 * reservoirs and floods).
 */
public final class RiverSpill {

    private RiverSpill() {}

    private static long opened, kept;

    /** A block next to a river's water at {@code pos} changed, at {@code from}. */
    public static void consider(ServerLevel level, BlockPos pos, BlockPos from) {
        if (!GeyserConfig.RIVER_SPILLS.get() || HydraulicsHooks.active(level)) return;
        if (from.getY() > pos.getY() || Math.abs(from.getX() - pos.getX()) + Math.abs(from.getY() - pos.getY()) + Math.abs(from.getZ() - pos.getZ()) != 1) return;
        FluidState here = level.getFluidState(pos);
        if (!(here.getType() instanceof RiverWaterFluid) || !here.isSource()) return;
        BlockState s = level.getBlockState(from);
        FluidState f = s.getFluidState();
        if (s.isAir()) {
            // A gap just opened in the bank: the river runs into it.
            level.setBlock(from, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            opened++;
        } else if (f.getType() == Fluids.FLOWING_WATER || f.getType() == Fluids.WATER && !f.isSource()) {
            // The water run out through the gap, drawn down by another mod's counted water: the river tops it up.
            level.setBlock(from, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            kept++;
        }
    }

    public static String summary() {
        return String.format(Locale.ROOT, "river spills: %d gaps opened, %d kept full", opened, kept);
    }

    public static boolean any() {
        return opened + kept > 0;
    }

    public static void clear() {
        opened = kept = 0;
    }
}
