package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * The ground the generator would build with no river cut into it, in blocks, as a plain function of two coordinates.
 *
 * <p>It is taken once a server, the first time the noise router is wired -- the world's random state, before any
 * chunk exists -- and with every cache taken out of it. It used to be taken again from every chunk the generator
 * built, which bound it to that chunk's caches: one of them keeps the last position it answered for and the answer
 * in two separate fields, which two threads can pull apart, and a finished chunk stayed alive through it.</p>
 */
public final class RawGround {

    private RawGround() {}

    private static volatile DensityFunction offset;

    /** Holders opened and cache markers taken off: what is left reads the same on any thread and keeps nothing. */
    private static final DensityFunction.Visitor BARE = f -> f instanceof DensityFunctions.HolderHolder h
            ? h.function().value()
            : f instanceof DensityFunctions.MarkerOrMarked m ? m.wrapped() : f;

    /** Handed the raw offset as the router is wired; only the first of a server counts. */
    static void offer(DensityFunction wired) {
        if (offset != null) return;
        synchronized (RawGround.class) {
            if (offset != null) return;
            offset = wired.mapAll(BARE);
        }
        // Switched off, the network never opens: no channel is cut into the offset, no cave is pushed away from one
        // and no water is laid. Half a river is worse than none.
        if (GeyserConfig.RIVERS.get()) {
            RiverNetwork.open(RawGround::heightAt, TerrainContext.seed(), TerrainContext.params().horizontal());
        }
    }

    public static boolean ready() {
        return offset != null;
    }

    /** The ground here, in blocks: where the offset puts the surface in both world types. */
    public static double heightAt(int x, int z) {
        DensityFunction f = offset;
        return f == null ? Double.NaN : 128.0 + 128.0 * f.compute(new DensityFunction.SinglePointContext(x, 0, z));
    }

    public static void clear() {
        offset = null;
    }
}
