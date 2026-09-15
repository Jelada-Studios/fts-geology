package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the mod costs a world generation thread, part by part, summed over the chunks generated and
 * written to the log every so often. The server-thread profile shows none of this: with a far
 * renderer generating chunks by the thousand, this is where the time goes.
 */
public final class GenCost {

    private GenCost() {}

    public static final int DEEP = 0, RIDGE = 1, ORE = 2, TUBES = 3, SIGNS = 4, BASIN = 5, SOIL = 6, VOLCANO = 7, SEAMOUNT = 8;
    private static final String[] NAMES = {"deep", "ridge", "ore", "tubes", "signs", "basin", "soil", "volcano", "seamount"};

    /** Chunks between log lines. */
    private static final int EVERY = 2000;

    private static final LongAdder[] NANOS = new LongAdder[NAMES.length];
    private static final LongAdder CHUNKS = new LongAdder();
    private static final LongAdder CELLS = new LongAdder(), CELL_NANOS = new LongAdder();
    private static final AtomicLong CELL_WORST = new AtomicLong();
    private static final LongAdder HEIGHTS = new LongAdder();

    static {
        for (int i = 0; i < NANOS.length; i++) NANOS[i] = new LongAdder();
    }

    /** Adds the time one part of a chunk's geology took. */
    public static void add(int part, long nanos) {
        NANOS[part].add(nanos);
    }

    /** Adds the time one large-volcano cell took to work out. */
    public static void cell(long nanos) {
        CELLS.increment();
        CELL_NANOS.add(nanos);
        CELL_WORST.accumulateAndGet(nanos, Math::max);
    }

    /**
     * Counts one question a feature puts to the generator's height. In the mod's own world type each one runs a whole
     * column of terrain noise, so this is the number to keep small.
     */
    public static void height() {
        HEIGHTS.increment();
    }

    /** Counts a chunk whose deep geology ran, and logs the running totals every {@link #EVERY} chunks. */
    public static void chunkDone() {
        CHUNKS.increment();
        if (CHUNKS.sum() % EVERY != 0) return;
        GeysersMod.LOGGER.info("{}", summary());
    }

    /** The running totals as one line. */
    public static String summary() {
        long chunks = CHUNKS.sum(), n = Math.max(1, chunks);
        StringBuilder sb = new StringBuilder("worldgen cost over ").append(chunks).append(" chunks, microseconds a chunk: ");
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append(' ').append(NANOS[i].sum() / 1000 / n).append(", ");
        }
        return sb.append("height queries ").append(HEIGHTS.sum())
                .append(", volcano cells ").append(CELLS.sum()).append(" in ").append(CELL_NANOS.sum() / 1_000_000)
                .append(" ms, worst ").append(CELL_WORST.get() / 1_000_000).append(" ms").toString();
    }

    /** Starts every total again, to measure one stretch of generation on its own. */
    public static void reset() {
        for (LongAdder a : NANOS) a.reset();
        CHUNKS.reset();
        CELLS.reset();
        CELL_NANOS.reset();
        CELL_WORST.set(0);
        HEIGHTS.reset();
    }
}
