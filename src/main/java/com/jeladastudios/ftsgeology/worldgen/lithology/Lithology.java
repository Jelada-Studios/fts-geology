package com.jeladastudios.ftsgeology.worldgen.lithology;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields;

/**
 * What the ground is made of under a column, from the plates alone: the rock sequence a geologist would expect of the
 * setting. A platform keeps flat-lying limestone, sandstone and shale over a granite and gneiss basement; a fold belt
 * stands its gneiss, quartzite and marble in steep bands round granite cores; a foreland's beds tilt up towards the
 * mountains; an arc lays ash and lava over its plutons, with the scraped-off mélange of the trench along the coast; a rift
 * fills its floor with basalt and sediment between basement shoulders; a hotspot stacks basalt; the sea floor is pillow
 * basalt over gabbro over mantle.
 *
 * <p>Nothing of the world generator here: {@link LithologyRule} lays this into the world, and a tool can read it
 * without one. Settings meet in a zone where their rocks interfinger in pods, and every contact wanders a block or two,
 * so no border is a line.</p>
 */
public final class Lithology {

    private Lithology() {}

    /** The rocks the sequences use. {@link #KEEP} leaves whatever the generator put there. */
    public enum Rock {
        KEEP, STONE, SANDSTONE, SHALE, CALCITE, RED_BEDS, GRANITE, DIORITE, ANDESITE, TUFF, RHYOLITE, BASALT,
        SMOOTH_BASALT, BLACKSTONE, GABBRO, PERIDOTITE, SERPENTINITE, CHERT, GNEISS, SCHIST, SLATE, MARBLE, QUARTZITE
    }

    public enum Setting { PLATFORM, FOLD_BELT, FORELAND, ARC, PRISM, RIFT, SHEAR_ZONE, HOTSPOT, OCEAN_FLOOR }

    /**
     * One column, worked out once: its setting and how fully it holds, what the column falls back to at the setting's
     * edge, and the numbers its sequence needs.
     *
     * @param across       fault widths from the boundary
     * @param folded       blocks from the boundary, wandering, for bands that run along it
     * @param cover        how deep a platform's or foreland's sediment goes
     * @param plutonTop    how far down a granite body starts, or a large number where there is none
     * @param bedShift     how far flat beds are lifted or lowered here
     * @param basement     the old rock under the sediment
     * @param pluton       the rock of this column's pluton
     * @param dyke         whether a dyke swarm runs through here
     */
    public record Column(Setting setting, double weight, Setting fallback, double across, double folded, int cover,
                         int plutonTop, int bedShift, Rock basement, Rock pluton, boolean dyke) {}

    /** A pluton this deep or deeper is none at all. */
    private static final int NO_PLUTON = 10_000;

    public static Column column(long seed, GeologyParams p, int x, int z) {
        PlateSample s = TerrainFields.sampleAt(seed, p, x, z);
        double a = TerrainFields.across(s, p);
        double belt = TerrainFields.belt(s, p);
        boolean oceanic = s.plateKind().isOceanic();
        FaultType k = s.boundaryType();

        Setting fallback = oceanic ? Setting.OCEAN_FLOOR : Setting.PLATFORM;
        Setting setting = fallback;
        double weight = 1.0;
        double plume = HotspotMap.plumeStrength(seed, x, z, p);
        if (plume >= 0.25) {
            setting = Setting.HOTSPOT;
            weight = smooth((plume - 0.25) / 0.25);
        } else if (oceanic) {
            // An island arc is an arc on an ocean plate.
            if (s.overridingSide()) {
                setting = Setting.ARC;
                weight = smooth(belt / 0.5);
            }
        } else {
            // The plain's border wanders as the biome's does, so the rock and the ground above it change together.
            double apron = TerrainFields.apronAt(seed, p, x, z)
                    - TerrainFields.APRON_WANDER * TerrainFields.jitterWide(seed, p, x, z);
            switch (k) {
                case CONVERGENT_COLLISION -> {
                    setting = Setting.FOLD_BELT;
                    weight = smooth(belt / 0.55);
                }
                case CONVERGENT_SUBDUCTION -> {
                    double prism = 1.0 - smooth((a - 0.1) / 0.15);
                    double arc = smooth(belt / 0.5);
                    setting = prism >= arc ? Setting.PRISM : Setting.ARC;
                    weight = Math.max(prism, arc);
                }
                case DIVERGENT -> {
                    setting = Setting.RIFT;
                    weight = smooth(belt / 0.45);
                }
                case TRANSFORM -> {
                    setting = Setting.SHEAR_ZONE;
                    weight = 1.0 - smooth(a / 0.3);
                }
                case INTERIOR -> { }
            }
            if (apron > weight) {
                setting = Setting.FORELAND;
                weight = apron;
            }
        }
        if (weight <= 0.0) {
            setting = fallback;
            weight = 1.0;
        }

        double granite = noise(seed, x, z, 90.0 * p.horizontal(), 0x7A11L);
        int plutonTop = granite > 0.1 ? 14 + (int) Math.round(46.0 * (1.0 - granite)) : NO_PLUTON;
        return new Column(setting, weight, fallback, a,
                s.faultDistance() + 14.0 * noise(seed, x, z, 120.0 * p.horizontal(), 0x2C3DL),
                18 + (int) Math.round(26.0 * (0.5 + 0.5 * noise(seed, x, z, 700.0 * p.horizontal(), 0x5E71L))),
                plutonTop,
                (int) Math.round(5.0 * noise(seed, x, z, 220.0 * p.horizontal(), 0x3B1FL)),
                noise(seed, x, z, 160.0 * p.horizontal(), 0x6D0BL) > 0.0 ? Rock.GRANITE : Rock.GNEISS,
                noise(seed, x, z, 200.0 * p.horizontal(), 0x1E5AL) > 0.0 ? Rock.GRANITE : Rock.DIORITE,
                noise(seed, x, z, 60.0 * p.horizontal(), 0x44D1L) > 0.2);
    }

