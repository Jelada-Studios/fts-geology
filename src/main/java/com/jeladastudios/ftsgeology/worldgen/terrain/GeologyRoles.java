package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the plate model knows about a place that its climate cannot say: the floor of a rift, the ribbon of a
 * volcanic arc, the basin over a plume. {@link GeologyBiomeSource} turns these into biomes; the rule itself lives
 * here, with nothing of the world generator about it, so a tool can map where each one would go without a world.
 *
 * <p>Every distance is in fault widths, read off the same {@link TerrainFields} the ground was cut from, so a
 * mountain and the biome on it can never drift apart.</p>
 */
public final class GeologyRoles {

    private GeologyRoles() {}

    public enum Role {
        /** Nothing the geology needs to say: whatever biome the climate chose stands. */
        NONE,
        /** The sinter and mud flats over a mantle plume. */
        GEOTHERMAL_BASIN,
        /** The floor of a continental rift, dropped between its shoulders. */
        RIFT_VALLEY,
        /** The ribbon of volcanoes above a subducting slab. */
        VOLCANIC_HIGHLAND,
        /** The core of a fold belt, worn down to the rock the mountains are made of. */
        OROGENIC_HIGHLAND,
        /** The flat ground a mountain belt sheds its sediment onto. */
        ALLUVIAL_PLAIN,
        /** The sea floor where two plates pull apart and new crust is made. */
        OCEANIC_RIDGE;

        /** The name this role goes by in the world preset. */
        public final String key = name().toLowerCase(Locale.ROOT);
    }

    /** Ground flatter than this can hold a basin. */
    private static final double FLAT = 0.1;
    /** How strong a plume's dome has to be under a geothermal basin. */
    private static final double BASIN_PLUME = 0.45;
    /** How far the arc's ribbon reaches, and how deep into a belt its core goes; see {@link PlateSample#onArc}. */
    private static final double ARC_FROM = 0.25, ARC_TO = 0.75, BELT_CORE = 0.55;
    /** How wide a rift floor is, how far a ridge reaches, and how far a belt's sediment goes, in fault widths. */
    private static final double GRABEN_TO = 0.35, RIDGE_TO = 0.9, APRON_OVER = 1.6;

    /**
     * Which role belongs at a column. Cached on the four-block grid the biomes are laid out on: the answer does
     * not depend on height, and a chunk asks for each of its columns once for every level of it.
     */
    public static Role roleAt(int x, int z) {
        long seed = TerrainContext.seed();
        if (seed != cachedFor) {
            ROLES.clear();
            cachedFor = seed;
        }
        long key = ((long) (x >> 2) & 0xFFFFFFFFL) | (((long) (z >> 2) & 0xFFFFFFFFL) << 32);
        Role hit = ROLES.get(key);
        if (hit != null) return hit;
        Role role = decide(seed, TerrainContext.params(), x, z);
        if (ROLES.size() > ROLE_CACHE_MAX) ROLES.clear();
        ROLES.put(key, role);
        return role;
    }

    private static final Map<Long, Role> ROLES = new ConcurrentHashMap<>();
    private static final int ROLE_CACHE_MAX = 200_000;
    private static volatile long cachedFor;

    public static void clearCache() {
        ROLES.clear();
    }

    /** The rule itself, a pure function of the seed and the column. */
    public static Role decide(long seed, GeologyParams p, int x, int z) {
        PlateSample s = TerrainFields.sampleAt(seed, p, x, z);
        FaultType k = s.boundaryType();
        double a = TerrainFields.across(s, p);
        boolean oceanic = s.plateKind().isOceanic();
        boolean margin = oceanic != s.neighbourKind().isOceanic();

        // New sea floor, rising out of the abyss along the line where two ocean plates part.
        if (oceanic) {
            return !margin && k == FaultType.DIVERGENT && a < RIDGE_TO ? Role.OCEANIC_RIDGE : Role.NONE;
        }

        double erosion = TerrainFields.field(Field.EROSION, seed, p, x, z);
        // A plume's basin: wide, flat, and hot underneath, whatever the boundary nearest it happens to be doing.
        if (HotspotMap.plumeStrength(seed, x, z, p) >= BASIN_PLUME && erosion > FLAT) return Role.GEOTHERMAL_BASIN;
        if (k == FaultType.DIVERGENT && a < GRABEN_TO) return Role.RIFT_VALLEY;
        if (s.overridingSide() && a >= ARC_FROM && a <= ARC_TO) return Role.VOLCANIC_HIGHLAND;
        if (k == FaultType.CONVERGENT_COLLISION && TerrainFields.belt(s, p) > BELT_CORE) return Role.OROGENIC_HIGHLAND;
        // The apron of sediment a belt sheds just beyond its mountains: flat, low, and the coal country. It lies
        // on the side that is being pushed down and so kept low, which is where the coal basins are too: under
        // the thrust in a collision, behind the arc at a subduction margin.
        boolean foreland = k == FaultType.CONVERGENT_COLLISION ? s.downGoing() : s.overridingSide();
        if (foreland && a > p.beltFactor() && a < p.beltFactor() * APRON_OVER) return Role.ALLUVIAL_PLAIN;
        return Role.NONE;
    }
}
