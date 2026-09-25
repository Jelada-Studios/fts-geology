package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.hydrology.Karst;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.util.ColumnCache;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.RawGround;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.LongAdder;

/**
 * Sinkholes: the ground of a karst country ({@link Karst}) sagging into the rock it stands on.
 *
 * <p>Most are dolines, round hollows a few blocks deep with soil to their floor, where the water soaking down a joint has
 * carried the rock away under them grain by grain; the Dinaric karst is pocked with them by the thousand. Some are
 * collapses, where the roof of a cave gave way: steep, the rock showing in their walls. Each stands on a seeded grid,
 * worked out from the ground alone, so every chunk cuts its own share of one and a village can keep out of it.</p>
 */
public final class Dolines {

    private Dolines() {}

    /** One sinkhole: where, how wide and deep, and whether it is a collapse. */
    public record Doline(double x, double z, double radius, double depth, boolean collapse) {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** The grid the sinkholes stand on, in blocks at the normal layout, and the share of its cells that have one. */
    private static final double CELL = 36.0, SHARE = 0.55;
    /** The share of sinkholes that are collapses. */
    private static final double COLLAPSE = 0.18;
    /** How wide a sinkhole is, in blocks at the normal layout, from the smallest to the largest. */
    private static final double R_MIN = 3.5, R_SPAN = 5.5;
    /** How far from a river's bank a sinkhole keeps. */
    private static final double RIVER_CLEAR = 6.0;

    private static final LongAdder MADE = new LongAdder(), COLUMNS = new LongAdder(), REFUSED = new LongAdder();

    /** A cell's answer, with the seed it was worked out for: a village asks for the same cells over and over. */
    private record Answer(long seed, Doline doline) {}

