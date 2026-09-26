package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.mix;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tectonic plate model laid over an existing world.
 *
 * <p>Plates come from a jittered-grid Voronoi diagram seeded from the world seed, each with a stable
 * id, a crust type ({@link PlateKind}) and a constant drift. Where two meet, their relative motion
 * decides the boundary. {@link #sample} answers all of it for any column.</p>
 *
 * <p>It edits no blocks: crust type is read from the biome source at the plate centre, so plates
 * line up with whatever oceans the terrain generator made. A sample scans at most 5x5 candidate
 * centres, and crust types are cached per dimension.</p>
 */
public final class TectonicMap {

    private TectonicMap() {}

    /** Cached crust type per plate, keyed by dimension id plus plate id. */
    private static final Map<String, PlateKind> KIND_CACHE = new ConcurrentHashMap<>();

    /** How many block samples decide a plate crust type. */
    private static final int KIND_SAMPLES = 9;

    // === Public API =========================================================

    /**
     * Computes the full tectonic picture for a column. Y is irrelevant here: plates are treated as
     * a surface-level concept.
     */
    public static PlateSample sample(ServerLevel level, int blockX, int blockZ) {
        // In the mod's own terrain the plates shaped the ground, so the plate is the one the ground was cut from:
        // the terrain warps its coordinates and lets the boundary that lifts the ground more speak, and every
        // system reads that same plate, or the volcanoes, the springs and the quakes stand up to a warp's width
        // (250 blocks, more than a fault zone) off the mountains they belong to. Elsewhere the plates lie over
        // ground made without them and read their crust from the biomes.
        if (com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)) {
            return com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.sampleAt(level.getSeed(),
                    GeologyParams.current(), blockX, blockZ);
        }
        return compute(level.getSeed(), blockX, blockZ, level, GeologyParams.current());
    }


    /** A plate's crust from the seed: {@code oceanShare} of the plates are oceanic. */
    static PlateKind seededKind(long seed, long plateId, GeologyParams params) {
        return rand01(mix(plateId ^ seed ^ 0x0CEA4L)) < params.oceanShare() ? PlateKind.OCEANIC : PlateKind.CONTINENTAL;
    }

    /**
     * A column's nearest boundary and every other within {@link #NEIGHBOUR_REACH} of being as near, nearest first, with
     * how much further than the first each lies. The terrain blends them all by distance: a column near where
     * boundaries meet is shaped by every one of them, and the blend has to stay continuous where the order among them
     * changes, and across the first boundary itself, where the column's own plate and with it the whole set change.
     *
     * <p>It used to keep the three nearest. Near a junction four or five boundaries lie within a few blocks of the same
     * distance, and where the fourth overtook the third it came into the blend at seven tenths of its weight: a
     * collision belt's crop stood up out of a rift's floor as a straight wall a hundred blocks high. Kept whole, a
     * boundary comes into the blend only at the reach, where its weight is nothing.</p>
     */
    public record Edges(PlateSample first, PlateSample[] rest, double[] gaps, double[] lends) {
        /** The second nearest, or the first where there is none. */
        public PlateSample second() {
            return rest.length > 0 ? rest[0] : first;
        }

        /** The third nearest, or the first where there is none. */
        public PlateSample third() {
            return rest.length > 1 ? rest[1] : first;
        }

        /** How much further the second lies than the first, or infinitely far. */
        public double gap() {
            return gaps.length > 0 ? gaps[0] : Double.MAX_VALUE;
        }
    }

    private static final PlateSample[] NO_SAMPLES = new PlateSample[0];
    private static final double[] NO_GAPS = new double[0];

    /**
     * How near its own boundary a column has to be for the plate across it to lend its boundaries, in blocks: the
     * same distance over which the terrain blends boundaries ({@code TerrainFields.HANDOVER}), scaled with the world.
     */
    public static final double NEIGHBOUR_REACH = 160.0;

    /** Both boundaries from the seed alone, as the terrain generator asks for them. */
    public static Edges sampleSeededEdges(long seed, int blockX, int blockZ, GeologyParams params) {
        return computeEdges(seed, blockX, blockZ, null, params, true);
    }

    /** @param biomes the level whose biomes decide each plate's crust, or null for the seed to decide */
    private static PlateSample compute(long seed, int blockX, int blockZ, ServerLevel biomes, GeologyParams params) {
        return computeEdges(seed, blockX, blockZ, biomes, params, false).first();
    }

    private static Edges computeEdges(long seed, int blockX, int blockZ, ServerLevel biomes, GeologyParams params,
                                      boolean withSecond) {
        double scale = params.plateScale();
        double jitter = params.plateJitter();

        double px = blockX, pz = blockZ;
        int gx = Mth.floor(px / scale);
        int gz = Mth.floor(pz / scale);

        // 1. Find the plate centre this column belongs to. A 5x5 neighbourhood is scanned so even a
        //    heavily jittered grid can never miss the true nearest site.
        double bestD2 = Double.MAX_VALUE;
        double bx = 0, bz = 0;
        int bgx = gx, bgz = gz;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int cx = gx + ox, cz = gz + oz;
                double sx = siteX(seed, cx, cz, scale, jitter);
                double sz = siteZ(seed, cx, cz, scale, jitter);
                double d2 = (sx - px) * (sx - px) + (sz - pz) * (sz - pz);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bx = sx; bz = sz; bgx = cx; bgz = cz;
                }
            }
        }
        long plateId = plateId(seed, bgx, bgz);

        // 2. The boundaries of this plate's cell. A Voronoi edge is the perpendicular bisector between two centres,
        //    so the distance to an edge is the distance to that bisector: exact, unlike the common
        //    second-nearest-minus-nearest approximation, which bulges where three plates meet.
        java.util.List<double[]> edges = new java.util.ArrayList<>();
        collectEdges(seed, bgx, bgz, bx, bz, px, pz, scale, jitter, edges);
        double[] own = edges.get(0);
        for (double[] e : edges) if (e[0] < own[0]) own = e;
        double faultDistance = Math.max(0.0, own[0]);
        PlateKind kind = biomes == null ? seededKind(seed, plateId, params) : plateKind(biomes, seed, bgx, bgz, scale, jitter);
        PlateSample first = boundary(seed, plateId, kind, (int) own[3], (int) own[4], faultDistance, own[1], own[2], own[7], biomes, params);
        if (!withSecond) return new Edges(first, NO_SAMPLES, NO_GAPS, NO_GAPS);

        // 3. Every plate near this column lends its boundaries: where three plates meet, all three boundaries shape the
        //    ground. Each lends by how near its own ground is, measured to its whole outline, so the answer does not
        //    depend on which plate the column stands in: crossing a line, the two sides see the same boundaries at the
        //    same weights and the ground runs on. Only the plate across the nearest line used to lend, and where a
        //    column crossed into a third plate's cell at a junction, a collision belt's boundary lent by the plate it
        //    had left dropped out of the blend -- a hundred and twenty blocks of mountain gone in four.
        // Only near: a boundary's bisector runs on past the plates it parts, and taken from further away the line of
        // one of a neighbour's boundaries passed within ten blocks of a column five hundred blocks inside its own
        // plate, as a mountain range that was not there.
        double reach = NEIGHBOUR_REACH * params.horizontal();
        double ownD2 = (bx - px) * (bx - px) + (bz - pz) * (bz - pz);
        java.util.Map<Long, double[]> unique = new java.util.HashMap<>();
        for (double[] e : edges) if (e != own) keepNearer(unique, e, 0.0);
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                if (ox == 0 && oz == 0) continue;
                int cx = bgx + ox, cz = bgz + oz;
                double sx = siteX(seed, cx, cz, scale, jitter), sz = siteZ(seed, cx, cz, scale, jitter);
                double sep = Math.sqrt((sx - bx) * (sx - bx) + (sz - bz) * (sz - bz));
                if (sep < 1.0e-6) continue;
                // That plate lies past the bisector between its centre and this one's, so it is at least this far.
                if (((sx - px) * (sx - px) + (sz - pz) * (sz - pz) - ownD2) / (2.0 * sep) >= reach) continue;
                java.util.List<double[]> theirs = new java.util.ArrayList<>();
                collectEdges(seed, cx, cz, sx, sz, px, pz, scale, jitter, theirs);
                double near = Double.MAX_VALUE;
                for (double[] e : theirs) near = Math.min(near, e[0]);
                if (near >= reach) continue;
                for (double[] e : theirs) keepNearer(unique, e, near);
            }
        }
        unique.remove(pairKey(own));
        java.util.List<double[]> sorted = new java.util.ArrayList<>(unique.values());
        sorted.sort(java.util.Comparator.comparingDouble(e -> e[0]));
        // Every boundary that could weigh at all, and the two nearest whatever their distance, for the roles. A
        // neighbour's boundary can lie nearer than this plate's own: the gap is then negative and the blend takes it in
        // full.
        int keep = 0;
        while (keep < sorted.size() && (keep < 2 || sorted.get(keep)[0] - faultDistance < reach)) keep++;
        PlateSample[] rest = new PlateSample[keep];
        double[] gaps = new double[keep], lends = new double[keep];
        for (int i = 0; i < keep; i++) {
            rest[i] = sampleFor(seed, sorted.get(i), biomes, params, scale, jitter);
            gaps[i] = sorted.get(i)[0] - faultDistance;
            lends[i] = sorted.get(i)[8];
        }
        return new Edges(first, rest, gaps, lends);
    }

    /**
     * Keeps one copy of a boundary: the same line is found from both the plates it parts, and the copy from the plate
     * nearer this column lends it more.
     */
    private static void keepNearer(java.util.Map<Long, double[]> unique, double[] e, double lend) {
        long key = pairKey(e);
        double[] was = unique.get(key);
        if (was != null && was[8] <= lend) return;
        double[] c = java.util.Arrays.copyOf(e, 9);
        c[8] = lend;
        unique.put(key, c);
    }

    /** The boundary between the two cells an edge runs between, whichever of them it was found from. */
    private static long pairKey(double[] e) {
        long a = cellKey((int) e[5], (int) e[6]), b = cellKey((int) e[3], (int) e[4]);
        return a < b ? a * 0x9E3779B97F4A7C15L + b : b * 0x9E3779B97F4A7C15L + a;
    }

    private static long cellKey(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xffffffffL);
    }

    /**
     * The edges between one plate's cell and every cell round it, as candidate boundaries for a column:
     * {distance, normal x, normal z, the cell across, the cell that owns the edge}. The distance is to the edge
     * itself -- the stretch of the bisector between its two junctions -- so the column's own plate and the plate
     * across the line measure every boundary alike.
     */
    private static void collectEdges(long seed, int cgx, int cgz, double cx, double cz,
                                     double px, double pz, double scale, double jitter, java.util.List<double[]> out) {
        double[] siteXs = new double[25], siteZs = new double[25];
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int i = (ox + 2) * 5 + oz + 2;
                siteXs[i] = siteX(seed, cgx + ox, cgz + oz, scale, jitter);
                siteZs[i] = siteZ(seed, cgx + ox, cgz + oz, scale, jitter);
            }
        }
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int gx = cgx + ox, gz = cgz + oz;
                if (gx == cgx && gz == cgz) continue;
                int self = (ox + 2) * 5 + oz + 2;
                double sx = siteXs[self];
                double sz = siteZs[self];
                double dx = sx - cx, dz = sz - cz;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1.0e-6) continue;
                double ux = dx / len, uz = dz / len;
                double midX = (cx + sx) * 0.5, midZ = (cz + sz) * 0.5;
                // Signed offset from that bisector: a column inside the cell always sits on the near side, so the
                // dot product is negative and negating it gives the perpendicular distance to the edge.
                double d = -((px - midX) * ux + (pz - midZ) * uz);
                // How far down the boundary the column lies. The two plates order themselves by their cells, so both
                // sides of a line measure from the same end and read the same ground.
                double along = (px - midX) * -uz + (pz - midZ) * ux;
                double alongOne = (cgx < gx || (cgx == gx && cgz < gz)) ? along : -along;
                // The edge is not the whole bisector but the stretch of it between the junctions, where the cell's
                // other neighbours cut it off; a neighbour whose bisector they cut away entirely is no neighbour at
                // all. Measured to that stretch, from whichever side: the plate the column stands in used to measure
                // its own edges to the whole line, the plate across to the junction, and where a third boundary came
                // near the two sides gave it different shares -- a hundred-block step down the line between them.
                double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY;
                for (int t = 0; t < 25 && lo <= hi; t++) {
                    if (t == 12 || t == self) continue;
                    double wx = siteXs[t] - cx, wz = siteZs[t] - cz;
                    double k = -uz * wx + ux * wz;
                    double r = -((midX - (cx + siteXs[t]) * 0.5) * wx + (midZ - (cz + siteZs[t]) * 0.5) * wz);
                    if (Math.abs(k) < 1.0e-9) {
                        if (r < 0) lo = Double.POSITIVE_INFINITY;
                    } else if (k > 0) {
                        hi = Math.min(hi, r / k);
                    } else {
                        lo = Math.max(lo, r / k);
                    }
                }
                if (lo > hi) continue;
                d = Math.abs(d);
                if (along < lo || along > hi) {
                    double t = along < lo ? lo : hi;
                    d = Math.sqrt(sq(px - (midX - uz * t)) + sq(pz - (midZ + ux * t)));
                }
                out.add(new double[]{d, ux, uz, gx, gz, cgx, cgz, alongOne});
            }
        }
    }

    private static double sq(double d) {
        return d * d;
    }

    /** The sample a candidate edge makes, as the plate that owns the edge sees it. */
    private static PlateSample sampleFor(long seed, double[] e, ServerLevel biomes, GeologyParams params, double scale,
                                         double jitter) {
        int ogx = (int) e[5], ogz = (int) e[6];
        long ownerId = plateId(seed, ogx, ogz);
        PlateKind ownerKind = biomes == null ? seededKind(seed, ownerId, params) : plateKind(biomes, seed, ogx, ogz, scale, jitter);
        return boundary(seed, ownerId, ownerKind, (int) e[3], (int) e[4], Math.max(0.0, e[0]), e[1], e[2], e[7], biomes, params);
    }

    /** A column's plate against one of its boundaries, the plate across it lying in grid cell (ngx, ngz). */
    private static PlateSample boundary(long seed, long plateId, PlateKind kind, int ngx, int ngz, double faultDistance,
                                        double nx, double nz, double along, ServerLevel biomes, GeologyParams params) {
        long neighbourId = plateId(seed, ngx, ngz);
        // 3. Plate drift, and therefore what this boundary is doing.
        double[] vA = plateVelocity(seed, plateId);
        double[] vB = plateVelocity(seed, neighbourId);
        double relX = vB[0] - vA[0];
        double relZ = vB[1] - vA[1];
        // Positive convergence means the neighbour is closing on us; negative means rifting apart.
        double convergence = -(relX * nx + relZ * nz);
        double shear = Math.abs(relX * nz - relZ * nx);

        PlateKind neighbourKind = biomes == null ? seededKind(seed, neighbourId, params)
                : plateKind(biomes, seed, ngx, ngz, params.plateScale(), params.plateJitter());

        // 4. What the boundary is doing, and how hard, are PlateSample's own answers, so the terrain, which
        //    reaches past the fault zone, and the features, which do not, can never disagree about either. The
        //    sample is built once without them and then again with what it said about itself.
        double faultWidth = params.faultWidth();
        PlateSample bare = new PlateSample(plateId, kind, vA[0], vA[1], neighbourId, neighbourKind,
                FaultType.INTERIOR, faultDistance, convergence, shear, nx, nz, 0.0, along);
        double stress = bare.belt(faultWidth, 1.0);
        FaultType type = faultDistance > faultWidth ? FaultType.INTERIOR : bare.boundaryType();
        return new PlateSample(plateId, kind, vA[0], vA[1], neighbourId, neighbourKind,
                type, faultDistance, convergence, shear, nx, nz, stress, along);
    }

    /**
     * Cached sample on a four-block grid, indistinguishable from exact for plate features. Use
     * {@link #sample} where exactness matters, such as tracing a rupture.
     */
    public static PlateSample sampleCached(ServerLevel level, int blockX, int blockZ) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(blockX >> 2, blockZ >> 2);
        PlateSample hit = SAMPLE_CACHE.get(key);
        if (hit != null) return hit;
        // Sampled at the cell's centre, not where the first asker stood, so the answer does not depend on which chunk
        // generated first.
        PlateSample s = sample(level, (blockX & ~3) + 2, (blockZ & ~3) + 2);
        SAMPLE_CACHE.put(key, s);
        return s;
    }

    private static final com.jeladastudios.ftsgeology.util.ColumnCache<PlateSample> SAMPLE_CACHE =
            new com.jeladastudios.ftsgeology.util.ColumnCache<>(16);

    /** Short human-friendly code for a plate id, for display in commands and tooltips. */
    public static String plateCode(long plateId) {
        String s = Long.toUnsignedString(plateId, 36).toUpperCase(Locale.ROOT);
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    /** Drops cached plate crust types, so a reload re-samples biomes. */
    public static void clearCache() {
        KIND_CACHE.clear();
        SAMPLE_CACHE.clear();
    }

    // === Plate geometry =====================================================

    private static double siteX(long seed, int cx, int cz, double scale, double jitter) {
        double j = (rand01(hash(seed, cx, cz, 0x51ED270BL)) - 0.5) * jitter;
        return (cx + 0.5 + j) * scale;
    }

    private static double siteZ(long seed, int cx, int cz, double scale, double jitter) {
        double j = (rand01(hash(seed, cx, cz, 0x2545F491L)) - 0.5) * jitter;
        return (cz + 0.5 + j) * scale;
    }

    private static long plateId(long seed, int cx, int cz) {
        return hash(seed, cx, cz, 0x9E3779B9L);
    }

    /**
     * Constant drift of a plate, as an {x, z} pair. Deterministic from the plate id, so a plate
     * always moves the same way. Magnitudes land in roughly 0.3..1.0, loosely echoing the few
     * centimetres a year that real plates manage.
     */
    private static double[] plateVelocity(long seed, long plateId) {
        long h = mix(plateId ^ seed);
        double angle = rand01(h) * Math.PI * 2.0;
        double speed = 0.3 + rand01(mix(h)) * 0.7;
        return new double[] { Math.cos(angle) * speed, Math.sin(angle) * speed };
    }

    // === Crust type, read from the world biome source =======================

    private static PlateKind plateKind(ServerLevel level, long seed, int cx, int cz,
                                       double scale, double jitter) {
        long id = plateId(seed, cx, cz);
        String key = level.dimension().location() + "@" + id;
        PlateKind cached = KIND_CACHE.get(key);
        if (cached != null) return cached;

        double sx = siteX(seed, cx, cz, scale, jitter);
        double sz = siteZ(seed, cx, cz, scale, jitter);
        PlateKind kind = sampleCrust(level, sx, sz, scale, id);
        KIND_CACHE.put(key, kind);
        return kind;
    }

    /**
     * Calls a plate oceanic when most probes around its centre land in ocean, asked of the noise
     * biome source so no chunk loads. Falls back to a seed-derived split if the generator cannot answer.
     */
    private static PlateKind sampleCrust(ServerLevel level, double sx, double sz, double scale, long id) {
        try {
            ServerChunkCache chunkSource = level.getChunkSource();
            Climate.Sampler sampler = chunkSource.randomState().sampler();
            BiomeSource biomes = chunkSource.getGenerator().getBiomeSource();
            int qy = QuartPos.fromBlock(level.getSeaLevel());
            // Spread the probes over a good fraction of the plate, so one stray lake or island does
            // not decide the crust type of an entire plate.
            int spread = (int) Math.max(64.0, scale * 0.25);
            int[][] offsets = { {0, 0}, {spread, 0}, {-spread, 0}, {0, spread}, {0, -spread},
                    {spread, spread}, {-spread, spread}, {spread, -spread}, {-spread, -spread} };
            int ocean = 0;
            for (int i = 0; i < KIND_SAMPLES; i++) {
                int bxx = (int) sx + offsets[i][0];
                int bzz = (int) sz + offsets[i][1];
                Holder<Biome> biome = biomes.getNoiseBiome(
                        QuartPos.fromBlock(bxx), qy, QuartPos.fromBlock(bzz), sampler);
                if (TfcCompat.ocean(biome)) ocean++;
            }
            return ocean * 2 > KIND_SAMPLES ? PlateKind.OCEANIC : PlateKind.CONTINENTAL;
        } catch (Throwable t) {
            return rand01(mix(id)) < 0.45 ? PlateKind.OCEANIC : PlateKind.CONTINENTAL;
        }
    }
}
