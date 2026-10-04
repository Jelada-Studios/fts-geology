package com.jeladastudios.ftsgeology.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * The river's flow on a client, registered with {@link FtsGeologyApi.Client#registerFlow}: what pushes the player and
 * a boat they steer, and the way the running water's texture moves. Asked on the client's own thread and on the threads
 * that build the chunks' meshes, so it has to be safe on any thread, and quick.
 */
@FunctionalInterface
public interface ClientFlow {

    /** How fast and which way the water runs at a river block, blocks a second; null to leave it to the block's own way. */
    Vec3 flow(BlockGetter level, BlockPos pos, FluidState state);
}
