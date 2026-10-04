package com.jeladastudios.ftsgeology.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns a fluid into a block where it stands: lava setting into rock. Flowing Fluids keeps the volume of a fluid a
 * block is put into and pushes it aside, searching out room for it, deep and over chunk edges -- in a big pack one
 * block of cooling lava held the server for a third of a second waiting on a chunk. Lava that sets is not pushed
 * anywhere: the fluid is taken out first (air pushes nothing aside) and the block put in its place.
 */
public final class Freeze {

    private Freeze() {}

    /** Puts {@code state} where a fluid may stand, with {@code flags}; the fluid, if any, goes first, quietly. */
    public static void set(Level level, BlockPos pos, BlockState state, int flags) {
        var fluid = level.getBlockState(pos).getFluidState();
        // Lava frozen where it lay is new bare ground, and its biome goes with it (volcano.BiomeScars).
        if (fluid.is(net.minecraft.tags.FluidTags.LAVA) && level instanceof net.minecraft.server.level.ServerLevel server) {
            com.jeladastudios.ftsgeology.volcano.BiomeScars.mark(server, pos.getX(), pos.getY(), pos.getZ());
        }
        if (!fluid.isEmpty()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(pos, state, flags);
    }
}
