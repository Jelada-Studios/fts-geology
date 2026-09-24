package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.block.GeothermalTurbineBlock;
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
 * A geothermal turbine: steam from the ground under it turns it, and it pushes Forge Energy into whatever is beside
 * it. What it makes is read off the ground, the way a real plant is sited on a well: it has to stand over an outlet
 * of the mod's own -- a hot spring's bed, a steam vent, a mud pot, a geyser's plumbing -- and the heat under that
 * outlet (magma, lava, a geyser's chamber) sets how hard it runs. Turbines close together draw on one reservoir and
 * share it, so a field of them is little better than one well placed; and the same outlet away from the plate
 * boundaries and plumes that make geothermal ground runs weaker, since there is less heat coming up to it.
 */
public class GeothermalTurbineBlockEntity extends BlockEntity {

    /** How far down under the turbine, and how far to either side, heat and outlets are looked for. */
    private static final int DEPTH = 24, SIDE = 1;
    /** Heat that runs a turbine flat out: four magma blocks' worth, or a geyser's chamber and a bit. */
    private static final double HEAT_FULL = 4.0;
    /** How far apart two turbines have to stand before they stop sharing a reservoir. */
    private static final int SHARE_RADIUS = 8;
    /** Ticks between two readings of the ground: the reservoir does not change from one tick to the next. */
    private static final int SURVEY_EVERY = 100;
    private static final int CAPACITY = 50_000, PUSH_PER_TICK = 2_000;

    /** Where the turbines are, per dimension, for sharing. Filled as they load, emptied as they go. */
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

    /** What the ground under it gives, before sharing, and what it made on the last tick after sharing. */
    private double heat;
    private boolean outlet;
    private double region = 1.0;
    private int perTick;
    private int sharing = 1;
    private int nextSurvey;

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
        boolean running = be.perTick > 0;
        if (state.getValue(GeothermalTurbineBlock.RUNNING) != running) {
            level.setBlock(pos, state.setValue(GeothermalTurbineBlock.RUNNING, running), Block.UPDATE_CLIENTS);
        }
    }

    /** Reads the heat and the outlets under the turbine, and how many turbines share them. */
    private void survey(ServerLevel level) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        double h = 0;
        boolean found = false;
        for (int dx = -SIDE; dx <= SIDE; dx++) {
            for (int dz = -SIDE; dz <= SIDE; dz++) {
                for (int dy = (dx == 0 && dz == 0) ? -1 : 0; dy >= -DEPTH; dy--) {
                    at.set(worldPosition.getX() + dx, worldPosition.getY() + dy, worldPosition.getZ() + dz);
                    if (!level.isLoaded(at)) continue;
                    BlockState s = level.getBlockState(at);
                    if (isOutlet(s)) found = true;
                    // The heat a column carries fades with depth: steam from far down has cooled on its way up.
                    double near = 1.0 - 0.5 * (-dy) / (double) DEPTH;
                    h += heatOf(s) * near;
                }
            }
        }
        heat = h;
        outlet = found;
        var fit = GeothermalSuitability.at(level, worldPosition.getX(), worldPosition.getZ());
        region = 0.35 + 0.65 * Math.max(fit.hotSpring(), fit.geyser());
        sharing = neighbours(level);
        int max = GeyserConfig.TURBINE_MAX_FE.get(), min = Math.min(max, GeyserConfig.TURBINE_MIN_FE.get());
        int full = 0;
        if (outlet && heat > 0) {
            double t = Math.min(1.0, heat / HEAT_FULL);
            full = (int) Math.round((min + (max - min) * t) * region);
        }
        int now = full / Math.max(1, sharing);
        if (now != perTick) setChanged();
        perTick = now;
    }

    /** One of the mod's own ways up for hot water and steam: what a turbine has to stand over. */
    private static boolean isOutlet(BlockState s) {
        return s.is(ModBlocks.HOT_SPRING.get()) || s.is(ModBlocks.STEAM_VENT.get()) || s.is(ModBlocks.MUD_POT.get())
                || s.is(ModBlocks.GEYSER_CORE.get()) || s.is(ModBlocks.GEYSER_CHAMBER.get())
                || s.is(ModBlocks.SPRING_SOURCE.get()) || s.is(ModBlocks.VOLCANO_CORE.get());
    }

    /** How much heat one block under the turbine gives. */
    private static double heatOf(BlockState s) {
        if (s.is(Blocks.MAGMA_BLOCK)) return 1.0;
        if (s.is(ModBlocks.GEYSER_CHAMBER.get()) || s.is(ModBlocks.GEYSER_CORE.get())
                || s.is(ModBlocks.VOLCANO_CORE.get())) return 3.0;
        if (s.is(ModBlocks.HOT_SPRING.get()) || s.is(ModBlocks.SPRING_SOURCE.get())
                || s.is(ModBlocks.STEAM_VENT.get())) return 1.0;
        if (s.is(ModBlocks.MUD_POT.get())) return 0.5;
        FluidState f = s.getFluidState();
        if (f.is(FluidTags.LAVA)) return f.isSource() ? 1.0 : 0.5;
        return 0.0;
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

    /** Hands stored energy to whatever beside it takes it. */
    private void push(Level level, BlockPos pos) {
        if (energy.getEnergyStored() <= 0) return;
        for (Direction d : Direction.values()) {
            BlockEntity other = level.getBlockEntity(pos.relative(d));
            if (other == null) continue;
            IEnergyStorage into = other.getCapability(ForgeCapabilities.ENERGY, d.getOpposite()).orElse(null);
            if (into == null || !into.canReceive()) continue;
            int offered = energy.extractEnergy(PUSH_PER_TICK, true);
            int taken = into.receiveEnergy(offered, false);
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
        if (!outlet) {
            out.add(Component.translatable("message.fts_geology.turbine.no_outlet"));
        } else if (heat <= 0) {
            out.add(Component.translatable("message.fts_geology.turbine.no_heat"));
        }
        out.add(Component.translatable("message.fts_geology.turbine.output", perTick,
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
        synchronized (PLACED) {
            PLACED.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(worldPosition.asLong());
        }
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
        if (cap == ForgeCapabilities.ENERGY) return handle.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        handle.invalidate();
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
