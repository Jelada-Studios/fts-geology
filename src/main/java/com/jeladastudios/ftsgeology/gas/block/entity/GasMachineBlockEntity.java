package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.IGasHandler;
import com.jeladastudios.ftsgeology.gas.registry.GasCapabilities;
import com.jeladastudios.ftsgeology.gas.world.GasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Base for every block entity that exposes gas tanks. */
public abstract class GasMachineBlockEntity extends BlockEntity {
    private final LazyOptional<IGasHandler> gasCap = LazyOptional.of(() -> new IGasHandler() {
        @Override
        public @Nullable GasTank getTank(@Nullable Direction side) {
            return tankFor(side);
        }

        @Override
        public boolean isPipe() {
            return GasMachineBlockEntity.this.isPipe();
        }

        @Override
        public boolean connectsTo(@Nullable Direction side) {
            return GasMachineBlockEntity.this.connectsOn(side);
        }
    });

    protected GasMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The tank reachable from {@code side} (null = any/internal). */
    @Nullable
    public abstract GasTank tankFor(@Nullable Direction side);

    public boolean isPipe() {
        return false;
    }

    public boolean connectsOn(@Nullable Direction side) {
        return tankFor(side) != null;
    }

    public void serverTick() {
    }

    /** Status lines shown on right-click. */
    public List<Component> status() {
        return new ArrayList<>();
    }

    /** Opens the gauge panel on the player's screen (see {@code GasPanelScreen}); it asks for fresh readings while open. */
    public InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.jeladastudios.ftsgeology.network.ModNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                    new com.jeladastudios.ftsgeology.network.TerminalPacket(worldPosition, panelData(), true));
        }
        return InteractionResult.CONSUME;
    }

    /**
     * What the gauge panel shows: the machine's name and status lines, its energy, a voltage it refused lately, and
     * its own tank's pressure and mix.
     */
    public net.minecraft.nbt.CompoundTag panelData() {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putBoolean("Gas", true);
        t.putString("Name", getBlockState().getBlock().getDescriptionId());
        net.minecraft.nbt.ListTag lines = new net.minecraft.nbt.ListTag();
        for (Component c : status()) lines.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(c)));
        t.put("Status", lines);
        MachineEnergy e = energy();
        if (e != null) {
            t.putInt("Energy", e.getEnergyStored());
            t.putInt("EnergyMax", e.getMaxEnergyStored());
        }
        if (level != null && refusedAt >= 0 && level.getGameTime() - refusedAt < 100) t.putDouble("Refused", refusedVolts);
        GasTank tank = tankFor(null);
        if (tank != null) {
            t.putDouble("Atm", tank.pressure());
            t.putDouble("MaxAtm", tank.maxPressure);
            t.putDouble("Moles", tank.total());
            net.minecraft.nbt.ListTag mix = new net.minecraft.nbt.ListTag();
            for (com.jeladastudios.ftsgeology.gas.Gas g : com.jeladastudios.ftsgeology.gas.Gas.VALUES) {
                double f = tank.gas.fraction(g);
                if (f < 1e-6) continue;
                net.minecraft.nbt.CompoundTag c = new net.minecraft.nbt.CompoundTag();
                c.putString("Id", g.id);
                c.putDouble("F", f);
                mix.add(c);
            }
            t.put("Mix", mix);
        }
        return t;
    }

    /** Called when the block is broken or replaced. */
    public void onRemoved() {
    }

    protected GasManager gas() {
        return GasManager.get((ServerLevel) level);
    }

    protected Direction facing() {
        BlockState s = getBlockState();
        if (s.hasProperty(BlockStateProperties.FACING)) return s.getValue(BlockStateProperties.FACING);
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return Direction.NORTH;
    }

    /** Gas tank of the neighbouring block on {@code side}. */
    @Nullable
    protected GasTank neighbourTank(Direction side) {
        return GasCapabilities.tankAt(level, worldPosition.relative(side), side.getOpposite());
    }

    /**
     * An output port with nothing attached vents into the world once its pressure exceeds
     * {@code threshold} atm. Returns true if something is attached on that side.
     */
    protected boolean ventIfUnconnected(GasTank tank, Direction side, double threshold) {
        IGasHandler h = GasCapabilities.handlerAt(level, worldPosition.relative(side), side.getOpposite());
        if (h != null && h.getTank(side.getOpposite()) != null) return true;
        double excess = tank.total() - tank.molesAt(threshold);
        if (excess > 1e-4 && worldOpen(side)) {
            gas().release(worldPosition.relative(side), tank.extract(excess));
        }
        return false;
    }

    protected boolean worldOpen(Direction side) {
        BlockPos p = worldPosition.relative(side);
        return level.isLoaded(p) && !gas().isGasTight(level.getBlockState(p), p);
    }

    protected void setLit(boolean lit) {
        BlockState s = getBlockState();
        if (s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT) != lit) {
            level.setBlock(worldPosition, s.setValue(BlockStateProperties.LIT, lit), 3);
        }
    }

    /** Forge Energy buffer, or null for machines without power. */
    @Nullable
    protected MachineEnergy energy() {
        return null;
    }

    /** Sides that accept/provide energy. */
    protected boolean energyOn(@Nullable Direction side) {
        return true;
    }

    private final LazyOptional<IEnergyStorage> energyCap = LazyOptional.of(this::energy);

    /**
     * The same store as Electrodynamics' electricity, where it is installed, for a machine that takes power: its wires
     * feed it straight at 120 V; a higher voltage it refuses and says so, as the well pump does, rather than burning out.
     */
    private final LazyOptional<Object> volts = com.jeladastudios.ftsgeology.compat.ElectrodynamicsPower.receiver(
            () -> energy() == null ? 0 : energy().getEnergyStored(),
            () -> energy() == null ? 0 : energy().getMaxEnergyStored(),
            () -> energy() == null ? 0 : energy().room(),
            v -> {
                if (energy() != null) energy().set(v);
            },
            this::setChanged, this::tooHigh);
    /** The last voltage it refused, and when, for the reading. */
    private double refusedVolts;
    private long refusedAt = -1;

    private void tooHigh(double v) {
        refusedVolts = v;
        if (level != null) refusedAt = level.getGameTime();
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == GasCapabilities.GAS_HANDLER) {
            return side == null || connectsOn(side) ? gasCap.cast() : LazyOptional.empty();
        }
        if (cap == ForgeCapabilities.ENERGY && energy() != null) {
            return energyOn(side) ? energyCap.cast() : LazyOptional.empty();
        }
        if (com.jeladastudios.ftsgeology.compat.ElectrodynamicsPower.is(cap) && energy() != null && energy().canReceive()) {
            return energyOn(side) ? volts.cast() : LazyOptional.empty();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        gasCap.invalidate();
        energyCap.invalidate();
        volts.invalidate();
    }
}
