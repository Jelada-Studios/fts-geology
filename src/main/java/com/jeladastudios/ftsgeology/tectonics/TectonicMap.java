package com.jeladastudios.ftsgeology.tectonics;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.mix;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
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
        double scale = GeyserConfig.PLATE_SCALE.get();
        double jitter = GeyserConfig.PLATE_JITTER.get();
        double faultWidth = GeyserConfig.FAULT_WIDTH.get();
        long seed = level.getSeed();

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

        // 2. Distance to the nearest plate boundary, and which plate lies across it. A Voronoi edge
        //    is the perpendicular bisector between two centres, so the distance to the nearest edge
        //    is the smallest distance to any of those bisectors. That is exact, unlike the common
        //    second-nearest-minus-nearest approximation, which bulges where three plates meet.
        double nearestEdge = Double.MAX_VALUE;
        double nx = 1, nz = 0;                 // unit normal across that nearest boundary
        long neighbourId = plateId;
        int ngx = bgx, ngz = bgz;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int cx = bgx + ox, cz = bgz + oz;
                if (cx == bgx && cz == bgz) continue;
                double sx = siteX(seed, cx, cz, scale, jitter);
                double sz = siteZ(seed, cx, cz, scale, jitter);
                double dx = sx - bx, dz = sz - bz;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1.0e-6) continue;
                double ux = dx / len, uz = dz / len;
                double midX = (bx + sx) * 0.5, midZ = (bz + sz) * 0.5;
                // Signed offset from that bisector. Because our own centre is the closest one, the column
                // always sits on the near side, so this dot product is negative; negating it gives the
                // positive perpendicular distance to that edge.
                double d = -((px - midX) * ux + (pz - midZ) * uz);
                if (d < nearestEdge) {
                    nearestEdge = d;
                    nx = ux; nz = uz;
                    neighbourId = plateId(seed, cx, cz);
                    ngx = cx; ngz = cz;
                }
            }
        }
        double faultDistance = Math.max(0.0, nearestEdge);

        // 3. Plate drift, and therefore what this boundary is doing.
        double[] vA = plateVelocity(seed, plateId);
        double[] vB = plateVelocity(seed, neighbourId);
        double relX = vB[0] - vA[0];
        double relZ = vB[1] - vA[1];
        // Positive convergence means the neighbour is closing on us; negative means rifting apart.
        double convergence = -(relX * nx + relZ * nz);
        double shear = Math.abs(relX * nz - relZ * nx);

        PlateKind kind = plateKind(level, seed, bgx, bgz, scale, jitter);
        PlateKind neighbourKind = plateKind(level, seed, ngx, ngz, scale, jitter);
        FaultType type = classify(faultDistance, faultWidth, convergence, shear, kind, neighbourKind);

        // 4. Stress: the square root of proximity times motion, with a motion floor, so the active
        //    core of the fault zone is wide and a slow boundary still lives on the line.
        double proximity = 1.0 - Mth.clamp(faultDistance / faultWidth, 0.0, 1.0);
        proximity = Math.sqrt(proximity);
        double motion = Mth.clamp(Math.max(Math.abs(convergence), shear) / 1.2, 0.0, 1.0);
        double stress = Mth.clamp(proximity * (0.45 + 0.55 * motion), 0.0, 1.0);

        return new PlateSample(plateId, kind, vA[0], vA[1], neighbourId, neighbourKind,
                type, faultDistance, convergence, shear, nx, nz, stress);
    }

    /**
     * Cached sample on a four-block grid, indistinguishable from exact for plate features. Use
     * {@link #sample} where exactness matters, such as tracing a rupture.
     */
    public static PlateSample sampleCached(ServerLevel level, int blockX, int blockZ) {
        long key = ((long) (blockX >> 2) & 0xFFFFFFFFL) | (((long) (blockZ >> 2) & 0xFFFFFFFFL) << 32);
        PlateSample hit = SAMPLE_CACHE.get(key);
        if (hit != null) return hit;
        PlateSample s = sample(level, blockX, blockZ);
        if (SAMPLE_CACHE.size() > SAMPLE_CACHE_MAX) SAMPLE_CACHE.clear();
        SAMPLE_CACHE.put(key, s);
        return s;
    }

    private static final Map<Long, PlateSample> SAMPLE_CACHE = new ConcurrentHashMap<>();
    private static final int SAMPLE_CACHE_MAX = 60000;

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

    // === Classification =====================================================

    private static FaultType classify(double faultDistance, double faultWidth,
                                      double convergence, double shear,
                                      PlateKind a, PlateKind b) {
        if (faultDistance > faultWidth) return FaultType.INTERIOR;
        if (Math.abs(convergence) >= shear) {
            if (convergence > 0) {
                // Dense oceanic crust always loses and dives under; two continents just crumple.
                boolean anyOceanic = a.isOceanic() || b.isOceanic();
                return anyOceanic ? FaultType.CONVERGENT_SUBDUCTION : FaultType.CONVERGENT_COLLISION;
            }
            return FaultType.DIVERGENT;
        }
        return FaultType.TRANSFORM;
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
                if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)) ocean++;
            }
            return ocean * 2 > KIND_SAMPLES ? PlateKind.OCEANIC : PlateKind.CONTINENTAL;
        } catch (Throwable t) {
            return rand01(mix(id)) < 0.45 ? PlateKind.OCEANIC : PlateKind.CONTINENTAL;
        }
    }

    // === Hashing ============================================================

}
