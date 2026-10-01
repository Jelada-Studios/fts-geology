package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.util.SeedHash;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The air over the land: high and low pressure, and the wind between them.
 *
 * <p>Weather systems a few thousand blocks across drift over the world with its prevailing wind, which turns slowly as
 * it goes: lows, where the air rises and the rain comes -- fronts and showers gather in them (see {@link Storms}) -- and
 * highs, where it sinks and the sky stays clear. A system builds over its first days and fills in over its last. The
 * pressure at a place is the day's mean with the systems' own added, a low some ten to thirty hectopascals down, a high
 * up; the wind blows round them, as it does on Earth, turning about a low with the low on its left and out of a high,
 * and the harder the steeper the pressure falls. A place's temperature comes from its biome, its height, the hour and
 * the weather; its humidity from its biome, the region's wet or dry spell, the rain and the night.</p>
 *
 * <p>All of it is worked out, not simulated: the systems are a few records, and anything at a place -- now, or a day
 * ahead for a forecast -- is a sum over them. Off where {@link Storms#on} is off.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Atmosphere {

    private Atmosphere() {}

    /** The mean pressure at the sea, hectopascals. */
    public static final double MEAN = 1013.25;

    enum Kind { LOW, HIGH }

    static final class Cell {
        Kind kind = Kind.LOW;
        double x, z, vx, vz, radius, depth;
        long born, dies;
        int seed;

        /** How deep it is now, hectopascals (below zero a low): it builds over its first fifth and fills over its last third. */
        double anomaly(long now) {
            double span = Math.max(1, dies - born), t = (now - born) / span;
            double life = t < 0 ? 0 : t < 0.2 ? t / 0.2 : t > 0.67 ? Math.max(0, (1 - t) / 0.33) : 1;
            return depth * life;
        }

        /** Where it will be at a time, drifting as it does now. */
        double xAt(long when) {
            return x + vx * (when - lastMove);
        }

        double zAt(long when) {
            return z + vz * (when - lastMove);
        }

        long lastMove;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Kind", kind.name());
            t.putDouble("X", x);
            t.putDouble("Z", z);
            t.putDouble("Vx", vx);
            t.putDouble("Vz", vz);
            t.putDouble("R", radius);
            t.putDouble("Depth", depth);
            t.putLong("Born", born);
            t.putLong("Dies", dies);
            t.putLong("Moved", lastMove);
            t.putInt("Seed", seed);
            return t;
        }

        static Cell load(CompoundTag t) {
            Cell c = new Cell();
            try {
                c.kind = Kind.valueOf(t.getString("Kind"));
            } catch (IllegalArgumentException e) {
                c.kind = Kind.LOW;
            }
            c.x = t.getDouble("X");
            c.z = t.getDouble("Z");
            c.vx = t.getDouble("Vx");
            c.vz = t.getDouble("Vz");
            c.radius = t.getDouble("R");
            c.depth = t.getDouble("Depth");
            c.born = t.getLong("Born");
            c.dies = t.getLong("Dies");
            c.lastMove = t.getLong("Moved");
            c.seed = t.getInt("Seed");
            return c;
        }
    }

    static final class Store extends SavedData {
        final List<Cell> all = new ArrayList<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            for (Tag t : tag.getList("Cells", Tag.TAG_COMPOUND)) s.all.add(Cell.load((CompoundTag) t));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Cell c : all) list.add(c.save());
            tag.put("Cells", list);
            return tag;
        }
    }

    static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store::load, Store::new, "fts_geology_atmosphere");
    }

    /** The systems, for the server thread's reads; empty where none have formed. */
    static List<Cell> cells(ServerLevel level) {
        Store st = level.getDataStorage().get(Store::load, "fts_geology_atmosphere");
        return st == null ? List.of() : st.all;
    }

    // === The prevailing wind ====================================================

    /** The scale the prevailing wind turns over, blocks, and the days it takes to. */
    private static final double FLOW_SCALE = 24000, FLOW_DAYS = 8;

    /**
     * The prevailing wind that carries the systems, blocks a tick: a world's own direction, turning slowly across the
     * world and over the days, 1.5 to 3.5 blocks a second.
     */
    static double[] steering(ServerLevel level, double x, double z, long when) {
        long seed = level.getSeed();
        double base = SeedHash.rand01(SeedHash.hash(seed, 0, 0, 0x5EE1L)) * Math.PI * 2;
        double t = when / 24000.0 / FLOW_DAYS;
        double turn = (smoothNoise(seed, x / FLOW_SCALE, z / FLOW_SCALE, t, 0xA1L) - 0.5) * 2.2;
        double speed = 1.5 + 2.0 * smoothNoise(seed, x / FLOW_SCALE, z / FLOW_SCALE, t, 0xA2L);
        double a = base + turn;
        return new double[]{Math.cos(a) * speed / 20.0, Math.sin(a) * speed / 20.0};
    }

    /** Value noise over space and time, 0 to 1, smooth. */
    private static double smoothNoise(long seed, double x, double z, double t, long salt) {
        int x0 = Mth.floor(x), z0 = Mth.floor(z), t0 = Mth.floor(t);
        double fx = fade(x - x0), fz = fade(z - z0), ft = fade(t - t0);
        double out = 0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dz = (i >> 1) & 1, dt = i >> 2;
            double w = (dx == 0 ? 1 - fx : fx) * (dz == 0 ? 1 - fz : fz) * (dt == 0 ? 1 - ft : ft);
            out += w * SeedHash.rand01(SeedHash.hash(seed, x0 + dx, z0 + dz, salt * 1_000_003L + t0 + dt));
        }
        return out;
    }

    private static double fade(double f) {
        return f * f * (3 - 2 * f);
    }

    // === Pressure and wind ========================================================

    /** The pressure at a place, hectopascals at sea level, now. */
    public static double pressure(ServerLevel level, double x, double z) {
        return pressure(level, x, z, level.getGameTime());
    }

    /** The pressure at a place at a time: the systems where they will be then, as deep as they will be. */
    public static double pressure(ServerLevel level, double x, double z, long when) {
        double p = MEAN;
        for (Cell c : cells(level)) {
            double dx = x - c.xAt(when), dz = z - c.zAt(when), s = c.radius * 0.5;
            p += c.anomaly(when) * Math.exp(-(dx * dx + dz * dz) / (2 * s * s));
        }
        return p;
    }

    /** How the pressure falls away at a place, hectopascals a thousand blocks, towards +x and +z. */
    static double[] gradient(ServerLevel level, double x, double z) {
        return gradient(level, x, z, level.getGameTime());
    }

    /** How the pressure will fall away at a place at a time, the systems where they will be then. */
    static double[] gradient(ServerLevel level, double x, double z, long now) {
        double gx = 0, gz = 0;
        for (Cell c : cells(level)) {
            double dx = x - c.xAt(now), dz = z - c.zAt(now), s = c.radius * 0.5;
            double e = c.anomaly(now) * Math.exp(-(dx * dx + dz * dz) / (2 * s * s)) / (s * s);
            gx -= e * dx;
            gz -= e * dz;
        }
        return new double[]{gx * 1000, gz * 1000};
    }

    /** Blocks a second of wind for a hectopascal a thousand blocks of pressure falling away. */
    private static final double WIND_PER_GRADIENT = 2.4;

    /**
     * The wind at a place, blocks a tick: round the systems as on Earth's northern half -- about a low with the low on
     * its left, out of a high -- turned a little in towards the low by the ground's drag, on the prevailing wind's
     * back, and at most fifteen blocks a second.
     */
    public static double[] wind(ServerLevel level, double x, double z) {
        return windAt(level, x, z, level.getGameTime());
    }

    /** A wind pinned over a place for a game day, for trying the weather out; not kept over a restart. */
    private static double pinX, pinZ, pinRadius, pinWx, pinWz;
    private static long pinUntil = Long.MIN_VALUE;

    /** Pins the wind within {@code radius} of a place to this many blocks a second towards this bearing, or frees it at 0. */
    public static void pinWind(ServerLevel level, double x, double z, double radius, double blocksPerSecond, double towardDegrees) {
        pinX = x;
        pinZ = z;
        pinRadius = radius;
        pinWx = blocksPerSecond / 20.0 * Math.cos(Math.toRadians(towardDegrees));
        pinWz = blocksPerSecond / 20.0 * Math.sin(Math.toRadians(towardDegrees));
        pinUntil = blocksPerSecond > 0 ? level.getGameTime() + 24000 : Long.MIN_VALUE;
    }

    /** The wind at a place at a time, blocks a tick, the systems where they will be then: for a forecast. */
    public static double[] windAt(ServerLevel level, double x, double z, long when) {
        if (when < pinUntil && Math.hypot(x - pinX, z - pinZ) < pinRadius) return new double[]{pinWx, pinWz};
        double[] g = gradient(level, x, z, when);
        // North is -z: the wind runs along the isobars, low pressure on its left.
        double ux = WIND_PER_GRADIENT * g[1], uz = -WIND_PER_GRADIENT * g[0];
        // The ground's drag turns it some twenty degrees to its left, in towards the low.
        double cos = Math.cos(Math.toRadians(20)), sin = Math.sin(Math.toRadians(20));
        double wx = ux * cos + uz * sin, wz = uz * cos - ux * sin;
        double[] s = steering(level, x, z, when);
        wx = wx / 20.0 + s[0] * 0.4;
        wz = wz / 20.0 + s[1] * 0.4;
        double speed = Math.hypot(wx, wz) * 20;
        if (speed > 15) {
            wx *= 15 / speed;
            wz *= 15 / speed;
        }
        return new double[]{wx, wz};
    }

    /**
     * How much more the land round a place wants rain than its climate alone would: in a low up to three times, in a
     * high as little as a sixth. It averages about one, a place having lows and highs over it by turns.
     */
    public static double rainFactor(ServerLevel level, double x, double z) {
        return rainFactor(level, x, z, level.getGameTime());
    }

    /** The same at a time ahead, for a forecast. */
    public static double rainFactor(ServerLevel level, double x, double z, long when) {
        double a = pressure(level, x, z, when) - MEAN;
        return a < 0 ? Math.min(3.0, 1 + -a / 10.0) : Math.max(0.15, 1 - a / 14.0);
    }

    // === Temperature and humidity ================================================

    /**
     * The air's temperature at a place, degrees Celsius: the biome's climate, less 6.5 a kilometre of height over the
     * sea, cooler at night and under rain, warmer in a high's sun.
     */
    public static double temperature(ServerLevel level, BlockPos pos) {
        return temperatureAt(level, pos, 0, Storms.intensityAt(level, pos.getX(), pos.getZ()));
    }

    /** The temperature at a place {@code ahead} ticks from now, with {@code rain} falling then: for a forecast. */
    public static double temperatureAt(ServerLevel level, BlockPos pos, long ahead, double rain) {
        float t = level.getBiome(pos).value().getBaseTemperature();
        double c = t <= 1 ? -5 + 25 * t : 20 + 10 * (t - 1);
        double metres = Math.max(0, Meteorology.altitude(level, pos));
        c -= 6.5 * metres / 1000.0;
        long day = (level.getDayTime() + ahead) % 24000;
        // The day's round: warmest early in the afternoon (8000 is two o'clock), coldest in the small hours.
        c += 4.5 * Math.cos((day - 8000) / 24000.0 * Math.PI * 2);
        double a = pressure(level, pos.getX(), pos.getZ(), level.getGameTime() + ahead) - MEAN;
        c += Mth.clamp(a / 8.0, -1.5, 2.0);
        c -= 3.0 * rain;
        return c;
    }

    /**
     * The air's relative humidity at a place, 0 to 1: the biome's wetness and the region's spell, wetter in a low,
     * at night and under rain, drier in a high's sun.
     */
    public static double humidity(ServerLevel level, BlockPos pos) {
        var climate = level.getBiome(pos).value().getModifiedClimateSettings();
        double h = 0.3 + 0.4 * Mth.clamp(climate.downfall(), 0, 1) + 0.2 * (Storms.wetness(level, pos.getX(), pos.getZ()) - 0.5);
        double a = pressure(level, pos.getX(), pos.getZ()) - MEAN;
        h -= Mth.clamp(a / 60.0, -0.15, 0.2);
        long day = level.getDayTime() % 24000;
        // Dampest before dawn, driest in the afternoon.
        h += 0.12 * Math.cos((day - 22000) / 24000.0 * Math.PI * 2);
        // Under rain the air is near saturation.
        float rain = Storms.intensityAt(level, pos.getX(), pos.getZ());
        if (rain >= Storms.WET) h = Math.max(h, 0.85 + 0.15 * rain);
        return Mth.clamp(h, 0.05, 1.0);
    }

    // === Every second ======================================================

    /** How far from the players the systems are kept, and how many of each round them. */
    private static final double KEEP = 16000, NEAR = 11000;
    private static final int LOWS = 3, HIGHS = 3, MOST = 40;
    private static long formed;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || !Storms.on(level)) return;
        long now = level.getGameTime();
        if (now % 20 != 0) return;
        Store st = store(level);
        List<ServerPlayer> players = level.players();
        st.all.removeIf(c -> {
            if (now >= c.dies) return true;
            for (ServerPlayer p : players) {
                if (Math.hypot(p.getX() - c.x, p.getZ() - c.z) < KEEP + c.radius) return false;
            }
            return !players.isEmpty();
        });
        for (Cell c : st.all) {
            c.x = c.xAt(now);
            c.z = c.zAt(now);
            c.lastMove = now;
            // Carried by the prevailing wind where it is, with a little drift of its own.
            double[] s = steering(level, c.x, c.z, now);
            double own = (SeedHash.rand01(c.seed) - 0.5) * 0.3;
            c.vx = s[0] * (1 + own);
            c.vz = s[1] * (1 - own);
        }
        if (now % 200 == 0) {
            for (ServerPlayer p : players) keepAround(level, st, p.getX(), p.getZ(), now);
        }
        st.setDirty();
    }

    /**
     * Keeps a few lows and highs within reach of a place: a new one forms upwind, to drift over. The region's wet spell
     * makes its lows deeper and more of them lows; a dry one, highs. The first time round a place, they are strewn
     * about it and part way through their lives, so the weather is under way from the start.
     */
    private static void keepAround(ServerLevel level, Store st, double x, double z, long now) {
        int lows = 0, highs = 0;
        for (Cell c : st.all) {
            if (Math.hypot(c.x - x, c.z - z) > NEAR) continue;
            if (c.kind == Kind.LOW) lows++;
            else highs++;
        }
        boolean first = lows + highs == 0;
        double wet = Storms.wetness(level, x, z);
        int wantLows = LOWS + (wet > 0.65 ? 1 : 0) - (wet < 0.3 ? 1 : 0), wantHighs = HIGHS + (wet < 0.3 ? 1 : 0) - (wet > 0.65 ? 1 : 0);
        var rnd = level.random;
        while ((lows < wantLows || highs < wantHighs) && st.all.size() < MOST) {
            Cell c = new Cell();
            // Whichever is further short of what the spell wants.
            boolean low = lows < wantLows && (highs >= wantHighs || (double) lows / wantLows <= (double) highs / wantHighs);
            c.kind = low ? Kind.LOW : Kind.HIGH;
            if (c.kind == Kind.LOW) {
                lows++;
                c.radius = 2500 + rnd.nextDouble() * 3500;
                c.depth = -(8 + rnd.nextDouble() * 14) * (0.8 + 0.6 * wet);
            } else {
                highs++;
                c.radius = 4000 + rnd.nextDouble() * 4000;
                c.depth = (6 + rnd.nextDouble() * 12) * (1.2 - 0.5 * wet);
            }
            long life = 72000 + rnd.nextInt(120000);
            c.born = first ? now - (long) (life * rnd.nextDouble() * 0.6) : now;
            c.dies = c.born + life;
            c.seed = rnd.nextInt(1 << 20);
            double[] s = steering(level, x, z, now);
            double speed = Math.max(1e-4, Math.hypot(s[0], s[1]));
            if (first) {
                double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * NEAR;
                c.x = x + Math.cos(a) * d;
                c.z = z + Math.sin(a) * d;
            } else {
                // Upwind, where it will drift over the place in a day or two, a little to one side.
                double back = NEAR * (0.7 + 0.3 * rnd.nextDouble()), side = (rnd.nextDouble() - 0.5) * NEAR;
                c.x = x - s[0] / speed * back - s[1] / speed * side;
                c.z = z - s[1] / speed * back + s[0] / speed * side;
            }
            c.vx = s[0];
            c.vz = s[1];
            c.lastMove = now;
            st.all.add(c);
            formed++;
            com.jeladastudios.ftsgeology.util.Diagnostics.info("atmosphere: a {} forms {} blocks from {}, {} {}, {} across, {} game days",
                    c.kind == Kind.LOW ? "low" : "high", (int) Math.hypot(c.x - x, c.z - z),
                    (int) x + " " + (int) z, String.format(Locale.ROOT, "%+.0f", c.depth), "hPa", (int) (2 * c.radius),
                    String.format(Locale.ROOT, "%.1f", (c.dies - c.born) / 24000.0));
        }
    }

    /** The systems within reach of a place, for {@code /geology storms}. */
    public static List<String> describe(ServerLevel level, double x, double z) {
        List<String> out = new ArrayList<>();
        long now = level.getGameTime();
        double[] w = wind(level, x, z);
        double speed = Math.hypot(w[0], w[1]) * 20;
        out.add(String.format(Locale.ROOT, "pressure %.1f hPa; wind %.1f blocks/s (%.0f kn) from %s",
                pressure(level, x, z), speed, speed * 1.944, compass(Math.atan2(-w[1], -w[0]))));
        for (Cell c : cells(level)) {
            double d = Math.hypot(c.x - x, c.z - z);
            if (d > NEAR * 1.5) continue;
            out.add(String.format(Locale.ROOT, "%s %+.0f hPa now, %d blocks off to the %s, %d across, %.1f game days left",
                    c.kind == Kind.LOW ? "low" : "high", c.anomaly(now), (int) d, compass(Math.atan2(c.z - z, c.x - x)),
                    (int) (2 * c.radius), (c.dies - now) / 24000.0));
        }
        return out;
    }

    /** A bearing as a point of the compass: the angle from +x towards +z. */
    public static String compass(double a) {
        String[] points = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        int i = Math.floorMod((int) Math.round(a / (Math.PI / 4)), 8);
        return points[i];
    }

    public static String summary() {
        return "atmosphere: " + formed + " systems formed";
    }

    public static boolean any() {
        return formed > 0;
    }

    public static void clear() {
        formed = 0;
    }
}
