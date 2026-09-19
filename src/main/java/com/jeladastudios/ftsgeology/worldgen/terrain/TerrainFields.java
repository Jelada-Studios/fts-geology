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

    public enum Field { CONTINENTS, EROSION, RIDGES, RELIEF, VARIETY, BELT, VALLEY, CREST, MEANDER_X, MEANDER_Z }

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
    private static final double ARC_AT = 0.55, ARC_SEA_HALF = 0.4, ARC_LAND_HALF = 1.1;
    /**
     * How high an arc stands, in uplift, and how much of that the passes between its massifs give up: an arc is a
     * chain of volcanic massifs and the saddles between them, along the same crest noise a fold belt follows, not
     * one level ridge along the coast.
     */
    private static final double ARC_RISE = 0.95, ARC_CREST = 0.45;
    /** Where a trench lies off a subduction coast. */
    private static final double TRENCH_AT = 0.3, TRENCH_HALF = 0.35;
    /**
     * A rift: a flat floor out to {@code GRABEN_FLOOR}, then two fault scarps up out of it, each {@code STEP_WIDTH}
     * across and half the drop, the second starting at {@code GRABEN_STEP}. The floor used to rise to the rim as a
     * square root, which stands vertical at the rim.
     */
    private static final double GRABEN_FLOOR = 0.16, GRABEN_STEP = 0.30, STEP_WIDTH = 0.06;
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
    private static final double APRON_LIFT = 0.06;

    /**
     * The broad flat-floored valleys of an earlier round, kept as a field for the record but with no hold on the
     * ground ({@code VALLEY_DEPTH} and {@code VALLEY_FLAT} are 0): their floors cut across the ridge-and-pass pattern
     * of {@link #crestShape} and read as benches, not valleys. The V-shaped valleys are the passes of the crest noise.
     */
    private static final double VALLEY_SCALE = 520.0, VALLEY_FLOOR = 0.15, VALLEY_SIDE = 0.3, VALLEY_DEPTH = 0.0,
            VALLEY_FLAT = 0.0;
    /**
     * How much of a fold belt's uplift stands on its crests: the rest is taken out of the ground between them, so a
     * belt is a range of peaks and passes instead of one raised plateau.
     */
    private static final double CREST_SHARE = 0.75, CREST_SCALE = 220.0, CREST_TOP = 0.08, CREST_RIDGE = 0.35;
    /**
     * An old collision belt, worn down: the Appalachians or the Urals against the Himalaya. This share of the
     * collision belts, chosen from the two plates' identities so a belt is worn along its whole length. A worn
     * belt keeps this much of its uplift, gives up this much of it between rounded ridges, takes this much of the
     * ridge noise, and has vanilla's mountain spline cut to {@code WORN_SPLINE} of it instead of {@link #SPLINE_CUT}.
     */
    private static final double WORN_SHARE = 1.0 / 3.0, WORN_RELIEF = 0.5, WORN_CREST_SHARE = 0.35, WORN_RIDGES = 0.4,
            WORN_SPLINE = 0.7;
    /** How much of vanilla's mountain spline the offset takes out inside a belt: the data's own {@code -0.5 * belt}. */
    private static final double SPLINE_CUT = 0.5;
    /** How far the plain's border wanders in and out, in apron depth, over {@link #jitterWide}'s few hundred blocks. */
    public static final double APRON_WANDER = 0.3;
    /** How far the ridge noise is pushed about, in its noise units (four blocks each), and the length of a bend. */
    private static final double MEANDER_AMPLITUDE = 12.0, MEANDER_SCALE = 260.0;

    /** How rugged ground is where no boundary reaches it: vanilla's erosion, where higher is flatter. */
    private static final double OCEAN_EROSION = 0.5, INTERIOR_EROSION = 0.45;

    /**
     * The plate the terrain sees at a column: the sample at the warped coordinate, which is where the coast, the
     * belt and the arc all really are. Cached on a four-block grid.
     */
    /**
     * The plate against the boundary that shapes the ground here. Usually the nearest one; but where a second
     * boundary is almost as near and lifts the ground more, it is that one: the roles and the rock follow the
     * mountains, which the two-boundary blend in {@link #field} raises, instead of the nearest line on the map.
     * A fold belt's highest peaks stood next to a transform fault as plain platform under a badlands biome.
     */
    public static PlateSample sampleAt(long seed, GeologyParams p, int x, int z) {
        TectonicMap.Edges e = edgesAt(seed, p, x, z);
        if (e.gap() >= HANDOVER * p.horizontal()) return e.first();
        return relief(e.second(), p, seed, x, z) > relief(e.first(), p, seed, x, z) ? e.second() : e.first();
    }

    /** The plate at the warped coordinate against its two nearest boundaries. */
    private static TectonicMap.Edges edgesAt(long seed, GeologyParams p, int x, int z) {
        double h = p.horizontal();
        double wx = x + WARP_AMPLITUDE * h * twoOctaves(seed, x, z, WARP_SCALE * h, 0x77A1L);
        double wz = z + WARP_AMPLITUDE * h * twoOctaves(seed, x, z, WARP_SCALE * h, 0x3B2CL);
        return TerrainCache.edges(seed, p, (int) Math.floor(wx), (int) Math.floor(wz));
    }

    public static double field(Field field, long seed, GeologyParams p, int x, int z) {
        if (field == Field.MEANDER_X || field == Field.MEANDER_Z) {
            // The amplitude is in the ridge noise's own units, which grow with the world, so it is not scaled.
            return MEANDER_AMPLITUDE * twoOctaves(seed, x, z, MEANDER_SCALE * p.horizontal(),
                    field == Field.MEANDER_X ? 0x3E11L : 0x71C3L);
        }
        TectonicMap.Edges e = edgesAt(seed, p, x, z);
        double v = value(field, e.first(), p, seed, x, z);
        // A column almost as near a second boundary is shaped by both. Along the line where one boundary hands over to
        // the next, both sides see the same mean of the two, so the ground cannot jump there: it did, by up to eighty
        // blocks, wherever a fold belt's boundary met a rift's or a quiet coast's.
        double handover = HANDOVER * p.horizontal();
        if (e.gap() < handover) {
            double w = 0.5 + 0.5 * smooth(Mth.clamp(e.gap() / handover, 0, 1));
            v = w * v + (1.0 - w) * value(field, e.second(), p, seed, x, z);
        }
        // A belt's floodplain belongs to the belt, whichever boundary is nearer: read off the nearer boundary alone,
        // it stopped on the line where a quiet coast took over as nearest, and the plain's few blocks of lift ended
        // there in a dead-straight shore.
        double apron = apronAt(e, p);
        return switch (field) {
            case RELIEF -> v + APRON_LIFT * apron + 0.25 * HotspotMap.plumeStrength(seed, x, z, p);
            case EROSION -> v + 0.25 * apron;
            default -> v;
        };
    }

    /** How deep into a belt's floodplain a column lies, from whichever of its two boundaries says it is deeper. */
    public static double apronAt(long seed, GeologyParams p, int x, int z) {
        return apronAt(edgesAt(seed, p, x, z), p);
    }

    private static double apronAt(TectonicMap.Edges e, GeologyParams p) {
        return Math.max(apron(e.first(), p), apron(e.second(), p));
    }

    /** How much further than the nearest boundary the next one can be and still shape the ground, in blocks. */
    private static final double HANDOVER = 64.0;

    /** One field from one boundary. */
    private static double value(Field field, PlateSample s, GeologyParams p, long seed, int x, int z) {
        return switch (field) {
            case CONTINENTS -> continents(s, p);
            case EROSION -> erosion(s, p, seed, x, z);
            // No sharp peaks on a valley floor: the ridge noise that makes them is turned down there.
            case RIDGES -> 0.5 + (worn(s, seed) ? WORN_RIDGES : 0.9) * belt(s, p);
            case RELIEF -> relief(s, p, seed, x, z);
            // A mountain belt's grip, for the offset to scale vanilla's mountain spline down by inside the belt, and
            // how deep in one of the belt's valleys the column lies, for the offset to cut that spline further. A
            // rift keeps vanilla's full spline: halving it there lifted the rift floors out of their lakes.
            case BELT -> mountainBelt(s, p);
            case VALLEY -> valley(seed, p, x, z) * mountainBelt(s, p);
            // 1 on a ridge, 0 in the pass between: for the offset to bend vanilla's mountain spline into the same
            // ridges and V-shaped valleys the mod's own relief follows, or the two cut across each other and the
            // valleys vanished under vanilla's peaks.
            case CREST -> crestField(s, p, seed, x, z);
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

    private static double erosion(PlateSample s, GeologyParams p, long seed, int x, int z) {
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
        double b = belt(s, p);
        double rugged = b * calm(s, p) * switch (k) {
            case CONVERGENT_SUBDUCTION, CONVERGENT_COLLISION -> 2.0;
            case TRANSFORM -> 1.2;
            case DIVERGENT -> 0.9;
            case INTERIOR -> 0.0;
        };
        boolean convergent = k == FaultType.CONVERGENT_COLLISION || k == FaultType.CONVERGENT_SUBDUCTION;
        double flat = convergent ? VALLEY_FLAT * valley(seed, p, x, z) * b : 0.0;
        return quiet - rugged + flat;
    }

    private static double relief(PlateSample s, GeologyParams p, long seed, int x, int z) {
        double a = across(s, p);
        if (s.plateKind().isOceanic()) return oceanRelief(s, p, a, seed, x, z);
        double u = p.uplift() / 128.0 * (0.5 + 0.5 * motion(s));
        double t = Math.min(1.0, a / p.beltFactor());
        // The floodplain's lift ({@link #APRON_LIFT}) is added in {@link #field}, from both boundaries.
        double cut = 1.0 - VALLEY_DEPTH * valley(seed, p, x, z);
        return switch (s.boundaryType()) {
            case CONVERGENT_COLLISION -> worn(s, seed)
                    ? WORN_RELIEF * u * bump(t) * crest(seed, p, x, z, WORN_CREST_SHARE) * cut
                    : u * bump(t) * crest(seed, p, x, z, CREST_SHARE) * cut;
            // The arc stands where the volcanoes do, on a plateau that fades out across the belt and keeps clear of
            // the coast; the trench lies off the coast, on the plate that goes under. The arc's mountains drop
            // steeply to the sea and go down slowly inland, as the Andes do to the altiplano, and rise and fall
            // along the coast with the crest noise: massifs and the saddles between them.
            case CONVERGENT_SUBDUCTION -> s.overridingSide()
                    ? u * (ARC_RISE * peak(a, ARC_AT, ARC_SEA_HALF, ARC_LAND_HALF)
                            * (1.0 - ARC_CREST * (1.0 - crestShape(seed, p, x, z))) + 0.3 * bump(t) * calm(s, p)) * cut
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
            double hills = 1.0 - Math.abs(noise(seed, x, z, HILL_SCALE * p.horizontal(), 0x4B1DL));
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

    /**
     * Over this share of the belt's width, at its outer edge, the grip fades to nothing with a level tangent. The
     * grip itself is a square root of the distance and stands vertical at the belt's edge, so the erosion left the
     * quiet ground at a jump there and vanilla's spline stood the belt's front up as a wall along the plain. The
     * outer half: at three tenths the front still fell a spline step in a hundred blocks just inside the taper,
     * where the square root's grip was steep, and the highland dropped to the plain as a bank.
     */
    private static final double EDGE_TAPER = 0.5;

    /** The boundary's grip over the whole mountain belt: 1 on the line, 0 at the belt's edge. */
    public static double belt(PlateSample s, GeologyParams p) {
        double t = Math.min(1.0, across(s, p) / p.beltFactor());
        double taper = smooth(Mth.clamp((1.0 - t) / EDGE_TAPER, 0, 1));
        return Mth.clamp(s.belt(p.faultWidth(), p.beltFactor()) * taper, 0.0, 1.0);
    }

    /**
     * {@link #belt} where the boundary raises mountains, on either side of it: a collision, a subduction margin or a
     * transform fault, whose ranges are vanilla's erosion spline alone and stood as walls too. 0 at a rift and in the
     * interior. Both sides, because the two plates meet on a line: a grip that dropped to nothing on the plate going
     * under put a wall along every subduction coast.
     */
    private static double mountainBelt(PlateSample s, GeologyParams p) {
        FaultType k = s.boundaryType();
        return k == FaultType.DIVERGENT || k == FaultType.INTERIOR ? 0.0 : belt(s, p);
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
    public static double jitter(long seed, GeologyParams p, int x, int z) {
        return noise(seed, x, z, 40.0 * p.horizontal(), 0x6A17L);
    }

    /**
     * A slower wobble in -1..1, over a few hundred blocks, for a border that wanders in and out instead of running
     * parallel to the boundary: {@link #jitter} rags a line, this one bends it. The plain's edge, drawn at one depth
     * of apron, stood as a straight strip of forest along the belt.
     */
    public static double jitterWide(long seed, GeologyParams p, int x, int z) {
        return noise(seed, x, z, 220.0 * p.horizontal(), 0x1D4EL);
    }

    /**
     * Whether the collision belt at this boundary is an old one, worn down. Decided from the two plates' identities
     * alone, so a belt is worn along its whole length and both its sides agree.
     */
    public static boolean worn(PlateSample s, long seed) {
        if (s.boundaryType() != FaultType.CONVERGENT_COLLISION) return false;
        long lo = Math.min(s.plateId(), s.neighbourId()), hi = Math.max(s.plateId(), s.neighbourId());
        return SeedHash.rand01(SeedHash.mix(seed ^ 0x57A9L ^ lo * 0x9E3779B97F4A7C15L ^ SeedHash.mix(hi ^ 0x57A9L))) < WORN_SHARE;
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

    /** How much of a fold belt's uplift a column keeps: all of it on a crest, {@code share} less in the passes between. */
    private static double crest(long seed, GeologyParams p, int x, int z, double share) {
        return 1.0 - share * (1.0 - crestShape(seed, p, x, z));
    }

    /**
     * The crest the offset reads, 1 on a ridge and 0 in a pass. The data bends vanilla's mountain spline by
     * {@code (1 - SPLINE_CUT*belt) * (1 - CREST_SHARE*belt*(1 - crest))}, one formula for every belt. A worn belt
     * wants a lower spline and shallower passes, {@code (1 - WORN_SPLINE*belt) * (1 - WORN_CREST_SHARE*belt*(1 - shape))}:
     * the crest handed over there is the value that brings the data's two factors to that product.
     */
    private static double crestField(PlateSample s, GeologyParams p, long seed, int x, int z) {
        double shape = crestShape(seed, p, x, z);
        if (!worn(s, seed)) return shape;
        double b = mountainBelt(s, p);
        if (b < 1e-6) return shape;
        double want = (1.0 - WORN_SPLINE * b) * (1.0 - WORN_CREST_SHARE * b * (1.0 - shape));
        double have = 1.0 - SPLINE_CUT * b;
        return Mth.clamp(1.0 - (1.0 - want / have) / (CREST_SHARE * b), 0.0, 1.0);
    }

    /**
     * Where a column stands between ridge and pass, 1 to 0. A ridge keeps its full height across a narrow crest,
     * then the ground falls off in a V to the pass: the noise's zero line is the pass, its extremes the ridges.
     */
    private static double crestShape(long seed, GeologyParams p, int x, int z) {
        // Value noise seldom reaches its extremes: with the ridge at 1 the ground was nearly all pass, a weak
        // ripple on a uniformly lowered belt. The ridge is reached at CREST_RIDGE, which the noise crosses often.
        return Mth.clamp((Math.abs(twoOctaves(seed, x, z, CREST_SCALE * p.horizontal(), 0x2F0DL)) - CREST_TOP)
                / CREST_RIDGE, 0, 1);
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

    /** {@link #peak} with its own width on each side: {@code nearHalf} towards the boundary, {@code farHalf} away. */
    private static double peak(double a, double centre, double nearHalf, double farHalf) {
        return bump(Math.min(1.0, a < centre ? (centre - a) / nearHalf : (a - centre) / farHalf));
    }

    /** How deep in a valley a column lies, 0 on the ridges to 1 across the whole floor. */
    private static double valley(long seed, GeologyParams p, int x, int z) {
        double off = Math.abs(twoOctaves(seed, x, z, VALLEY_SCALE * p.horizontal(), 0x5A11L)) - VALLEY_FLOOR;
        return smooth(Mth.clamp(1.0 - off / VALLEY_SIDE, 0, 1));
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
