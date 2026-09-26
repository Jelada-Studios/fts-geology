package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.util.SeedHash;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

/**
 * Real mountain ground, from SRTM elevation data (viewfinderpanoramas.org, see {@code data/fts_geology/dem/README.txt}).
 * Each crop is a 43 by 29 km piece of a range, resampled to a 90 m grid with the range's trend along its long side
 * and its main divide down the middle. A mountain belt reads one crop after another along its boundary, the crop
 * chosen from the two plates and the stretch of boundary, so both sides of the line and every chunk agree.
 *
 * <p>Along the boundary the crops are laid every {@link #PERIOD} metres and overlap their neighbours by a tenth at
 * each end, blended there so that the relief keeps its spread (the mean of two unrelated ranges is a flat one).</p>
 */
public final class DemLibrary {

    private DemLibrary() {}

    /** Which ranges a belt draws from. */
    public enum Kind { YOUNG, WORN }

    private static final String[] YOUNG = {"alps_oetztal", "alps_bernina", "alps_valais", "alps_bernese",
            "alps_montblanc", "cauc_bezengi", "cauc_svaneti", "cauc_kazbek"};
    /**
     * The high ranges. In the normal world, at twenty-five metres to the block, they would stand through the sky;
     * in the tall one, at ten, they are the whole of a young belt, and the Alps would be foothills beside them.
     */
    private static final String[] HIGH = {"him_manaslu", "him_langtang", "him_choyu", "kara_baltoro", "kara_nanga"};
    private static final String[] WORN = {"app_valleyridge", "app_blueridge", "app_newriver"};

    /**
     * The three named mountains, in the order the rest of the mod counts them. Unlike every other crop these are
     * not tiled along a belt: each is laid down once in a world, at its own place, the way it stands on Earth.
     */
    public static final String[] LANDMARKS = {"everest", "k2", "matterhorn"};
    private static final String[] LANDMARK_FILES = {"land_everest", "land_k2", "land_matterhorn"};

    /** A crop's length along the range, less its overlap, in metres. */
    private static final double PERIOD = 400 * 90.0;
    private static final double OVERLAP = 0.1;

    private record Crop(short[] v, int w, int h, double mpp, double mean, double peak) {}

    private static final class Holder {
        static final Crop[] YOUNG_NORMAL = load(YOUNG);
        static final Crop[] YOUNG_TALL = load(HIGH);
        static final Crop[] WORN_ALL = load(WORN);
        static final Crop[] LANDMARK = load(LANDMARK_FILES);
    }

    /** Whether the three named mountains are all there; without them nothing is placed at all. */
    public static boolean landmarksReady() {
        return Holder.LANDMARK.length == LANDMARKS.length;
    }

    /** How far a named mountain's crop reaches from its middle, in metres: half its short side and half its long one. */
    public static double landmarkHalfAcross(int which) {
        Crop c = Holder.LANDMARK[which];
        return (c.h - 1) / 2.0 * c.mpp;
    }

    public static double landmarkHalfAlong(int which) {
        Crop c = Holder.LANDMARK[which];
        return (c.w - 1) / 2.0 * c.mpp;
    }

    /** The tallest of the three over its own valley floor, in metres: what the lift is measured against. */
    public static double landmarkTallest() {
        double top = 0.0;
        for (Crop c : Holder.LANDMARK) top = Math.max(top, c.peak);
        return top;
    }

    /** One named mountain's height in metres over its own valley floor, at a point measured from its middle. */
    public static double landmark(int which, double along, double across) {
        Crop c = Holder.LANDMARK[which];
        double u = (c.w - 1) / 2.0 + along / c.mpp;
        double v = (c.h - 1) / 2.0 - across / c.mpp;
        if (u < 0 || v < 0 || u > c.w - 1 || v > c.h - 1) return 0.0;
        return bicubic(c, u, v);
    }

    /** Whether the crops loaded, for the terrain to fall back on its own relief if they did not. */
    public static boolean available() {
        return Holder.YOUNG_NORMAL.length > 0 && Holder.WORN_ALL.length > 0;
    }

    /**
     * The ground's height in metres at {@code along} metres down a boundary and {@code across} metres off its line,
     * for the boundary between the two plates {@code pair} names. Measured from the crop's own valley floor, not
     * from the sea: laid on the ground absolute, a range's floor stood a thousand metres up and the whole belt sat
     * on a pedestal above the plain instead of rising out of it.
     */
    public static double metres(long seed, long pair, Kind kind, boolean tall, double along, double across) {
        Crop[] pool = kind == Kind.WORN ? Holder.WORN_ALL : tall ? Holder.YOUNG_TALL : Holder.YOUNG_NORMAL;
        if (pool.length == 0) return 0.0;
        double f = along / PERIOD;
        long k = (long) Math.floor(f);
        f -= k;
        double own = tile(seed, pair, pool, k, f, across);
        long other;
        double w;
        if (f < OVERLAP) {
            other = k - 1;
            w = 0.5 + 0.5 * smooth(f / OVERLAP);
        } else if (f > 1.0 - OVERLAP) {
            other = k + 1;
            w = 0.5 + 0.5 * smooth((1.0 - f) / OVERLAP);
        } else {
            return own;
        }
        double fo = other < k ? f + 1.0 : f - 1.0;
        double theirs = tile(seed, pair, pool, other, fo, across);
        Crop a = pick(seed, pair, pool, k), b = pick(seed, pair, pool, other);
        double v = 1.0 - w;
        // Blended about each crop's own mean and scaled back up, so the seam keeps its peaks and valleys.
        double mean = w * a.mean + v * b.mean;
        return mean + (w * (own - a.mean) + v * (theirs - b.mean)) / Math.sqrt(w * w + v * v);
    }

