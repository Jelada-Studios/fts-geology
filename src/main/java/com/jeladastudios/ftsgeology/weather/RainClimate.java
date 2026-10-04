package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.compat.SereneSeasons;
import com.jeladastudios.ftsgeology.util.SeedHash;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;

/**
 * How much rain a place has in a year, and how its year shares it out.
 *
 * <p><b>The year's rain</b>, millimetres, from the lie of the land as a climatologist reads it: the biome's own wetness
 * and warmth, how near the sea is, whether the prevailing wind comes in off the sea and climbs the ground here
 * (rain on the windward slopes: the Black Sea's mountain front, 2000-2500 mm) or has crossed a range on its way (the
 * rain shadow behind it: inner Anatolia, 300-400 mm), and the tropics' rain forests (2500-4000 mm). A desert has under
 * 150 mm, a temperate forest 700-1000, a Mediterranean coast 600-900. It holds with or without the seasons.</p>
 *
 * <p><b>The year's share</b>, where Serene Seasons keeps the seasons: the regime of the place -- Mediterranean (wet
 * winters, dry hot summers), oceanic (wet all year, most in autumn and winter), the Black Sea type (wet all year, most in
 * autumn), continental (a dry snowy winter, the most at the end of spring, a dry summer of showers), monsoon (a very wet
 * summer, a dry winter), tropical (the tropical biomes' own wet and dry halves) -- gives each part of the year its part of
 * the rain, a multiplier that comes to one over the year. Summer's rain comes in showers, winter's in long fronts.</p>
 *
 * <p>Worked out once for each 256-block square, from a dozen points of the generator's ground: cheap after.</p>
 */
public final class RainClimate {

    private RainClimate() {}

    public enum Regime { MEDITERRANEAN, OCEANIC, BLACK_SEA, CONTINENTAL, MONSOON, TROPICAL, ARID, POLAR }

    /** What a square of land is: its year's rain (mm), its regime, and the reading behind them. */
    public record Here(double mm, Regime regime, double coast, double lift, double shadow, boolean tropical) {}

    private static final int CELL = 256, KEEP = 4096;
    private static final Long2ObjectLinkedOpenHashMap<Here> CACHE = new Long2ObjectLinkedOpenHashMap<>();

    /** Sub-season shares of the year's rain, early spring first, for each regime (averaging one). */
    private static final double[][] SHARE = new double[Regime.values().length][];
    static {
        put(Regime.MEDITERRANEAN, 1.1, 0.8, 0.5, 0.25, 0.1, 0.2, 0.7, 1.4, 1.8, 2.0, 1.9, 1.5);
        put(Regime.OCEANIC, 0.9, 0.8, 0.75, 0.7, 0.75, 0.85, 1.0, 1.2, 1.35, 1.3, 1.25, 1.1);
        put(Regime.BLACK_SEA, 0.85, 0.75, 0.75, 0.8, 0.75, 0.95, 1.3, 1.5, 1.4, 1.15, 1.0, 0.9);
        put(Regime.CONTINENTAL, 1.0, 1.5, 1.8, 1.4, 0.8, 0.6, 0.6, 0.8, 0.9, 0.9, 0.85, 0.85);
        put(Regime.MONSOON, 0.3, 0.5, 0.9, 1.8, 2.6, 2.4, 1.4, 0.7, 0.4, 0.3, 0.25, 0.25);
        put(Regime.TROPICAL, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        put(Regime.ARID, 1.1, 1.0, 0.8, 0.6, 0.5, 0.6, 0.8, 1.0, 1.2, 1.5, 1.5, 1.4);
        put(Regime.POLAR, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    }

    private static void put(Regime r, double... s) {
        double sum = 0;
        for (double v : s) sum += v;
        double[] out = new double[12];
        for (int i = 0; i < 12; i++) out[i] = s[i] * 12.0 / sum;
        SHARE[r.ordinal()] = out;
    }

    /** The square a place is in, worked out the first time it is asked for. */
    public static synchronized Here at(ServerLevel level, int x, int z) {
        long key = (long) Math.floorDiv(x, CELL) << 32 | (Math.floorDiv(z, CELL) & 0xFFFFFFFFL);
        Here h = CACHE.getAndMoveToLast(key);
        if (h != null) return h;
        h = work(level, Math.floorDiv(x, CELL) * CELL + CELL / 2, Math.floorDiv(z, CELL) * CELL + CELL / 2);
        CACHE.put(key, h);
        while (CACHE.size() > KEEP) CACHE.removeFirst();
        return h;
    }

    public static synchronized void clear() {
        CACHE.clear();
    }

    private static double ground(ServerLevel level, int x, int z) {
        if (com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.ready()) {
            return com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.heightAt(x, z);
        }
        return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
                level.getChunkSource().randomState());
    }

    /** The world's prevailing wind, as an angle: the way the wind blows, long taken (see Atmosphere#steering). */
    private static double prevailing(ServerLevel level) {
        return SeedHash.rand01(SeedHash.hash(level.getSeed(), 0, 0, 0x5EE1L)) * Math.PI * 2;
    }

    private static Here work(ServerLevel level, int x, int z) {
        int sea = level.getSeaLevel();
        double h0 = ground(level, x, z);
        // The sea round it: how much of two rings, near and far, is under it.
        double near = 0, far = 0, farther = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            if (ground(level, x + (int) (Math.cos(a) * 500), z + (int) (Math.sin(a) * 500)) < sea - 2) near += 1 / 8.0;
            if (ground(level, x + (int) (Math.cos(a) * 1500), z + (int) (Math.sin(a) * 1500)) < sea - 2) far += 1 / 8.0;
            if (ground(level, x + (int) (Math.cos(a) * 3000), z + (int) (Math.sin(a) * 3000)) < sea - 2) farther += 1 / 8.0;
        }
        double coast = h0 < sea - 2 ? 1.0 : Math.min(1.0, Math.max(2.0 * near, Math.max(1.2 * far, 0.6 * farther)));
        // Upwind: the ground the wind comes over, and whether it comes off the sea.
        double w = prevailing(level);
        double ux = -Math.cos(w), uz = -Math.sin(w);
        double lowest = h0, ridge = Double.NEGATIVE_INFINITY;
        boolean offSea = false, toSea = false;
        for (int d : new int[]{300, 800, 1600}) {
            double hu = ground(level, x + (int) (ux * d), z + (int) (uz * d));
            lowest = Math.min(lowest, hu);
            ridge = Math.max(ridge, hu);
            offSea |= hu < sea - 2;
            if (ground(level, x - (int) (ux * d), z - (int) (uz * d)) < sea - 2) toSea = true;
        }
        double lift = Mth.clamp((h0 - lowest) / 150.0, 0.0, 1.5) * (offSea ? 1.0 : 0.5);
        double shadow = ridge - h0 > 40 ? Mth.clamp((ridge - h0) / 300.0, 0.0, 0.75) : 0.0;
        BlockPos at = new BlockPos(x, (int) Math.max(sea, h0) + 1, z);
        Holder<Biome> biome = com.jeladastudios.ftsgeology.util.Loaded.biome(level, at);
        var climate = biome.value().getModifiedClimateSettings();
        double d = Mth.clamp(climate.downfall(), 0, 1), t = climate.temperature();
        double hot = t > 0.9 && d > 0.75 ? 2.4 : 1.0;
        double cold = t < 0.15 ? 0.6 : 1.0;
        double mm = (250 + 1000 * d) * (0.55 + 0.55 * coast) * (1 + lift) * (1 - shadow) * hot * cold;
        mm = Mth.clamp(mm, 40, 4500);
        boolean tropical = SereneSeasons.tropical(biome);
        Regime r;
        if (t < 0.15) r = Regime.POLAR;
        else if (mm < 250) r = Regime.ARID;
        else if (tropical) r = Regime.TROPICAL;
        else if (t >= 0.8 && d >= 0.6 && coast >= 0.4 && toSea && !offSea) r = Regime.MONSOON;
        else if (lift >= 0.5 && coast >= 0.5 && d >= 0.5 && t < 0.95) r = Regime.BLACK_SEA;
        else if (t >= 0.5 && t < 0.95 && coast >= 0.4 && offSea && d <= 0.7) r = Regime.MEDITERRANEAN;
        else if (t < 0.6 && coast >= 0.4 && d >= 0.5) r = Regime.OCEANIC;
        else if (coast >= 0.6) r = Regime.OCEANIC;
        else r = Regime.CONTINENTAL;
        return new Here(mm, r, coast, lift, shadow, tropical);
    }

