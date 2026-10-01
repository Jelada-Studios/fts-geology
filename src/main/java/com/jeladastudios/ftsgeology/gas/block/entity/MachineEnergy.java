package com.jeladastudios.ftsgeology.gas.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;

/** Forge Energy buffer that marks its block entity dirty on change. */
public class MachineEnergy extends EnergyStorage {
    private final Runnable onChange;

    public MachineEnergy(int capacity, int maxReceive, int maxExtract, Runnable onChange) {
        super(capacity, maxReceive, maxExtract);
        this.onChange = onChange;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        int r = super.receiveEnergy(maxReceive, simulate);
        if (r > 0 && !simulate) onChange.run();
        return r;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        int r = super.extractEnergy(maxExtract, simulate);
        if (r > 0 && !simulate) onChange.run();
        return r;
    }

    /** Internal use, ignores transfer limits. */
    public int consume(int amount) {
        int take = Math.min(energy, Math.max(0, amount));
        energy -= take;
        if (take > 0) onChange.run();
        return take;
    }

    /** Internal generation, ignores transfer limits. */
    public int generate(int amount) {
        int add = Math.min(capacity - energy, Math.max(0, amount));
        energy += add;
        if (add > 0) onChange.run();
        return add;
    }

    public int space() {
        return capacity - energy;
    }

    /** What it takes in from outside now: its free space, no more than its intake allows. */
    public int room() {
        return Math.min(maxReceive, capacity - energy);
    }

    public void set(int value) {
        energy = Math.max(0, Math.min(capacity, value));
    }

    /**
     * Pushes energy into neighbouring receivers (generators): Electrodynamics' electricity at 120 V where the block takes
     * it there -- its wires take nothing else -- and Forge Energy where not.
     */
    public void pushTo(Level level, BlockPos pos, Direction skip, int maxPerSide) {
        pushTo(level, pos, d -> d == skip, maxPerSide);
    }

    /** The same, leaving out every side {@code skip} says. */
    public void pushTo(Level level, BlockPos pos, java.util.function.Predicate<Direction> skip, int maxPerSide) {
        for (Direction d : Direction.values()) {
            if (skip.test(d) || energy <= 0) continue;
            BlockEntity be = level.getBlockEntity(pos.relative(d));
            if (be == null) continue;
            int offered = Math.min(energy, maxPerSide);
            int taken = com.jeladastudios.ftsgeology.compat.ElectrodynamicsPower.give(be, d.getOpposite(), offered);
            if (taken >= 0) {
                if (taken > 0) {
                    energy -= taken;
                    onChange.run();
                }
                continue;
            }
            IEnergyStorage target = be.getCapability(ForgeCapabilities.ENERGY, d.getOpposite()).orElse(null);
            if (target == null || !target.canReceive()) continue;
            int sent = target.receiveEnergy(offered, false);
            if (sent > 0) {
                energy -= sent;
                onChange.run();
            }
        }
    }
}
