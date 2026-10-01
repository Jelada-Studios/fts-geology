package com.jeladastudios.ftsgeology.gas;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Capability exposed by blocks that hold gas (pipes, tanks, machines). A side returning
 * {@code null} is not connectable.
 */
public interface IGasHandler {
    @Nullable
    GasTank getTank(@Nullable Direction side);

    /** Pipes only move gas between each other; a pipe never pulls from a pipe on its own. */
    default boolean isPipe() {
        return false;
    }

    /** Whether pipes should attach to this side (defaults to having a tank there). */
    default boolean connectsTo(@Nullable Direction side) {
        return getTank(side) != null;
    }
}
