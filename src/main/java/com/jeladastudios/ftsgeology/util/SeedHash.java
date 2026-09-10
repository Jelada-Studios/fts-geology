package com.jeladastudios.ftsgeology.util;

/**
 * Position hashing for everything decided from the world seed: plates, plumes, volcano sites.
 *
 * <p>These formulas place the plates and hotspots of every existing world. Changing a constant moves
 * them, so the numbers are frozen.</p>
 */
public final class SeedHash {

    private SeedHash() {}

    /** A well mixed value for a seed, a grid position and a salt naming what it is for. */
    public static long hash(long seed, int x, int z, long salt) {
        long h = seed ^ salt;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        return mix(h);
    }

    /** The 64-bit MurmurHash3 finaliser. */
    public static long mix(long h) {
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    /** Uniform double in [0, 1) from a hash. */
    public static double rand01(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Seed for one column's dice, so a column rolls the same whatever order it is reached in. */
    public static long columnSeed(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }
}
