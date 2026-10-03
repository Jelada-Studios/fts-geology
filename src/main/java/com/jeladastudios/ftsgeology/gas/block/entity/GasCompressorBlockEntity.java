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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Electric gas compressor. Front: intake (a pipe, or the open air/room in front of it).
 * Back: high-pressure outlet into a pipe or tank, up to 50 atm. Work follows isothermal
 * compression, W = nRT\u00B7ln(P\u2082/P\u2081), divided by efficiency. A redstone signal stops it.
 */
public class GasCompressorBlockEntity extends GasMachineBlockEntity {
    public static final double MAX_OUTLET = 50.0;
    public static final double RATE = 1.0;

    private final MachineEnergy energy = new MachineEnergy(20000, 500, 0, this::setChanged);
    private double lastRate;
    private double lastCost;

    public GasCompressorBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_COMPRESSOR.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        return null;
    }

    @Override
    public boolean connectsOn(@Nullable Direction side) {
        return side != null && side.getAxis() == facing().getAxis();
    }

    @Override
    protected MachineEnergy energy() {
        return energy;
    }

    @Override
    protected boolean energyOn(@Nullable Direction side) {
        return side == null || side.getAxis() != facing().getAxis();
    }

    /** FE per mole: 1 + RT\u00B7ln(P\u2082/P\u2081) / \u03B7, with RT \u2248 2.44 kJ/mol and \u03B7 \u2248 0.7 (1 FE = 1 kJ). */
    public static double costPerMole(double pIn, double pOut) {
        return 1.0 + 3.5 * Math.log(Math.max(1.0, pOut / Math.max(0.05, pIn)));
    }

    @Override
    public java.util.List<Port> ports() {
        return java.util.List.of(new Port("intake", null, true, front(), null),
                new Port("outlet", null, false, back(), null));
    }

    /** Why it stood still on its last tick, or null. */
    private @Nullable String idleWhy = "no_outlet";

    @Override
    protected @Nullable String idle() {
        return idleWhy;
    }

    @Override
    public void serverTick() {
        lastRate = 0;
        Direction front = facing();
        Direction back = front.getOpposite();
        GasTank out = neighbourTank(back);
        if (out == null || level.hasNeighborSignal(worldPosition)) {
            idleWhy = out == null ? "no_outlet" : "redstone";
            setLit(false);
            return;
        }
        double pOut = out.pressure();
        GasTank in = neighbourTank(front);
        double pIn;
        if (in != null) pIn = in.pressure();
        else if (worldOpen(front)) pIn = gas().sample(worldPosition.relative(front)).total() / Gas.MOL_PER_BLOCK;
        else pIn = 0;

        double moles = Math.min(RATE, out.roomUntil(MAX_OUTLET));
        if (in != null) moles = Math.min(moles, in.total() * 0.5);
        else if (pIn < 0.3) moles = 0; // cannot pull a deeper vacuum
        double cost = costPerMole(pIn, Math.max(pOut, 1.0));
        moles = Math.min(moles, energy.getEnergyStored() / cost);
        if (moles < 1e-3) {
            idleWhy = energy.getEnergyStored() < cost * 1e-3 ? "no_power" : out.roomUntil(MAX_OUTLET) < 1e-3 ? "full" : "no_input";
            setLit(false);
            return;
        }
        idleWhy = null;
        GasMix m = in != null ? in.extract(moles) : gas().extract(worldPosition.relative(front), moles);
        double moved = out.insert(m);
        if (!m.isEmpty()) {
            if (in != null) in.insert(m);
            else gas().release(worldPosition.relative(front), m);
        }
        energy.consume((int) Math.ceil(moved * cost));
        lastRate = moved;
        lastCost = cost;
        setLit(moved > 0);
        if (moved > 0 && level.getGameTime() % 12 == 0) {
            level.playSound(null, worldPosition, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.12f, 1.6f);
        }
    }

    @Override
    public List<Component> status() {
        List<Component> out = super.status();
        out.add(Component.translatable("block.fts_geology.gas_compressor").withStyle(ChatFormatting.GOLD));
        out.add(Component.literal(String.format(Locale.ROOT, "  %d / %d FE   %.2f mol/s   %.1f FE/mol",
                energy.getEnergyStored(), energy.getMaxEnergyStored(), lastRate * 20, lastCost)).withStyle(ChatFormatting.WHITE));
        GasTank o = neighbourTank(facing().getOpposite());
        if (o != null) out.add(Component.translatable("fts_geology.gas.outlet_pressure", GasText.atm(o.pressure())).withStyle(ChatFormatting.GRAY));
        out.add(Component.translatable("block.fts_geology.gas_compressor.help").withStyle(ChatFormatting.DARK_GRAY));
        return out;
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
