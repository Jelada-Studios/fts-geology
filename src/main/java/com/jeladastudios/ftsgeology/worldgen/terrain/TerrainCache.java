package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plate samples for the terrain, on a four-block grid and keyed by the seed they were made for. The density
 * functions ask from every generator thread at once, and the same column is asked for by continents, erosion,
 * ridges and relief in turn.
 */
public final class TerrainCache {

    private TerrainCache() {}

    private static final Map<Long, PlateSample> SAMPLES = new ConcurrentHashMap<>();
    private static final int MAX = 200_000;
    private static volatile long forSeed;

    public static PlateSample sample(long seed, int x, int z) {
        if (seed != forSeed) {
            SAMPLES.clear();
            forSeed = seed;
        }
        long key = ((long) (x >> 2) & 0xFFFFFFFFL) | (((long) (z >> 2) & 0xFFFFFFFFL) << 32);
        PlateSample hit = SAMPLES.get(key);
        if (hit != null) return hit;
        PlateSample s = TectonicMap.sampleSeeded(seed, x, z);
        if (SAMPLES.size() > MAX) SAMPLES.clear();
        SAMPLES.put(key, s);
        return s;
    }

    public static void clear() {
        SAMPLES.clear();
    }
}
