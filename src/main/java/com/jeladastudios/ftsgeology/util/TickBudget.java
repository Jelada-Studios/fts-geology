package com.jeladastudios.ftsgeology.util;

import com.jeladastudios.ftsgeology.config.GeyserConfig;

/**
 * One wall-clock budget for everything the mod does on the server thread in a tick.
 *
 * <h2>Why this exists</h2>
 * Every long-running system here was given its own deadline, and each of them measured that
 * deadline from the moment it happened to start. There are five of them across two independent
 * {@code ServerTickEvent} handlers that knew nothing about each other, so in a bad tick the mod
 * could hand itself <b>32 milliseconds</b> of a 20 ms tick: eight for the earthquake, eight for
 * retrogen, eight for a volcano under construction, and four each for parked edits and settling.
 * Profiling a dedicated server showed exactly that - twelve percent of the server thread spent on
 * this mod while a player did nothing but load chunks, and the tick time climbing whenever a quake
 * ran.
 *
 * <p>None of those systems was individually wrong. The bug was that nobody owned the tick. So the
 * budget is opened once per tick here and everything draws from the same pot: when it is gone, the
 * rest of the mod does nothing until the next tick, however much work is queued.</p>
 *
 * <h2>Shares</h2>
 * Forge does not promise which handler runs first, so a plain "take what is left" rule would let
 * background work starve a quake simply by being scheduled earlier. Each caller therefore asks for
 * at most a fixed <em>share</em> of the whole budget: background construction can never take more
 * than a fraction of it, and what it leaves is there for the visible work whichever order they run
 * in.
 *
 * <p>Not thread safe, and does not need to be: every caller is on the server thread.</p>
 */
public final class TickBudget {

    private TickBudget() {}

    /** Nanosecond deadline for the whole mod this tick. */
    private static long deadline;

    /** Server tick this budget was opened for, so opening twice in one tick is a no-op. */
    private static long openedAt = Long.MIN_VALUE;

    /** Total nanos allowed this tick, kept so {@link #slice} can work in fractions of it. */
    private static long total;

    /**
     * Starts the budget for this tick, if it has not been started already. Safe - and expected - to
     * call from more than one tick handler.
     */
    public static void open(long tickCount) {
        if (tickCount == openedAt) return;
        openedAt = tickCount;
        total = Math.max(1L, GeyserConfig.TICK_BUDGET_MS.get()) * 1_000_000L;
        deadline = System.nanoTime() + total;
    }

    /** True once the mod has used up its slot in this tick. */
    public static boolean expired() {
        return System.nanoTime() >= deadline;
    }

    /** Nanoseconds left of the whole mod's budget. */
    public static long remaining() {
        return Math.max(0L, deadline - System.nanoTime());
    }

    /**
     * Nanoseconds this caller may spend: whatever is left, capped at {@code maxShare} of the tick's
     * total.
     *
     * <p>The cap is what makes the split fair without needing to control handler order. Background
     * work asks for a small share and therefore cannot empty the pot before a quake gets a look in,
     * even when it runs first.</p>
     *
     * @param maxShare fraction of the whole tick budget, 0..1
     */
    public static long slice(double maxShare) {
        return Math.min(remaining(), (long) (total * Math.max(0.0, Math.min(1.0, maxShare))));
    }
}
