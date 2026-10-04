package com.jeladastudios.ftsgeology.api;

import net.minecraftforge.eventbus.api.Event;

/**
 * A peal of the mod's thunder is played on the client: the flash it belongs to, how long after the flash it is heard,
 * and how loud. Posted on {@link net.minecraftforge.common.MinecraftForge#EVENT_BUS} on the client thread, as the sound
 * starts, so an addon can duck its other sounds under it for a moment. Client only.
 */
public class ThunderHeardEvent extends Event {

    private final double x, y, z;
    private final int delay;
    private final float strength;

    public ThunderHeardEvent(double x, double y, double z, int delay, float strength) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.delay = delay;
        this.strength = strength;
    }

    /** Where the flash was. */
    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** Ticks between the flash and this: the sound's travel time, three seconds a kilometre. */
    public int delay() {
        return delay;
    }

    /** How loud it is played, 0 to about 1. */
    public float strength() {
        return strength;
    }
}
