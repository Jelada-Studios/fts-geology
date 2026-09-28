package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.DynamicTreesFelling;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.instrument.SeismicWave;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Hillsides giving way. The shaking pulls the loose cover off steep ground -- soil, scree, sand, snow -- and it goes
 * down the slope as one mass, trees and all, and heaps up at the foot, leaving a raw scar of rock behind. Most of the
 * deaths in many earthquakes are landslides: Wenchuan in 2008, Gorkha in 2015, the 1970 Huascarán avalanche.
 *
 * <p>Only on natural ground: a slope a player or a village built on is left to {@link ShakingDamage}. Only away from
 * water, so a slide does not dam a river in a way nothing ever clears. The steeper, the harder shaken and the wetter
 * the slope, the likelier; once a slope goes, the ground round it on the same slope goes with it.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Landslides {

    private Landslides() {}

    /** The least intensity that brings down a slope: about Mercalli VI. */
    static final double ONSET = 5.5;
    /** How far across the slope is read, and the least drop over it that is steep: forty-five degrees. */
    private static final int SPAN = 3, STEEP = 3;
    /** How deep the loose cover is taken, at most. */
    private static final int COVER = 3;
    /** Slides per chunk, and in all, at most; the falling blocks shown in all, at most. */
    private static final int PER_CHUNK = 2, MOST = 40, SHOWN = 400;
    /** How far down the slope a slide's debris runs before it heaps up, at most. */
    private static final int RUNOUT = 24;

    private static final LongAdder SLIDES = new LongAdder(), MOVED = new LongAdder(), TREES = new LongAdder();

    private record Job(ResourceKey<Level> dimension, BlockPos epicentre, List<QuakePlanner.TracePoint> trace,
                       double magnitude, double depthMetres, long startAt, Deque<ChunkPos> chunks, int[] counts) {}

    /** A slope due to give way: where it starts, which way is down, how big, and when. */
    private record Slide(ResourceKey<Level> dimension, BlockPos seed, Direction down, int radius, long at, int[] counts) {}

    private static final Deque<Job> JOBS = new ArrayDeque<>();
    private static final PriorityQueue<Slide> DUE = new PriorityQueue<>(Comparator.comparingLong(Slide::at));

    static double reach(double magnitude) {
        return Math.min(GeyserConfig.QUAKE_DAMAGE_RANGE.get(), Math.max(0.0, FeltShaking.distanceFor(magnitude, ONSET)));
    }

    public static void start(ServerLevel level, BlockPos epicentre, List<QuakePlanner.TracePoint> trace,
                             double magnitude, double depthMetres, long startAt) {
        if (!GeyserConfig.QUAKE_LANDSLIDES.get() || trace.isEmpty()) return;
        double reach = reach(magnitude);
        if (reach < 1) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (QuakePlanner.TracePoint t : trace) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
        }
        Deque<ChunkPos> chunks = new ArrayDeque<>();
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
            JOBS.add(new Job(level.dimension(), epicentre, trace, magnitude, depthMetres, startAt, chunks, new int[2]));
        }
    }

    /** Whether something happened since the last summary was logged. */
    private static boolean report;

    public static void drain(MinecraftServer server, long nanos) {
        if (JOBS.isEmpty() && DUE.isEmpty()) {
            if (report) {
                report = false;
                com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", summary());
            }
            return;
        }
        report = true;
        long deadline = System.nanoTime() + nanos;
        scanDeadline = deadline;
        while (!DUE.isEmpty() && System.nanoTime() < deadline) {
            Slide s = DUE.peek();
            ServerLevel level = server.getLevel(s.dimension());
            if (level == null) {
                DUE.poll();
                continue;
            }
            if (s.at() > level.getGameTime()) break;
            DUE.poll();
            if (com.jeladastudios.ftsgeology.util.Loaded.at(level, s.seed())) slide(level, s);
        }
        while (!JOBS.isEmpty() && System.nanoTime() < deadline) {
            Job job = JOBS.peek();
            ChunkPos cp = job.chunks().poll();
            if (cp == null || job.counts()[0] >= MOST) {
                JOBS.poll();
                continue;
            }
            ServerLevel level = server.getLevel(job.dimension());
            if (level != null) chunk(level, job, cp);
        }
    }

    /** Until when this tick's look over the slopes may work out the water table where it is not known yet. */
    private static long scanDeadline = Long.MAX_VALUE;

    /** Looks over a chunk's slopes for the ones the shaking brings down, and when. */
    private static void chunk(ServerLevel level, Job job, ChunkPos cp) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return;
        double[] far = new double[1];
        QuakePlanner.TracePoint at = FeltShaking.nearest(job.trace(), cp.getMiddleBlockX(), cp.getMiddleBlockZ(), far);
        double intensity = FeltShaking.intensity(job.magnitude(), far[0]);
        if (intensity < ONSET) return;
        double strength = intensity - ONSET;
        long now = level.getGameTime();
        long arrives = Math.max(now, job.startAt()) + FeltShaking.ruptureDelay(job.epicentre(), at)
                + FeltShaking.travelTicks(far[0], job.depthMetres(), SeismicWave.VS);
        int lasts = Math.max(20, FeltShaking.durationTicks(job.magnitude(), far[0]));
        int radius = Math.min(6, 2 + (int) Math.round(strength * 1.5));
        LongSet placed = PlayerBuilt.inChunk(level, cp.x, cp.z);
        List<BoundingBox> built = ShakingDamage.structureBoxes(level, chunk, level.getMinBuildHeight());
        int here = 0;
        for (int lx = 1; lx < 16 && here < PER_CHUNK; lx += 2) {
            for (int lz = 1; lz < 16 && here < PER_CHUNK; lz += 2) {
                int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                int top = TerrainProbe.groundY(level, x, z);
                if (top == Integer.MIN_VALUE) continue;
                Direction down = null;
                int drop = 0;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = new BlockPos(x + d.getStepX() * SPAN, top, z + d.getStepZ() * SPAN);
                    if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) continue;
                    int g = TerrainProbe.groundY(level, n.getX(), n.getZ());
                    if (g != Integer.MIN_VALUE && top - g > drop) {
                        drop = top - g;
                        down = d;
                    }
                }
                if (down == null || drop < STEEP) continue;
                if (cover(level, x, top, z) == 0 || built(placed, built, x, top, z) || nearWater(level, x, top, z)) continue;
                // Steeper and harder shaken, likelier; a wet slope likelier still.
                double chance = 0.04 * (strength + 0.5) * Math.min(3.0, drop / (double) STEEP);
                int table = com.jeladastudios.ftsgeology.hydrology.WaterTable.tableYBefore(level, x, z, scanDeadline);
                if (table != Integer.MIN_VALUE && top - table <= 4) chance *= 1.5;
                if (level.random.nextDouble() >= Math.min(0.5, chance)) continue;
                if (job.counts()[0]++ >= MOST) return;
                here++;
                DUE.add(new Slide(level.dimension(), new BlockPos(x, top, z), down, radius,
                        arrives + level.random.nextInt(lasts), job.counts()));
            }
        }
    }

    /** How many blocks of loose cover a column has at its top: soil, scree, sand, snow. */
    private static int cover(ServerLevel level, int x, int top, int z) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = top; y > top - COVER; y--) {
            if (!loose(level.getBlockState(m.set(x, y, z)))) break;
            n++;
        }
        return n;
    }

    private static boolean loose(BlockState s) {
        return s.is(BlockTags.DIRT) && !DynamicTreesFelling.isRooty(s) || s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVEL)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CLAY) || s.is(Blocks.MUD)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.PACKED_MUD)
                || s.is(com.jeladastudios.ftsgeology.registry.ModBlocks.VOLCANIC_ASH.get());
    }

    /** Whether a column is part of something built: a player's, or a structure's. */
    private static boolean built(LongSet placed, List<BoundingBox> boxes, int x, int top, int z) {
        for (int y = top - COVER; y <= top + 12; y++) if (placed.contains(BlockPos.asLong(x, y, z))) return true;
        return !boxes.isEmpty() && ShakingDamage.inside(boxes, x, top, z);
    }

    private static boolean nearWater(ServerLevel level, int x, int top, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx += 2) {
            for (int dz = -4; dz <= 4; dz += 2) {
                for (int dy = -4; dy <= 1; dy++) {
                    m.set(x + dx, top + dy, z + dz);
                    if (com.jeladastudios.ftsgeology.util.Loaded.at(level, m) && !level.getFluidState(m).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /**
     * The slope goes: the loose cover of every steep column round the seed that faces the same way comes off and runs
     * down, as falling blocks thrown down the slope that heap where they land, and past the cap straight onto the
     * ground where the run-out ends. Trees on it come down; what is left hanging settles after.
     */
    private static void slide(ServerLevel level, Slide s) {
        BlockPos seed = s.seed();
        Direction down = s.down();
        LongSet placed = PlayerBuilt.inChunk(level, seed.getX() >> 4, seed.getZ() >> 4);
        List<QuakePlanner.Edit> emptied = new ArrayList<>();
        int moved = 0;
        for (int dx = -s.radius(); dx <= s.radius(); dx++) {
            for (int dz = -s.radius(); dz <= s.radius(); dz++) {
                if (dx * dx + dz * dz > s.radius() * s.radius() + 1) continue;
                int x = seed.getX() + dx, z = seed.getZ() + dz;
                BlockPos col = new BlockPos(x, seed.getY(), z);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, col)) continue;
                int top = TerrainProbe.groundY(level, x, z);
                if (top == Integer.MIN_VALUE) continue;
                BlockPos below = new BlockPos(x + down.getStepX() * SPAN, top, z + down.getStepZ() * SPAN);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, below)) continue;
                int g = TerrainProbe.groundY(level, below.getX(), below.getZ());
                // Only the same slope: at least half as steep, and falling the same way.
                if (g == Integer.MIN_VALUE || top - g < 2) continue;
                if (placed.contains(BlockPos.asLong(x, top, z)) || placed.contains(BlockPos.asLong(x, top + 1, z))) continue;
                int k = cover(level, x, top, z);
                if (k == 0) continue;
                // A Dynamic Trees tree on it comes down whole, felled down the slope.
                BlockState over = level.getBlockState(new BlockPos(x, top + 1, z));
                if (DynamicTreesFelling.isBranch(over) && DynamicTreesFelling.fell(level, new BlockPos(x, top + 1, z),
                        down.getOpposite())) {
                    TREES.increment();
                }
                // What grows on it goes with it.
                for (int y = top + 1; y <= top + 2; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState plant = level.getBlockState(p);
                    if (TerrainProbe.isVegetation(plant) || plant.is(Blocks.SNOW)) {
                        QuakeWrites.set(level, p, Blocks.AIR.defaultBlockState());
                    }
                }
                for (int i = 0; i < k; i++) {
                    BlockPos p = new BlockPos(x, top - i, z);
                    BlockState block = level.getBlockState(p);
                    if (s.counts()[1] < SHOWN) {
                        s.counts()[1]++;
                        FallingBlockEntity f = FallingBlockEntity.fall(level, p, block);
                        double push = 0.25 + level.random.nextDouble() * 0.3;
                        f.setDeltaMovement(new Vec3(down.getStepX() * push + (level.random.nextDouble() - 0.5) * 0.1,
                                0.05 + level.random.nextDouble() * 0.1,
                                down.getStepZ() * push + (level.random.nextDouble() - 0.5) * 0.1));
                        f.setHurtsEntities(1.0f, 10);
                        f.hurtMarked = true;
                    } else {
                        QuakeWrites.set(level, p, Blocks.AIR.defaultBlockState());
                        deposit(level, x, z, down, block);
                    }
                    emptied.add(new QuakePlanner.Edit(p, Blocks.AIR.defaultBlockState()));
                    moved++;
                }
            }
        }
        if (moved == 0) return;
        SLIDES.increment();
        MOVED.add(moved);
        com.jeladastudios.ftsgeology.advancement.GeologyTrigger.awardNear(level, seed.getX(), seed.getZ(), 64, "landslide");
        level.playSound(null, seed, net.minecraft.sounds.SoundEvents.GRAVEL_BREAK, net.minecraft.sounds.SoundSource.BLOCKS,
                3.0f, 0.5f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()),
                seed.getX() + 0.5, seed.getY() + 1.0, seed.getZ() + 0.5, 60, s.radius(), 1.0, s.radius(), 0.1);
        // Trees left standing on air, and cover left hanging at the scar's edge, come down after.
        Weathering.enqueue(level, emptied);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("landslide at {} {} {}: {} blocks down the slope to the {}",
                seed.getX(), seed.getY(), seed.getZ(), moved, down.getName());
    }

    /** Where debris that is not shown falling comes to rest: down the slope until it flattens out. */
    private static void deposit(ServerLevel level, int x, int z, Direction down, BlockState block) {
        int y = TerrainProbe.groundY(level, x, z);
        for (int i = 0; i < RUNOUT; i++) {
            int nx = x + down.getStepX(), nz = z + down.getStepZ();
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(nx, y, nz))) break;
            int g = TerrainProbe.groundY(level, nx, nz);
            if (g == Integer.MIN_VALUE || g >= y) break;
            x = nx;
            z = nz;
            y = g;
        }
        BlockPos at = new BlockPos(x, y + 1, z);
        if (com.jeladastudios.ftsgeology.util.Loaded.at(level, at) && level.getBlockState(at).canBeReplaced()) level.setBlock(at, block, Block.UPDATE_ALL);
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "landslides: %d, %d blocks moved, %d trees felled",
                SLIDES.sum(), MOVED.sum(), TREES.sum());
    }

    public static void clear() {
        JOBS.clear();
        DUE.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
