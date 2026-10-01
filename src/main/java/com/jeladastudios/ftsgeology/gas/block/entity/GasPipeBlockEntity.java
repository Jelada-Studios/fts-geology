package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.block.GasPipeBlock;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.IGasHandler;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import com.jeladastudios.ftsgeology.gas.registry.GasCapabilities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class GasPipeBlockEntity extends GasMachineBlockEntity {
    /** Inner volume of one pipe segment (m\u00B3) and its pressure rating (atm). */
    public static final double VOLUME = 0.1;
    public static final double MAX_PRESSURE = 100;

    private final GasTank tank = new GasTank(VOLUME, MAX_PRESSURE).onChange(this::setChanged);

    public GasPipeBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_PIPE.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        return tank;
    }

    @Override
    public boolean isPipe() {
        return true;
    }

    @Override
    public void serverTick() {
        BlockState state = getBlockState();
        for (Direction d : Direction.values()) {
            if (!state.getValue(GasPipeBlock.PROPS.get(d))) continue;
            IGasHandler h = GasCapabilities.handlerAt(level, worldPosition.relative(d), d.getOpposite());
            if (h == null) continue;
            GasTank other = h.getTank(d.getOpposite());
            if (other == null || other == tank) continue;
            if (h.isPipe()) {
                // Each pipe pair is handled once, by the pipe on the negative side.
                if (d.getAxisDirection() == Direction.AxisDirection.POSITIVE) GasTank.equalize(tank, other, 0.45, 0.1);
            } else {
                GasTank.equalize(tank, other, 0.35, 0.05);
            }
        }
    }

    @Override
    public void onRemoved() {
        // A cut pipe leaks its contents.
        if (!tank.gas.isEmpty()) gas().release(worldPosition, tank.gas);
    }

    @Override
    public List<Component> status() {
        List<Component> out = super.status();
        out.add(Component.translatable("block.fts_geology.gas_pipe").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" \u2014 " + GasText.atm(tank.pressure())).withStyle(ChatFormatting.WHITE)));
        GasText.appendComposition(tank.gas, out::add);
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("gas", tank.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        tank.load(tag.getCompound("gas"));
    }
}
