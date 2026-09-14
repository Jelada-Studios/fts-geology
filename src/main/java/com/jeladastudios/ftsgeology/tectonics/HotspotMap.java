package com.jeladastudios.ftsgeology.tectonics;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantle hotspots: the intraplate volcanism plate boundaries cannot explain, such as Yellowstone and
 * Hawaii.
 *
 * <p>A plume is fixed while the plate slides over it, so volcanism is live only above the plume and
 * older volcanoes trail back along the way the plate came from. {@link #sample} reports both the
 * live strength and the position along that trail.</p>
 *
 * <p>Like {@link TectonicMap}, pure seed-derived maths.</p>
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

                // Extinct trail: back along minus the plate velocity. A plume's drift is cached.
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
        // Ground the generator painted as thermal, such as Terralith's Yellowstone, outranks the
        // plume grid; see ThermalBiomes.
        double painted = ThermalBiomes.strength(level, blockX, blockZ);
        if (painted > best.strength()) {
            return new Hotspot(painted, 0.0, 0.0, false);
        }
        return best;
    }

    /**
     * How deep this column sits inside a geyser basin: 0 outside, 1 at its centre. Meaningful only
     * over a live plume. Geysers cluster in a few basins, as at Yellowstone, rather than scattering
     * evenly over the dome.
     */
    public static double basinStrength(ServerLevel level, int blockX, int blockZ) {
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return 0.0;
        // A biome already painted as a thermal basin is the basin.
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
     * Strength of the live plume dome here, 0 to 1, without the trail or the biome reading. Cheaper
     * than {@link #sample}, which asks {@link ThermalBiomes} and so the generator's base height.
     */
    public static double plumeStrength(ServerLevel level, int blockX, int blockZ) {
        return plumeStrength(level.getSeed(), blockX, blockZ, GeologyParams.current());
    }

    /** The plume dome from the seed alone, for the terrain generator. */
    public static double plumeStrength(long seed, int blockX, int blockZ, GeologyParams params) {
        if (!params.hotspots()) return 0.0;
        double scale = params.hotspotScale();
        double density = params.hotspotDensity();
        double radius = params.hotspotRadius();
        int gx = Mth.floor(blockX / scale);
        int gz = Mth.floor(blockZ / scale);
        double best = 0.0;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int cx = gx + ox, cz = gz + oz;
                if (!cellHasPlume(seed, cx, cz, density)) continue;
                double d = Math.hypot(blockX - plumeX(seed, cx, cz, scale), blockZ - plumeZ(seed, cx, cz, scale));
                if (d < radius) best = Math.max(best, 1.0 - d / radius);
            }
        }
        return best;
    }

    /** Drift of the plate over a plume, cached: it never changes and costs a full Voronoi solve. */
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

    /**
     * Centres of the plumes inside a box of blocks, as {x, z}. The same arithmetic
     * {@link #plumeStrength} runs, so it costs nothing but the seed.
     */
    public static java.util.List<int[]> plumeCentres(ServerLevel level, int minX, int minZ,
                                                     int maxX, int maxZ) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return out;
        double scale = GeyserConfig.HOTSPOT_SCALE.get();
        double density = GeyserConfig.HOTSPOT_DENSITY.get();
        long seed = level.getSeed();
        for (int cx = Mth.floor(minX / scale) - 1; cx <= Mth.floor(maxX / scale) + 1; cx++) {
            for (int cz = Mth.floor(minZ / scale) - 1; cz <= Mth.floor(maxZ / scale) + 1; cz++) {
                if (!cellHasPlume(seed, cx, cz, density)) continue;
                double px = plumeX(seed, cx, cz, scale), pz = plumeZ(seed, cx, cz, scale);
                if (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ) {
                    out.add(new int[] {(int) Math.floor(px), (int) Math.floor(pz)});
                }
            }
        }
        return out;
    }

    /**
     * A plume under an oceanic plate and the way its islands are carried off it.
     *
     * @param dirX    unit direction the plate moves, X part: older islands lie further along it
     * @param plateId the plate over the plume
     */
    public record Trail(double x, double z, double dirX, double dirZ, long plateId) {}

    /**
     * The plumes under oceanic plates whose track of {@code length} blocks can reach into this box of blocks. The
     * track runs from the plume in the direction the plate moves, as the Hawaiian-Emperor chain does.
     */
    public static java.util.List<Trail> oceanTrails(ServerLevel level, int minX, int minZ, int maxX, int maxZ,
                                                    double length) {
        java.util.List<Trail> out = new java.util.ArrayList<>();
        if (!GeyserConfig.HOTSPOTS_ENABLED.get() || length <= 0.0) return out;
        double scale = GeyserConfig.HOTSPOT_SCALE.get();
        double density = GeyserConfig.HOTSPOT_DENSITY.get();
        long seed = level.getSeed();
        for (int cx = Mth.floor((minX - length) / scale) - 1; cx <= Mth.floor((maxX + length) / scale) + 1; cx++) {
            for (int cz = Mth.floor((minZ - length) / scale) - 1; cz <= Mth.floor((maxZ + length) / scale) + 1; cz++) {
                if (!cellHasPlume(seed, cx, cz, density)) continue;
                double px = plumeX(seed, cx, cz, scale), pz = plumeZ(seed, cx, cz, scale);
                if (px < minX - length || px > maxX + length || pz < minZ - length || pz > maxZ + length) continue;
                PlateSample plate = TectonicMap.sampleCached(level, (int) Math.floor(px), (int) Math.floor(pz));
                double[] v = plumeDrift(level, seed, cx, cz, px, pz);
                double len = Math.sqrt(v[0] * v[0] + v[1] * v[1]);
                if (len < 1.0e-6) continue;
                out.add(new Trail(px, pz, v[0] / len, v[1] / len, plate.plateId()));
            }
        }
        return out;
    }

    // === Layout =============================================================

    /** Only a fraction of grid cells host a plume, which is what keeps hotspots rare. */
    private static boolean cellHasPlume(long seed, int cx, int cz, double density) {
        return rand01(hash(seed, cx, cz, 0x487A5L)) < density;
    }

    /** The centre of the live plume whose dome this column is under, or null where there is none. */
    public static double[] plumeCentre(ServerLevel level, int blockX, int blockZ) {
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return null;
        double scale = GeyserConfig.HOTSPOT_SCALE.get();
        double density = GeyserConfig.HOTSPOT_DENSITY.get();
        double radius = GeyserConfig.HOTSPOT_RADIUS.get();
        long seed = level.getSeed();
        int gx = Mth.floor(blockX / scale), gz = Mth.floor(blockZ / scale);
        double[] best = null;
        double bestD = radius;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int cx = gx + ox, cz = gz + oz;
                if (!cellHasPlume(seed, cx, cz, density)) continue;
                double px = plumeX(seed, cx, cz, scale), pz = plumeZ(seed, cx, cz, scale);
                double d = Math.hypot(blockX - px, blockZ - pz);
                if (d < bestD) { bestD = d; best = new double[] {px, pz}; }
            }
        }
        return best;
    }

    private static double plumeX(long seed, int cx, int cz, double scale) {
        return (cx + 0.5 + (rand01(hash(seed, cx, cz, 0x1B7C3L)) - 0.5) * 0.7) * scale;
    }

    private static double plumeZ(long seed, int cx, int cz, double scale) {
        return (cz + 0.5 + (rand01(hash(seed, cx, cz, 0x6E19DL)) - 0.5) * 0.7) * scale;
    }

}
