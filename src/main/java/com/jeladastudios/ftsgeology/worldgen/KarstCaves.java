package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * The caves a sunk river runs in ({@link com.jeladastudios.ftsgeology.hydrology.Karst}).
 *
 * <p>Where the network has a river run underground, the river falls into a swallow hole at the start of the stretch
 * and a passage is cut along its course under the valley, left dry and uncut over it, with the river's water along its
 * floor. The cave's water stands a few blocks under where the river's would have, and never lower than the river it
 * comes out into: so it runs downhill all the way, and toward its end, where the river's would-be water comes down to
 * that level, the rock over it thins away and the cave runs on in a slot open to the sky, out into the river below at
 * that river's own level.</p>
 *
 * <p>Everything is read off the river's own course, so every chunk cuts its own share of a cave and the shares meet.
 * Only what the world laid is cut: nothing a player placed, no water or lava that was there before.</p>
 */
public final class KarstCaves {

    private KarstCaves() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** How far under the river's would-be water the cave's water stands, at most, at the normal layout. */
    private static final double DEPTH = 8.0;
    /** How far past the river's flat floor the cave reaches, at the normal layout: its water spans the river's. */
    private static final double WIDER = 1.0;
    /** How high the cave's roof stands over its water, at the least and at most, at the normal layout. */
    private static final double HEIGHT_MIN = 3.0, HEIGHT_MAX = 5.0;
    /** Rock left standing over the cave, at least; thinner, the cave is open to the sky. */
    private static final int ROOF = 2;
    /** How far past a chunk the underground stretches are gathered, in blocks at the normal layout: a whole stretch. */
    private static final double MARGIN = 96.0;

    private static final LongAdder COLUMNS = new LongAdder(), WINDOWS = new LongAdder(), LOW = new LongAdder(),
            SHAFTS = new LongAdder(), FALLS = new LongAdder(), FLOORS = new LongAdder(), REFUSED = new LongAdder();

