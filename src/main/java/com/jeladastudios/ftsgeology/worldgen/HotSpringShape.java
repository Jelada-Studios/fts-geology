package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a hot spring looks like, as a function of where it is and how old it is.
 *
 * <h2>Why this is one function and nothing else</h2>
 * A spring has two independent questions in it: <i>where and when does one appear</i>, and <i>what
 * does one look like</i>. Every serious bug this feature has had came from answering them in the
 * same place - a pool that rebuilt itself on a timer walked downhill, one that grew cell by cell
 * came out full of holes, one that rose a block per stage ended up on a calcite pedestal above the
 * treetops. So the shape is now a pure function of {@code (x, z, stage)} that can be called by hand
 * from {@code /geology place hotspring <stage>}, and the mineral water line underneath only decides
 * which arguments to pass.
 *
 * <h2>The rule that keeps it on the ground</h2>
 * The water line is read from the <b>untouched ground outside the pool</b> - a ring beyond the
 * stage's own radius - and the water sits one block under it. That makes a pool recessed into the
 * land, which is how the springs that looked right were built in the first place, and it means
 * calling this again at a larger stage cannot drift: the reference ring for stage 3 lies outside
 * the pool stage 2 dug, so it is still original ground.
 */
public final class HotSpringShape {

    private HotSpringShape() {}

    /** The last stage. A stage 4 spring has its microbial colour bands. */
    public static final int MAX_STAGE = 4;

    /** Pool radius per stage - diameters of 5, 9, 15 and 21 blocks. */
    private static final int[] RADIUS = {2, 4, 7, 10};

    /** How far past the pool the untouched reference ring is read. */
    private static final int REFERENCE_GAP = 2;

    public static int radiusFor(int stage) {
        return RADIUS[Math.max(1, Math.min(MAX_STAGE, stage)) - 1];
    }

