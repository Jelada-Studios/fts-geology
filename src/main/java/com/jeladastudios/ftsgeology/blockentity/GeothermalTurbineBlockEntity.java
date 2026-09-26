package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.block.GeothermalTurbineBlock;
import com.jeladastudios.ftsgeology.compat.ElectrodynamicsPower;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A geothermal turbine: steam from a well under it turns it, and it pushes Forge Energy into whatever is beside it or
 * on top of it. What it makes is read off the ground, the way a real plant is sited: it stands on a string of
 * {@link com.jeladastudios.ftsgeology.block.WellCasingBlock well casing}, and what counts is where the string ends.
 * The well has to reach a hot water reservoir of the mod's own -- a hot spring's bed, a spring's or a geyser's deep
 * chamber, a steam vent, a mud pot -- and the heat round its foot (the reservoir's magma bed above all) sets how hard
 * the turbine runs. A short well beside a pool reaches the pool's own bed and runs part way; a deep one bored down to
 * the chamber a spring or a geyser rises from runs flat out. Lava poured down a hole is not a reservoir: there is no
 * water there to make steam of. A geyser's own vent is a well already: a turbine set on it caps the geyser, which
 * stops erupting and sends its steam through the turbine instead, flat out, with no casing needed. Turbines close together draw on one reservoir and share it, and the same well away
 * from the plate boundaries and plumes that make geothermal ground runs weaker.
 */
public class GeothermalTurbineBlockEntity extends BlockEntity {

    /** How far round a well's foot the reservoir is read, either way. */
    private static final int FOOT = 2;
    /** Heat that runs a turbine flat out: a reservoir's magma bed, or a pool's beds and more. */
    private static final double HEAT_FULL = 8.0;
    /** How far apart two turbines have to stand before they stop sharing a reservoir. */
    private static final int SHARE_RADIUS = 8;
    /** Ticks between two readings of the ground: the reservoir does not change from one tick to the next. */
    private static final int SURVEY_EVERY = 100;
    private static final int CAPACITY = 50_000, PUSH_PER_TICK = 2_000;
    /** How fast the rotor turns at each step of power, in degrees a tick, and how quickly it gets there. */
    private static final float SPIN_PER_POWER = 6.0f, SPIN_UP = 0.04f;

    /**
     * Where the turbines drawing on a reservoir are, per dimension, for sharing: one stood beside a well with no
     * well of its own draws nothing and takes nothing from it. Kept as each reads its ground, emptied as they go.
     */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> PLACED = new HashMap<>();

    /** Only ever filled from inside: nothing else can put energy into a turbine. */
    private static final class Buffer extends EnergyStorage {
        Buffer() {
            super(CAPACITY, 0, PUSH_PER_TICK);
        }

        void set(int amount) {
            energy = Math.max(0, Math.min(capacity, amount));
        }
    }

    private final Buffer energy = new Buffer();
    private final LazyOptional<IEnergyStorage> handle = LazyOptional.of(() -> energy);
    /** The same store as Electrodynamics' electricity, where it is installed: its wires take nothing else. */
    private final LazyOptional<Object> volts = ElectrodynamicsPower.generator(energy::getEnergyStored, () -> CAPACITY,
            energy::set, this::setChanged);

    /** What the ground under it gives, before sharing, and what it made on the last tick after sharing. */
    private int well;
    private double heat;
    private boolean reservoir;
    /** Whether it stands capping a geyser's vent rather than on a well of its own. */
    private boolean onGeyser;
    private double region = 1.0;
    private int perTick;
    private int sharing = 1;
    private int nextSurvey;

    /** The rotor, on the client only: its angle now and a tick ago, and how fast it is turning. */
    private float spin, spinO, speed;

    public GeothermalTurbineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GEOTHERMAL_TURBINE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GeothermalTurbineBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (--be.nextSurvey <= 0) {
            be.nextSurvey = SURVEY_EVERY;
            be.survey(server);
        }
        int made = Math.min(be.perTick, CAPACITY - be.energy.getEnergyStored());
        if (made > 0) {
            be.energy.set(be.energy.getEnergyStored() + made);
            be.setChanged();
        }
        be.push(level, pos);
        int power = be.power();
        if (state.getValue(GeothermalTurbineBlock.POWER) != power) {
            level.setBlock(pos, state.setValue(GeothermalTurbineBlock.POWER, power), Block.UPDATE_CLIENTS);
        }
    }

    /** Turns the rotor: it spins up to the speed its power sets over a couple of seconds, and coasts down to rest. */
    public static void clientTick(Level level, BlockPos pos, BlockState state, GeothermalTurbineBlockEntity be) {
        float target = state.getValue(GeothermalTurbineBlock.POWER) * SPIN_PER_POWER;
        be.speed += (target - be.speed) * SPIN_UP;
        if (target == 0 && be.speed < 0.05f) be.speed = 0;
        be.spinO = be.spin;
        be.spin += be.speed;
        if (be.spin >= 360f) {
            be.spin -= 360f;
            be.spinO -= 360f;
        }
    }

    /** The rotor's angle between the last tick and this one, in degrees, for the renderer. */
    public float spin(float partialTick) {
        return Mth.lerp(partialTick, spinO, spin);
    }

    /** The step of power the block state shows: 0 stood still, 1 to 3 by thirds of what one turbine can make. */
    private int power() {
        if (perTick <= 0) return 0;
        double f = perTick / (double) Math.max(1, GeyserConfig.TURBINE_MAX_FE.get());
        return f < 1.0 / 3.0 ? 1 : f < 2.0 / 3.0 ? 2 : 3;
    }

    /** Follows the well down to its foot, reads the reservoir there, and how many turbines share it. */
    private void survey(ServerLevel level) {
        BlockPos.MutableBlockPos at = worldPosition.mutable();
        int depth = 0;
        int most = GeyserConfig.TURBINE_WELL_DEPTH.get();
        while (depth < most) {
            at.move(Direction.DOWN);
            if (!level.isLoaded(at) || !level.getBlockState(at).is(ModBlocks.WELL_CASING.get())) break;
            depth++;
        }
        well = depth;
        double h = 0;
        boolean found = false;
        GeyserCoreBlockEntity geyser = depth == 0 ? GeyserCoreBlockEntity.under(level, worldPosition) : null;
        onGeyser = geyser != null;
        if (onGeyser) {
            // The geyser's vent is the well, bored to its chamber on its magma bed: all the heat a turbine can use.
            well = worldPosition.getY() - geyser.getBlockPos().getY();
            h = HEAT_FULL;
            found = true;
        }
        if (depth > 0) {
            // Round the open foot of the string: the block under its last length, and two either way.
            int fx = worldPosition.getX(), fy = worldPosition.getY() - depth - 1, fz = worldPosition.getZ();
            for (int dx = -FOOT; dx <= FOOT; dx++) {
                for (int dy = -FOOT; dy <= FOOT; dy++) {
                    for (int dz = -FOOT; dz <= FOOT; dz++) {
                        at.set(fx + dx, fy + dy, fz + dz);
                        if (!level.isLoaded(at)) continue;
                        BlockState s = level.getBlockState(at);
                        if (isReservoir(s)) found = true;
                        h += heatOf(s);
                    }
                }
            }
        }
        heat = h;
        reservoir = found;
        drawing(level, reservoir && heat > 0);
        var fit = GeothermalSuitability.at(level, worldPosition.getX(), worldPosition.getZ());
        region = 0.35 + 0.65 * Math.max(fit.hotSpring(), fit.geyser());
        sharing = neighbours(level);
        int max = GeyserConfig.TURBINE_MAX_FE.get(), min = Math.min(max, GeyserConfig.TURBINE_MIN_FE.get());
        int full = 0;
        if (reservoir && heat > 0) {
            double t = Math.min(1.0, heat / HEAT_FULL);
            full = (int) Math.round((min + (max - min) * t) * region);
        }
        int now = full / Math.max(1, sharing);
        if (now != perTick) setChanged();
        perTick = now;
    }

    /** One of the mod's own hot water reservoirs or the ways up from them: what a well has to reach. */
    private static boolean isReservoir(BlockState s) {
        return s.is(ModBlocks.HOT_SPRING.get()) || s.is(ModBlocks.STEAM_VENT.get()) || s.is(ModBlocks.MUD_POT.get())
                || s.is(ModBlocks.GEYSER_CORE.get()) || s.is(ModBlocks.GEYSER_CHAMBER.get())
                || s.is(ModBlocks.SPRING_SOURCE.get()) || s.is(ModBlocks.VOLCANO_CORE.get());
    }

    /** How much heat one block round the well's foot gives. */
    private static double heatOf(BlockState s) {
        if (s.is(Blocks.MAGMA_BLOCK)) return 1.0;
        if (s.is(ModBlocks.GEYSER_CHAMBER.get()) || s.is(ModBlocks.GEYSER_CORE.get())
                || s.is(ModBlocks.SPRING_SOURCE.get()) || s.is(ModBlocks.VOLCANO_CORE.get())) return 3.0;
        if (s.is(ModBlocks.HOT_SPRING.get()) || s.is(ModBlocks.STEAM_VENT.get())) return 1.0;
        if (s.is(ModBlocks.MUD_POT.get())) return 0.5;
        FluidState f = s.getFluidState();
        if (f.is(FluidTags.LAVA)) return f.isSource() ? 1.0 : 0.5;
        return 0.0;
    }

    /** Whether a turbine drawing on the ground stands over this geyser core's vent, near where the vent opens. */
    public static boolean capsVent(ServerLevel level, BlockPos core, int mouthY) {
        synchronized (PLACED) {
            LongOpenHashSet all = PLACED.get(level.dimension());
            if (all == null) return false;
            for (long l : all) {
                BlockPos p = BlockPos.of(l);
                if (Math.abs(p.getX() - core.getX()) <= GeyserCoreBlockEntity.CAP_REACH
                        && Math.abs(p.getZ() - core.getZ()) <= GeyserCoreBlockEntity.CAP_REACH
                        && p.getY() >= mouthY - GeyserCoreBlockEntity.CAP_BELOW
                        && p.getY() <= mouthY + GeyserCoreBlockEntity.CAP_ABOVE) return true;
            }
        }
        return false;
    }

    /** Puts this turbine among those drawing on the ground, or takes it out. */
    private void drawing(ServerLevel level, boolean draws) {
        synchronized (PLACED) {
            LongOpenHashSet all = PLACED.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
            if (draws) all.add(worldPosition.asLong());
            else all.remove(worldPosition.asLong());
        }
    }

    private int neighbours(ServerLevel level) {
        int n = 0;
        int r2 = SHARE_RADIUS * SHARE_RADIUS;
        synchronized (PLACED) {
            LongOpenHashSet all = PLACED.get(level.dimension());
            if (all == null) return 1;
            for (long l : all) {
                BlockPos p = BlockPos.of(l);
                if (p.distSqr(worldPosition) <= r2) n++;
            }
        }
        return Math.max(1, n);
    }

    /** Hands stored energy to whatever takes it on its four sides and its top; its foot is the well. */
    private void push(Level level, BlockPos pos) {
        if (energy.getEnergyStored() <= 0) return;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            BlockEntity other = level.getBlockEntity(pos.relative(d));
            if (other == null) continue;
            int offered = energy.extractEnergy(PUSH_PER_TICK, true);
            // Electrodynamics' electricity where the block takes it -- a wire takes nothing else -- and Forge Energy where not.
            int taken = ElectrodynamicsPower.give(other, d.getOpposite(), offered);
            if (taken < 0) {
                IEnergyStorage into = other.getCapability(ForgeCapabilities.ENERGY, d.getOpposite()).orElse(null);
                if (into == null || !into.canReceive()) continue;
                taken = into.receiveEnergy(offered, false);
            }
            if (taken > 0) {
                energy.extractEnergy(taken, false);
                setChanged();
            }
            if (energy.getEnergyStored() <= 0) return;
        }
    }

    /** What a right-click reads off it. */
    public List<Component> report() {
        List<Component> out = new ArrayList<>();
        if (onGeyser) {
            out.add(Component.translatable("message.fts_geology.turbine.geyser"));
        } else if (well == 0) {
            out.add(Component.translatable("message.fts_geology.turbine.no_well"));
        } else if (!reservoir) {
            out.add(Component.translatable("message.fts_geology.turbine.no_reservoir", well));
        } else if (heat <= 0) {
            out.add(Component.translatable("message.fts_geology.turbine.no_heat"));
        }
        out.add(Component.translatable("message.fts_geology.turbine.output", perTick, well,
                String.format(java.util.Locale.ROOT, "%.1f", heat), sharing));
        out.add(Component.translatable("message.fts_geology.turbine.stored", energy.getEnergyStored(), CAPACITY));
        return out;
    }

    public int perTick() {
        return perTick;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        nextSurvey = 1;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        forget();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        forget();
    }

    private void forget() {
        if (level == null || level.isClientSide) return;
        synchronized (PLACED) {
            LongOpenHashSet all = PLACED.get(level.dimension());
            if (all != null) all.remove(worldPosition.asLong());
        }
    }

    /** Forgets every turbine: the server is going down, and the next may be another world. */
    public static void clearAll() {
        synchronized (PLACED) {
            PLACED.clear();
        }
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY && side != Direction.DOWN) return handle.cast();
        if (ElectrodynamicsPower.is(cap) && side != Direction.DOWN) return volts.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        handle.invalidate();
        volts.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Energy", energy.getEnergyStored());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energy.set(tag.getInt("Energy"));
    }
}
