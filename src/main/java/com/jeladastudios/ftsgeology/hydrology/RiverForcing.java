package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.compat.SereneSeasons;
import com.jeladastudios.ftsgeology.weather.RainClimate;
import com.jeladastudios.ftsgeology.weather.Storms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the weather does to the rivers now, against what they carry on average: for a mod that runs the rivers' water
 * itself (see the API's hydraulics). The long-run discharge of the network already holds the average rain and
 * evaporation; these are what comes on top or falls short.
 *
 * <p>{@code baseFactor} scales every source of water: the season of a place's regime (snow-fed rivers at the melt, a
 * Mediterranean river in winter, a monsoon river in summer; 1 over the year) and the region's wet and dry spells (1 on
 * average). {@code stormFactor}, for the heads and the ways out of lakes, is the quick rise of a catchment in a heavy rain
 * on soaked ground: 1 with no rain. Where the soil's water is known -- a loaded chunk, looked at every ten seconds -- the
 * rain running off its cells beyond what the ground takes in, the rain on open water less what the air takes back, how
 * freely a river's bed leaks into the ground, and how far the wells have drawn the ground's water down are given by
 * cell.</p>
 *
 * <p>Everything here is in the addon's clock: blocks of water a second at twenty ticks a second, though the ground keeps
 * its own faster time (a game day is a week of it). Every value is made whole and published at once, so it is read on
 * any thread.</p>
 */
public final class RiverForcing {

    private RiverForcing() {}

    /** What one chunk's cells are doing, published by the soil's water on the server thread. */
    record Cellwise(float[] runoff, float[] open, float[] leak, float[] draw, double soaked, long tick) {}

    private static final Map<Long, Cellwise> CELLS = new ConcurrentHashMap<>();

    /** How much a soaked catchment's discharge rises in the heaviest rain, over its long-run discharge. */
    private static final double STORM_GAIN = 2.0;
    /** A season's share of the year's rain under which a river runs low, as SeasonalRivers has it. */
    private static final double LOW = 0.45;

    /** Blocks of water a second, in the addon's clock, of a rate in millimetres an hour of the ground's time. */
    static double perSecond(double mmPerGroundHour) {
        return mmPerGroundHour * SoilWater.HOURS_PER_TICK * 20.0 / 1000.0;
    }

    static void forget(long chunk) {
        CELLS.remove(chunk);
    }

    static void clear() {
        CELLS.clear();
    }

