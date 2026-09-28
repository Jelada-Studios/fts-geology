package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.worldgen.terrain.DemLibrary;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.LandmarkSites;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields;
import net.minecraft.server.level.ServerLevel;

/**
 * The active faults of Everest, K2 and the Matterhorn, where they run on Earth. None of the three stands on its fault:
 * Everest's is the Main Himalayan Thrust, which comes up at the foot of the High Himalaya some forty kilometres to the
 * south-southwest; K2's the Main Karakoram Thrust along the Shyok, seventy kilometres the same way; the Matterhorn's the
 * Rhone-Simplon fault zone down the Valais, thirty kilometres north-northwest, a strike-slip fault and the most restless
 * ground in the Alps. Each mountain is laid down at true scale, so each fault is laid beside it at its true distance and
 * bearing, turned with the mountain's own ground.
 *
 * <p>For the quakes and the instruments only, never the terrain, which stays exactly as it was generated. A column reads
 * whichever boundary is nearer, the mountain's fault or the plates' own.</p>
 */
public final class LandmarkFaults {

    private LandmarkFaults() {}

    /** How far either side of a mountain's fault its fault zone reaches, in blocks. */
    private static final double ZONE = 400.0;

    /**
     * One mountain's fault and its ground. The crop is its range turned so the range runs along its long side;
     * {@code turn} is how far, anticlockwise, real north was turned with it, and {@code summitRight}/{@code summitUp}
     * where the summit stands in it, in metres from its middle -- both measured on the crops against the summits round
     * each mountain (Makalu and Nuptse; Broad Peak and Gasherbrum I; Monte Rosa, the Dom, the Weisshorn and the Dent
     * Blanche), which they place to within a pixel. The fault lies {@code km} from the summit towards the compass
     * bearing {@code towards}, and runs square to it.
     */
    private record Fault(String name, double turn, double summitRight, double summitUp, double towards, double km,
                         FaultType type, double activity) {}

    /** In the order {@link DemLibrary#LANDMARKS} counts them. */
    private static final Fault[] FAULTS = {
            new Fault("Main Himalayan Thrust", 20.0, -1845.0, 5085.0, 200.0, 38.0, FaultType.CONVERGENT_COLLISION, 1.0),
            new Fault("Main Karakoram Thrust", 35.0, -5175.0, 7425.0, 215.0, 70.0, FaultType.CONVERGENT_COLLISION, 0.7),
            new Fault("Rhone-Simplon fault", -25.0, 315.0, 945.0, 350.0, 32.0, FaultType.TRANSFORM, 0.6)};

    /** A mountain's fault laid out in the world: a point of it, its direction, and the way across to the mountain. */
    private record Line(int which, double px, double pz, double ux, double uz, double nx, double nz, double reach,
                        double length, double distance, Fault fault, double summitX, double summitZ) {}

    private static volatile Line[] lines;
    private static volatile long linesFor = Long.MIN_VALUE;

    /** The plate reading at a column for quakes and instruments: the mountains' faults laid over the plates. */
    public static PlateSample sample(ServerLevel level, int x, int z) {
        return adjust(level, x, z, TectonicMap.sample(level, x, z));
    }

    /** As {@link #sample}, from the cached plate reading. */
    public static PlateSample sampleCached(ServerLevel level, int x, int z) {
        return adjust(level, x, z, TectonicMap.sampleCached(level, x, z));
    }

    /**
     * Where a named mountain's summit stands, and where its fault comes nearest it and how far that is, in blocks. Null
     * where the mountain is not raised.
     */
    public static Nearest nearest(ServerLevel level, String mountain) {
        Line[] ls = lines(level);
        if (ls == null) return null;
        for (Line l : ls) {
            if (!DemLibrary.LANDMARKS[l.which()].equals(mountain)) continue;
            return new Nearest((int) Math.round(l.px()), (int) Math.round(l.pz()), (int) Math.round(l.distance()),
                    l.fault().name(), l.fault().type(), (int) Math.round(l.summitX()), (int) Math.round(l.summitZ()));
        }
        return null;
    }

    /** A mountain's fault at the point nearest its summit. */
    public record Nearest(int x, int z, int distance, String name, FaultType type, int summitX, int summitZ) {}

