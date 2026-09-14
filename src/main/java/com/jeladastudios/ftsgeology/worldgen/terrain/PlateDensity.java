package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The plate model as a density function, {@code fts_geology:plate} in the noise settings. One instance answers one
 * field of the terrain, all of them the seed's pure function of the column:
 *
 * <ul>
 *   <li>{@code continents}: the vanilla continentalness, from crust: an oceanic plate is sea, a continental one
 *       land, a shore where they meet, a trench off a subduction coast;</li>
 *   <li>{@code erosion}: the vanilla erosion (low is mountainous), from tectonic activity: an active belt is
 *       rugged, a plate's interior flat;</li>
 *   <li>{@code ridges}: a factor on the vanilla ridge noise, stronger in a belt;</li>
 *   <li>{@code relief}: an offset on the terrain height in vanilla depth units (1.0 is 128 blocks): a fold belt's
 *       uplift, an arc, a trench, a rift's graben and shoulders, a plume's dome.</li>
 * </ul>
 *
 * The coordinates are warped by a low-frequency value noise first, so a plate's straight Voronoi edges become
 * winding coasts and belts. The plate sample is cached on a four-block grid.
 */
public final class PlateDensity implements DensityFunction.SimpleFunction {

    public static final MapCodec<PlateDensity> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("field").forGetter(f -> f.field.name().toLowerCase(java.util.Locale.ROOT)),
            Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(f -> f.scale)
    ).apply(i, PlateDensity::new));
    public static final KeyDispatchDataCodec<PlateDensity> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    public enum Field { CONTINENTS, EROSION, RIDGES, RELIEF }

    /** How far the coordinates are pushed about, in blocks, and the size of the pushing. */
    private static final double WARP_AMPLITUDE = 250.0;
    private static final double WARP_SCALE = 1200.0;
    /** The interior rises gently for this far from a coast. */
    private static final double INTERIOR_RISE = 1500.0;

    private final Field field;
    private final double scale;

    public PlateDensity(String field, double scale) {
        this.field = Field.valueOf(field.toUpperCase(java.util.Locale.ROOT));
        this.scale = scale;
    }

    @Override
    public double compute(FunctionContext ctx) {
        long seed = WorldSeed.current();
        int x = ctx.blockX(), z = ctx.blockZ();
        double wx = x + WARP_AMPLITUDE * warp(seed, x, z, 0x77A1L);
        double wz = z + WARP_AMPLITUDE * warp(seed, x, z, 0x3B2CL);
        PlateSample s = TerrainCache.sample(seed, (int) Math.floor(wx), (int) Math.floor(wz));
        double v = switch (field) {
            case CONTINENTS -> continents(s);
            case EROSION -> erosion(s);
            case RIDGES -> 0.5 + 0.9 * beltStress(s);
            case RELIEF -> relief(s) + 0.25 * HotspotMap.plumeStrength(seed, x, z);
        };
        return v * scale;
    }

    // === Fields =============================================================

    private static double continents(PlateSample s) {
        boolean oceanic = s.plateKind().isOceanic(), otherOceanic = s.neighbourKind().isOceanic();
        double d = s.faultDistance();
        if (oceanic && otherOceanic) return -0.9;
        if (!oceanic && !otherOceanic) return 0.55 + 0.25 * Math.min(1.0, d / INTERIOR_RISE);
        // One curve through the boundary, the shore just off it, so the coast is a slope and not a cliff: the
        // continental side climbs to the coastal plain over 400 blocks and on to the interior, the oceanic side
        // falls to the deep over 400, deeper still in a subduction trench.
        if (!oceanic) return -0.3 + 0.85 * Mth.clamp(d / 400, 0, 1) + 0.25 * Mth.clamp((d - 600) / 1200, 0, 1);
        double c = -0.3 - 0.6 * Mth.clamp(d / 400, 0, 1);
        if (kind(s) == Kind.SUBDUCTION) c -= 0.2 * peak(across(s), 0.3, 0.25);
        return c;
    }

    private static double erosion(PlateSample s) {
        double stress = beltStress(s);
        Kind k = kind(s);
        if (s.plateKind().isOceanic() && !(k == Kind.SUBDUCTION && s.overriding())) return 0.5;
        // Vanilla's peaks want erosion under -0.4: a belt's core goes to -0.8, its foothills stay gentle.
        return switch (k) {
            case SUBDUCTION, COLLISION -> 0.6 - 2.0 * stress;
            case TRANSFORM -> 0.7 - 1.2 * stress;
            case DIVERGENT -> 0.3 - 0.3 * stress;
            case INTERIOR -> 0.45;
        };
    }

    private static double relief(PlateSample s) {
        double u = GeyserConfig.TERRAIN_UPLIFT.get() / 128.0 * (0.5 + 0.5 * motion(s));
        double a = across(s);
        return switch (kind(s)) {
            case COLLISION -> u * bump(a);
            case SUBDUCTION -> s.overriding() ? 0.8 * u * peak(a, 0.35, 0.35) : -0.25 * peak(a, 0.25, 0.25);
            case DIVERGENT -> a < 0.3 ? -0.25 * Math.sqrt(1.0 - a / 0.3) : 0.15 * peak(a, 0.5, 0.3);
            case TRANSFORM, INTERIOR -> 0.0;
        };
    }

    // === The belt: the plate sample's classification, on the terrain's own width ===================

    private enum Kind { INTERIOR, SUBDUCTION, COLLISION, DIVERGENT, TRANSFORM }

    private static double across(PlateSample s) {
        return s.faultDistance() / GeyserConfig.TERRAIN_BELT_WIDTH.get();
    }

    private static Kind kind(PlateSample s) {
        if (across(s) > 1.0) return Kind.INTERIOR;
        if (Math.abs(s.convergence()) >= s.shear()) {
            if (s.convergence() > 0) {
                return s.plateKind().isOceanic() || s.neighbourKind().isOceanic() ? Kind.SUBDUCTION : Kind.COLLISION;
            }
            return Kind.DIVERGENT;
        }
        return Kind.TRANSFORM;
    }

    private static double motion(PlateSample s) {
        return Mth.clamp(Math.max(Math.abs(s.convergence()), s.shear()) / 1.2, 0.0, 1.0);
    }

    private static double beltStress(PlateSample s) {
        double proximity = Math.sqrt(1.0 - Mth.clamp(across(s), 0.0, 1.0));
        return Mth.clamp(proximity * (0.45 + 0.55 * motion(s)), 0.0, 1.0);
    }

    /** 1 at the boundary, 0 at the belt's edge, smooth in between. */
    private static double bump(double a) {
        if (a >= 1.0) return 0.0;
        return (1.0 - a) * (1.0 - a) * (1.0 + 2.0 * a);
    }

    /** 1 at {@code centre}, 0 at {@code halfWidth} either side, smooth in between. */
    private static double peak(double a, double centre, double halfWidth) {
        return bump(Math.min(1.0, Math.abs(a - centre) / halfWidth));
    }

    // === Warp ===============================================================

    /** Two octaves of value noise in -1..1, from the seed. */
    private static double warp(long seed, int x, int z, long salt) {
        return 0.75 * value(seed, x, z, WARP_SCALE, salt) + 0.25 * value(seed, x, z, WARP_SCALE / 3.0, salt ^ 0x5A5AL);
    }

    private static double value(long seed, int x, int z, double scale, long salt) {
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

    // === DensityFunction ====================================================

    @Override
    public double minValue() {
        return switch (field) {
            case CONTINENTS, EROSION -> -1.5 * Math.abs(scale);
            case RIDGES -> 0.0;
            case RELIEF -> -1.0 * Math.abs(scale);
        };
    }

    @Override
    public double maxValue() {
        return switch (field) {
            case CONTINENTS, EROSION -> 1.5 * Math.abs(scale);
            case RIDGES -> 2.0 * Math.abs(scale);
            case RELIEF -> 1.5 * Math.abs(scale);
        };
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
