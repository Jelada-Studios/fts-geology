package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Bringing a hot spring back after something buries it.
 *
 * <h2>Why a spring should come back at all</h2>
 * An earthquake wrecks the surface and leaves the plumbing alone. The mod's own quakes reach about
 * 24 blocks down; the heat that feeds a spring sits below that and is modelled deeper still. So a
 * quake that fills a pool with rubble has not destroyed the spring - it has blocked the outlet, and
 * an outlet that is still being pushed from below does not stay blocked.
 *
 * <p>This is what actually happens. The 1959 Hebgen Lake earthquake shook Yellowstone hard enough to
 * change the eruption interval of dozens of geysers and open several new vents. It did not switch
 * any of them off. Springs that had been quiet for years started up again within days.</p>
 *
 * <h2>Why it needed the water table first</h2>
 * Re-opening a vent in the same hole is only half of it. Where the water comes out is decided by
 * where the water table meets the ground, and after a quake has moved the ground that is not
 * necessarily where it was. {@link WaterTable} is what lets the outlet <i>move</i> rather than
 * simply reappear, which is why this was held back until the table existed: without it, renewal
 * would have been a rule with nothing underneath it.
 *
 * <h2>What it will not do</h2>
 * It never breaks a player block. A column with anything built in it is left alone permanently -
 * if somebody has roofed their spring over, that was on purpose.
 */
public final class SpringRenewal {

    private SpringRenewal() {}

    /**
     * Deepest rubble a vent can push through, in blocks.
     *
     * <p>Beyond this the old outlet is properly sealed and the water has to find somewhere else,
     * which is the interesting case rather than the failure case.</p>
     */
    private static final int MAX_REOPEN = 7;

    /** How far up a column is inspected before giving up on it. */
    private static final int COLUMN_SCAN = 24;

    private record Pending(ServerLevel level, BlockPos bed, long dueAt) {}

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();
    private static final Set<BlockPos> QUEUED = new HashSet<>();

    /** Beds that have been tried and cannot be helped, so they are not retried every minute. */
    private static final Set<BlockPos> GIVEN_UP = new HashSet<>();

    // === Public API =========================================================

    /**
     * Asks for a spring bed to be looked at, once the recovery delay has passed.
     *
     * <p>Called by the bed itself when it notices it has no water over it. That is deliberately the
     * only trigger: it catches a quake burying the pool, a player filling it in, a landslide, or
     * any future cause, without any of them having to know this class exists.</p>
     */
    public static synchronized void request(ServerLevel level, BlockPos bed) {
        if (!GeyserConfig.SPRING_RENEWAL_ENABLED.get()) return;
        BlockPos key = bed.immutable();
        if (GIVEN_UP.contains(key) || !QUEUED.add(key)) return;
        long delay = Math.max(1, GeyserConfig.SPRING_RENEWAL_DELAY_TICKS.get());
        QUEUE.add(new Pending(level, key, level.getGameTime() + delay));
    }

    /** Works through whatever is due, within the time it is given. */
    public static void drain(long nanos) {
        if (nanos <= 0) return;
        long deadline = System.nanoTime() + nanos;
        while (System.nanoTime() < deadline) {
            Pending p;
            synchronized (SpringRenewal.class) {
                p = QUEUE.peek();
                if (p == null) return;
                if (p.level().getGameTime() < p.dueAt()) return;   // the head is the earliest
                QUEUE.poll();
                QUEUED.remove(p.bed());
            }
            try {
                if (!renew(p.level(), p.bed())) {
                    synchronized (SpringRenewal.class) { GIVEN_UP.add(p.bed()); }
                }
            } catch (Exception e) {
                GeysersMod.LOGGER.warn("Spring renewal failed at {}: {}", p.bed(), e.toString());
                synchronized (SpringRenewal.class) { GIVEN_UP.add(p.bed()); }
            }
        }
    }

    /** Dropped with the other per-server state when a server stops. */
    public static synchronized void clear() {
        QUEUE.clear();
        QUEUED.clear();
        GIVEN_UP.clear();
    }

    // === The work ===========================================================

