package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.network.LocalWeatherPacket;
import com.jeladastudios.ftsgeology.network.ModNetwork;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rain that falls somewhere, not everywhere.
 *
 * <p>Vanilla has one weather for the whole world: it rains on every field at once and stops everywhere at once. Here the
 * rain comes in storms -- cells a few hundred to a few thousand blocks across that drift with the wind, grow, rain and
 * die -- and it rains only under them. What storms a region gets depends on its weather, which changes slowly: each
 * stretch of a few thousand blocks goes through wet spells and dry spells lasting days to weeks, and in a wet spell a
 * slow, broad rain can hang over the land for a week or two. Wet biomes get more of it than dry ones, deserts little.
 * Showers and thunderstorms are small and quick, fronts wide and long; only the heaviest showers carry thunder. Where the
 * wind drives the air up a mountainside the rain is heavier, and past the crest, in the rain shadow, lighter.</p>
 *
 * <p>The game is told the weather as locally as it can be. It "rains" in the world while any storm is out, so what reads
 * the world's one flag sees rain; but rain falls ({@code isRainingAt}), snow settles, cauldrons fill and lightning
 * strikes only under a storm, and each player's sky, rain and thunder are drawn from the storm over them (see
 * {@code ClientWeather}), so a shader reads the rain where the player is. {@code /weather rain|thunder} brings a storm
 * over the players, {@code /weather clear} and sleeping through a night clear the sky over them. Not in
 * TerraFirmaCraft, which keeps its own climate; {@code regionalRain} turns it off.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Storms {

    private Storms() {}

    /** Rain lighter than this is none; this and over is heavy. */
    public static final float WET = 0.05f, HEAVY = 0.5f;

    enum Kind { SHOWER, FRONT, SPELL }

    static final class Storm {
        double x, z, vx, vz, radius, peak;
        long born, dies;
        boolean thunder;
        Kind kind = Kind.FRONT;
        int seed;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("X", x);
            t.putDouble("Z", z);
            t.putDouble("Vx", vx);
            t.putDouble("Vz", vz);
            t.putDouble("R", radius);
            t.putDouble("Peak", peak);
            t.putLong("Born", born);
            t.putLong("Dies", dies);
            t.putBoolean("Thunder", thunder);
            t.putString("Kind", kind.name());
            t.putInt("Seed", seed);
            return t;
        }

        static Storm load(CompoundTag t) {
            Storm s = new Storm();
            s.x = t.getDouble("X");
            s.z = t.getDouble("Z");
            s.vx = t.getDouble("Vx");
            s.vz = t.getDouble("Vz");
            s.radius = t.getDouble("R");
            s.peak = t.getDouble("Peak");
            s.born = t.getLong("Born");
            s.dies = t.getLong("Dies");
            s.thunder = t.getBoolean("Thunder");
            try {
                s.kind = Kind.valueOf(t.getString("Kind"));
            } catch (IllegalArgumentException e) {
                s.kind = Kind.FRONT;
            }
            s.seed = t.getInt("Seed");
            return s;
        }

        /** How much it rains under it, 0 to 1, at a point: a plateau inside, a soft edge, bands that move with it. */
        double at(double px, double pz, long now) {
            double dx = px - x, dz = pz - z;
            double d2 = dx * dx + dz * dz;
            if (d2 >= radius * radius) return 0;
            double edge = Mth.clamp((1.0 - Math.sqrt(d2) / radius) * 1.8, 0.0, 1.0);
            edge = edge * edge * (3 - 2 * edge);
            double bands = 0.7 + 0.3 * ValueNoise.noise((int) dx + seed, (int) dz - seed, radius * 0.35);
            return peak * life(now) * edge * bands;
        }

        /**
         * Whether it rains at a point, or will: where it will stand halfway through its life if it is younger, at its
         * full strength, until it starts to die away. How the land's rain is reckoned when storms are formed.
         */
        boolean reaches(double px, double pz, long now) {
            double span = Math.max(1, dies - born);
            if (now - born > 0.8 * span) return false;
            double ahead = Math.max(0, born + span * 0.5 - now);
            double dx = px - (x + vx * ahead), dz = pz - (z + vz * ahead);
            double d2 = dx * dx + dz * dz;
            if (d2 >= radius * radius) return false;
            double edge = Mth.clamp((1.0 - Math.sqrt(d2) / radius) * 1.8, 0.0, 1.0);
            edge = edge * edge * (3 - 2 * edge);
            return peak * edge * 0.85 >= WET;
        }

        /** Its strength through its life: it builds over the first tenth, and dies away over the last fifth. */
        double life(long now) {
            double span = Math.max(1, dies - born), t = (now - born) / span;
            if (t < 0.1) return Math.max(0, t / 0.1);
            if (t > 0.8) return Math.max(0, (1 - t) / 0.2);
            return 1;
        }
    }

    static final class Store extends SavedData {
        final List<Storm> all = new ArrayList<>();
        /** Until when no storm forms over the players: a /weather clear, or a night slept through. */
        long clearUntil;

        static Store load(CompoundTag tag) {
            Store s = new Store();
            for (Tag t : tag.getList("Storms", Tag.TAG_COMPOUND)) s.all.add(Storm.load((CompoundTag) t));
            s.clearUntil = tag.getLong("ClearUntil");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Storm s : all) list.add(s.save());
            tag.put("Storms", list);
            tag.putLong("ClearUntil", clearUntil);
            return tag;
        }
    }

    private static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store::load, Store::new, "fts_geology_storms");
    }

    /** The storms out now, for a forecast; empty where none have formed. */
    static List<Storm> storms(ServerLevel level) {
        Store st = level.getDataStorage().get(Store::load, "fts_geology_storms");
        return st == null ? List.of() : st.all;
    }

    /** Whether the rain is regional in this level: the overworld, with the setting on, and not in TerraFirmaCraft. */
    public static boolean on(Level level) {
        return !level.isClientSide && GeyserConfig.REGIONAL_RAIN.get() && Level.OVERWORLD.equals(level.dimension())
                && !com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.active();
    }

    // === How much it rains where ============================================

    /** Rain by chunk, worked out afresh every couple of seconds: every farmland block asks for its own. */
    private static final Long2FloatOpenHashMap RAIN = new Long2FloatOpenHashMap(), THUNDER = new Long2FloatOpenHashMap();
    /** The slope of the ground at each chunk, which the wind drives the air up or lets it down: it does not change. */
    private static final Long2ObjectOpenHashMap<float[]> SLOPE = new Long2ObjectOpenHashMap<>();
    private static long rainAt = Long.MIN_VALUE;

    /**
     * How hard it rains at a place, 0 to 1 (under {@link #WET} nothing, from {@link #HEAVY} heavily). Off the server's
     * own thread, or where the rain is not regional, the world's one weather.
     */
    public static float intensityAt(ServerLevel level, int x, int z) {
        if (!on(level) || !level.getServer().isSameThread()) return level.isRaining() ? (level.isThundering() ? 1f : 0.4f) : 0f;
        refresh(level);
        long k = BlockPos.asLong(x >> 4, 0, z >> 4);
        float v = RAIN.getOrDefault(k, -1f);
        if (v >= 0) return v;
        work(level, x >> 4, z >> 4, k);
        return RAIN.get(k);
    }

    /** How much of a thunderstorm stands over a place, 0 to 1. */
    public static float thunderAt(ServerLevel level, int x, int z) {
        if (!on(level) || !level.getServer().isSameThread()) return level.isThundering() ? 1f : 0f;
        intensityAt(level, x, z);
        return THUNDER.get(BlockPos.asLong(x >> 4, 0, z >> 4));
    }

    private static void refresh(ServerLevel level) {
        long now = level.getGameTime();
        if (rainAt != Long.MIN_VALUE && now >= rainAt && now - rainAt < 40) return;
        rainAt = now;
        RAIN.clear();
        THUNDER.clear();
    }

    private static void work(ServerLevel level, int cx, int cz, long k) {
        Store st = level.getDataStorage().get(Store::load, "fts_geology_storms");
        long now = level.getGameTime();
        double px = cx * 16 + 8, pz = cz * 16 + 8;
        double dry = 1, thunder = 0;
        double[] wind = null;
        if (st != null) {
            for (Storm s : st.all) {
                double v = s.at(px, pz, now);
                if (v <= 0) continue;
                dry *= 1 - v;
                if (s.thunder) thunder = Math.max(thunder, v);
                if (wind == null) wind = new double[]{s.vx, s.vz};
            }
        }
        double rain = 1 - dry;
        if (rain > 0 && wind != null) rain *= orographic(level, cx, cz, wind[0], wind[1]);
        RAIN.put(k, (float) Mth.clamp(rain, 0, 1));
        THUNDER.put(k, (float) Mth.clamp(thunder, 0, 1));
    }

    /**
     * More rain where the wind drives the air up the ground, less where it comes down it: the slope along the wind,
     * blocks up a block across, scaled to half as much again at a steep windward face and a third at the lee.
     */
    private static double orographic(ServerLevel level, int cx, int cz, double vx, double vz) {
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < 1e-6) return 1;
        long k = BlockPos.asLong(cx, 0, cz);
        float[] slope = SLOPE.get(k);
        if (slope == null) {
            slope = slopeAt(level, cx * 16 + 8, cz * 16 + 8);
            if (SLOPE.size() > 50000) SLOPE.clear();
            SLOPE.put(k, slope);
        }
        double up = (slope[0] * vx + slope[1] * vz) / speed;
        return Mth.clamp(1 + 6 * up, 0.35, 1.6);
    }

    private static float[] slopeAt(ServerLevel level, int x, int z) {
        int d = 48;
        double e = height(level, x + d, z), w = height(level, x - d, z), s = height(level, x, z + d), n = height(level, x, z - d);
        if (Double.isNaN(e + w + s + n)) return new float[]{0, 0};
        return new float[]{(float) ((e - w) / (2 * d)), (float) ((s - n) / (2 * d))};
    }

    /** The ground's height: the terrain function on the mod's own world type, else the chunk's where it is loaded. */
    private static double height(ServerLevel level, int x, int z) {
        if (com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)
                && com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.ready()) {
            return com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.heightAt(x, z);
        }
        if (!com.jeladastudios.ftsgeology.util.Loaded.chunk(level, x >> 4, z >> 4)) return Double.NaN;
        return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
    }

    // === A region's weather ====================================================

    /** A region's side, in blocks, and the days its weather takes to turn. */
    private static final int REGION = 4096;
    private static final double TURN_DAYS = 9.0;

    /**
     * How wet a region's weather is now, 0 (a dry spell) to 1 (a wet one): a slow wander between its own values a few
     * game days apart, so a spell lasts days to weeks.
     */
    static double wetness(ServerLevel level, double x, double z) {
        int rx = Mth.floor(x / REGION), rz = Mth.floor(z / REGION);
        double t = level.getGameTime() / 24000.0 / TURN_DAYS;
        int t0 = Mth.floor(t);
        double f = t - t0;
        f = f * f * (3 - 2 * f);
        long seed = level.getSeed();
        double a = SeedHash.rand01(SeedHash.hash(seed, rx, rz, 0x3E7L * 31 + t0));
        double b = SeedHash.rand01(SeedHash.hash(seed, rx, rz, 0x3E7L * 31 + t0 + 1));
        return a + (b - a) * f;
    }

    /** How wet the spell the region round a place is in, 0 (a drought) to 1 (a wet spell): for the lakes' levels. */
    public static double spell(ServerLevel level, double x, double z) {
        return wetness(level, x, z);
    }

    /** The wind at a place, blocks a tick: round the highs and lows (see {@link Atmosphere#wind}). */
    static double[] wind(ServerLevel level, double x, double z) {
        return Atmosphere.wind(level, x, z);
    }

    // === Every second ======================================================

    private static long formed;
    /** Where storms formed against where they were tried, by the air's water; showers of a hot, humid afternoon. */
    private static long bornMoist, bornDry, triedMoist, triedDry, tried, steamy;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || !on(level)) return;
        long now = level.getGameTime();
        if (now % 20 != 0) return;
        Store st = store(level);
        List<ServerPlayer> players = level.players();
        // Drift, and go: past their time, or far from everyone.
        st.all.removeIf(s -> {
            s.x += s.vx * 20;
            s.z += s.vz * 20;
            if (now >= s.dies) return true;
            for (ServerPlayer p : players) {
                double dx = p.getX() - s.x, dz = p.getZ() - s.z;
                if (dx * dx + dz * dz < (s.radius + FAR) * (s.radius + FAR)) return false;
            }
            return true;
        });
        if (now % 1200 == 0 && level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE) && now >= st.clearUntil) form(level, st, players);
        st.setDirty();
        steer(level, st);
        if (now % 40 == 0) tell(level, players);
        if (now % 100 == 0) sky(level, players, st);
    }

    /** Each player's sky: the storms within sight and the highs and lows round them, for the clouds drawn. */
    private static void sky(ServerLevel level, List<ServerPlayer> players, Store st) {
        long now = level.getGameTime();
        for (ServerPlayer p : players) {
            it.unimi.dsi.fastutil.floats.FloatArrayList storms = new it.unimi.dsi.fastutil.floats.FloatArrayList();
            for (Storm s : st.all) {
                if (Math.hypot(s.x - p.getX(), s.z - p.getZ()) > s.radius * 1.4 + 1500) continue;
                storms.add((float) s.x);
                storms.add((float) s.z);
                storms.add((float) s.radius);
                storms.add((float) (s.peak * s.life(now)));
                storms.add((float) s.vx);
                storms.add((float) s.vz);
                storms.add(s.thunder ? 1f : 0f);
                storms.add(s.kind.ordinal());
            }
            it.unimi.dsi.fastutil.floats.FloatArrayList systems = new it.unimi.dsi.fastutil.floats.FloatArrayList();
            for (Atmosphere.Cell c : Atmosphere.cells(level)) {
                if (Math.hypot(c.x - p.getX(), c.z - p.getZ()) > 20000) continue;
                systems.add((float) c.xAt(now));
                systems.add((float) c.zAt(now));
                systems.add((float) c.radius);
                systems.add((float) c.anomaly(now));
                systems.add((float) c.vx);
                systems.add((float) c.vz);
            }
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                    new com.jeladastudios.ftsgeology.network.SkyPacket(now, storms.toFloatArray(), systems.toFloatArray()));
        }
    }

    /** How far from every player a storm may drift before it is let go, beyond its own radius. */
    private static final double FAR = 6000;
    /**
     * Storms at most round one player's land, and in the whole world: a cap for the world alone, reached by a few
     * players far apart, held each of them to a part of their rain.
     */
    private static final int MOST_NEAR = 16, MOST = 160;

    /**
     * Brings storms to where the players are, as their regions' weather calls for: where less of the land round a
     * player is under rain than the weather there would have, a storm forms somewhere in it and drifts across.
     */
    private static void form(ServerLevel level, Store st, List<ServerPlayer> players) {
        List<double[]> seen = new ArrayList<>();
        for (ServerPlayer p : players) {
            boolean near = false;
            for (double[] q : seen) near |= (q[0] - p.getX()) * (q[0] - p.getX()) + (q[1] - p.getZ()) * (q[1] - p.getZ()) < 1500 * 1500;
            if (near || st.all.size() >= MOST) continue;
            seen.add(new double[]{p.getX(), p.getZ()});
            int around = 0;
            for (Storm s : st.all) {
                if (Math.hypot(s.x - p.getX(), s.z - p.getZ()) < AROUND + s.radius + 2500) around++;
            }
            if (around >= MOST_NEAR) continue;
            double wet = wetness(level, p.getX(), p.getZ());
            var climate = level.getBiome(p.blockPosition()).value().getModifiedClimateSettings();
            double want = wanted(level, p.blockPosition());
            // Storms still gathering, or upwind on their way, count at once: counted only once they rained here, more
            // kept forming meanwhile, and the land came to be under rain two or three times as much as its weather has.
            double have = covered(level, st, p.getX(), p.getZ(), true);
            if (have >= want) continue;
            // One now and then, not one a minute: storms take their time to gather -- less of it where a low has come over
            // dry land and much more of it wants rain than has it.
            if (level.random.nextDouble() > Math.min(0.8, 0.25 + 1.5 * (want - have))) continue;
            Storm s = storm(level, p.getX(), p.getZ(), wet, climate.temperature(), want - have);
            st.all.add(s);
            formed++;
            com.jeladastudios.ftsgeology.util.Diagnostics.info("storm: a {} forms {} blocks from {} ({} across, {}{}, {} game hours; weather here {}, rain over {} of the land, wanting {})",
                    s.kind.name().toLowerCase(Locale.ROOT), (int) Math.hypot(s.x - p.getX(), s.z - p.getZ()), p.getName().getString(),
                    (int) (2 * s.radius), String.format(Locale.ROOT, "%.2f", s.peak), s.thunder ? ", thunder" : "",
                    (s.dies - s.born) / 1000, String.format(Locale.ROOT, "%.2f", wet), String.format(Locale.ROOT, "%.2f", have),
                    String.format(Locale.ROOT, "%.2f", want));
        }
    }

    /** How far round a player the land's rain is reckoned, and the storms for it formed. */
    private static final double AROUND = 3000;

    /**
     * Of the land round a place, the share under rain its weather would have now: a wet biome's a fifth of the time on
     * average, half of it in a wet spell, a twentieth in a dry one; a desert's hardly ever.
     */
    static double wanted(ServerLevel level, BlockPos pos) {
        return wantedAt(level, pos, level.getGameTime());
    }

    /** The same at a time ahead, the highs and lows where they will be then: for a forecast. */
    static double wantedAt(ServerLevel level, BlockPos pos, long when) {
        double wet = wetness(level, pos.getX(), pos.getZ());
        // The place's year of rain, from the lie of the land, and this part of the year's share of it (see RainClimate).
        double year = RainClimate.share(level, pos.getX(), pos.getZ()) * RainClimate.season(level, pos.getX(), pos.getZ());
        // And by the water the air over it holds now: wrung out by a long rain, dried over a drought, fed by the sea (Moisture).
        year *= Mth.clamp(Moisture.column(level, pos.getX(), pos.getZ()), 0.5, 1.5);
        // More under a low passing over, less under a high: taken over the land round the place, not its middle alone.
        double air = 0;
        for (int i = 0; i < 5; i++) {
            double a = i * Math.PI / 2, d = i == 4 ? 0 : AROUND * 0.6;
            air += Atmosphere.rainFactor(level, pos.getX() + Math.cos(a) * d, pos.getZ() + Math.sin(a) * d, when) / 5;
        }
        return Mth.clamp(GeyserConfig.RAIN_AMOUNT.get() * year * (0.05 + 0.7 * wet * wet) * air, 0, 0.9);
    }

    /** Points spread evenly over the land round a place, on a sunflower's spiral: where its rain is reckoned. */
    private static final int POINTS = 32;

    /**
     * The share of the land within {@link #AROUND} blocks of a place under rain: now, or {@code planned}, counting each
     * storm where it will stand halfway through its life and at its full strength (see {@link Storm#reaches}).
     */
    private static double covered(ServerLevel level, Store st, double x, double z, boolean planned) {
        long now = level.getGameTime();
        int wet = 0;
        for (int i = 0; i < POINTS; i++) {
            double r = AROUND * Math.sqrt((i + 0.5) / POINTS), a = i * 2.399963;
            double px = x + Math.cos(a) * r, pz = z + Math.sin(a) * r;
            for (Storm s : st.all) {
                if (planned ? s.reaches(px, pz, now) : s.at(px, pz, now) >= WET) {
                    wet++;
                    break;
                }
            }
        }
        return wet / (double) POINTS;
    }

    /**
     * A new storm, of the kind the weather makes, somewhere in the land round a place; no bigger than the share of that
     * land, {@code room}, still wanting rain, so a dry land gets its rain from showers.
     */
    private static Storm storm(ServerLevel level, double x, double z, double wet, float temperature, double room) {
        var rnd = level.random;
        Storm s = new Storm();
        // Where it will stand halfway through its life: anywhere in the land round the place, but mostly where the
        // pressure is low -- a few places tried, each as likely as the rain its air brings, squared.
        double mx = x, mz = z, total = 0;
        double[] px = new double[6], pz = new double[6], weight = new double[6];
        for (int i = 0; i < 6; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * AROUND;
            px[i] = x + Math.cos(a) * d;
            pz[i] = z + Math.sin(a) * d;
            double f = Atmosphere.rainFactor(level, px[i], pz[i]);
            // And as the water its air holds: a storm grows out of moist air, hardly out of dry.
            double full = full(level, px[i], pz[i]);
            weight[i] = f * f * (Double.isNaN(full) ? 1.0 : Mth.clamp((full - 0.3) / 0.45, 0.15, 1.4));
            if (!Double.isNaN(full)) {
                tried++;
                if (full >= 0.7) triedMoist++;
                else if (full < 0.5) triedDry++;
            }
            total += weight[i];
        }
        double pick = rnd.nextDouble() * total;
        for (int i = 0; i < 6; i++) {
            mx = px[i];
            mz = pz[i];
            if ((pick -= weight[i]) <= 0) break;
        }
        double air = Atmosphere.rainFactor(level, mx, mz);
        double here = full(level, mx, mz);
        if (!Double.isNaN(here)) {
            if (here >= 0.7) bornMoist++;
            else if (here < 0.5) bornDry++;
        }
        // A hot, humid afternoon: the air near saturation rises off the warm ground and breaks into showers and thunder.
        long day = level.getDayTime() % 24000;
        boolean sultry = !Double.isNaN(here) && here >= 0.7 && temperature > 0.6 && day >= 5000 && day <= 10000;
        double roll = rnd.nextDouble();
        if (wet > 0.75 && air > 1.2 && roll < 0.35) s.kind = Kind.SPELL;
        else if (roll < (wet < 0.4 ? 0.7 : 0.35) * (temperature > 0.8 ? 1.2 : temperature < 0.3 ? 0.5 : 1.0)
                * RainClimate.showery(level) * (sultry ? 1.6 : 1.0)) s.kind = Kind.SHOWER;
        else s.kind = Kind.FRONT;
        // Under a high only a shower breaks out, from a hot afternoon's rising air.
        if (air < 0.6) s.kind = Kind.SHOWER;
        double most = AROUND * Math.sqrt(Math.max(1.5 * room, 0.012));
        if (most < 800) s.kind = Kind.SHOWER;
        // Carried on the prevailing wind, as the lows and highs are.
        double[] w = Atmosphere.steering(level, mx, mz, level.getGameTime());
        long now = level.getGameTime();
        switch (s.kind) {
            case SHOWER -> {
                s.radius = 250 + rnd.nextDouble() * 450;
                s.peak = 0.5 + rnd.nextDouble() * 0.5;
                s.dies = now + 720 + rnd.nextInt(4080);
                s.thunder = s.peak > (sultry ? 0.66 : 0.78) && temperature > 0.3;
                if (sultry) steamy++;
                s.vx = w[0] * 1.5;
                s.vz = w[1] * 1.5;
            }
            case FRONT -> {
                s.radius = 800 + rnd.nextDouble() * 1200;
                s.peak = 0.3 + rnd.nextDouble() * 0.45;
                s.dies = now + 7200 + rnd.nextInt(40800);
                s.vx = w[0];
                s.vz = w[1];
            }
            case SPELL -> {
                s.radius = 1500 + rnd.nextDouble() * 1500;
                s.peak = 0.3 + rnd.nextDouble() * 0.3;
                s.dies = now + 72000 + rnd.nextInt(264000);
                s.vx = w[0] * 0.15;
                s.vz = w[1] * 0.15;
            }
        }
        // The settings: how hard, how long and how wide; a wider storm still no bigger than the rain wanted round here.
        double size = GeyserConfig.STORM_SIZE.get();
        s.peak = Math.min(1.0, s.peak * GeyserConfig.RAIN_STRENGTH.get());
        s.dies = now + (long) ((s.dies - now) * GeyserConfig.STORM_LENGTH.get());
        s.radius = Math.min(s.radius * size, Math.max(250 * size, most));
        s.born = now;
        s.seed = rnd.nextInt(1 << 20);
        // Upwind of that place by the way it drifts till then: so a place is under rain as much of the time as the land
        // round it is. Aimed at the place itself, it kept whoever stood there under rain most of the time.
        double speed = Math.max(1e-4, Math.hypot(s.vx, s.vz));
        double back = Math.min(2500, speed * (s.dies - now) * 0.5);
        s.x = mx - s.vx / speed * back;
        s.z = mz - s.vz / speed * back;
        return s;
    }

    /** The world's one weather, set to what the storms add up to: rain while any storm is out, thunder while any carries it. */
    private static void steer(ServerLevel level, Store st) {
        if (!(level.getLevelData() instanceof ServerLevelData data)) return;
        boolean rain = !st.all.isEmpty(), thunder = false;
        for (Storm s : st.all) thunder |= s.thunder;
        data.setRaining(rain);
        data.setThundering(thunder);
        data.setRainTime(rain ? 12000 : 0);
        data.setThunderTime(thunder ? 12000 : 0);
        data.setClearWeatherTime(rain ? 0 : 12000);
    }

    /** Each player's own weather, to draw. */
    private static void tell(ServerLevel level, List<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            float[] here = smoothAt(level, p.getX(), p.getZ());
            double[] w = wind(level, p.getX(), p.getZ());
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                    new LocalWeatherPacket(here[0], here[1], (float) (w[0] * 20), (float) (w[1] * 20), Moisture.fog(level, p.blockPosition())));
        }
    }

    /**
     * Rain and thunder at a point, blended between the middles of the four chunks round it: taken chunk by chunk, the
     * rain a player heard jumped at a chunk's edge where the slope under the wind changed.
     */
    static float[] smoothAt(ServerLevel level, double x, double z) {
        double fx = (x - 8) / 16.0, fz = (z - 8) / 16.0;
        int cx = Mth.floor(fx), cz = Mth.floor(fz);
        double tx = fx - cx, tz = fz - cz;
        float rain = 0, thunder = 0;
        for (int i = 0; i < 4; i++) {
            int ox = i & 1, oz = i >> 1;
            double w = (ox == 0 ? 1 - tx : tx) * (oz == 0 ? 1 - tz : tz);
            int bx = (cx + ox) * 16 + 8, bz = (cz + oz) * 16 + 8;
            rain += (float) (w * intensityAt(level, bx, bz));
            thunder += (float) (w * thunderAt(level, bx, bz));
        }
        return new float[]{rain, thunder};
    }

    // === Commands and sleep ===================================================

    /**
     * {@code /weather}: rain or thunder brings a storm over each player for the time given (vanilla's own lengths where
     * none is); clear takes away the storms over them and keeps new ones off for that long.
     */
    public static void command(ServerLevel level, boolean raining, boolean thundering, int ticks) {
        if (!on(level)) return;
        Store st = store(level);
        long now = level.getGameTime();
        if (!raining) {
            clearOverPlayers(level, st);
            st.clearUntil = now + Math.max(ticks, 1200);
        } else {
            st.clearUntil = 0;
            for (ServerPlayer p : level.players()) {
                Storm s = new Storm();
                s.kind = thundering ? Kind.SHOWER : Kind.FRONT;
                s.radius = thundering ? 700 : 1500;
                s.peak = thundering ? 1.0 : 0.6;
                s.thunder = thundering;
                s.x = p.getX();
                s.z = p.getZ();
                double[] w = wind(level, s.x, s.z);
                s.vx = w[0] * 0.2;
                s.vz = w[1] * 0.2;
                // Born a tenth of its life ago: at full strength at once.
                long life = Math.max(1200, ticks > 0 ? ticks : 12000 + level.random.nextInt(12000));
                s.born = now - life / 9;
                s.dies = now + life;
                s.seed = level.random.nextInt(1 << 20);
                st.all.add(s);
            }
        }
        st.setDirty();
        refreshNow();
    }

    /**
     * A storm of {@code peak} strength standing still over a place for a game day, the storms already over it taken away
     * first; with a peak of 0, only that. For trying the weather out. Returns the storms taken away.
     */
    public static int spawnHere(ServerLevel level, int x, int z, double peak, int radius) {
        if (!on(level)) return 0;
        Store st = store(level);
        int before = st.all.size();
        st.all.removeIf(s -> Math.hypot(s.x - x, s.z - z) < s.radius + radius);
        int gone = before - st.all.size();
        if (peak > 0) {
            Storm s = new Storm();
            s.kind = Kind.FRONT;
            s.x = x;
            s.z = z;
            s.radius = radius;
            s.peak = peak;
            s.thunder = peak > 0.78;
            long now = level.getGameTime();
            s.born = now - 2400;
            s.dies = now + 24000;
            s.seed = level.random.nextInt(1 << 20);
            st.all.add(s);
        }
        st.clearUntil = level.getGameTime() + 24000;
        st.setDirty();
        refreshNow();
        return gone;
    }

    /** A night slept through: the sky clears over the sleepers, and stays clear a while. */
    public static void slept(ServerLevel level) {
        if (!on(level)) return;
        Store st = store(level);
        clearOverPlayers(level, st);
        st.clearUntil = level.getGameTime() + 12000;
        st.setDirty();
        refreshNow();
    }

    private static void clearOverPlayers(ServerLevel level, Store st) {
        st.all.removeIf(s -> {
            for (ServerPlayer p : level.players()) {
                double dx = p.getX() - s.x, dz = p.getZ() - s.z;
                if (dx * dx + dz * dz < (s.radius + 300) * (s.radius + 300)) return true;
            }
            return false;
        });
    }

    private static void refreshNow() {
        rainAt = Long.MIN_VALUE;
    }

    public static void clear() {
        RAIN.clear();
        THUNDER.clear();
        SLOPE.clear();
        rainAt = Long.MIN_VALUE;
        formed = 0;
        bornMoist = bornDry = triedMoist = triedDry = tried = steamy = 0;
    }

    public static String summary() {
        return String.format(Locale.ROOT, "storms: %d formed; where the air's water was worked out, %d in moist air and %d in dry, of places tried %d of %d moist and %d dry; %d showers of a hot, humid afternoon",
                formed, bornMoist, bornDry, triedMoist, tried, triedDry, steamy);
    }

    /** How full of water the air over a place is (see Moisture); NaN where it is not worked out. */
    private static double full(ServerLevel level, double x, double z) {
        return Moisture.fullness(level, new BlockPos((int) Math.floor(x), level.getSeaLevel() + 1, (int) Math.floor(z)));
    }

    public static boolean any() {
        return formed > 0;
    }

    /** What the weather is round a place, for {@code /geology weather}. */
    public static List<String> describe(ServerLevel level, int x, int z) {
        List<String> out = new ArrayList<>();
        double[] w = wind(level, x, z);
        BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
        var biome = com.jeladastudios.ftsgeology.util.Loaded.biome(level, at);
        String falls = switch (biome.value().getPrecipitationAt(at)) {
            case NONE -> "none falls";
            case SNOW -> "as snow";
            default -> "as rain";
        };
        out.add(String.format(Locale.ROOT, "rain here %.2f (%s, %s), thunder %.2f; the region's weather %.2f (0 dry spell, 1 wet); wind %.1f blocks/s towards %.0f deg",
                intensityAt(level, x, z), biome.unwrapKey().map(k -> k.location().toString()).orElse("?"), falls, thunderAt(level, x, z),
                wetness(level, x, z), Math.hypot(w[0], w[1]) * 20, Math.toDegrees(Math.atan2(w[1], w[0]))));
        out.add(Moisture.describe(level, at));
        out.add(RainClimate.describe(level, x, z));
        out.addAll(Atmosphere.describe(level, x, z));
        Store st = level.getDataStorage().get(Store::load, "fts_geology_storms");
        if (st == null) return out;
        out.add(String.format(Locale.ROOT, "the land within %d blocks: %.2f under rain now, %.2f with the storms on their way; its weather wants %.2f",
                (int) AROUND, covered(level, st, x, z, false), covered(level, st, x, z, true), wanted(level, at)));
        long now = level.getGameTime();
        for (Storm s : st.all) {
            out.add(String.format(Locale.ROOT, "%s %s %d blocks off, %d across, peak %.2f now %.2f, %.1f game hours left",
                    s.kind.name().toLowerCase(Locale.ROOT), s.thunder ? "(thunder)" : "", (int) Math.hypot(s.x - x, s.z - z),
                    (int) (2 * s.radius), s.peak, s.peak * s.life(now), (s.dies - now) / 1000.0));
        }
        return out;
    }
}
