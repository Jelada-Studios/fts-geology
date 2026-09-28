package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.worldgen.terrain.DemLibrary;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.LandmarkSites;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import net.minecraft.server.level.ServerLevel;

/**
 * Everest, K2 and the Matterhorn stand where a belt of mountains is, but the belt they stand in can be a subduction
 * margin or a transform, or lie a few hundred blocks off its fault: in one world the Matterhorn was four hundred blocks
 * from the nearest boundary and quakes never touched it. The real three stand on collision belts -- the Himalaya, the
 * Karakoram, the Alps -- that still rise and still shake.
 *
 * <p>So for the quakes and the instruments, and never for the terrain, which is left exactly as it was generated,
 * each of them has a collision fault of its own running along its range's axis: across the mountain's own width it
 * reads as a collision boundary, stressed most on the axis, and quakes start there and run along it.</p>
 */
public final class LandmarkFaults {

    private LandmarkFaults() {}

    /** How far either side of the axis the landmark's fault zone reaches at least, in blocks. */
    private static final double ZONE_MIN = 400.0;

    /** The plate reading at a column for quakes and instruments: the landmarks' own faults laid over the plates. */
    public static PlateSample sample(ServerLevel level, int x, int z) {
        return adjust(level, x, z, TectonicMap.sample(level, x, z));
    }

    /** As {@link #sample}, from the cached plate reading. */
    public static PlateSample sampleCached(ServerLevel level, int x, int z) {
        return adjust(level, x, z, TectonicMap.sampleCached(level, x, z));
    }

    static PlateSample adjust(ServerLevel level, int x, int z, PlateSample s) {
        if (!DemLibrary.landmarksReady() || !GeologyWorld.isOwn(level)) return s;
        LandmarkSites.Site[] sites = LandmarkSites.all(TerrainContext.seed(), TerrainContext.params());
        double mpb = 25.0 / TerrainContext.params().horizontal();
        for (LandmarkSites.Site site : sites) {
            double halfAlong = DemLibrary.landmarkHalfAlong(site.which()) / mpb;
            double zone = Math.max(ZONE_MIN, DemLibrary.landmarkHalfAcross(site.which()) / mpb);
            double c = Math.cos(site.bearing()), sn = Math.sin(site.bearing());
            double dx = x - site.x(), dz = z - site.z();
            double along = dx * c + dz * sn;
            double across = -dx * sn + dz * c;
            if (Math.abs(along) > halfAlong + zone || Math.abs(across) > zone) continue;
            double d = Math.abs(across);
            // Only where the plates themselves have nothing nearer to say.
            if (s.faultType() == FaultType.CONVERGENT_COLLISION && s.faultDistance() <= d) return s;
            if (s.faultType() != FaultType.INTERIOR && s.faultDistance() < d * 0.5) return s;
            double sign = across >= 0 ? 1.0 : -1.0;
            // The normal points from the column to the axis; the strike runs along the range.
            double nx = -sign * -sn, nz = -sign * c;
            double stress = 0.35 + 0.5 * (1.0 - d / zone);
            return new PlateSample(s.plateId(), PlateKind.CONTINENTAL, s.plateVelX(), s.plateVelZ(), s.neighbourId(),
                    PlateKind.CONTINENTAL, FaultType.CONVERGENT_COLLISION, d, 1.0, 0.2, nx, nz, stress, along);
        }
        return s;
    }
}
