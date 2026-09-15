package com.jeladastudios.ftsgeology.util;

import java.util.Arrays;

/**
 * A fixed table of answers worked out for a place, keyed by a long. Generator threads ask from all over at once, and
 * for the same place over and over.
 *
 * <p>The table overwrites rather than grows. A map that is emptied when it fills throws away everything it knows each
 * time the generator moves on, which is what a distant-horizon mod does all day; this keeps what is near and lets
 * the rest fall out. Each slot holds one immutable entry, so a reader sees a whole entry or an older one, never half
 * of each, and no lock is needed.</p>
 */
public final class ColumnCache<T> {

    private record Entry<T>(long key, T value) {}

    private final Entry<T>[] slots;
    private final int bits;

    @SuppressWarnings("unchecked")
    public ColumnCache(int bits) {
        this.bits = bits;
        this.slots = new Entry[1 << bits];
    }

    /** What was stored for this key, or null. */
    public T get(long key) {
        Entry<T> e = slots[slot(key)];
        return e != null && e.key == key ? e.value : null;
    }

    public void put(long key, T value) {
        slots[slot(key)] = new Entry<>(key, value);
    }

    public void clear() {
        Arrays.fill(slots, null);
    }

    /** A key for a pair of coordinates, already reduced to whatever grid the caller keeps its answers on. */
    public static long key(int a, int b) {
        return ((long) a & 0xFFFFFFFFL) | (((long) b & 0xFFFFFFFFL) << 32);
    }

    private int slot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - bits));
    }
}
