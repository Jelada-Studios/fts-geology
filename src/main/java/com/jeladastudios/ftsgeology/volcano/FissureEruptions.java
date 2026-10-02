package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.instrument.SeismicNetwork;
import com.jeladastudios.ftsgeology.network.ModNetwork;
import com.jeladastudios.ftsgeology.quake.Earthquake;
import com.jeladastudios.ftsgeology.quake.FeltShaking;
import com.jeladastudios.ftsgeology.quake.PlayerBuilt;
import com.jeladastudios.ftsgeology.quake.QuakePlanner;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.Diagnostics;
import com.jeladastudios.ftsgeology.util.Loaded;
import com.jeladastudios.ftsgeology.util.TickBudget;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * Fissure eruptions, as on Iceland's Reykjanes peninsula in 2023-24, on the rifts between continents and over the hot
 * spots. For a day the ground over a magma body swells and a swarm of small quakes thickens under it. Then a dike of
 * magma runs out along the rift in a couple of minutes, the swarm racing along with it; the body drains and the ground
 * over it sinks, and over the dike a narrow graben drops a block or two, its edges cracking open (Grindavik, November
 * 2023). A few minutes on, a fissure some hundreds of blocks long unzips in a curtain of lava fountains and its lava
 * spreads over the plain, running down the low ground first. Within minutes the eruption draws into a few vents, which
 * build small cinder cones, open on their downhill side where the lava leaves; then it stops, and the lava sets into a
 * field of young basalt, a crust still glowing here and there.
 *
 * <p>Only near players and rarely; one at a time. The instruments read it as they read a volcano's unrest (the tilt,
 * the GPS's swelling, the seismograph's volcanic swarm). Lava goes on to open ground only, never over what a player
 * built; it sets fire to what it touches as any lava does.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class FissureEruptions {

    private FissureEruptions() {}

    enum Stage { UNREST, DIKE, PAUSE, CURTAIN, FOCUS, COOL }

    /** Ticks the dike takes to run its length, the lull after it, the curtain of fire, the vents' time, the cooling. */
    private static final int DIKE = 2400, PAUSE = 6000, CURTAIN = 3600, FOCUS = 9000, COOL = 6000;
    /** How often a natural one is rolled for, in ticks. */
    private static final int ROLL = 1200;
    /** How fast the fissure unzips along its segment, blocks a second each way. */
    private static final int UNZIP = 4;
    /** The share of the lava laid while the whole fissure is erupting; the rest comes from the vents. */
    private static final double CURTAIN_SHARE = 0.45;
    /** A cinder cone's crater radius, and how far its flank runs out per block of height. */
    private static final int CRATER = 2;
    private static final double CONE_SLOPE = 1.6;
    /** How far from the players a natural one breaks out, in blocks. */
    private static final int NEAR = 64, FAR = 240;

    private static final String NAME = "fts_geology_fissures";

    private static long begun, sunkColumns, cracked, laid, cooled, swarm;
    private static double largest, worstMs;

    // === One eruption ======================================================

    static final class Fissure {
        long id;
        int cx, cz;
        double sx, sz;
        /** The dike's half length, the eruptive segment along it, how far it has unzipped, where it broke first. */
        int half, seg0, seg1, open0, open1, start;
        /** The graben's half width; the magnitude the instruments read its unrest at. */
        int hw, magnitude;
        boolean hotspot, opened, bigQuake;
        Stage stage = Stage.UNREST;
        long stageAt, unrest;
        int lavaMost, lava;
        /** Every block of lava laid, the fissure's own trench blocks, the vents, and how far the cooling has got. */
        LongArrayList cells = new LongArrayList(), trench = new LongArrayList(), vents = new LongArrayList();
        int cool1, cool2;
        /** The chunks over the graben and those it has dropped in. */
        long[] band = new long[0];
        LongOpenHashSet sunk = new LongOpenHashSet();
        boolean done;

        // Not saved: rebuilt from the cells.
        transient PriorityQueue<Front> front;
        transient LongOpenHashSet lavaCols;
        transient double owed;
        /** Where the lava field's thickening has got to in its cells. */
        transient int riseAt;
        /** How far the trench has set, once the eruption has drawn into its vents. */
        transient int trenchAt = Integer.MAX_VALUE;
        /** Cones growing a block: by vent, the height, how far through its columns, and its breach in degrees. */
        transient it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> heaping = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        /** How far through a chunk the graben has got, where it is part way. */
        transient it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap grabenAt = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        /** The height each vent's cone was last heaped to. */
        transient it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap shaped = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        BlockPos centre() {
            return new BlockPos(cx, 0, cz);
        }

        int x(double a, double c) {
            return (int) Math.floor(cx + a * sx - c * sz + 0.5);
        }

        int z(double a, double c) {
            return (int) Math.floor(cz + a * sz + c * sx + 0.5);
        }

        double along(double x, double z) {
            return (x - cx) * sx + (z - cz) * sz;
        }

        double across(double x, double z) {
            return -(x - cx) * sz + (z - cz) * sx;
        }

        /** The fissure's line wanders a block or so either side, stepping in short segments. */
        double wobble(double a) {
            return 1.2 * Math.sin(a * 0.07 + (id & 7)) + 0.6 * Math.sin(a * 0.19 + (id >>> 3 & 7));
        }

        long ticks(Stage s) {
            return switch (s) {
                case UNREST -> unrest;
                case DIKE -> DIKE;
                case PAUSE -> PAUSE;
                case CURTAIN -> CURTAIN;
                case FOCUS -> FOCUS;
                case COOL -> COOL;
            };
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("id", id);
            t.putInt("cx", cx);
            t.putInt("cz", cz);
            t.putDouble("sx", sx);
            t.putDouble("sz", sz);
            t.putIntArray("seg", new int[]{half, seg0, seg1, open0, open1, start, hw, magnitude, lavaMost, lava, cool1, cool2});
            t.putBoolean("hotspot", hotspot);
            t.putBoolean("opened", opened);
            t.putBoolean("big", bigQuake);
            t.putString("stage", stage.name());
            t.putLong("stageAt", stageAt);
            t.putLong("unrest", unrest);
            t.put("cells", new LongArrayTag(cells.toLongArray()));
            t.put("trench", new LongArrayTag(trench.toLongArray()));
            t.put("vents", new LongArrayTag(vents.toLongArray()));
            t.put("band", new LongArrayTag(band));
            t.put("sunk", new LongArrayTag(sunk.toLongArray()));
            return t;
        }

        static Fissure load(CompoundTag t) {
            Fissure f = new Fissure();
            f.id = t.getLong("id");
            f.cx = t.getInt("cx");
            f.cz = t.getInt("cz");
            f.sx = t.getDouble("sx");
            f.sz = t.getDouble("sz");
            int[] s = t.getIntArray("seg");
            if (s.length >= 12) {
                f.half = s[0]; f.seg0 = s[1]; f.seg1 = s[2]; f.open0 = s[3]; f.open1 = s[4]; f.start = s[5];
                f.hw = s[6]; f.magnitude = s[7]; f.lavaMost = s[8]; f.lava = s[9]; f.cool1 = s[10]; f.cool2 = s[11];
            }
            f.hotspot = t.getBoolean("hotspot");
            f.opened = t.getBoolean("opened");
            f.bigQuake = t.getBoolean("big");
            try {
                f.stage = Stage.valueOf(t.getString("stage"));
            } catch (IllegalArgumentException e) {
                f.stage = Stage.COOL;
            }
            f.stageAt = t.getLong("stageAt");
            f.unrest = t.getLong("unrest");
            f.cells = new LongArrayList(t.getLongArray("cells"));
            f.trench = new LongArrayList(t.getLongArray("trench"));
            f.vents = new LongArrayList(t.getLongArray("vents"));
            f.band = t.getLongArray("band");
            f.sunk = new LongOpenHashSet(t.getLongArray("sunk"));
            return f;
        }
    }

    /** A column the lava could run into next: lowest ground first, so it runs downhill and pools before it climbs. */
    record Front(int priority, long column, int allowed) implements Comparable<Front> {
        @Override
        public int compareTo(Front o) {
            return Integer.compare(priority, o.priority);
        }
    }

    static final class Store extends SavedData {
        final List<Fissure> all = new ArrayList<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            ListTag list = tag.getList("fissures", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) s.all.add(Fissure.load(list.getCompound(i)));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Fissure f : all) list.add(f.save());
            tag.put("fissures", list);
            return tag;
        }
    }

    private static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store::load, Store::new, NAME);
    }

    // === Where ============================================================

    /** A place for one: its middle, the strike of its dike, and whether it is a hot spot's rather than a rift's. */
    public record Site(int x, int z, double sx, double sz, boolean hotspot) {}

    /**
     * A place for a fissure eruption between {@code near} and {@code far} blocks of a point: on the axis of a rift
     * between continents, or over a hot spot's live plume, on land. The nearest if {@code nearest}, else any. Null if
     * there is none.
     */
    public static Site site(ServerLevel level, int px, int pz, int near, int far, boolean nearest) {
        if (!GeologyWorld.isOwn(level)) return null;
        long seed = level.getSeed();
        GeologyParams gp = GeologyParams.current();
        List<int[]> axis = new ArrayList<>(), plume = new ArrayList<>();
        for (int dx = -far; dx <= far; dx += 16) {
            for (int dz = -far; dz <= far; dz += 16) {
                double d = Math.hypot(dx, dz);
                if (d < near || d > far) continue;
                int x = px + dx, z = pz + dz;
                if (TerrainFields.riftAxisDistance(seed, gp, x, z, 12) >= 0) {
                    axis.add(new int[]{x, z, (int) d});
                } else if (HotspotMap.sample(level, x, z).strength() >= 0.6) {
                    plume.add(new int[]{x, z, (int) d});
                }
            }
        }
        boolean hot = axis.isEmpty();
        List<int[]> pick = hot ? plume : axis;
        if (nearest) pick.sort((a, b) -> Integer.compare(a[2], b[2]));
        for (int tries = 0; tries < Math.min(12, pick.size()); tries++) {
            int[] c = nearest ? pick.get(tries) : pick.get(level.random.nextInt(pick.size()));
            int ground = level.getChunkSource().getGenerator().getBaseHeight(c[0], c[1], Heightmap.Types.WORLD_SURFACE_WG,
                    level, level.getChunkSource().randomState());
            if (ground <= level.getSeaLevel() + 1) continue;
            // A natural one does not break out within some fifty blocks of what people have built.
            if (!nearest && built(level, c[0], c[1], 3)) continue;
            double sx, sz;
            PlateSample s = TectonicMap.sampleCached(level, c[0], c[1]);
            if (!hot && s != null && Math.hypot(s.faultStrikeX(), s.faultStrikeZ()) > 1e-3) {
                double n = Math.hypot(s.faultStrikeX(), s.faultStrikeZ());
                sx = s.faultStrikeX() / n;
                sz = s.faultStrikeZ() / n;
            } else {
                double a = level.random.nextDouble() * Math.PI;
                sx = Math.cos(a);
                sz = Math.sin(a);
            }
            return new Site(c[0], c[1], sx, sz, hot);
        }
        return null;
    }

    /** Whether any loaded chunk within {@code r} chunks of a point holds blocks a player placed. */
    private static boolean built(ServerLevel level, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = (x >> 4) + dx, cz = (z >> 4) + dz;
                if (Loaded.chunk(level, cx, cz) && !PlayerBuilt.inChunk(level, cx, cz).isEmpty()) return true;
            }
        }
        return false;
    }

    /** Starts one at a site; its unrest lasts {@code unrestTicks}. */
    public static void begin(ServerLevel level, Site site, long unrestTicks) {
        long now = level.getGameTime();
        Fissure f = new Fissure();
        long h = mix(site.x() * 341873128712L ^ site.z() * 132897987541L ^ now);
        f.id = h;
        f.cx = site.x();
        f.cz = site.z();
        f.sx = site.sx();
        f.sz = site.sz();
        f.hotspot = site.hotspot();
        f.half = 90 + (int) (80 * unit(h, 1));
        int length = Math.max(60, Math.min(170, (int) (f.half * (0.6 + 0.4 * unit(h, 2)))));
        int shift = (int) ((unit(h, 3) - 0.5) * 0.4 * f.half);
        f.seg0 = Math.max(-f.half + 10, shift - length / 2);
        f.seg1 = Math.min(f.half - 10, f.seg0 + length);
        f.start = f.seg0 + (int) ((f.seg1 - f.seg0) * (0.2 + 0.6 * unit(h, 4)));
        f.open0 = f.open1 = f.start;
        f.hw = 5 + (int) (3 * unit(h, 5));
        f.magnitude = f.hotspot ? 5 : 4;
        f.lavaMost = (int) (GeyserConfig.FISSURE_LAVA.get() * (0.7 + 0.6 * unit(h, 6)));
        f.unrest = Math.max(200, unrestTicks);
        f.stageAt = now;
        LongOpenHashSet band = new LongOpenHashSet();
        for (int a = -f.half; a <= f.half; a += 4) {
            for (int c = -f.hw - 3; c <= f.hw + 3; c += 2) band.add(chunkKey(f.x(a, c) >> 4, f.z(a, c) >> 4));
        }
        f.band = band.toLongArray();
        Store st = store(level);
        st.all.add(f);
        st.setDirty();
        begun++;
        Diagnostics.info("fissure eruption on its way at {}, {} ({}): a dike {} blocks long along {}, {}; it breaks out over {}..{}; unrest {} s",
                f.cx, f.cz, f.hotspot ? "hot spot" : "rift", 2 * f.half, String.format(Locale.ROOT, "%.2f", f.sx),
                String.format(Locale.ROOT, "%.2f", f.sz), f.seg0, f.seg1, f.unrest / 20);
    }

    // === Ticking ==========================================================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        long now = level.getGameTime();
        Store st = level.getDataStorage().get(Store::load, NAME);
        if (now % ROLL == 0 && GeyserConfig.FISSURE_ERUPTIONS.get() && (st == null || st.all.isEmpty())) roll(level);
        if (st == null || st.all.isEmpty()) return;
        long t0 = System.nanoTime();
        TickBudget.open(event.getServer().getTickCount());
        long deadline = t0 + TickBudget.slice(0.15);
        PLAYER_BUILT.clear();
        for (Fissure f : st.all) {
            show(level, f, now);
            if (now % 20 == 0) step(level, f, now);
            work(level, f, deadline);
        }
        if (st.all.removeIf(f -> f.done)) Diagnostics.info("fissure eruption over; {}", summary());
        st.setDirty();
        worstMs = Math.max(worstMs, (System.nanoTime() - t0) / 1e6);
    }

    /** A natural one, now and then, near a player who is near a rift or a hot spot. */
    private static void roll(ServerLevel level) {
        if (!GeologyWorld.isOwn(level)) return;
        double chance = ROLL / (GeyserConfig.FISSURE_ERUPTION_DAYS.get() * 24000.0);
        for (ServerPlayer p : level.players()) {
            if (level.random.nextDouble() >= chance) continue;
            Site s = site(level, p.getBlockX(), p.getBlockZ(), NEAR, FAR, false);
            if (s == null) continue;
            begin(level, s, GeyserConfig.FISSURE_UNREST_TICKS.get());
            return;
        }
    }

    /** Once a second: the stage's own business, and on to the next when its time is up. */
    private static void step(ServerLevel level, Fissure f, long now) {
        // Past the unrest, which the instruments hear from afar, it goes on only while the ground where it breaks out
        // is loaded, as a volcano does: its clock held while nobody is near.
        if (f.stage != Stage.UNREST && !Loaded.at(level, f.x(f.start, 0), f.z(f.start, 0))) {
            f.stageAt += 20;
            return;
        }
        double p = Math.min(1.0, (now - f.stageAt) / (double) f.ticks(f.stage));
        BlockPos centre = f.centre();
        switch (f.stage) {
            case UNREST -> {
                VolcanoUnrest.hold(level, centre, f.magnitude, 0.15 + 0.85 * p, false);
                if (level.random.nextDouble() < 1.0 / 60.0 + (1.0 / 5.0 - 1.0 / 60.0) * p * p) {
                    quake(level, f, (level.random.nextDouble() - 0.5) * 0.5 * f.half, 0.8 + 1.4 * p + 0.7 * level.random.nextDouble(), p);
                }
            }
            case DIKE -> {
                // The body drains into the dike: the ground over it sinks back.
                VolcanoUnrest.hold(level, centre, f.magnitude, 0.05 + 0.9 * (1.0 - p), false);
                double tip = p * f.half;
                for (int i = 0; i < 2; i++) {
                    double a = (level.random.nextBoolean() ? 1 : -1) * Math.max(0.0, tip - 12 * level.random.nextDouble());
                    quake(level, f, a, 1.4 + 1.6 * level.random.nextDouble() * level.random.nextDouble(), 1.0);
                }
                if (!f.bigQuake && p >= 0.5) {
                    f.bigQuake = true;
                    double a = (level.random.nextBoolean() ? 1 : -1) * tip;
                    BlockPos at = new BlockPos(f.x(a, 0), level.getSeaLevel(), f.z(a, 0));
                    Earthquake.tremor(level, at, FaultType.DIVERGENT, 3.6 + 0.6 * level.random.nextDouble(), f.sx, f.sz, false);
                }
            }
            case PAUSE -> {
                VolcanoUnrest.hold(level, centre, f.magnitude, 0.1 + 0.6 * p, false);
                if (level.random.nextDouble() < 1.0 / 25.0) {
                    quake(level, f, f.seg0 + (f.seg1 - f.seg0) * level.random.nextDouble(), 0.8 + 1.2 * level.random.nextDouble(), 1.0);
                }
            }
            case CURTAIN -> {
                VolcanoUnrest.hold(level, centre, f.magnitude, 1.0, true);
                if (!f.opened) {
                    f.opened = true;
                    carve(level, f, f.start);
                    level.playSound(null, f.x(f.start, 0), level.getSeaLevel() + 40, f.z(f.start, 0),
                            SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 6.0f, 0.4f);
                    Diagnostics.info("fissure eruption at {}, {}: the fissure opens at {}, {}", f.cx, f.cz, f.x(f.start, 0), f.z(f.start, 0));
                }
                for (int i = 0; i < UNZIP; i++) {
                    if (f.open0 > f.seg0) carve(level, f, --f.open0);
                    if (f.open1 < f.seg1) carve(level, f, ++f.open1);
                }
                f.owed += f.lavaMost * CURTAIN_SHARE / (CURTAIN / 20.0);
                if ((now / 20) % 5 == 0) ModNetwork.shakeNear(level, f.x(f.start, 0), f.z(f.start, 0), 160, 0.2f, 30);
            }
            case FOCUS -> {
                VolcanoUnrest.hold(level, centre, f.magnitude, 0.8, true);
                if (f.vents.isEmpty()) focus(level, f);
                f.owed += f.lavaMost * (1.0 - CURTAIN_SHARE) * 2.0 * (1.0 - p) / (FOCUS / 20.0);
                cones(level, f, p);
            }
            case COOL -> VolcanoUnrest.hold(level, centre, f.magnitude, 0.3 * (1.0 - p), false);
        }
        if (p < 1.0) return;
        if (f.stage == Stage.COOL) {
            if (f.cool2 >= f.cells.size() && f.cool1 >= f.cells.size()) {
                f.done = true;
                VolcanoUnrest.settle(centre);
            }
            return;
        }
        Stage next = Stage.values()[f.stage.ordinal() + 1];
        Diagnostics.info("fissure eruption at {}, {}: {} -> {} (lava {} of {}, graben chunks {} of {})", f.cx, f.cz, f.stage,
                next, f.lava, f.lavaMost, f.sunk.size(), f.band.length);
        if (next == Stage.COOL) f.owed = 0;
        f.stage = next;
        f.stageAt = now;
    }

    /** Every tick, within the mod's budget: the graben, the lava owed, the cooling. */
    private static void work(ServerLevel level, Fissure f, long deadline) {
        if (f.stage != Stage.UNREST && !Loaded.at(level, f.x(f.start, 0), f.z(f.start, 0))) return;
        // The graben drops where the dike has got to, a slice of a chunk at a time while the budget lasts: in a big pack
        // a whole chunk's columns took some fifty milliseconds.
        if (f.stage != Stage.UNREST && f.stage != Stage.COOL && f.sunk.size() < f.band.length) {
            double tip = f.stage == Stage.DIKE ? (level.getGameTime() - f.stageAt) / (double) DIKE * f.half : f.half;
            for (long k : f.band) {
                if (System.nanoTime() >= deadline) break;
                if (f.sunk.contains(k)) continue;
                int ccx = (int) (k >> 32), ccz = (int) k;
                double a = Math.abs(f.along(ccx * 16 + 8, ccz * 16 + 8)) - 11;
                if (a > tip || !Loaded.chunk(level, ccx, ccz)) continue;
                int from = f.grabenAt.getOrDefault(k, 0);
                while (from < 256 && System.nanoTime() < deadline) {
                    graben(level, f, ccx, ccz, from, Math.min(256, from + SLICE));
                    from += SLICE;
                }
                if (from >= 256) {
                    f.sunk.add(k);
                    f.grabenAt.remove(k);
                } else {
                    f.grabenAt.put(k, from);
                }
                break;
            }
        }
        if (f.owed >= 1 && (f.stage == Stage.CURTAIN || f.stage == Stage.FOCUS)) {
            front(level, f);
            while (f.owed >= 1 && f.lava < f.lavaMost && System.nanoTime() < deadline) {
                // With nowhere lower to run, the flow thickens where it lies until it spills over what holds it.
                if (f.front.isEmpty()) {
                    if (!rise(level, f)) break;
                    continue;
                }
                if (spill(level, f, f.front.poll())) f.owed--;
            }
            // Never more than a few seconds' lava owed: what could not be laid is lost.
            f.owed = f.lava >= f.lavaMost ? 0 : Math.min(f.owed, 200);
        }
        if (f.stage == Stage.FOCUS) {
            setTrench(level, f, deadline);
            heap(level, f, deadline);
        }
        if (f.stage == Stage.COOL) cool(level, f, deadline);
    }

    // === The swarm ========================================================

    /** One quake of the swarm, {@code along} blocks along the dike from its middle, filed as volcanic and felt nearby. */
    private static void quake(ServerLevel level, Fissure f, double along, double m, double rising) {
        double depthM = 1000.0 + (5000.0 - 3000.0 * rising) * level.random.nextDouble();
        double c = (level.random.nextDouble() - 0.5) * 2 * (f.hw + 2);
        int x = f.x(along, c), z = f.z(along, c);
        int y = level.getSeaLevel() - (int) Math.round(depthM / DepthScale.metresPerBlock());
        BlockPos at = new BlockPos(x, Math.max(level.getMinBuildHeight(), y), z);
        SeismicNetwork.recordVolcanic(level, at, m, depthM);
        FeltShaking.start(level, at, List.of(new QuakePlanner.TracePoint(x, z, f.sx, f.sz, 0.0)), m, depthM, level.getGameTime());
        swarm++;
        largest = Math.max(largest, m);
    }

    // === The graben =======================================================

    /** The columns of a chunk's graben dropped at a go. */
    private static final int SLICE = 32;

    /**
     * Drops the graben over a slice of one chunk, its columns {@code from} to {@code to} (sixteen to a row): a block, two
     * in its middle, the edges cracked open in short steps.
     */
    private static void graben(ServerLevel level, Fissure f, int ccx, int ccz, int from, int to) {
        LongSet built = PlayerBuilt.inChunk(level, ccx, ccz);
        for (int i = from; i < to; i++) {
            int x = ccx * 16 + (i >> 4), z = ccz * 16 + (i & 15);
            double a = f.along(x + 0.5, z + 0.5), c = Math.abs(f.across(x + 0.5, z + 0.5));
            if (Math.abs(a) > f.half) continue;
            double end = Math.abs(a) / f.half;
            // Narrowing to nothing at the dike's ends, and its edges ragged by a block.
            double w = f.hw * Math.sqrt(Math.max(0.0, 1.0 - end * end * end * end)) + (unit(mix(x * 31L + z), 7) - 0.5) * 1.4;
            if (w < 1.0 || c > w + 0.5) continue;
            if (c > w - 0.5) {
                // The bounding faults open as cracks, in steps, two of every three.
                if (Math.floorMod((int) Math.floor((a + (f.id & 15)) / 9.0), 3) != 2) {
                    crack(level, built, x, z, 2 + (int) (4 * unit(mix(x * 7L + z * 13L), 8)));
                }
                continue;
            }
            int k = c < w - 3 && end < 0.5 ? 2 : 1;
            if (sink(level, built, x, z, k)) sunkColumns++;
        }
    }

    /** Lowers a column of natural ground by {@code k} blocks, with what grows on it; false where it is built on or wet. */
    private static boolean sink(ServerLevel level, LongSet built, int x, int z, int k) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = g - k; y <= g; y++) {
            if (!natural(level, built, m.set(x, y, z))) return false;
        }
        int cover = 0;
        for (int y = g + 1; y <= g + 3; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) break;
            if (!TerrainProbe.isVegetation(s) || TerrainProbe.isTreePart(s) || y == g + 3) return false;
            cover++;
        }
        // Bottom up, so nothing is left hanging.
        for (int y = g - k; y <= g + cover; y++) {
            level.setBlock(m.set(x, y, z), level.getBlockState(new BlockPos(x, y + k, z)), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (int y = g + cover + 1; y <= g + cover + k; y++) {
            level.setBlock(m.set(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        return true;
    }

    /** Opens a crack a block wide and {@code depth} deep in natural ground. */
    private static void crack(ServerLevel level, LongSet built, int x, int z, int depth) {
        if (!Loaded.around(level, new BlockPos(x, 0, z))) return;
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = g - depth + 1; y <= g; y++) {
            if (!natural(level, built, m.set(x, y, z))) return;
        }
        BlockState over = level.getBlockState(m.set(x, g + 1, z));
        if (!over.isAir() && !(TerrainProbe.isVegetation(over) && !TerrainProbe.isTreePart(over))) return;
        if (!over.isAir()) level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (int y = g; y > g - depth; y--) level.setBlock(m.set(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        cracked++;
    }

    private static final Long2ObjectOpenHashMap<LongSet> PLAYER_BUILT = new Long2ObjectOpenHashMap<>();

    /** Ground nobody built: not placed by a player, holding nothing, not bedrock, no liquid in it. */
    private static boolean natural(ServerLevel level, LongSet built, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.hasBlockEntity() || !s.getFluidState().isEmpty() || s.is(Blocks.BEDROCK)
                || EruptionHandler.isPlayerPlaced(s)) return false;
        if (built == null) built = PLAYER_BUILT.computeIfAbsent(chunkKey(p.getX() >> 4, p.getZ() >> 4),
                k -> PlayerBuilt.inChunk(level, p.getX() >> 4, p.getZ() >> 4));
        return !built.contains(p.asLong());
    }

    // === The lava =========================================================

    /** The ground under a column's open air: down past lava, plants and snow; MIN where water stands on it. */
    private static int ground(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        for (int i = 0; i < 10; i++) {
            BlockState s = level.getBlockState(m);
            if (s.getFluidState().is(FluidTags.LAVA) || s.isAir() || s.is(Blocks.SNOW)
                    || s.canBeReplaced() && s.getFluidState().isEmpty()) {
                m.move(0, -1, 0);
                continue;
            }
            return s.getFluidState().isEmpty() ? m.getY() : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** Whether lava may take a block's place: open air, a plant, snow, or lava running loose. */
    private static boolean takes(BlockState s) {
        if (s.isAir() || s.is(Blocks.SNOW)) return true;
        if (s.getFluidState().is(FluidTags.LAVA)) return !s.getFluidState().isSource();
        return s.canBeReplaced() && s.getFluidState().isEmpty() && !s.hasBlockEntity();
    }

    private static BlockState lava(ServerLevel level, BlockPos p) {
        return TfcCompat.translate(level, p, Blocks.LAVA.defaultBlockState());
    }

    /** The columns the lava could take next, rebuilt after a restart from what it has laid. */
    private static void front(ServerLevel level, Fissure f) {
        if (f.front != null) return;
        f.front = new PriorityQueue<>();
        f.lavaCols = new LongOpenHashSet();
        for (long k : f.cells) f.lavaCols.add(column(BlockPos.getX(k), BlockPos.getZ(k)));
        for (long k : f.cells) {
            BlockPos p = BlockPos.of(k);
            if (level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) offer(level, f, p.getX(), p.getZ(), p.getY());
        }
    }

    /** Offers the four columns round one the lava stands in, with its surface at {@code surface}. */
    private static void offer(ServerLevel level, Fissure f, int x, int z, int surface) {
        for (int i = 0; i < 4; i++) {
            int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0), nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
            long col = column(nx, nz);
            if (f.lavaCols.contains(col) || !Loaded.at(level, nx, nz)) continue;
            int g = ground(level, nx, nz);
            if (g == Integer.MIN_VALUE || g + 1 > surface) continue;
            f.front.add(new Front((g + 1) * 16 + (int) (16 * unit(mix(nx * 73428767L ^ nz), 9)), col, surface));
        }
    }

    /** Lays lava in the column a front names, if it still can; true if it did. */
    private static boolean spill(ServerLevel level, Fissure f, Front fr) {
        long col = fr.column();
        if (f.lavaCols.contains(col)) return false;
        int x = (int) (col >> 32), z = (int) col;
        // A block set at a chunk's edge updates the next chunk: that must be in too, or the server waits for it.
        if (!Loaded.around(level, new BlockPos(x, 0, z))) return false;
        int g = ground(level, x, z);
        if (g == Integer.MIN_VALUE || g + 1 > fr.allowed()) return false;
        BlockPos p = new BlockPos(x, g + 1, z);
        if (!takes(level.getBlockState(p)) || !natural(level, null, p.below())) return false;
        level.setBlock(p, lava(level, p), Block.UPDATE_ALL);
        f.cells.add(p.asLong());
        f.lavaCols.add(col);
        f.lava++;
        laid++;
        offer(level, f, x, z, g + 1);
        return true;
    }

    /**
     * Thickens the flow by a block where it is thinnest, the oldest of it first (it lies nearest the fissure): a block
     * of lava on a block of lava no more than three deep, its neighbours then offered at the new height. False if none
     * of the next few dozen can.
     */
    private static boolean rise(ServerLevel level, Fissure f) {
        int n = f.cells.size();
        for (int tries = 0; tries < Math.min(64, n); tries++) {
            if (f.riseAt >= n) f.riseAt = 0;
            BlockPos p = BlockPos.of(f.cells.getLong(f.riseAt++));
            if (!Loaded.around(level, p) || !level.getBlockState(p).getFluidState().isSource()
                    || !level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) continue;
            BlockPos up = p.above();
            BlockState over = level.getBlockState(up);
            if (!takes(over)) continue;
            int deep = 1;
            while (deep < 4 && level.getBlockState(p.below(deep)).getFluidState().is(FluidTags.LAVA)) deep++;
            if (deep >= 3 || lowerBeside(level, p)) continue;
            level.setBlock(up, lava(level, up), Block.UPDATE_ALL);
            f.cells.add(up.asLong());
            f.lava++;
            f.owed--;
            laid++;
            offer(level, f, up.getX(), up.getZ(), up.getY());
            return true;
        }
        return false;
    }

    /** Whether lava beside a block of lava stands lower than it: then that rises first, and the pool stays level. */
    private static boolean lowerBeside(ServerLevel level, BlockPos p) {
        for (int i = 0; i < 4; i++) {
            int nx = p.getX() + (i == 0 ? 1 : i == 1 ? -1 : 0), nz = p.getZ() + (i == 2 ? 1 : i == 3 ? -1 : 0);
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1;
            if (top < p.getY() && level.getBlockState(new BlockPos(nx, top, nz)).getFluidState().is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** Opens the fissure at a point along it: a trench of lava, which overflows on to the ground round it. */
    private static void carve(ServerLevel level, Fissure f, int a) {
        double c = f.wobble(a);
        int x = f.x(a, c), z = f.z(a, c);
        if (!Loaded.around(level, new BlockPos(x, 0, z))) return;
        front(level, f);
        int g = ground(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int y = g - 2; y <= g; y++) {
            if (!natural(level, null, m.set(x, y, z))) continue;
            BlockPos p = m.immutable();
            level.setBlock(p, lava(level, p), Block.UPDATE_ALL);
            f.cells.add(p.asLong());
            f.trench.add(p.asLong());
            any = true;
        }
        BlockPos over = new BlockPos(x, g + 1, z);
        if (takes(level.getBlockState(over))) level.setBlock(over, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (!any) return;
        f.lavaCols.add(column(x, z));
        // The fountains throw it over the rims and a block up.
        offer(level, f, x, z, g + 2);
    }

    // === The vents and their cones ========================================

    /** The eruption draws into a few vents along the fissure; the rest of it sets, its rims left as spatter ramparts. */
    private static void focus(ServerLevel level, Fissure f) {
        int n = 2 + (int) (3 * unit(f.id, 10));
        int lo = f.open0, hi = f.open1;
        List<Integer> picked = new ArrayList<>();
        for (int tries = 0; tries < 40 && picked.size() < n && hi > lo; tries++) {
            int a = lo + level.random.nextInt(hi - lo + 1);
            boolean apart = true;
            for (int b : picked) if (Math.abs(a - b) < 18) apart = false;
            if (apart) picked.add(a);
        }
        for (int a : picked) {
            int x = f.x(a, f.wobble(a)), z = f.z(a, f.wobble(a));
            if (!Loaded.at(level, x, z)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            f.vents.add(BlockPos.asLong(x, top, z));
        }
        // None of them where the ground is loaded: tried again a second on.
        if (f.vents.isEmpty()) return;
        // A fresh front: from now on the lava comes from the vents only. The trench away from them sets (see setTrench).
        f.front = new PriorityQueue<>();
        f.trenchAt = 0;
        Diagnostics.info("fissure eruption at {}, {}: drawn into {} vents", f.cx, f.cz, f.vents.size());
    }

    /** The trench away from the vents sets, crust over basalt, its rims heaped with spatter; a few blocks a tick. */
    private static void setTrench(ServerLevel level, Fissure f, long deadline) {
        while (f.trenchAt < f.trench.size() && System.nanoTime() < deadline) {
            BlockPos p = BlockPos.of(f.trench.getLong(f.trenchAt++));
            if (!Loaded.around(level, p)) {
                // Waits for its ground.
                f.trenchAt--;
                return;
            }
            if (nearVent(f, p.getX(), p.getZ(), 6)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().is(FluidTags.LAVA)) continue;
            boolean top = !level.getBlockState(p.above()).getFluidState().is(FluidTags.LAVA);
            level.setBlock(p, top ? ModBlocks.COOLING_LAVA_CRUST.get().defaultBlockState() : Blocks.BASALT.defaultBlockState(), Block.UPDATE_ALL);
            // Spatter heaped a block high along its sides.
            if (top) {
                for (int side = -1; side <= 1; side += 2) {
                    int rx = p.getX() + (int) Math.round(-f.sz * side * 1.5), rz = p.getZ() + (int) Math.round(f.sx * side * 1.5);
                    if (!Loaded.at(level, rx, rz)) continue;
                    int g = ground(level, rx, rz);
                    if (g == Integer.MIN_VALUE || g > p.getY() + 1) continue;
                    BlockPos r = new BlockPos(rx, g + 1, rz);
                    if (Loaded.around(level, r) && takes(level.getBlockState(r)) && !level.getBlockState(r).getFluidState().is(FluidTags.LAVA)
                            && natural(level, null, r.below())) level.setBlock(r, scoria(level, false), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static boolean nearVent(Fissure f, int x, int z, int r) {
        for (long v : f.vents) {
            if (Math.abs(BlockPos.getX(v) - x) <= r && Math.abs(BlockPos.getZ(v) - z) <= r) return true;
        }
        return false;
    }

    /** Once a second: each vent's cone a little higher, its crater kept full and its lava sent out through the breach. */
    private static void cones(ServerLevel level, Fissure f, double p) {
        front(level, f);
        for (long v : f.vents) {
            int vx = BlockPos.getX(v), vy = BlockPos.getY(v), vz = BlockPos.getZ(v);
            if (!Loaded.at(level, vx, vz)) continue;
            long h = mix(v ^ f.id);
            double most = 3 + 5 * unit(h, 11);
            double height = most * Math.min(1.0, Math.sqrt(1.3 * p));
            // The crater: a pool of lava at the old ground, the fountain's foot.
            for (int dx = -CRATER; dx <= CRATER; dx++) {
                for (int dz = -CRATER; dz <= CRATER; dz++) {
                    if (Math.hypot(dx, dz) > CRATER + 0.5) continue;
                    int x = vx + dx, z = vz + dz;
                    BlockPos q = new BlockPos(x, vy, z);
                    BlockState s = level.getBlockState(q);
                    if (!s.getFluidState().isSource() && (takes(s) || natural(level, null, q)) && natural(level, null, q.below())
                            && Loaded.around(level, q)) {
                        level.setBlock(q, lava(level, q), Block.UPDATE_ALL);
                        f.cells.add(q.asLong());
                        f.lavaCols.add(column(x, z));
                    }
                    if (level.getBlockState(q).getFluidState().is(FluidTags.LAVA)) offer(level, f, x, z, vy + 2);
                }
            }
            // The cone, heaped a block higher each time it has grown one: set going here, laid in work() a few
            // columns at a time.
            int step = (int) Math.round(height);
            if (step <= f.shaped.getOrDefault(v, 0) || f.heaping.containsKey(v)) continue;
            f.heaping.put(v, new int[]{step, 0, (int) Math.round(Math.toDegrees(breach(level, vx, vz, vy)))});
        }
    }

    /** Lays the cones' growth set going in {@link #cones}, column by column while the budget lasts. */
    private static void heap(ServerLevel level, Fissure f, long deadline) {
        for (var it = f.heaping.long2ObjectEntrySet().iterator(); it.hasNext() && System.nanoTime() < deadline; ) {
            var e = it.next();
            long v = e.getLongKey();
            int[] job = e.getValue();
            int vx = BlockPos.getX(v), vy = BlockPos.getY(v), vz = BlockPos.getZ(v), step = job[0];
            if (!Loaded.at(level, vx, vz)) continue;
            double breach = Math.toRadians(job[2]);
            int reach = CRATER + (int) Math.ceil(CONE_SLOPE * step) + 1, side = 2 * reach + 1;
            long h = mix(v ^ f.id);
            while (job[1] < side * side && System.nanoTime() < deadline) {
                int i = job[1]++, dx = i / side - reach, dz = i % side - reach;
                double d = Math.hypot(dx, dz);
                if (d <= CRATER + 0.5) continue;
                double ang = Math.abs(Math.IEEEremainder(Math.atan2(dz, dx) - breach, 2 * Math.PI));
                if (ang < 0.45) continue;
                int target = vy + (int) Math.round(step - (d - CRATER) / CONE_SLOPE);
                if (target <= vy) continue;
                build(level, vx + dx, vz + dz, target, d <= CRATER + 1.6, h);
            }
            if (job[1] >= side * side) {
                f.shaped.put(v, step);
                it.remove();
            }
        }
    }

    /** The way down from a vent, radians: where the ground eight blocks out lies lowest. */
    private static double breach(ServerLevel level, int vx, int vz, int vy) {
        double best = 0;
        int low = Integer.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int x = vx + (int) Math.round(8 * Math.cos(a)), z = vz + (int) Math.round(8 * Math.sin(a));
            if (!Loaded.at(level, x, z)) continue;
            int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (g < low) {
                low = g;
                best = a;
            }
        }
        return best;
    }

    /** Heaps scoria on a column up to {@code target}, over its own lava, never on what was built. */
    private static void build(ServerLevel level, int x, int z, int target, boolean rim, long h) {
        if (!Loaded.around(level, new BlockPos(x, 0, z))) return;
        int g = ground(level, x, z);
        if (g == Integer.MIN_VALUE || g >= target) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, g, z);
        if (!natural(level, null, m) && !level.getBlockState(m).is(ModBlocks.COOLING_LAVA_CRUST.get())) return;
        for (int y = g + 1; y <= target; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (!takes(s) && !s.getFluidState().is(FluidTags.LAVA)) return;
            level.setBlock(m, scoria(level, rim && y == target), Block.UPDATE_ALL);
        }
    }

    /** Red and black scoria, the odd lump of basalt; glowing near a crater's lip. */
    private static BlockState scoria(ServerLevel level, boolean lip) {
        int r = level.random.nextInt(20);
        if (lip && r < 6) return Blocks.MAGMA_BLOCK.defaultBlockState();
        return (r < 8 ? Blocks.RED_TERRACOTTA : r < 15 ? Blocks.BLACKSTONE : r < 18 ? Blocks.BROWN_TERRACOTTA : Blocks.BASALT)
                .defaultBlockState();
    }

    // === Cooling ==========================================================

    /**
     * The lava sets: first all of it into a crust (glowing still, over basalt where it lies deep), then most of the
     * crust on into young basalt.
     */
    private static void cool(ServerLevel level, Fissure f, long deadline) {
        boolean second = level.getGameTime() - f.stageAt >= COOL / 2;
        // Once its time is up, lava where the ground is not loaded is left to lie.
        boolean late = level.getGameTime() - f.stageAt >= COOL;
        while (System.nanoTime() < deadline) {
            if (f.cool1 < f.cells.size()) {
                BlockPos p = BlockPos.of(f.cells.getLong(f.cool1++));
                if (!Loaded.around(level, p)) {
                    if (late) continue;
                    // Waits for its ground to be loaded.
                    f.cool1--;
                    return;
                }
                BlockState s = level.getBlockState(p);
                if (!s.getFluidState().is(FluidTags.LAVA)) continue;
                boolean top = !level.getBlockState(p.above()).getFluidState().is(FluidTags.LAVA);
                level.setBlock(p, top ? ModBlocks.COOLING_LAVA_CRUST.get().defaultBlockState() : Blocks.BASALT.defaultBlockState(), Block.UPDATE_ALL);
                cooled++;
            } else if (second && f.cool2 < f.cells.size()) {
                BlockPos p = BlockPos.of(f.cells.getLong(f.cool2++));
                if (!Loaded.around(level, p)) {
                    if (late) continue;
                    f.cool2--;
                    return;
                }
                if (!level.getBlockState(p).is(ModBlocks.COOLING_LAVA_CRUST.get())) continue;
                int r = level.random.nextInt(20);
                if (r < 3) continue;
                level.setBlock(p, (r < 13 ? Blocks.BASALT : r < 18 ? Blocks.SMOOTH_BASALT : Blocks.BLACKSTONE).defaultBlockState(),
                        Block.UPDATE_ALL);
            } else {
                return;
            }
        }
    }

    // === What is seen and heard ===========================================

    /** Every tick: the fountains along the open fissure, then over the vents; their roar. */
    private static void show(ServerLevel level, Fissure f, long now) {
        if (f.stage != Stage.CURTAIN && f.stage != Stage.FOCUS) return;
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (Math.abs(f.along(p.getX(), p.getZ())) < f.half + 320 && Math.abs(f.across(p.getX(), p.getZ())) < 320) near.add(p);
        }
        if (near.isEmpty()) return;
        if (f.stage == Stage.CURTAIN) {
            if (f.open1 <= f.open0) return;
            for (int i = 0; i < 12; i++) {
                int a = f.open0 + level.random.nextInt(f.open1 - f.open0 + 1);
                double c = f.wobble(a);
                int x = f.x(a, c), z = f.z(a, c);
                if (!Loaded.at(level, x, z)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double tall = 4 + 7 * level.random.nextDouble();
                // A wall of fire: thick flame up the fountain, spatter flung out of it.
                send(level, near, ParticleTypes.FLAME, x + 0.5, y + tall * 0.5, z + 0.5, 12, 0.35, tall * 0.35, 0.35, 0.03);
                send(level, near, ParticleTypes.LAVA, x + 0.5, y + 0.5 + tall * 0.3, z + 0.5, 4, 0.5, tall * 0.2, 0.5, 0.0);
                // The plume of gas and steam over it, seen from far off.
                if (i < 2 && now % 2 == 0) send(level, near, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x + 0.5, y + tall + 1, z + 0.5, 1, 1.2, 0.5, 1.2, 0.01);
                if (i == 0 && now % 4 == 0) send(level, near, ParticleTypes.LARGE_SMOKE, x + 0.5, y + tall + 2, z + 0.5, 4, 1.5, 1.0, 1.5, 0.02);
                if (i == 0 && now % 20 == 0) level.playSound(null, x, y, z, SoundEvents.LAVA_AMBIENT, SoundSource.BLOCKS, 4.0f, 0.5f);
                if (i == 1 && now % 40 == 0) level.playSound(null, x, y, z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 4.0f, 0.4f);
            }
            return;
        }
        double p = Math.min(1.0, (now - f.stageAt) / (double) FOCUS);
        for (long v : f.vents) {
            int x = BlockPos.getX(v), y = BlockPos.getY(v), z = BlockPos.getZ(v);
            if (!Loaded.at(level, x, z)) continue;
            double tall = (2 + 6 * level.random.nextDouble()) * (1.0 - 0.6 * p);
            send(level, near, ParticleTypes.FLAME, x + 0.5, y + 1 + tall * 0.5, z + 0.5, 10, 0.6, tall * 0.35, 0.6, 0.03);
            if (level.random.nextInt(12) == 0) {
                send(level, near, ParticleTypes.LAVA, x + 0.5, y + 1, z + 0.5, 14, 0.8, 0.4, 0.8, 0.0);
                level.playSound(null, x, y, z, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 3.0f, 0.6f);
            }
            if (now % 6 == 0) send(level, near, ParticleTypes.LARGE_SMOKE, x + 0.5, y + tall + 3, z + 0.5, 3, 1.0, 1.0, 1.0, 0.02);
            if (now % 5 == 0) send(level, near, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x + 0.5, y + tall + 1, z + 0.5, 1, 0.8, 0.5, 0.8, 0.01);
        }
    }

    private static void send(ServerLevel level, List<ServerPlayer> to, ParticleOptions type, double x, double y, double z,
                             int count, double dx, double dy, double dz, double speed) {
        for (ServerPlayer p : to) level.sendParticles(p, type, true, x, y, z, count, dx, dy, dz, speed);
    }

    // === Commands =========================================================

    /** Starts one near a point (the nearest place for one, or right here); a message for the command. */
    public static String start(ServerLevel level, BlockPos at, boolean here, long unrestTicks) {
        Store st = store(level);
        if (!st.all.isEmpty()) return "a fissure eruption is already under way";
        Site s;
        if (here) {
            PlateSample ps = TectonicMap.sampleCached(level, at.getX(), at.getZ());
            double n = ps == null ? 0 : Math.hypot(ps.faultStrikeX(), ps.faultStrikeZ());
            double a = level.random.nextDouble() * Math.PI;
            s = n > 1e-3 ? new Site(at.getX(), at.getZ(), ps.faultStrikeX() / n, ps.faultStrikeZ() / n, false)
                    : new Site(at.getX(), at.getZ(), Math.cos(a), Math.sin(a), false);
        } else {
            s = site(level, at.getX(), at.getZ(), 24, 480, true);
            if (s == null) return "no rift axis or hot spot on land within 480 blocks";
        }
        begin(level, s, unrestTicks);
        return String.format(Locale.ROOT, "fissure eruption on its way at %d, %d (%s), along %.2f, %.2f", s.x(), s.z(),
                s.hotspot() ? "hot spot" : "rift", s.sx(), s.sz());
    }

    /** Moves the eruption under way on to its next stage at once. */
    public static String next(ServerLevel level) {
        Store st = level.getDataStorage().get(Store::load, NAME);
        if (st == null || st.all.isEmpty()) return "no fissure eruption under way";
        for (Fissure f : st.all) f.stageAt = level.getGameTime() - f.ticks(f.stage) + 20;
        st.setDirty();
        return "on to the next stage";
    }

    public static String info(ServerLevel level) {
        Store st = level.getDataStorage().get(Store::load, NAME);
        if (st == null || st.all.isEmpty()) return "no fissure eruption under way; " + summary();
        StringBuilder b = new StringBuilder();
        for (Fissure f : st.all) {
            double p = Math.min(1.0, (level.getGameTime() - f.stageAt) / (double) f.ticks(f.stage));
            b.append(String.format(Locale.ROOT, "fissure at %d, %d: %s %.0f%%, open %d..%d of %d..%d, lava %d of %d, vents %d, graben chunks %d of %d; ",
                    f.cx, f.cz, f.stage, 100 * p, f.open0, f.open1, f.seg0, f.seg1, f.lava, f.lavaMost, f.vents.size(),
                    f.sunk.size(), f.band.length));
            if (!f.cells.isEmpty()) {
                int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
                for (long k : f.cells) {
                    x0 = Math.min(x0, BlockPos.getX(k)); x1 = Math.max(x1, BlockPos.getX(k));
                    y0 = Math.min(y0, BlockPos.getY(k)); y1 = Math.max(y1, BlockPos.getY(k));
                    z0 = Math.min(z0, BlockPos.getZ(k)); z1 = Math.max(z1, BlockPos.getZ(k));
                }
                b.append(String.format(Locale.ROOT, "lava over %d..%d, %d..%d, %d..%d; ", x0, x1, y0, y1, z0, z1));
            }
            for (long v : f.vents) {
                b.append(String.format(Locale.ROOT, "vent %d %d %d cone %d; ", BlockPos.getX(v), BlockPos.getY(v), BlockPos.getZ(v),
                        f.shaped.getOrDefault(v, 0)));
            }
        }
        return b + summary();
    }

    /**
     * Puts a player where they can watch the eruption under way: {@code across} blocks to the side of where it broke
     * out (of its first vent, once it has them) and {@code up} over it, looking at it.
     */
    public static String watch(ServerLevel level, ServerPlayer player, int across, int up) {
        Store st = level.getDataStorage().get(Store::load, NAME);
        if (st == null || st.all.isEmpty()) return "no fissure eruption under way";
        Fissure f = st.all.get(0);
        int tx, tz;
        if (!f.vents.isEmpty()) {
            tx = BlockPos.getX(f.vents.getLong(0));
            tz = BlockPos.getZ(f.vents.getLong(0));
        } else {
            tx = f.x(f.start, 0);
            tz = f.z(f.start, 0);
        }
        int ty = Loaded.at(level, tx, tz) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz)
                : level.getChunkSource().getGenerator().getBaseHeight(tx, tz, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
        double x = tx + 0.5 - across * f.sz, z = tz + 0.5 + across * f.sx, y = ty + up;
        double dx = tx + 0.5 - x, dz = tz + 0.5 - z, dy = ty - (y + 1.62);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleportTo(level, x, y, z, yaw, pitch);
        return String.format(Locale.ROOT, "watching the fissure at %d, %d, %d from %.0f, %.0f, %.0f", tx, ty, tz, x, y, z);
    }

    public static String stop(ServerLevel level) {
        Store st = level.getDataStorage().get(Store::load, NAME);
        if (st == null || st.all.isEmpty()) return "no fissure eruption under way";
        for (Fissure f : st.all) VolcanoUnrest.settle(f.centre());
        st.all.clear();
        st.setDirty();
        return "stopped";
    }

    public static String summary() {
        return String.format(Locale.ROOT, "fissure eruptions: %d begun, %d swarm quakes (largest M%.1f), %d columns dropped, %d cracks, %d lava laid, %d set; worst tick %.1f ms",
                begun, swarm, largest, sunkColumns, cracked, laid, cooled, worstMs);
    }

    public static boolean any() {
        return begun > 0 || laid > 0 || swarm > 0;
    }

    public static void clear() {
        begun = sunkColumns = cracked = laid = cooled = swarm = 0;
        largest = worstMs = 0;
        PLAYER_BUILT.clear();
    }

    // === Small helpers ====================================================

    private static long chunkKey(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    private static long column(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ h >>> 33;
    }

    private static double unit(long h, int salt) {
        return (mix(h + salt * 0x9E3779B97F4A7C15L) >>> 11) / (double) (1L << 53);
    }
}
