package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.GasConfig;
import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.registry.GasTags;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Per-dimension gas simulation. Each open block is a 1 m\u00B3 cell; only cells that differ from
 * normal air are stored. Every sweep, neighbouring cells exchange gas by
 * <ul>
 *   <li>bulk flow driven by pressure differences,</li>
 *   <li>diffusion/turbulent mixing of every species,</li>
 *   <li>buoyancy: a lighter mixture below a heavier one swaps upward (CH\u2084, H\u2082 rise; CO\u2082, H\u2082S sink),</li>
 * </ul>
 * plus wind venting under open sky and water-vapour condensation.
 */
public class GasManager {
    private static final Map<ServerLevel, GasManager> MANAGERS = new IdentityHashMap<>();
    static final Direction[] DIRS = Direction.values();

    public static final double N0 = Gas.MOL_PER_BLOCK;
    /** A virtual air neighbour becomes a real cell once it deviates by 2\u00D7 the trace levels... */
    private static final double MATERIALIZE_SCALE = 2.0;
    /** ...and a cell is dropped again once it is back within them. */
    private static final double DEACTIVATE_SCALE = 1.0;
    /** Cells richer than 3\u00D7 the trace levels always spread into their empty neighbours. */
    private static final double SOURCE_SCALE = 3.0;
    private static final double SATURATION_H2O = 0.023;
    /** Normal air; shared, never modify. */
    public static final GasMix AMBIENT = GasMix.air(N0);

    /** Simulate everywhere, not only near players (GameTests run without players). */
    public static boolean forceSimulation;

