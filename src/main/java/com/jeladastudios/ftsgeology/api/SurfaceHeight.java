package com.jeladastudios.ftsgeology.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;

/**
 * How high a river block's water is drawn on a client, registered with {@link FtsGeologyApi.Client#registerSurface}.
 * The mod's fluid drawing (vanilla's renderer and Embeddium's) asks it before its own reckoning, and its foam and the
 * leaves on the water float at it. Asked on the threads that build the chunks' meshes and on the render thread: safe
 * on any thread, and quick. Asked with a shader pack on too.
 */
@FunctionalInterface
public interface SurfaceHeight {

    /** The water's height in the block, 0 to 1; NaN for "not known", and the mod's own is used. */
    float height(BlockAndTintGetter level, BlockPos pos, FluidState state);
}
