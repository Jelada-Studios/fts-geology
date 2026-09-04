package com.jeladastudios.ftsgeology.quake;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Ground that is still moving, and the geothermal machinery that has to leave it alone.
 *
 * <h2>Why this exists</h2>
 * A quake takes a minute or two to apply and its debris goes on settling afterwards. Through all of
 * that, every geothermal feature in the corridor was trying to repair itself: springs rebuilding
 * pools into ground that was about to move again, volcanoes re-laying cones that were still being
 * cut apart. The result was not a spring that survived a quake, it was a spring that thrashed for
 * two minutes and left wreckage - which is exactly what testing found afterwards, a field of ruins
 * with nothing rebuilt in it.
 *
 * <p>Standing still is also the honest answer geologically. The deep plumbing is what survives an
 * earthquake; the surface expression does not, and does not try to. Hebgen Lake in 1959 rearranged
 * the vents across Yellowstone over hours and days, not while the ground was still shaking.</p>
 *
 * <h2>The release is the delicate part</h2>
 * A zone is <b>not</b> released when the rupture finishes. {@link Weathering} is still bringing down
 * everything the quake left hanging, and a pool rebuilt before that has finished is immediately
 * fouled by the debris landing in it - which would leave this whole mechanism looking like it had
 * done nothing. So a zone stays shut until the settling queue for it is empty, and then for a grace
 * period after that.
 */
public final class QuakeQuiet {

    private QuakeQuiet() {}

    /**
     * How long after the ground stops moving before anything may rebuild, in ticks.
     *
     * <p>Long enough that the last few blocks of talus have landed and the fluids have found their
     * level, short enough that a player who watched the quake sees the spring come back rather than
     * wandering off first.</p>
     */
    private static final int GRACE_TICKS = 600;

    /** Margin around the rupture, so features just outside the edits are covered too. */
    private static final int MARGIN = 48;

    /** Still open, i.e. the quake has not finished applying. */
    private static final long RUNNING = Long.MAX_VALUE;

    private static final class Zone {
        final ResourceKey<Level> dimension;
        final int x;
        final int z;
        final long radiusSq;
        long until;

        Zone(ResourceKey<Level> dimension, int x, int z, int radius) {
            this.dimension = dimension;
            this.x = x;
            this.z = z;
            this.radiusSq = (long) radius * radius;
            this.until = RUNNING;
        }

        boolean covers(ResourceKey<Level> dim, int px, int pz) {
            if (!dimension.equals(dim)) return false;
            long dx = px - x, dz = pz - z;
            return dx * dx + dz * dz <= radiusSq;
        }
    }

    private static final List<Zone> ZONES = new ArrayList<>();

    /** Opens a zone as a quake begins. Held open until {@link #settling} releases it. */
    public static synchronized void open(ServerLevel level, BlockPos epicentre, double ruptureLength) {
        int radius = (int) Math.round(ruptureLength / 2.0) + MARGIN;
        ZONES.add(new Zone(level.dimension(), epicentre.getX(), epicentre.getZ(), radius));
    }

    /**
     * The rupture has finished applying. The zone now waits on the debris.
     *
     * <p>Called with the settling still queued, so this sets no deadline yet - {@link #tick} does
     * that once the queue for this level is empty.</p>
     */
    public static synchronized void settling(ServerLevel level, BlockPos epicentre) {
        for (Zone z : ZONES) {
            if (z.until == RUNNING && z.covers(level.dimension(), epicentre.getX(), epicentre.getZ())) {
                z.until = RUNNING - 1;      // finished rupturing, still settling
                return;
            }
        }
    }

    /**
     * Retires zones whose debris has landed and whose grace period has run out.
     *
     * @param settled whether the settling queue for this level is empty
     */
    public static synchronized void tick(ServerLevel level, boolean settled) {
        long now = level.getGameTime();
        for (Zone z : ZONES) {
            if (z.until == RUNNING - 1 && settled) z.until = now + GRACE_TICKS;
        }
        ZONES.removeIf(z -> z.until < RUNNING - 1 && now >= z.until);
    }

    /**
     * Is this column inside ground that is still moving or still settling?
     *
     * <p>Cheap: a distance check over a list that is empty almost all the time and holds a couple of
     * entries at worst.</p>
     */
    public static synchronized boolean isQuiet(ServerLevel level, int x, int z) {
        if (ZONES.isEmpty()) return false;
        for (Zone zone : ZONES) {
            if (zone.covers(level.dimension(), x, z)) return true;
        }
        return false;
    }

    public static boolean isQuiet(ServerLevel level, BlockPos pos) {
        return isQuiet(level, pos.getX(), pos.getZ());
    }

    /**
     * When the zone covering this column releases, or {@link Long#MIN_VALUE} if none does.
     *
     * <p>Used as a one-shot stamp: a feature records the release it has already reacted to, so it
     * re-sites itself once per quake rather than on a timer. That distinction is the whole safety
     * margin on re-measuring a spring's datum.</p>
     */
    public static synchronized long releaseAt(ServerLevel level, int x, int z) {
        long latest = Long.MIN_VALUE;
        for (Zone zone : ZONES) {
            if (zone.covers(level.dimension(), x, z)) latest = Math.max(latest, zone.until);
        }
        return latest;
    }

    /** Drops every zone. For {@code /geology quake cancel}, and for world unload. */
    public static synchronized void clear() {
        ZONES.clear();
    }
}
