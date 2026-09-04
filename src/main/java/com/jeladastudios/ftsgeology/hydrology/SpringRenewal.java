package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.SpringSourceBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.RetrogenHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Gives an old hot spring the deep end it was generated without.
 *
 * <h2>What this used to be, and why it is so much smaller now</h2>
 * The first version of renewal did the repair itself: it counted the rubble over a spring bed and
 * either cleared that one column or hunted for somewhere else to put the bed. Testing showed why
 * that was the wrong shape. Filling a pool in gave back a single column of water - a pinhole where
 * a pool had been - because a bed is one block and a pool is dozens. And a large earthquake removed
 * the bed outright, at which point there was nothing left to ask for a repair at all.
 *
 * <p>Both symptoms had the same cause: the spring's identity lived on the surface, where the damage
 * happens. It now lives underneath, in {@link SpringSourceBlockEntity}, which is far below anything
 * a quake reaches and works its own way back up. A source notices its own pool has gone and rebuilds
 * it without being told, so the repair path no longer needs a caller.</p>
 *
 * <h2>What is left</h2>
 * Springs generated before this existed have no source under them, and a world is not regenerated
 * when the mod updates. So this backfills: when a bed reports that its pool has gone and there is no
 * source beneath it, one is seated and woken, and from then on that spring looks after itself like
 * any new one.
 */
public final class SpringRenewal {

    private SpringRenewal() {}

    /** How far down to look for an existing source before deciding there is not one. */
    private static final int SEARCH_DOWN = 40;

    private record Pending(ServerLevel level, BlockPos bed, long dueAt) {}

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();
    private static final Set<BlockPos> QUEUED = new HashSet<>();

    /** Beds that cannot be helped, so they are not retried for the life of the server. */
    private static final Set<BlockPos> GIVEN_UP = new HashSet<>();

    // === Public API =========================================================

    /**
     * Asks for a spring bed to be given a source, once the recovery delay has passed.
     *
     * <p>Called by the bed when it notices it has no water over it. On a spring that already has a
     * source this is redundant - the source is watching anyway - but it costs one lookup and it is
     * what makes an old spring catch up the first time anything disturbs it.</p>
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
                if (!backfill(p.level(), p.bed())) {
                    synchronized (SpringRenewal.class) { GIVEN_UP.add(p.bed()); }
                }
            } catch (Exception e) {
                GeysersMod.LOGGER.warn("Spring backfill failed at {}: {}", p.bed(), e.toString());
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
     * Makes sure this bed has a source under it, and wakes it.
     *
     * @return false when nothing can be done and the bed should stop asking
     */
    private static boolean backfill(ServerLevel level, BlockPos bed) {
        // It was loaded when the request was made - a block entity only ticks when it is - but the
        // delay runs for a minute and the player may have walked away. Re-queue rather than drop:
        // a bed only ever asks once, so a dropped request abandons that spring for good.
        if (!level.isLoaded(bed)) {
            long delay = Math.max(1, GeyserConfig.SPRING_RENEWAL_DELAY_TICKS.get());
            synchronized (SpringRenewal.class) {
                if (QUEUED.add(bed)) QUEUE.add(new Pending(level, bed, level.getGameTime() + delay));
            }
            return true;
        }
        if (!level.getBlockState(bed).is(ModBlocks.HOT_SPRING.get())) return false;

        BlockPos source = findSourceBelow(level, bed);
        if (source == null) {
            source = RetrogenHandler.seatSourceUnder(level, bed, 4);
            if (source == null) return false;                  // bedrock, or somebody's cellar
            GeysersMod.LOGGER.info("Backfilled a spring source at {} under the pool at {}", source, bed);
        }
        if (level.getBlockEntity(source) instanceof SpringSourceBlockEntity be) {
            be.nudge();
            return true;
        }
        return false;
    }

    /** The source belonging to this bed, if it already has one. */
    private static BlockPos findSourceBelow(ServerLevel level, BlockPos bed) {
        for (int dy = 1; dy <= SEARCH_DOWN; dy++) {
            BlockPos p = bed.below(dy);
            if (p.getY() <= level.getMinBuildHeight()) break;
            if (level.getBlockState(p).is(ModBlocks.SPRING_SOURCE.get())) return p;
        }
        return null;
    }
}