    /** A chunk's cells after a look of {@code hours} of the ground's time (0 for a look that settled them). */
    static void publish(ServerLevel level, LevelChunk chunk, SoilWater.Cells c, double hours, boolean raining,
                        boolean storm, boolean day) {
        float[] runoff = new float[16], open = new float[16], leak = new float[16], draw = new float[16];
        // The ground under the chunk's water: what most of its other cells are, loam where none is ground.
        int[] counts = new int[SoilWater.Soil.values().length];
        for (int i = 0; i < 16; i++) if (c.kind[i] >= 0) counts[c.kind[i]]++;
        SoilWater.Soil under = SoilWater.Soil.LOAM;
        int most = 0;
        for (SoilWater.Soil s : SoilWater.Soil.values()) {
            if (s.ground() && counts[s.ordinal()] > most) {
                most = counts[s.ordinal()];
                under = s;
            }
        }
        ChunkPos p = chunk.getPos();
        for (int i = 0; i < 16; i++) {
            SoilWater.Soil s = c.kind[i] >= 0 ? SoilWater.Soil.values()[c.kind[i]] : SoilWater.Soil.NONE;
            if (hours > 0) runoff[i] = (float) perSecond(c.cellRunoff[i] / hours);
            draw[i] = Math.max(0f, c.lowered);
            SoilWater.Soil bed = s.ground() ? s : under;
            // A bed a block thick: a block of head over it passes the soil's own rate through it.
            leak[i] = (float) perSecond(bed.conductivity);
            if (s == SoilWater.Soil.WATER) {
                int x = p.getMinBlockX() + (i & 3) * 4 + 2, z = p.getMinBlockZ() + (i >> 2) * 4 + 2;
                var biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(z)).value();
                double pet = SoilWater.evaporation(biome);
                boolean rains = biome.hasPrecipitation();
                double rainNow = rains && raining ? (storm ? SoilWater.STORM : SoilWater.RAIN) : 0;
                double petNow = pet * (day ? 1.9 : 0.1) * (raining ? 0.3 : 1.0);
                double mean = (rains ? SoilWater.RAIN * SoilWater.RAINY_SHARE : 0) - pet;
                open[i] = (float) perSecond((rainNow - petNow) - mean);
            }
        }
        double soaked = SoilWater.soaked(level, p.x, p.z);
        CELLS.put(p.toLong(), new Cellwise(runoff, open, leak, draw, soaked, level.getGameTime()));
    }

    // === The factors ========================================================

    /** {base, storm, season, phase (0 without seasons), drought, seasonal (1 or 0)} at a place. */
    static double[] factors(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        double phase = SereneSeasons.phase(level);
        boolean seasonal = !Double.isNaN(phase);
        double season = seasonal ? season(level, bx, bz, phase) : 1.0;
        double wet = Storms.spell(level, x, z);
        double spell = 0.5 + wet;
        Cellwise c = CELLS.get(ChunkPos.asLong(bx >> 4, bz >> 4));
        double soaked = c != null && c.soaked() >= 0 ? c.soaked() : 0.3 + 0.6 * wet;
        double rain = Storms.sky().rain(x, z);
        double storm = 1.0 + STORM_GAIN * rain * soaked;
        double drought = Mth.clamp((0.35 - wet) / 0.35, 0.0, 1.0);
        return new double[]{season * spell, storm, season, seasonal ? phase : 0.0, drought, seasonal ? 1 : 0};
    }

    /** The factors over a catchment by node, with the 200-tick step they were worked out in: {step, base, storm, ...}. */
    private static final com.jeladastudios.ftsgeology.util.SetCache<double[]> UPSTREAM = new com.jeladastudios.ftsgeology.util.SetCache<>(12);

    /**
     * The factors over a catchment: the average at a few of the nodes up its main river. Kept for each node for the
     * 200 ticks the soil's cells are kept for, so a chunk's columns asking one after another cost one working out.
     */
    static double[] upstream(ServerLevel level, long node) {
        long step = level.getGameTime() / 200;
        double[] hit = UPSTREAM.get(node);
        if (hit != null && hit[0] == step) return java.util.Arrays.copyOfRange(hit, 1, 7);
        double[] f = upstreamNow(level, node);
        double[] kept = new double[7];
        kept[0] = step;
        System.arraycopy(f, 0, kept, 1, 6);
        UPSTREAM.put(node, kept);
        return f;
    }

    private static double[] upstreamNow(ServerLevel level, long node) {
        long[] up = RiverFlow.upstream(node, 4);
        double[] sum = new double[6];
        int n = 0;
        for (long k : up) {
            double[] at = RiverFlow.at(k);
            double[] f = factors(level, at[0], at[1]);
            for (int i = 0; i < 6; i++) sum[i] += f[i];
            n++;
        }
        if (n == 0) return factors(level, RiverFlow.at(node)[0], RiverFlow.at(node)[1]);
        for (int i = 0; i < 6; i++) sum[i] /= n;
        return sum;
    }

    /** A place's river flow this time of the year against its year's average. */
    static double season(ServerLevel level, int x, int z, double phase) {
        RainClimate.Here h = RainClimate.at(level, x, z);
        float t = com.jeladastudios.ftsgeology.util.Loaded.biome(level, new BlockPos(x, level.getSeaLevel() + 8, z)).value().getBaseTemperature();
        boolean snowFed = t < 0.5f && h.regime() != RainClimate.Regime.POLAR;
        if (snowFed) return (1.0 + 4.5 * bump(phase, 0.0, 0.13)) / (1.0 + 4.5 * 0.13);
        if (h.regime() == RainClimate.Regime.ARID || h.regime() == RainClimate.Regime.POLAR) return 1.0;
        if (h.regime() == RainClimate.Regime.TROPICAL) return flow(RainClimate.season(level, x, z));
        return flow(RainClimate.seasonShare(h.regime(), phase)) / mean(h.regime());
    }

    /** River flow for a season's share of the year's rain: low in the dry season, high in the wet. */
    private static double flow(double share) {
        return share < LOW ? 0.6 * share / LOW : 1.0 + 3.0 * Math.max(0.0, share - 1.0);
    }

    private static final Map<RainClimate.Regime, Double> MEANS = new EnumMap<>(RainClimate.Regime.class);

    private static synchronized double mean(RainClimate.Regime r) {
        Double m = MEANS.get(r);
        if (m != null) return m;
        double sum = 0;
        for (int i = 0; i < 48; i++) sum += flow(RainClimate.seasonShare(r, (i + 0.5) / 48.0));
        double v = Math.max(0.1, sum / 48.0);
        MEANS.put(r, v);
        return v;
    }

    private static double bump(double phase, double centre, double half) {
        double d = Math.abs(phase - centre);
        d = Math.min(d, 1.0 - d);
        if (d >= half) return 0.0;
        double c = Math.cos(d / half * Math.PI / 2);
        return c * c;
    }

    // === Asked of a chunk ===================================================

    /** The forcing of one chunk; its cells' arrays are zero, and {@code cellsKnown} false, where the chunk is not loaded. */
    public record Values(double baseFactor, double stormFactor, float[] runoffExcess, float[] openWater,
                         float[] leakance, float[] drawdown, boolean cellsKnown, boolean seasonal, double seasonPhase,
                         double seasonFactor, double drought, long updatedTick) {}

    public static Values at(ServerLevel level, int cx, int cz) {
        double[] f = factors(level, (cx << 4) + 8, (cz << 4) + 8);
        Cellwise c = CELLS.get(ChunkPos.asLong(cx, cz));
        if (c == null) {
            return new Values(f[0], f[1], new float[16], new float[16], new float[16], new float[16], false, f[5] > 0,
                    f[3], f[2], f[4], level.getGameTime());
        }
        return new Values(f[0], f[1], c.runoff().clone(), c.open().clone(), c.leak().clone(), c.draw().clone(), true,
                f[5] > 0, f[3], f[2], f[4], c.tick());
    }
}