    /**
     * Builds a hot spring of the given stage centred here, replacing whatever earlier stage was
     * there.
     *
     * @return the pool cells, or an empty list if this spot will not hold a spring
     */
    public static List<BlockPos> build(ServerLevel level, int x, int z, int stage) {
        stage = Math.max(1, Math.min(MAX_STAGE, stage));
        int radius = radiusFor(stage);

        int waterY = waterLine(level, x, z, radius);
        if (waterY == Integer.MIN_VALUE) return List.of();
        // A basin at or under the waterline drains into the sea the moment anything updates it.
        if (waterY <= level.getSeaLevel() + 1) return List.of();

        RetrogenHandler.clearCanopy(level, x, z, radius + 12);

        List<BlockPos> pool = poolCells(level, x, z, radius, waterY);
        if (pool.size() < 4) return List.of();

        BlockState crust = ModBlocks.SINTER.get().defaultBlockState();
        for (BlockPos cell : pool) {
            int cx = cell.getX(), cz = cell.getZ();
            // Open the cell to the sky. Only loose cover and the spring's own crust come out;
            // native rock above the water line is what bounds the pool, not something to dig.
            for (int y = waterY + 1; y <= waterY + 3; y++) {
                BlockPos p = new BlockPos(cx, y, cz);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                if (TerrainProbe.isVegetation(s) || isCrust(s)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            // Floor up to the water line where the ground has fallen away below it.
            int g = TerrainProbe.groundY(level, cx, cz);
            int from = g == Integer.MIN_VALUE ? waterY - 1 : Math.min(waterY - 1, g);
            for (int y = from; y <= waterY - 1; y++) {
                BlockPos p = new BlockPos(cx, y, cz);
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(p))) continue;
                level.setBlock(p, Blocks.CALCITE.defaultBlockState(), 2);
            }
            level.setBlock(cell, Blocks.WATER.defaultBlockState(), 2);
        }

        // The warm bed, and the heat under it, at the middle of whatever shape formed.
        BlockPos bed = bedFor(pool, x, z, waterY);
        level.setBlock(bed, ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
        level.setBlock(bed.below(2), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        MagmaSealing.seal(level, bed.below(2), false);

        rim(level, pool, waterY, crust);

        // The colours arrive last. A microbial mat needs a large, warm, settled pool; a spring that
        // opened a few days ago has not got one yet.
        if (stage >= MAX_STAGE) {
            RetrogenHandler.paintRings(level, pool, x, z, waterY);
        }
        return pool;
    }

    /**
     * Retires a spring, leaving the crust it built behind.
     *
     * <p>Used when the water finds a new way out and this outlet dies. What is left is what a dead
     * travertine terrace actually is: no water, no heat, the deposit still standing, and the
     * colours gone - the mats are alive, and they do not outlive the spring that fed them.</p>
     */
    public static void abandon(ServerLevel level, int x, int z, int stage) {
        int radius = radiusFor(stage) + 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos p = new BlockPos(x + dx, groundNear(level, x + dx, z + dz) + dy, z + dz);
                    BlockState s = level.getBlockState(p);
                    if (s.is(ModBlocks.HOT_SPRING.get())) {
                        level.setBlock(p, Blocks.CALCITE.defaultBlockState(), 2);
                    } else if (isMat(s)) {
                        level.setBlock(p, ModBlocks.SINTER.get().defaultBlockState(), 2);
                    } else if (s.is(Blocks.MAGMA_BLOCK)) {
                        level.setBlock(p, Blocks.CALCITE.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    // === Internals ==========================================================

    /**
     * The water line, read from ground the pool has not touched.
     *
     * <p>Taking it from inside the pool is what produced the ratchet: a cut pool lowers the ground,
     * and a water line derived from that ground sits lower again, so a spring rebuilt on a timer
     * walked itself 49 blocks downhill. The reference ring lies beyond the stage radius, so it is
     * still the land the spring arrived in however many times this runs.</p>
     */
    private static int waterLine(ServerLevel level, int x, int z, int radius) {
        List<Integer> heights = new ArrayList<>();
        int inner = radius + REFERENCE_GAP, outer = inner + 2;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 < inner * inner || d2 > outer * outer) continue;
                int g = TerrainProbe.groundY(level, x + dx, z + dz);
                if (g != Integer.MIN_VALUE) heights.add(g);
            }
        }
        if (heights.size() < 8) return Integer.MIN_VALUE;
        heights.sort(null);
        // The median, not the mean: one cliff in the ring must not drag the whole pool with it.
        return heights.get(heights.size() / 2) - 1;
    }

    /**
     * The pool as one connected region at the water line.
     *
     * <p>A flood fill, so the result is always a single body of water. Testing each cell on its own
     * and keeping the winners is what produced a scatter of separate holes in a calcite field.</p>
     */
    private static List<BlockPos> poolCells(ServerLevel level, int x, int z, int radius, int waterY) {
        double phaseA = level.random.nextDouble() * Math.PI * 2;
        double phaseB = level.random.nextDouble() * Math.PI * 2;

        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{x, z});
        seen.add(key(x, z));

        while (!queue.isEmpty() && out.size() < 2048) {
            int[] c = queue.poll();
            int cx = c[0], cz = c[1];
            int dx = cx - x, dz = cz - z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            // A wobbled edge, so a spring is not a disc.
            double ang = Math.atan2(dz, dx);
            double reach = radius * (1.0 + 0.22 * Math.sin(2 * ang + phaseA)
                    + 0.12 * Math.sin(3 * ang + phaseB));
            if (dist > reach) continue;

            int g = TerrainProbe.groundY(level, cx, cz);
            if (g == Integer.MIN_VALUE) continue;
            if (g > waterY + 2) continue;               // the land rises here: the pool ends
            if (waterY - g > 6) continue;               // a hollow too deep to floor
            if (TerrainProbe.hasFluidAbove(level, cx, cz) && dist > 1) continue;
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(new BlockPos(cx, g, cz)))) continue;

            out.add(new BlockPos(cx, waterY, cz));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = cx + d.getStepX(), nz = cz + d.getStepZ();
                if (seen.add(key(nx, nz))) queue.add(new int[]{nx, nz});
            }
        }
        return out;
    }

    /** A course of sinter round the lip, so the pool is held by the spring's own crust. */
    private static void rim(ServerLevel level, List<BlockPos> pool, int waterY, BlockState crust) {
        Set<Long> inside = new HashSet<>();
        for (BlockPos p : pool) inside.add(key(p.getX(), p.getZ()));

        for (BlockPos p : pool) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int x = p.getX() + d.getStepX(), z = p.getZ() + d.getStepZ();
                if (inside.contains(key(x, z))) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos edge = new BlockPos(x, waterY + dy, z);
                    BlockState s = level.getBlockState(edge);
                    if (EruptionHandler.isPlayerPlaced(s)) continue;
                    if (dy == 0 && !s.isAir() && s.getFluidState().isEmpty()) continue;
                    if (dy == 1 && !s.isAir() && !TerrainProbe.isVegetation(s)) continue;
                    if (dy == 1 && level.random.nextInt(3) != 0) continue;   // a broken lip, not a wall
                    level.setBlock(edge, crust, 2);
                }
            }
        }
    }

    /** The bed goes in the middle if the middle is wet, otherwise in a cell that is. */
    private static BlockPos bedFor(List<BlockPos> pool, int x, int z, int waterY) {
        for (BlockPos p : pool) {
            if (p.getX() == x && p.getZ() == z) return new BlockPos(x, waterY - 1, z);
        }
        BlockPos mid = pool.get(pool.size() / 2);
        return new BlockPos(mid.getX(), waterY - 1, mid.getZ());
    }

    private static int groundNear(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        return g == Integer.MIN_VALUE ? level.getSeaLevel() : g;
    }

    /** Material a spring lays down, which it is therefore allowed to take up again. */
    private static boolean isCrust(BlockState s) {
        return s.is(Blocks.CALCITE) || s.is(ModBlocks.SINTER.get()) || isMat(s);
    }

    private static boolean isMat(BlockState s) {
        return s.is(ModBlocks.MICROBIAL_MAT_GREEN.get())
                || s.is(ModBlocks.MICROBIAL_MAT_YELLOW.get())
                || s.is(ModBlocks.MICROBIAL_MAT_ORANGE.get())
                || s.is(ModBlocks.MICROBIAL_MAT_BROWN.get());
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }
}