    /**
     * Tries to give one spring bed its pool back.
     *
     * @return false when nothing can be done and the bed should stop asking
     */
    private static boolean renew(ServerLevel level, BlockPos bed) {
        // The chunk has to be loaded to be edited. It was loaded when the request was made - a
        // block entity only ticks when it is - but a minute passes before the request comes due and
        // the player may well have walked away in that time.
        //
        // This has to go back on the queue rather than be dropped: the bed only asks once, when it
        // first notices it is dry, so a dropped request is a spring abandoned for the life of the
        // world with nothing in the log to say so.
        if (!level.isLoaded(bed)) {
            long delay = Math.max(1, GeyserConfig.SPRING_RENEWAL_DELAY_TICKS.get());
            synchronized (SpringRenewal.class) {
                if (QUEUED.add(bed)) QUEUE.add(new Pending(level, bed, level.getGameTime() + delay));
            }
            return true;
        }
        if (!level.getBlockState(bed).is(ModBlocks.HOT_SPRING.get())) return false;  // gone; forget it

        // Already wet again - something else fixed it, or the report was a one-off.
        if (!level.getBlockState(bed.above()).getFluidState().isEmpty()) return true;

        int overburden = overburdenAbove(level, bed);
        if (overburden == BUILT_ON) return false;          // somebody's roof: never touch it
        if (overburden >= 0 && overburden <= MAX_REOPEN) {
            return reopen(level, bed, overburden);
        }
        return relocate(level, bed);
    }

    /** Sentinel: the column above this bed contains something a player put there. */
    private static final int BUILT_ON = -1;

    /**
     * How many blocks of natural rubble sit between the bed and open air.
     *
     * <h2>Why the whole column is scanned for builds, not just the rubble</h2>
     * The obvious version stops counting at the first air block, which means it never sees a floor
     * a player has laid two blocks above the bed with a gap underneath. Measured on a synthetic
     * column, that version classified {@code air, air, player floor} as a clean re-open - and
     * re-opening it would have put water directly under somebody's floor. So player blocks are
     * looked for over the whole reachable column even though rubble is only counted up to the first
     * gap. Deliberately conservative: a column with anything built in it is never touched.
     *
     * @return the count, or {@link #BUILT_ON} if anything in the column is player-placed, or
     *         {@link Integer#MAX_VALUE} if the column is buried deeper than {@link #COLUMN_SCAN}
     */
    private static int overburdenAbove(ServerLevel level, BlockPos bed) {
        int solid = 0;
        boolean counting = true;
        for (int dy = 1; dy <= COLUMN_SCAN; dy++) {
            BlockState s = level.getBlockState(bed.above(dy));
            if (EruptionHandler.isPlayerPlaced(s)) return BUILT_ON;
            if (!counting) continue;
            if (s.isAir() || !s.getFluidState().isEmpty()) {
                counting = false;                                // open sky reached; keep scanning
                continue;
            }
            if (TerrainProbe.isVegetation(s)) continue;          // grass over the top is not rubble
            solid++;
        }
        return counting ? Integer.MAX_VALUE : solid;
    }