    public static GasManager get(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level, GasManager::new);
    }

    @Nullable
    public static GasManager getIfPresent(Level level) {
        return level instanceof ServerLevel sl ? get(sl) : null;
    }

    public static void unload(ServerLevel level) {
        GasManager m = MANAGERS.remove(level);
        if (m != null && m.ticks > 0) {
            com.jeladastudios.ftsgeology.util.Diagnostics.info("gases in {}: {} cells, {} awake at the last sweep; {} ms a tick on average over {} ticks, the worst {} ms; {} firedamp pockets ({} cells), {} flames snuffed",
                    level.dimension().location(), m.activeCellCount(), m.awakeCount,
                    String.format(java.util.Locale.ROOT, "%.3f", m.nanos / 1e6 / m.ticks), m.ticks,
                    String.format(java.util.Locale.ROOT, "%.2f", m.worstNanos / 1e6), GasWorldGen.pockets, GasWorldGen.pocketCells, m.snuffed);
        }
    }

    public static void clearAll() {
        MANAGERS.clear();
    }

    public final ServerLevel level;
    private final Long2ObjectOpenHashMap<GasChunkData> chunks = new Long2ObjectOpenHashMap<>();
    private final List<Deflagration> deflagrations = new ArrayList<>();
    private final List<Deflagration> newDeflagrations = new ArrayList<>();
    private final ArrayDeque<GasChunkData> seedQueue = new ArrayDeque<>();
    private final List<PendingRelease> pending = new ArrayList<>();
    private final List<ScheduledIgnition> scheduledIgnitions = new ArrayList<>();
    private final LongArrayList sweep = new LongArrayList();
    private int sweepIndex;
    private long lastSweepStart = -1000;
    private boolean reverseSweep;

    public int lastSweepSize;
    public long sweepCount;
    public double lastTickMs;
    public long ticks, nanos, worstNanos;

    private final GasMix scratch = new GasMix();
    private final BlockPos.MutableBlockPos cpos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos npos = new BlockPos.MutableBlockPos();
    private final BlockState[] nStates = new BlockState[6];
    private final GasChunkData[] nData = new GasChunkData[6];
    private double kD, kP, kB, kSky, kCond, kRock;
    private boolean allowGrowth = true;
    private final double[] drift = new double[Gas.COUNT];
    /** Gas fraction at which buoyant drift reaches full speed. */
    private static final double DRIFT_SATURATION = 4.0;
    /**
     * Sweeps in a row a cell may hardly change before it sleeps, and how little is hardly: a ten-thousandth of what of
     * each gas it holds, and never less than a five-hundredth of that gas's trace level (a hundred-thousandth of a
     * cell's air for oxygen and nitrogen) -- so a slow settling, CO2 still sinking out of a thin mix, keeps it awake till
     * it is done. A settled pool of CO2 on a cave floor, firedamp under a roof, sleep; release, extraction, fire, a block
     * changing beside them, or a neighbour moving gas into them, wake them, and every sleeping cell near a player is
     * looked at again every {@link #AUDIT} sweeps in turn, for what changed without a word (a torch set by a command).
     */
    private static final int QUIET_SWEEPS = 16, AUDIT = 64;
    private static final double QUIET_SHARE = 0.002, QUIET_AIR = 1.0e-5, QUIET_REL = 1.0e-4;
    /** The most of the mod's tick budget the sweeps take (see TickBudget). */
    private static final double BUDGET_SHARE = 0.3;
    private final double[] before = new double[Gas.COUNT];
    private final double[] beforeN = new double[Gas.COUNT];
    private static final double[] QUIET_MOLES = new double[Gas.COUNT];
    static {
        for (int i = 0; i < Gas.COUNT; i++) {
            QUIET_MOLES[i] = (i == Gas.O2 || i == Gas.N2) ? QUIET_AIR * N0 : Math.max(1.0e-9, QUIET_SHARE * Gas.VALUES[i].trace * N0);
        }
    }
    public int awakeCount;
    final RandomSource random = RandomSource.create();

    private GasManager(ServerLevel level) {
        this.level = level;
    }

    // ------------------------------------------------------------------ chunk lifecycle

    public void onChunkLoad(GasChunkData data) {
        chunks.put(data.chunk.getPos().toLong(), data);
        if (!data.seeded) seedQueue.add(data);
    }

    public void onChunkUnload(LevelChunk chunk) {
        chunks.remove(chunk.getPos().toLong());
    }

    // ------------------------------------------------------------------ cell access

    @Nullable
    GasChunkData data(int x, int z) {
        return chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
    }

    public boolean isLoaded(BlockPos p) {
        return data(p.getX(), p.getZ()) != null && !level.isOutsideBuildHeight(p);
    }

    @Nullable
    public GasMix getCell(BlockPos p) {
        GasChunkData d = data(p.getX(), p.getZ());
        return d == null ? null : d.cells.get(GasChunkData.key(p.getX(), p.getY(), p.getZ()));
    }

    /** The gas at a position: its cell, or ambient air. Do not modify the result. */
    public GasMix sample(BlockPos p) {
        GasMix c = getCell(p);
        return c == null ? AMBIENT : c;
    }

    /** Existing or new (air-filled) cell, or {@code null} if unloaded or solid. */
    @Nullable
    public GasMix getOrCreateCell(BlockPos p) {
        if (level.isOutsideBuildHeight(p)) return null;
        GasChunkData d = data(p.getX(), p.getZ());
        if (d == null) return null;
        int key = GasChunkData.key(p.getX(), p.getY(), p.getZ());
        GasMix c = d.cells.get(key);
        if (c != null) {
            wake(d, key, c);                 // whoever asked is about to change it
            return c;
        }
        if (isGasTight(d.chunk.getBlockState(p), p)) return null;
        c = GasMix.air(N0);
        d.cells.put(key, c);
        wake(d, key, c);
        d.markDirty();
        return c;
    }

    private static void wake(GasChunkData d, int key, GasMix c) {
        c.quiet = 0;
        d.awake.add(key);
    }

    /** A cell changed from outside the sweeps (a fire, a machine): saved, and simulated again. */
    public void markDirty(BlockPos p) {
        GasChunkData d = data(p.getX(), p.getZ());
        if (d == null) return;
        d.markDirty();
        int key = GasChunkData.key(p.getX(), p.getY(), p.getZ());
        GasMix c = d.cells.get(key);
        if (c != null) wake(d, key, c);
    }

    /** Whether the gas at a column is simulated now: within the simulation radius of a player. */
    public boolean simulated(int x, int z) {
        if (forceSimulation) return true;
        int r = GasConfig.SIM_RADIUS_CHUNKS.get(), cx = x >> 4, cz = z >> 4;
        for (ServerPlayer p : level.players()) {
            if (Math.abs(p.chunkPosition().x - cx) <= r && Math.abs(p.chunkPosition().z - cz) <= r) return true;
        }
        return false;
    }

    /** A block changed here: the gas at it and beside it has somewhere new to go, or less room. */
    public void wakeAround(BlockPos p) {
        for (int i = -1; i < 6; i++) {
            int x = p.getX(), y = p.getY(), z = p.getZ();
            if (i >= 0) {
                x += DIRS[i].getStepX();
                y += DIRS[i].getStepY();
                z += DIRS[i].getStepZ();
            }
            GasChunkData d = data(x, z);
            if (d == null || d.cells.isEmpty()) continue;
            int key = GasChunkData.key(x, y, z);
            GasMix c = d.cells.get(key);
            if (c != null) wake(d, key, c);
        }
    }

    public void removeCell(BlockPos p) {
        GasChunkData d = data(p.getX(), p.getZ());
        if (d == null) return;
        int key = GasChunkData.key(p.getX(), p.getY(), p.getZ());
        d.awake.remove(key);
        if (d.cells.remove(key) != null) d.markDirty();
    }

    /**
     * Releases gas into the world at {@code p}. If that block is solid the gas goes to the
     * first open neighbour. Returns false if it could not be placed anywhere.
     */
    public boolean release(BlockPos p, GasMix mix) {
        GasMix c = getOrCreateCell(p);
        if (c == null) {
            for (Direction d : DIRS) {
                c = getOrCreateCell(p.relative(d));
                if (c != null) break;
            }
        }
        if (c == null) return false;
        c.add(mix);
        mix.clear();
        markDirty(p);
        return true;
    }

    /** Draws {@code moles} of whatever gas is at {@code p} (creating a pressure deficit). */
    public GasMix extract(BlockPos p, double moles) {
        GasMix c = getOrCreateCell(p);
        if (c == null) return new GasMix();
        GasMix out = c.take(Math.min(moles, c.total() * 0.9));
        markDirty(p);
        return out;
    }

    public void releaseLater(BlockPos p, GasMix mix, int delay) {
        pending.add(new PendingRelease(p.immutable(), mix, delay));
    }

    public int activeCellCount() {
        int n = 0;
        for (GasChunkData d : chunks.values()) n += d.cells.size();
        return n;
    }

    public int loadedChunkCount() {
        return chunks.size();
    }

    public int deflagrationCount() {
        return deflagrations.size();
    }

    public void forEachCellNear(BlockPos center, int radius, BiConsumer<BlockPos, GasMix> consumer) {
        int r2 = radius * radius;
        int minCx = (center.getX() - radius) >> 4, maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4, maxCz = (center.getZ() + radius) >> 4;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                GasChunkData d = chunks.get(ChunkPos.asLong(cx, cz));
                if (d == null || d.cells.isEmpty()) continue;
                for (Int2ObjectMap.Entry<GasMix> e : d.cells.int2ObjectEntrySet()) {
                    int k = e.getIntKey();
                    p.set((cx << 4) | GasChunkData.keyLocalX(k), GasChunkData.keyY(k), (cz << 4) | GasChunkData.keyLocalZ(k));
                    if (p.distSqr(center) <= r2) consumer.accept(p, e.getValue());
                }
            }
        }
    }

    public int clearNear(BlockPos center, int radius) {
        List<BlockPos> toRemove = new ArrayList<>();
        forEachCellNear(center, radius, (p, g) -> toRemove.add(p.immutable()));
        for (BlockPos p : toRemove) removeCell(p);
        return toRemove.size();
    }

    // ------------------------------------------------------------------ physics helpers

    /** A block that fills its whole cell so no gas can be inside it. */
    public boolean isGasTight(BlockState s, BlockPos p) {
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return true;
        if (s.is(GasTags.GAS_PERMEABLE)) return false;
        return s.isCollisionShapeFullBlock(level, p);
    }

    /** True if the block's shape seals its face towards {@code dir} (closed doors, trapdoors, slabs). */
    public boolean faceBlocked(BlockState s, BlockPos p, Direction dir) {
        if (s.isAir() || s.is(GasTags.GAS_PERMEABLE)) return false;
        VoxelShape shape = s.getCollisionShape(level, p);
        if (shape.isEmpty()) return false;
        return Block.isFaceFull(shape, dir);
    }

    private boolean canFlow(BlockState from, BlockPos fromPos, BlockState to, BlockPos toPos, Direction d) {
        if (isGasTight(to, toPos)) return false;
        if (faceBlocked(from, fromPos, d)) return false;
        return !faceBlocked(to, toPos, d.getOpposite());
    }

    /** Can gas move from {@code a} into its neighbour in direction {@code d}? */
    public boolean canFlow(BlockPos a, Direction d) {
        BlockPos b = a.relative(d);
        if (!isLoaded(a) || !isLoaded(b)) return false;
        return canFlow(level.getBlockState(a), a, level.getBlockState(b), b, d);
    }

    public boolean isIgnitionSource(BlockState s) {
        return isIgnitionSource(s, null);
    }

    /** @param side the face of the source block touching the gas (null = unknown) */
    public boolean isIgnitionSource(BlockState s, @Nullable Direction side) {
        if (s.isAir()) return false;
        if (s.getFluidState().is(FluidTags.LAVA)) return true;
        if (s.getBlock() instanceof BaseFireBlock) return true;
        if (s.getBlock() instanceof GasIgniter ig) return ig.ignitesGas(s, side);
        if (!GasConfig.TORCHES_IGNITE.get() || !s.is(GasTags.IGNITION_SOURCES)) return false;
        if (s.hasProperty(BlockStateProperties.LIT)) return s.getValue(BlockStateProperties.LIT);
        return true;
    }

    public boolean isSkyExposed(BlockPos p) {
        return p.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
    }

    // ------------------------------------------------------------------ ignition

    /** Starts a flame front at {@code p} if the gas there is flammable. */
    public boolean ignite(BlockPos p, @Nullable Entity cause) {
        GasMix c = getCell(p);
        if (c == null || !Combustion.isFlammable(c)) return false;
        long l = p.asLong();
        for (Deflagration d : deflagrations) {
            if (d.contains(l)) return false;
        }
        for (Deflagration d : newDeflagrations) {
            if (d.contains(l)) return false;
        }
        // Explosions fired while ticking can ignite more gas, so queue instead of mutating the list.
        newDeflagrations.add(new Deflagration(this, p.immutable(), cause));
        return true;
    }

    /** Ignites every flammable cell within {@code radius} of {@code center}. */
    public boolean igniteAround(BlockPos center, int radius, @Nullable Entity cause) {
        boolean any = false;
        List<BlockPos> hits = new ArrayList<>();
        forEachCellNear(center, radius, (p, g) -> {
            if (Combustion.isFlammable(g)) hits.add(p.immutable());
        });
        for (BlockPos p : hits) any |= ignite(p, cause);
        return any;
    }

    /**
     * Keeps trying to ignite around {@code center} for a while (e.g. a ruptured tank whose
     * contents first have to mix with air before they can burn).
     */
    public void igniteLater(BlockPos center, int radius, int delay, int attempts) {
        scheduledIgnitions.add(new ScheduledIgnition(center.immutable(), radius, delay, attempts));
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        long start = System.nanoTime();
        processPending();
        processScheduledIgnitions();
        seedChunks();
        if (!newDeflagrations.isEmpty()) {
            deflagrations.addAll(newDeflagrations);
            newDeflagrations.clear();
        }
        if (!deflagrations.isEmpty()) {
            deflagrations.removeIf(Deflagration::tick);
        }
        if (GasConfig.SWAMP_GAS.get() && level.getGameTime() % 40 == 0) swampGas();
        if (level.getGameTime() % 20 == 0 && level.dimension() == Level.OVERWORLD) GasFields.tick(this);
        simulate();
        long took = System.nanoTime() - start;
        lastTickMs = lastTickMs * 0.9 + took / 1e6 * 0.1;
        ticks++;
        nanos += took;
        worstNanos = Math.max(worstNanos, took);
    }

    private void processPending() {
        if (pending.isEmpty()) return;
        Iterator<PendingRelease> it = pending.iterator();
        while (it.hasNext()) {
            PendingRelease r = it.next();
            if (--r.delay <= 0) {
                release(r.pos, r.mix);
                it.remove();
            }
        }
    }

    private void processScheduledIgnitions() {
        if (scheduledIgnitions.isEmpty()) return;
        List<ScheduledIgnition> due = new ArrayList<>();
        scheduledIgnitions.removeIf(s -> {
            if (--s.delay > 0) return false;
            due.add(s);
            return true;
        });
        for (ScheduledIgnition s : due) {
            if (!igniteAround(s.center, s.radius, null) && --s.attempts > 0) {
                s.delay = 5;
                scheduledIgnitions.add(s);
            }
        }
    }

    private void seedChunks() {
        for (int i = 0; i < 8 && !seedQueue.isEmpty(); i++) {
            GasChunkData d = seedQueue.poll();
            if (d.seeded || chunks.get(d.chunk.getPos().toLong()) != d) continue;
            GasWorldGen.seed(this, d);
        }
    }

    private void swampGas() {
        for (ServerPlayer player : level.players()) {
            BlockPos base = player.blockPosition();
            if (!level.getBiome(base).is(Tags.Biomes.IS_SWAMP)) continue;
            for (int i = 0; i < 3; i++) {
                int x = base.getX() + random.nextInt(25) - 12;
                int z = base.getZ() + random.nextInt(25) - 12;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos surface = new BlockPos(x, y, z);
                if (!level.getFluidState(surface.below()).is(FluidTags.WATER) || !level.isEmptyBlock(surface)) continue;
                GasMix bubble = new GasMix();
                bubble.add(Gas.METHANE, 0.4 + random.nextDouble() * 0.9);
                bubble.add(Gas.CARBON_DIOXIDE, 0.15);
                bubble.add(Gas.HYDROGEN_SULFIDE, 0.0006);
                release(surface, bubble);
                level.sendParticles(ParticleTypes.BUBBLE_POP, x + 0.5, y + 0.05, z + 0.5, 4, 0.2, 0.0, 0.2, 0.01);
            }
        }
    }

    private void loadRates() {
        kD = GasConfig.DIFFUSION.get();
        kP = GasConfig.PRESSURE_FLOW.get();
        kB = GasConfig.BUOYANCY.get();
        kSky = GasConfig.SKY_VENT.get();
        kCond = GasConfig.CONDENSATION.get();
        double days = GasConfig.ROCK_EXCHANGE_DAYS.get();
        kRock = days <= 0 ? 0 : 1.0 / (24000.0 * days);
        for (Gas g : Gas.VALUES) {
            // Relative density vs air: CH\u2084 +0.45, H\u2082 +0.93, H\u2082O +0.38, CO \u2248 0, CO\u2082 \u22120.52, H\u2082S \u22120.18, SO\u2082 \u22121.
            double rel = (Gas.AIR_MOLAR_MASS - g.molarMass) / Gas.AIR_MOLAR_MASS;
            drift[g.ordinal()] = g.isAirComponent() ? 0 : kB * Math.max(-1, Math.min(1, rel));
        }
    }

    private void simulate() {
        if (sweepIndex >= sweep.size()) {
            long now = level.getGameTime();
            if (now - lastSweepStart < GasConfig.SWEEP_INTERVAL.get()) return;
            rebuildSweep();
            lastSweepStart = now;
            if (sweep.isEmpty()) return;
        }
        loadRates();
        // The cell count caps a tick, and so does the share of the mod's budget: whichever runs out first.
        com.jeladastudios.ftsgeology.util.TickBudget.open(level.getServer().getTickCount());
        long deadline = System.nanoTime() + com.jeladastudios.ftsgeology.util.TickBudget.slice(BUDGET_SHARE);
        int budget = GasConfig.CELL_BUDGET.get();
        int size = sweep.size();
        long now = level.getGameTime();
        while (budget-- > 0 && sweepIndex < size) {
            if ((budget & 63) == 0 && System.nanoTime() > deadline) break;
            int i = sweepIndex++;
            simulateCell(sweep.getLong(reverseSweep ? size - 1 - i : i), now);
        }
    }

    private void rebuildSweep() {
        sweep.clear();
        sweepIndex = 0;
        reverseSweep = !reverseSweep;
        sweepCount++;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty() && !forceSimulation) {
            lastSweepSize = 0;
            return;
        }
        int r = GasConfig.SIM_RADIUS_CHUNKS.get();
        for (Long2ObjectMap.Entry<GasChunkData> e : chunks.long2ObjectEntrySet()) {
            GasChunkData d = e.getValue();
            if (d.cells.isEmpty()) continue;
            int cx = ChunkPos.getX(e.getLongKey()), cz = ChunkPos.getZ(e.getLongKey());
            boolean near = forceSimulation;
            for (ServerPlayer p : players) {
                if (Math.abs(p.chunkPosition().x - cx) <= r && Math.abs(p.chunkPosition().z - cz) <= r) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;
            // The cells still settling, and in turn a few of the sleeping ones, looked at again: a sleeping cell is
            // woken by whatever stirs it, but not everything that changes a block says so.
            if (d.awake.size() < d.cells.size()) {
                for (Int2ObjectMap.Entry<GasMix> e2 : d.cells.int2ObjectEntrySet()) {
                    int k = e2.getIntKey();
                    if (((k * 0x9E3779B1 + sweepCount) & (AUDIT - 1)) != 0 || d.awake.contains(k)) continue;
                    d.awake.add(k);
                    e2.getValue().quiet = QUIET_SWEEPS - 1;
                }
            }
            for (int k : d.awake) {
                sweep.add(BlockPos.asLong((cx << 4) | GasChunkData.keyLocalX(k), GasChunkData.keyY(k), (cz << 4) | GasChunkData.keyLocalZ(k)));
            }
        }
        lastSweepSize = sweep.size();
        awakeCount = lastSweepSize;
        allowGrowth = activeCellCount() < GasConfig.MAX_CELLS.get();
    }

    private void simulateCell(long packed, long now) {
        int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
        GasChunkData cd = data(x, z);
        if (cd == null) return;
        int key = GasChunkData.key(x, y, z);
        GasMix cell = cd.cells.get(key);
        if (cell == null || !cd.awake.contains(key)) return;
        cpos.set(x, y, z);
        BlockState state = cd.chunk.getBlockState(cpos);

        if (isGasTight(state, cpos)) {
            evict(cd, key, cell);
            return;
        }
        System.arraycopy(cell.m, 0, before, 0, Gas.COUNT);
        boolean sky = y > cd.chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);

        // Air under ground breathes slowly through the rock: what is not air drifts back toward it, over days, so gas
        // with nothing feeding it does not linger for ever (a seam, a vent or a well that keeps it fed does).
        if (cell.simulatedAt == 0L) cell.simulatedAt = now;
        long dt = now - cell.simulatedAt;
        cell.simulatedAt = now;
        if (!sky && dt > 0 && kRock > 0) {
            double f = Math.exp(-dt * kRock), t = cell.total();
            for (int g = 0; g < Gas.COUNT; g++) {
                double air = g == Gas.O2 ? t * Gas.AIR_O2 : g == Gas.N2 ? t * Gas.AIR_N2 : 0.0;
                cell.m[g] = air + (cell.m[g] - air) * f;
            }
        }

        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        for (int i = 0; i < 6; i++) {
            Direction d = DIRS[i];
            int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
            nStates[i] = null;
            nData[i] = null;
            if (ny < minY || ny >= maxY) continue;
            GasChunkData nd = ((nx >> 4) == (x >> 4) && (nz >> 4) == (z >> 4)) ? cd : data(nx, nz);
            if (nd == null) continue;
            npos.set(nx, ny, nz);
            nStates[i] = nd.chunk.getBlockState(npos);
            nData[i] = nd;
        }

        for (int i = 0; i < 6; i++) {
            BlockState ns = nStates[i];
            if (ns == null) continue;
            Direction d = DIRS[i];
            int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
            GasChunkData nd = nData[i];
            int nkey = GasChunkData.key(nx, ny, nz);
            GasMix other = nd.cells.get(nkey);
            boolean positive = d.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            boolean asleep = other != null && !nd.awake.contains(nkey);
            // Between two awake cells the positive one handles the pair; a sleeping one is handled from here.
            if (!positive && other != null && !asleep) continue;
            npos.set(nx, ny, nz);
            if (!canFlow(state, cpos, ns, npos, d)) continue;
            boolean virtual = other == null;
            if (virtual && !allowGrowth) continue; // over the cell cap: gas stays where it is
            if (virtual) {
                other = scratch;
                other.setAir(N0);
            }
            if (asleep) System.arraycopy(other.m, 0, beforeN, 0, Gas.COUNT);
            if (d == Direction.UP) exchangeVertical(cell, other);
            else if (d == Direction.DOWN) exchangeVertical(other, cell);
            else exchange(cell, other);

            if (virtual) {
                if (!other.isNearAir(N0, MATERIALIZE_SCALE) || !cell.isNearAir(N0, SOURCE_SCALE)) {
                    GasMix born = other.copy();
                    born.simulatedAt = now;
                    nd.cells.put(nkey, born);
                    wake(nd, nkey, born);
                    nd.markDirty();
                } else {
                    // Only traces here: not worth a new cell. Keep the gas (so none is lost); the
                    // surrounding air has still evened out the pressure and oxygen.
                    for (int g = 0; g < Gas.COUNT; g++) {
                        if (g != Gas.N2 && g != Gas.O2) cell.m[g] += other.m[g];
                    }
                }
            } else {
                // A sleeping neighbour this cell moved gas into, or out of, wakes.
                if (asleep && changed(other.m, beforeN)) wake(nd, nkey, other);
                if (nd != cd) nd.markDirty();
            }
        }

        // Wind carries gas away under open sky, but only as far down an opening as the ground round it lets it: in a
        // shaft, a crack or a narrow pit, walled on its sides, the gas stands, and only its mouth is swept.
        if (kSky > 0 && sky) {
            int walls = 0;
            for (int i = 0; i < 6; i++) {
                Direction d = DIRS[i];
                if (d.getAxis() == Direction.Axis.Y || nData[i] == null) continue;
                int nx = x + d.getStepX(), nz = z + d.getStepZ();
                if (y < nData[i].chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, nx & 15, nz & 15)) walls++;
            }
            double k = kSky * (4 - walls) / 4.0;
            for (int g = 0; g < Gas.COUNT && k > 0; g++) {
                double target = g == Gas.O2 ? N0 * Gas.AIR_O2 : g == Gas.N2 ? N0 * Gas.AIR_N2 : 0;
                cell.m[g] += (target - cell.m[g]) * k;
            }
        }

        // Water vapour above saturation condenses (removes gas, a slight pressure drop).
        double h2o = cell.m[Gas.H2O];
        if (h2o > 0) {
            double excess = h2o - cell.total() * SATURATION_H2O;
            if (excess > 0) cell.m[Gas.H2O] -= excess * kCond;
        }

        // Saved only when it changed; asleep once it has hardly changed for a few sweeps.
        if (changed(cell.m, before)) {
            cell.quiet = 0;
            cd.markDirty();
        } else if (++cell.quiet >= QUIET_SWEEPS) {
            cd.awake.remove(key);
        }

        // An open flame in air short of oxygen goes out: blackdamp puts out a miner's candle (a lantern burns on).
        // Carbon dioxide takes more of a flame's heat than the nitrogen it stands in for, so it puts one out sooner:
        // about a fifth of it in the air does, where nitrogen needs the oxygen down to 15%.
        if (!state.isAir() && putsOutFlames(cell)
                && snuff(state, cpos)) return;

        if (Combustion.isFlammable(cell)) {
            boolean spark = isIgnitionSource(state);
            for (int i = 0; i < 6 && !spark; i++) {
                if (nStates[i] != null && isIgnitionSource(nStates[i], DIRS[i].getOpposite())) spark = true;
            }
            if (spark) {
                ignite(cpos, null);
                return;
            }
        }

        if (cell.isNearAir(N0, DEACTIVATE_SCALE)) deactivate(cd, key, cell);
    }

    /** The oxygen below which a candle, a torch or a fire goes out. */
    private static final double SNUFF_O2 = 0.15;
    /** How much more heat a mole of CO2 takes up in a flame than a mole of nitrogen, less one. */
    private static final double CO2_HEAT = 0.6;

    /** Whether air this short of oxygen, or this thick with carbon dioxide, puts out an open flame. */
    public static boolean putsOutFlames(GasMix cell) {
        return cell.fraction(Gas.O2) / (1.0 + CO2_HEAT * cell.fraction(Gas.CO2)) < SNUFF_O2;
    }

    /** Puts out a flame standing in this cell: a torch drops, a fire goes, a candle or campfire is snuffed. */
    private boolean snuff(BlockState s, BlockPos p) {
        net.minecraft.world.level.block.Block b = s.getBlock();
        boolean out = false;
        if (b instanceof net.minecraft.world.level.block.TorchBlock && !(b instanceof net.minecraft.world.level.block.RedstoneTorchBlock)) {
            level.destroyBlock(p.immutable(), true);
            out = true;
        } else if (b instanceof BaseFireBlock) {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            out = true;
        } else if (s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT)
                && (b instanceof net.minecraft.world.level.block.CampfireBlock || b instanceof net.minecraft.world.level.block.AbstractCandleBlock)) {
            if (b instanceof net.minecraft.world.level.block.CampfireBlock) net.minecraft.world.level.block.CampfireBlock.dowse(null, level, p, s);
            level.setBlock(p, s.setValue(BlockStateProperties.LIT, false), Block.UPDATE_ALL);
            out = true;
        }
        if (out) {
            snuffed++;
            level.sendParticles(ParticleTypes.SMOKE, p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
            level.playSound(null, p, net.minecraft.sounds.SoundEvents.CANDLE_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.8f);
        }
        return out;
    }

    public int snuffed;

    /** Whether a cell moved by more than a sliver of each gas's trace level since {@code was}. */
    private static boolean changed(double[] m, double[] was) {
        for (int g = 0; g < Gas.COUNT; g++) {
            if (Math.abs(m[g] - was[g]) > QUIET_MOLES[g] + QUIET_REL * Math.abs(was[g])) return true;
        }
        return false;
    }

    /**
     * Drops a cell that is back to (nearly) normal air. Its remaining traces are handed to an
     * active neighbour so that gas spreading thinly through a closed room is not lost; only a cell
     * with no active neighbours lets its traces disperse into the atmosphere.
     */
    private void deactivate(GasChunkData cd, int key, GasMix cell) {
        cd.cells.remove(key);
        cd.awake.remove(key);
        cd.markDirty();
        GasMix heir = null;
        for (int i = 0; i < 6 && heir == null; i++) {
            if (nStates[i] == null) continue;
            Direction d = DIRS[i];
            npos.set(cpos.getX() + d.getStepX(), cpos.getY() + d.getStepY(), cpos.getZ() + d.getStepZ());
            GasMix n = nData[i].cells.get(GasChunkData.key(npos.getX(), npos.getY(), npos.getZ()));
            if (n != null && canFlow(cd.chunk.getBlockState(cpos), cpos, nStates[i], npos, d)) {
                heir = n;
                nData[i].markDirty();
                wake(nData[i], GasChunkData.key(npos.getX(), npos.getY(), npos.getZ()), n);
            }
        }
        if (heir == null) return;
        for (int g = 0; g < Gas.COUNT; g++) {
            if (g != Gas.N2 && g != Gas.O2) heir.m[g] += cell.m[g];
        }
    }

    /** Removes every gas cell in this dimension. */
    public int clearAllCells() {
        int n = 0;
        for (GasChunkData d : chunks.values()) {
            n += d.cells.size();
            if (!d.cells.isEmpty()) {
                d.cells.clear();
                d.awake.clear();
                d.markDirty();
            }
        }
        sweep.clear();
        sweepIndex = 0;
        return n;
    }

    /** Gas trapped in a block that became solid is pushed to open neighbours. */
    private void evict(GasChunkData cd, int key, GasMix cell) {
        cd.cells.remove(key);
        cd.awake.remove(key);
        cd.markDirty();
        List<GasMix> open = new ArrayList<>(6);
        BlockPos base = cpos.immutable();
        for (Direction d : DIRS) {
            GasMix n = getOrCreateCell(base.relative(d));
            if (n != null) open.add(n);
        }
        if (open.isEmpty()) return;
        // Only the non-air part matters; the displaced air just raises neighbour pressure a bit.
        cell.scale(1.0 / open.size());
        for (GasMix n : open) n.add(cell);
    }

    private void exchange(GasMix a, GasMix b) {
        double ta = a.total(), tb = b.total();
        double flow = kP * (ta - tb);
        if (flow > 1e-9) a.moveTo(b, flow);
        else if (flow < -1e-9) b.moveTo(a, -flow);

        ta = a.total();
        tb = b.total();
        if (ta <= 1e-9 || tb <= 1e-9 || kD <= 0) return;
        double k = kD * 0.5 * (ta + tb);
        for (int i = 0; i < Gas.COUNT; i++) {
            double d = k * (a.m[i] / ta - b.m[i] / tb);
            if (d > 0) d = Math.min(d, a.m[i]);
            else d = -Math.min(-d, b.m[i]);
            a.m[i] -= d;
            b.m[i] += d;
        }
    }

    /**
     * Vertical exchange: normal mixing plus buoyant drift. Light species drift up out of the
     * lower cell, heavy ones sink out of the upper cell, and the displaced volume is made up by
     * air flowing the other way. The drift scales with how concentrated the gas is, so a dense
     * plume of methane races to the ceiling while a few ppm simply mix in \u2014 as in reality,
     * where only the density difference of a whole parcel makes it rise or sink.
     */
    private void exchangeVertical(GasMix lower, GasMix upper) {
        exchange(lower, upper);
        if (kB <= 0) return;
        double tl = lower.total(), tu = upper.total();
        if (tl <= 1e-9 || tu <= 1e-9) return;
        double netUp = 0;
        for (int i = 0; i < Gas.COUNT; i++) {
            double v = drift[i];
            if (v > 0) {
                double n = lower.m[i];
                double d = v * Math.min(1.0, n / tl * DRIFT_SATURATION) * n;
                lower.m[i] -= d;
                upper.m[i] += d;
                netUp += d;
            } else if (v < 0) {
                double n = upper.m[i];
                double d = -v * Math.min(1.0, n / tu * DRIFT_SATURATION) * n;
                upper.m[i] -= d;
                lower.m[i] += d;
                netUp -= d;
            }
        }
        // Air flows the other way to fill the space.
        if (netUp > 0) moveAir(upper, lower, netUp);
        else if (netUp < 0) moveAir(lower, upper, -netUp);
    }

    private static void moveAir(GasMix from, GasMix to, double moles) {
        double air = from.m[Gas.N2] + from.m[Gas.O2];
        if (air <= 1e-9) return;
        double f = Math.min(1.0, moles / air);
        double n2 = from.m[Gas.N2] * f, o2 = from.m[Gas.O2] * f;
        from.m[Gas.N2] -= n2;
        from.m[Gas.O2] -= o2;
        to.m[Gas.N2] += n2;
        to.m[Gas.O2] += o2;
    }

    private static final class ScheduledIgnition {
        final BlockPos center;
        final int radius;
        int delay, attempts;

        ScheduledIgnition(BlockPos center, int radius, int delay, int attempts) {
            this.center = center;
            this.radius = radius;
            this.delay = delay;
            this.attempts = attempts;
        }
    }

    private static final class PendingRelease {
        final BlockPos pos;
        final GasMix mix;
        int delay;

        PendingRelease(BlockPos pos, GasMix mix, int delay) {
            this.pos = pos;
            this.mix = mix;
            this.delay = delay;
        }
    }
}
