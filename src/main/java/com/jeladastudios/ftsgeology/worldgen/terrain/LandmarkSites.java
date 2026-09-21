package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.util.SeedHash;

/**
 * Where the three named mountains stand in a world: Everest, K2 and the Matterhorn, one of each.
 *
 * <p>Every other mountain in the mod is a consequence -- a belt raises whatever crop the boundary's hash draws,
 * tiled along the line, and no two worlds have it in the same place for the same reason. These three are the
 * opposite: a particular mountain, laid down once, so that a player can be told where it is and go there. They
 * are only in the tall world, because at ten metres to the block a crop keeps its own height and at twenty-five
 * it would be a hill.</p>
 *
 * <p>The site is a pure function of the seed. A ring of candidates is walked in a fixed order and the first that
 * stands on continental ground inside a mountain belt, and far enough from the ones already placed, is taken.
 * Nothing is searched at run time past the first ask: three sites are worked out once and kept.</p>
 */
public final class LandmarkSites {

    private LandmarkSites() {}

    /** One named mountain in one world: where its crop's middle sits, and which way its long axis runs. */
    public record Site(int which, int x, int z, double bearing) {}

    /** How far out the sites are looked for, in blocks of the normal world, and how finely. */
    private static final double FROM = 20_000.0, TO = 110_000.0, STEP = 2_500.0;

    /** How much ground a candidate has to have: dry, and inside a range. */
    private static final double WANT_LAND = 0.25, WANT_BELT = 0.45;

    /** How far apart two of them have to stand, in crops: they are two thousand blocks wide each. */
    private static final double APART = 2.5;

    private static long forSeed = Long.MIN_VALUE;
    private static Site[] sites = new Site[0];

    /** The three sites of this world, worked out once. */
    public static synchronized Site[] all(long seed, GeologyParams p) {
        if (forSeed == seed && sites.length > 0) return sites;
        sites = choose(seed, p);
        forSeed = seed;
        return sites;
    }

    private static Site[] choose(long seed, GeologyParams p) {
        if (!DemLibrary.landmarksReady()) return new Site[0];
        Site[] out = new Site[DemLibrary.LANDMARKS.length];
        int made = 0;
        for (int which = 0; which < out.length; which++) {
            double reach = DemLibrary.landmarkHalfAlong(which) / (TerrainFields.METRES_PER_BLOCK / p.horizontal());
            long h = SeedHash.hash(seed, which, 0x1A2D, 0x4E71L);
            double turn = SeedHash.rand01(h) * Math.PI * 2.0;
            Site found = null;
            // A spiral rather than a scan: the candidates come out spread over the whole ring instead of marching
            // outwards along one line, so a world whose first belt is far away does not put all three in it.
            for (double r = FROM * p.horizontal(); r <= TO * p.horizontal() && found == null; r += STEP * p.horizontal()) {
                int ring = Math.max(8, (int) (r / (STEP * p.horizontal())) * 2);
                for (int i = 0; i < ring; i++) {
                    double a = turn + Math.PI * 2.0 * ((i * 0.6180339887) % 1.0);
                    int cx = (int) Math.round(Math.cos(a) * r), cz = (int) Math.round(Math.sin(a) * r);
                    if (!suits(seed, p, cx, cz)) continue;
                    if (tooNear(out, made, cx, cz, reach * APART)) continue;
                    found = new Site(which, cx, cz, SeedHash.rand01(SeedHash.mix(h ^ 0x77L)) * Math.PI * 2.0);
                    break;
                }
            }
            if (found == null) continue;
            out[made++] = found;
            GeysersMod.LOGGER.info("{} stands at {}, {}", DemLibrary.LANDMARKS[which], found.x(), found.z());
        }
        Site[] kept = new Site[made];
        System.arraycopy(out, 0, kept, 0, made);
        if (made < out.length) {
            GeysersMod.LOGGER.warn("Only {} of the named mountains found ground to stand on", made);
        }
        return kept;
    }

    /** Dry continental ground inside a range, read off the plate model alone so nothing has to be generated. */
    private static boolean suits(long seed, GeologyParams p, int x, int z) {
        return TerrainFields.field(TerrainFields.Field.CONTINENTS, seed, p, x, z) > WANT_LAND
                && TerrainFields.field(TerrainFields.Field.BELT, seed, p, x, z) > WANT_BELT;
    }

    private static boolean tooNear(Site[] out, int made, int x, int z, double apart) {
        for (int i = 0; i < made; i++) {
            double dx = out[i].x() - x, dz = out[i].z() - z;
            if (dx * dx + dz * dz < apart * apart) return true;
        }
        return false;
    }

    /**
     * The named mountain whose crop covers this column, or null. The crop is turned to its bearing, so it is tested in
     * its own frame: a square round the site, as this once was, cut off the crop's corners in straight walls a hundred
     * and more blocks high wherever the mountain still stood there.
     */
    public static Site near(long seed, GeologyParams p, int x, int z) {
        Site[] all = all(seed, p);
        double mpb = TerrainFields.METRES_PER_BLOCK / p.horizontal();
        for (Site s : all) {
            double halfAlong = DemLibrary.landmarkHalfAlong(s.which()) / mpb;
            double halfAcross = DemLibrary.landmarkHalfAcross(s.which()) / mpb;
            double dx = x - s.x(), dz = z - s.z();
            if (dx * dx + dz * dz > halfAlong * halfAlong + halfAcross * halfAcross) continue;
            double c = Math.cos(s.bearing()), sn = Math.sin(s.bearing());
            if (Math.abs(dx * c + dz * sn) <= halfAlong && Math.abs(-dx * sn + dz * c) <= halfAcross) return s;
        }
        return null;
    }

    /** Where one named mountain is, for the find command; null where it found nowhere to stand. */
    public static Site of(long seed, GeologyParams p, String name) {
        for (Site s : all(seed, p)) {
            if (DemLibrary.LANDMARKS[s.which()].equals(name)) return s;
        }
        return null;
    }

    public static void clear() {
        forSeed = Long.MIN_VALUE;
        sites = new Site[0];
    }
}
