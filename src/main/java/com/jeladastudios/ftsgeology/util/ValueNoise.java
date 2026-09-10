package com.jeladastudios.ftsgeology.util;

import net.minecraft.util.Mth;

/**
 * Smooth value noise in -1..1, from hashed integer lattice points. Pure and thread safe.
 *
 * <p>Ore, soil, basin floors and the deep structure are all laid out with these, so the formulas are
 * frozen for the same reason as {@link SeedHash}.</p>
 */
public final class ValueNoise {

    private ValueNoise() {}

    /** 2D noise with features about {@code scale} blocks across. */
    public static double noise(int x, int z, double scale) {
        double fx = x / scale, fz = z / scale;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double ax = fx - x0, az = fz - z0;
        ax = ax * ax * (3.0 - 2.0 * ax);
        az = az * az * (3.0 - 2.0 * az);
        return Mth.lerp(az,
                Mth.lerp(ax, lattice(x0, z0), lattice(x0 + 1, z0)),
                Mth.lerp(ax, lattice(x0, z0 + 1), lattice(x0 + 1, z0 + 1)));
    }

    public static double lattice(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }

    /** 3D noise, stretched separately across and down. */
    public static double noise3D(int x, int y, int z, double scaleXZ, double scaleY) {
        double fx = x / scaleXZ, fy = y / scaleY, fz = z / scaleXZ;
        int x0 = Mth.floor(fx), y0 = Mth.floor(fy), z0 = Mth.floor(fz);
        double ax = fx - x0, ay = fy - y0, az = fz - z0;
        ax = ax * ax * (3.0 - 2.0 * ax);
        ay = ay * ay * (3.0 - 2.0 * ay);
        az = az * az * (3.0 - 2.0 * az);
        return Mth.lerp(ay,
                Mth.lerp(az,
                        Mth.lerp(ax, lattice3D(x0, y0, z0), lattice3D(x0 + 1, y0, z0)),
                        Mth.lerp(ax, lattice3D(x0, y0, z0 + 1), lattice3D(x0 + 1, y0, z0 + 1))),
                Mth.lerp(az,
                        Mth.lerp(ax, lattice3D(x0, y0 + 1, z0), lattice3D(x0 + 1, y0 + 1, z0)),
                        Mth.lerp(ax, lattice3D(x0, y0 + 1, z0 + 1), lattice3D(x0 + 1, y0 + 1, z0 + 1))));
    }

    public static double lattice3D(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0x85157AF5L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }
}
