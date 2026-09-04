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

    /** How far above the water line the pool clears its overburden. */
    private static final int OVERBURDEN_CUT = 3;

    /** One warm bed per this many cells of pool floor. */
    private static final int CELLS_PER_BED = 12;

    /** Reads the original ground level here, for a spring that does not have one yet. */
    public static int datumFor(ServerLevel level, int x, int z) {
        int line = waterLine(level, x, z, radiusFor(1));
        return line == Integer.MIN_VALUE ? Integer.MIN_VALUE : line + 1;
    }

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
        return build(level, x, z, stage, Integer.MIN_VALUE);
    }

    /**
     * @param datumY the original ground level here, from before this spring existed. Pass
     *               {@link Integer#MIN_VALUE} to read it off the surrounding land, which is right
     *               the first time and wrong every time after.
     *
     * <h2>Why the datum has to be remembered</h2>
     * Reading the water line off a ring outside the pool is stable while a spring is <i>growing</i>,
     * because a bigger stage samples further out than a smaller one dug. It is not stable when a
     * spring is rebuilt from stage 1 after being covered: the stage 1 ring, at radius 4 to 6, lies
     * <b>inside</b> the basin stage 4 cut at radius 10, so it measures the old excavated floor and
     * sites the new pool lower. Every cover-and-recover cycle then steps down again, which is the
     * spring sinking into the ground that testing found.
     *
     * <p>So the level is measured once, when the water first reaches daylight, and kept. After that
     * it is a fact about the place rather than a reading of what the spring has done to it.</p>
     */
    public static List<BlockPos> build(ServerLevel level, int x, int z, int stage, int datumY) {
        stage = Math.max(1, Math.min(MAX_STAGE, stage));
        int radius = radiusFor(stage);

        int waterY = datumY != Integer.MIN_VALUE ? datumY - 1 : waterLine(level, x, z, radius);
        if (waterY == Integer.MIN_VALUE) return List.of();
        // A basin at or under the waterline drains into the sea the moment anything updates it.
        if (waterY <= level.getSeaLevel() + 1) return List.of();

        RetrogenHandler.clearCanopy(level, x, z, radius + 12);

        List<BlockPos> pool = fillHoles(poolCells(level, x, z, radius, waterY), waterY);
        if (pool.size() < 4) return List.of();

        BlockState crust = ModBlocks.SINTER.get().defaultBlockState();
        for (BlockPos cell : pool) {
            int cx = cell.getX(), cz = cell.getZ();
            // Open the cell to the sky, and mean it.
            //
            // This used to take out only loose cover and the spring's own crust, while the flood
            // fill admitted cells whose ground stood up to two blocks ABOVE the water line. So a
            // grass block sat on top of a pool cell and stayed there: water underneath, lid on top,
            // which is the "springs come out covered in grass" report. Anything natural over the
            // water comes out now. It is a cut, but a bounded one, and the thing that stops it
            // ratcheting is the remembered datum rather than a refusal to dig.
            for (int y = waterY + 1; y <= waterY + OVERBURDEN_CUT; y++) {
                BlockPos p = new BlockPos(cx, y, cz);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
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

        // Warm beds through the floor, not one in the middle.
        //
        // A single bed heated a 21-block pool from one point, so the edges read cold and the steam
        // all came from the centre. It also made "is this spring blocked?" a question about one
        // column, which is why covering any other part of a pool did nothing at all.
        placeBeds(level, pool, x, z, waterY);

        rim(level, pool, waterY, crust);

        // Colours by age - see paintThermalRings. Stage 1 gets only its own bare deposit.
        RetrogenHandler.paintRings(level, pool, x, z, waterY, stage);
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
     * Standing water the pool must not spread into - a lake, a river, the sea.
     *
     * <h2>Why it cannot simply refuse all water</h2>
     * It used to, and that quietly broke every spring that grows. Stage 1 leaves water on a crust
     * floor; at stage 2 those cells report standing water, so they were refused, and since they ring
     * the vent the flood fill could not get past them. Measured over a run of stages, the pool went
     * <b>13, 5, 5, 5</b> - it shrank at stage 2 and never recovered - where with the guard lifted it
     * goes 13, 49, 149, 317. Springs placed by command looked right the whole time because a command
     * builds one stage on untouched ground and never runs a sequence, which is why the screenshots
     * disagreed with the code.
     *
     * <p>The discriminator is what the water is standing on. The spring's own pool sits at its own
     * water line on the crust it laid; a lake sits on whatever the world put there. With that test
     * the pool grows normally and still stops dead at a lake pressed against it - measured at 245
     * cells with the lake untouched.</p>
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
     * Adds the holes the pool has closed around to the pool.
     *
     * <p>A flood fill leaves gaps: a knoll a couple of blocks proud, a hollow too deep, a cell that
     * failed a test. Anything enclosed by the pool is a hole rather than an edge, and leaving it out
     * had two visible consequences - the overburden pass never cleared it, so it stood in the water
     * as an island of bare dirt, and {@link #rim} treated it as a piece of shoreline and built a
     * column of crust on it, which the colour bands then painted. Those are the pillars standing in
     * the middle of a pool.</p>
     *
     * <p>Found by flooding inward from the bounding box: whatever is neither pool nor reachable from
     * outside it is enclosed.</p>
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

                // The wall. Both the water line and the block under it have to be solid, or the
                // pool leaks: a single course at the water line still lets it pour out underneath
                // wherever the ground outside falls away.
                //
                // Vegetation used to slip through here. The test at the water line asked only
                // "not air, and no fluid in it" - and a grass tuft is neither, so it was left
                // standing in contact with the water and the pool drained through it the moment
                // anything updated the block. That is the most likely cause of a spring that
                // quietly empties itself, and it would be immediate with Flowing Fluids.
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos edge = new BlockPos(x, waterY + dy, z);
                    BlockState s = level.getBlockState(edge);
                    if (EruptionHandler.isPlayerPlaced(s)) continue;
                    boolean holdsWater = !s.isAir() && s.getFluidState().isEmpty()
                            && !TerrainProbe.isVegetation(s);
                    if (holdsWater) continue;
                    level.setBlock(edge, crust, 2);
                }

                // And a broken lip above it, for looks rather than containment.
                BlockPos lip = new BlockPos(x, waterY + 1, z);
                BlockState s = level.getBlockState(lip);
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                if (!s.isAir() && !TerrainProbe.isVegetation(s)) continue;
                if (level.random.nextInt(3) != 0) continue;
                level.setBlock(lip, crust, 2);
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
        level.setBlock(at, bed, 2);
        level.setBlock(at.below(2), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        MagmaSealing.seal(level, at.below(2), false);
    }

    /**
     * Has this spring lost its pool?
     *
     * <p>Asked of the whole basin rather than of one column. The single-column test meant a spring
     * only noticed it was in trouble when the block directly over its bed was covered - fill in any
     * other part of the pool, even most of it, and nothing happened at all.</p>
     */
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
     * How much of this pool is still water.
     *
     * <h2>Two thresholds, not one</h2>
     * One threshold gave a spring only two states, and set the bar so high that dropping a patch of
     * dirt into a pool did nothing observable at all - which is what testing reported. A real spring
     * flushes a small obstruction and is stopped by a large one, so there are two: a fouled pool is
     * cleaned out and rebuilt at the age it had reached, and a buried one makes the water look for
     * another way out.
     */
    public static Health health(ServerLevel level, int x, int z, int stage, int datumY) {
        int waterY = datumY - 1;
        int radius = radiusFor(stage);
        int wet = 0, dry = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos p = new BlockPos(x + dx, waterY, z + dz);
                BlockState s = level.getBlockState(p);
                if (!s.getFluidState().isEmpty()) wet++;
                else if (!s.isAir()) dry++;
            }
        }
        int total = wet + dry;
        if (total == 0) return Health.BLOCKED;
        double wetShare = (double) wet / total;
        if (wetShare >= 0.9) return Health.FINE;
        if (wetShare >= 0.5) return Health.FOULED;
        return Health.BLOCKED;
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
