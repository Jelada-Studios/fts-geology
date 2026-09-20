package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.util.ColumnCache;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Where the groundwater is.
 *
 * <p>The water table is a subdued replica of the topography: it rises under hills and falls under
 * valleys, with far less relief. Where it wants to stand above the local ground the water comes out
 * as a spring, so springs land on valley floors and at the foot of scarps. How far below the land it
 * sits is a climate reading: a couple of blocks in a rainforest, tens in a desert.</p>
 *
 * <p>Pure maths, like {@link com.jeladastudios.ftsgeology.tectonics.TectonicMap}: the regional
 * surface comes from the chunk generator's base height, which loads nothing.</p>
 */
public final class WaterTable {

    private WaterTable() {}

    /**
     * How far out the neighbourhood is sampled, in blocks. Measured: at 32 the ring never leaves a
     * valley and no springs appear; at 96 it spans a Terralith valley while ignoring single hills.
     */
    private static final int RING_SPREAD = 96;

    /**
     * A groundwater reading for one column.
     *
     * @param head         Y the water surface WANTS to be at, before the land gets in the way.
     *                     Above {@code localSurface} this is what drives a spring.
     * @param tableY       Y the water actually sits at: {@code head} capped at the land surface.
     * @param localSurface generator surface height of this column.
     * @param regional     mean surface height over the ring - what recharges the aquifer.
     * @param base         lowest surface height on the ring - the drain the water runs to.
     * @param unsaturated  thickness of dry ground above the table, in blocks, from the climate.
     */
    public record Sample(int head, int tableY, int localSurface, int regional, int base,
                         int unsaturated) {

        /**
         * True where the water surface stands at or above the land: a spring line. Ground at or
         * below sea level is excluded, since the table there is just the sea.
         */
        public boolean isSpringLine(int seaLevel) {
            return localSurface > seaLevel && head >= localSurface;
        }

        /**
         * How hard the water is pushing out, in blocks of excess head. Zero away from a spring
         * line; a few blocks in a strong valley-floor spring. Callers use it to decide how big a
         * seep should be.
         */
        public int artesianHead() {
            return Math.max(0, head - localSurface);
        }

        /** Blocks of dry ground between the surface and the water. What a well has to be dug. */
        public int depthToWater() {
            return Math.max(0, localSurface - tableY);
        }
    }

    // === Public API =========================================================

    /**
     * The groundwater picture for a column. Never returns null; if the generator cannot answer,
     * falls back to a flat table at sea level, which is what a world with no relief would have.
     */
    public static Sample sample(ServerLevel level, int blockX, int blockZ) {
        int sea = level.getSeaLevel();
        // Switched off, the world behaves as if it had no groundwater model at all: a flat table at
        // sea level, no spring lines anywhere, and every caller falls back to its older rule.
        if (!GeyserConfig.WATER_TABLE_ENABLED.get()) return new Sample(sea, sea, sea, sea, sea, 0);
        try {
            ServerChunkCache chunkSource = level.getChunkSource();

            int local = surfaceAt(chunkSource, level, blockX, blockZ);

            // Eight neighbours on a ring, plus this column. The MEAN of them is what recharges the
            // aquifer - the land rain falls on. The MINIMUM is the drain it runs to.
            int sum = local, base = local;
            int d = RING_SPREAD, diag = (int) Math.round(RING_SPREAD * 0.7071);
            int[][] ring = {
                    {d, 0}, {-d, 0}, {0, d}, {0, -d},
                    {diag, diag}, {diag, -diag}, {-diag, diag}, {-diag, -diag}};
            for (int[] o : ring) {
                int h = regionalAt(chunkSource, level, blockX + o[0], blockZ + o[1]);
                sum += h;
                base = Math.min(base, h);
            }
            int regional = Math.round(sum / 9.0f);

            int unsaturated = unsaturatedThickness(chunkSource, level, blockX, blockZ, local);

            // The table rises from the local drain towards the recharge area, damped: a subdued
            // replica of the land. The datum is the drain, not sea level, since a plateau discharges
            // to the valley beside it.
            double subdual = GeyserConfig.WATER_TABLE_SUBDUAL.get();
            int head = (int) Math.round(base + (regional - base) * subdual) - unsaturated;

            // Only a coastal aquifer is held at sea level. Inland the table may drop below the valley
            // floor, which is what a dry valley is.
            if (base < sea) head = Math.max(head, sea);
            head = Math.max(head, level.getMinBuildHeight() + 1);

            // Water cannot stand above the land. Where it wants to, it comes out instead - which is
            // what isSpringLine() reads off the uncapped head.
            int tableY = Math.min(head, local);

            return new Sample(head, tableY, local, regional, base, unsaturated);
        } catch (Throwable t) {
            return new Sample(sea, sea, sea, sea, sea, 0);
        }
    }

