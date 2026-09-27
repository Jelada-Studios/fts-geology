package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.instrument.SeismicWave;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Wet sand losing its footing. Shaken hard enough, loose sand and silt under the water table stop behaving as ground:
 * the water in them takes the weight, the grains float apart, and what stood on them sinks while the water, and the
 * sand with it, is forced up through any crack -- sand boils, little volcanoes of wet sand along a river bank. Niigata
 * in 1964 lost whole blocks of flats that way; Christchurch in 2011 had its eastern suburbs covered in sand.
 *
 * <p>Here: in strongly shaken ground ({@link #ONSET} and up) that is loose -- sand, silt, gravel, soil, several blocks
 * of it -- and wet -- by a river, a lake or the sea, on a floodplain, or where the water table stands within a few
 * blocks of the surface -- sand boils open in the open ground; the columns of what players and villages built on it
 * settle a block into it, and not all together, so a house on half-sound ground cracks and leans; and whoever stands
 * on it is held fast for a few seconds.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Liquefaction {

    private Liquefaction() {}

    /** The least intensity that liquefies anything: about Mercalli VII. */
    static final double ONSET = 6.0;
    /** How many blocks down from the surface the loose ground is looked for, and how many of those must be loose. */
    private static final int DEPTH = 6, LOOSE = 3;
    /** How close to the surface the water table must stand, in blocks. */
    private static final int WET = 3;
    /** How far a built column is followed up, and the most columns one quake settles. */
    private static final int STACK = 48, MOST = 3000;
    /** How long a sand boil runs, in ticks. */
    private static final int BOIL = 240;

    private static final LongAdder CHUNKS = new LongAdder(), BOILS = new LongAdder(), SETTLED = new LongAdder(),
            HELD = new LongAdder();

    private record Job(ResourceKey<Level> dimension, BlockPos epicentre, List<QuakePlanner.TracePoint> trace,
                       double magnitude, double depthMetres, long startAt, Deque<ChunkPos> chunks, int[] settled) {}

    private enum Kind { BOIL, SETTLE, HOLD, DRAIN }

    /** Something due at a moment of the shaking: a boil opening, a column settling, people held, a boil drying. */
    private record Due(ResourceKey<Level> dimension, Kind kind, BlockPos pos, int extra, long at) {}

    private static final Deque<Job> JOBS = new ArrayDeque<>();
    private static final PriorityQueue<Due> DUE = new PriorityQueue<>(Comparator.comparingLong(Due::at));
    /** Boils running, and their dimension. */
    private static final java.util.Map<BlockPos, ResourceKey<Level>> RUNNING = new java.util.HashMap<>();

    /** How far from the rupture the ground can liquefy: where the shaking still reaches {@link #ONSET}. */
    static double reach(double magnitude) {
        return Math.min(GeyserConfig.QUAKE_DAMAGE_RANGE.get(), Math.max(0.0, FeltShaking.distanceFor(magnitude, ONSET)));
    }

    public static void start(ServerLevel level, BlockPos epicentre, List<QuakePlanner.TracePoint> trace,
                             double magnitude, double depthMetres, long startAt) {
        if (!GeyserConfig.QUAKE_LIQUEFACTION.get() || trace.isEmpty()) return;
        double reach = reach(magnitude);
        if (reach < 1) return;
        Deque<ChunkPos> chunks = new ArrayDeque<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (QuakePlanner.TracePoint t : trace) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
        }
        int r = (int) Math.ceil(reach);
        double[] d = new double[1];
        for (int cx = (minX - r) >> 4; cx <= (maxX + r) >> 4; cx++) {
            for (int cz = (minZ - r) >> 4; cz <= (maxZ + r) >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
                FeltShaking.nearest(trace, cx * 16 + 8, cz * 16 + 8, d);
                if (d[0] <= reach + 12) chunks.add(new ChunkPos(cx, cz));
            }
        }
        if (!chunks.isEmpty()) {
            JOBS.add(new Job(level.dimension(), epicentre, trace, magnitude, depthMetres, startAt, chunks, new int[1]));
        }
    }

    /** Whether something happened since the last summary was logged. */
    private static boolean report;

    public static void drain(MinecraftServer server, long nanos) {
        if (JOBS.isEmpty() && DUE.isEmpty() && RUNNING.isEmpty()) {
            if (report) {
                report = false;
                com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", summary());
            }
            return;
        }
        report = true;
        long deadline = System.nanoTime() + nanos;
        long now = server.getTickCount();
        while (!DUE.isEmpty() && System.nanoTime() < deadline) {
            Due due = DUE.peek();
            ServerLevel level = server.getLevel(due.dimension());
            if (level == null) {
                DUE.poll();
                continue;
            }
            if (due.at() > level.getGameTime()) break;
            DUE.poll();
            if (!level.isLoaded(due.pos())) continue;
            switch (due.kind()) {
                case BOIL -> boil(level, due.pos());
                case SETTLE -> settle(level, due.pos(), due.extra());
                case HOLD -> hold(level, due.pos());
                case DRAIN -> dry(level, due.pos());
            }
        }
        // The boils bubbling.
        if (!RUNNING.isEmpty() && now % 4 == 0) {
            for (var e : RUNNING.entrySet()) {
                ServerLevel level = server.getLevel(e.getValue());
                BlockPos p = e.getKey();
                if (level == null || !level.isLoaded(p) || !level.getFluidState(p).is(FluidTags.WATER)) continue;
                level.sendParticles(ParticleTypes.SPLASH, p.getX() + 0.5, p.getY() + 0.9, p.getZ() + 0.5,
                        6, 0.2, 0.3, 0.2, 0.3);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()),
                        p.getX() + 0.5, p.getY() + 0.8, p.getZ() + 0.5, 3, 0.15, 0.4, 0.15, 0.15);
            }
        }
        while (!JOBS.isEmpty() && System.nanoTime() < deadline) {
            Job job = JOBS.peek();
            ChunkPos cp = job.chunks().poll();
            if (cp == null || job.settled()[0] >= MOST) {
                JOBS.poll();
                continue;
            }
            ServerLevel level = server.getLevel(job.dimension());
            if (level != null) chunk(level, job, cp);
        }
    }

    /** Works out one chunk: its shaking, its wet loose ground, and what comes of it and when. */
    private static void chunk(ServerLevel level, Job job, ChunkPos cp) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return;
        double[] far = new double[1];
        QuakePlanner.TracePoint at = FeltShaking.nearest(job.trace(), cp.getMiddleBlockX(), cp.getMiddleBlockZ(), far);
        double intensity = FeltShaking.intensity(job.magnitude(), far[0]);
        if (intensity < ONSET) return;
        CHUNKS.increment();
        long now = level.getGameTime();
        long arrives = Math.max(now, job.startAt()) + FeltShaking.ruptureDelay(job.epicentre(), at)
                + FeltShaking.travelTicks(far[0], job.depthMetres(), SeismicWave.VS);
        int lasts = Math.max(20, FeltShaking.durationTicks(job.magnitude(), far[0]));
        // Sand goes quick after the shaking starts and not at the very first jolt: through the middle of it.
        java.util.function.LongSupplier when = () -> arrives + lasts / 4 + level.random.nextInt(Math.max(1, lasts / 2));
        double strength = intensity - ONSET;

        // Built columns first: what players placed, and what structures stand on the ground here.
        LongSet placed = PlayerBuilt.inChunk(level, cp.x, cp.z);
        List<BoundingBox> built = ShakingDamage.structureBoxes(level, chunk,
                chunk.getMinBuildHeight());
        Long2IntOpenHashMap bases = new Long2IntOpenHashMap();
        bases.defaultReturnValue(Integer.MAX_VALUE);
        for (long p : placed) {
            long col = BlockPos.asLong(BlockPos.getX(p), 0, BlockPos.getZ(p));
            bases.put(col, Math.min(bases.get(col), BlockPos.getY(p)));
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        if (!built.isEmpty()) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                    if (!ShakingDamage.inside(built, x, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz), z)) continue;
                    int base = structureBase(chunk, m, x, z, lx, lz);
                    if (base == Integer.MIN_VALUE) continue;
                    long col = BlockPos.asLong(x, 0, z);
                    bases.put(col, Math.min(bases.get(col), base));
                }
            }
        }
        // Whether this ground goes at all: once it does, everything on it goes with it, so a building on it settles
        // together, and one standing half on sounder ground is torn across.
        boolean goes = level.random.nextDouble() < Math.min(0.9, 0.35 * (strength + 1.0));
        if (goes) {
            for (var e : bases.long2IntEntrySet()) {
                int x = BlockPos.getX(e.getLongKey()), z = BlockPos.getZ(e.getLongKey());
                int base = e.getIntValue();
                if (!susceptible(level, chunk, m, x, base - 1, z)) continue;
                if (job.settled()[0]++ >= MOST) break;
                DUE.add(new Due(level.dimension(), Kind.SETTLE, new BlockPos(x, base, z), 0, when.getAsLong()));
            }
        }
        // Sand boils in the open ground, a few to a chunk, more the harder it shakes.
        int tries = (int) Math.round(3 + strength * 6);
        for (int i = 0; i < tries; i++) {
            int lx = level.random.nextInt(16), lz = level.random.nextInt(16);
            int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
            if (bases.containsKey(BlockPos.asLong(x, 0, z))) continue;
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
            if (!susceptible(level, chunk, m, x, top, z)) continue;
            if (!chunk.getBlockState(m.set(x, top + 1, z)).isAir()) continue;
            if (level.random.nextDouble() >= Math.min(0.8, 0.25 * (strength + 1.0))) continue;
            DUE.add(new Due(level.dimension(), Kind.BOIL, new BlockPos(x, top, z), 0, when.getAsLong()));
        }
        // Whoever stands on it sinks in for a moment.
        DUE.add(new Due(level.dimension(), Kind.HOLD, new BlockPos(cp.getMiddleBlockX(), 0, cp.getMiddleBlockZ()),
                0, arrives + lasts / 3));
    }

    /**
     * Where a structure's building in this column starts: the lowest block of it over the ground, followed down from
     * the top of the column. The natural ground and the plants on it are not the building.
     */
    private static int structureBase(LevelChunk chunk, BlockPos.MutableBlockPos m, int x, int z, int lx, int lz) {
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
        int base = Integer.MIN_VALUE;
        for (int y = top; y > top - STACK; y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            if (ShakingDamage.ground(s)) break;
            if (!s.getFluidState().isEmpty()) return Integer.MIN_VALUE;
            base = y;
        }
        return base;
    }

    /** Loose, and wet: whether the ground under {@code top} liquefies. */
    static boolean susceptible(ServerLevel level, LevelChunk chunk, BlockPos.MutableBlockPos m, int x, int top, int z) {
        int loose = 0;
        boolean sandy = false;
        for (int y = top; y > top - DEPTH; y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (loose(s)) {
                loose++;
                if (s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVEL) || s.is(Blocks.MUD)) sandy = true;
            } else if (!s.isAir() && s.getFluidState().isEmpty()) {
                if (y >= top - 1) return false;       // rock at the surface
            }
        }
        if (loose < LOOSE) return false;
        return wet(level, m, x, top, z, sandy);
    }

    /** Sand, silt, gravel and soil: grains that can float apart. Clay holds together; rock does not move. */
    private static boolean loose(BlockState s) {
        return s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVEL) || s.is(Blocks.MUD) || s.is(Blocks.DIRT)
                || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.MUDDY_MANGROVE_ROOTS) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND)
                || s.is(Blocks.SUSPICIOUS_SAND) || s.is(Blocks.SUSPICIOUS_GRAVEL);
    }

    /**
     * Wet enough: water beside it at the surface (a river, a lake, the sea), or a floodplain, a river bank, a beach or a
     * swamp, or the water table within {@link #WET} blocks of the surface. Plain soil needs the water beside it; sand
     * and gravel liquefy on a high table alone.
     */
    private static boolean wet(ServerLevel level, BlockPos.MutableBlockPos m, int x, int top, int z, boolean sandy) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + dz * dz > 10) continue;
                for (int dy = -2; dy <= 1; dy++) {
                    m.set(x + dx, top + dy, z + dz);
                    if (!level.isLoaded(m)) continue;
                    if (level.getFluidState(m).is(FluidTags.WATER)) return true;
                }
            }
        }
        var biome = level.getBiome(m.set(x, top, z));
        boolean wetland = biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH) || biome.is(Tags.Biomes.IS_SWAMP)
                || biome.is(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                        new net.minecraft.resources.ResourceLocation(GeysersMod.MODID, "alluvial_plain")));
        if (wetland) return true;
        if (!sandy) return false;
        return top - com.jeladastudios.ftsgeology.hydrology.WaterTable.tableY(level, x, z) <= WET;
    }

    /** A sand boil opens: the ground round the vent sanded over, water welling up in the middle, for a while. */
    private static void boil(ServerLevel level, BlockPos top) {
        BlockState ground = level.getBlockState(top);
        if (!loose(ground) || !level.getBlockState(top.above()).isAir()) return;
        level.setBlock(top, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos n = top.relative(d);
            BlockState s = level.getBlockState(n);
            if (loose(s) && !s.is(BlockTags.SAND) && level.getBlockState(n.above()).isAir()) {
                level.setBlock(n, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
            } else if (s.isAir() && level.random.nextBoolean()) {
                // ejected sand heaped on the rim
                BlockState under = level.getBlockState(n.below());
                if (under.isFaceSturdy(level, n.below(), net.minecraft.core.Direction.UP)) {
                    level.setBlock(n, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // The vent itself sinks a little and water fills it.
        level.setBlock(top, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        RUNNING.put(top.immutable(), level.dimension());
        level.playSound(null, top, net.minecraft.sounds.SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.6f);
        DUE.add(new Due(level.dimension(), Kind.DRAIN, top.immutable(), 0, level.getGameTime() + BOIL));
        BOILS.increment();
        com.jeladastudios.ftsgeology.advancement.GeologyTrigger.awardNear(level, top.getX(), top.getZ(), 48, "liquefaction");
    }

    /** A boil dries up: its water sinks back into the sand, and a sand cone is left. */
    private static void dry(ServerLevel level, BlockPos top) {
        RUNNING.remove(top);
        if (level.getFluidState(top).isSource() && level.getBlockState(top).is(Blocks.WATER)) {
            level.setBlock(top, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * A built column settles one block into the ground under it: the block of ground goes, and everything built on it
     * comes down one, in order. A column holding anything with contents stays, and is torn from its neighbours.
     */
    private static void settle(ServerLevel level, BlockPos basePos, int unused) {
        int x = basePos.getX(), z = basePos.getZ(), base = basePos.getY();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState ground = level.getBlockState(m.set(x, base - 1, z));
        if (!loose(ground)) return;
        LongSet placed = PlayerBuilt.inChunk(level, x >> 4, z >> 4);
        // The column's building: from its base up to its last block, the air of its rooms included; six blocks of
        // air over it is the sky. Natural ground in it -- a hillside it is built against, a tree over its roof -- is not
        // the building's to move, and neither is anything with contents: such a column stands.
        int top = base, air = 0;
        for (int y = base; y < Math.min(base + STACK, level.getMaxBuildHeight() - 1) && air < 6; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) {
                air++;
                continue;
            }
            air = 0;
            if (s.hasBlockEntity() || !s.getFluidState().isEmpty()) return;
            if (ShakingDamage.ground(s) && !placed.contains(BlockPos.asLong(x, y, z))) return;
            top = y;
        }
        int height = top - base + 1;
        BlockState[] stack = new BlockState[height];
        for (int i = 0; i < height; i++) stack[i] = level.getBlockState(m.set(x, base + i, z));
        for (int i = 0; i < height; i++) {
            BlockPos to = new BlockPos(x, base - 1 + i, z);
            level.setBlock(to, stack[i], Earthquake.FLAGS);
            if (placed.contains(BlockPos.asLong(x, base + i, z))) PlayerBuilt.move(level, BlockPos.asLong(x, base + i, z), to);
        }
        level.setBlock(new BlockPos(x, top, z), Blocks.AIR.defaultBlockState(), Earthquake.FLAGS);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), x + 0.5, base, z + 0.5,
                6, 0.4, 0.1, 0.4, 0.05);
        SETTLED.increment();
    }

    /** Whoever stands on the liquefied ground of a chunk sinks in and is held for a few seconds. */
    private static void hold(ServerLevel level, BlockPos middle) {
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(middle.getX() - 8, level.getMinBuildHeight(),
                middle.getZ() - 8, middle.getX() + 8, level.getMaxBuildHeight(), middle.getZ() + 8);
        LevelChunk chunk = level.getChunkSource().getChunkNow(middle.getX() >> 4, middle.getZ() >> 4);
        if (chunk == null) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::onGround)) {
            BlockPos under = e.blockPosition().below();
            if (!susceptible(level, chunk, m, under.getX(), under.getY(), under.getZ())) continue;
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 3));
            e.setDeltaMovement(e.getDeltaMovement().add(0, -0.2, 0));
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(under)),
                    e.getX(), e.getY(), e.getZ(), 12, 0.3, 0.05, 0.3, 0.05);
            HELD.increment();
            if (e instanceof net.minecraft.server.level.ServerPlayer sp) com.jeladastudios.ftsgeology.advancement.GeologyTrigger.award(sp, "liquefaction");
        }
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "liquefaction: %d chunks, %d sand boils, %d columns settled, %d held fast",
                CHUNKS.sum(), BOILS.sum(), SETTLED.sum(), HELD.sum());
    }

    public static void clear() {
        JOBS.clear();
        DUE.clear();
        RUNNING.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
