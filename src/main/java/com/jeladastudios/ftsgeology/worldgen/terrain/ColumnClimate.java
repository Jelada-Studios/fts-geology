package com.jeladastudios.ftsgeology.worldgen.terrain;

import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Climate;

/**
 * The climate of a column as the look of its ground reads it: the climate's own temperature and humidity (the values
 * vanilla picks its biomes by, -1 to 1), the mean yearly temperature at the sea and at a height, and the tree line, all
 * as {@link AltitudeBelts} works them out for the mountains' belts.
 */
public final class ColumnClimate {

    private ColumnClimate() {}

    /** A column's climate: temperature and humidity, -1 to 1. */
    public record At(double temperature, double humidity) {

        /** The mean yearly temperature at the sea, degrees Celsius. */
        public double yearAtSea() {
            return AltitudeBelts.seaLevel(temperature);
        }

        /** The mean yearly temperature at height {@code y}, cooled with height at the real rate. */
        public double yearAt(int y, int sea) {
            return AltitudeBelts.at(yearAtSea(), y, sea, metres());
        }

        /** The height of the tree line over a column, with the line's own wavering there. */
        public int treeLine(int x, int z, int sea) {
            return AltitudeBelts.treeLine(AltitudeBelts.summer(yearAtSea()) + AltitudeBelts.waver(x, z), sea, metres());
        }

        /** The height of the snow line over a column. */
        public int snowLine(int x, int z, int sea) {
            return AltitudeBelts.snowLine(AltitudeBelts.summer(yearAtSea()) + AltitudeBelts.waver(x, z), sea, metres());
        }

        /** Between this and another, {@code t} of the way. */
        public At lerp(double t, At o) {
            return new At(Mth.lerp(t, temperature, o.temperature), Mth.lerp(t, humidity, o.humidity));
        }
    }

    /** The climate the world's generator gives a column. */
    public static At at(ServerLevel level, int x, int z) {
        return at(level.getChunkSource().randomState().sampler(), x, z);
    }

    /** The climate a sampler gives a column. */
    public static At at(Climate.Sampler sampler, int x, int z) {
        Climate.TargetPoint p = sampler.sample(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z));
        return new At(Climate.unquantizeCoord(p.temperature()), Climate.unquantizeCoord(p.humidity()));
    }

    /**
     * The four corners of a chunk from the generator the mod's world is built with, for a surface rule, which has no
     * level to ask; null where none is bound.
     */
    public static At[] corners(int x0, int z0) {
        Climate.Sampler s = RawGround.climate();
        if (s == null) return null;
        return new At[]{corner(s, x0, z0), corner(s, x0 + 16, z0), corner(s, x0, z0 + 16), corner(s, x0 + 16, z0 + 16)};
    }

    /**
     * The climate at each column of a chunk, from its four corners, which the chunks round it share: whatever is drawn
     * by it has no edge along the chunk's. {@code corners} are the corners' climates, north-west, north-east,
     * south-west, south-east.
     */
    public static At blend(At[] corners, int dx, int dz) {
        return corners[0].lerp(dx / 16.0, corners[1]).lerp(dz / 16.0, corners[2].lerp(dx / 16.0, corners[3]));
    }

    /** The four corners of a chunk, for {@link #blend}. */
    public static At[] corners(ServerLevel level, int x0, int z0) {
        Climate.Sampler s = level.getChunkSource().randomState().sampler();
        return new At[]{corner(s, x0, z0), corner(s, x0 + 16, z0), corner(s, x0, z0 + 16), corner(s, x0 + 16, z0 + 16)};
    }

    /** A chunk corner's climate: each is shared by four chunks and asked by the soil, the cliffs and the ground rules. */
    private static At corner(Climate.Sampler s, int x, int z) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(x, z);
        At hit = CORNERS.get(key);
        if (hit != null) return hit;
        At a = at(s, x, z);
        CORNERS.put(key, a);
        return a;
    }

    private static final com.jeladastudios.ftsgeology.util.ColumnCache<At> CORNERS = new com.jeladastudios.ftsgeology.util.ColumnCache<>(14);

    /** Forgets the corners: the server is stopping, and the next world may be another. */
    public static void clear() {
        CORNERS.clear();
    }

    /** Metres a block of height stands for, at the world's own scale. */
    public static double metres() {
        return TerrainFields.METRES_PER_BLOCK / TerrainContext.params().horizontal();
    }
}
