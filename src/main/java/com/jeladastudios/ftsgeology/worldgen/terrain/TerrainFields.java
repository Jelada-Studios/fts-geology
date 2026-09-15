package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.SeedHash;
import net.minecraft.util.Mth;

/**
 * The terrain the plates make, as a few numbers a column: the model itself, with nothing of the world generator
 * about it. {@link PlateDensity} hands these to vanilla's noise router; the biome source and the tools read the
 * same answers, so what the ground does and what grows on it can never drift apart.
 *
 * <ul>
 *   <li>{@link Field#CONTINENTS}: the vanilla continentalness, from crust. An oceanic plate is sea, a continental
 *       one land, a shore where they meet, a trench off a subduction coast.</li>
 *   <li>{@link Field#EROSION}: the vanilla erosion, low where the ground is rugged: an active belt is mountainous,
 *       a plate's interior flat, a floodplain flatter still.</li>
 *   <li>{@link Field#RIDGES}: a factor on the vanilla ridge noise, stronger in a belt.</li>
 *   <li>{@link Field#RELIEF}: height added to the terrain in vanilla depth units (1.0 is 128 blocks): a fold
 *       belt's uplift, an arc, a trench, a rift's graben and shoulders, a spreading ridge, a plume's dome.</li>
 *   <li>{@link Field#MEANDER_X}, {@link Field#MEANDER_Z}: how far the ridge noise is pushed about, in its own noise
 *       units. Vanilla's rivers run along that noise's zero lines, so pushing it about makes them wind.</li>
 * </ul>
 *
 * <p>Distances are measured the way the rest of the mod measures them: in fault widths from the boundary
 * ({@link PlateSample#across}), so the mountains, the volcanoes, the quake corridor and the ore belts line up. A
 * mountain belt is wider than the fault that raised it and reaches {@code terrainBeltFactor} fault widths.</p>
 *
 * <p>The coordinates are warped by a low-frequency value noise first, so a plate's straight Voronoi edges become
 * winding coasts and belts; the whole geometry moves together, boundary, coast and arc alike.</p>
 */
public final class TerrainFields {

    private TerrainFields() {}

    public enum Field { CONTINENTS, EROSION, RIDGES, RELIEF, VARIETY, MEANDER_X, MEANDER_Z }

    /** How far the coordinates are pushed about, in blocks, and the size of the pushing. */
    private static final double WARP_AMPLITUDE = 250.0;
    private static final double WARP_SCALE = 1200.0;

    // Distances from the boundary, in fault widths.
    /**
     * How far a coast climbs inland before the ground is properly continental. A subduction margin is the steep
     * kind, the Andean coast where the mountains stand close to the sea; a quiet margin has a wide coastal plain.
     * The shore itself stands hard by the boundary either way, so an arc always has dry ground to stand on.
     */
    private static final double COAST_RISE_STEEP = 1.2, COAST_RISE_QUIET = 1.6;
    /** How far inland the ground goes on rising, from the coastal plain to the deep interior. */
    private static final double INLAND_OVER = 2.5;
    /**
     * The fore-arc lowland of a subduction coast: the ground stays gentle out to here, and the arc's ruggedness builds
     * up over the next {@code COAST_FOOTHILLS}. When it started at the waterline the coast stood as a wall along the sea.
     */
    private static final double COAST_LOWLAND = 0.05, COAST_FOOTHILLS = 0.5;
    /** Where an arc's volcanoes stand: the middle of {@link PlateSample#onArc}'s band. */
    private static final double ARC_AT = 0.55, ARC_HALF = 0.35;
    /** Where a trench lies off a subduction coast. */
    private static final double TRENCH_AT = 0.3, TRENCH_HALF = 0.35;
    /**
     * A rift: a flat floor out to {@code GRABEN_FLOOR}, then two fault scarps up out of it, each {@code STEP_WIDTH}
     * across and half the drop, the second starting at {@code GRABEN_STEP}. The floor used to rise to the rim as a
     * square root, which stands vertical at the rim.
     */
    private static final double GRABEN_FLOOR = 0.16, GRABEN_STEP = 0.30, STEP_WIDTH = 0.13;
    private static final double SHOULDER_AT = 0.7, SHOULDER_HALF = 0.4;
    /** Where a rift's floor starts to take the ruggedness of its shoulders, and over how far. */
    private static final double RIFT_CALM_FROM = 0.1, RIFT_CALM_OVER = 0.6;
    /** How far out the continental shelf runs before the floor falls away to the abyss. */
    private static final double SHELF_TO = 0.15, SLOPE_OVER = 1.5;
    /** How far a spreading ridge's rise reaches either side of its axis, and its axial valley. */
    public static final double RIDGE_HALF = 0.7;
    private static final double VALLEY_HALF = 0.12;
    /**
     * Where a mountain belt's floodplain lies beyond its mountains, in belt widths from the boundary: the middle of it
     * and how far it reaches either side, on the side pushed down, and the narrower one across a collision.
     */
    private static final double APRON_AT = 1.6, APRON_HALF = 0.8, RETRO_AT = 1.35, RETRO_HALF = 0.5, RETRO_SHARE = 0.7;

