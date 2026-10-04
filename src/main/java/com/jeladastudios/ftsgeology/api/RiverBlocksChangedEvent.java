package com.jeladastudios.ftsgeology.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.Event;

import java.util.Collection;
import java.util.List;

/**
 * The mod has moved or taken away river water in a level where a {@link Hydraulics} is registered. It still does where
 * the ground itself moves: an earthquake lifting and dropping the ground with what stands on it, a fissure's graben, a
 * sinkhole, a cave roof falling in; and as the flood and rain-pond water it laid before the registration goes back down.
 * The water is carried with the ground, not drawn afresh, and water left hanging over nothing is taken away. Read the
 * chunks again.
 *
 * <p>Posted on {@link net.minecraftforge.common.MinecraftForge#EVENT_BUS} on the server thread, once the writes of a
 * cause have stopped for a second, with every chunk they touched. An earthquake works through its loaded chunks over a
 * few ticks and leaves the chunks not loaded till they load: those come in an event of their own then.</p>
 */
public class RiverBlocksChangedEvent extends Event {

    public enum Cause { QUAKE, FISSURE, SINKHOLE, COLLAPSE, RECEDE }

    private final ServerLevel level;
    private final List<ChunkPos> chunks;
    private final Cause cause;

    public RiverBlocksChangedEvent(ServerLevel level, Collection<ChunkPos> chunks, Cause cause) {
        this.level = level;
        this.chunks = List.copyOf(chunks);
        this.cause = cause;
    }

    public ServerLevel level() {
        return level;
    }

    /** Every chunk written in. */
    public List<ChunkPos> chunks() {
        return chunks;
    }

    public Cause cause() {
        return cause;
    }
}
