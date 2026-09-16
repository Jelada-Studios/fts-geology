package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.ColumnCache;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles.Role;

/**
 * What the terrain has already worked out about a four-block cell: the plate under it with its two nearest boundaries,
 * and the role it plays. The density functions and the biome source ask from every generator thread at once, and for
 * the same cell over and over — each column of a chunk asks for the cell it sits in, and the biome source asks again
 * for every level. Kept in {@link ColumnCache}s, for the seed they were worked out for.
 */
public final class TerrainCache {

    private TerrainCache() {}

    private static final ColumnCache<TectonicMap.Edges> PLATES = new ColumnCache<>(16);
    private static final ColumnCache<Role> ROLES = new ColumnCache<>(16);
    private static volatile long forSeed;

    /** The plate against its nearest boundary. */
    public static PlateSample sample(long seed, GeologyParams params, int x, int z) {
        return edges(seed, params, x, z).first();
    }

    /** The plate against its nearest boundary and the next nearest. */
    public static TectonicMap.Edges edges(long seed, GeologyParams params, int x, int z) {
        checkSeed(seed);
        long key = ColumnCache.key(x >> 2, z >> 2);
        TectonicMap.Edges hit = PLATES.get(key);
        if (hit != null) return hit;
        TectonicMap.Edges e = TectonicMap.sampleSeededEdges(seed, (x & ~3) + 2, (z & ~3) + 2, params);
        PLATES.put(key, e);
        return e;
    }

    /** The role at a cell, worked out once; {@code decide} is the rule itself. See {@link GeologyRoles}. */
    static Role role(long seed, GeologyParams params, int x, int z) {
        checkSeed(seed);
        long key = ColumnCache.key(x >> 2, z >> 2);
        Role hit = ROLES.get(key);
        if (hit != null) return hit;
        Role r = GeologyRoles.decide(seed, params, (x & ~3) + 2, (z & ~3) + 2);
        ROLES.put(key, r);
        return r;
    }

    private static void checkSeed(long seed) {
        if (seed == forSeed) return;
        clear();
        forSeed = seed;
    }

    public static void clear() {
        PLATES.clear();
        ROLES.clear();
    }
}