    // Continentalness. Vanilla reads these bands: under -1.05 mushroom fields, -1.05..-0.455 deep ocean,
    // -0.455..-0.19 ocean, -0.19..-0.11 the shore, then inland. The floor stays clear of the mushroom band:
    // how deep the sea is comes from the relief, not from here.
    private static final double ABYSS = -0.95, RIDGE_TOP = -0.85, SHORE = -0.22;

    // Relief, in vanilla depth units: 1.0 is 128 blocks. The abyssal plain lies well below the shelf, a
    // spreading ridge rises back out of it with a valley split down its axis, and a trench falls further still.
    private static final double ABYSS_DEEP = -0.18, RIDGE_RISE = 0.26, RIDGE_VALLEY = -0.09, TRENCH_DEEP = -0.12;
    private static final double GRABEN_DEEP = -0.25, SHOULDER_RISE = 0.15;
    /** The hills of new sea floor either side of a ridge, faulted blocks of crust a few blocks high, and their size. */
    private static final double ABYSSAL_HILLS = 0.045, HILL_SCALE = 40.0;
    /** How far a floodplain lies below the country round it. */
    private static final double APRON_LOW = -0.03;
    /**
     * How much of a fold belt's uplift stands on its crests: the rest is taken out of the ground between them, so a
     * belt is a range of peaks and passes instead of one raised plateau.
     */
    private static final double CREST_SHARE = 0.45, CREST_SCALE = 150.0;
    /** How far the ridge noise is pushed about, in its noise units (four blocks each), and the length of a bend. */
    private static final double MEANDER_AMPLITUDE = 12.0, MEANDER_SCALE = 260.0;

    /** How rugged ground is where no boundary reaches it: vanilla's erosion, where higher is flatter. */
    private static final double OCEAN_EROSION = 0.5, INTERIOR_EROSION = 0.45;

    /**
     * The plate the terrain sees at a column: the sample at the warped coordinate, which is where the coast, the
     * belt and the arc all really are. Cached on a four-block grid.
     */
    public static PlateSample sampleAt(long seed, GeologyParams p, int x, int z) {
        return edgesAt(seed, p, x, z).first();
    }

    /** The plate at the warped coordinate against its two nearest boundaries. */
    private static TectonicMap.Edges edgesAt(long seed, GeologyParams p, int x, int z) {
        double wx = x + WARP_AMPLITUDE * twoOctaves(seed, x, z, WARP_SCALE, 0x77A1L);
        double wz = z + WARP_AMPLITUDE * twoOctaves(seed, x, z, WARP_SCALE, 0x3B2CL);
        return TerrainCache.edges(seed, p, (int) Math.floor(wx), (int) Math.floor(wz));
    }

