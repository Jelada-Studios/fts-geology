package com.jeladastudios.ftsgeology.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.Event;

/**
 * A river round a chunk would go over its banks in the mod's own reckoning -- a heavy rain on soaked ground -- or has
 * come back down. Only information: in a level with a {@link Hydraulics} registered the mod lays no flood water; the
 * rise comes through the sources' {@code stormFactor} (see {@link FtsGeologyApi#forcing}), and where it spreads is the
 * hydraulics' own. Posted on the server thread, in the levels the mod's floods run in.
 */
public class FloodEvent extends Event {

    private final ServerLevel level;
    private final ChunkPos chunk;
    private final boolean started;
    private final int rise;

    public FloodEvent(ServerLevel level, ChunkPos chunk, boolean started, int rise) {
        this.level = level;
        this.chunk = chunk;
        this.started = started;
        this.rise = rise;
    }

    public ServerLevel level() {
        return level;
    }

    public ChunkPos chunk() {
        return chunk;
    }

    /** True as the flood begins, false as it ends. */
    public boolean started() {
        return started;
    }

    /** Blocks the mod would have raised the river by: 1, or 2 in the heaviest rain; 0 as it ends. */
    public int rise() {
        return rise;
    }
}