    /**
     * The rock at one block of a column whose ground stands at {@code surface}. Only called for blocks the generator
     * left as plain stone, so soil, water and air are never asked about.
     */
    public static Rock rockAt(long seed, Column c, int x, int y, int z, int surface) {
        long h = SeedHash.hash(seed, x, z, y);
        // Vanilla's deepslate fades in over the bottom eight blocks, so the sequences stop there raggedly too.
        if (y < (int) (h & 7)) return Rock.KEEP;
        // Every contact wanders a block or two.
        int depth = surface - y + (int) ((h >>> 3) & 3) - 1;
        Setting setting = c.setting();
        if (c.weight() < 1.0) {
            // Where one setting gives way to the next, their rocks interfinger in blobs rather than meet at a line.
            double pick = 0.5 + 0.5 * ValueNoise.noise3D(x + shiftX(seed) + 4096, y, z + shiftZ(seed), POD_SCALE, POD_SCALE);
            if (pick >= c.weight()) setting = c.fallback();
        }
        long salt = SeedHash.mix(seed ^ 0x117E5L);
        return switch (setting) {
            case PLATFORM -> depth < c.cover() ? bed(salt, y + c.bedShift(), PLATFORM_BEDS, PLATFORM_BANDS)
                    : basement(seed, c, x, y, z, depth - c.cover());
            case FOLD_BELT -> depth > c.plutonTop() ? Rock.GRANITE
                    : pick(FOLD_BANDS, salt ^ 0x40L, Math.floorDiv((int) Math.floor(c.folded() + 0.5 * y), 12));
            case FORELAND -> depth < c.cover() + 12
                    ? bed(salt ^ 0x50L, y + c.bedShift() + (int) Math.round(0.35 * c.folded() / 8.0), FORELAND_BEDS,
                            FORELAND_BANDS)
                    : basement(seed, c, x, y, z, depth - c.cover() - 12);
            // An arc's lavas and ash lie over everything to a good depth; under them, older flows and dykes, and
            // the plutons that fed them.
            case ARC -> depth > c.plutonTop() ? c.pluton()
                    : depth < 22 + c.bedShift() * 2 ? pick(ARC_BEDS, salt ^ 0x60L, Math.floorDiv(depth + c.bedShift(), 4))
                    : pick(ARC_ROOT, salt ^ 0x68L, SeedHash.hash(seed ^ 0x68L, x >> 3, z >> 3, y >> 3));
            case PRISM -> body(seed, x, y, z, pick(PRISM_ROCKS, salt ^ 0x70L, SeedHash.hash(seed ^ 0x70L, x >> 3, z >> 3, y >> 2)));
            case RIFT -> rift(seed, salt, c, x, y, z, depth);
            case SHEAR_ZONE -> depth > 40 ? basement(seed, c, x, y, z, depth - 40)
                    : pick(SHEAR_BANDS, salt ^ 0x90L, Math.floorDiv((int) Math.floor(c.folded()), 3));
            case HOTSPOT -> depth < 50 ? pick(HOTSPOT_BEDS, salt ^ 0xA0L, Math.floorDiv(depth + c.bedShift(), 5))
                    : body(seed, x, y, z, Rock.GABBRO);
            case OCEAN_FLOOR -> depth < 8 ? ((h >>> 5) & 3) == 0 ? Rock.SMOOTH_BASALT : Rock.BASALT
                    : depth < 30 ? body(seed, x, y, z, Rock.GABBRO)
                    : body(seed, x, y, z, pick(MANTLE, salt ^ 0xB0L, SeedHash.hash(seed ^ 0xB0L, x >> 3, z >> 3, y >> 3)));
        };
    }

