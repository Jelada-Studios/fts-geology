package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the groundwater is.
 *
 * <h2>What a water table actually does</h2>
 * The single most common mistake is to imagine groundwater as a flat underground lake. It is not.
 * The water table is a <b>subdued replica of the topography</b>: it rises under hills and falls
 * under valleys, but with far less relief than the land above it. Rain falls on the high ground,
 * soaks in, and drains sideways towards the low ground, and the surface it forms is a smoothed
 * version of the landscape - a damped echo, not a level plane.
 *
 * <p>Two consequences follow, and they are the whole reason this class exists.</p>
 *
 * <h2>1. Springs are not random</h2>
 * Because the table follows the <i>regional</i> land surface while the ground follows the
 * <i>local</i> one, there are places where the water surface wants to be higher than the ground is.
 * Water cannot stand above the land, so it comes out: that is a spring. Valley floors, the foot of
 * a scarp, the wall of a canyon cut below the regional water level - these are where springs are in
 * the real world, and they fall out of the arithmetic here for free, because {@link Sample#head()}
 * and {@link Sample#localSurface()} are both computed anyway.
 *
 * <p>This is what gives the mod's hot springs a <i>provenance</i>. Until now a hot spring appeared
 * because a mantle plume was nearby. Heat is only half the recipe; the other half is water having
 * somewhere to come out, and that is a hydrological question, not a tectonic one.</p>
 *
 * <h2>2. Depth to water is a climate reading</h2>
 * How far below the land the table sits is set by how much rain gets in against how fast it drains
 * away again. In a rainforest that is a couple of blocks. In a desert it can be tens of metres,
 * which is exactly why desert wells are deep and desert springs are rare enough to build towns
 * around.
 *
 * <h2>Why it is pure maths</h2>
 * Same contract as {@link com.jeladastudios.ftsgeology.tectonics.TectonicMap}: any column in an
 * infinite world, answered with no precomputation, no chunk generation and no stored state, so it
 * is identical on every machine and survives world upgrades. The regional surface comes from the
 * chunk generator's own base-height function, which answers from generator maths without loading
 * anything - the same door {@code ThermalBiomes} already uses.
 */
public final class WaterTable {

    private WaterTable() {}

    /**
     * How far out the neighbourhood is sampled, in blocks.
     *
     * <p>This number is the model, and it was chosen by measurement rather than taste. Too small
     * and the ring never climbs out of a valley, so there is no relief for the damping to act on
     * and springs never appear at all: at spread 32 the spring rate measured 0.00% at every
     * setting of {@link GeyserConfig#WATER_TABLE_SUBDUAL}. Too large and the ring reaches across
     * whole ranges and the table flattens. At 96 blocks it spans a valley of the width Terralith
     * actually cuts while still ignoring single hills.</p>
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
         * True where the water surface stands at or above the land: a spring line.
         *
         * <h2>Why the sea-level test is here</h2>
         * Ground at or below sea level is coastline, lake bed or ocean floor. The water table there
         * is the sea, which is true but says nothing - and calling it a spring made a quarter of
         * all reported springs land on high ground, because the sea-level floor on {@code head}
         * lifted every low column into a false positive. Excluding them took that failure rate
         * from 25% to zero.
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
                int h = surfaceAt(chunkSource, level, blockX + o[0], blockZ + o[1]);
                sum += h;
                base = Math.min(base, h);
            }
            int regional = Math.round(sum / 9.0f);

            int unsaturated = unsaturatedThickness(chunkSource, level, blockX, blockZ, local);

            // The water table rises from the drain towards the recharge area, damped: that damping
            // is what makes it a SUBDUED replica of the land rather than a copy of it.
            //
            // The datum here is the local drain, NOT sea level. Damping towards the ocean was the
            // first version of this line and it was wrong twice over - physically, because a water
            // table under a plateau discharges to the valley beside it and not to a distant coast;
            // and numerically, because damping a typical 22-block elevation by a third gives less
            // than the unsaturated zone subtracts, so the table collapsed flat onto sea level
            // everywhere and no config key changed anything at all.
            double subdual = GeyserConfig.WATER_TABLE_SUBDUAL.get();
            int head = (int) Math.round(base + (regional - base) * subdual) - unsaturated;

            // A coastal aquifer cannot sit below the ocean it discharges into - but only a coastal
            // one. Clamping every column to sea level was the second version of the same mistake
            // the datum had: measured on ordinary lowland, it decided the water table for 93.8% of
            // desert columns, which pinned tableY at 63 and made the reported depth simply "how far
            // above the sea am I". That is why a desert and a temperate hill read identically in
            // testing - the dry-zone setting could not show through at all.
            //
            // Inland the table is allowed below the valley floor. That is not an error state: it is
            // what a dry valley is, and a desert should have them.
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
     * Cached {@link #sample}, rounded to the nearest four blocks.
     *
     * <p>A sample costs nine generator height lookups, which is far too much to repeat per column
     * during a chunk scan. Groundwater varies over hundreds of blocks, so rounding to four is
     * indistinguishable from exact - the same trade {@code TectonicMap.sampleCached} makes. Call
     * this one, not {@link #sample}, from anything that walks a chunk.</p>
     */
    public static Sample sampleCached(ServerLevel level, int blockX, int blockZ) {
        int gx = blockX & ~3, gz = blockZ & ~3;
        long key = (((long) gx) << 32) ^ (gz & 0xFFFFFFFFL);
        Sample hit = CACHE.get(key);
        if (hit != null) return hit;
        Sample s = sample(level, gx, gz);
        if (CACHE.size() > CACHE_MAX) CACHE.clear();
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
    }

    // === Internals ==========================================================

    private static final Map<Long, Sample> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 60000;

    /** Generator surface height, answered without loading or generating the chunk. */
    private static int surfaceAt(ServerChunkCache chunkSource, ServerLevel level, int x, int z) {
        return chunkSource.getGenerator().getBaseHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, level, chunkSource.randomState());
    }

    /**
     * Thickness of dry ground above the water table, read off the climate.
     *
     * <h2>Why temperature and rainfall, and not a noise field</h2>
     * Depth to water is a water balance: what soaks in, minus what drains and evaporates. Minecraft
     * already models both halves of that as biome climate, so reading them gives a table that lines
     * up with the landscape a player can see - deep under dunes, at your ankles in a swamp - rather
     * than a second invisible pattern laid over the top.
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
