package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class GasTankBlockEntity extends GasMachineBlockEntity {
    public static final double VOLUME = 8.0;
    public static final double MAX_PRESSURE = 60.0;

    private final GasTank tank = new GasTank(VOLUME, MAX_PRESSURE).onChange(this::changed);
    private int lastComparator = -1;

    public GasTankBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_TANK.get(), pos, state);
    }

    public GasTank tank() {
        return tank;
    }

    private void changed() {
        setChanged();
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        return tank;
    }

    @Override
    public void serverTick() {
        if (level.getGameTime() % 10 != 0) return;
        int c = level.getBlockState(worldPosition).getAnalogOutputSignal(level, worldPosition);
        if (c != lastComparator) {
            lastComparator = c;
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    /** The vessel fails: all gas escapes at once, and the blast that broke it ignites it. */
    public void rupture() {
        if (tank.gas.isEmpty() || !(level instanceof ServerLevel sl)) return;
        GasMix escaping = tank.gas.copy();
        tank.gas.clear();
        double n = escaping.total();
        // Spread the release over the tank cell and its neighbours.
        int parts = 7;
        escaping.scale(1.0 / parts);
        gas().release(worldPosition, escaping.copy());
        for (Direction d : Direction.values()) gas().release(worldPosition.relative(d), escaping.copy());
        sl.sendParticles(ParticleTypes.CLOUD, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
                (int) Math.min(80, 10 + n / 50), 0.8, 0.8, 0.8, 0.2);
        sl.playSound(null, worldPosition, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 1.0f, 1.6f);
        // The escaping jet is too rich to burn at first; once it mixes with air, the fire from
        // the blast that broke the tank lights it.
        if (Combustion.fuelFraction(escaping) > 0) gas().igniteLater(worldPosition, 6, 4, 12);
    }

    @Override
    public List<Component> status() {
        List<Component> out = super.status();
        double p = tank.pressure();
        out.add(Component.translatable("block.fts_geology.gas_tank").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ROOT, " \u2014 %s / %.0f atm, %s", GasText.atm(p), MAX_PRESSURE, GasText.mol(tank.total())))
                        .withStyle(ChatFormatting.WHITE)));
        GasText.appendComposition(tank.gas, out::add);
        if (Combustion.fuelFraction(tank.gas) > 0) {
            out.add(Component.translatable("fts_geology.gas.energy_content", String.format(Locale.ROOT, "%.1f MJ", Combustion.heatContent(tank.gas) / 1000))
                    .withStyle(ChatFormatting.GRAY));
        }
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