    /**
     * Clears the rubble and lets the pool fill again, in the hole it was always in.
     *
     * <p>The water is put back <b>recessed</b>, a block under the surrounding ground, for the same
     * reason the pool was cut that way to begin with: containment comes from the shape of the land,
     * so the restored pool cannot spill any more than the original could.</p>
     */
    private static boolean reopen(ServerLevel level, BlockPos bed, int overburden) {
        int ground = TerrainProbe.groundY(level, bed.getX(), bed.getZ());
        if (ground == Integer.MIN_VALUE) return false;

        int waterY = ground - 1;
        if (waterY < bed.getY() + 1) waterY = bed.getY() + 1;     // a very shallow bed still gets one cell
        if (waterY <= level.getSeaLevel()) return false;          // it is coastline now, not a spring

        // Check the whole column before touching any of it. Writing as it goes and bailing out
        // halfway leaves a half-flooded shaft behind, which is worse than doing nothing - the same
        // validate-then-mutate rule the vent carver had to learn.
        for (int y = bed.getY() + 1; y <= waterY + 2; y++) {
            if (EruptionHandler.isPlayerPlaced(
                    level.getBlockState(new BlockPos(bed.getX(), y, bed.getZ())))) {
                return false;
            }
        }

        for (int y = bed.getY() + 1; y <= waterY; y++) {
            level.setBlock(new BlockPos(bed.getX(), y, bed.getZ()),
                    Blocks.WATER.defaultBlockState(), 2);
        }
        // Anything left directly over the water goes, so the pool is open to the sky rather than
        // being a puddle under a lid - the exact failure the terraced springs had.
        for (int y = waterY + 1; y <= waterY + 2; y++) {
            BlockPos p = new BlockPos(bed.getX(), y, bed.getZ());
            if (level.getBlockState(p).isAir()) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        // Fresh sinter around the lip: the spring has been depositing again.
        crust(level, new BlockPos(bed.getX(), waterY, bed.getZ()));

        GeysersMod.LOGGER.info("Hot spring at {} re-opened through {} blocks of rubble", bed, overburden);
        return true;
    }

    /**
     * The old vent is sealed for good, so the water comes up somewhere else.
     *
     * <p>Somewhere else is not arbitrary: it is wherever {@link WaterTable} says the water surface
     * reaches the ground within reach of the same heat. That is how a real hydrothermal system
     * behaves when its outlet chokes - the pressure does not go away, it finds the next weakness -
     * and it is the reason the water table had to exist before this could be written.</p>
     */
    private static boolean relocate(ServerLevel level, BlockPos bed) {
        int reach = GeyserConfig.SPRING_RENEWAL_SEARCH.get();
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 < 9 || d2 > reach * reach || d2 >= bestDist) continue;
                int x = bed.getX() + dx, z = bed.getZ() + dz;
                if (!level.isLoaded(new BlockPos(x, bed.getY(), z))) continue;
                if (!WaterTable.isSpringLine(level, x, z)) continue;

                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE || g <= level.getSeaLevel() + 1) continue;
                if (g <= bed.getY()) continue;                      // the bed must stay underneath
                if (TerrainProbe.hasFluidAbove(level, x, z)) continue;
                if (!columnIsClear(level, x, g, z)) continue;

                best = new BlockPos(x, g, z);
                bestDist = d2;
            }
        }
        if (best == null) return false;

        // Seal the dead vent the way a real one seals: with its own deposit.
        level.setBlock(bed, Blocks.CALCITE.defaultBlockState(), 2);

        // A small new pool at the new outlet: floor, bed, heat, water, and a sinter lip.
        int waterY = best.getY() - 1;
        BlockPos newBed = new BlockPos(best.getX(), waterY - 1, best.getZ());
        level.setBlock(newBed.below(2), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        level.setBlock(newBed, ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
        level.setBlock(new BlockPos(best.getX(), waterY, best.getZ()),
                Blocks.WATER.defaultBlockState(), 2);
        for (int y = waterY + 1; y <= best.getY() + 1; y++) {
            BlockPos p = new BlockPos(best.getX(), y, best.getZ());
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(p))) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        crust(level, new BlockPos(best.getX(), waterY, best.getZ()));

        GeysersMod.LOGGER.info("Hot spring outlet moved from {} to {} ({} blocks)", bed, newBed,
                (int) Math.round(Math.sqrt(bed.distSqr(newBed))));
        return true;
    }

    /** True when nothing built stands in the few blocks over this ground column. */
    private static boolean columnIsClear(ServerLevel level, int x, int groundY, int z) {
        for (int dy = -2; dy <= 3; dy++) {
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(new BlockPos(x, groundY + dy, z)))) {
                return false;
            }
        }
        return true;
    }

    /** A ring of fresh sinter at the water line, so the restored pool does not look cut out. */
    private static void crust(ServerLevel level, BlockPos waterCell) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > 5) continue;
                if (level.random.nextInt(3) == 0) continue;        // patchy, not a tiled rim
                BlockPos p = new BlockPos(waterCell.getX() + dx, waterCell.getY(), waterCell.getZ() + dz);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                level.setBlock(p, ModBlocks.SINTER.get().defaultBlockState(), 2);
            }
        }
    }
}
