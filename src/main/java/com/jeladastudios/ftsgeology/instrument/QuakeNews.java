package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who learns a quake's size and place. Without an instrument a person feels a quake -- the P wave's jolt, then the S
 * wave's shaking, and how hard -- but cannot tell how big it was or where: that is read off a seismograph. So it is
 * told to a player standing near a seismograph that recorded it, or near a station terminal on the network, which reads
 * every seismograph that did; the others are told only what they felt.
 */
public final class QuakeNews {

    private QuakeNews() {}

    /** How near an instrument a player reads it, in blocks. */
    public static final int READ = 32;

    /**
     * Whether a player learns a quake's size and place: near a listening seismograph that heard it, or near a terminal
     * on the network when any seismograph did. Always, where the config does not ask for an instrument.
     */
    public static boolean informs(ServerLevel level, ServerPlayer p, double x, double z, double magnitude, double depthMetres) {
        if (!GeyserConfig.QUAKE_INFO_NEEDS_INSTRUMENT.get()) return true;
        double hears = GeyserConfig.SEISMOGRAPH_RANGE.get();
        BlockPos at = p.blockPosition();
        boolean heard = false;
        for (BlockPos s : SeismicStations.listening(level.dimension())) {
            if (!heardAt(s, x, z, magnitude, depthMetres, hears)) continue;
            if (s.distSqr(at) <= (double) READ * READ) return true;
            heard = true;
        }
        // A terminal on the network reads every seismograph that heard it.
        return heard && StationNetwork.terminalNear(level, at, READ);
    }

    /** Whether a seismograph here draws a quake out of its paper's own noise. */
    static boolean heardAt(BlockPos station, double x, double z, double magnitude, double depthMetres, double hears) {
        double flat = Math.hypot(station.getX() - x, station.getZ() - z);
        if (flat > hears) return false;
        double amp = Math.min(SeismicWave.CLIP_MM, SeismicWave.amplitudeMm(magnitude, SeismicWave.hypocentralMetres(flat, depthMetres)));
        return SeismicWave.detectable(amp);
    }
}
