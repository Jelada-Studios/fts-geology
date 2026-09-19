package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field;

import java.util.Locale;

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
    /**
     * How strong a plume's dome has to be under a geothermal basin: the same strength at which
     * { GeothermalBasin} starts laying its sinter and mud down, so the biome covers the ground the mod
     * already paints rather than a smaller circle inside it.
     */
    private static final double BASIN_PLUME = 0.30;
    /** How far the arc's ribbon reaches, and how deep into a belt its core goes; see {@link PlateSample#onArc}. */
    private static final double ARC_FROM = 0.25, ARC_TO = 0.75, BELT_CORE = 0.55;
    /**
     * Ground a collision has lifted this much (in offset units, 0.24 is about thirty blocks) is the highland whatever
     * the belt's grip says: over a slow collision the grip stays under {@link #BELT_CORE} while the mountains stand
     * as high as anywhere, and they were left as no role at all, under a vanilla biome, with the rock and the rule
     * that bares it their own.
     */
    private static final double HIGH_RELIEF = 0.24;
    /** How wide a rift floor is and how far a ridge reaches, in fault widths. */
    private static final double GRABEN_TO = 0.35, RIDGE_TO = TerrainFields.RIDGE_HALF;
    /** How deep into a floodplain ({@link TerrainFields#apron}) the plain begins. */
    private static final double APRON_CORE = 0.28;
    /** How far the border between two kinds of ground wanders, in fault widths or belt grip. */
    private static final double EDGE = 0.05;

    /**
     * Which role belongs at a column. Kept in {@link TerrainCache}: the answer does not depend on height, and a
     * chunk asks for each of its columns once for every level of it.
     */
    public static Role roleAt(int x, int z) {
        return TerrainCache.role(TerrainContext.seed(), TerrainContext.params(), x, z);
    }

    /** The rule itself, a pure function of the seed and the column. */
    public static Role decide(long seed, GeologyParams p, int x, int z) {
        PlateSample s = TerrainFields.sampleAt(seed, p, x, z);
        FaultType k = s.boundaryType();
        double a = TerrainFields.across(s, p);
        boolean oceanic = s.plateKind().isOceanic();
        boolean margin = oceanic != s.neighbourKind().isOceanic();
        // Borders between one kind of ground and the next wander, as vanilla's own biome edges do, instead of
        // following a line a set distance from the boundary.
        double j = EDGE * TerrainFields.jitter(seed, p, x, z);

        // New sea floor, rising out of the abyss along the line where two ocean plates part.
        if (oceanic) {
            return !margin && k == FaultType.DIVERGENT && a < RIDGE_TO + j ? Role.OCEANIC_RIDGE : Role.NONE;
        }

        double erosion = TerrainFields.field(Field.EROSION, seed, p, x, z);
        // A plume's basin: wide, flat, and hot underneath, whatever the boundary nearest it happens to be doing.
        if (HotspotMap.plumeStrength(seed, x, z, p) >= BASIN_PLUME && erosion > FLAT) return Role.GEOTHERMAL_BASIN;
        if (k == FaultType.DIVERGENT && a < GRABEN_TO + j) return Role.RIFT_VALLEY;
        if (s.overridingSide() && a >= ARC_FROM + j && a <= ARC_TO + j) return Role.VOLCANIC_HIGHLAND;
        if (k == FaultType.CONVERGENT_COLLISION && (TerrainFields.belt(s, p) > BELT_CORE + j
                || TerrainFields.field(Field.RELIEF, seed, p, x, z) > HIGH_RELIEF + j)) return Role.OROGENIC_HIGHLAND;
        // The apron of sediment a belt sheds beyond its mountains: flat, low, and the coal country. Its border wanders
        // in and out over a few hundred blocks as well as ragging: at one depth of apron it ran parallel to the belt.
        if (TerrainFields.apronAt(seed, p, x, z) > APRON_CORE + 2 * j
                + TerrainFields.APRON_WANDER * TerrainFields.jitterWide(seed, p, x, z)) return Role.ALLUVIAL_PLAIN;
        return Role.NONE;
    }
}
