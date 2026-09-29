package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.hydrology.Aquifer;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A well pump: it lifts groundwater up the {@link com.jeladastudios.ftsgeology.block.WellCasingBlock well casing} under
 * it, on Forge Energy, and hands the water to whatever beside it or on top of it takes water. The water comes in at the
 * casing's foot, from the rock there ({@link Aquifer}): gravel and sand give all it can lift, limestone and basalt a
 * good deal, granite and slate little, clay a trickle -- and only while water stands over the foot. What it draws is
 * drawn from the ground: the water table round it sinks in a cone for as long as it runs, the faster the tighter the
 * rock, until the well draws itself dry or the pump stops and the cone fills back in. Lifting costs energy by the block.
 */
public class WellPumpBlockEntity extends BlockEntity {

    private static final int SURVEY_EVERY = 100, READ_EVERY = 20;
    private static final int TANK = 8_000, STORE = 40_000, TAKE_PER_TICK = 4_000, PUSH_PER_TICK = 1_000;

    /** Filled from outside, spent from inside. */
    private static final class Store extends EnergyStorage {
        Store() {
            super(STORE, TAKE_PER_TICK, 0);
        }

        void set(int amount) {
            energy = Mth.clamp(amount, 0, capacity);
        }
    }

    private final Store energy = new Store();
    private final FluidTank tank = new FluidTank(TANK, s -> s.getFluid() == Fluids.WATER);
    /** What others see of the tank: water to take, nothing to put in. */
    private final IFluidHandler outlet = new IFluidHandler() {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public @NotNull FluidStack getFluidInTank(int t) {
            return tank.getFluid();
        }

        @Override
        public int getTankCapacity(int t) {
            return TANK;
        }

        @Override
        public boolean isFluidValid(int t, @NotNull FluidStack stack) {
            return false;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            FluidStack out = tank.drain(resource, action);
            if (action.execute() && !out.isEmpty()) setChanged();
            return out;
        }

        @Override
        public @NotNull FluidStack drain(int max, FluidAction action) {
            FluidStack out = tank.drain(max, action);
            if (action.execute() && !out.isEmpty()) setChanged();
            return out;
        }
    };
    private final LazyOptional<IEnergyStorage> power = LazyOptional.of(() -> energy);
    private final LazyOptional<IFluidHandler> water = LazyOptional.of(() -> outlet);

    /** Lengths of casing under it, and the foot of the last: the rock round it gives the water. */
    private int well;
    @Nullable
    private BlockPos foot;
    private Aquifer.Rock rock = Aquifer.CRYSTALLINE;
    /** Blocks of water standing over the foot, and blocks the pump lifts it; what the rock lets through, mB a tick. */
    private double over, lift, allowed;
    /** What it may lift but has not yet, below a whole millibucket; what it lifted on the last tick. */
    private double owed;
    private int lifted;
    private int nextSurvey, nextRead;

