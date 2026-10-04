package com.jeladastudios.ftsgeology.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A mod that runs the water of the rivers itself, registered with {@link FtsGeologyApi#registerHydraulics}. While one is
 * registered in a level the mod keeps its hands off the rivers' water there (see that method) and asks this instead.
 */
public interface Hydraulics {

    /**
     * How fast and which way the water runs at a block, blocks a second, or null for no answer (the block's own way is
     * used then). Asked on the server thread only, of the server level: whenever an entity in a river is pushed by it.
     * It is asked often; keep it to a look-up.
     */
    Vec3 flow(ServerLevel level, BlockPos pos);

    /**
     * Takes up to {@code millibuckets} of water out of the river at a block (1000 a block, a cubic metre), or only says
     * how much it would with {@code simulate}; returns what it took. Asked on the server thread, only in a chunk this
     * owns ({@link #owns}), by Create's open pipe end and hose pulley drawing on a river. Create asks a whole block at a
     * time; 0 gives Create nothing.
     */
    int drain(ServerLevel level, BlockPos pos, int millibuckets, boolean simulate);

    /**
     * Whether this runs the water of a chunk now. Asked on any thread, every time it matters (it is not kept): the mod
     * lays no rain pond there and leaves Create's drawing to {@link #drain}. Keep it cheap.
     */
    boolean owns(ServerLevel level, int chunkX, int chunkZ);
}
