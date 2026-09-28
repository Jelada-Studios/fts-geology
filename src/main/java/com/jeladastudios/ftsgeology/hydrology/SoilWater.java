package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The water in the ground: how wet the soil is and where the groundwater stands, across the overworld, a little at a
 * time.
 *
 * <p>Each chunk keeps sixteen cells, four blocks a side, and each cell a small bucket model of the ground: a thin top
 * layer that wets in a shower and dries in the sun, the root zone under it that plants draw on, deep soil under that,
 * water standing on the surface, and how far the groundwater stands over or under the level {@link WaterTable} gives
 * the place. Rain soaks into the top as fast as the soil takes it -- sand quickly, clay slowly -- and the rest stands
 * and runs off; each layer drains into the one under it faster the wetter it is, and the deep soil into the
 * groundwater, which rises for it and sinks back over weeks; the sun and the plants take water back out of the top
 * and the root zone, fast in a hot biome and hardly at all in a cold one, and a shallow water table wets the root
 * zone from below. Built-over ground keeps what it had.</p>
 *
 * <p>The ground's time runs slower than the sky's: a game day is a week of it, so a shower wets the top for half a
 * day, a dry spell takes the root zone three or four days, and the groundwater answers over weeks. Loaded chunks are
 * looked at every ten seconds, within a small share of the tick; a chunk loaded again after a while catches up on
 * the time it was away, with the rain the place gets on average. What is kept goes into the chunk's own save.</p>
 *
 * <p>On its own it changes nothing in the world. With {@code soilWaterChangesGround} the grass over drying ground is
 * drawn towards straw (the colour is sent to the players watching the chunk) and farmland over wet soil stays moist;
 * no block is replaced.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class SoilWater {

    private SoilWater() {}

    /** The ground's hours in a game tick: a game day is a week. */
    static final double HOURS_PER_TICK = 7.0 * 24.0 / 24000.0;
    /** Ticks between looks at a loaded chunk, the longest step taken at once, and the most steps a catch-up takes. */
    static final int EVERY = 200;
    static final double STEP_HOURS = 6.0;
    static final int MOST_STEPS = 400;
    /** Steps of average weather a cell never looked at is settled with, and their length: a month of the ground's time. */
    static final int SPIN_UP = 40;
    static final double SPIN_UP_HOURS = 18.0;
    /** Rain, in millimetres an hour of the ground's time, and the share of the time it rains on average. */
    static final double RAIN = 1.2, STORM = 3.0, RAINY_SHARE = 0.2;
    /** How long water standing on the ground takes to run off, and the groundwater to settle back, in hours. */
    static final double RUNOFF_HOURS = 6.0, SETTLE_HOURS = 60.0 * 24.0;
    /** Millimetres of water that raise the groundwater a block: a fifth of the rock is pore that drains. */
    static final double MM_PER_BLOCK = 200.0;

    private static final String TAG = "fts_soil_water";
    private static final int VERSION = 1;

    /**
     * What the ground is at a cell, for how it holds and passes water. The layers are the top two centimetres, the
     * root zone to sixty and the deep soil to a metre eighty; their capacities are the water they hold soaked, in mm.
     * Field capacity is the share a soil keeps once it has drained, the wilting point the share plants cannot draw
     * out of it; the rates are in mm an hour.
     */
    public enum Soil {
        SAND(8, 240, 480, 0.38, 0.12, 30, 8, 0.25),
        LOAM(9, 270, 540, 0.67, 0.26, 10, 2, 1.0),
        CLAY(10, 300, 600, 0.80, 0.50, 3, 0.4, 0.7),
        ROCK(2, 20, 60, 0.30, 0.10, 1.5, 0.6, 0.1),
        /** Open water: the cell is its lake or river, soaked through. */
        WATER(0, 0, 0, 1, 0, 0, 0, 0),
        /** Built over, or not ground at all: left as it was. */
        NONE(0, 0, 0, 1, 0, 0, 0, 0);

        final double top, root, deep, fieldCapacity, wilting, infiltration, conductivity, plants;

        Soil(double top, double root, double deep, double fieldCapacity, double wilting, double infiltration,
             double conductivity, double plants) {
            this.top = top;
            this.root = root;
            this.deep = deep;
            this.fieldCapacity = fieldCapacity;
            this.wilting = wilting;
            this.infiltration = infiltration;
            this.conductivity = conductivity;
            this.plants = plants;
        }

        /** How much of the water plants can use a soil wet to this share holds: 0 at the wilting point, 1 drained. */
        public double available(double saturation) {
            return fieldCapacity <= wilting ? 0 : Mth.clamp((saturation - wilting) / (fieldCapacity - wilting), 0.0, 1.0);
        }

        public boolean ground() {
            return this != WATER && this != NONE;
        }
    }

    /** One chunk's cells. The arrays are by cell, {@code (lz / 4) * 4 + lx / 4}; water in millimetres. */
    static final class Cells {
        final float[] top = new float[16], root = new float[16], deep = new float[16], pond = new float[16];
        /** Blocks the groundwater stands over (or, below zero, under) the water table's own level. */
        final float[] table = new float[16];
        /** The game time of the last look, or -1 before the first. */
        long last = -1L;
        /** Blocks from the ground to the water table's own level under the chunk's middle, or -1 not known yet. */
        int depth = -1;
        /** Unloaded: its data goes with the save that follows, and then it is let go. */
        boolean leaving;
        /** How dry the grass looks in each cell, 0 to 15, as last sent to the players watching the chunk. */
        final byte[] tint = new byte[16];
    }

    /**
     * A reading of one cell. Water in millimetres; saturations from 0 to 1; the share of the root zone's water plants
     * can use, from 0 at the wilting point to 1 drained; table blocks over its own level.
     */
    public record Reading(Soil soil, double top, double root, double deep, double pond, double topSat, double rootSat,
                          double deepSat, double rootAvailable, double table, int depth, double hoursSinceLook,
                          int grassDryness) {}

    /** Cells by overworld chunk. */
    private static final Map<Long, Cells> CELLS = new ConcurrentHashMap<>();
    /** Loaded overworld chunks, looked at in turn. */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    private static long looks, steps, nanos;

    private static boolean enabled(Level level) {
        return GeyserConfig.SOIL_WATER.get() && Level.OVERWORLD.equals(level.dimension()) && !TfcCompat.active();
    }

    private static long key(Level level, int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }

    // === Chunks coming and going ===========================================

    @SubscribeEvent
    public static void onChunkData(ChunkDataEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        CompoundTag tag = event.getData().getCompound(TAG);
        if (tag.getInt("v") != VERSION) return;
        int[] w = tag.getIntArray("w");
        if (w.length != 80) return;
        Cells c = new Cells();
        for (int i = 0; i < 16; i++) {
            c.top[i] = w[i] / 10f;
            c.root[i] = w[16 + i] / 10f;
            c.deep[i] = w[32 + i] / 10f;
            c.pond[i] = w[48 + i] / 10f;
            c.table[i] = w[64 + i] / 100f;
        }
        c.last = tag.getLong("t");
        c.depth = tag.getInt("d");
        ChunkPos p = event.getChunk().getPos();
        CELLS.put(key(level, p.x, p.z), c);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)
                || !enabled(level)) {
            return;
        }
        ChunkPos p = chunk.getPos();
        Cells c = CELLS.get(key(level, p.x, p.z));
        if (c != null) c.leaving = false;
        synchronized (QUEUE) {
            QUEUE.add(p.toLong());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)
                || !enabled(level)) {
            return;
        }
        ChunkPos p = chunk.getPos();
        synchronized (QUEUE) {
            QUEUE.remove(p.toLong());
        }
        Cells c = CELLS.get(key(level, p.x, p.z));
        if (c == null) return;
        // The chunk is saved straight after this, but only if it counts as changed; the water has.
        c.leaving = true;
        chunk.setUnsaved(true);
    }

    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        ChunkPos p = event.getChunk().getPos();
        long k = key(level, p.x, p.z);
        Cells c = CELLS.get(k);
        if (c == null) return;
        if (c.last < 0) {
            if (c.leaving) CELLS.remove(k);
            return;
        }
        int[] w = new int[80];
        for (int i = 0; i < 16; i++) {
            w[i] = Math.round(c.top[i] * 10);
            w[16 + i] = Math.round(c.root[i] * 10);
            w[32 + i] = Math.round(c.deep[i] * 10);
            w[48 + i] = Math.round(c.pond[i] * 10);
            w[64 + i] = Math.round(c.table[i] * 100);
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", VERSION);
        tag.putLong("t", c.last);
        tag.putInt("d", c.depth);
        tag.putIntArray("w", w);
        event.getData().put(TAG, tag);
        if (c.leaving) CELLS.remove(k);
    }

    /** The server is going down: every loaded chunk is saved, the water with it. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null || !enabled(level)) return;
        long[] keys;
        synchronized (QUEUE) {
            keys = QUEUE.toLongArray();
        }
        for (long k : keys) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k));
            if (chunk != null) chunk.setUnsaved(true);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (looks > 0) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", summary());
        CELLS.clear();
        synchronized (QUEUE) {
            QUEUE.clear();
        }
        looks = steps = nanos = 0;
    }

    // === Looking in turn ==================================================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null || !enabled(level)) return;
        com.jeladastudios.ftsgeology.util.TickBudget.open(server.getTickCount());
        long started = System.nanoTime();
        long deadline = started + com.jeladastudios.ftsgeology.util.TickBudget.slice(0.05);
        long now = level.getGameTime();
        int tries, looked = 0;
        // A chunk is looked at every EVERY ticks, so a tick needs to go through only a share of them: twice what that
        // takes, so a chunk is never long overdue.
        synchronized (QUEUE) {
            tries = Math.min(QUEUE.size(), 1 + 2 * QUEUE.size() / EVERY);
        }
        // One chunk a tick whatever the budget says: with the budget spent by others, as it is while a world first
        // loads, nothing else would ever be looked at.
        while (tries-- > 0 && (looked == 0 || System.nanoTime() < deadline)) {
            long k;
            synchronized (QUEUE) {
                if (QUEUE.isEmpty()) return;
                k = QUEUE.removeFirstLong();
                QUEUE.add(k);
            }
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            long key = key(level, cx, cz);
            Cells c = CELLS.get(key);
            if (c != null && now - c.last < EVERY) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            if (c == null) {
                c = new Cells();
                CELLS.put(key, c);
            }
            look(level, chunk, c, now, deadline);
            looked++;
        }
        nanos += System.nanoTime() - started;
    }

    /** Brings a chunk's cells up to now: the weather since the last look, or the average of it for a long gap. */
    private static void look(ServerLevel level, LevelChunk chunk, Cells c, long now, long deadline) {
        ChunkPos p = chunk.getPos();
        if (c.depth < 0) {
            int table = WaterTable.tableYBefore(level, p.getMiddleBlockX(), p.getMiddleBlockZ(), deadline);
            if (table != Integer.MIN_VALUE) {
                int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 8, 8);
                c.depth = Math.max(0, ground - table);
            }
        }
        boolean first = c.last < 0;
        double hours = first ? 0 : (now - c.last) * HOURS_PER_TICK;
        boolean away = hours > EVERY * HOURS_PER_TICK * 3;          // not looked at for a while: unloaded
        boolean raining = level.isRaining(), storm = level.isThundering(), day = level.isDay();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 16; i++) {
            int x = p.getMinBlockX() + (i & 3) * 4 + 2, z = p.getMinBlockZ() + (i >> 2) * 4 + 2;
            int g = ground(level, x, z);
            if (g == Integer.MIN_VALUE) continue;
            BlockState top = chunk.getBlockState(m.set(x, g, z));
            Soil soil = soilOf(top, chunk.getBlockState(m.set(x, g + 1, z)));
            if (soil == Soil.WATER) {
                c.top[i] = c.root[i] = c.deep[i] = 0;
                c.pond[i] = 0;
                c.table[i] = 0;
                continue;
            }
            if (!soil.ground()) continue;
            Holder<Biome> biome = level.getBiome(m.set(x, g + 1, z));
            double pet = evaporation(biome.value());
            // Snow counts as the rain it melts into: a tundra's ground is wet, not a desert's.
            boolean rains = biome.value().hasPrecipitation();
            if (first) {
                spinUp(c, i, soil, pet, rains);
                continue;
            }
            double left = hours;
            int n = 0;
            while (left > 1e-6 && n++ < MOST_STEPS) {
                double h = Math.min(left, STEP_HOURS);
                // Away, the weather is what the place gets on average; here, what the sky is doing now.
                double rain = !rains ? 0 : away ? RAIN * RAINY_SHARE : raining ? (storm ? STORM : RAIN) : 0;
                double petNow = away ? pet : pet * (day ? 1.9 : 0.1) * (raining ? 0.3 : 1.0);
                step(c, i, soil, h, rain, petNow);
                left -= h;
                steps++;
            }
            // Longer away than the steps reach: what is left of the gap has settled the ground to its average.
            if (left > 1e-6) spinUp(c, i, soil, pet, rains);
        }
        c.last = now;
        looks++;
        if (GeyserConfig.SOIL_WATER_GROUND.get()) showGround(level, chunk, c);
    }

    // === What it does to the ground (soilWaterChangesGround) ===============

    /**
     * How dry the grass over a cell looks, 0 to 15: green while the root zone holds half of what plants can use, then
     * paler, straw once it is nearly all gone -- from about the third dry day in temperate loam to the seventh.
     */
    static byte tintOf(Soil soil, float root) {
        if (soil != Soil.LOAM && soil != Soil.CLAY && soil != Soil.SAND) return 0;
        double t = Mth.clamp((0.45 - soil.available(sat(root, soil.root))) / 0.35, 0.0, 1.0);
        return (byte) Math.round(t * 15);
    }

    /** Farmland over soil this wet stays moist, as a rain-fed field does. */
    private static final double MOIST_FIELD = 0.6;

    /** The grass's colour to the players watching the chunk, when it has changed; moist fields over wet soil. */
    private static void showGround(ServerLevel level, LevelChunk chunk, Cells c) {
        ChunkPos p = chunk.getPos();
        boolean changed = false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 16; i++) {
            int x0 = p.getMinBlockX() + (i & 3) * 4, z0 = p.getMinBlockZ() + (i >> 2) * 4;
            int g = ground(level, x0 + 2, z0 + 2);
            Soil soil = g == Integer.MIN_VALUE ? Soil.NONE
                    : soilOf(chunk.getBlockState(m.set(x0 + 2, g, z0 + 2)), chunk.getBlockState(m.set(x0 + 2, g + 1, z0 + 2)));
            byte t = tintOf(soil, c.root[i]);
            if (t != c.tint[i]) {
                c.tint[i] = t;
                changed = true;
            }
            if (!soil.ground() || soil.available(sat(c.root[i], soil.root)) < MOIST_FIELD) continue;
            for (int dx = 0; dx < 4; dx++) {
                for (int dz = 0; dz < 4; dz++) {
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, (x0 + dx) & 15, (z0 + dz) & 15);
                    for (int y = top; y >= top - 1; y--) {
                        BlockState s = chunk.getBlockState(m.set(x0 + dx, y, z0 + dz));
                        if (!s.is(Blocks.FARMLAND)) continue;
                        if (s.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE) < 7) {
                            level.setBlock(m.immutable(), s.setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7), 2);
                        }
                        break;
                    }
                }
            }
        }
        if (changed) {
            com.jeladastudios.ftsgeology.network.ModNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.TRACKING_CHUNK.with(() -> chunk),
                    new com.jeladastudios.ftsgeology.network.SoilTintPacket(p.x, p.z, c.tint.clone()));
        }
    }

    /** A player starts watching a chunk: how dry its grass looks, if it looks dry at all. */
    @SubscribeEvent
    public static void onWatch(net.minecraftforge.event.level.ChunkWatchEvent.Watch event) {
        ServerLevel level = event.getLevel();
        if (!enabled(level) || !GeyserConfig.SOIL_WATER_GROUND.get()) return;
        ChunkPos p = event.getPos();
        Cells c = CELLS.get(key(level, p.x, p.z));
        if (c == null) return;
        boolean any = false;
        for (byte b : c.tint) any |= b != 0;
        if (!any) return;
        com.jeladastudios.ftsgeology.network.ModNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(event::getPlayer),
                new com.jeladastudios.ftsgeology.network.SoilTintPacket(p.x, p.z, c.tint.clone()));
    }

    /** A cell never looked at: the ground as the place's weather keeps it, a few weeks of it on average. */
    private static void spinUp(Cells c, int i, Soil soil, double pet, boolean rains) {
        double wet = rains ? Mth.clamp(RAIN * RAINY_SHARE / Math.max(pet, 0.01), 0.0, 1.0) : 0.0;
        double kept = soil.wilting + (soil.fieldCapacity - soil.wilting) * (0.2 + 0.8 * wet);
        c.top[i] = (float) (soil.top * kept * 0.8);
        c.root[i] = (float) (soil.root * kept);
        c.deep[i] = (float) (soil.deep * soil.fieldCapacity * (0.7 + 0.3 * wet));
        c.pond[i] = 0;
        for (int k = 0; k < SPIN_UP; k++) step(c, i, soil, SPIN_UP_HOURS, rains ? RAIN * RAINY_SHARE : 0, pet);
    }

    /**
     * One step of the ground's water over {@code h} hours, with {@code rain} and the evaporation the air asks for in
     * millimetres an hour.
     */
    static void step(Cells c, int i, Soil s, double h, double rain, double pet) {
        double top = c.top[i], root = c.root[i], deep = c.deep[i], pond = c.pond[i], table = c.table[i];
        // Rain on the ground, and what it takes in: all of it up to what the soil passes in the time.
        pond += rain * h;
        double in = Math.min(pond, s.infiltration * h);
        pond -= in;
        top += in;
        // Each layer wetter than it drains to passes water down, faster the wetter; what it cannot hold goes on.
        double down = Math.min(top, s.conductivity * h * drains(sat(top, s.top), s)) + Math.max(0, top - s.top);
        top -= down;
        root += down;
        down = Math.min(root, s.conductivity * h * drains(sat(root, s.root), s)) + Math.max(0, root - s.root);
        root -= down;
        deep += down;
        double recharge = Math.min(deep, 0.5 * s.conductivity * h * drains(sat(deep, s.deep), s))
                + Math.max(0, deep - s.deep);
        deep -= recharge;
        // Groundwater up at the surface takes no more: what would have gone down stands on the ground instead.
        int depth = Math.max(0, c.depth);
        if (c.depth >= 0 && table >= depth) {
            pond += recharge;
            recharge = 0;
        }
        table += (float) (recharge / MM_PER_BLOCK);
        // The sun and the plants take it back: standing water first, then the top, slower as it dries, then the roots,
        // freely until half of what plants can use is gone and less and less after.
        double want = pet * h;
        double e = Math.min(pond, want);
        pond -= e;
        want -= e;
        double t = sat(top, s.top);
        e = Math.min(top, want * t * t);
        top -= e;
        want -= e;
        double stress = Mth.clamp(s.available(sat(root, s.root)) / 0.5, 0.0, 1.0);
        e = Math.min(root, want * s.plants * stress);
        root -= e;
        // Standing water runs off towards the rivers.
        pond *= Math.exp(-h / RUNOFF_HOURS);
        // The groundwater settles back to its level over weeks; near the surface it wets the roots from below.
        table *= Math.exp(-h / SETTLE_HOURS);
        if (c.depth >= 0) {
            double under = depth - table;
            if (under < 3) {
                double rise = Math.min(s.root * s.fieldCapacity - root, 0.3 * h * (1 - Math.max(0, under) / 3.0));
                if (rise > 0) {
                    root += rise;
                    table -= (float) (rise / MM_PER_BLOCK);
                }
            }
        }
        c.top[i] = (float) Mth.clamp(top, 0, s.top);
        c.root[i] = (float) Mth.clamp(root, 0, s.root);
        c.deep[i] = (float) Mth.clamp(deep, 0, s.deep);
        c.pond[i] = (float) Math.max(0, pond);
        c.table[i] = (float) Mth.clamp(table, -32, Math.max(0, depth) + 1);
    }

    private static double sat(double water, double cap) {
        return cap <= 0 ? 0 : Mth.clamp(water / cap, 0.0, 1.0);
    }

    /** How freely a layer this wet drains, 0 at field capacity and under, 1 soaked. */
    private static double drains(double saturation, Soil s) {
        if (saturation <= s.fieldCapacity) return 0;
        double x = (saturation - s.fieldCapacity) / (1 - s.fieldCapacity);
        return x * x;
    }

    /**
     * What the air takes out of wet ground, mm an hour on average: under a millimetre a day where it is cold, four in
     * a temperate summer, eight in a hot desert.
     */
    static double evaporation(Biome biome) {
        double t = biome.getBaseTemperature();
        return Mth.clamp(0.8 + 3.5 * t, 0.2, 8.0) / 24.0;
    }

    /** The ground of a column, under its plants, trees, snow and any water on it; a roof counts, and is no soil. */
    private static int ground(ServerLevel level, int x, int z) {
        return com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z);
    }

    /** The ground a cell stands on, by its top block; what lies over it, if it is a fluid, makes it water. */
    static Soil soilOf(BlockState top, BlockState over) {
        if (!top.getFluidState().isEmpty() || !over.getFluidState().isEmpty()) return Soil.WATER;
        if (top.is(BlockTags.SAND) || top.is(Tags.Blocks.GRAVEL) || top.is(Tags.Blocks.SANDSTONE)) return Soil.SAND;
        if (top.is(Blocks.CLAY) || top.is(Blocks.MUD) || top.is(Blocks.MUDDY_MANGROVE_ROOTS)
                || top.is(BlockTags.TERRACOTTA)) {
            return Soil.CLAY;
        }
        if (top.is(BlockTags.DIRT) || top.is(Blocks.FARMLAND) || top.is(Blocks.DIRT_PATH) || top.is(Blocks.SNOW_BLOCK)
                || top.is(Blocks.POWDER_SNOW)) {
            return Soil.LOAM;
        }
        if (top.is(BlockTags.BASE_STONE_OVERWORLD) || top.is(Tags.Blocks.STONE) || top.is(Blocks.CALCITE)
                || top.is(Blocks.BASALT) || top.is(Blocks.SMOOTH_BASALT) || top.is(Blocks.BLACKSTONE)
                || top.is(BlockTags.ICE)) {
            return Soil.ROCK;
        }
        return Soil.NONE;
    }

    // === Reading ==========================================================

    /** The cell a column is in, as last looked at, or null where nothing is kept (not loaded, or not looked at). */
    public static Reading at(ServerLevel level, int x, int z) {
        if (!enabled(level)) return null;
        Cells c = CELLS.get(key(level, x >> 4, z >> 4));
        if (c == null || c.last < 0) return null;
        int i = ((z & 15) >> 2) * 4 + ((x & 15) >> 2);
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        Soil soil = Soil.NONE;
        if (chunk != null) {
            int cx = (x & ~3) + 2, cz = (z & ~3) + 2;
            int g = ground(level, cx, cz);
            if (g != Integer.MIN_VALUE) {
                soil = soilOf(chunk.getBlockState(new BlockPos(cx, g, cz)), chunk.getBlockState(new BlockPos(cx, g + 1, cz)));
            }
        }
        double hours = (level.getGameTime() - c.last) * HOURS_PER_TICK;
        double rootSat = sat(c.root[i], soil.root);
        return new Reading(soil, c.top[i], c.root[i], c.deep[i], c.pond[i], sat(c.top[i], soil.top), rootSat,
                sat(c.deep[i], soil.deep), soil.available(rootSat), c.table[i], c.depth, hours, c.tint[i]);
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "soil water: %d chunks kept, %d looks, %d steps, %.1f ms in all, %.3f ms a look",
                CELLS.size(), looks, steps, nanos / 1e6, looks == 0 ? 0.0 : nanos / 1e6 / looks);
    }
}
