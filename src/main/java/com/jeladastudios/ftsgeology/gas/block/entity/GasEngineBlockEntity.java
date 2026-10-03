package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.IGasHandler;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import com.jeladastudios.ftsgeology.gas.registry.GasCapabilities;
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

/**
 * Spark-ignition gas engine / generator.
 * <ul>
 *   <li>Front: air intake, from the cell in front. If that air already contains fuel (a methane
 *       filled mine), the engine runs on it directly.</li>
 *   <li>Sides, back, bottom: fuel inlet from pipes. Fuel is injected to reach a stoichiometric
 *       charge.</li>
 *   <li>Top: exhaust. Into a pipe if one is attached, otherwise into the world \u2014 running an engine
 *       in a closed room fills it with CO\u2082 and, when air runs short, carbon monoxide.</li>
 * </ul>
 * 35 % thermal efficiency, 1 FE = 1 kJ. A redstone signal stops it.
 */
public class GasEngineBlockEntity extends GasMachineBlockEntity {
    public static final double EFFICIENCY = 0.35;
    /** Air drawn per tick at full load (mol). */
    public static final double DISPLACEMENT = 4.0;

    private final GasTank fuel = new GasTank(0.5, 50).onChange(this::setChanged);
    private final GasTank exhaust = new GasTank(0.2, 10).onChange(this::setChanged);
    private final MachineEnergy energy = new MachineEnergy(60000, 0, 1000, this::setChanged);
    private double lastFe;
    private double lastPhi;
    private boolean lastCo;
    private String stallReason = "";

