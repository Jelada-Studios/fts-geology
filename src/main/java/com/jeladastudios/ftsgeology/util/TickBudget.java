package com.jeladastudios.ftsgeology.util;

import com.jeladastudios.ftsgeology.config.GeyserConfig;

/**
 * One wall-clock budget for everything the mod does on the server thread in a tick. Separate
 * deadlines per system once let the mod take 32 ms of a 20 ms tick; now all draw from one pot, and
 * when it is gone the rest waits for the next tick.
 *
 * <p>Handler order is not guaranteed, so each caller asks for at most a fixed share, and background
 * work cannot starve a quake by running first. Server thread only.</p>
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

    /** Nanoseconds left of the whole mod's budget. */
    public static long remaining() {
        return Math.max(0L, deadline - System.nanoTime());
    }

    /**
     * Nanoseconds this caller may spend: whatever is left, capped at {@code maxShare} of the tick's
     * total, which keeps the split fair whatever order handlers run in.
     *
     * @param maxShare fraction of the whole tick budget, 0..1
     */
    public static long slice(double maxShare) {
        return Math.min(remaining(), (long) (total * Math.max(0.0, Math.min(1.0, maxShare))));
    }
}
