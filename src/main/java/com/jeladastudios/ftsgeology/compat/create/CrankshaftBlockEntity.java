package com.jeladastudios.ftsgeology.compat.create;

import com.jeladastudios.ftsgeology.gas.block.entity.GasEngineBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

/**
 * A gas engine's power as rotation. The engine burns its gas as it always does and the crankshaft takes the work out of
 * its store, as much as the shaft's load asks for: an idle network takes next to none, and the engine, its store full,
 * throttles back and burns less. What it offers the network is what the engine makes at full load on its fuel, at the
 * rate Create Crafts &amp; Additions turns FE into stress (480 FE a tick for 16,384 su at 256 rpm), so a gas engine's
 * work comes to the same whether its FE goes to an electric motor or its crank turns the shaft.
 */
public class CrankshaftBlockEntity extends GeneratingKineticBlockEntity {

    /** The shaft's speed, rpm. */
    public static final float RPM = 64;
    /** Stress units a tick's FE moves, at any speed: Crafts &amp; Additions' 16,384 su for 480 FE a tick. */
    public static final float SU_PER_FE = 16384f / 480f;
    /** How often the network is told of a change in what the engine offers, ticks, and how big a change it takes. */
    private static final int RETELL = 20;
    private static final float CHANGE = 0.1f;

    /** FE a tick the engine makes at full load, as last told to the network; and as the engine has it now. */
    private float offered, available;
    private float lastDrawn;
    private int sinceTold;

    public CrankshaftBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    /** The engine behind, coupled at an end of its axle; null when there is none. */
    private GasEngineBlockEntity engine() {
        Direction back = getBlockState().getValue(CrankshaftBlock.FACING).getOpposite();
        return level != null && level.getBlockEntity(worldPosition.relative(back)) instanceof GasEngineBlockEntity e
                && e.axleEnd(back.getOpposite()) ? e : null;
    }

    @Override
    public void initialize() {
        super.initialize();
        if (!hasSource() || getGeneratedSpeed() > getTheoreticalSpeed()) updateGeneratedRotation();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        GasEngineBlockEntity e = engine();
        available = e == null ? 0 : (float) e.fullLoadFe();
        // The network's load on what it is offered: the engine's work drawn in that share of the full load.
        float share = capacity > 0 ? Math.min(1f, stress / capacity) : 0f;
        lastDrawn = e == null || offered <= 0 ? 0 : e.driveShaft(Math.round(offered * share));
        sinceTold++;
        boolean onOff = (offered > 0) != (available > 0);
        boolean moved = Math.abs(available - offered) > CHANGE * Math.max(offered, 1f);
        if (onOff || (moved && sinceTold >= RETELL)) {
            offered = available;
            sinceTold = 0;
            updateGeneratedRotation();
        }
    }

    @Override
    public float getGeneratedSpeed() {
        return offered > 0 ? convertToDirection(RPM, getBlockState().getValue(CrankshaftBlock.FACING)) : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        float perRpm = offered * SU_PER_FE / RPM;
        lastCapacityProvided = perRpm;
        return perRpm;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean added = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        tooltip.add(Component.literal("    ").append(Component.translatable("block.fts_geology.crankshaft.goggles",
                String.format(Locale.ROOT, "%.0f", lastDrawn), String.format(Locale.ROOT, "%.0f", offered)))
                .withStyle(ChatFormatting.GRAY));
        return true;
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putFloat("offered", offered);
        tag.putFloat("drawn", lastDrawn);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        offered = tag.getFloat("offered");
        lastDrawn = tag.getFloat("drawn");
    }
}
