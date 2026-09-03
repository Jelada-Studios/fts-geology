package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Every earthquake the server has produced this session, so instruments can look them up.
 *
 * <h2>Why a log rather than an event</h2>
 * A seismograph out in the world is usually in an unloaded chunk when a quake happens - the player
 * is somewhere else, which is rather the point of leaving a station behind. Firing an event at the
 * moment of the quake would only ever reach the handful of stations that happened to be loaded, so
 * a station would record exactly the quakes you were standing next to anyway and nothing else.
 *
 * <p>Instead the quake is written here, and each station remembers the id of the last entry it has
 * seen. When its chunk loads it works through whatever is newer, which is what a real unattended
 * station does: the drum turns whether anyone is watching it or not.</p>
 *
 * <p>Session memory only, like the quake's own parked edits. A restart loses the back catalogue,
 * which costs some tidiness and no correctness - a station simply starts its log again.</p>
 */
public final class SeismicNetwork {

    private SeismicNetwork() {}

    /** How many events are kept for stations to catch up on. */
    private static final int HISTORY = 64;

    /**
     * One earthquake as the network knows it: where and when it happened, and how big it was.
     * A station never sees this record - it only ever sees what its own drum drew.
     */
    public record Event(long id, ResourceKey<Level> dimension, BlockPos hypocentre,
                        FaultType type, double magnitude, double depthMetres, long gameTime) {}

    private static final List<Event> LOG = new ArrayList<>();
    private static long nextId = 1L;

    /** Files an earthquake. Called from the quake itself, once, on the server thread. */
    public static synchronized void record(ServerLevel level, BlockPos at, FaultType type,
                                           double magnitude, double depthMetres) {
        LOG.add(new Event(nextId++, level.dimension(), at.immutable(), type, magnitude,
                depthMetres, level.getGameTime()));
        while (LOG.size() > HISTORY) LOG.remove(0);
    }

    /** Events in this dimension newer than {@code afterId}, oldest first. */
    public static synchronized List<Event> since(ResourceKey<Level> dimension, long afterId) {
        List<Event> out = new ArrayList<>();
        for (Event e : LOG) {
            if (e.id() > afterId && e.dimension().equals(dimension)) out.add(e);
        }
        return out;
    }

    /** Id of the newest event on file, so a freshly placed station starts from now rather than 1970. */
    public static synchronized long latestId() {
        return nextId - 1;
    }

    public static synchronized void clear() {
        LOG.clear();
        nextId = 1L;
    }
}
