package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.block.ThermoelectricBlock;
import com.jeladastudios.ftsgeology.gas.block.entity.GasValveBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.MachineEnergy;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A thermoelectric generator: power from a difference in temperature and nothing moving, as the generators on deep-space
 * probes and in remote pipeline stations make it. Its hot plate faces a heat -- lava, a magma block, a fire or a
 * campfire, a hot spring or a steam vent, or best a lit gas burner aimed into it -- and its fins the other way into the
 * cold: water, ice or snow, else the air, which draws the heat off far worse. The power grows with the square of the
 * difference, as a thermoelectric couple's does, at the few per cent of the heat such couples turn into electricity: a
 * fire against water gives some two hundred Forge Energy a tick, a lava lake against ice the most, three hundred, and a
 * hot spring hardly anything. It hands its energy to whatever takes it on its other four sides, Electrodynamics' wires
 * at 120 V where they are installed.
 */
public class ThermoelectricBlockEntity extends BlockEntity {

    public static final int CAPACITY = 20_000, PUSH = 400, MOST = 300;
    /** Forge Energy a tick for each square degree of difference. */
    private static final double COUPLE = 0.00035;

    private final MachineEnergy energy = new MachineEnergy(CAPACITY, 0, PUSH, this::setChanged);
    private final LazyOptional<IEnergyStorage> fe = LazyOptional.of(() -> energy);
    private final LazyOptional<Object> volts = com.jeladastudios.ftsgeology.compat.ElectrodynamicsPower.generator(
            energy::getEnergyStored, () -> CAPACITY, energy::set, this::setChanged);

    private double hot = Double.NaN, cold = Double.NaN;
    private int output;

    public ThermoelectricBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.THERMOELECTRIC.get(), pos, state);
    }

    /** The side its hot plate faces. */
    public Direction hotFace() {
        return getBlockState().getValue(ThermoelectricBlock.FACING);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ThermoelectricBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (level.getGameTime() % 10 == 0) be.measure(server, pos);
        if (be.output > 0) be.energy.generate(be.output);
        Direction h = be.hotFace();
        be.energy.pushTo(level, pos, d -> d.getAxis() == h.getAxis(), PUSH);
        boolean lit = be.output >= 20;
        if (state.getValue(ThermoelectricBlock.LIT) != lit) level.setBlock(pos, state.setValue(ThermoelectricBlock.LIT, lit), 3);
    }

    private void measure(ServerLevel level, BlockPos pos) {
        Direction h = hotFace();
        double air = com.jeladastudios.ftsgeology.weather.Meteorology.temperature(level, pos);
        double hotSide = heatOf(level, pos.relative(h), h.getOpposite());
        hot = Double.isNaN(hotSide) ? air : hotSide;
        double[] c = coldOf(level, pos.relative(h.getOpposite()), air);
        cold = c[0];
        double dt = Math.max(0, hot - cold) * c[1];
        output = (int) Math.min(MOST, COUPLE * dt * dt);
        setChanged();
    }

    /** The temperature of a heat by the hot plate, degrees C, or NaN where nothing there is hot. */
    private double heatOf(ServerLevel level, BlockPos at, Direction toUs) {
        BlockState s = level.getBlockState(at);
        FluidState f = level.getFluidState(at);
        if (f.is(FluidTags.LAVA)) return 1100;
        if (s.is(Blocks.MAGMA_BLOCK)) return 700;
        if (s.is(Blocks.FIRE)) return 800;
        if (s.is(Blocks.SOUL_FIRE)) return 600;
        if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) return s.is(Blocks.SOUL_CAMPFIRE) ? 500 : 700;
        // A lit gas burner aimed into the plate: as hot as the flame it gives.
        if (level.getBlockEntity(at) instanceof GasValveBlockEntity valve && valve.nozzleFacing() == toUs && valve.flameHeat() > 0) {
            return Math.min(1300, 400 + 160 * valve.flameHeat());
        }
        // A standing gas flame with the plate in it.
        double vent = com.jeladastudios.ftsgeology.gas.world.GasManager.get(level).flames().heatAt(at);
        if (vent > 0) return Math.min(1300, 600 + vent);
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id != null && "fts_geology".equals(id.getNamespace())) {
            switch (id.getPath()) {
                case "steam_vent" -> { return 140; }
                case "mud_pot" -> { return 95; }
                case "hot_spring" -> { return 90; }
                default -> { }
            }
        }
        return Double.NaN;
    }

    /** The cold the fins stand in: {degrees C, how well it draws the heat off, 1 for water or ice}. */
    private static double[] coldOf(ServerLevel level, BlockPos at, double air) {
        BlockState s = level.getBlockState(at);
        FluidState f = level.getFluidState(at);
        if (s.is(Blocks.BLUE_ICE)) return new double[]{-15, 1};
        if (s.is(Blocks.PACKED_ICE)) return new double[]{-5, 1};
        if (s.is(Blocks.ICE)) return new double[]{0, 1};
        if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) return new double[]{-2, 0.9};
        if (f.is(FluidTags.WATER)) return new double[]{Math.max(2, Math.min(22, air - 4)), 1};
        return new double[]{air, 0.35};
    }

    public List<Component> report() {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("block.fts_geology.thermoelectric_generator").withStyle(ChatFormatting.GOLD));
        if (Double.isNaN(hot)) {
            out.add(Component.translatable("message.fts_geology.thermoelectric.warming"));
        } else {
            out.add(Component.translatable("message.fts_geology.thermoelectric.reading", String.format(Locale.ROOT, "%.0f", hot),
                    String.format(Locale.ROOT, "%.0f", cold), output));
        }
        out.add(Component.translatable("message.fts_geology.thermoelectric.stored", energy.getEnergyStored(), CAPACITY));
        return out;
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        boolean face = side != null && side.getAxis() == hotFace().getAxis();
        if (cap == ForgeCapabilities.ENERGY && !face) return fe.cast();
        if (com.jeladastudios.ftsgeology.compat.ElectrodynamicsPower.is(cap) && !face) return volts.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        fe.invalidate();
        volts.invalidate();
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
