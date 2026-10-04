package com.jeladastudios.ftsgeology.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/**
 * A region's long dry spell begins or ends. Only information: its effect on the rivers is in the sources'
 * {@code baseFactor} (see {@link FtsGeologyApi#forcing}). A region is a square of the mod's regional weather; posted on
 * the server thread for the regions the players are in.
 */
public class DroughtEvent extends Event {

    private final ServerLevel level;
    private final int minX, minZ, maxX, maxZ;
    private final boolean started;

    public DroughtEvent(ServerLevel level, int minX, int minZ, int maxX, int maxZ, boolean started) {
        this.level = level;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.started = started;
    }

    public ServerLevel level() {
        return level;
    }

    /** The region, blocks, inclusive. */
    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxZ() {
        return maxZ;
    }

    /** True as the drought begins, false as it ends. */
    public boolean started() {
        return started;
    }
}