    public WellPumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WELL_PUMP.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WellPumpBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (--be.nextSurvey <= 0) {
            be.nextSurvey = SURVEY_EVERY;
            be.survey(server);
            be.nextRead = 0;
        }
        if (--be.nextRead <= 0) {
            be.nextRead = READ_EVERY;
            be.read(server);
        }
        be.pump(server);
        be.push(level, pos);
    }

    /** Follows the casing down to its foot. */
    private void survey(ServerLevel level) {
        BlockPos.MutableBlockPos at = worldPosition.mutable();
        int depth = 0, most = GeyserConfig.TURBINE_WELL_DEPTH.get();
        while (depth < most) {
            at.move(Direction.DOWN);
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, at) || !level.getBlockState(at).is(ModBlocks.WELL_CASING.get())) break;
            depth++;
        }
        well = depth;
        foot = depth == 0 ? null : worldPosition.below(depth);
        rock = foot == null ? Aquifer.CRYSTALLINE : Aquifer.rockAt(level, foot);
    }

    /** Where the water stands at the well now, and what the rock lets through with that much over the foot. */
    private void read(ServerLevel level) {
        // The groundwater is the overworld's: the Nether and the End have none to draw.
        if (foot == null || !Level.OVERWORLD.equals(level.dimension())) {
            over = lift = allowed = 0;
            return;
        }
        double waterY = Aquifer.waterY(level, worldPosition.getX(), worldPosition.getZ());
        over = waterY - foot.getY();
        lift = Math.max(1.0, worldPosition.getY() - waterY);
        allowed = Aquifer.millibucketsPerTick(Aquifer.capacity(rock, over));
    }

    private int perBucket() {
        return (int) Math.ceil(GeyserConfig.WELL_PUMP_ENERGY.get() * lift);
    }

    private void pump(ServerLevel level) {
        lifted = 0;
        if (foot == null || over <= 0) return;
        double rate = Math.min(allowed, GeyserConfig.WELL_PUMP_RATE.get());
        owed = Math.min(owed + rate, rate * 2 + 1);
        int want = Math.min(TANK - tank.getFluidAmount(), (int) owed);
        int cost = perBucket();
        if (cost > 0) want = (int) Math.min(want, energy.getEnergyStored() * 1000L / cost);
        if (want <= 0) return;
        tank.fill(new FluidStack(Fluids.WATER, want), IFluidHandler.FluidAction.EXECUTE);
        energy.set(energy.getEnergyStored() - (int) Math.ceil(want * (double) cost / 1000.0));
        owed -= want;
        lifted = want;
        Aquifer.draw(level, foot, want / 1000.0);
        setChanged();
    }

    /** Hands the water to whatever takes it on its four sides and its top; its foot is the well. */
    private void push(Level level, BlockPos pos) {
        if (tank.isEmpty()) return;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            BlockEntity other = level.getBlockEntity(pos.relative(d));
            if (other == null) continue;
            IFluidHandler into = other.getCapability(ForgeCapabilities.FLUID_HANDLER, d.getOpposite()).orElse(null);
            if (into == null) continue;
            int took = into.fill(tank.drain(PUSH_PER_TICK, IFluidHandler.FluidAction.SIMULATE), IFluidHandler.FluidAction.EXECUTE);
            if (took > 0) {
                tank.drain(took, IFluidHandler.FluidAction.EXECUTE);
                setChanged();
            }
            if (tank.isEmpty()) return;
        }
    }

    /** What a right-click reads off it. */
    public List<Component> report() {
        List<Component> out = new ArrayList<>();
        if (foot == null) {
            out.add(Component.translatable("message.fts_geology.pump.no_well"));
        } else {
            out.add(Component.translatable("message.fts_geology.pump.well", well,
                    Component.translatable("message.fts_geology.pump.rock." + rock.name()), dec(over)));
            if (over <= 0) out.add(Component.translatable("message.fts_geology.pump.dry", dec(-over)));
            out.add(Component.translatable("message.fts_geology.pump.output", lifted, dec(allowed),
                    GeyserConfig.WELL_PUMP_RATE.get()));
            if (level instanceof ServerLevel server) {
                double cone = Aquifer.drawdown(server, worldPosition.getX() + 0.5, worldPosition.getZ() + 0.5);
                if (cone >= 0.01) out.add(Component.translatable("message.fts_geology.pump.cone", dec(cone)));
            }
            if (over > 0 && energy.getEnergyStored() < perBucket()) {
                out.add(Component.translatable("message.fts_geology.pump.no_power"));
            }
        }
        out.add(Component.translatable("message.fts_geology.pump.stored", energy.getEnergyStored(), STORE,
                tank.getFluidAmount(), TANK, perBucket()));
        return out;
    }

    private static String dec(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        nextSurvey = 1;
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY) return power.cast();
        if (cap == ForgeCapabilities.FLUID_HANDLER && side != Direction.DOWN) return water.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        power.invalidate();
        water.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.put("Tank", tank.writeToNBT(new CompoundTag()));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energy.set(tag.getInt("Energy"));
        tank.readFromNBT(tag.getCompound("Tank"));
    }
}
