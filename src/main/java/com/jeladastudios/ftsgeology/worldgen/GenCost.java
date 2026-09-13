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

    public static final int DEEP = 0, RIDGE = 1, ORE = 2, SIGNS = 3, BASIN = 4, SOIL = 5, VOLCANO = 6, SEAMOUNT = 7;
    private static final String[] NAMES = {"deep", "ridge", "ore", "signs", "basin", "soil", "volcano", "seamount"};

    /** Chunks between log lines. */
    private static final int EVERY = 2000;

    private static final LongAdder[] NANOS = new LongAdder[NAMES.length];
    private static final LongAdder CHUNKS = new LongAdder();
    private static final LongAdder CELLS = new LongAdder(), CELL_NANOS = new LongAdder();
    private static final AtomicLong CELL_WORST = new AtomicLong();

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

    /** Counts a chunk whose deep geology ran, and logs the running totals every {@link #EVERY} chunks. */
    public static void chunkDone() {
        CHUNKS.increment();
        long n = CHUNKS.sum();
        if (n % EVERY != 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]).append(' ').append(NANOS[i].sum() / 1000 / n).append(", ");
        }
        long cells = CELLS.sum();
        GeysersMod.LOGGER.info("worldgen cost over {} chunks, microseconds a chunk: {}volcano cells {} in {} ms, worst {} ms",
                n, sb, cells, CELL_NANOS.sum() / 1_000_000, CELL_WORST.get() / 1_000_000);
    }
}