    /** A rift's floor holds basalt flows and sediment, deepest on the axis; its shoulders are basement, cut by dykes. */
    private static Rock rift(long seed, long salt, Column c, int x, int y, int z, int depth) {
        int fill = riftFill(c);
        if (depth < fill) return bed(salt ^ 0x80L, y + c.bedShift(), RIFT_FILL, RIFT_BANDS);
        if (c.dyke() && Math.floorMod((int) Math.floor(c.folded()), 41) < 2) return Rock.GABBRO;
        return basement(seed, c, x, y, z, depth - fill);
    }

    /** How deep a rift's fill goes: forty blocks on the axis, thinning to nothing at the shoulders. */
    private static int riftFill(Column c) {
        return (int) Math.round(40.0 * (1.0 - smooth((c.across() - 0.1) / 0.45)));
    }

    // === For the deposits =====================================================

    /** Whether a granite body lies under this column at all. */
    public static boolean hasPluton(Column c) {
        return c.plutonTop() != NO_PLUTON;
    }

    /**
     * How deep under the ground a column's sediment or fill reaches before its basement: a platform's beds, a
     * foreland's thicker ones, a rift's floor. Zero where the setting has no cover, so a coal seam or a sulfide lens
     * that belongs in the cover has nowhere to go.
     */
    public static int coverDepth(Column c) {
        return switch (c.setting()) {
            case PLATFORM -> c.cover();
            case FORELAND -> c.cover() + 12;
            case RIFT -> riftFill(c);
            default -> 0;
        };
    }

    /** How deep under its cover the basement is laid whole, and the share of it laid below that, in bodies. */
    private static final int BASEMENT_TOP = 12;
    private static final double BASEMENT_BODIES = 0.45;

    /**
     * The old rock under a cover of sediment, {@code below} blocks under the cover's base. Its top is laid whole, where
     * a valley or a cave cuts through the cover; deeper down it comes in bodies eight blocks across, and between them
     * the generator's stone stays, which reads as the same crystalline country rock. Every block laid is a write to the
     * chunk, and this is most of a column's volume.
     */
    private static Rock basement(long seed, Column c, int x, int y, int z, int below) {
        if (below < BASEMENT_TOP) return c.basement();
        return body(seed, x, y, z, c.basement());
    }

    /**
     * The size of a body and of an interfingering pod, and the noise value a body starts at: a little over the noise's
     * middle, for {@link #BASEMENT_BODIES} of the volume. Value noise, not a die per eight-block cell: the cells stood
     * out of every cliff as cubes of diorite and granite.
     */
    private static final double BODY_SCALE = 9.0, BODY_CUT = 0.08, POD_SCALE = 8.0;

    /**
     * A rock laid in bodies, {@link #BASEMENT_BODIES} of the volume, with the generator's own stone between them.
     * The sea floor's gabbro and mantle, the prism and a hotspot's roots come this way as the basement does: laid
     * whole, every cave wall was the mod's rock and read as painted rather than as ground.
     */
    private static Rock body(long seed, int x, int y, int z, Rock rock) {
        return ValueNoise.noise3D(x + shiftX(seed), y, z + shiftZ(seed), BODY_SCALE, BODY_SCALE) > BODY_CUT ? rock : Rock.KEEP;
    }

    /** The noise is a function of position alone; the seed moves it. */
    private static int shiftX(long seed) {
        return (int) (seed & 0xFFFF);
    }

    private static int shiftZ(long seed) {
        return (int) ((seed >>> 16) & 0xFFFF);
    }

    /**
     * A bed of a flat-lying sequence: beds of eight, with a thin band here and there. The beds themselves are rock an
     * ore can sit in ({@code #stone_ore_replaceables}); sandstone, calcite and the red beds come only as the thin
     * bands, which is where a cliff face shows them anyway. Laid down eight thick they stood out as white and yellow
     * stripes and, because no vanilla ore may replace them, they took better than half the coal out of the ground.
     */
    private static Rock bed(long salt, int level, Rock[] beds, Rock[] bands) {
        long thin = SeedHash.mix(salt ^ (Math.floorDiv(level, 3) * 0x9E3779B97F4A7C15L));
        if ((thin & 7) == 1) return bands[(int) Math.floorMod(thin >>> 4, (long) bands.length)];
        return pick(beds, salt, Math.floorDiv(level, 5));
    }

    private static Rock pick(Rock[] table, long salt, long index) {
        return table[(int) Math.floorMod(SeedHash.mix(salt ^ (index * 0xD1B54A32D192ED03L)), (long) table.length)];
    }

