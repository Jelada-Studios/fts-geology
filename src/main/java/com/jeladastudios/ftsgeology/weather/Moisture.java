package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.GeysersMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * The water in the air, worked out rather than read off the biome. Round each player the land is cut into squares 512
 * blocks across, and each holds the air's water vapour (grams to a kilogram of air). Every few seconds:
 * <ul>
 *   <li>the sea, lakes and rivers, wet ground and plants give water to air drier than the saturation of its warmth
 *   (Clausius-Clapeyron: a few grams a kilogram at freezing, twenty-odd in the tropics), slowly over dry land;</li>
 *   <li>the wind carries the air on, the vapour with it (the air off a sea is damp, off a desert dry);</li>
 *   <li>air carried past what its warmth can hold gives the excess up, and under rain the air is near saturation;</li>
 *   <li>and all of it eases back over days to the climate the land has (so nothing drifts away for ever).</li>
 * </ul>
 * <p>The air's warmth comes from {@link Atmosphere#temperature}, with the day's round in it: the same vapour that leaves
 * the afternoon air half-saturated saturates it before dawn, and where it does near the ground in still air -- in the
 * low ground, by water -- a fog lies until the morning sun warms it off. The weather instruments read the humidity from
 * here. The rain itself is still the storms' (the vapour does not yet make it).</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Moisture {

    private Moisture() {}

    /** The squares' size, how many round a player each way, and the ticks between steps. */
    private static final int CELL = 512, REACH = 4, STEP = 100;
    /** Steps over which the sea brings air to saturation, and over which the air eases back to the land's climate. */
    private static final double SEA_STEPS = 120, CLIMATE_STEPS = 4320;
    /** Steps over which the sea and the ground give the column back its water, and what a downpour takes a step. */
    private static final double COLUMN_STEPS = 360, RAIN_TAKES = 0.004;
    /** Squares kept at most; the farthest from everyone go first. */
    private static final int MOST = 1500;

    private static final class Cell {
        double q, water, ground;
        /** The biome at its middle: its warmth and wetness, and whether rain falls there. Read once. */
        float warmth, downfall;
        boolean precipitates;
        /** The water the column of air over the square holds against an ordinary day's: rain takes it, the sea and wet
         *  ground give it back. */
        double column = 1.0;
        long seen;
    }

    private static final Long2ObjectOpenHashMap<Cell> CELLS = new Long2ObjectOpenHashMap<>();
    private static long steps;

    /** Saturation vapour, grams to a kilogram of air near sea level, at a temperature in degrees C (Magnus, Bolton). */
    public static double saturation(double celsius) {
        return 3.79 * Math.exp(17.67 * celsius / (celsius + 243.5));
    }

    /** The dew point of air holding {@code q} grams a kilogram, degrees C. */
    public static double dewPoint(double q) {
        double l = Math.log(Math.max(1e-4, q) / 3.79);
        return 243.5 * l / (17.67 - l);
    }

    private static long key(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    private static double ground(ServerLevel level, int x, int z) {
        if (level.hasChunkAt(new BlockPos(x, 0, z))) return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.ready()) {
            return com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.heightAt(x, z);
        }
        return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
                level.getChunkSource().randomState());
    }

    /** The surface of a square's middle, for its air's warmth. */
    private static BlockPos middle(ServerLevel level, int cx, int cz, Cell c) {
        int x = cx * CELL + CELL / 2, z = cz * CELL + CELL / 2;
        return new BlockPos(x, (int) Math.max(level.getSeaLevel(), c.ground) + 1, z);
    }

    /** The vapour the land's climate gives a square's air: drier air over dry land, wetter by water. */
    private static double climate(ServerLevel level, BlockPos at, Cell c, double t) {
        double rh = 0.40 + 0.30 * Mth.clamp(c.downfall, 0, 1) + 0.20 * c.water;
        // The day's mean warmth: the round taken out.
        double mean = t - 4.5 * Math.cos((level.getDayTime() % 24000 - 8000) / 24000.0 * Math.PI * 2);
        return rh * saturation(mean);
    }

    /** The air's warmth over a square's middle, from its biome as read once. */
    private static double warm(ServerLevel level, BlockPos at, Cell c) {
        return Atmosphere.temperatureAt(level, at, 0, Storms.intensityAt(level, at.getX(), at.getZ()), c.warmth);
    }

    private static Cell cell(ServerLevel level, int cx, int cz) {
        long k = key(cx, cz);
        Cell c = CELLS.get(k);
        if (c != null) return c;
        c = new Cell();
        int x = cx * CELL + CELL / 2, z = cz * CELL + CELL / 2;
        c.ground = ground(level, x, z);
        RainClimate.Here here = RainClimate.at(level, x, z);
        c.water = c.ground < level.getSeaLevel() - 2 ? 1.0 : 0.5 * here.coast();
        BlockPos mid = middle(level, cx, cz, c);
        var biome = com.jeladastudios.ftsgeology.util.Loaded.biome(level, mid).value();
        c.warmth = biome.getBaseTemperature();
        c.downfall = biome.getModifiedClimateSettings().downfall();
        c.precipitates = biome.hasPrecipitation();
        c.q = climate(level, mid, c, warm(level, mid, c));
        c.seen = level.getGameTime();
        CELLS.put(k, c);
        return c;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || !Storms.on(level) || level.players().isEmpty()) return;
        long now = level.getGameTime();
        if (now % STEP != 0) return;
        step(level, now);
    }

    private static void step(ServerLevel level, long now) {
        steps++;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet live = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (ServerPlayer p : level.players()) {
            int pcx = Math.floorDiv(p.getBlockX(), CELL), pcz = Math.floorDiv(p.getBlockZ(), CELL);
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) live.add(key(pcx + dx, pcz + dz));
            }
        }
        // The new vapour of each square worked out from the old, then set: the wind carries the old.
        Long2ObjectOpenHashMap<double[]> next = new Long2ObjectOpenHashMap<>();
        for (long k : live) {
            int cx = (int) (k >> 32), cz = (int) k;
            Cell c = cell(level, cx, cz);
            BlockPos at = middle(level, cx, cz, c);
            double t = warm(level, at, c);
            double qs = saturation(t);
            // Carried: the air here now came from upwind.
            double[] w = Atmosphere.wind(level, at.getX(), at.getZ());
            double ux = at.getX() - w[0] * STEP, uz = at.getZ() - w[1] * STEP;
            double q = sample(level, ux, uz);
            // Given up by the sea, the wet ground and the plants, into air drier than the sea leaves air (some 85%).
            double wet = c.water + (1 - c.water) * (0.15 + 0.6 * soil(level, at));
            q += wet / SEA_STEPS * Math.max(0.0, 0.85 * qs - q);
            // Past saturation it condenses; under rain the air is near saturation.
            // Rain only where it falls as rain or snow: a desert under a storm stays dry.
            float rain = c.precipitates ? Storms.intensityAt(level, at.getX(), at.getZ()) : 0f;
            if (rain >= Storms.WET) q = Math.max(q, (0.85 + 0.12 * rain) * qs);
            q = Math.min(q, qs * 1.02);
            // Back towards the land's climate over days.
            q += (climate(level, at, c, t) - q) / CLIMATE_STEPS;
            // The column's water: carried with the wind, given back by the sea and the wet ground towards what they keep
            // in the air, taken by the rain that falls.
            double col = sampleColumn(level, ux, uz);
            double keeps = 1.2 * c.water + (1 - c.water) * (0.7 + 0.6 * soil(level, at));
            col += (keeps - col) / COLUMN_STEPS;
            col -= rain * RAIN_TAKES;
            next.put(k, new double[]{q, Mth.clamp(col, 0.2, 1.6)});
        }
        for (var e : next.long2ObjectEntrySet()) {
            Cell c = CELLS.get(e.getLongKey());
            c.q = e.getValue()[0];
            c.column = e.getValue()[1];
            c.seen = now;
        }
        if (CELLS.size() > MOST) CELLS.long2ObjectEntrySet().removeIf(e -> !live.contains(e.getLongKey()) && now - e.getValue().seen > 6000);
    }

    /** How wet the ground of a square's middle is, 0 to 1, from the water in its soil; a half where it is not known. */
    private static double soil(ServerLevel level, BlockPos at) {
        var r = com.jeladastudios.ftsgeology.hydrology.SoilWater.at(level, at.getX(), at.getZ());
        if (r == null) return 0.5;
        return Mth.clamp(0.7 * r.rootSat() + 0.3 * r.topSat() + (r.pond() > 1 ? 0.3 : 0.0), 0, 1);
    }

    /** The vapour at a place, blended between the squares round it; a square not kept yet is made. */
    private static double sample(ServerLevel level, double x, double z) {
        double fx = x / CELL - 0.5, fz = z / CELL - 0.5;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        double a = vapour(level, x0, z0), b = vapour(level, x0 + 1, z0), c = vapour(level, x0, z0 + 1), d = vapour(level, x0 + 1, z0 + 1);
        return Mth.lerp(tz, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
    }

    /** The column's water at a place, blended between the squares round it. */
    private static double sampleColumn(ServerLevel level, double x, double z) {
        double fx = x / CELL - 0.5, fz = z / CELL - 0.5;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        double a = cell(level, x0, z0).column, b = cell(level, x0 + 1, z0).column, c = cell(level, x0, z0 + 1).column,
                d = cell(level, x0 + 1, z0 + 1).column;
        return Mth.lerp(tz, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
    }

    /**
     * How much water the air over a place holds against an ordinary day's, 0.2 to 1.6: low after a long rain has wrung
     * it out or over ground a drought has dried, high off a warm sea. 1 where it is not worked out. The storms form by it.
     */
    public static double column(ServerLevel level, int x, int z) {
        if (!kept(level, new BlockPos(x, 0, z))) return 1.0;
        return sampleColumn(level, x, z);
    }

    private static double vapour(ServerLevel level, int cx, int cz) {
        Cell c = CELLS.get(key(cx, cz));
        return c != null ? c.q : cell(level, cx, cz).q;
    }

    /** Whether the air's water is worked out at a place now: near a player, with the rain regional. */
    private static boolean kept(Level level, BlockPos pos) {
        return level instanceof ServerLevel sl && Storms.on(sl) && CELLS.containsKey(key(Math.floorDiv(pos.getX(), CELL), Math.floorDiv(pos.getZ(), CELL)));
    }

    /** The day's mean warmth at a place, degrees C: the day's round taken out. */
    private static double meanWarmth(ServerLevel level, BlockPos p) {
        return Atmosphere.temperature(level, p) - 4.5 * Math.cos((level.getDayTime() % 24000 - 8000) / 24000.0 * Math.PI * 2);
    }

    /**
     * How full the air of the square round a place is, against what the day's mean warmth of the square's middle
     * holds: the vapour carried from square to square, read as a share. The land's warmth here is patchy -- a cold
     * shore beside a warm sea -- and a share of saturation carries to a place where grams would not. NaN where not
     * worked out.
     */
    private static double fullness(ServerLevel level, BlockPos pos) {
        if (!kept(level, pos)) return Double.NaN;
        int cx = Math.floorDiv(pos.getX(), CELL), cz = Math.floorDiv(pos.getZ(), CELL);
        Cell c = CELLS.get(key(cx, cz));
        return sample(level, pos.getX(), pos.getZ()) / saturation(meanWarmth(level, middle(level, cx, cz, c)));
    }

    /** The relative humidity at a place, 0 to 1, with the warmth there now; NaN where not worked out. */
    public static double humidity(ServerLevel level, BlockPos pos) {
        double f = fullness(level, pos);
        if (Double.isNaN(f)) return Double.NaN;
        return Mth.clamp(f * saturation(meanWarmth(level, pos)) / saturation(Atmosphere.temperature(level, pos)), 0.02, 1.0);
    }

    /** The air's vapour at a place, grams a kilogram; NaN where it is not worked out. */
    public static double vapour(ServerLevel level, BlockPos pos) {
        double rh = humidity(level, pos);
        return Double.isNaN(rh) ? Double.NaN : rh * saturation(Atmosphere.temperature(level, pos));
    }

    /**
     * How thick a fog lies at a place, 0 to 1: where the air near the ground is saturated, the wind is light and no rain
     * falls -- most in the low ground and by water, before dawn; the sun burns it off.
     */
    public static float fog(ServerLevel level, BlockPos pos) {
        double full = fullness(level, pos);
        if (Double.isNaN(full)) return 0f;
        if (Storms.intensityAt(level, pos.getX(), pos.getZ()) >= Storms.WET) return 0f;
        // On a clear still night the ground loses its warmth to the sky, and the cold air drains down into the low
        // ground and pools there: the air by the ground, the more so in a hollow, is colder than the air over the land.
        double night = Mth.clamp(-Math.cos((level.getDayTime() % 24000 - 8000) / 24000.0 * Math.PI * 2), 0, 1);
        Cell c = CELLS.get(key(Math.floorDiv(pos.getX(), CELL), Math.floorDiv(pos.getZ(), CELL)));
        double below = c == null ? 0 : Mth.clamp((c.ground - pos.getY()) / 20.0, 0, 1);
        double t = Atmosphere.temperature(level, pos) - night * (1.0 + 3.0 * below);
        double rh = full * saturation(meanWarmth(level, pos)) / saturation(t);
        double f = Mth.clamp((rh - 0.92) / 0.08, 0, 1);
        if (f <= 0) return 0f;
        double[] w = Atmosphere.wind(level, pos.getX(), pos.getZ());
        double speed = Math.hypot(w[0], w[1]) * 20.0;
        f *= Mth.clamp((6.0 - speed) / 3.0, 0, 1);
        // Low ground holds it; high up it thins.
        double above = pos.getY() - level.getSeaLevel();
        f *= Mth.clamp(1.0 - (above - 40.0) / 120.0, 0.25, 1.0);
        return (float) f;
    }

    /**
     * For trying it out: the air round a place (the squares within {@code radius} squares) given the vapour that is
     * {@code rh} of what its mean warmth holds.
     */
    public static int set(ServerLevel level, BlockPos pos, double rh, int radius) {
        int cx = Math.floorDiv(pos.getX(), CELL), cz = Math.floorDiv(pos.getZ(), CELL), n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Cell c = cell(level, cx + dx, cz + dz);
                BlockPos at = middle(level, cx + dx, cz + dz, c);
                double mean = Atmosphere.temperature(level, at) - 4.5 * Math.cos((level.getDayTime() % 24000 - 8000) / 24000.0 * Math.PI * 2);
                c.q = rh * saturation(mean);
                n++;
            }
        }
        return n;
    }

    /** A line on the air at a place, for the commands. */
    public static String describe(ServerLevel level, BlockPos pos) {
        double q = vapour(level, pos);
        if (Double.isNaN(q)) return "the air's water is not worked out here";
        double t = Atmosphere.temperature(level, pos);
        return String.format(Locale.ROOT, "air: %.1f g/kg of vapour, %.0f%% humidity at %.1f C, dew point %.1f C, fog %.2f; the column holds %.2f of its water",
                q, 100 * humidity(level, pos), t, dewPoint(q), fog(level, pos), column(level, pos.getX(), pos.getZ()));
    }

    public static String summary() {
        return String.format(Locale.ROOT, "air's water: %d squares kept, %d steps", CELLS.size(), steps);
    }

    public static void clear() {
        CELLS.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        clear();
        RainClimate.clear();
    }
}
