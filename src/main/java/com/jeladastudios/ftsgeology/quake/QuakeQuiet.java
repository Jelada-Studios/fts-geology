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
 * two minutes and left wreckage.
 *
 * <p>Standing still is also the honest answer geologically. The deep plumbing is what survives an
 * earthquake; the surface expression does not. Hebgen Lake in 1959 rearranged the vents across
 * Yellowstone over hours and days, not while the ground was still shaking.</p>
 *
 * <h2>A zone is released, not deleted - and it is stamped with a number, not a time</h2>
 * The first version deleted a zone when its grace period ran out, and that made the window in which
 * a spring could react <b>exactly zero ticks wide</b>: while the zone existed {@link #isQuiet}
 * refused to let the spring run at all, and the moment it was deleted {@link #released} had nothing
 * left to report. Measured over two M9.5 quakes in one session: zero springs re-sited.
 *
 * <p>So a finished zone stays in the list as <i>released</i>, and carries a sequence number. A
 * feature records the number of the last quake it has already answered for. That matters because
 * a rupture is a thousand blocks long and its corridor is a couple of hundred wide, so most of the
 * springs it wrecks are in <b>unloaded chunks</b> when it finishes - with a deadline they would
 * simply miss their turn, and with a number they pick it up whenever the player next goes there.</p>
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

    /**
     * The longest a zone may stay quiet, whatever else is happening, in ticks.
     *
     * <h2>Why there has to be a ceiling</h2>
     * The first version released a zone only when {@code Weathering} reported no settling left over
     * its ground. Settling for an unloaded chunk is <b>parked</b>, and parked work only resumes when
     * somebody walks there - so a rupture a thousand blocks long, most of it never revisited, left
     * work outstanding for ever. The zone never released, {@link #isQuiet} stayed true, and every
     * spring inside a 548-block radius was frozen: no rebuild, no health check, no climb, nothing.
     * Testing reported exactly that - a destroyed spring stayed destroyed.
     *
     * <p>So the ceiling is not a tuning knob, it is the property that makes this fail safely. A
     * mistake in the release test now costs a pool rebuilt slightly early, which the spring's own
     * health check cleans up. Before, it cost the spring permanently.</p>
     */
    private static final int MAX_QUIET_TICKS = 1200;      // one minute

    /** How long a released zone is remembered, so features in unloaded chunks still get their turn. */
    private static final long MEMORY_TICKS = 72_000L;   // an hour of game time

    /** Hard ceiling on remembered zones, so a very long-lived world cannot grow this without bound. */
    private static final int MAX_ZONES = 64;

    private enum Phase {
        /** The rupture is still being applied. */
        RUPTURING,
        /** Applied, but the debris is still coming down. */
        SETTLING,
        /** Debris landed; running out the grace period before anything is allowed to build. */
        GRACE,
        /** Quiet over. Features in here may act, once, and stamp this zone's sequence number. */
        RELEASED
    }

    private static final class Zone {
        final long sequence;
        final ResourceKey<Level> dimension;
        final int x;
        final int z;
        final int radius;
        final long radiusSq;
        Phase phase = Phase.RUPTURING;
        /** Game time the grace period ends, and then the time the zone was released. */
        long graceEnds;
        long releasedAt;
        /** Game time this zone must release by, no matter what. See MAX_QUIET_TICKS. */
        long mustReleaseBy = Long.MAX_VALUE;

        Zone(long sequence, ResourceKey<Level> dimension, int x, int z, int radius) {
            this.sequence = sequence;
            this.dimension = dimension;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.radiusSq = (long) radius * radius;
        }

        boolean covers(ResourceKey<Level> dim, int px, int pz) {
            if (!dimension.equals(dim)) return false;
            long dx = px - x, dz = pz - z;
            return dx * dx + dz * dz <= radiusSq;
        }
    }

    private static final List<Zone> ZONES = new ArrayList<>();
    private static long nextSequence = 1L;

    /** Opens a zone as a quake begins. Held until the ground and its debris are both still. */
    public static synchronized void open(ServerLevel level, BlockPos epicentre, double ruptureLength) {
        int radius = (int) Math.round(ruptureLength / 2.0) + MARGIN;
        ZONES.add(new Zone(nextSequence++, level.dimension(),
                epicentre.getX(), epicentre.getZ(), radius));
    }

    /** The rupture has finished applying. The zone now waits on the debris. */
    public static synchronized void settling(ServerLevel level, BlockPos epicentre) {
        for (Zone z : ZONES) {
            if (z.phase == Phase.RUPTURING
                    && z.covers(level.dimension(), epicentre.getX(), epicentre.getZ())) {
                z.phase = Phase.SETTLING;
                // The clock starts the moment the ground stops moving, and it is what actually
                // releases the zone. Waiting on the settling queue was the design that froze
                // everything; the queue is now only allowed to hold the zone WITHIN this deadline.
                z.mustReleaseBy = level.getGameTime() + MAX_QUIET_TICKS;
                return;
            }
        }
    }

    /**
     * Releases zones whose own debris has landed, and forgets very old ones.
     *
     * <p>Each zone asks about <b>its own ground</b>. The first version asked
     * {@code Weathering.settled()}, which is one queue for the whole server, so a quake anywhere -
     * even in another dimension - held every other zone open, and parked columns waiting on an
     * unloaded chunk were not counted at all.</p>
     */
    public static synchronized void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Zone z : ZONES) {
            if (!z.dimension.equals(level.dimension())) continue;   // this level's clock only
            switch (z.phase) {
                case SETTLING -> {
                    // Settling that is actually queued may delay the zone; parked settling may
                    // not, because parked work waits on a chunk visit that may never come. Either
                    // way the deadline wins.
                    if (now < z.mustReleaseBy
                            && Weathering.pendingNear(level, z.x, z.z, z.radius)) continue;
                    z.phase = Phase.GRACE;
                    z.graceEnds = now + GRACE_TICKS;
                }
                case GRACE -> {
                    if (now < z.graceEnds) continue;
                    z.phase = Phase.RELEASED;
                    z.releasedAt = now;
                    // The ground is finally still, so the rivers in it can start adjusting to the
                    // level it has stopped at. Called from the one place a zone can become released,
                    // which makes it exactly once per quake by construction rather than by a stamp.
                    com.jeladastudios.ftsgeology.hydrology.Knickpoint.afterQuake(
                            level, new BlockPos(z.x, level.getSeaLevel(), z.z), z.radius * 2.0);
                }
                default -> { }
            }
        }
        // Forget the oldest once they are well past release.
        ZONES.removeIf(z -> z.phase == Phase.RELEASED
                && z.dimension.equals(level.dimension())
                && now - z.releasedAt > MEMORY_TICKS);
        // And cap the list - but only ever by dropping a zone that has already been released.
        // Removing index 0 unconditionally could evict a zone still rupturing or settling, which
        // would strand every feature inside it with no release to react to.
        while (ZONES.size() > MAX_ZONES) {
            int oldest = -1;
            for (int i = 0; i < ZONES.size(); i++) {
                if (ZONES.get(i).phase != Phase.RELEASED) continue;
                if (oldest < 0 || ZONES.get(i).sequence < ZONES.get(oldest).sequence) oldest = i;
            }
            if (oldest < 0) break;      // all still live: keep them all rather than strand one
            ZONES.remove(oldest);
        }
    }

    /**
     * Is this column inside ground that is still moving or still settling?
     *
     * <p>A released zone answers <b>false</b> - that is the whole point of keeping it. It stays in
     * the list so {@link #released} can still report it, not to go on silencing anything.</p>
     */
    public static synchronized boolean isQuiet(ServerLevel level, int x, int z) {
        if (ZONES.isEmpty()) return false;
        for (Zone zone : ZONES) {
            if (zone.phase == Phase.RELEASED) continue;
            if (zone.covers(level.dimension(), x, z)) return true;
        }
        return false;
    }

    public static boolean isQuiet(ServerLevel level, BlockPos pos) {
        return isQuiet(level, pos.getX(), pos.getZ());
    }

    /**
     * The sequence number of the newest <b>released</b> quake over this column, or 0 if none.
     *
     * <p>Running and settling zones are deliberately invisible here. Reporting them was a real bug:
     * the old version returned the maximum {@code until} over every covering zone, and a running
     * zone's was {@link Long#MAX_VALUE}, so a second quake arriving over the same ground made a
     * spring stamp itself with a number nothing could ever exceed - and it never re-sited again,
     * for that quake or any later one.</p>
     *
     * <p>Compare against the feature's own stamp: greater means there is a quake here it has not
     * answered for yet. Because it is a count rather than a clock, a spring in a chunk that stays
     * unloaded for hours still gets its turn when the chunk comes back.</p>
     */
    public static synchronized long released(ServerLevel level, int x, int z) {
        long newest = 0L;
        for (Zone zone : ZONES) {
            if (zone.phase != Phase.RELEASED) continue;
            if (!zone.covers(level.dimension(), x, z)) continue;
            newest = Math.max(newest, zone.sequence);
        }
        return newest;
    }

    /** Drops every zone. For {@code /geology quake cancel}, and for world unload. */
    public static synchronized void clear() {
        ZONES.clear();
    }
}
