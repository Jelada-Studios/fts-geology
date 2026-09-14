package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.util.SeedHash;
import net.minecraft.util.Mth;

/**
 * The terrain the plates make, as four numbers a column: the model itself, with nothing of the world generator
 * about it. {@link PlateDensity} hands these to vanilla's noise router; the biome source and the tools read the
 * same answers, so what the ground does and what grows on it can never drift apart.
 *
 * <ul>
 *   <li>{@link Field#CONTINENTS}: the vanilla continentalness, from crust. An oceanic plate is sea, a continental
 *       one land, a shore where they meet, a trench off a subduction coast.</li>
 *   <li>{@link Field#EROSION}: the vanilla erosion, low where the ground is rugged: an active belt is mountainous,
 *       a plate's interior flat.</li>
 *   <li>{@link Field#RIDGES}: a factor on the vanilla ridge noise, stronger in a belt.</li>
 *   <li>{@link Field#RELIEF}: height added to the terrain in vanilla depth units (1.0 is 128 blocks): a fold
 *       belt's uplift, an arc, a trench, a rift's graben and shoulders, a plume's dome.</li>
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

    public enum Field { CONTINENTS, EROSION, RIDGES, RELIEF, VARIETY }

    /** How far the coordinates are pushed about, in blocks, and the size of the pushing. */
    private static final double WARP_AMPLITUDE = 250.0;
    private static final double WARP_SCALE = 1200.0;

    // Distances from the boundary, in fault widths.
    /**
     * How far a coast climbs inland before the ground is properly continental. A subduction margin is the steep
     * kind, the Andean coast where the mountains stand almost in the sea; a quiet margin has a wide coastal plain.
     * The shore itself stands hard by the boundary either way, so an arc always has dry ground to stand on.
     */
    private static final double COAST_RISE_STEEP = 0.8, COAST_RISE_QUIET = 1.6;
    /** How far inland the ground goes on rising, from the coastal plain to the deep interior. */
    private static final double INLAND_OVER = 2.5;
    /** Where an arc's volcanoes stand: the middle of {@link PlateSample#onArc}'s band. */
    private static final double ARC_AT = 0.55, ARC_HALF = 0.35;
    /** Where a trench lies off a subduction coast. */
    private static final double TRENCH_AT = 0.3, TRENCH_HALF = 0.35;
    /** How far a rift's graben floor reaches, and where its shoulders stand. */
    private static final double GRABEN_TO = 0.35, SHOULDER_AT = 0.7, SHOULDER_HALF = 0.4;
    /** How far out the continental shelf runs before the floor falls away to the abyss. */
    private static final double SHELF_TO = 0.15, SLOPE_OVER = 1.5;

    // Continentalness. Vanilla reads these bands: under -1.05 mushroom fields, -1.05..-0.455 deep ocean,
    // -0.455..-0.19 ocean, -0.19..-0.11 the shore, then inland. The floor stays clear of the mushroom band:
    // how deep the sea is comes from the relief, not from here.
    private static final double ABYSS = -0.95, RIDGE_TOP = -0.85, SHORE = -0.22;

    // Relief, in vanilla depth units: 1.0 is 128 blocks. The abyssal plain lies well below the shelf, a
    // spreading ridge rises back out of it with a valley split down its axis, and a trench falls further still.
    private static final double ABYSS_DEEP = -0.18, RIDGE_RISE = 0.18, RIDGE_VALLEY = -0.06, TRENCH_DEEP = -0.12;

    /** How rugged ground is where no boundary reaches it: vanilla's erosion, where higher is flatter. */
    private static final double OCEAN_EROSION = 0.5, INTERIOR_EROSION = 0.45;

    /**
     * The plate the terrain sees at a column: the sample at the warped coordinate, which is where the coast, the
     * belt and the arc all really are. Cached on a four-block grid.
     */
    public static PlateSample sampleAt(long seed, GeologyParams p, int x, int z) {
        double wx = x + WARP_AMPLITUDE * warp(seed, x, z, 0x77A1L);
        double wz = z + WARP_AMPLITUDE * warp(seed, x, z, 0x3B2CL);
        return TerrainCache.sample(seed, p, (int) Math.floor(wx), (int) Math.floor(wz));
    }

    public static double field(Field field, long seed, GeologyParams p, int x, int z) {
        PlateSample s = sampleAt(seed, p, x, z);
        return switch (field) {
            case CONTINENTS -> continents(s, p);
            case EROSION -> erosion(s, p);
            case RIDGES -> 0.5 + 0.9 * belt(s, p);
            case RELIEF -> relief(s, p) + 0.25 * HotspotMap.plumeStrength(seed, x, z, p);
            case VARIETY -> variety(s, p);
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
        // coastal plain and on to the interior; the oceanic side crosses the shelf and falls to the abyss.
        double rise = s.boundaryType() == FaultType.CONVERGENT_SUBDUCTION ? COAST_RISE_STEEP : COAST_RISE_QUIET;
        if (!oceanic) {
            return SHORE + 0.55 * Mth.clamp(a / rise, 0, 1)
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
        double belt = belt(s, p);
        return quiet - belt * switch (k) {
            case CONVERGENT_SUBDUCTION, CONVERGENT_COLLISION -> 2.0;
            case TRANSFORM -> 1.2;
            case DIVERGENT -> 0.9;
            case INTERIOR -> 0.0;
        };
    }

    private static double relief(PlateSample s, GeologyParams p) {
        double a = across(s, p);
        if (s.plateKind().isOceanic()) return oceanRelief(s, p, a);
        double u = p.uplift() / 128.0 * (0.5 + 0.5 * motion(s));
        double t = Math.min(1.0, a / p.beltFactor());
        return switch (s.boundaryType()) {
            case CONVERGENT_COLLISION -> u * bump(t);
            // The arc stands where the volcanoes do, on a plateau that fades out across the belt; the trench
            // lies off the coast, on the plate that goes under.
            case CONVERGENT_SUBDUCTION -> s.overridingSide()
                    ? u * (0.55 * peak(a, ARC_AT, ARC_HALF) + 0.3 * bump(t))
                    : -0.25 * peak(a, TRENCH_AT, TRENCH_HALF);
            case DIVERGENT -> a < GRABEN_TO
                    ? -0.25 * Math.sqrt(1.0 - a / GRABEN_TO)
                    : 0.15 * peak(a, SHOULDER_AT, SHOULDER_HALF);
            case TRANSFORM, INTERIOR -> 0.0;
        };
    }

    /**
     * The sea floor: a shelf by the coast, then the long fall to the abyssal plain, a spreading ridge rising back
     * out of it with a valley down its axis, and a trench where the plate goes under.
     */
    private static double oceanRelief(PlateSample s, GeologyParams p, double a) {
        FaultType k = s.boundaryType();
        boolean margin = !s.neighbourKind().isOceanic();
        // Off a coast the floor is still shelf; out in the open sea it is already the plain.
        double deep = margin ? ABYSS_DEEP * Mth.clamp((a - SHELF_TO) / SLOPE_OVER, 0, 1) : ABYSS_DEEP;
        if (k == FaultType.DIVERGENT && !margin) {
            deep += RIDGE_RISE * peak(a, 0.0, 0.9) + RIDGE_VALLEY * peak(a, 0.0, 0.12);
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

    // === Warp ===============================================================

    /** Two octaves of value noise in -1..1, from the seed. */
    private static double warp(long seed, int x, int z, long salt) {
        return 0.75 * noise(seed, x, z, WARP_SCALE, salt) + 0.25 * noise(seed, x, z, WARP_SCALE / 3.0, salt ^ 0x5A5AL);
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
