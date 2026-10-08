package com.jeladastudios.ftsgeology.compat;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Power to Electrodynamics' wires.
 *
 * <p>On Forge the turbine, the thermoelectric generator, the well pump and the gas machines speak Voltaic's
 * electricity (joules at a voltage) through a capability looked up by name, so that Electrodynamics' wires take their
 * power. On NeoForge 1.21.1 that capability is a block capability of Voltaic's own, and Voltaic for 1.21.1 is not on
 * this machine to be checked against: until it is, the bridge is off. The machines still give and take Forge Energy,
 * which Electrodynamics' battery boxes take at 120 volts.</p>
 *
 * <p>The methods keep their Forge shapes so the machines need no change when it is wired again.</p>
 */
public final class ElectrodynamicsPower {

    private ElectrodynamicsPower() {}

    /** A generator's side of Voltaic's electricity over a store of energy; null while the bridge is off. */
    public static Object generator(IntSupplier stored, IntSupplier capacity, IntConsumer set, Runnable changed) {
        return null;
    }

    /** A machine's side of Voltaic's electricity over a store of energy; null while the bridge is off. */
    public static Object receiver(IntSupplier stored, IntSupplier capacity, IntSupplier room, IntConsumer set,
                                  Runnable changed, DoubleConsumer tooHigh) {
        return null;
    }

    /** Joules handed to Voltaic's electricity on a block's side, or -1 where there is none: always -1 while off. */
    public static int give(BlockEntity other, Direction side, int offered) {
        return -1;
    }
}
