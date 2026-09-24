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

    public enum Field { CONTINENTS, EROSION, RIDGES, RELIEF, VARIETY, BELT, RANGE, VALLEY, CREST, MEANDER_X, MEANDER_Z, DEM, GRIP, SPLINE, GRABEN, LANDMARK }

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
    private static final double ARC_AT = 0.55, ARC_SEA_HALF = 0.5, ARC_LAND_HALF = 1.3;
    /**
     * How high an arc stands, in uplift, and how much of that the passes between its massifs give up: an arc is a
     * chain of volcanic massifs and the saddles between them, along the same crest noise a fold belt follows, not
     * one level ridge along the coast.
     */
    private static final double ARC_RISE = 1.05, ARC_CREST = 0.45;
    /**
     * Vanilla's mountain regime starts where erosion falls under about -0.4. A belt's erosion fell there in its outer
     * half, before the mod's own uplift had begun, and vanilla stood the front of every range and arc up as a bank
     * over the plain. Out to FRONT_TO belt widths the erosion is held at FRONT_EROSION (hills, no peaks), and the
     * floor is let go over FRONT_OVER inside that, where the uplift is already half its height.
     */
    private static final double FRONT_EROSION = -0.3, FRONT_TO = 0.5, FRONT_OVER = 0.2;
    /**
     * How rugged a transform fault's belt is on the line, and over how many fault widths the ocean's side of an
     * ocean-continent transform lets that go: the continent's flank is steep where it meets the sea.
     */
    private static final double TRANSFORM_RUGGED = 1.2, TRANSFORM_SEA_SLOPE = 0.5;
    /** Where a trench lies off a subduction coast. */
    private static final double TRENCH_AT = 0.3, TRENCH_HALF = 0.35;
    /**
     * A rift: a flat floor out to {@code GRABEN_FLOOR}, then two fault scarps up out of it, each {@code STEP_WIDTH}
     * across and half the drop, the second starting at {@code GRABEN_STEP}. The floor used to rise to the rim as a
     * square root, which stands vertical at the rim.
     */
    private static final double GRABEN_FLOOR = 0.112, GRABEN_STEP = 0.21, STEP_WIDTH = 0.042;
    private static final double SHOULDER_AT = 0.7, SHOULDER_HALF = 0.4;
    /** Where a rift's floor starts to take the ruggedness of its shoulders, and over how far. */
    private static final double RIFT_CALM_FROM = 0.1, RIFT_CALM_OVER = 0.6;

    /** How much narrower a rift is than its first drawing, in both worlds: at full width it read as a lowland. */
    private static final double RIFT_NARROW = 0.75;

    /**
     * A column's distance from a rift's axis, in fault widths, measured on a shorter ruler.
     *
     * <p>Nine numbers set a rift's shape -- the graben floor, the two fault steps, the shoulder and its width, the
     * two ends of the quiet ramp, the fade of the graben field and the biome's own half width -- and narrowing it
     * by hand means changing all nine together. Dividing the distance instead does the same arithmetic in one
     * place and cannot drift out of step.</p>
     */
    static double riftAcross(PlateSample s, GeologyParams p) {
        return across(s, p) / RIFT_NARROW;
    }
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
    /**
     * How far the ridge noise is pushed about, in its noise units (four blocks each), and the length of a bend, at
     * three sizes: the sweep of a whole reach, the bends in it, and the wobble of the bank. One bend length gave a
     * river the same gentle curve everywhere; a real river turns on every scale at once.
     */
    private static final double[] MEANDER_AMPLITUDE = {24.0, 12.0, 5.0}, MEANDER_SCALE = {700.0, 260.0, 80.0};
    /** The furthest the ridge noise is ever pushed: the three amplitudes together, for the density function's bounds. */
    public static final double MEANDER_REACH = 41.0 * (1.0 + 0.75);
    /** How much further a river swings on its floodplain, at the plain's heart: the plain is where a river meanders. */
    private static final double MEANDER_PLAIN = 0.75;

    /** How rugged ground is where no boundary reaches it: vanilla's erosion, where higher is flatter. */
    private static final double OCEAN_EROSION = 0.5, INTERIOR_EROSION = 0.45;

    /**
     * Over how many fault widths the two crusts' quiet ground meets across a boundary.
     *
     * <p>A column reads the boundary from the plate it stands on, and crossing the line changes that plate: on one
     * side the quiet ground is a flat sea floor, on the other a worn interior. Nothing smoothed the change. The
     * blend in {@link #field} is for junctions -- it fades the second and third boundaries in as they come as near
     * as the nearest -- and at a plain margin the next boundary is over a thousand blocks away and takes no share
     * at all, so the field simply stepped by the difference between the two numbers above.</p>
     *
     * <p>A twentieth of erosion sounds like nothing, and it is, except that 0.45 and 0.5 sit on either side of a
     * break in vanilla's offset spline. Measured at a subduction margin in the tall world, four blocks across the
     * line took the ground from 182 to 103: a sheer face eighty blocks tall, dead straight, running the length of
     * the boundary, and picked out in bare rock because a face that steep is a cliff to the surface rules. That is
     * the wall reported from three test rounds running.</p>
     *
     * <p>Blending to the halfway point on the line makes both sides agree there, so the margin comes down over a
     * fault width instead of over four blocks. Where both sides are the same crust -- a collision belt, an ocean
     * ridge -- the two numbers are equal and nothing changes at all.</p>
     */
    private static final double CRUST_BLEND = 1.0;

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
        // Only this plate's own second boundary: the second may be the plate across the line's, which would put
        // the column's rock and role on the wrong plate.
        if (e.gap() >= HANDOVER * p.horizontal() || e.second().plateId() != e.first().plateId()) return e.first();
        return relief(e.second(), p, seed, x, z) > relief(e.first(), p, seed, x, z) ? e.second() : e.first();
    }

    /** How far off a column the cached 4-block cell can put the nearest line, in blocks: the cell's diagonal and a bit. */
    private static final double CELL_SLACK = 6.0;

    /**
     * How far this column is from the axis of a rift between two continents, in blocks, on the same warped ground the
     * graben itself is laid out on; -1 where the nearest boundary is no such rift or its axis is further than
     * {@code reach}. The cached edges are read on a four-block grid, which cannot draw a strip a few blocks wide, so
     * the exact ones are read -- but only where the cached cell already puts the axis near.
     */
    public static double riftAxisDistance(long seed, GeologyParams p, int x, int z, double reach) {
        if (!continentalRift(edgesAt(seed, p, x, z).first(), reach + CELL_SLACK)) return -1;
        double h = p.horizontal();
        double wx = x + WARP_AMPLITUDE * h * twoOctaves(seed, x, z, WARP_SCALE * h, 0x77A1L);
        double wz = z + WARP_AMPLITUDE * h * twoOctaves(seed, x, z, WARP_SCALE * h, 0x3B2CL);
        PlateSample s = TectonicMap.sampleSeededEdges(seed, (int) Math.floor(wx), (int) Math.floor(wz), p).first();
        return continentalRift(s, reach) ? s.faultDistance() : -1;
    }

    /** A rift between two continents within {@code reach} blocks of its line: a continent-ocean one is a coast. */
    private static boolean continentalRift(PlateSample s, double reach) {
        return s.boundaryType() == FaultType.DIVERGENT && s.faultDistance() <= reach
                && !s.plateKind().isOceanic() && !s.neighbourKind().isOceanic();
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
            long salt = field == Field.MEANDER_X ? 0x3E11L : 0x71C3L;
            double push = 0.0;
            for (int i = 0; i < MEANDER_SCALE.length; i++) {
                push += MEANDER_AMPLITUDE[i] * twoOctaves(seed, x, z, MEANDER_SCALE[i] * p.horizontal(), salt + i * 0x9F1L);
            }
            return push * (1.0 + MEANDER_PLAIN * apronAt(seed, p, x, z));
        }
        // A named mountain is not read off a boundary: it stands where it was put, and the blend below would only
        // dilute it with the country round it.
        if (field == Field.LANDMARK) return landmark(seed, p, x, z);
        TectonicMap.Edges e = edgesAt(seed, p, x, z);
        // A column near more than one boundary is shaped by all of them: its own plate's two nearest and, near the
        // line, the boundaries of the plate across it (see TectonicMap.computeEdges), each fading in over HANDOVER
        // as it comes as near as the nearest. Two boundaries at the same distance take the same share, so the
        // second or third changing identity is continuous; both sides of a line see the same set, so crossing it is.
        double handover = HANDOVER * p.horizontal();
        double v = value(field, e.first(), p, seed, x, z), weights = 1.0;
        for (int i = 0; i < e.rest().length; i++) {
            double w = weight(e, i, handover);
            if (w <= 0) continue;
            v += w * value(field, e.rest()[i], p, seed, x, z);
            weights += w;
        }
        v /= weights;
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

    /** The three boundaries a column is blended from and the share each takes, for finding a step in a field. */
    public static String debugAt(long seed, GeologyParams p, int x, int z) {
        TectonicMap.Edges e = edgesAt(seed, p, x, z);
        double handover = HANDOVER * p.horizontal();
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "first %s d %.0f plate %d e %.3f",
                e.first().boundaryType(), e.first().faultDistance(), e.first().plateId(), erosion(e.first(), p, seed, x, z)));
        for (int i = 0; i < e.rest().length; i++) {
            PlateSample s = e.rest()[i];
            sb.append(String.format(java.util.Locale.ROOT, " | %s gap %.0f w %.3f plate %d e %.3f", s.boundaryType(),
                    e.gaps()[i], weight(e, i, handover), s.plateId(), erosion(s, p, seed, x, z)));
        }
        return sb.toString();
    }

    /**
     * How much of the i-th further boundary a column takes: by how nearly as near it is as the first, and, lent by
     * another plate, by how near that plate's own ground is -- so a lent boundary neither switches on at a set distance
     * nor reaches, as a line, deep into the plate the column stands in.
     */
    private static double weight(TectonicMap.Edges e, int i, double handover) {
        double gap = e.gaps()[i];
        if (gap >= handover) return 0.0;
        double w = smooth(1.0 - Math.max(0.0, gap) / handover);
        return w * smooth(1.0 - Math.min(1.0, e.lends()[i] / handover));
    }

    /** How deep into a belt's floodplain a column lies, from whichever of its two boundaries says it is deeper. */
    public static double apronAt(long seed, GeologyParams p, int x, int z) {
        return apronAt(edgesAt(seed, p, x, z), p);
    }

    private static double apronAt(TectonicMap.Edges e, GeologyParams p) {
        return Math.max(apron(e.first(), p), Math.max(apron(e.second(), p), apron(e.third(), p)));
    }


    /**
     * How much further than the nearest boundary the next can be and still shape the ground, in blocks. A mountain
     * belt ends where its boundary meets another at a junction, and its full height has to come down to the other
     * boundary's ground over this distance: at sixty-four the end of a collision belt was a four-to-one wall.
     * {@link TectonicMap#NEIGHBOUR_REACH} is the same distance.
     */
    private static final double HANDOVER = TectonicMap.NEIGHBOUR_REACH;

    /** One field from one boundary. */
    private static double value(Field field, PlateSample s, GeologyParams p, long seed, int x, int z) {
        return switch (field) {
            // Answered before the blend, from where it stands rather than from a boundary; never asked here.
            case LANDMARK -> 0.0;
            case CONTINENTS -> continents(s, p);
            case EROSION -> erosion(s, p, seed, x, z);
            // No sharp peaks on a valley floor: the ridge noise that makes them is turned down there.
            case RIDGES -> 0.5 + (worn(s, seed) ? WORN_RIDGES : 0.9) * belt(s, p);
            case RELIEF -> relief(s, p, seed, x, z);
            // A mountain belt's grip, for the offset to scale vanilla's mountain spline down by inside the belt, and
            // how deep in one of the belt's valleys the column lies, for the offset to cut that spline further. A
            // rift keeps vanilla's full spline: halving it there lifted the rift floors out of their lakes.
            case BELT -> mountainBelt(s, p);
            case RANGE -> rangeBelt(s, p);
            case VALLEY -> valley(seed, p, x, z) * mountainBelt(s, p);
            // 1 on a ridge, 0 in the pass between: for the offset to bend vanilla's mountain spline into the same
            // ridges and V-shaped valleys the mod's own relief follows, or the two cut across each other and the
            // valleys vanished under vanilla's peaks.
            case CREST -> crestField(s, p, seed, x, z);
            // Real mountain ground for the belts that take their shape from it, how much of the column it shapes,
            // and what is left of vanilla's own mountain spline there.
            case DEM -> dem(s, p, seed, x, z);
            case GRIP -> demGrip(s, p);
            case SPLINE -> spline(s, p, seed, x, z);
            // The floor of a rift, where the ground lies under the sea's level and the aquifer fills it: vanilla's
            // cave pillars stood there as rock columns in open water, water over them and under them.
            case GRABEN -> s.boundaryType() == FaultType.DIVERGENT && !s.plateKind().isOceanic()
                    ? smooth(Mth.clamp((GRABEN_STEP + STEP_WIDTH - riftAcross(s, p)) / 0.15, 0, 1)) : 0.0;
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
        // The quiet ground a plate has away from its edge: a flat sea floor, a worn interior, and on the line the
        // two crusts meet halfway, which is the one thing that makes the field the same from both sides.
        double mine = oceanic ? OCEAN_EROSION : INTERIOR_EROSION;
        double theirs = s.neighbourKind().isOceanic() ? OCEAN_EROSION : INTERIOR_EROSION;
        double quiet = mine + (theirs - mine)
                * 0.5 * smooth(Mth.clamp(1.0 - across(s, p) / CRUST_BLEND, 0, 1));
        FaultType k = s.boundaryType();
        // A transform between an ocean and a continent: the continent's side is rugged down to the line, and an ocean
        // floor that stayed quiet up to it stood the two a whole erosion unit apart there -- a sheer wall hundreds of
        // blocks high where one ran through K2. The sea floor takes the same ruggedness on the line and lets it go
        // over a short slope, the continent's flank going down into the water.
        if (oceanic && k == FaultType.TRANSFORM && !s.neighbourKind().isOceanic()) {
            double slope = smooth(Mth.clamp(1.0 - across(s, p) / TRANSFORM_SEA_SLOPE, 0, 1));
            return quiet - belt(s, p) * TRANSFORM_RUGGED * slope;
        }
        // A sea floor is flat except where it is being made or destroyed: the hills of a spreading ridge and the
        // islands of an arc are the exceptions.
        if (oceanic && !s.overridingSide() && k != FaultType.DIVERGENT) return quiet;
        // Every belt starts from that quiet ground and works down from it, so the ruggedness fades out where the
        // belt does instead of ending in a ring one belt width from the boundary. Vanilla's peaks want erosion
        // under -0.4, which a belt's core reaches while its foothills stay gentle.
        double b = belt(s, p);
        double rugged = b * calm(s, p) * switch (k) {
            case CONVERGENT_SUBDUCTION, CONVERGENT_COLLISION -> 2.0;
            case TRANSFORM -> TRANSFORM_RUGGED;
            case DIVERGENT -> 0.9;
            case INTERIOR -> 0.0;
        };
        boolean convergent = k == FaultType.CONVERGENT_COLLISION || k == FaultType.CONVERGENT_SUBDUCTION;
        double flat = convergent ? VALLEY_FLAT * valley(seed, p, x, z) * b : 0.0;
        double e = quiet - rugged + flat;
        if (k == FaultType.DIVERGENT || k == FaultType.INTERIOR) return e;
        double t = Math.min(1.0, across(s, p) / p.beltFactor());
        double floor = FRONT_EROSION - (1.5 + FRONT_EROSION) * smooth(Mth.clamp((FRONT_TO - t) / FRONT_OVER, 0, 1));
        return Math.max(e, floor);
    }

    /** The relief the plates themselves make, where no real mountain ground shapes the column. */
    private static double relief(PlateSample s, GeologyParams p, long seed, int x, int z) {
        double a = across(s, p);
        if (s.plateKind().isOceanic()) return oceanRelief(s, p, a, seed, x, z);
        double u = p.uplift() / 128.0 * (0.5 + 0.5 * motion(s));
        double t = Math.min(1.0, a / p.beltFactor());
        // The floodplain's lift ({@link #APRON_LIFT}) is added in {@link #field}, from both boundaries.
        double cut = 1.0 - VALLEY_DEPTH * valley(seed, p, x, z);
        // Where real mountain ground shapes the column it is the relief: the plates' own uplift fades out under it,
        // and comes back at the belt's edge and wherever the crops are not read (an ocean, a rift, no crops at all).
        double own = 1.0 - demGrip(s, p);
        return own * switch (s.boundaryType()) {
            // The ridges and passes fade out at the belt's edge with its grip: at full share out to the edge the first
            // ridge stood up from the plain as a bank.
            case CONVERGENT_COLLISION -> worn(s, seed)
                    ? WORN_RELIEF * u * bump(t) * crest(seed, p, x, z, WORN_CREST_SHARE * crestGrip(s, p)) * cut
                    : u * bump(t) * crest(seed, p, x, z, CREST_SHARE * crestGrip(s, p)) * cut;
            // The arc stands where the volcanoes do, on a plateau that fades out across the belt and keeps clear of
            // the coast; the trench lies off the coast, on the plate that goes under. The arc's mountains drop
            // steeply to the sea and go down slowly inland, as the Andes do to the altiplano, and rise and fall
            // along the coast with the crest noise: massifs and the saddles between them.
            case CONVERGENT_SUBDUCTION -> s.overridingSide()
                    ? u * (ARC_RISE * peak(a, ARC_AT, ARC_SEA_HALF, ARC_LAND_HALF)
                            * (1.0 - ARC_CREST * (1.0 - crestShape(seed, p, x, z))) + 0.3 * bump(t) * calm(s, p)) * cut
                    : -0.25 * peak(a, TRENCH_AT, TRENCH_HALF);
            case DIVERGENT -> {
                double r = riftAcross(s, p);
                yield graben(r) + SHOULDER_RISE * peak(r, SHOULDER_AT, SHOULDER_HALF);
            }
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

    /** Where a subduction margin's own mountains stand: the arc and its front, not the country behind it. */
    private static final double RANGE_ARC_TO = 0.75;

    /**
     * How far a column lies across a subduction boundary, signed: positive on the plate riding over, negative on
     * the one going down.
     *
     * <p>An arc's range is read from the overriding plate, and the test for which plate that is was a boolean. A
     * column reads the boundary from the plate it stands on, so crossing the line flipped the answer, and with it
     * the whole real-ground envelope: on one side the crop stood at its full height, on the other it was not there
     * at all. Nothing blended the two, because the blend in {@link #field} fades in the <em>second and third</em>
     * boundaries at a junction and at a plain margin they are a thousand blocks away.</p>
     *
     * <p>Measured in the tall world at a subduction margin: four blocks across the line took the real ground from
     * 0.549 to 0, the range field from 0.79 to 0 and the ground from 182 to 103. An eighty-block sheer face, dead
     * straight, running the length of the boundary, and bare rock because a face that steep is a cliff to the
     * surface rules. That is the wall reported from three test rounds running, and it is not a river.</p>
     *
     * <p>Signing the distance instead lets the arc's sea-side flank carry on across the line and die out over the
     * trench, which is what a margin does. On the line itself both sides read the same number, so the field is
     * continuous there by construction rather than by a tuned width.</p>
     */
    private static double arcAcross(PlateSample s, GeologyParams p) {
        double a = across(s, p);
        return s.overridingSide() ? a : -a;
    }

    /**
     * The belt of a range that is really there, as against {@link #mountainBelt}, which also answers along a
     * transform fault and out behind a subduction arc. The tall world multiplies its ground by this, so it has to
     * mean "a range stands here": on the broader belt it was raising vanilla's badlands two and a half times as
     * well, and that is how a mesa came to stand at y 500 with its top stripped to bare rock.
     */
    private static double rangeBelt(PlateSample s, GeologyParams p) {
        FaultType k = s.boundaryType();
        if (k == FaultType.CONVERGENT_COLLISION) return belt(s, p);
        if (k == FaultType.CONVERGENT_SUBDUCTION) {
            // The same envelope the real ground uses, so the two rise and fall together instead of one of them
            // ending at a line the other carries straight through.
            return belt(s, p) * peak(arcAcross(s, p), ARC_AT, DEM_ARC_SEA, DEM_ARC_LAND);
        }
        return 0.0;
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
        double a = across(s, p);
        FaultType k = s.boundaryType();
        // An ocean plate riding over another is an island arc's, and its ruggedness starts back from the trench as a
        // continent's does: taken in full on the line, the sea floor stood a two-unit erosion step against the
        // quiet plate going under.
        if (k == FaultType.CONVERGENT_SUBDUCTION && s.overridingSide()) {
            return smooth(Mth.clamp((a - COAST_LOWLAND) / COAST_FOOTHILLS, 0, 1));
        }
        if (s.plateKind().isOceanic()) return 1.0;
        // Vanilla turns erosion into height steeply, so the rift's flat floor hands over to its rugged shoulders slowly:
        // over a third of a fault width the change stood as a fifty-block step.
        if (k == FaultType.DIVERGENT) {
            return smooth(Mth.clamp((riftAcross(s, p) - RIFT_CALM_FROM) / RIFT_CALM_OVER, 0, 1));
        }
        return 1.0;
    }

    /** A rift's floor and the two fault scarps up out of it, in relief units: flat, half the drop, then the rim. */
    private static double graben(double a) {
        double first = smooth(Mth.clamp((a - GRABEN_FLOOR) / STEP_WIDTH, 0, 1));
        double second = smooth(Mth.clamp((a - GRABEN_STEP) / STEP_WIDTH, 0, 1));
        return GRABEN_DEEP * (1.0 - 0.5 * first - 0.5 * second);
    }

    // === Real mountains ======================================================

    /**
     * Over this share of a belt, from the boundary out, the real ground shapes it in full; over the rest it fades to
     * nothing, so a range comes down to the plain instead of ending at a cliff where the crop does.
     */
    private static final double DEM_FADE = 0.6;

    /** How much of vanilla's mountain spline the real ground takes over where it holds. */
    private static final double SPLINE_DEM = 0.9;

    /** How far either side of an arc's line the real ground reaches: wider than the arc's own rise, or the range
     * would be squeezed into it and stand as cliffs. */
    private static final double DEM_ARC_SEA = 0.8, DEM_ARC_LAND = 1.8;

    /** Metres of real ground a block stands for in the normal world; the tall world is the same ground at ten. */
    static final double METRES_PER_BLOCK = 25.0;

    /**
     * How tall the highest of the three named mountains stands over its own valley floor, in blocks.
     *
     * <p>Everest's crop is four and a half kilometres from its floor to its summit, which at ten metres to the
     * block is four hundred and fifty. Asked for eight hundred, so all three are lifted by the one factor that
     * gets the tallest of them there -- about one and three quarters. They keep their heights relative to each
     * other, which is the part worth keeping: K2 comes out a little under Everest and the Matterhorn a good deal
     * under both, as they are.</p>
     */
    private static final double LANDMARK_BLOCKS = 800.0;

    /** Over what share of its crop a named mountain comes down to the ground it stands on. */
    private static final double LANDMARK_FADE = 0.3;

    /**
     * The three named mountains: Everest, K2 and the Matterhorn, laid on the ground where {@link LandmarkSites}
     * put them, at their own shape and at a height the whole of which is the same lift.
     */
    private static double landmark(long seed, GeologyParams p, int x, int z) {
        if (p.horizontal() < 1.5 || !DemLibrary.landmarksReady()) return 0.0;
        LandmarkSites.Site s = LandmarkSites.near(seed, p, x, z);
        if (s == null) return 0.0;
        double mpb = METRES_PER_BLOCK / p.horizontal();
        double dx = (x - s.x()) * mpb, dz = (z - s.z()) * mpb;
        double c = Math.cos(s.bearing()), sn = Math.sin(s.bearing());
        double along = dx * c + dz * sn, across = -dx * sn + dz * c;
        double m = DemLibrary.landmark(s.which(), along, across);
        if (m <= 0.0) return 0.0;
        double env = fade(Math.abs(along) / DemLibrary.landmarkHalfAlong(s.which()))
                * fade(Math.abs(across) / DemLibrary.landmarkHalfAcross(s.which()));
        if (env <= 0.0) return 0.0;
        double lift = LANDMARK_BLOCKS * mpb / DemLibrary.landmarkTallest();
        return env * m * lift / mpb / 128.0;
    }

    /** One over the middle of a crop, nothing at its edge: the mountain has to meet the country it stands in. */
    private static double fade(double t) {
        return smooth(Mth.clamp((1.0 - t) / LANDMARK_FADE, 0, 1));
    }

    /** How much of a named mountain a column carries, for the biome to take its name from it. */
    public static double landmarkShare(long seed, GeologyParams p, int x, int z) {
        return landmark(seed, p, x, z) * 128.0 / LANDMARK_BLOCKS;
    }

    /**
     * How much of its range a belt raises at a standstill, and how much the rest of the closing adds. The crops are
     * measured from their own valley floor, so this scales the height of the mountains over their valleys and not,
     * as it once did, the whole range together with its floor: at six tenths a slow collision had the Alps squashed
     * to three fifths and read as a plateau at one height.
     */
    private static final double DRIVE_STILL = 0.8;

    /**
     * The roughness a ninety-metre grid cannot carry: scree, gullies and crags, in metres, over a wavelength in
     * metres. Without it the crop is a smooth height field, and a smooth height field rounded to whole blocks is a
     * contour map -- flats a dozen blocks wide with a one-block riser between them, all the way up the mountain.
     */
    private static final double ROUGH_METRES = 50.0, ROUGH_WAVE = 900.0;

    /**
     * The real mountains, in offset units: a crop of the Alps, the Caucasus, the Himalaya, the Karakoram or the
     * Appalachians, read at the column's place along and across the boundary and laid on the ground at true scale,
     * twenty-five metres to the block in the normal world and ten in the tall one.
     */
    private static double dem(PlateSample s, GeologyParams p, long seed, int x, int z) {
        double env = demGrip(s, p);
        if (env <= 0.0) return 0.0;
        double mpb = METRES_PER_BLOCK / p.horizontal();
        double a = across(s, p);
        double acrossM = s.boundaryType() == FaultType.CONVERGENT_SUBDUCTION
                ? (arcAcross(s, p) - ARC_AT) * p.faultWidth() * mpb
                : (Long.compareUnsigned(s.plateId(), s.neighbourId()) < 0 ? a : -a) * p.faultWidth() * mpb;
        long lo = Math.min(s.plateId(), s.neighbourId()), hi = Math.max(s.plateId(), s.neighbourId());
        long pair = SeedHash.mix(lo * 0x9E3779B97F4A7C15L ^ SeedHash.mix(hi ^ 0x0DE31L));
        DemLibrary.Kind kind = worn(s, seed) ? DemLibrary.Kind.WORN : DemLibrary.Kind.YOUNG;
        double metres = DemLibrary.metres(seed, pair, kind, p.horizontal() > 1.5, s.along() * mpb, acrossM);
        // A belt that is barely closing keeps a lower range, as the plates' own uplift did.
        double drive = DRIVE_STILL + (1.0 - DRIVE_STILL) * motion(s);
        metres += ROUGH_METRES * twoOctaves(seed, x, z, ROUGH_WAVE / mpb, 0x70C4L);
        return env * drive * p.demScale() * metres / mpb / 128.0;
    }

    /** How much of the column the real ground shapes: 1 in the belt's core, 0 at its edge, 0 where it has none. */
    private static double demGrip(PlateSample s, GeologyParams p) {
        if (!DemLibrary.available()) return 0.0;
        // An ocean floor has no range of its own -- except the one on the margin it is diving under, whose sea-side
        // flank comes down over the trench. Reading this off the plate the column stands on is what cut the crop
        // in half along the line: the ocean plate answered no before the envelope was ever asked.
        if (s.plateKind().isOceanic() && s.boundaryType() != FaultType.CONVERGENT_SUBDUCTION) return 0.0;
        double a = across(s, p);
        return switch (s.boundaryType()) {
            case CONVERGENT_COLLISION -> {
                double t = Math.min(1.0, a / p.beltFactor());
                yield smooth(Mth.clamp((1.0 - t) / DEM_FADE, 0, 1));
            }
            // An arc stands where its volcanoes do, not on the line: the range is read from there, steep to the sea
            // and long inland.
            case CONVERGENT_SUBDUCTION -> peak(arcAcross(s, p), ARC_AT, DEM_ARC_SEA, DEM_ARC_LAND);
            default -> 0.0;
        };
    }

    /**
     * What the offset keeps of vanilla's mountain spline: less of it inside a belt, which has its own relief, and
     * almost none where the real ground shapes the column, or vanilla's peaks cut across the range's own.
     */
    private static double spline(PlateSample s, GeologyParams p, long seed, int x, int z) {
        double b = mountainBelt(s, p);
        double shape = crestShape(seed, p, x, z);
        double cut = worn(s, seed)
                ? (1.0 - WORN_SPLINE * b) * (1.0 - WORN_CREST_SHARE * b * (1.0 - shape))
                : (1.0 - SPLINE_CUT * b) * (1.0 - CREST_SHARE * b * (1.0 - shape));
        return cut * (1.0 - SPLINE_DEM * demGrip(s, p));
    }

    /** How much of the crest share a column takes: all of it inside CREST_FULL_BELT of grip, less towards the edge. */
    private static double crestGrip(PlateSample s, GeologyParams p) {
        return Math.min(1.0, belt(s, p) / CREST_FULL_BELT);
    }

    private static final double CREST_FULL_BELT = 0.8;

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
