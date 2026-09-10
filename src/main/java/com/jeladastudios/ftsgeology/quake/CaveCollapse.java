package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cave roofs brought down by an earthquake.
 *
 * <h2>What happens underground</h2>
 * A cave is an arch, and an arch is only as good as the rock it is cut in and the distance it spans.
 * Shaking loads it sideways, the one direction an arch is not built for, so after a strong quake the
 * roofs of wide, shallow caves come down - slabs peeling off the ceiling onto the floor. The void does
 * not disappear when that happens. It moves up into the space the slab left, and where the rock
 * above was thin enough it reaches daylight: a sinkhole, which in karst country is one of the
 * commonest things an earthquake leaves behind.
 *
 * <p>So that is exactly what this does. The bottom of a roof drops onto the cave floor as rubble, the
 * void rises by as much, and where it breaks the surface the ground opens.</p>
 *
 * <h2>Where it looks</h2>
 * Probes are drawn from the columns the quake actually moved and weakened with distance from the
 * epicentre, so roofs come down along the rupture rather than everywhere in earshot of it. A probe in
 * a chunk nobody has loaded waits for that chunk, the way the rest of the settling does.
 *
 * <h2>What it will not do</h2>
 * Move a roof made of player blocks, or bury anything: rubble only lands in open air, so a torch or a
 * rail in a mine tunnel stops the collapse rather than being covered. Drain water: a flooded cave, a
 * roof with water in it and ground with a lake on it are all left alone. Or touch the mod's own
 * machinery, which is underground on purpose.
 */
public final class CaveCollapse {

    private CaveCollapse() {}

    /** Probes looked at per tick at most, on top of the wall-clock budget. */
    private static final int PROBES_PER_TICK = 64;

    /** A roof this thin or thinner comes down all the way, and the collapse reaches the surface. */
    private static final int THIN_ROOF = 5;

    /** A void at least this tall is a cave rather than a pocket. */
    private static final int MIN_VOID = 2;

    /** How far a probe looks sideways along the ceiling to measure the span. */
    private static final int SPAN_REACH = 8;

    /** Overall likelihood a fully loaded arch in weak rock gives way under the strongest shaking. */
    private static final double CHANCE = 0.9;

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** One rupture's worth of probes: columns, how hard each was shaken, and running totals. */
    private static final class Job {
        final ResourceKey<Level> dimension;
        final long[] columns;
        final float[] shaking;
        final int minX, maxX, minZ, maxZ;
        int cursor, probed, caves, collapses, sinkholes, moved, refused;

        Job(ResourceKey<Level> dimension, long[] columns, float[] shaking) {
            this.dimension = dimension;
            this.columns = columns;
            this.shaking = shaking;
            int lx = Integer.MAX_VALUE, hx = Integer.MIN_VALUE, lz = Integer.MAX_VALUE, hz = Integer.MIN_VALUE;
            for (long c : columns) {
                int x = (int) (c >> 32), z = (int) c;
                lx = Math.min(lx, x);
                hx = Math.max(hx, x);
                lz = Math.min(lz, z);
                hz = Math.max(hz, z);
            }
            minX = lx; maxX = hx; minZ = lz; maxZ = hz;
        }

        boolean overlaps(int x, int z, int radius) {
            return x + radius >= minX && x - radius <= maxX && z + radius >= minZ && z - radius <= maxZ;
        }
    }

    /** Probes waiting on a chunk to be loaded, by chunk. */
    private static final class Parked {
        final LongArrayList columns = new LongArrayList();
        final FloatArrayList shaking = new FloatArrayList();
    }

    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    private static final Map<String, Parked> PARKED = new HashMap<>();

    /** The shape of the cave under one column. {@code roof} counts the solid blocks over it. */
    private record Cave(int ground, int top, int floor, int roof) {}

    // === Queue ===============================================================

    /**
     * Draws probes from a finished quake's corridor. More for a bigger quake, and nothing at all below
     * about M5.5, where the shaking is not enough to fail an arch that has stood for ten thousand years.
     */
    public static void enqueue(ServerLevel level, QuakePlanner.Plan plan) {
        if (!GeyserConfig.QUAKE_CAVE_COLLAPSE.get()) return;
        List<QuakePlanner.Edit> edits = plan.edits();
        double strength = Mth.clamp((plan.magnitude() - 5.5) / 3.5, 0.0, 1.0);
        if (edits.isEmpty() || strength <= 0.0) return;

        int wanted = Mth.clamp((int) Math.round(plan.magnitude() * plan.magnitude() * 16.0), 64, 2000);
        BlockPos e = plan.epicentre();
        double reach = plan.ruptureLength() * 0.5 + 60.0;
        RandomSource rng = level.random;
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList columns = new LongArrayList(wanted);
        FloatArrayList shaking = new FloatArrayList(wanted);
        for (int i = 0; i < wanted * 3 && columns.size() < wanted; i++) {
            BlockPos p = edits.get(rng.nextInt(edits.size())).pos();
            long k = key(p.getX(), p.getZ());
            if (!seen.add(k)) continue;
            double d = Math.hypot(p.getX() - e.getX(), p.getZ() - e.getZ());
            columns.add(k);
            shaking.add((float) (strength * Mth.clamp(1.0 - d / reach, 0.15, 1.0)));
        }
        QUEUE.add(new Job(level.dimension(), columns.toLongArray(), shaking.toFloatArray()));
        GeysersMod.LOGGER.info("cave collapse queued: {} probes along the rupture", columns.size());
    }

