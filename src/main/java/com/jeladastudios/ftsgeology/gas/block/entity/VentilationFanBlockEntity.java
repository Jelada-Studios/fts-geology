package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Mine ventilation fan: moves 4 mol/tick (\u2248 2 m\u00B3/s) of air from behind it to the front. Forcing
 * fresh air through workings is how real mines keep firedamp below its explosive limit.
 */
public class VentilationFanBlockEntity extends GasMachineBlockEntity {
    public static final double FLOW = 4.0;
    public static final int FE_PER_TICK = 8;

    private final MachineEnergy energy = new MachineEnergy(8000, 200, 0, this::setChanged);
    private boolean running;

    public VentilationFanBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.VENTILATION_FAN.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        return null;
    }

    @Override
    protected MachineEnergy energy() {
        return energy;
    }

    @Override
    public java.util.List<Port> ports() {
        return java.util.List.of(new Port("draw", null, true, back(), null),
                new Port("blow", null, false, front(), null));
    }

    @Override
    public void serverTick() {
        Direction f = facing();
        running = !level.hasNeighborSignal(worldPosition) && energy.getEnergyStored() >= FE_PER_TICK
                && worldOpen(f) && worldOpen(f.getOpposite());
        setLit(running);
        if (!running) return;
        energy.consume(FE_PER_TICK);
        GasMix moved = gas().extract(worldPosition.relative(f.getOpposite()), FLOW);
        gas().release(worldPosition.relative(f), moved);
    }

    @Override
    public List<Component> status() {
        List<Component> o = super.status();
        o.add(Component.translatable("block.fts_geology.ventilation_fan").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT, "  %d / %d FE  %s", energy.getEnergyStored(), energy.getMaxEnergyStored(),
                        running ? "\u25B6 " + String.format(Locale.ROOT, "%.0f mol/s", FLOW * 20) : "\u25A0")).withStyle(ChatFormatting.WHITE)));
        o.add(Component.translatable("block.fts_geology.ventilation_fan.help").withStyle(ChatFormatting.DARK_GRAY));
        return o;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("energy", energy.getEnergyStored());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energy.set(tag.getInt("energy"));
    }
}
