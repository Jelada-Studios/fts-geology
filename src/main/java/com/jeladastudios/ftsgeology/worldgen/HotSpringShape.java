package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a hot spring looks like, as a pure function of place and stage.
 *
 * <p>Where and when a spring appears is decided elsewhere; this only builds the shape, so it can be
 * called by hand from {@code /geology place hotspring <stage>}. The water sits one block under the
 * untouched ground in a ring outside the pool, so the pool is recessed into the land and building a
 * larger stage cannot drift downward.</p>
 */
public final class HotSpringShape {

    private HotSpringShape() {}

    /** The last stage. A stage 4 spring has its microbial colour bands. */
    public static final int MAX_STAGE = 4;

    /** Pool radius per stage - diameters of 5, 9, 15 and 21 blocks. */
    private static final int[] RADIUS = {2, 4, 7, 10};

    /** How far past the pool the untouched reference ring is read. */
    private static final int REFERENCE_GAP = 2;

    /** How far the pool's wobbled edge can reach, as a multiple of the radius: 1 + 0.22 + 0.12. */
    private static final double MAX_WOBBLE = 1.34;

    /** How far above the water line the pool clears its overburden. */
    private static final int OVERBURDEN_CUT = 3;

    /** One warm bed per this many cells of pool floor. */
    private static final int CELLS_PER_BED = 12;

    /** No neighbour shape updates: at the edge of the loaded area they load the next chunk on the server thread. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Reads the original ground level here, for a spring that does not have one yet. */
    public static int datumFor(ServerLevel level, int x, int z) {
        int line = waterLine(level, x, z, radiusFor(1));
        return line == Integer.MIN_VALUE ? Integer.MIN_VALUE : line + 1;
    }

    /**
     * The water line the untouched ring around a spring of this stage gives. The ring is outside
     * anything the pool reaches, so it shows what a quake did to the ground, not what the spring did.
     */
    public static int waterLineAt(ServerLevel level, int x, int z, int stage) {
        return waterLine(level, x, z, radiusFor(stage));
    }

    public static int radiusFor(int stage) {
        return RADIUS[Math.max(1, Math.min(MAX_STAGE, stage)) - 1];
    }

    /** Logs why a spring could not be built here, at debug level. */
    private static void refuse(int x, int z, int stage, String why, int datumY, int waterY,
                               int cells) {
        GeysersMod.LOGGER.debug(
                "Spring shape refused at {},{} stage {}: {} (datum {}, water {}, {} cells)",
                x, z, stage, why,
                datumY == Integer.MIN_VALUE ? "none" : datumY,
                waterY == Integer.MIN_VALUE ? "none" : waterY, cells);
    }

    /**
     * Builds a hot spring of the given stage centred here, replacing whatever earlier stage was
     * there.
     *
     * @return the pool cells, or an empty list if this spot will not hold a spring
     */
    public static List<BlockPos> build(ServerLevel level, int x, int z, int stage) {
        return build(level, x, z, stage, Integer.MIN_VALUE);
    }

    public static List<BlockPos> build(ServerLevel level, int x, int z, int stage, int datumY) {
        return build(level, x, z, stage, datumY, true);
    }

