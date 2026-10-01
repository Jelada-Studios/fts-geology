package com.jeladastudios.ftsgeology.gas.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Implemented by mod blocks that can be an open flame (burners). */
public interface GasIgniter {
    /**
     * @param side the face of this block that touches the gas, or null if unknown
     */
    boolean ignitesGas(BlockState state, @Nullable Direction side);
}
