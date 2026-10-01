package com.jeladastudios.ftsgeology.gas.block.entity;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Membrane / pressure-swing gas separator. Mixture in at the back; the selected gas comes out of
 * the front, everything else out of the top (vented into the world if nothing is attached \u2014 handy
 * for turning mine air into pure methane).
 */
public class GasSeparatorBlockEntity extends GasMachineBlockEntity {
    public static final double RATE = 0.5;
    public static final double FE_PER_MOL = 2;
    public static final double FE_PER_MOL_TARGET = 10;

    private final GasTank input = new GasTank(0.5, 50).onChange(this::setChanged);
    private final GasTank target = new GasTank(0.5, 50).onChange(this::setChanged);
    private final GasTank rest = new GasTank(0.5, 50).onChange(this::setChanged);
    private final MachineEnergy energy = new MachineEnergy(20000, 500, 0, this::setChanged);
    private Gas selected = Gas.METHANE;
    private double lastRate;

    public GasSeparatorBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_SEPARATOR.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        if (side == null) return target;
        Direction f = facing();
        if (side == f) return target;
        if (side == f.getOpposite()) return input;
        if (side == Direction.UP) return rest;
        return null;
    }

    @Override
    protected MachineEnergy energy() {
        return energy;
    }

    @Override
    public InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).isEmpty() && player.isShiftKeyDown()) {
            selected = Gas.VALUES[(selected.ordinal() + 1) % Gas.COUNT];
            setChanged();
            player.displayClientMessage(Component.translatable("block.fts_geology.gas_separator.selected",
                    Component.literal(selected.formula).withStyle(s -> s.withColor(selected.color)), selected.displayName()), true);
            return InteractionResult.CONSUME;
        }
        return super.onUse(player, hand, hit);
    }

    @Override
    protected void controls(net.minecraft.nbt.ListTag out) {
        button(out, "prev", Component.literal("<"));
        button(out, "next_gas", Component.translatable("gui.fts_geology.gas_panel.target",
                Component.literal(selected.formula).withStyle(s -> s.withColor(selected.color)), selected.displayName()));
        button(out, "next", Component.literal(">"));
    }

    @Override
    public boolean control(String key, net.minecraft.server.level.ServerPlayer player) {
        int step = switch (key) {
            case "prev" -> Gas.COUNT - 1;
            case "next", "next_gas" -> 1;
            default -> 0;
        };
        if (step == 0) return false;
        selected = Gas.VALUES[(selected.ordinal() + step) % Gas.COUNT];
        setChanged();
        return true;
    }

    @Override
    public void serverTick() {
        ventIfUnconnected(rest, Direction.UP, 1.1);
        lastRate = 0;
        if (level.hasNeighborSignal(worldPosition) || input.gas.isEmpty()) return;
        double mol = Math.min(RATE, input.total());
        double share = input.gas.fraction(selected);
        mol = Math.min(mol, energy.getEnergyStored() / (FE_PER_MOL + FE_PER_MOL_TARGET * share));
        mol = Math.min(mol, share > 0 ? target.room() / share : mol);
        mol = Math.min(mol, share < 1 ? rest.room() / (1 - share) : mol);
        if (mol < 1e-4) return;
        GasMix m = input.extract(mol);
        double t = m.takeSpecies(selected, m.get(selected));
        target.gas.add(selected, t);
        rest.gas.add(m);
        target.changed();
        rest.changed();
        energy.consume((int) Math.ceil(mol * FE_PER_MOL + t * FE_PER_MOL_TARGET));
        lastRate = mol;
    }

    @Override
    public List<Component> status() {
        List<Component> o = super.status();
        o.add(Component.translatable("block.fts_geology.gas_separator").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" \u2192 " + selected.formula).withStyle(s -> s.withColor(selected.color))));
        o.add(Component.literal(String.format(Locale.ROOT, "  %d / %d FE   %.1f mol/s   in %s  out %s  rest %s",
                energy.getEnergyStored(), energy.getMaxEnergyStored(), lastRate * 20,
                GasText.atm(input.pressure()), GasText.atm(target.pressure()), GasText.atm(rest.pressure()))).withStyle(ChatFormatting.WHITE));
        o.add(Component.translatable("block.fts_geology.gas_separator.help").withStyle(ChatFormatting.DARK_GRAY));
        return o;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("in", input.save());
        tag.put("target", target.save());
        tag.put("rest", rest.save());
        tag.putInt("energy", energy.getEnergyStored());
        tag.putString("selected", selected.id);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        input.load(tag.getCompound("in"));
        target.load(tag.getCompound("target"));
        rest.load(tag.getCompound("rest"));
        energy.set(tag.getInt("energy"));
        Gas g = Gas.byId(tag.getString("selected"));
        selected = g == null ? Gas.METHANE : g;
    }

    @Override
    public void onRemoved() {
        for (GasTank t : new GasTank[]{input, target, rest}) {
            if (!t.gas.isEmpty()) gas().release(worldPosition, t.gas);
        }
    }
}
