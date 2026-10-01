package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.registry.ModFluids;
import com.jeladastudios.ftsgeology.worldgen.OilReserves;
import com.jeladastudios.ftsgeology.worldgen.PetroleumFields;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A well head: crude oil up the well casing under it, on the field's own pressure, from an oil field's oil. The casing's
 * foot has to be in the oil -- the oil-soaked sandstone between a dome's gas cap and its water; in the gas cap the
 * casing brings gas, in the water nothing. A fresh well flows freely and slows as its field runs down, all the wells on
 * a field drawing on the one store; a rich field gives more. The oil goes to whatever takes it beside or on top of the
 * head, or into a bucket.
 */
public class WellheadBlockEntity extends BlockEntity {

    private static final int SURVEY_EVERY = 100, TANK = 8_000, PUSH_PER_TICK = 1_000, MOST_DEPTH = 192;
    /** Millibuckets a tick from a fresh well on a field of average richness. */
    private static final double RATE = 2.0;
    /** Below this share of its oil left a field gives no more on its own pressure. */
    private static final double SPENT = 0.02;

    private final FluidTank tank = new FluidTank(TANK, s -> s.getFluid() == ModFluids.CRUDE_OIL.get());
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
    private final LazyOptional<IFluidHandler> oil = LazyOptional.of(() -> outlet);

    /**
     * The gas that comes up with the oil, as out of a real well: dissolved in it down in the reservoir, it bubbles out as
     * the pressure falls on the way up -- some hundred moles a bucket, mostly methane, with a little carbon dioxide and
     * nitrogen, and hydrogen sulphide from a sour field. A gas pipe on a side takes it, to be burnt in an engine or a
     * burner; with none the wellhead lets it off over its top.
     */
    private static final double GAS_PER_MB = 0.1;
    private final com.jeladastudios.ftsgeology.gas.GasTank gas = new com.jeladastudios.ftsgeology.gas.GasTank(0.5, 20.0).onChange(this::setChanged);
    private final com.jeladastudios.ftsgeology.gas.IGasHandler gasPort = side -> side == Direction.DOWN ? null : gas;
    private final LazyOptional<com.jeladastudios.ftsgeology.gas.IGasHandler> gasCap = LazyOptional.of(() -> gasPort);

    private int well, nextSurvey;
    @Nullable
    private PetroleumFields.Field field;
    private PetroleumFields.Zone zone = PetroleumFields.Zone.NONE;
    private double owed, rate;