    /** One length of an underground stretch, with the cave's water at its two ends. */
    private record Length(RiverNetwork.Point p, double cave, double caveEnd, boolean first) {}

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        if (TfcCompat.active() || !GeologyWorld.isOwn(level.getLevel()) || !RiverNetwork.ready()) return 0;
        if (!GeyserConfig.RIVERS.get() || !GeyserConfig.KARST.get()) return 0;
        double h = RiverNetwork.horizontal();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int m = (int) Math.ceil(MARGIN * h);
        List<Length> lengths = stretches(RiverNetwork.sunkNear(x0 - m, z0 - m, x0 + 15 + m, z0 + 15 + m), DEPTH * h);
        if (lengths.isEmpty()) return 0;
        int[] tops = new int[256];
        java.util.Arrays.fill(tops, Integer.MIN_VALUE);
        int placed = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = x0 + dx, z = z0 + dz;
                if (!RiverNetwork.at(x, z).sunk()) continue;
                // The nearest length underground, and how far along it this column lies.
                Length best = null;
                double bestD = Double.MAX_VALUE, bestT = 0;
                for (Length l : lengths) {
                    RiverNetwork.Point p = l.p();
                    double ax = p.ex() - p.x(), az = p.ez() - p.z(), len2 = ax * ax + az * az;
                    double t = len2 < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x + 0.5 - p.x()) * ax + (z + 0.5 - p.z()) * az) / len2));
                    double ddx = p.x() + ax * t - (x + 0.5), ddz = p.z() + az * t - (z + 0.5);
                    double d = Math.sqrt(ddx * ddx + ddz * ddz);
                    if (d < bestD) {
                        bestD = d;
                        best = l;
                        bestT = t;
                    }
                }
                if (best == null) continue;
                RiverNetwork.Point p = best.p();
                double wide = p.halfWidth() + WIDER * h;
                if (bestD > wide) continue;
                double t = bestT;
                int w = (int) Math.floor(best.cave() + (best.caveEnd() - best.cave()) * t);
                double deep = (p.water() - p.bed()) + ((p.waterEnd() - p.bedEnd()) - (p.water() - p.bed())) * t;
                int fy = w - Math.max(1, (int) Math.round(deep));
                double height = Math.max(HEIGHT_MIN, Math.min(HEIGHT_MAX, 2.0 + 0.5 * p.halfWidth())) * (1.0 + 0.3 * (h - 1.0));
                double q = bestD / wide;
                int top = w + (int) Math.round(height * Math.sqrt(Math.max(0.0, 1.0 - q * q)));
                int ground = TerrainProbe.groundY(level, x, z);
                if (ground == Integer.MIN_VALUE || fy < level.getMinBuildHeight() + 1) continue;
                BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState()
                        .setValue(RiverWaterFluid.FLOW, RiverWaterFluid.wayOf(p.ex() - p.x(), p.ez() - p.z()));
                int cut = cut(level, at, x, z, fy, w, top, ground, water);
                if (cut < 0) continue;
                placed += cut;
                tops[dx + 16 * dz] = w;
                COLUMNS.increment();
            }
        }
        placed += falls(level, at, x0, z0, tops);
        for (Length l : lengths) if (l.first()) placed += shaft(level, cp, at, l);
        return placed;
    }

    /**
     * The stretches underground, a length at a time, with the cave's water at each end: a few blocks under the river's
     * would-be water, and never under the water the stretch comes out into. The river's own stretch comes out into the
     * river it feeds; a river that joins it underground comes out into its cave, at that cave's water where they meet.
     */
    private static List<Length> stretches(List<RiverNetwork.Point> sunk, double depth) {
        Map<Long, RiverNetwork.Point> byStart = new HashMap<>();
        Set<Long> ends = new HashSet<>();
        for (RiverNetwork.Point p : sunk) {
            byStart.put(key(p.x(), p.z()), p);
            ends.add(key(p.ex(), p.ez()));
        }
        List<List<RiverNetwork.Point>> mains = new ArrayList<>(), joins = new ArrayList<>();
        for (RiverNetwork.Point head : sunk) {
            if (ends.contains(key(head.x(), head.z()))) continue;
            List<RiverNetwork.Point> run = new ArrayList<>();
            for (RiverNetwork.Point p = head; p != null && run.size() < 256; p = byStart.get(key(p.ex(), p.ez()))) run.add(p);
            (head.channel() ? mains : joins).add(run);
        }
        List<Length> out = new ArrayList<>();
        for (List<RiverNetwork.Point> run : mains) add(out, run, run.get(run.size() - 1).waterEnd(), depth);
        List<Length> main = new ArrayList<>(out);
        for (List<RiverNetwork.Point> run : joins) {
            RiverNetwork.Point last = run.get(run.size() - 1);
            add(out, run, caveAt(main, last.ex(), last.ez(), last.waterEnd()), depth);
        }
        return out;
    }

    private static void add(List<Length> out, List<RiverNetwork.Point> run, double outWater, double depth) {
        for (int i = 0; i < run.size(); i++) {
            RiverNetwork.Point p = run.get(i);
            out.add(new Length(p, Math.max(outWater, p.water() - depth), Math.max(outWater, p.waterEnd() - depth), i == 0));
        }
    }

    /** The cave's water on the nearest of these lengths to a point, or {@code otherwise} where none is near. */
    private static double caveAt(List<Length> lengths, double x, double z, double otherwise) {
        double best = Double.MAX_VALUE, cave = otherwise;
        for (Length l : lengths) {
            RiverNetwork.Point p = l.p();
            double ax = p.ex() - p.x(), az = p.ez() - p.z(), len2 = ax * ax + az * az;
            double t = len2 < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - p.x()) * ax + (z - p.z()) * az) / len2));
            double dx = p.x() + ax * t - x, dz = p.z() + az * t - z, d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                cave = l.cave() + (l.caveEnd() - l.cave()) * t;
            }
        }
        return best <= 400.0 ? Math.min(cave, otherwise) : otherwise;
    }

    private static long key(float x, float z) {
        return ((long) Math.round(x * 16.0f) << 32) ^ (Math.round(z * 16.0f) & 0xFFFFFFFFL);
    }

    /**
     * One column of the cave: its water from the floor up, air over it to the roof, and where the rock over it is too thin
     * for a roof, open to the sky. The floor is rock. Returns the blocks placed, or -1 where the column is not ours to cut.
     */
    private static int cut(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int fy, int w, int top, int ground,
                           BlockState water) {
        boolean window = ground - ROOF - 1 < top;
        int cut = window ? Math.max(top, ground) : top;
        if (!clear(level, at, x, fy + 1, cut, z)) {
            REFUSED.increment();
            return -1;
        }
        BlockState air = window ? Blocks.AIR.defaultBlockState() : Blocks.CAVE_AIR.defaultBlockState();
        if (window) {
            TerrainProbe.clearGroundCover(level, x, ground, z, 2);
            WINDOWS.increment();
            if (ground < w) LOW.increment();
        }
        int placed = 0;
        for (int y = fy + 1; y <= cut; y++) {
            level.setBlock(at.set(x, y, z), y <= w ? water : air, FLAGS);
            placed++;
        }
        // The floor holds and is rock: a hollow under it is stopped, and soil the surface left on it -- mud, dirt, sand,
        // where the cave runs shallow -- is a stream's bed of stone, not a bank's.
        BlockState floor = level.getBlockState(at.set(x, fy, z));
        if (!floor.isSolidRender(level, at) || soil(floor)) {
            level.setBlock(at, Blocks.STONE.defaultBlockState(), FLAGS);
            FLOORS.increment();
        }
        return placed;
    }

    /** The swallow hole where the river sinks: a shaft from the river's water down to the cave's, the water falling down it. */
    private static int shaft(WorldGenLevel level, ChunkPos cp, BlockPos.MutableBlockPos at, Length l) {
        RiverNetwork.Point p = l.p();
        // As wide as the river's water: the river goes down it whole, and no ground is dug away round it.
        double r = p.halfWidth() + 1.0;
        int x0 = Math.max(cp.getMinBlockX(), (int) Math.floor(p.x() - r)), x1 = Math.min(cp.getMinBlockX() + 15, (int) Math.ceil(p.x() + r));
        int z0 = Math.max(cp.getMinBlockZ(), (int) Math.floor(p.z() - r)), z1 = Math.min(cp.getMinBlockZ() + 15, (int) Math.ceil(p.z() + r));
        if (x0 > x1 || z0 > z1) return 0;
        int w = (int) Math.floor(l.cave());
        int fy = w - Math.max(1, (int) Math.round(p.water() - p.bed()));
        int wTop = (int) Math.floor(p.water());
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState()
                .setValue(RiverWaterFluid.FLOW, RiverWaterFluid.wayOf(p.ex() - p.x(), p.ez() - p.z()));
        BlockState falling = water.setValue(LiquidBlock.LEVEL, 8);
        int placed = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double dx = x + 0.5 - p.x(), dz = z + 0.5 - p.z();
                if (dx * dx + dz * dz > r * r) continue;
                int ground = TerrainProbe.groundY(level, x, z);
                if (ground == Integer.MIN_VALUE || ground <= fy) continue;
                int top = Math.max(ground, wTop);
                if (!clear(level, at, x, fy + 1, top, z)) {
                    REFUSED.increment();
                    continue;
                }
                for (int y = fy + 1; y <= top; y++) {
                    level.setBlock(at.set(x, y, z), y <= w ? water : y <= wTop ? falling : Blocks.AIR.defaultBlockState(), FLAGS);
                    placed++;
                }
                BlockState floor = level.getBlockState(at.set(x, fy, z));
                if (!floor.isSolidRender(level, at) || soil(floor)) level.setBlock(at, Blocks.STONE.defaultBlockState(), FLAGS);
            }
        }
        if (placed > 0) SHAFTS.increment();
        return placed;
    }

    /**
     * Where the cave's water steps down from one column to the next, the step is hung with falling water, as the river's
     * falls are in the open; a face of standing water was left otherwise, held up by nothing.
     */
    private static int falls(WorldGenLevel level, BlockPos.MutableBlockPos at, int x0, int z0, int[] tops) {
        int placed = 0;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int w = tops[dx + 16 * dz];
                if (w == Integer.MIN_VALUE) continue;
                for (int[] s : sides) {
                    int nx = dx + s[0], nz = dz + s[1];
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                    int nw = tops[nx + 16 * nz];
                    if (nw == Integer.MIN_VALUE || nw >= w) continue;
                    BlockState falling = ModBlocks.RIVER_WATER.get().defaultBlockState().setValue(LiquidBlock.LEVEL, 8)
                            .setValue(RiverWaterFluid.FLOW, RiverWaterFluid.wayOf(s[0], s[1]));
                    for (int y = nw + 1; y <= w; y++) {
                        if (!level.getBlockState(at.set(x0 + nx, y, z0 + nz)).isAir()) break;
                        level.setBlock(at, falling, FLAGS);
                        placed++;
                    }
                    FALLS.increment();
                }
            }
        }
        return placed;
    }

    private static boolean soil(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.MUD) || s.is(Blocks.CLAY) || s.is(BlockTags.SAND)
                || s.is(Blocks.GRAVEL) || s.is(BlockTags.SNOW);
    }

    /** Whether every block from y0 to y1 here may be cut: rock or soil the world laid, no other fluid, nothing built. */
    private static boolean clear(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) {
            BlockState s = level.getBlockState(at.set(x, y, z));
            // The river's own water is no bar: the swallow hole takes it in.
            if (s.isAir() || s.is(ModBlocks.RIVER_WATER.get())) continue;
            if (!s.getFluidState().isEmpty() || s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s) || level.getBlockEntity(at) != null) {
                return false;
            }
        }
        return true;
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT,
                "karst: %d swallow holes, %d cave columns (%d open to the sky, %d of those under the water), %d falls, %d floors made rock, %d refused",
                SHAFTS.sum(), COLUMNS.sum(), WINDOWS.sum(), LOW.sum(), FALLS.sum(), FLOORS.sum(), REFUSED.sum());
    }
}
