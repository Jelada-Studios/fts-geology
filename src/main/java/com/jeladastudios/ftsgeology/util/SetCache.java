package com.jeladastudios.ftsgeology.util;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Like {@link ColumnCache}, but each key may sit in any of four slots of its set, so two keys that happen to share a
 * slot no longer throw each other out on every other lookup.
 *
 * <p>A single-slot table holding six hundred tiles in two thousand slots has a quarter of them sharing a slot with
 * another; asked for alternately, the pair is worked out again and again. For a tile that costs two thousand reads of
 * the ground, that was seventeen times the work of the whole map. Four ways make such a collision rare. What falls out
 * is still only worked out again, so which slot a new entry takes needs no care.</p>
 */
public final class SetCache<T> {

    private record Entry<T>(long key, T value) {}

    private static final int WAYS = 4;

    private final Entry<T>[] slots;
    private final int bits;

    /** A table of {@code 1 << bits} sets of four. */
    @SuppressWarnings("unchecked")
    public SetCache(int bits) {
        this.bits = bits;
        this.slots = new Entry[WAYS << bits];
    }

    public T get(long key) {
        int base = set(key);
        for (int w = 0; w < WAYS; w++) {
            Entry<T> e = slots[base + w];
            if (e != null && e.key == key) return e.value;
        }
        return null;
    }

    public void put(long key, T value) {
        int base = set(key);
        int free = -1;
        for (int w = 0; w < WAYS; w++) {
            Entry<T> e = slots[base + w];
            if (e == null) {
                if (free < 0) free = w;
            } else if (e.key == key) {
                slots[base + w] = new Entry<>(key, value);
                return;
            }
        }
        int way = free >= 0 ? free : ThreadLocalRandom.current().nextInt(WAYS);
        slots[base + way] = new Entry<>(key, value);
    }

    public void clear() {
        Arrays.fill(slots, null);
    }

    private int set(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - bits)) * WAYS;
    }
}
