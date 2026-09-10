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
 * <p>While a quake applies and its debris settles, springs and volcanoes do not repair themselves:
 * rebuilding into moving ground only leaves wreckage. The deep plumbing survives, and the surface
 * expression comes back afterwards.</p>
 *
 * <p>A finished zone stays in the list as released, with a sequence number. A feature records the
 * number of the last quake it answered for, so a spring in a chunk that was unloaded at release
 * still gets its turn when the chunk comes back.</p>
 */
public final class QuakeQuiet {

    private QuakeQuiet() {}

    /** Ticks after the ground stops before anything may rebuild: time for talus and fluids to settle. */
    private static final int GRACE_TICKS = 600;

    /** Margin around the rupture, so features just outside the edits are covered too. */
    private static final int MARGIN = 48;

    /**
     * The longest a zone may stay quiet, in ticks. Settling in unloaded chunks is parked and may never
     * resume, so without a ceiling a zone could freeze every spring in it for good; with it, a mistake
     * costs at most a pool rebuilt early.
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
                // The clock starts when the ground stops, and it is what releases the zone.
                z.mustReleaseBy = level.getGameTime() + MAX_QUIET_TICKS;
                return;
            }
        }
    }

    /**
     * Releases zones whose own debris has landed, and forgets very old ones. Each zone asks about its
     * own ground, not the server-wide queue.
     */
    public static synchronized void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Zone z : ZONES) {
            if (!z.dimension.equals(level.dimension())) continue;   // this level's clock only
            switch (z.phase) {
                case SETTLING -> {
                    // Queued settling may delay the zone, parked settling may not; the deadline wins.
                    if (now < z.mustReleaseBy
                            && (Weathering.pendingNear(level, z.x, z.z, z.radius)
                                || CaveCollapse.pendingNear(level, z.x, z.z, z.radius))) continue;
                    z.phase = Phase.GRACE;
                    z.graceEnds = now + GRACE_TICKS;
                }
                case GRACE -> {
                    if (now < z.graceEnds) continue;
                    z.phase = Phase.RELEASED;
                    z.releasedAt = now;
                }
                default -> { }
            }
        }
        // Forget the oldest once they are well past release.
        ZONES.removeIf(z -> z.phase == Phase.RELEASED
                && z.dimension.equals(level.dimension())
                && now - z.releasedAt > MEMORY_TICKS);
        // Cap the list, only ever dropping a zone already released.
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

    /** Is this column inside ground still moving or settling? A released zone answers false. */
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
     * The sequence number of the newest released quake over this column, or 0. Running and settling
     * zones are invisible here. Greater than a feature's own stamp means a quake it has not answered
     * for yet; a count rather than a clock, so unloaded chunks catch up later.
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