    private static final ColumnCache<Answer> CELLS = new ColumnCache<>(14);

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        if (TfcCompat.active() || !GeologyWorld.isOwn(level.getLevel()) || !RawGround.ready()) return 0;
        if (!GeyserConfig.KARST.get()) return 0;
        double h = RiverNetwork.horizontal();
        double cell = CELL * h;
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        double reach = (R_MIN + R_SPAN) * (1.0 + 0.4 * (h - 1.0)) + 1.0;
        int i0 = (int) Math.floor((x0 - reach) / cell), i1 = (int) Math.floor((x0 + 15 + reach) / cell);
        int j0 = (int) Math.floor((z0 - reach) / cell), j1 = (int) Math.floor((z0 + 15 + reach) / cell);
        int placed = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                Doline d = inCell(i, j);
                if (d != null) placed += carve(level, cp, at, d);
            }
        }
        return placed;
    }

    /** The nearest sinkhole's middle, searching out a ring of grid cells at a time, or null within so many rings. */
    public static double[] nearest(int x, int z, int rings) {
        if (!GeyserConfig.KARST.get() || !RawGround.ready()) return null;
        double cell = CELL * RiverNetwork.horizontal();
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        for (int ring = 0; ring <= rings; ring++) {
            double best = Double.MAX_VALUE;
            double[] hit = null;
            for (int i = ci - ring; i <= ci + ring; i++) {
                for (int j = cj - ring; j <= cj + ring; j++) {
                    if (Math.max(Math.abs(i - ci), Math.abs(j - cj)) != ring) continue;
                    Doline d = inCell(i, j);
                    if (d == null) continue;
                    double dd = Math.hypot(d.x() - x, d.z() - z);
                    if (dd < best) {
                        best = dd;
                        hit = new double[]{d.x(), d.z()};
                    }
                }
            }
            if (hit != null) return hit;
        }
        return null;
    }

    /** The sinkhole whose hollow takes in this column, or null. */
    public static Doline covering(int x, int z) {
        if (!GeyserConfig.KARST.get() || !RawGround.ready()) return null;
        double cell = CELL * RiverNetwork.horizontal();
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        for (int i = ci - 1; i <= ci + 1; i++) {
            for (int j = cj - 1; j <= cj + 1; j++) {
                Doline d = inCell(i, j);
                if (d == null) continue;
                double dx = x + 0.5 - d.x(), dz = z + 0.5 - d.z();
                if (dx * dx + dz * dz <= d.radius() * d.radius()) return d;
            }
        }
        return null;
    }

    /** The sinkhole of a grid cell, if it has one: on soluble ground, clear of rivers and lakes, not on a steep slope. */
    static Doline inCell(int i, int j) {
        long seed = TerrainContext.seed();
        long key = ColumnCache.key(i, j);
        Answer known = CELLS.get(key);
        if (known != null && known.seed() == seed) return known.doline();
        Doline d = work(seed, i, j);
        CELLS.put(key, new Answer(seed, d));
        return d;
    }

    private static Doline work(long seed, int i, int j) {
        double h = RiverNetwork.horizontal();
        double cell = CELL * h;
        long hash = SeedHash.hash(seed, i, j, 0xD011EL);
        if (SeedHash.rand01(hash) >= SHARE) return null;
        double x = (i + 0.5 + (SeedHash.rand01(SeedHash.mix(hash ^ 1)) - 0.5) * 0.7) * cell;
        double z = (j + 0.5 + (SeedHash.rand01(SeedHash.mix(hash ^ 2)) - 0.5) * 0.7) * cell;
        double r = (R_MIN + R_SPAN * SeedHash.rand01(SeedHash.mix(hash ^ 3))) * (1.0 + 0.4 * (h - 1.0));
        boolean collapse = SeedHash.rand01(SeedHash.mix(hash ^ 4)) < COLLAPSE;
        double v = SeedHash.rand01(SeedHash.mix(hash ^ 5));
        double depth = collapse ? r * (0.8 + 0.3 * v) : r * (0.28 + 0.22 * v);
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        double ground = RawGround.heightAt(bx, bz);
        if (!Karst.soluble(bx, bz, ground)) return null;
        // Not on a hillside: the hollow would be a notch in the slope.
        double lo = ground, hi = ground;
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            double g = RawGround.heightAt((int) Math.floor(x + o[0] * r), (int) Math.floor(z + o[1] * r));
            lo = Math.min(lo, g);
            hi = Math.max(hi, g);
        }
        if (hi - lo > Math.max(2.0, depth)) return null;
        // Clear of the rivers and lakes, and of the dry valleys of the sunk ones.
        RiverNetwork.At a = RiverNetwork.at(bx, bz);
        if (a.distance() != Double.MAX_VALUE && (a.lake() || a.distance() <= a.halfWidth() + r + RIVER_CLEAR * h)) return null;
        return new Doline(x, z, r, depth, collapse);
    }

    private static int carve(WorldGenLevel level, ChunkPos cp, BlockPos.MutableBlockPos at, Doline d) {
        int x0 = Math.max(cp.getMinBlockX(), (int) Math.floor(d.x() - d.radius())), x1 = Math.min(cp.getMinBlockX() + 15, (int) Math.ceil(d.x() + d.radius()));
        int z0 = Math.max(cp.getMinBlockZ(), (int) Math.floor(d.z() - d.radius())), z1 = Math.min(cp.getMinBlockZ() + 15, (int) Math.ceil(d.z() + d.radius()));
        int placed = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double dx = x + 0.5 - d.x(), dz = z + 0.5 - d.z();
                double q = Math.sqrt(dx * dx + dz * dz) / d.radius();
                if (q >= 1.0) continue;
                // A bowl; a collapse stands steep to near its rim and flat at the bottom.
                double cut = d.depth() * (d.collapse() ? 1.0 - q * q * q * q : 1.0 - q * q);
                int ground = TerrainProbe.groundY(level, x, z);
                if (ground == Integer.MIN_VALUE) continue;
                int floor = ground - (int) Math.round(cut);
                if (floor >= ground) continue;
                if (!clear(level, at, x, floor + 1, ground + 1, z)) {
                    REFUSED.increment();
                    continue;
                }
                BlockState top = level.getBlockState(at.set(x, ground, z));
                BlockState under = level.getBlockState(at.set(x, ground - 1, z));
                for (int y = floor + 1; y <= ground + 1; y++) {
                    BlockState was = level.getBlockState(at.set(x, y, z));
                    if (!was.isAir()) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
                        placed++;
                    }
                }
                // The soil goes down with the ground, but a collapse's walls are the rock it fell through.
                boolean soil = top.is(BlockTags.DIRT) || top.is(BlockTags.SAND) || top.is(Blocks.GRAVEL);
                if (soil && !(d.collapse() && q > 0.5)) {
                    level.setBlock(at.set(x, floor, z), top, FLAGS);
                    if (under.is(BlockTags.DIRT) && !level.getBlockState(at.set(x, floor - 1, z)).isAir()) {
                        level.setBlock(at, under, FLAGS);
                    }
                }
                COLUMNS.increment();
            }
        }
        if (placed > 0 && Math.abs(d.x() - (cp.getMinBlockX() + 8)) <= 8 && Math.abs(d.z() - (cp.getMinBlockZ() + 8)) <= 8) MADE.increment();
        return placed;
    }

    /** Whether every block from y0 to y1 here may be taken away: what the world laid, no fluid, nothing built. */
    private static boolean clear(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) {
            BlockState s = level.getBlockState(at.set(x, y, z));
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty() || s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return false;
            if (level.getBlockEntity(at) != null) return false;
        }
        return true;
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "sinkholes: %d, %d columns, %d refused", MADE.sum(), COLUMNS.sum(), REFUSED.sum());
    }
}