    /**
     * @param datumY     the original ground level, measured once when the water first reached
     *                   daylight. {@link Integer#MIN_VALUE} reads it off the surrounding land, which is
     *                   right only the first time: a rebuild would sample the spring's own basin and
     *                   site the pool lower each time.
     * @param clearTrees whether to take the canopy off. True when a spring arrives or grows, false on a
     *                   same-stage rebuild, so repeated repairs do not widen a ring of dead trees.
     */
    public static List<BlockPos> build(ServerLevel level, int x, int z, int stage, int datumY,
                                       boolean clearTrees) {
        stage = Math.max(1, Math.min(MAX_STAGE, stage));
        int radius = radiusFor(stage);

        int waterY = datumY != Integer.MIN_VALUE ? datumY - 1 : waterLine(level, x, z, radius);
        if (waterY == Integer.MIN_VALUE) {
            refuse(x, z, stage, "no reading of the ground", datumY, waterY, 0);
            return List.of();
        }
        // A basin at or under the waterline drains into the sea the moment anything updates it.
        if (waterY <= level.getSeaLevel() + 1) {
            refuse(x, z, stage, "at or under the waterline", datumY, waterY, 0);
            return List.of();
        }

        if (clearTrees) HotSpringSites.clearCanopy(level, x, z, radius + 12);

        List<BlockPos> pool = fillHoles(poolCells(level, x, z, radius, waterY), waterY);
        if (pool.size() < 4) {
            refuse(x, z, stage, "nowhere to put a pool", datumY, waterY, pool.size());
            return List.of();
        }

        BlockState crust = ModBlocks.SINTER.get().defaultBlockState();
        for (BlockPos cell : pool) {
            int cx = cell.getX(), cz = cell.getZ();
            // Open the cell to the sky: anything natural over the water comes out, so no grass lid is
            // left on the pool. The remembered datum keeps this from ratcheting down.
            for (int y = waterY + 1; y <= waterY + OVERBURDEN_CUT; y++) {
                BlockPos p = new BlockPos(cx, y, cz);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            // Floor up to the water line where the ground has fallen away below it.
            int g = TerrainProbe.groundY(level, cx, cz);
            int from = g == Integer.MIN_VALUE ? waterY - 1 : Math.min(waterY - 1, g);
            for (int y = from; y <= waterY - 1; y++) {
                BlockPos p = new BlockPos(cx, y, cz);
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(p))) continue;
                level.setBlock(p, Blocks.CALCITE.defaultBlockState(), FLAGS);
            }
            level.setBlock(cell, Blocks.WATER.defaultBlockState(), FLAGS);
        }

        // Warm beds spread through the floor, not one in the middle, so the whole pool steams.
        placeBeds(level, pool, x, z, waterY);

        rim(level, pool, waterY, crust);

        // Colours by age - see paintThermalRings. Stage 1 gets only its own bare deposit.
        HotSpringSites.paintRings(level, pool, x, z, waterY, stage);