    public GasEngineBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_ENGINE.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        if (side == null) return fuel;
        if (side == facing()) return null;
        if (side == Direction.UP) return exhaust;
        return fuel;
    }

    @Override
    protected MachineEnergy energy() {
        return energy;
    }

    @Override
    protected boolean energyOn(@Nullable Direction side) {
        return side != facing();
    }

    @Override
    public java.util.List<Port> ports() {
        return java.util.List.of(new Port("fuel", null, true, backSidesBottom(), fuel),
                new Port("air", null, true, front(), null),
                new Port("exhaust", null, false, java.util.List.of(Direction.UP), exhaust));
    }

    @Override
    protected @Nullable String idle() {
        return switch (stallReason) {
            case "" -> null;
            case "intake" -> "blocked";
            case "full" -> "energy_full";
            default -> stallReason;
        };
    }

    @Override
    public void serverTick() {
        energy.pushTo(level, worldPosition, facing(), 1000);
        ventExhaust();
        lastFe = 0;
        if (level.hasNeighborSignal(worldPosition)) {
            stall("redstone");
            return;
        }
        if (energy.space() < 50) {
            stall("full");
            return;
        }
        if (exhaust.pressure() > 5.0) {
            stall("exhaust");
            return;
        }
        Direction front = facing();
        if (!worldOpen(front)) {
            stall("intake");
            return;
        }
        BlockPos intakePos = worldPosition.relative(front);
        double load = Math.min(1.0, energy.space() / 200.0 + 0.2);
        GasMix cellNow = gas().sample(intakePos);
        GasMix charge = gas().extract(intakePos, Math.min(DISPLACEMENT * load, cellNow.total() * 0.3));

        // Oxygen left after the fuel already in the intake air has burnt.
        double spareO2 = charge.get(Gas.OXYGEN) - Combustion.o2Required(charge);
        GasMix injected = new GasMix();
        if (spareO2 > 0 && fuel.total() > 1e-6) {
            double t = fuel.total();
            double needPerMol = (Combustion.o2Required(fuel.gas) - fuel.gas.get(Gas.OXYGEN)) / t;
            double n = needPerMol > 1e-3 ? spareO2 / needPerMol : 1.0;
            n = Math.min(n, Math.min(t, 3.0));
            injected = fuel.extract(n);
            charge.add(injected);
        }

        double req = Combustion.o2Required(charge);
        double o2 = charge.get(Gas.OXYGEN);
        double phi = o2 > 1e-6 ? req / o2 : 99;
        lastPhi = phi;
        if (req < 1e-6 || phi < 0.5 || phi > 2.0) {
            // Too lean, too rich or no fuel: misfire. Give everything back.
            subtractInjected(charge, injected);
            if (!injected.isEmpty()) fuel.insert(injected);
            gas().release(intakePos, charge);
            stall(req < 1e-6 ? "fuel" : phi < 0.5 ? "lean" : "rich");
            return;
        }
        double heat = Combustion.burn(charge);
        lastCo = charge.get(Gas.CARBON_MONOXIDE) > 1e-4;
        int fe = (int) Math.floor(heat * EFFICIENCY);
        energy.generate(fe);
        lastFe = fe;
        stallReason = "";
        exhaust.gas.add(charge);
        exhaust.changed();
        ventExhaust();
        setLit(true);
        effects();
    }

    private static void subtractInjected(GasMix charge, GasMix injected) {
        for (int i = 0; i < Gas.COUNT; i++) charge.m[i] = Math.max(0, charge.m[i] - injected.m[i]);
    }

    /** Exhaust leaves through an attached pipe, else into the world above (or in front). */
    private void ventExhaust() {
        if (exhaust.gas.isEmpty()) return;
        IGasHandler above = GasCapabilities.handlerAt(level, worldPosition.above(), Direction.DOWN);
        if (above != null && above.getTank(Direction.DOWN) != null) return; // the pipe pulls it
        if (worldOpen(Direction.UP)) gas().release(worldPosition.above(), exhaust.gas);
        else if (worldOpen(facing())) gas().release(worldPosition.relative(facing()), exhaust.gas);
        exhaust.changed();
    }

    private void stall(String reason) {
        stallReason = reason;
        setLit(false);
    }

    private void effects() {
        ServerLevel sl = (ServerLevel) level;
        long t = sl.getGameTime();
        if (t % 5 == 0) {
            BlockPos out = worldOpen(Direction.UP) ? worldPosition.above() : worldPosition.relative(facing());
            sl.sendParticles(lastCo ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    out.getX() + 0.5, out.getY() + 0.1, out.getZ() + 0.5, 2, 0.15, 0.1, 0.15, 0.02);
        }
        if (t % 6 == 0) sl.playSound(null, worldPosition, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.12f, 0.55f);
    }

    @Override
    public List<Component> status() {
        List<Component> out = super.status();
        out.add(Component.translatable("block.fts_geology.gas_engine").withStyle(ChatFormatting.GOLD));
        out.add(Component.literal(String.format(Locale.ROOT, "  %d / %d FE   %.0f FE/t   \u03C6=%.2f",
                energy.getEnergyStored(), energy.getMaxEnergyStored(), lastFe, lastPhi)).withStyle(ChatFormatting.WHITE));
        if (!stallReason.isEmpty()) {
            out.add(Component.translatable("block.fts_geology.gas_engine.stall." + stallReason).withStyle(ChatFormatting.YELLOW));
        }
        if (lastCo) out.add(Component.translatable("block.fts_geology.gas_engine.co_warning").withStyle(ChatFormatting.RED));
        out.add(Component.translatable("fts_geology.gas.fuel_tank", GasText.atm(fuel.pressure()), GasText.mol(fuel.total())).withStyle(ChatFormatting.GRAY));
        GasText.appendComposition(fuel.gas, out::add);
        out.add(Component.translatable("block.fts_geology.gas_engine.help").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("fuel", fuel.save());
        tag.put("exhaust", exhaust.save());
        tag.putInt("energy", energy.getEnergyStored());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        fuel.load(tag.getCompound("fuel"));
        exhaust.load(tag.getCompound("exhaust"));
        energy.set(tag.getInt("energy"));
    }

    @Override
    public void onRemoved() {
        if (!fuel.gas.isEmpty()) gas().release(worldPosition, fuel.gas);
        if (!exhaust.gas.isEmpty()) gas().release(worldPosition, exhaust.gas);
    }
}
