package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;

/**
 * Where the server is asking about the weather from (see {@link Storms}). The world keeps one rain flag, on while a
 * storm stands anywhere; a mod that asks the world whether it is raining, as Tough As Nails asks it of a player out
 * under the sky, was told so in the sun a thousand blocks from the storm. While an entity, a player or a block entity
 * ticks, its place is noted here, and the world's rain answers for that place ({@code mixin.LevelWeatherMixin}). Only
 * the place is noted: the rain is worked out only when something asks.
 *
 * <p>The server's own thread only; the client's weather is the player's already.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class RainContext {

    private RainContext() {}

    private static long[] stack = new long[16];
    private static int depth;
    private static volatile Thread server;

    /** Whether this is the server's own thread, the only one a place is noted on. */
    public static boolean onServerThread() {
        return Thread.currentThread() == server;
    }

    /** A place the weather is now asked from, until {@link #pop}. Nests: a passenger ticks inside its vehicle. */
    /** No storm at all: what the world's time of day is reckoned under, for its light (see {@link #pushClear}). */
    private static final long CLEAR = Long.MIN_VALUE;

    public static void push(int x, int z) {
        if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
        stack[depth++] = ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * The weather asked about as if no storm stood anywhere, until {@link #pop}: while the world reckons how dark its sky
     * is, which makes a day or a night of it for every mob. A storm anywhere would have darkened it everywhere -- bees
     * home, zombies unburnt and monsters about at noon, a thousand blocks from the rain.
     */
    public static void pushClear() {
        if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
        stack[depth++] = CLEAR;
    }

    /** Whether the place noted last is no place: the clear sky of {@link #pushClear}. */
    public static boolean clear() {
        return stack[depth - 1] == CLEAR;
    }

    public static void pop() {
        if (depth > 0) depth--;
    }

    /** Forgets every place noted: a tick some mod cut short can leave one standing. */
    public static void reset() {
        depth = 0;
    }

    /** Whether a place is noted. */
    public static boolean any() {
        return depth > 0;
    }

    public static int x() {
        return (int) (stack[depth - 1] >> 32);
    }

    public static int z() {
        return (int) stack[depth - 1];
    }

    /** A tick that threw out of the middle of a push leaves it standing: each server tick starts with none. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        server = Thread.currentThread();
        reset();
    }
}