    public static double field(Field field, long seed, GeologyParams p, int x, int z) {
        if (field == Field.MEANDER_X || field == Field.MEANDER_Z) {
            return MEANDER_AMPLITUDE * twoOctaves(seed, x, z, MEANDER_SCALE, field == Field.MEANDER_X ? 0x3E11L : 0x71C3L);
        }
        TectonicMap.Edges e = edgesAt(seed, p, x, z);
        double v = value(field, e.first(), p, seed, x, z);
        // A column almost as near a second boundary is shaped by both. Along the line where one boundary hands over to
        // the next, both sides see the same mean of the two, so the ground cannot jump there: it did, by up to eighty
        // blocks, wherever a fold belt's boundary met a rift's or a quiet coast's.
        if (e.gap() < HANDOVER) {
            double w = 0.5 + 0.5 * smooth(Mth.clamp(e.gap() / HANDOVER, 0, 1));
            v = w * v + (1.0 - w) * value(field, e.second(), p, seed, x, z);
        }
        return field == Field.RELIEF ? v + 0.25 * HotspotMap.plumeStrength(seed, x, z, p) : v;
    }

    /** How much further than the nearest boundary the next one can be and still shape the ground, in blocks. */
    private static final double HANDOVER = 64.0;

    /** One field from one boundary. */
    private static double value(Field field, PlateSample s, GeologyParams p, long seed, int x, int z) {
        return switch (field) {
            case CONTINENTS -> continents(s, p);
            case EROSION -> erosion(s, p);
            case RIDGES -> 0.5 + 0.9 * belt(s, p);
            case RELIEF -> relief(s, p, seed, x, z);
            case VARIETY -> variety(s, p);
            case MEANDER_X, MEANDER_Z -> 0.0;
        };
    }

    /**
     * How much free noise a column allows, 0.2 to 1. The plates decide the shape of a belt and of its coast; left
     * at full strength the terrain noise moved a shoreline by a hundred blocks and sank a third of every volcanic
     * arc. Away from a boundary the noise has the ground to itself again.
     */
    private static double variety(PlateSample s, GeologyParams p) {
        return Mth.clamp(1.0 - 0.8 * belt(s, p), 0.2, 1.0);
    }

    // === Fields =============================================================

    private static double continents(PlateSample s, GeologyParams p) {
        boolean oceanic = s.plateKind().isOceanic(), otherOceanic = s.neighbourKind().isOceanic();
        double a = across(s, p);
        // Two oceanic plates: open sea, a little shallower over a spreading ridge.
        if (oceanic && otherOceanic) {
            return s.boundaryType() == FaultType.DIVERGENT
                    ? ABYSS + (RIDGE_TOP - ABYSS) * peak(a, 0.0, 0.9) : ABYSS;
        }
        // Two continental plates: land either way, rising towards the interior.
        if (!oceanic && !otherOceanic) return 0.55 + 0.25 * Math.min(1.0, a / (INLAND_OVER * 2));
        // A margin: one curve through the boundary, the shore hard by it. The continental side climbs to the
        // coastal plain and on to the interior, leaving the shore gently; the oceanic side crosses the shelf and
        // falls to the abyss.
        double rise = s.boundaryType() == FaultType.CONVERGENT_SUBDUCTION ? COAST_RISE_STEEP : COAST_RISE_QUIET;
        if (!oceanic) {
            return SHORE + 0.55 * smooth(Mth.clamp(a / rise, 0, 1))
                    + 0.45 * Mth.clamp((a - rise) / INLAND_OVER, 0, 1);
        }
        return SHORE + (ABYSS - SHORE) * Mth.clamp((a - SHELF_TO) / SLOPE_OVER, 0, 1);
    }

    private static double erosion(PlateSample s, GeologyParams p) {
        boolean oceanic = s.plateKind().isOceanic();
        // The quiet ground a plate has away from its edge: a flat sea floor, a worn interior.
        double quiet = oceanic ? OCEAN_EROSION : INTERIOR_EROSION;
        FaultType k = s.boundaryType();
        // A sea floor is flat except where it is being made or destroyed: the hills of a spreading ridge and the
        // islands of an arc are the exceptions.
        if (oceanic && !s.overridingSide() && k != FaultType.DIVERGENT) return quiet;
        // Every belt starts from that quiet ground and works down from it, so the ruggedness fades out where the
        // belt does instead of ending in a ring one belt width from the boundary. Vanilla's peaks want erosion
        // under -0.4, which a belt's core reaches while its foothills stay gentle.
        double rugged = belt(s, p) * calm(s, p) * switch (k) {
            case CONVERGENT_SUBDUCTION, CONVERGENT_COLLISION -> 2.0;
            case TRANSFORM -> 1.2;
            case DIVERGENT -> 0.9;
            case INTERIOR -> 0.0;
        };
        return quiet - rugged + 0.25 * apron(s, p);
    }