    static PlateSample adjust(ServerLevel level, int x, int z, PlateSample s) {
        Line[] ls = lines(level);
        if (ls == null) return s;
        // One mountain's fault: the one whose fault zone the column stands in, and of those, or of all where it stands in
        // none, the one whose summit is nearest. The three stand closer in a world than on Earth: taken by summit alone,
        // Everest's thrust ran on past K2, and the Matterhorn's own fault read as a plate interior where Everest's
        // summit was the nearer.
        Line l = null;
        double best = Double.MAX_VALUE;
        boolean zoned = false;
        for (Line c : ls) {
            double rx = x - c.px(), rz = z - c.pz();
            boolean in = Math.abs(rx * c.ux() + rz * c.uz()) <= c.length() && Math.abs(rx * c.nx() + rz * c.nz()) <= ZONE;
            if (zoned && !in) continue;
            double m = (x - c.summitX()) * (x - c.summitX()) + (z - c.summitZ()) * (z - c.summitZ());
            if (in && !zoned || m < best) {
                best = m;
                l = c;
                zoned |= in;
            }
        }
        double rx = x - l.px(), rz = z - l.pz();
        double along = rx * l.ux() + rz * l.uz();
        double side = rx * l.nx() + rz * l.nz();          // positive on the mountain's side
        double d = Math.abs(side);
        boolean named = Math.abs(along) <= l.length() && d <= l.reach();
        boolean inZone = named && d <= ZONE;
        // On a mountain's own ground, out of its fault's zone, the column stands in the belt the mountain was raised
        // in: the Alps, the Himalaya and the Karakoram are all two continents run together. A boundary of the plates
        // that happens to pass under the crop is not read there, where a transform through the Matterhorn's summit
        // said the mountain stood on a strike-slip fault.
        if (!inZone) {
            LandmarkSites.Site site = LandmarkSites.near(TerrainContext.seed(), TerrainContext.params(), x, z);
            if (site != null) return belt(s, site, x, z);
        }
        if (!named) return s;
        // Whichever boundary is nearer is the one a column reads.
        if (s.faultDistance() < d) return s;
        Fault f = l.fault();
        // Towards the line from the column.
        double sign = side >= 0 ? -1.0 : 1.0;
        double nx = sign * l.nx(), nz = sign * l.nz();
        double stress = inZone ? f.activity() * (0.35 + 0.5 * (1.0 - d / ZONE)) : 0.0;
        boolean thrust = f.type() == FaultType.CONVERGENT_COLLISION;
        double convergence = thrust ? 1.0 : 0.1, shear = thrust ? 0.2 : 1.0;
        // On a thrust the mountain's side rides over: the plate the higher id is on, as downGoing() reads it.
        long a = s.plateId(), b = s.neighbourId() != s.plateId() ? s.neighbourId() : s.plateId() ^ 0x5DEECE66DL;
        long hi = Long.compareUnsigned(a, b) >= 0 ? a : b, lo = hi == a ? b : a;
        boolean over = side >= 0;
        return new PlateSample(over ? hi : lo, PlateKind.CONTINENTAL, s.plateVelX(), s.plateVelZ(), over ? lo : hi,
                PlateKind.CONTINENTAL, inZone ? f.type() : FaultType.INTERIOR, d, convergence, shear, nx, nz,
                stress, along);
    }

    /** How strained a named mountain's belt reads away from its fault: the Alps close a few millimetres a year. */
    private static final double BELT_STRESS = 0.25;

    /**
     * A column on a named mountain's ground, away from the mountain's own fault: in a collision belt whose line runs
     * along the range, through the middle of the crop. The suture of the Alps runs at the Matterhorn's very foot.
     */
    private static PlateSample belt(PlateSample s, LandmarkSites.Site site, int x, int z) {
        double c = Math.cos(site.bearing()), sn = Math.sin(site.bearing());
        double dx = x - site.x(), dz = z - site.z();
        double along = dx * c + dz * sn, across = -dx * sn + dz * c;
        // Towards the range's line from the column.
        double sign = across >= 0 ? -1.0 : 1.0;
        double nx = sign * -sn, nz = sign * c;
        long a = s.plateId(), b = s.neighbourId() != s.plateId() ? s.neighbourId() : s.plateId() ^ 0x5DEECE66DL;
        long hi = Long.compareUnsigned(a, b) >= 0 ? a : b, lo = hi == a ? b : a;
        boolean over = across >= 0;
        return new PlateSample(over ? hi : lo, PlateKind.CONTINENTAL, s.plateVelX(), s.plateVelZ(), over ? lo : hi,
                PlateKind.CONTINENTAL, FaultType.CONVERGENT_COLLISION, Math.abs(across), 1.0, 0.2, nx, nz,
                BELT_STRESS, along);
    }

    /** The three faults laid out for this world, worked out once from its seed. Null where no mountain is raised. */
    private static Line[] lines(ServerLevel level) {
        if (!DemLibrary.landmarksReady() || !GeologyWorld.isOwn(level)) return null;
        GeologyParams p = TerrainContext.params();
        // Only the tall world raises the mountains; elsewhere their faults would run beside nothing.
        if (p.horizontal() < 1.5) return null;
        long seed = TerrainContext.seed();
        long key = seed ^ Double.doubleToLongBits(p.horizontal());
        Line[] have = lines;
        if (have != null && linesFor == key) return have;
        LandmarkSites.Site[] sites = LandmarkSites.all(seed, p);
        double mpb = TerrainFields.METRES_PER_BLOCK / p.horizontal();
        Line[] out = new Line[sites.length];
        for (int i = 0; i < sites.length; i++) {
            LandmarkSites.Site site = sites[i];
            Fault f = FAULTS[site.which()];
            // Real (east, north) to the crop's (right, up): turned as the crop was.
            double t = Math.toRadians(f.turn());
            double te = Math.sin(Math.toRadians(f.towards())), tn = Math.cos(Math.toRadians(f.towards()));
            double cr = te * Math.cos(t) - tn * Math.sin(t), cu = te * Math.sin(t) + tn * Math.cos(t);
            // The crop's (right, up) to the world, as TerrainFields lays the mountain down.
            double c = Math.cos(site.bearing()), sn = Math.sin(site.bearing());
            double sx = site.x() + (f.summitRight() * c - f.summitUp() * sn) / mpb;
            double sz = site.z() + (f.summitRight() * sn + f.summitUp() * c) / mpb;
            double wx = cr * c - cu * sn, wz = cr * sn + cu * c;          // towards the fault, a unit vector
            double distance = f.km() * 1000.0 / mpb;
            double px = sx + wx * distance, pz = sz + wz * distance;
            double halfAlong = DemLibrary.landmarkHalfAlong(site.which()) / mpb;
            double halfAcross = DemLibrary.landmarkHalfAcross(site.which()) / mpb;
            // Across: from past the fault to past the mountain; along: the mountain's width and half as far again as
            // the fault lies from it.
            double reach = distance + halfAcross + ZONE;
            double length = halfAlong + distance / 2.0;
            out[i] = new Line(site.which(), px, pz, -wz, wx, -wx, -wz, reach, length, distance, f, sx, sz);
        }
        lines = out;
        linesFor = key;
        return out;
    }
}