    public WellheadBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WELLHEAD.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WellheadBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (--be.nextSurvey <= 0) {
            be.nextSurvey = SURVEY_EVERY;
            be.survey(server);
        }
        be.flow(server);
        be.push(level, pos);
        if (server.getGameTime() % 20 == 0) be.ventGas(server, pos);
    }

    /** Follows the casing down to its foot, and reads what the foot is in. */
    private void survey(ServerLevel level) {
        BlockPos.MutableBlockPos at = worldPosition.mutable();
        int depth = 0;
        while (depth < MOST_DEPTH) {
            at.move(Direction.DOWN);
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, at) || !level.getBlockState(at).is(ModBlocks.WELL_CASING.get())) break;
            depth++;
        }
        well = depth;
        field = null;
        zone = PetroleumFields.Zone.NONE;
        if (depth == 0) return;
        // The foot's own length of casing, or the rock under it: whichever is in the reservoir.
        for (BlockPos p : new BlockPos[]{worldPosition.below(depth), worldPosition.below(depth + 1)}) {
            PetroleumFields.At a = PetroleumFields.at(level, p);
            if (a == null || a.zone() == PetroleumFields.Zone.SEAL) continue;
            field = a.field();
            zone = a.zone();
            break;
        }
    }

    private void flow(ServerLevel level) {
        rate = 0;
        if (field == null || zone != PetroleumFields.Zone.OIL) return;
        OilReserves store = OilReserves.of(level);
        double left = store.left(field);
        if (left <= SPENT) return;
        rate = RATE * field.richness() * Math.sqrt(left);
        owed = Math.min(owed + rate, rate * 2 + 1);
        int want = Math.min(TANK - tank.getFluidAmount(), (int) owed);
        if (want <= 0) return;
        tank.fill(new FluidStack(ModFluids.CRUDE_OIL.get(), want), IFluidHandler.FluidAction.EXECUTE);
        owed -= want;
        store.take(field, want);
        if (com.jeladastudios.ftsgeology.gas.GasConfig.ENABLED.get()) {
            double mol = want * GAS_PER_MB, sour = Math.max(0, Math.min(0.2, field.sour()));
            com.jeladastudios.ftsgeology.gas.GasMix g = new com.jeladastudios.ftsgeology.gas.GasMix();
            g.add(com.jeladastudios.ftsgeology.gas.Gas.METHANE, mol * (0.90 - sour));
            g.add(com.jeladastudios.ftsgeology.gas.Gas.CARBON_DIOXIDE, mol * 0.06);
            g.add(com.jeladastudios.ftsgeology.gas.Gas.NITROGEN, mol * 0.04);
            g.add(com.jeladastudios.ftsgeology.gas.Gas.HYDROGEN_SULFIDE, mol * sour);
            gas.insert(g);
        }
        setChanged();
    }

    /**
     * The associated gas: into a gas pipe on a side where there is one (the pipes draw it themselves), else let off over
     * the top once it is a little over the air's pressure.
     */
    private void ventGas(ServerLevel level, BlockPos pos) {
        if (gas.gas.isEmpty()) return;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            var h = com.jeladastudios.ftsgeology.gas.registry.GasCapabilities.handlerAt(level, pos.relative(d), d.getOpposite());
            if (h != null && h.getTank(d.getOpposite()) != null) return;
        }
        double excess = gas.total() - gas.molesAt(1.2);
        if (excess <= 1e-4) return;
        var gm = com.jeladastudios.ftsgeology.gas.world.GasManager.getIfPresent(level);
        if (gm != null && !gm.isGasTight(level.getBlockState(pos.above()), pos.above())) gm.release(pos.above(), gas.extract(excess));
    }

    /** Hands the oil to whatever takes it on its four sides and its top; its foot is the well. */
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

    /** A bucketful out of the tank, if there is one. */
    public boolean takeBucket() {
        if (tank.getFluidAmount() < 1000) return false;
        tank.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        setChanged();
        return true;
    }

    /** What a right-click reads off it. */
    public List<Component> report() {
        List<Component> out = new ArrayList<>();
        if (well == 0) {
            out.add(Component.translatable("message.fts_geology.wellhead.no_well"));
        } else if (field == null) {
            out.add(Component.translatable("message.fts_geology.wellhead.no_field", well));
        } else {
            out.add(Component.translatable("message.fts_geology.wellhead.zone." + zone.name().toLowerCase(Locale.ROOT), well));
            if (zone == PetroleumFields.Zone.OIL && level instanceof ServerLevel server) {
                double left = OilReserves.of(server).left(field);
                out.add(Component.translatable(left <= SPENT ? "message.fts_geology.wellhead.spent" : "message.fts_geology.wellhead.flow",
                        String.format(Locale.ROOT, "%.0f", rate * 20), String.format(Locale.ROOT, "%.0f", left * 100),
                        String.format(Locale.ROOT, "%.0f", OilReserves.recoverable(field) / 1000.0)));
            }
        }
        out.add(Component.translatable("message.fts_geology.wellhead.tank", tank.getFluidAmount(), TANK));
        if (!gas.gas.isEmpty()) {
            out.add(Component.translatable("message.fts_geology.wellhead.gas", String.format(Locale.ROOT, "%.2f", gas.pressure())));
        }
        return out;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        nextSurvey = 1;
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.FLUID_HANDLER && side != Direction.DOWN) return oil.cast();
        if (cap == com.jeladastudios.ftsgeology.gas.registry.GasCapabilities.GAS_HANDLER && side != Direction.DOWN) return gasCap.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        oil.invalidate();
        gasCap.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Tank", tank.writeToNBT(new CompoundTag()));
        tag.put("Gas", gas.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        tank.readFromNBT(tag.getCompound("Tank"));
        gas.load(tag.getCompound("Gas"));
    }
}