    /**
     * Cached {@link #sample}, on a {@link #SAMPLE_GRID} grid. A sample costs a stack of generator height
     * lookups and each of those runs a whole noise column, so anything that walks a chunk calls this one.
     *
     * <p>Measured on the two worlds of the twenty-second round: this call was three quarters of everything
     * the mod spent on the server thread, and near enough all of it was misses. The grid was four blocks and
     * the ring reaches ninety-six, so no two neighbouring cells shared a single reading -- about a hundred
     * and forty noise columns for one chunk. The ring is a regional field by its own definition, so it is
     * read on a coarse lattice and kept; only the column at the middle, which is what decides a spring line,
     * is still read where it stands.</p>
     */
    public static Sample sampleCached(ServerLevel level, int blockX, int blockZ) {
        int gx = blockX & ~(SAMPLE_GRID - 1), gz = blockZ & ~(SAMPLE_GRID - 1);
        long key = ColumnCache.key(gx, gz);
        Sample hit = CACHE.get(key);
        if (hit != null) return hit;
        Sample s = sample(level, gx, gz);
        CACHE.put(key, s);
        return s;
    }

    /** Y of the water table, capped at the land. Shorthand for {@code sampleCached(..).tableY()}. */
    public static int tableY(ServerLevel level, int blockX, int blockZ) {
        return sampleCached(level, blockX, blockZ).tableY();
    }

    /** True where groundwater reaches the surface and would discharge. */
    public static boolean isSpringLine(ServerLevel level, int blockX, int blockZ) {
        return sampleCached(level, blockX, blockZ).isSpringLine(level.getSeaLevel());
    }

    /** Dropped alongside the other tectonic caches when a server stops. */
    public static void clearCache() {
        CACHE.clear();
        REGIONAL.clear();
    }

    // === Internals ==========================================================

    /** Blocks to a cached reading: one for a whole sample, a coarser one for the ring it averages. */
    private static final int SAMPLE_GRID = 16, REGIONAL_GRID = 32;

    /** Fixed tables, not maps that empty themselves when they fill: a distant-horizon mod fills them all day. */
    private static final ColumnCache<Sample> CACHE = new ColumnCache<>(14);
    private static final ColumnCache<int[]> REGIONAL = new ColumnCache<>(16);

    /** Counted so this class's cost can be read off a run: it was three quarters of the mod's tick. */
    private static final java.util.concurrent.atomic.LongAdder COLUMNS =
            new java.util.concurrent.atomic.LongAdder();

    /** How many whole noise columns the groundwater model has asked the generator for. */
    public static long noiseColumns() {
        return COLUMNS.sum();
    }

    /** Generator surface height, answered without loading or generating the chunk. */
    private static int surfaceAt(ServerChunkCache chunkSource, ServerLevel level, int x, int z) {
        COLUMNS.increment();
        return chunkSource.getGenerator().getBaseHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, level, chunkSource.randomState());
    }

    /**
     * The same height on a coarse lattice and kept. The ring is what recharges the aquifer and what it drains
     * to -- land measured a hundred blocks out, deliberately blunt -- so reading it every four blocks bought
     * nothing but noise columns. On this lattice a chunk's own cells share their readings with each other and
     * with the chunks round them.
     */
    private static int regionalAt(ServerChunkCache chunkSource, ServerLevel level, int x, int z) {
        int gx = Math.floorDiv(x, REGIONAL_GRID) * REGIONAL_GRID;
        int gz = Math.floorDiv(z, REGIONAL_GRID) * REGIONAL_GRID;
        long key = ColumnCache.key(gx, gz);
        int[] hit = REGIONAL.get(key);
        if (hit != null) return hit[0];
        int h = surfaceAt(chunkSource, level, gx, gz);
        REGIONAL.put(key, new int[]{h});
        return h;
    }

    /**
     * Thickness of dry ground above the table, from biome temperature and rainfall, so it matches
     * the landscape a player sees.
     */
    private static int unsaturatedThickness(ServerChunkCache chunkSource, ServerLevel level,
                                            int x, int z, int surface) {
        int temperate = GeyserConfig.WATER_TABLE_DEPTH_TEMPERATE.get();
        try {
            Climate.Sampler sampler = chunkSource.randomState().sampler();
            BiomeSource biomes = chunkSource.getGenerator().getBiomeSource();
            int sampleY = Math.max(surface - 2, level.getSeaLevel());
            Holder<Biome> holder = biomes.getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(z),
                    sampler);
            Biome biome = holder.value();

            int arid = GeyserConfig.WATER_TABLE_DEPTH_ARID.get();
            int humid = GeyserConfig.WATER_TABLE_DEPTH_HUMID.get();

            // No rainfall at all is the desert case: nothing recharges the aquifer from above, so
            // the table sits far down and springs are rare enough to be landmarks.
            if (!biome.hasPrecipitation()) {
                // Hot deserts are the deepest; cold dry ground (peaks, tundra) far less so, because
                // there is little evaporation to take the meltwater away again.
                return biome.getBaseTemperature() >= 1.0F ? arid : temperate;
            }

            // With rainfall, warmth decides how much of it stays: a jungle keeps its water at the
            // surface, a temperate wood drains steadily, a frozen biome is somewhere between.
            float t = biome.getBaseTemperature();
            double warmth = Mth.clamp((t - 0.3) / 0.7, 0.0, 1.0);
            return (int) Math.round(temperate + (humid - temperate) * warmth);
        } catch (Throwable ignored) {
            return temperate;
        }
    }
}