    /**
     * The share of the land under rain a place's year calls for, against a 1000 mm year's: in proportion up to that,
     * and slower past it (the wettest places have their rain harder, not only more often).
     */
    public static double share(ServerLevel level, int x, int z) {
        double mm = at(level, x, z).mm();
        return mm <= 1000 ? mm / 1000.0 : 1.0 + 0.5 * Math.log(mm / 1000.0);
    }

    /** This part of the year's share of the rain at a place: 1 without the seasons, averaging 1 over a year. */
    public static double season(ServerLevel level, int x, int z) {
        double phase = SereneSeasons.phase(level);
        if (Double.isNaN(phase)) return 1.0;
        Here h = at(level, x, z);
        if (h.regime() == Regime.TROPICAL) {
            int s = SereneSeasons.tropicalSeason(level);
            return s < 0 ? 1.0 : s >= 3 ? (s == 4 ? 1.9 : 1.5) : (s == 1 ? 0.2 : 0.4);
        }
        return seasonOf(h.regime(), phase);
    }

    /** A regime's share of the year's rain at a phase of the year: 1 on average over the year. */
    public static double seasonShare(Regime r, double phase) {
        return seasonOf(r, phase);
    }

    /** A regime's share at a phase of the year, smoothed between the middles of the sub-seasons. */
    static double seasonOf(Regime r, double phase) {
        double[] s = SHARE[r.ordinal()];
        double p = phase * 12.0 - 0.5;
        int i = Mth.floor(p);
        double f = p - i;
        f = f * f * (3 - 2 * f);
        return s[Math.floorMod(i, 12)] * (1 - f) + s[Math.floorMod(i + 1, 12)] * f;
    }

    /**
     * How much likelier a storm forming at a place is to be a shower against a front this time of year: half as likely
     * again in summer, under half in winter; 1 without the seasons.
     */
    public static double showery(ServerLevel level) {
        double phase = SereneSeasons.phase(level);
        if (Double.isNaN(phase)) return 1.0;
        // Midsummer at 0.42 of the year (mid-summer's middle), midwinter half a year on.
        return 1.0 + 0.55 * Math.cos((phase - 0.4167) * Math.PI * 2);
    }

    /** A line on a place, for the commands. */
    public static String describe(ServerLevel level, int x, int z) {
        Here h = at(level, x, z);
        double phase = SereneSeasons.phase(level);
        return String.format(Locale.ROOT, "%s, %.0f mm a year (coast %.2f, windward %.2f, rain shadow %.2f)%s",
                h.regime().name().toLowerCase(Locale.ROOT), h.mm(), h.coast(), h.lift(), h.shadow(),
                Double.isNaN(phase) ? ", no seasons" : String.format(Locale.ROOT, ", year %.2f: %.2f of its rain now", phase, season(level, x, z)));
    }
}