    private static double relief(PlateSample s, GeologyParams p, long seed, int x, int z) {
        double a = across(s, p);
        if (s.plateKind().isOceanic()) return oceanRelief(s, p, a, seed, x, z);
        double u = p.uplift() / 128.0 * (0.5 + 0.5 * motion(s));
        double t = Math.min(1.0, a / p.beltFactor());
        double low = APRON_LOW * apron(s, p);
        return low + switch (s.boundaryType()) {
            case CONVERGENT_COLLISION -> u * bump(t) * crest(seed, x, z);
            // The arc stands where the volcanoes do, on a plateau that fades out across the belt and keeps clear of
            // the coast; the trench lies off the coast, on the plate that goes under.
            case CONVERGENT_SUBDUCTION -> s.overridingSide()
                    ? u * (0.55 * peak(a, ARC_AT, ARC_HALF) + 0.3 * bump(t) * calm(s, p))
                    : -0.25 * peak(a, TRENCH_AT, TRENCH_HALF);
            case DIVERGENT -> graben(a) + SHOULDER_RISE * peak(a, SHOULDER_AT, SHOULDER_HALF);
            case TRANSFORM, INTERIOR -> 0.0;
        };
    }

    /**
     * The sea floor: a shelf by the coast, then the long fall to the abyssal plain, a spreading ridge rising back
     * out of it with a valley down its axis and hills of new crust either side, and a trench where the plate goes
     * under.
     */
    private static double oceanRelief(PlateSample s, GeologyParams p, double a, long seed, int x, int z) {
        FaultType k = s.boundaryType();
        boolean margin = !s.neighbourKind().isOceanic();
        // Off a coast the floor is still shelf; out in the open sea it is already the plain.
        double deep = margin ? ABYSS_DEEP * Mth.clamp((a - SHELF_TO) / SLOPE_OVER, 0, 1) : ABYSS_DEEP;
        if (k == FaultType.DIVERGENT && !margin) {
            double hills = 1.0 - Math.abs(noise(seed, x, z, HILL_SCALE, 0x4B1DL));
            deep += RIDGE_RISE * peak(a, 0.0, RIDGE_HALF) + RIDGE_VALLEY * peak(a, 0.0, VALLEY_HALF)
                    + ABYSSAL_HILLS * hills * bump(Math.min(1.0, a / 1.2));
        }
        if (k == FaultType.CONVERGENT_SUBDUCTION && s.downGoing()) {
            deep += TRENCH_DEEP * peak(a, TRENCH_AT, TRENCH_HALF);
        }
        return deep;
    }

    // === Geometry ===========================================================

    /** Fault widths from the boundary. */
    public static double across(PlateSample s, GeologyParams p) {
        return s.across(p.faultWidth());
    }

    /** The boundary's grip over the whole mountain belt: 1 on the line, 0 at the belt's edge. */
    public static double belt(PlateSample s, GeologyParams p) {
        return Mth.clamp(s.belt(p.faultWidth(), p.beltFactor()), 0.0, 1.0);
    }

    /**
     * How deep into a belt's floodplain a column lies, 0 to 1: the flat country beyond the mountains that their rivers
     * spread their sediment over. The broad one lies on the side pushed down and so kept low: under the thrust in a
     * collision, behind the arc at a subduction margin. Across a collision the other side has a narrower plain of its
     * own, as the Po plain faces the Molasse basin across the Alps.
     */
    public static double apron(PlateSample s, GeologyParams p) {
        if (s.plateKind().isOceanic()) return 0.0;
        FaultType k = s.boundaryType();
        double w = across(s, p) / p.beltFactor();
        if (k == FaultType.CONVERGENT_COLLISION) {
            return s.downGoing() ? peak(w, APRON_AT, APRON_HALF) : RETRO_SHARE * peak(w, RETRO_AT, RETRO_HALF);
        }
        return k == FaultType.CONVERGENT_SUBDUCTION && s.overridingSide() ? peak(w, APRON_AT, APRON_HALF) : 0.0;
    }