    // Tables: a rock appears as often as it is common in the sequence. The beds of a flat-lying sequence hold only
    // rock a vanilla ore may replace; the blocks that cannot hold one are in the BANDS tables, laid as thin bands.
    // Half of every sequence is the generator's own stone (KEEP): a section laid wholly in the mod's rock read as
    // painted, not as ground, in every cave. The named rock stands in beds and bands through a vanilla matrix.
    private static final Rock[] PLATFORM_BEDS = {
            Rock.SHALE, Rock.SHALE, Rock.SHALE, Rock.SHALE, Rock.MARBLE, Rock.CHERT,
            Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP};
    private static final Rock[] PLATFORM_BANDS = {
            Rock.SANDSTONE, Rock.SANDSTONE, Rock.SANDSTONE, Rock.CALCITE, Rock.RED_BEDS};
    private static final Rock[] FOLD_BANDS = {
            Rock.GNEISS, Rock.GNEISS, Rock.GNEISS, Rock.GNEISS, Rock.GNEISS, Rock.QUARTZITE, Rock.QUARTZITE,
            Rock.MARBLE, Rock.MARBLE, Rock.SCHIST, Rock.SLATE,
            Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP};
    private static final Rock[] FORELAND_BEDS = {
            Rock.SHALE, Rock.SHALE, Rock.SHALE, Rock.SHALE, Rock.CHERT, Rock.MARBLE,
            Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP};
    private static final Rock[] FORELAND_BANDS = {
            Rock.SANDSTONE, Rock.SANDSTONE, Rock.RED_BEDS, Rock.RED_BEDS, Rock.CALCITE};
    private static final Rock[] ARC_BEDS = {
            Rock.ANDESITE, Rock.ANDESITE, Rock.ANDESITE, Rock.ANDESITE, Rock.TUFF, Rock.TUFF, Rock.TUFF, Rock.TUFF,
            Rock.RHYOLITE, Rock.BASALT, Rock.BLACKSTONE};
    private static final Rock[] ARC_ROOT = {
            Rock.ANDESITE, Rock.ANDESITE, Rock.ANDESITE, Rock.ANDESITE, Rock.ANDESITE, Rock.DIORITE, Rock.DIORITE,
            Rock.TUFF, Rock.BASALT, Rock.GRANITE};
    private static final Rock[] PRISM_ROCKS = {
            Rock.SHALE, Rock.SHALE, Rock.SHALE, Rock.SHALE, Rock.CHERT, Rock.CHERT, Rock.CHERT,
            Rock.SERPENTINITE, Rock.SERPENTINITE, Rock.SLATE, Rock.BASALT};
    private static final Rock[] RIFT_FILL = {
            Rock.SHALE, Rock.SHALE, Rock.SHALE, Rock.STONE, Rock.STONE,
            Rock.BASALT, Rock.BASALT, Rock.BASALT, Rock.BASALT};
    private static final Rock[] RIFT_BANDS = {
            Rock.SANDSTONE, Rock.SANDSTONE, Rock.SMOOTH_BASALT, Rock.CALCITE};
    private static final Rock[] SHEAR_BANDS = {
            Rock.SLATE, Rock.SLATE, Rock.SCHIST, Rock.SCHIST, Rock.GNEISS,
            Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP, Rock.KEEP};
    // Smooth basalt is the one volcanic block no vanilla ore may replace, so a hotspot's stack keeps only a little.
    private static final Rock[] HOTSPOT_BEDS = {
            Rock.BASALT, Rock.BASALT, Rock.BASALT, Rock.BASALT, Rock.BASALT, Rock.BASALT, Rock.BLACKSTONE,
            Rock.BLACKSTONE, Rock.TUFF, Rock.SMOOTH_BASALT};
    private static final Rock[] MANTLE = {Rock.PERIDOTITE, Rock.PERIDOTITE, Rock.PERIDOTITE, Rock.SERPENTINITE};

    // === Noise ==============================================================

    private static double noise(long seed, int x, int z, double scale, long salt) {
        double fx = x / scale, fz = z / scale;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double a = lattice(seed, ix, iz, salt), b = lattice(seed, ix + 1, iz, salt);
        double c = lattice(seed, ix, iz + 1, salt), d = lattice(seed, ix + 1, iz + 1, salt);
        double top = a + (b - a) * tx, bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private static double lattice(long seed, int ix, int iz, long salt) {
        return SeedHash.rand01(SeedHash.hash(seed, ix, iz, salt)) * 2.0 - 1.0;
    }

    /** 0 below 0, 1 above 1, smooth between. */
    private static double smooth(double t) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }
}
