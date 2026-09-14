package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles.Role;

/**
 * What the terrain has already worked out about a four-block cell: the plate under it and the role it plays. The
 * density functions and the biome source ask from every generator thread at once, and for the same cell over and
 * over — each column of a chunk asks for the cell it sits in, and the biome source asks again for every level.
 *
 * <p>A fixed table that overwrites rather than grows. A map that is emptied when it fills throws away everything
 * it knows each time the generator moves on, which is what a distant-horizon mod does all day; this keeps what is
 * near and lets the rest fall out. Each slot holds one immutable pair, so a reader either sees a whole entry or
 * an older one, never half of each.</p>
 */
public final class TerrainCache {

    private TerrainCache() {}

    private static final int BITS = 16;
    private static final int SLOTS = 1 << BITS;

    private record Plates(long key, PlateSample sample) {}
    private record Roles(long key, Role role) {}

    private static final Plates[] PLATES = new Plates[SLOTS];
    private static final Roles[] ROLES = new Roles[SLOTS];
    private static volatile long forSeed;

    public static PlateSample sample(long seed, GeologyParams params, int x, int z) {
        checkSeed(seed);
        long key = key(x, z);
        int slot = slot(key);
        Plates hit = PLATES[slot];
        if (hit != null && hit.key == key) return hit.sample;
        PlateSample s = TectonicMap.sampleSeeded(seed, x, z, params);
        PLATES[slot] = new Plates(key, s);
        return s;
    }

    /** The role at a cell, worked out once; {@code decide} is the rule itself. See {@link GeologyRoles}. */
    static Role role(long seed, GeologyParams params, int x, int z) {
        checkSeed(seed);
        long key = key(x, z);
        int slot = slot(key);
        Roles hit = ROLES[slot];
        if (hit != null && hit.key == key) return hit.role;
        Role r = GeologyRoles.decide(seed, params, x, z);
        ROLES[slot] = new Roles(key, r);
        return r;
    }

    private static void checkSeed(long seed) {
        if (seed == forSeed) return;
        clear();
        forSeed = seed;
    }

    /** One entry per four-block cell, which is how far apart two columns have to be to differ. */
    private static long key(int x, int z) {
        return ((long) (x >> 2) & 0xFFFFFFFFL) | (((long) (z >> 2) & 0xFFFFFFFFL) << 32);
    }

    private static int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> (64 - BITS));
    }

    public static void clear() {
        java.util.Arrays.fill(PLATES, null);
        java.util.Arrays.fill(ROLES, null);
    }
}