    /** A slow wobble in -1..1, for ragging the border between one kind of ground and the next. */
    public static double jitter(long seed, int x, int z) {
        return noise(seed, x, z, 40.0, 0x6A17L);
    }

    /**
     * How much of a belt's ruggedness a column takes, 0 to 1. A subduction coast keeps a lowland along the sea before
     * the arc's mountains start; a rift keeps its floor flat and leaves the ruggedness to its scarps and shoulders.
     */
    private static double calm(PlateSample s, GeologyParams p) {
        if (s.plateKind().isOceanic()) return 1.0;
        double a = across(s, p);
        FaultType k = s.boundaryType();
        if (k == FaultType.CONVERGENT_SUBDUCTION && s.overridingSide()) {
            return smooth(Mth.clamp((a - COAST_LOWLAND) / COAST_FOOTHILLS, 0, 1));
        }
        // Vanilla turns erosion into height steeply, so the rift's flat floor hands over to its rugged shoulders slowly:
        // over a third of a fault width the change stood as a fifty-block step.
        if (k == FaultType.DIVERGENT) return smooth(Mth.clamp((a - RIFT_CALM_FROM) / RIFT_CALM_OVER, 0, 1));
        return 1.0;
    }

    /** A rift's floor and the two fault scarps up out of it, in relief units: flat, half the drop, then the rim. */
    private static double graben(double a) {
        double first = smooth(Mth.clamp((a - GRABEN_FLOOR) / STEP_WIDTH, 0, 1));
        double second = smooth(Mth.clamp((a - GRABEN_STEP) / STEP_WIDTH, 0, 1));
        return GRABEN_DEEP * (1.0 - 0.5 * first - 0.5 * second);
    }

    /** How much of a fold belt's uplift a column keeps: all of it on a crest, less in the passes between. */
    private static double crest(long seed, int x, int z) {
        return 1.0 - CREST_SHARE + CREST_SHARE * (1.0 - Math.abs(twoOctaves(seed, x, z, CREST_SCALE, 0x2F0DL)));
    }

    private static double motion(PlateSample s) {
        return Mth.clamp(Math.max(Math.abs(s.convergence()), s.shear()) / 1.2, 0.0, 1.0);
    }

    /** 1 at 0, 0 at 1, smooth in between. */
    private static double bump(double t) {
        if (t >= 1.0) return 0.0;
        return (1.0 - t) * (1.0 - t) * (1.0 + 2.0 * t);
    }

    /** 1 at {@code centre}, 0 at {@code halfWidth} either side, smooth in between. */
    private static double peak(double a, double centre, double halfWidth) {
        return bump(Math.min(1.0, Math.abs(a - centre) / halfWidth));
    }

    // === Noise ==============================================================

    /** Two octaves of value noise in -1..1, from the seed. */
    private static double twoOctaves(long seed, int x, int z, double scale, long salt) {
        return 0.75 * noise(seed, x, z, scale, salt) + 0.25 * noise(seed, x, z, scale / 3.0, salt ^ 0x5A5AL);
    }

    private static double noise(long seed, int x, int z, double scale, long salt) {
        double fx = x / scale, fz = z / scale;
        int ix = Mth.floor(fx), iz = Mth.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double a = lattice(seed, ix, iz, salt), b = lattice(seed, ix + 1, iz, salt);
        double c = lattice(seed, ix, iz + 1, salt), d = lattice(seed, ix + 1, iz + 1, salt);
        return Mth.lerp(tz, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
    }

    private static double lattice(long seed, int ix, int iz, long salt) {
        return SeedHash.rand01(SeedHash.hash(seed, ix, iz, salt)) * 2.0 - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
