package com.jeladastudios.ftsgeology.api;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.eventbus.api.Event;

/**
 * The mod has finished the ground of a chunk: its fumarole fields, geothermal basin floor, soil, rift steps, snow cover
 * and river banks are laid. Posted on {@link net.minecraftforge.common.MinecraftForge#EVENT_BUS}, once a chunk, both
 * when the chunk is generated and when an older chunk is brought up to date.
 *
 * <p>An addon may lay its own ground over the mod's here. Write only inside {@link #chunk()}: at generation the level
 * is the generator's region, and a block written outside the chunk may reach one that is not ready, or be lost. It may
 * be posted from a world generation thread; a listener has to be safe there, and quick.</p>
 */
public class GeologySurfacePaintedEvent extends Event {

    private final LevelAccessor level;
    private final ChunkPos chunk;

    public GeologySurfacePaintedEvent(LevelAccessor level, ChunkPos chunk) {
        this.level = level;
        this.chunk = chunk;
    }

    /** The level to read and write: the generator's region at generation, the server level when brought up to date. */
    public LevelAccessor level() {
        return level;
    }

    /** The chunk whose ground is done. */
    public ChunkPos chunk() {
        return chunk;
    }
}