        // And where the pool overflows, the streak it has laid down running away downhill.
        runoff(level, pool, waterY, stage);
        return pool;
    }

    /**
     * The sinter streak running downhill from where a mature pool (stage 3+) spills, as at Mammoth Hot
     * Springs. Carbonate comes out of the water fastest along the overflow, so the streak grows out of
     * the rim.
     */
    private static void runoff(ServerLevel level, List<BlockPos> pool, int waterY, int stage) {
        if (stage < 3 || pool.isEmpty()) return;

        // The spill point is the lowest ground just outside the rim. Pool cells go in a set first,
        // since every rim neighbour is tested against them.
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (BlockPos c : pool) cells.add(net.minecraft.core.BlockPos.asLong(c.getX(), 0, c.getZ()));

        BlockPos lip = null;
        int lowest = Integer.MAX_VALUE;
        java.util.Set<Long> tested = new java.util.HashSet<>();
        // North, south, east, west: the order decides which of two equally low lips wins.
        net.minecraft.core.Direction[] sides = {net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.WEST};
        for (BlockPos c : pool) {
            for (net.minecraft.core.Direction d : sides) {
                int nx = c.getX() + d.getStepX(), nz = c.getZ() + d.getStepZ();
                long key = net.minecraft.core.BlockPos.asLong(nx, 0, nz);
                if (cells.contains(key) || !tested.add(key)) continue;   // inside, or already looked
                int g = TerrainProbe.groundY(level, nx, nz);
                if (g == Integer.MIN_VALUE || g >= lowest) continue;
                lowest = g;
                lip = new BlockPos(nx, g, nz);
            }
        }
        if (lip == null || lowest > waterY + 1) return;    // rimmed all round: nothing spills

        int x = lip.getX(), z = lip.getZ();
        int last = lowest;
        // The heading it set off on, so it can carry straight on across level ground.
        int hx = Integer.signum(x - pool.get(0).getX());
        int hz = Integer.signum(z - pool.get(0).getZ());
        if (hx == 0 && hz == 0) hx = 1;
        java.util.Set<Long> walked = new java.util.HashSet<>();

        int width = 2;
        // Forty steps: the streak has to outrun the colour bands and halo before it shows at all.
        for (int step = 0; step < 40; step++) {
            if (!walked.add(net.minecraft.core.BlockPos.asLong(x, 0, z))) return;   // never twice
            paintRunoff(level, x, z, last, width, step);

            int[] next = flowStep(level, x, z, last, hx, hz);
            if (next == null) return;                       // the ground rises: the flow stops here
            if (last - next[1] > 2) return;                 // a cliff, not a slope
            if (TerrainProbe.hasFluidAbove(level, next[0], next[2])) return;   // reached water

            hx = Integer.signum(next[0] - x); hz = Integer.signum(next[2] - z);
            x = next[0]; last = next[1]; z = next[2];
            // Narrows as it goes, at steps scaled to the forty-step run.
            if (step == 14) width = 1;
            if (step == 30) width = 0;
        }
    }

    /**
     * The next cell the overflow runs to: downhill if there is one, otherwise straight on across level
     * ground. Minecraft slopes are staircases of flats, so a walk that needs a strictly lower neighbour
     * stops one block from the rim.
     *
     * @return {x, groundY, z}, or null if there is nowhere level or lower to go
     */
    private static int[] flowStep(ServerLevel level, int x, int z, int here, int hx, int hz) {
        int[] best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int g = TerrainProbe.groundY(level, x + dx, z + dz);
                if (g == Integer.MIN_VALUE || g > here) continue;      // never uphill
                double dist = (dx != 0 && dz != 0) ? Math.sqrt(2.0) : 1.0;
                // Drop dominates; the old heading only decides between equal options, which is what
                // keeps a run across a flat going straight instead of wandering back on itself.
                double score = (here - g) / dist * 4.0 + (dx * hx + dz * hz) * 0.5;
                if (score > bestScore) { bestScore = score; best = new int[]{x + dx, g, z + dz}; }
            }
        }
        return best;
    }

    /** One rung of the streak, across the flow. */
    private static void paintRunoff(ServerLevel level, int x, int z, int y, int width, int step) {
        for (int dx = -width; dx <= width; dx++) {
            for (int dz = -width; dz <= width; dz++) {
                if (dx * dx + dz * dz > width * width + width) continue;
                // Ragged at the sides, solid down the middle.
                if ((dx != 0 || dz != 0) && level.random.nextInt(3) == 0) continue;

                int g = TerrainProbe.groundY(level, x + dx, z + dz);
                if (g == Integer.MIN_VALUE || Math.abs(g - y) > 1) continue;
                if (TerrainProbe.hasFluidAbove(level, x + dx, z + dz)) continue;

                BlockPos at = new BlockPos(x + dx, g, z + dz);
                BlockState s = level.getBlockState(at);
                if (s.is(Blocks.BEDROCK) || !s.getFluidState().isEmpty()) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                // Leave the colour bands, the bed and the pool's calcite alone; dry crust is fair game.
                if (isMatBlock(s) || s.is(ModBlocks.HOT_SPRING.get()) || s.is(Blocks.CALCITE)) continue;

                TerrainProbe.clearVegetation(level, x + dx, g, z + dz, 2);
                // Pale carbonate, brighter than the halo it crosses, so it reads as a streak.
                BlockState put = level.random.nextInt(5) == 0
                        ? Blocks.CALCITE.defaultBlockState()
                        : level.random.nextInt(2) == 0
                            ? ModBlocks.TRAVERTINE.get().defaultBlockState()
                            : ModBlocks.SINTER.get().defaultBlockState();
                level.setBlock(at, put, FLAGS);
            }
        }
    }

    /**
     * Puts a pool's own basin back after a quake moved the floor under it: natural ground that rose into the
     * pool comes out up to six blocks over the water line, and columns that fell away are floored to just
     * under it, eight blocks at most. Only the pool's recorded columns are touched.
     *
     * @return false when nothing is recorded or one of its columns is not loaded
     */
    public static boolean restoreBasin(ServerLevel level, long[] cells, int waterY) {
        if (cells.length == 0 || waterY <= level.getMinBuildHeight() + 8) return false;
        for (long c : cells) {
            if (!level.hasChunkAt(new BlockPos(unpackX(c), waterY, unpackZ(c)))) return false;
        }
        for (long c : cells) {
            int x = unpackX(c), z = unpackZ(c);
            for (int y = waterY; y <= waterY + 6; y++) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                if (s.is(Blocks.BEDROCK) || s.hasBlockEntity() || EruptionHandler.isPlayerPlaced(s)) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            int g = TerrainProbe.groundY(level, x, z);
            if (g == Integer.MIN_VALUE || g >= waterY - 1) continue;
            for (int y = Math.max(g + 1, waterY - 8); y <= waterY - 1; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(p))) continue;
                level.setBlock(p, Blocks.CALCITE.defaultBlockState(), FLAGS);
            }
        }
        return true;
    }

    // === Internals ==========================================================

    /**
     * The water line: one below the median ground of a ring outside the pool. The ring starts past the
     * furthest the pool's wobbled edge can reach ({@link #MAX_WOBBLE}), so rebuilding a pool can never
     * lower the ground its own datum is measured from.
     */
    private static int waterLine(ServerLevel level, int x, int z, int radius) {
        List<Integer> heights = new ArrayList<>();
        int inner = (int) Math.ceil(radius * MAX_WOBBLE) + REFERENCE_GAP, outer = inner + 2;
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

    /** The pool as one connected region at the water line: a flood fill to a wobbled edge. */
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
            if (dist > 1 && foreignWater(level, cx, cz, waterY)) continue;
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(new BlockPos(cx, g, cz)))) continue;

            out.add(new BlockPos(cx, waterY, cz));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = cx + d.getStepX(), nz = cz + d.getStepZ();
                if (seen.add(key(nx, nz))) queue.add(new int[]{nx, nz});
            }
        }
        return out;
    }

    /**
     * Standing water the pool must not spread into: a lake, a river, the sea. Told from the spring's own
     * water by what it stands on, so a growing spring can flood past its previous stage's pool.
     */
    private static boolean foreignWater(ServerLevel level, int x, int z, int waterY) {
        if (!TerrainProbe.hasFluidAbove(level, x, z)) return false;
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return true;
        boolean ourLine = g + 1 == waterY;
        boolean ourFloor = isCrust(level.getBlockState(new BlockPos(x, g, z)));
        return !(ourLine && ourFloor);
    }

    /**
     * Adds the cells the pool encloses to the pool, so no island of dirt or pillar of crust is left
     * standing in the water. Found by flooding inward from the bounding box.
     */
    private static List<BlockPos> fillHoles(List<BlockPos> pool, int waterY) {
        if (pool.isEmpty()) return pool;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Long> inPool = new HashSet<>();
        for (BlockPos p : pool) {
            inPool.add(key(p.getX(), p.getZ()));
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        minX--; maxX++; minZ--; maxZ++;

        Set<Long> outside = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            queue.add(new int[]{x, minZ}); queue.add(new int[]{x, maxZ});
        }
        for (int z = minZ; z <= maxZ; z++) {
            queue.add(new int[]{minX, z}); queue.add(new int[]{maxX, z});
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int x = c[0], z = c[1];
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            long k = key(x, z);
            if (inPool.contains(k) || !outside.add(k)) continue;
            queue.add(new int[]{x + 1, z}); queue.add(new int[]{x - 1, z});
            queue.add(new int[]{x, z + 1}); queue.add(new int[]{x, z - 1});
        }

        List<BlockPos> full = new ArrayList<>(pool);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long k = key(x, z);
                if (!inPool.contains(k) && !outside.contains(k)) {
                    full.add(new BlockPos(x, waterY, z));
                }
            }
        }
        return full;
    }

    /** A course of sinter round the lip, so the pool is held by the spring's own crust. */
    private static void rim(ServerLevel level, List<BlockPos> pool, int waterY, BlockState crust) {
        Set<Long> inside = new HashSet<>();
        for (BlockPos p : pool) inside.add(key(p.getX(), p.getZ()));

        for (BlockPos p : pool) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int x = p.getX() + d.getStepX(), z = p.getZ() + d.getStepZ();
                if (inside.contains(key(x, z))) continue;

                // The wall: both the water line and the block under it must hold water, or the pool
                // leaks underneath. Vegetation does not hold water.
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos edge = new BlockPos(x, waterY + dy, z);
                    BlockState s = level.getBlockState(edge);
                    if (EruptionHandler.isPlayerPlaced(s)) continue;
                    boolean holdsWater = !s.isAir() && s.getFluidState().isEmpty()
                            && !TerrainProbe.isVegetation(s);
                    if (holdsWater) continue;
                    level.setBlock(edge, crust, FLAGS);
                }

                // And a broken lip above it, for looks rather than containment.
                BlockPos lip = new BlockPos(x, waterY + 1, z);
                BlockState s = level.getBlockState(lip);
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                if (!s.isAir() && !TerrainProbe.isVegetation(s)) continue;
                if (level.random.nextInt(3) != 0) continue;
                level.setBlock(lip, crust, FLAGS);
            }
        }
    }

    /** One warm bed per {@link #CELLS_PER_BED} of pool floor, and always one at the vent. */
    private static void placeBeds(ServerLevel level, List<BlockPos> pool, int x, int z, int waterY) {
        BlockState bed = ModBlocks.HOT_SPRING.get().defaultBlockState();

        BlockPos vent = new BlockPos(x, waterY - 1, z);
        boolean ventInPool = pool.stream().anyMatch(p -> p.getX() == x && p.getZ() == z);
        if (ventInPool) seatBed(level, vent, bed);

        int wanted = Math.max(1, pool.size() / CELLS_PER_BED);
        int placed = ventInPool ? 1 : 0;
        for (int i = 0; i < pool.size() && placed < wanted; i++) {
            // Spread over the list rather than clustered, so the heat is spread over the floor.
            BlockPos cell = pool.get((i * 7 + 3) % pool.size());
            if (cell.getX() == x && cell.getZ() == z) continue;
            seatBed(level, new BlockPos(cell.getX(), waterY - 1, cell.getZ()), bed);
            placed++;
        }
    }

    private static void seatBed(ServerLevel level, BlockPos at, BlockState bed) {
        if (EruptionHandler.isPlayerPlaced(level.getBlockState(at))) return;
        level.setBlock(at, bed, FLAGS);
        level.setBlock(at.below(2), Blocks.MAGMA_BLOCK.defaultBlockState(), FLAGS);
        MagmaSealing.seal(level, at.below(2), false);
    }

    /** What state a spring's pool is in. */
    public enum Health {
        /** Wet and whole. */
        FINE,
        /** Something has been dropped in it, but the outlet is clear. The spring flushes it. */
        FOULED,
        /** Buried past the point where the outlet can clear itself. The water goes elsewhere. */
        BLOCKED
    }

    /**
     * How much of the pool this spring actually built is still water: FINE from 90%, FOULED (flushed
     * and rebuilt) from 50%, otherwise BLOCKED. Measured against the recorded cells rather than a disc,
     * which would count the pool's own rim as dry.
     *
     * @param cells  the pool cells from the last successful {@link #build}, packed by {@link #key}
     * @param waterY the water line those cells sit at
     */
    public static Health health(ServerLevel level, long[] cells, int waterY) {
        if (cells == null || cells.length == 0) return Health.BLOCKED;
        int wet = 0;
        for (long c : cells) {
            BlockPos p = new BlockPos(unpackX(c), waterY, unpackZ(c));
            if (!level.getBlockState(p).getFluidState().isEmpty()) wet++;
        }
        double wetShare = (double) wet / cells.length;
        if (wetShare >= 0.9) return Health.FINE;
        if (wetShare >= 0.5) return Health.FOULED;
        return Health.BLOCKED;
    }

    /** Packs pool cells for storage on the block entity that owns them. */
    public static long[] pack(List<BlockPos> pool) {
        long[] out = new long[pool.size()];
        for (int i = 0; i < out.length; i++) out[i] = key(pool.get(i).getX(), pool.get(i).getZ());
        return out;
    }

    /** Material a spring lays down, including its warm beds, which it may therefore take up again. */
    static boolean isCrust(BlockState s) {
        return s.is(Blocks.CALCITE) || s.is(ModBlocks.SINTER.get())
                || s.is(ModBlocks.HOT_SPRING.get()) || s.is(Blocks.MAGMA_BLOCK) || isMat(s);
    }

    /** Exposed so a spring can tell its own water from a lake that reaches the same column. */
    public static boolean isMatBlock(BlockState s) {
        return isMat(s);
    }

    /** True for a spring's colour bands, warm bed, or calcite pool floor under water. */
    public static boolean isSpringGround(BlockState s, boolean underWater) {
        return isMat(s) || s.is(ModBlocks.HOT_SPRING.get()) || (underWater && s.is(Blocks.CALCITE));
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

    public static int unpackX(long k) {
        return (int) (k >> 32);
    }

    public static int unpackZ(long k) {
        return (int) k;
    }
}
