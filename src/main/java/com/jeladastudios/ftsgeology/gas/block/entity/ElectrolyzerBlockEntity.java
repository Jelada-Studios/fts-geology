package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.Gas;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Water electrolysis: 2 H\u2082O \u2192 2 H\u2082 + O\u2082. Needs 286 kJ/mol H\u2082 in theory; real cells take about
 * 360 kJ (\u2248 50 kWh per kg of hydrogen), so 360 FE per mole. 1 mB of water yields 1 mol H\u2082.
 * Hydrogen leaves at the top, oxygen at the back. Unconnected outlets vent into the world.
 */
public class ElectrolyzerBlockEntity extends GasMachineBlockEntity {
    public static final double RATE = 0.25;
    public static final double FE_PER_MOL = 360;
    public static final double MAX_PRESSURE = 50;

    private final GasTank h2 = new GasTank(0.5, MAX_PRESSURE).onChange(this::setChanged);
    private final GasTank o2 = new GasTank(0.5, MAX_PRESSURE).onChange(this::setChanged);
    private final MachineEnergy energy = new MachineEnergy(40000, 1000, 0, this::setChanged);
    private final FluidTank water = new FluidTank(10000, fs -> fs.getFluid().is(FluidTags.WATER)) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };
    private final LazyOptional<IFluidHandler> waterCap = LazyOptional.of(() -> water);
    private double waterDebt;
    private double lastRate;

    public ElectrolyzerBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.ELECTROLYZER.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        if (side == null) return o2;
        if (side == Direction.UP) return h2;
        if (side == facing().getOpposite()) return o2;
        return null;
    }

    @Override
    protected MachineEnergy energy() {
        return energy;
    }

    @Override
    public InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit) {
        if (FluidUtil.interactWithFluidHandler(player, hand, water)) return InteractionResult.CONSUME;
        return super.onUse(player, hand, hit);
    }

    @Override
    public void serverTick() {
        ventIfUnconnected(h2, Direction.UP, 1.1);
        ventIfUnconnected(o2, facing().getOpposite(), 1.1);
        lastRate = 0;
        if (level.hasNeighborSignal(worldPosition)) {
            setLit(false);
            return;
        }
        double mol = RATE;
        mol = Math.min(mol, water.getFluidAmount() - waterDebt);
        mol = Math.min(mol, h2.roomUntil(MAX_PRESSURE));
        mol = Math.min(mol, o2.roomUntil(MAX_PRESSURE) * 2);
        mol = Math.min(mol, energy.getEnergyStored() / FE_PER_MOL);
        if (mol < 1e-3) {
            setLit(false);
            return;
        }
        h2.gas.add(Gas.HYDROGEN, mol);
        o2.gas.add(Gas.OXYGEN, mol / 2);
        h2.changed();
        o2.changed();
        energy.consume((int) Math.ceil(mol * FE_PER_MOL));
        waterDebt += mol;
        int drain = (int) Math.floor(waterDebt);
        if (drain > 0) {
            water.drain(drain, IFluidHandler.FluidAction.EXECUTE);
            waterDebt -= drain;
        }
        lastRate = mol;
        setLit(true);
        if (level.getGameTime() % 8 == 0) {
            ((ServerLevel) level).sendParticles(ParticleTypes.BUBBLE_POP, worldPosition.getX() + 0.5, worldPosition.getY() + 1.02,
                    worldPosition.getZ() + 0.5, 2, 0.25, 0, 0.25, 0.01);
        }
    }

    @Override
    public List<Component> status() {
        List<Component> out = super.status();
        out.add(Component.translatable("block.fts_geology.electrolyzer").withStyle(ChatFormatting.GOLD));
        out.add(Component.literal(String.format(Locale.ROOT, "  %d / %d FE   %s: %d / %d mB   %.1f mol H\u2082/s",
                energy.getEnergyStored(), energy.getMaxEnergyStored(), Component.translatable("block.minecraft.water").getString(),
                water.getFluidAmount(), water.getCapacity(), lastRate * 20)).withStyle(ChatFormatting.WHITE));
        out.add(Component.literal("  H\u2082: " + GasText.atm(h2.pressure()) + "   O\u2082: " + GasText.atm(o2.pressure())).withStyle(ChatFormatting.GRAY));
        out.add(Component.translatable("block.fts_geology.electrolyzer.help").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.FLUID_HANDLER && side != Direction.UP) return waterCap.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        waterCap.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("h2", h2.save());
        tag.put("o2", o2.save());
        tag.putInt("energy", energy.getEnergyStored());
        tag.put("water", water.writeToNBT(new CompoundTag()));
        tag.putDouble("waterDebt", waterDebt);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        h2.load(tag.getCompound("h2"));
        o2.load(tag.getCompound("o2"));
        energy.set(tag.getInt("energy"));
        water.readFromNBT(tag.getCompound("water"));
        waterDebt = tag.getDouble("waterDebt");
    }

    @Override
    public void onRemoved() {
        if (!h2.gas.isEmpty()) gas().release(worldPosition, h2.gas);
        if (!o2.gas.isEmpty()) gas().release(worldPosition, o2.gas);
    }
}
