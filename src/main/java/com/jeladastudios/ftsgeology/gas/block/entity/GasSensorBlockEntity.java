package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.block.GasSensorBlock;
import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class GasSensorBlockEntity extends GasMachineBlockEntity {
    /** What the sensor measures, and the reading that gives a full signal of 15. */
    public enum Mode {
        LEL(100), OXYGEN_DEFICIENCY(0.12), CARBON_MONOXIDE(400), HYDROGEN_SULFIDE(100),
        CARBON_DIOXIDE(0.05), SULFUR_DIOXIDE(50), PRESSURE(50);

        final double fullScale;

        Mode(double fullScale) {
            this.fullScale = fullScale;
        }

        public String key() {
            return "block.fts_geology.gas_sensor.mode." + name().toLowerCase(Locale.ROOT);
        }
    }

    private Mode mode = Mode.LEL;
    private double lastValue;

    public GasSensorBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_SENSOR.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        return null;
    }

    @Override
    public InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown()) return super.onUse(player, hand, hit);
        mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
        setChanged();
        player.displayClientMessage(Component.translatable("block.fts_geology.gas_sensor.set", Component.translatable(mode.key())), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public void serverTick() {
        if (level.getGameTime() % 10 != 0) return;
        Direction f = getBlockState().getValue(GasSensorBlock.FACING);
        GasTank tank = neighbourTank(f);
        GasMix g;
        double pressure;
        if (tank != null) {
            g = tank.gas;
            pressure = tank.pressure();
        } else {
            g = gas().sample(worldPosition.relative(f));
            pressure = g.total() / Gas.MOL_PER_BLOCK;
        }
        lastValue = switch (mode) {
            case LEL -> Combustion.percentLel(g);
            case OXYGEN_DEFICIENCY -> Math.max(0, Gas.AIR_O2 - g.fraction(Gas.O2));
            case CARBON_MONOXIDE -> g.fraction(Gas.CO) * 1e6;
            case HYDROGEN_SULFIDE -> g.fraction(Gas.H2S) * 1e6;
            case CARBON_DIOXIDE -> g.fraction(Gas.CO2);
            case SULFUR_DIOXIDE -> g.fraction(Gas.SO2) * 1e6;
            case PRESSURE -> pressure;
        };
        int power = lastValue <= 1e-9 ? 0 : Mth.clamp((int) Math.ceil(lastValue / mode.fullScale * 15), 1, 15);
        // Tiny traces should not trip anything.
        if (mode == Mode.OXYGEN_DEFICIENCY && lastValue < 0.005) power = 0;
        BlockState s = getBlockState();
        if (s.getValue(GasSensorBlock.POWER) != power) level.setBlock(worldPosition, s.setValue(GasSensorBlock.POWER, power), 3);
    }

    @Override
    public List<Component> status() {
        List<Component> o = super.status();
        String value = switch (mode) {
            case LEL -> String.format(Locale.ROOT, "%.0f%% LEL", lastValue);
            case OXYGEN_DEFICIENCY -> "-" + GasText.pct(lastValue) + " O\u2082";
            case CARBON_DIOXIDE -> GasText.pct(lastValue);
            case PRESSURE -> GasText.atm(lastValue);
            default -> String.format(Locale.ROOT, "%.0f ppm", lastValue);
        };
        o.add(Component.translatable("block.fts_geology.gas_sensor").withStyle(ChatFormatting.GOLD).append(": ")
                .append(Component.translatable(mode.key())).append(Component.literal("  " + value + "  \u2192  "
                        + getBlockState().getValue(GasSensorBlock.POWER)).withStyle(ChatFormatting.WHITE)));
        o.add(Component.translatable("block.fts_geology.gas_sensor.help").withStyle(ChatFormatting.DARK_GRAY));
        return o;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("mode", mode.name());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try {
            mode = Mode.valueOf(tag.getString("mode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.LEL;
        }
    }
}
