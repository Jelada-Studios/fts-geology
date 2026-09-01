package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantle hotspots - the intraplate volcanism that plate boundaries cannot explain.
 *
 * <h2>Why this matters</h2>
 * About half the geysers on Earth are at Yellowstone, and Yellowstone is nowhere near a plate
 * boundary: it sits over a stationary plume of hot mantle. Hawaii is the same story. A model that
 * only knows about boundaries would miss the single most iconic geothermal field there is, so
 * hotspots are a first-class part of the model rather than an extra.
 *
 * <h2>The trail</h2>
 * A plume is fixed in the mantle while the plate slides over it, so volcanism is only active
 * directly above the plume; older volcanoes are carried away and go extinct, leaving a chain
 * pointing back along the direction the plate came FROM. {@link #sample} reports both the live
 * strength and how far along that trail a column sits, which is what lets worldgen place one active
 * volcano and a line of dead ones behind it - the Hawaii-Emperor pattern.
 *
 * <p>Like {@link TectonicMap} this is pure seed-derived maths: no storage, no worldgen hooks, no
 * blocks touched.</p>
 */
public final class HotspotMap {

    private HotspotMap() {}

    /** Result of a hotspot query for one column. */
    public record Hotspot(
            /** 0..1 volcanic activity from the plume right here; 0 when out of range. */
            double strength,
            /** Blocks to the plume centre, or {@link Double#MAX_VALUE} when there is none nearby. */
            double distance,
            /** 0 directly over the plume, rising to 1 at the far end of the extinct trail. */
            double trailAge,
            /** True when this column is on the extinct chain rather than over the live plume. */
            boolean onTrail) {

        public static final Hotspot NONE = new Hotspot(0.0, Double.MAX_VALUE, 1.0, false);

        public boolean active() {
            return strength > 0.0;
        }
    }

    /**
     * Samples the hotspot field. Hotspot cells are laid out on their own coarse jittered grid,
     * completely independent of the plate grid, and only a fraction of cells actually host a plume -
     * so hotspots stay rare landmarks rather than a second set of boundaries.
     */
    public static Hotspot sample(ServerLevel level, int blockX, int blockZ) {
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return Hotspot.NONE;

        double scale = GeyserConfig.HOTSPOT_SCALE.get();
        double density = GeyserConfig.HOTSPOT_DENSITY.get();
        double radius = GeyserConfig.HOTSPOT_RADIUS.get();
        double trailLength = GeyserConfig.HOTSPOT_TRAIL_LENGTH.get();
        long seed = level.getSeed();

        double px = blockX, pz = blockZ;
        int gx = Mth.floor(px / scale);
        int gz = Mth.floor(pz / scale);

        Hotspot best = Hotspot.NONE;
        // A plume can influence a column up to radius + trail away, so check the ring of cells that
        // could possibly reach here.
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int cx = gx + ox, cz = gz + oz;
                if (!cellHasPlume(seed, cx, cz, density)) continue;

                double hx = plumeX(seed, cx, cz, scale);
                double hz = plumeZ(seed, cx, cz, scale);
                double dx = px - hx, dz = pz - hz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                // Cheap early-out: a plume this far away can affect neither the dome nor the trail,
                // so we skip it before doing any of the expensive plate work below.
                if (dist > Math.max(radius, trailLength)) continue;

                // Live plume: a smooth dome of activity around the centre.
                double strength = dist >= radius ? 0.0
                        : Mth.clamp(1.0 - (dist / radius), 0.0, 1.0);

                // Extinct trail: the plate has carried older volcanoes away, so the chain runs back
                // along MINUS the plate velocity. Only worth resolving when we are close enough for
                // the trail to reach us; the drift of a given plume is cached because it never
                // changes.
                boolean onTrail = false;
                double trailAge = 1.0;
                if (trailLength > 0.0 && dist <= trailLength) {
                    double[] v = plumeDrift(level, seed, cx, cz, hx, hz);
                    double vlen = Math.sqrt(v[0] * v[0] + v[1] * v[1]);
                    if (vlen > 1.0e-6) {
                        double tx = v[0] / vlen, tz = v[1] / vlen;  // direction the plate is heading
                        double along = dx * tx + dz * tz;           // + means "ahead of" the plume
                        double across = Math.abs(dx * tz - dz * tx);
                        if (along > 0 && along <= trailLength && across <= radius * 0.6) {
                            onTrail = true;
                            trailAge = Mth.clamp(along / trailLength, 0.0, 1.0);
                        }
                    }
                }
                if (strength > best.strength() || (best.strength() == 0.0 && dist < best.distance())) {
                    best = new Hotspot(strength, dist, onTrail ? trailAge : (strength > 0 ? 0.0 : 1.0), onTrail);
                }
            }
        }
        // Ground the world generator has already painted as thermal outranks the plume grid.
        //
        // We cannot tell Terralith where to put its yellowstone and caldera biomes, but we can go
        // where it went - and since Yellowstone is a hotspot and a caldera is what hotspot volcanism
        // leaves behind, reading one as evidence of a plume is the geology rather than a shortcut.
        // See ThermalBiomes for why this is safe with any terrain mod, or none.
        double painted = ThermalBiomes.strength(level, blockX, blockZ);
        if (painted > best.strength()) {
            return new Hotspot(painted, 0.0, 0.0, false);
        }
        return best;
    }

    /**
     * How deeply this column sits inside a <b>geyser basin</b>: 0 outside one, rising to 1 at its
     * centre. Meaningful only where {@link #sample} already reports a live plume.
     *
     * <h2>Why a plume is not uniformly rich</h2>
     * Yellowstone holds roughly half the geysers on Earth, but they are not sprinkled evenly over the
     * caldera - they sit in a handful of basins (Upper, Lower, Norris, West Thumb) with miles of
     * ordinary forest between them, because a geyser needs a specific plumbing of fractured rock and
     * circulating water, not merely heat. Spreading vents evenly across a 700-block dome gave a
     * thin scatter that never read as a geyser field at all. Clustering them into basins is both what
     * the real thing does and what makes finding one feel like finding something.
     *
     * <p>Pure seed maths on its own coarse grid, exactly like the plume layer above it.</p>
     */
    public static double basinStrength(ServerLevel level, int blockX, int blockZ) {
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return 0.0;
        // A biome that is already painted as a thermal basin IS the basin - there is no sense in
        // subdividing Terralith's Yellowstone into quiet country and hot country when the whole
        // point of the place is that it is hot.
        double painted = ThermalBiomes.strength(level, blockX, blockZ);
        if (painted >= 0.8) return painted;

        double scale = GeyserConfig.HOTSPOT_BASIN_SCALE.get();
        double density = GeyserConfig.HOTSPOT_BASIN_DENSITY.get();
        if (density <= 0.0) return painted;
        long seed = level.getSeed();

        int gx = Mth.floor(blockX / scale);
        int gz = Mth.floor(blockZ / scale);
        double best = 0.0;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int cx = gx + ox, cz = gz + oz;
                if (rand01(hash(seed, cx, cz, 0x3C9A17L)) >= density) continue;
                double bx = (cx + 0.5 + (rand01(hash(seed, cx, cz, 0x7B2153L)) - 0.5) * 0.7) * scale;
                double bz = (cz + 0.5 + (rand01(hash(seed, cx, cz, 0x11D3E9L)) - 0.5) * 0.7) * scale;
                double radius = scale * (0.20 + 0.25 * rand01(hash(seed, cx, cz, 0x5A44B1L)));
                double d = Math.hypot(blockX - bx, blockZ - bz);
                if (d >= radius) continue;
                best = Math.max(best, 1.0 - d / radius);
            }
        }
        return best;
    }

    /**
     * Drift of the plate riding over a given plume. Constant for the life of the world, so it is
     * cached: without this, sampling the hotspot field would re-run the whole Voronoi plate solve
     * for every candidate plume of every column, which the chat map would feel immediately.
     */
    private static final Map<String, double[]> DRIFT_CACHE = new ConcurrentHashMap<>();

    private static double[] plumeDrift(ServerLevel level, long seed, int cx, int cz,
                                       double hx, double hz) {
        String key = level.dimension().location() + "@" + cx + ":" + cz;
        return DRIFT_CACHE.computeIfAbsent(key, k -> {
            PlateSample plate = TectonicMap.sampleCached(level, (int) hx, (int) hz);
            return new double[] { plate.plateVelX(), plate.plateVelZ() };
        });
    }

    /** Drops the cached plume drifts; called alongside the plate cache when a server stops. */
    public static void clearCache() {
        DRIFT_CACHE.clear();
        ThermalBiomes.clearCache();
    }

    // === Layout =============================================================

    /** Only a fraction of grid cells host a plume, which is what keeps hotspots rare. */
    private static boolean cellHasPlume(long seed, int cx, int cz, double density) {
        return rand01(hash(seed, cx, cz, 0x487A5L)) < density;
    }

    private static double plumeX(long seed, int cx, int cz, double scale) {
        return (cx + 0.5 + (rand01(hash(seed, cx, cz, 0x1B7C3L)) - 0.5) * 0.7) * scale;
    }

    private static double plumeZ(long seed, int cx, int cz, double scale) {
        return (cz + 0.5 + (rand01(hash(seed, cx, cz, 0x6E19DL)) - 0.5) * 0.7) * scale;
    }

    private static long hash(long seed, int x, int z, long salt) {
        long h = seed ^ salt;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    private static double rand01(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