    /** Looks at a slice of the probes. Bounded by a count and a wall-clock deadline. */
    public static void drain(MinecraftServer server, long budgetNanos) {
        if (QUEUE.isEmpty() || server == null) return;
        long deadline = System.nanoTime() + budgetNanos;
        Job job = QUEUE.peek();
        ServerLevel level = server.getLevel(job.dimension);
        if (level == null) {
            QUEUE.poll();
            return;
        }
        for (int n = 0; n < PROBES_PER_TICK && System.nanoTime() < deadline; n++) {
            if (job.cursor >= job.columns.length) {
                QUEUE.poll();
                if (job.collapses > 0 || job.columns.length >= 32) {
                    GeysersMod.LOGGER.info("cave collapse finished: {} probes, {} caves under them, {} roofs "
                                    + "came down, {} reached the surface, {} blocks moved, {} columns refused",
                            job.probed, job.caves, job.collapses, job.sinkholes, job.moved, job.refused);
                }
                return;
            }
            long k = job.columns[job.cursor];
            float s = job.shaking[job.cursor++];
            int x = (int) (k >> 32), z = (int) k;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                Parked p = PARKED.computeIfAbsent(parkKey(job.dimension, x >> 4, z >> 4), c -> new Parked());
                p.columns.add(k);
                p.shaking.add(s);
                continue;
            }
            probe(level, job, x, z, s);
        }
    }

    /** Re-queues the probes that were waiting on this chunk. */
    public static void onChunkLoaded(ServerLevel level, ChunkPos cp) {
        Parked p = PARKED.remove(parkKey(level.dimension(), cp.x, cp.z));
        if (p == null || p.columns.isEmpty()) return;
        QUEUE.add(new Job(level.dimension(), p.columns.toLongArray(), p.shaking.toFloatArray()));
    }

    /**
     * Are there still probes to look at over this patch of ground? Asked by {@link QuakeQuiet}, so a
     * spring does not rebuild beside a cave that is about to open. Parked probes do not count, for the
     * reason given at {@link Weathering#pendingNear}.
     */
    public static boolean pendingNear(ServerLevel level, int x, int z, int radius) {
        for (Job job : QUEUE) {
            if (job.dimension.equals(level.dimension()) && job.overlaps(x, z, radius)) return true;
        }
        return false;
    }

    /** Drops everything outstanding; used when a server stops. */
    public static int clear() {
        int n = QUEUE.size();
        QUEUE.clear();
        PARKED.clear();
        return n;
    }

    private static String parkKey(ResourceKey<Level> dim, int cx, int cz) {
        return dim.location() + "@" + cx + "," + cz;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // === Collapse ============================================================

    /** One probe: is there a cave under it, and does its roof give way? */
    private static void probe(ServerLevel level, Job job, int x, int z, float shaking) {
        job.probed++;
        Cave cave = caveUnder(level, x, z);
        if (cave == null) return;
        job.caves++;

        // How hard the arch is working: a wide span under a thin roof is the one that fails. The roof
        // counts at half weight, because rock arches over far more than its own thickness; at full
        // weight a first test at M8 found 177 caves along the rupture and brought down two roofs.
        int span = span(level, x, cave.top(), z);
        double arch = Mth.clamp(span / (cave.roof() * 0.5 + 2.0), 0.0, 1.5) / 1.5;
        double weak = RockTypes.erodibility(level.getBlockState(new BlockPos(x, cave.top() + 1, z)));
        if (level.random.nextDouble() >= CHANCE * shaking * arch * (0.4 + 0.6 * weak)) return;

        boolean throughRoof = cave.roof() <= THIN_ROOF;
        int fall = throughRoof ? cave.roof() : Math.min(cave.roof() - 2, 2 + level.random.nextInt(3));
        int radius = Mth.clamp(span / 3, 1, 4);
        int movedHere = 0;
        boolean surfaced = false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + radius) continue;
                int cx = x + dx, cz = z + dz;
                if (!level.hasChunk(cx >> 4, cz >> 4)) continue;
                Cave c = caveUnder(level, cx, cz);
                if (c == null || Math.abs(c.top() - cave.top()) > 4) continue;   // the same cave only
                int n = dropRoof(level, cx, cz, c, throughRoof ? c.roof() : Math.min(fall, c.roof() - 2));
                if (n < 0) {
                    job.refused++;
                    continue;
                }
                movedHere += n;
                if (n >= c.roof()) surfaced = true;
            }
        }
        if (movedHere == 0) return;
        job.collapses++;
        job.moved += movedHere;
        if (surfaced) job.sinkholes++;

        int y = surfaced ? cave.ground() + 1 : cave.floor() + 1;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(new BlockPos(x, y - 1, z))),
                x + 0.5, y, z + 0.5, surfaced ? 40 : 16, radius * 0.6, 0.4, radius * 0.6, 0.05);
        level.playSound(null, x + 0.5, y, z + 0.5,
                surfaced ? SoundEvents.GRAVEL_BREAK : SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 3.0F, 0.5F);
    }

    /** The first cave under a column within the configured depth, or null. */
    private static Cave caveUnder(ServerLevel level, int x, int z) {
        int ground = TerrainProbe.groundY(level, x, z);
        if (ground == Integer.MIN_VALUE) return null;
        int bottom = Math.max(level.getMinBuildHeight() + 1, ground - GeyserConfig.CAVE_COLLAPSE_DEPTH.get());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y = ground - 1;
        while (y > bottom && !level.getBlockState(m.set(x, y, z)).isAir()) y--;
        if (y <= bottom) return null;
        int top = y;
        while (y - 1 > bottom && level.getBlockState(m.set(x, y - 1, z)).isAir()) y--;
        if (top - y + 1 < MIN_VOID) return null;
        return new Cave(ground, top, y, ground - top);
    }

    /** The widest run of open ceiling through the column, along either axis. */
    private static int span(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int best = 1;
        for (int axis = 0; axis < 2; axis++) {
            int w = 1;
            for (int dir = -1; dir <= 1; dir += 2) {
                for (int i = 1; i <= SPAN_REACH; i++) {
                    int cx = x + (axis == 0 ? i * dir : 0), cz = z + (axis == 1 ? i * dir : 0);
                    if (!level.hasChunk(cx >> 4, cz >> 4) || !level.getBlockState(m.set(cx, y, cz)).isAir()) break;
                    w++;
                }
            }
            best = Math.max(best, w);
        }
        return best;
    }

    /**
     * Drops the bottom {@code n} blocks of a roof onto the cave floor, so the void rises by {@code n}.
     *
     * @return blocks moved, or -1 where this column refused to collapse at all
     */
    private static int dropRoof(ServerLevel level, int x, int z, Cave c, int n) {
        if (n <= 0) return 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Rubble lands only in open air: water, a torch or a rail in the void stops it.
        for (int y = c.floor(); y <= c.top(); y++) {
            if (!level.getBlockState(m.set(x, y, z)).isAir()) return -1;
        }
        // Breaking through only where nothing stands on the ground: a tree, a build or a lake up
        // there keeps its last block of support.
        if (n >= c.roof()) {
            BlockState above = level.getBlockState(m.set(x, c.ground() + 1, z));
            if (!above.getFluidState().isEmpty() || (!above.isAir() && !TerrainProbe.isVegetation(above))) {
                n = c.roof() - 1;
                if (n <= 0) return 0;
            }
        }
        BlockState[] slab = new BlockState[n];
        for (int i = 0; i < n; i++) {
            BlockState s = level.getBlockState(m.set(x, c.top() + 1 + i, z));
            if (s.is(Blocks.BEDROCK) || s.is(Blocks.MAGMA_BLOCK) || s.hasBlockEntity()
                    || QuakePlanner.machinery(s) || !s.getFluidState().isEmpty()
                    || EruptionHandler.isPlayerPlaced(s)) {
                return -1;
            }
            slab[i] = s;
        }
        if (n >= c.roof()) TerrainProbe.clearVegetation(level, x, c.ground(), z, 2);
        int drop = c.top() - c.floor() + 1;
        for (int i = 0; i < n; i++) {
            level.setBlock(m.set(x, c.top() + 1 + i, z), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        for (int i = 0; i < n; i++) {
            level.setBlock(m.set(x, c.top() + 1 + i - drop, z), rubble(slab[i], level.random), FLAGS);
        }
        return n;
    }

    /** What a block becomes on the way down: bedrock breaks up, soil loses its turf. */
    private static BlockState rubble(BlockState s, RandomSource rng) {
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)) {
            if (rng.nextInt(4) == 0) return Blocks.GRAVEL.defaultBlockState();
            return s.is(Blocks.DEEPSLATE) ? Blocks.COBBLED_DEEPSLATE.defaultBlockState()
                    : Blocks.COBBLESTONE.defaultBlockState();
        }
        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return s;
    }
}