    private static Crop pick(long seed, long pair, Crop[] pool, long k) {
        long h = SeedHash.mix(seed ^ pair * 0x9E3779B97F4A7C15L ^ SeedHash.mix(k * 0xC2B2AE3D27D4EB4FL ^ 0xDE31L));
        return pool[(int) Long.remainderUnsigned(h, pool.length)];
    }

    /** One crop's height at {@code f} of the way along its period, flipped either way as its hash says. */
    private static double tile(long seed, long pair, Crop[] pool, long k, double f, double across) {
        Crop c = pick(seed, pair, pool, k);
        long h = SeedHash.mix(seed ^ pair ^ SeedHash.mix(k ^ 0x5F1A7L));
        double pad = (c.w - PERIOD / c.mpp) / 2.0;
        double u = f * PERIOD / c.mpp + pad;
        double v = (c.h - 1) / 2.0 - across / c.mpp;
        if ((h & 1) != 0) u = c.w - 1 - u;
        if ((h & 2) != 0) v = c.h - 1 - v;
        return bicubic(c, u, v);
    }

    /** Catmull-Rom between the grid's samples: a bilinear crop showed its 90 m cells as flat facets in the tall world. */
    private static double bicubic(Crop c, double u, double v) {
        u = Math.max(0, Math.min(c.w - 1, u));
        v = Math.max(0, Math.min(c.h - 1, v));
        int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
        double fu = u - iu, fv = v - iv;
        double[] col = new double[4];
        for (int j = 0; j < 4; j++) {
            int y = Math.max(0, Math.min(c.h - 1, iv - 1 + j));
            int base = y * c.w;
            double p0 = c.v[base + Math.max(0, iu - 1)], p1 = c.v[base + iu];
            double p2 = c.v[base + Math.min(c.w - 1, iu + 1)], p3 = c.v[base + Math.min(c.w - 1, iu + 2)];
            col[j] = cubic(p0, p1, p2, p3, fu);
        }
        return cubic(col[0], col[1], col[2], col[3], fv);
    }

    private static double cubic(double p0, double p1, double p2, double p3, double t) {
        return p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static Crop[] load(String[] names) {
        List<Crop> out = new ArrayList<>();
        for (String n : names) {
            try (InputStream in = open(n)) {
                if (in == null) {
                    GeysersMod.LOGGER.warn("Mountain crop {} is missing from the jar", n);
                    continue;
                }
                DataInputStream d = new DataInputStream(in);
                if (d.readInt() != 0x46544444 || d.readShort() != 1) throw new IOException("not a DEM crop");
                int w = d.readShort(), h = d.readShort(), mpp = d.readShort();
                d.readShort();
                DataInputStream z = new DataInputStream(new InflaterInputStream(d));
                short[] v = new short[w * h];
                for (int j = 0; j < h; j++) {
                    int prev = 0;
                    for (int i = 0; i < w; i++) {
                        int x = z.readShort();
                        int val = i == 0 ? x : prev + x;
                        v[j * w + i] = (short) val;
                        prev = val;
                    }
                }
                // Down to the crop's own valley floor, so that the belt's valleys come out at the height of the
                // country round them. A percentile rather than the least sample: one hole in the data would put the
                // whole range a hundred metres up.
                short[] sorted = v.clone();
                java.util.Arrays.sort(sorted);
                int base = sorted[(int) (0.01 * sorted.length)];
                long sum = 0;
                int top = 0;
                for (int i = 0; i < v.length; i++) {
                    v[i] = (short) (v[i] - base);
                    sum += v[i];
                    top = Math.max(top, v[i]);
                }
                out.add(new Crop(v, w, h, mpp, sum / (double) v.length, top));
            } catch (IOException e) {
                GeysersMod.LOGGER.warn("Mountain crop {} could not be read: {}", n, e.toString());
            }
        }
        GeysersMod.LOGGER.info("Real mountain ground: {} of {} crops read", out.size(), names.length);
        return out.toArray(new Crop[0]);
    }

    /**
     * The crop's bytes. The class path serves them in the development environment and from most jars; a mod jar is
     * its own module, which may keep a resource under a package-shaped path to itself, so the mod file is asked too.
     */
    private static InputStream open(String name) throws IOException {
        String path = "data/fts_geology/dem/" + name + ".dem";
        InputStream in = DemLibrary.class.getResourceAsStream("/" + path);
        if (in != null) return in;
        var file = net.minecraftforge.fml.ModList.get().getModFileById("fts_geology");
        if (file == null) return null;
        java.nio.file.Path p = file.getFile().findResource(path);
        return java.nio.file.Files.exists(p) ? java.nio.file.Files.newInputStream(p) : null;
    }
}
