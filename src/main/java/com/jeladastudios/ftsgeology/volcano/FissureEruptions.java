package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.block.BasaltLayerBlock;
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
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
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

    private static long begun, sunkColumns, cracked, laid, cooled, swarm, swept, sheets, sheetLayers, films, quenched, hissed, reopened, blasts, ringBlocks;
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
        /** Ticks it has been held, its ground not loaded, since it last went on. */
        int held;
        /** Every block of lava laid, the fissure's own trench blocks, the vents, and how far the cooling has got. */
        LongArrayList cells = new LongArrayList(), trench = new LongArrayList(), vents = new LongArrayList();
        int cool1, cool2;
        /** The chunks over the graben and those it has dropped in. */
        long[] band = new long[0];
        LongOpenHashSet sunk = new LongOpenHashSet();
        boolean done;
        /** Where the fissure ran under water (at the water's top): steam blasts there, not lava. */
        LongArrayList wet = new LongArrayList();
        /** Where the lava met water at its edge and was chilled into a crust. */
        LongArrayList shore = new LongArrayList();
        /** The water the flow came up against, by column: its top, its bed and what it was, to be opened again. */
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap water = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        /** Whether the tuff rings round the steam blasts are built. */
        boolean ringed;
        /** How far the water has been opened again, once the lava has all set. */
        transient int restoreAt;
        transient boolean restored;
        /** The chunks round the eruptive segment whose water is noted before the lava comes, and how far that has got. */
        transient long[] shoreBand;
        transient int shoreAt;
        /** The noted water's columns as the watch on them last took them, and how far through it is. */
        transient long[] guardKeys;
        transient int guardAt;

        // Not saved: rebuilt from the cells.
        transient PriorityQueue<Front> front;
        transient LongOpenHashSet lavaCols;
        transient double owed;
        /** Where the lava field's thickening has got to in its cells. */
        transient int riseAt;
        /** The last sweep of the field's box for lava left lying: the box, how far through, which pass, what it found. */
        transient int[] box;
        transient int sweepAt, sweepPass, sweepFound;
        /** Lava found running on out of the box, still to be followed and set; how much of it has been. */
        transient it.unimi.dsi.fastutil.longs.LongArrayList beyond = new it.unimi.dsi.fastutil.longs.LongArrayList();
        transient int beyondSet;
        transient boolean swept;
        /** How far the trench has set, once the eruption has drawn into its vents. */
        transient int trenchAt = Integer.MAX_VALUE;
        /** Cones growing a block: by vent, the height, how far through its columns, and its breach in degrees. */
        transient it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> heaping = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        /** How far through a chunk the graben has got, where it is part way. */
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap grabenAt = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
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
            t.putIntArray("seg", new int[]{half, seg0, seg1, open0, open1, start, hw, magnitude, lavaMost, lava, cool1, cool2, held});
            t.put("grabenKeys", new LongArrayTag(grabenAt.keySet().toLongArray()));
            t.putIntArray("grabenAt", grabenAt.values().toIntArray());
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
            t.put("wet", new LongArrayTag(wet.toLongArray()));
            t.put("shore", new LongArrayTag(shore.toLongArray()));
            t.put("waterKeys", new LongArrayTag(water.keySet().toLongArray()));
            t.put("waterAt", new LongArrayTag(water.values().toLongArray()));
            t.putBoolean("ringed", ringed);
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
            if (s.length >= 13) f.held = s[12];
            long[] gk = t.getLongArray("grabenKeys");
            int[] gv = t.getIntArray("grabenAt");
            for (int i = 0; i < Math.min(gk.length, gv.length); i++) f.grabenAt.put(gk[i], gv[i]);
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
            f.wet = new LongArrayList(t.getLongArray("wet"));
            f.shore = new LongArrayList(t.getLongArray("shore"));
            long[] wk = t.getLongArray("waterKeys"), wv = t.getLongArray("waterAt");
            for (int i = 0; i < Math.min(wk.length, wv.length); i++) f.water.put(wk[i], wv[i]);
            f.ringed = t.getBoolean("ringed");
            // Drawn into its vents already: the trench sets on from the start (what has set is passed over).
            if (f.stage == Stage.FOCUS && !f.vents.isEmpty()) f.trenchAt = 0;
            return f;
        }
    }

    /** A column the lava could run into next, in the order {@link #priority} gives: it runs downhill and pools before it climbs. */
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
            // A rift's fissure opens on the valley's axis and runs down it; a hot spot's any way.
            double[] along = hot ? null : axisStrike(seed, gp, c[0], c[1]);
            if (along != null) return new Site((int) Math.round(along[2]), (int) Math.round(along[3]), along[0], along[1], false);
            double a = level.random.nextDouble() * Math.PI;
            return new Site(c[0], c[1], Math.cos(a), Math.sin(a), hot);
        }
        return null;
    }

    /** How far along a dike, either way, its line is fitted to the rift's axis, blocks, the step, and the directions tried. */
    private static final int AXIS_LOOK = 96, AXIS_STEP = 16, AXIS_TURNS = 36;

    /**
     * Which way the axis of a rift runs near a point, as the valley itself is laid out, and the point on the axis nearest
     * it: {sx, sz, x, z}. The point is moved onto the axis first, down the axis's distance; then the line through it is
     * turned to stay nearest the axis over a dike's length, read off the same warped ground the graben is drawn on. The
     * plate boundary's own strike is not it: the valley winds, and a dike laid along the boundary crossed it. Null where
     * no rift's axis is near.
     */
    static double[] axisStrike(long seed, GeologyParams gp, int x, int z) {
        double px = x, pz = z;
        for (int i = 0; i < 4; i++) {
            double d = axisOff(seed, gp, px, pz);
            if (d >= AXIS_FAR) return null;
            if (d < 1.5) break;
            double gx = axisOff(seed, gp, px + 2, pz) - axisOff(seed, gp, px - 2, pz);
            double gz = axisOff(seed, gp, px, pz + 2) - axisOff(seed, gp, px, pz - 2);
            double n = Math.hypot(gx, gz);
            if (n < 1e-6) break;
            px -= d * gx / n;
            pz -= d * gz / n;
        }
        double best = Double.MAX_VALUE, angle = 0;
        for (int i = 0; i < AXIS_TURNS; i++) {
            double a = Math.PI * i / AXIS_TURNS, off = 0;
            for (int t = AXIS_STEP; t <= AXIS_LOOK; t += AXIS_STEP) {
                off += axisOff(seed, gp, px + Math.cos(a) * t, pz + Math.sin(a) * t)
                        + axisOff(seed, gp, px - Math.cos(a) * t, pz - Math.sin(a) * t);
            }
            if (off < best) {
                best = off;
                angle = a;
            }
        }
        return new double[]{Math.cos(angle), Math.sin(angle), px, pz};
    }

    /** How far the rift's axis is from a point, blocks; {@link #AXIS_FAR} where it is further or there is none. */
    private static double axisOff(long seed, GeologyParams gp, double x, double z) {
        double d = TerrainFields.riftAxisDistance(seed, gp, (int) Math.floor(x), (int) Math.floor(z), AXIS_FAR);
        return d < 0 ? AXIS_FAR : d;
    }

    private static final double AXIS_FAR = 64;

    /**
     * For testing: the nearest place for a rift's fissure, the way its dike runs and the way the plate boundary there
     * runs, and how far each line strays from the valley's axis over the next 96 blocks either way (mean, blocks).
     */
    public static String strikeReport(ServerLevel level, BlockPos at) {
        if (!GeologyWorld.isOwn(level)) return "not the mod's world type";
        long seed = level.getSeed();
        GeologyParams gp = GeologyParams.current();
        // Right here where a rift's axis is near, on land or under the sea; else the nearest place on land for one.
        double[] here = axisStrike(seed, gp, at.getX(), at.getZ());
        Site s = here != null ? new Site((int) Math.round(here[2]), (int) Math.round(here[3]), here[0], here[1], false)
                : site(level, at.getX(), at.getZ(), 0, 480, true);
        if (s == null || s.hotspot()) return "no rift axis here or on land within 480 blocks";
        PlateSample ps = TectonicMap.sampleCached(level, s.x(), s.z());
        double n = ps == null ? 0 : Math.hypot(ps.faultStrikeX(), ps.faultStrikeZ());
        double px = n > 1e-3 ? ps.faultStrikeX() / n : 1, pz = n > 1e-3 ? ps.faultStrikeZ() / n : 0;
        double turn = Math.toDegrees(Math.acos(Math.min(1, Math.abs(px * s.sx() + pz * s.sz()))));
        return String.format(Locale.ROOT, "fissure site %d, %d: dike along %.2f, %.2f, %.1f blocks off the axis; plate line along %.2f, %.2f, %.1f off; %.0f degrees apart",
                s.x(), s.z(), s.sx(), s.sz(), stray(seed, gp, s.x(), s.z(), s.sx(), s.sz()), px, pz, stray(seed, gp, s.x(), s.z(), px, pz), turn);
    }

    private static double stray(long seed, GeologyParams gp, int x, int z, double sx, double sz) {
        double sum = 0;
        int n = 0;
        for (int t = -96; t <= 96; t += 8) {
            sum += axisOff(seed, gp, x + sx * t, z + sz * t);
            n++;
        }
        return sum / n;
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
        // One at a time, but one left a day with nobody near does not keep the others off.
        if (now % ROLL == 0 && GeyserConfig.FISSURE_ERUPTIONS.get() && (st == null || st.all.stream().allMatch(e -> e.held > 24000))) roll(level);
        if (st == null || st.all.isEmpty()) return;
        long t0 = System.nanoTime();
        TickBudget.open(event.getServer().getTickCount());
        long deadline = t0 + TickBudget.slice(0.15);
        PLAYER_BUILT.clear();
        for (Fissure f : st.all) {
            long a = System.nanoTime();
            show(level, f, now);
            long b = System.nanoTime();
            if (now % 20 == 0) step(level, f, now);
            long c = System.nanoTime();
            work(level, f, deadline);
            long e = System.nanoTime();
            // A long tick of its own, told by part for the log.
            if (e - a > 50_000_000L) Diagnostics.info("fissure eruption at {}, {}: a tick of {} ms in {} (show {}, step {}, work {})", f.cx, f.cz,
                    (e - a) / 1_000_000, f.stage, (b - a) / 1_000_000, (c - b) / 1_000_000, (e - c) / 1_000_000);
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
            f.held += 20;
            return;
        }
        f.held = 0;
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
                if (!f.ringed) tuffRings(level, f);
                f.owed += f.lavaMost * (1.0 - CURTAIN_SHARE) * 2.0 * (1.0 - p) / (FOCUS / 20.0);
                cones(level, f, p);
            }
            case COOL -> VolcanoUnrest.hold(level, centre, f.magnitude, 0.3 * (1.0 - p), false);
        }
        if (p < 1.0) return;
        if (f.stage == Stage.COOL) {
            if (f.cool2 >= f.cells.size() && f.cool1 >= f.cells.size() && f.swept && f.restored) {
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
        // The water round where the lava will come out, noted as it stands before any reaches it (see restore).
        if (f.stage == Stage.DIKE || f.stage == Stage.PAUSE || f.stage == Stage.CURTAIN) noteShores(level, f, deadline);
        // Lava come to the water, whatever brought it there: chilled at the edge, gone in a hiss over it.
        if ((f.stage == Stage.CURTAIN || f.stage == Stage.FOCUS || f.stage == Stage.COOL) && !f.swept) guardShores(level, f, deadline);
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
        if (f.stage == Stage.COOL) {
            cool(level, f, deadline);
            if (level.getGameTime() - f.stageAt >= COOL && f.cool1 >= f.cells.size() && f.cool2 >= f.cells.size() && !f.swept) {
                sweep(level, f, deadline);
            }
            if (f.swept && !f.restored) restore(level, f, deadline);
        }
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
            // Water the graben drops under is noted as it was, before anything runs out of it: a mod that lets water
            // run as finite volumes drained a lake into the cracks and the lava then covered its bed. The lava keeps
            // off it, and once the flow has set it is opened again to the height it stood at (see restore).
            noteWater(level, f, x, z);
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

    /**
     * Lowers a column of natural ground by {@code k} blocks, with what grows on it; false where it is built on. Under a
     * lake or a river the bed drops all the same and the water it stood under fills the room: the lake deepens, the
     * river runs on.
     */
    private static boolean sink(ServerLevel level, LongSet built, int x, int z, int k) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = g - k; y <= g; y++) {
            if (!natural(level, built, m.set(x, y, z))) return false;
        }
        int cover = 0;
        BlockState over = Blocks.AIR.defaultBlockState();
        for (int y = g + 1; y <= g + 3; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) break;
            if (openWater(s)) {
                over = s;
                break;
            }
            if (!TerrainProbe.isVegetation(s) || TerrainProbe.isTreePart(s) || y == g + 3) return false;
            cover++;
        }
        // Bottom up, so nothing is left hanging.
        for (int y = g - k; y <= g + cover; y++) {
            BlockState from = y + k <= g + cover ? level.getBlockState(new BlockPos(x, y + k, z)) : over;
            level.setBlock(m.set(x, y, z), from, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (int y = g + cover + 1; y <= g + cover + k; y++) {
            level.setBlock(m.set(x, y, z), over, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        if (over.isAir()) {
            // Dry ground on a shore dropped under the water beside it: the lake comes in over it, as high as it stands,
            // rather than running out of itself into the hollow.
            BlockPos beside = waterBeside(level, x, z, g - k + 1, g);
            if (beside != null) {
                BlockState water = level.getBlockState(beside);
                for (int y = g - k + 1; y <= beside.getY(); y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (s.isAir() || TerrainProbe.isVegetation(s) && !TerrainProbe.isTreePart(s)) {
                        level.setBlock(m, water, Block.UPDATE_ALL);
                    }
                }
            }
        }
        return true;
    }

    /** A block of water itself (a lake's, a river's), not a plant or a block standing in water. */
    private static boolean openWater(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && s.getFluidState().is(FluidTags.WATER);
    }

    /**
     * The highest block of open water beside a column, on its four sides, from {@code lo} to {@code hi}; null if none.
     * Never loads a chunk.
     */
    private static BlockPos waterBeside(ServerLevel level, int x, int z, int lo, int hi) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        for (int i = 0; i < 4; i++) {
            int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0), nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
            if (!Loaded.at(level, nx, nz)) continue;
            for (int y = hi; y >= lo && (best == null || y > best.getY()); y--) {
                if (openWater(level.getBlockState(m.set(nx, y, nz)))) {
                    best = m.immutable();
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Opens a crack a block wide and {@code depth} deep in natural ground; under water, or on a shore beside it, the
     * water fills it as high as it stands, rather than running into it out of a lake.
     */
    private static void crack(ServerLevel level, LongSet built, int x, int z, int depth) {
        if (!ready(level, x, z)) return;
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = g - depth + 1; y <= g; y++) {
            if (!natural(level, built, m.set(x, y, z))) return;
        }
        BlockState over = level.getBlockState(m.set(x, g + 1, z));
        boolean wet = openWater(over);
        if (!wet && !over.isAir() && !(TerrainProbe.isVegetation(over) && !TerrainProbe.isTreePart(over))) return;
        if (!wet && !over.isAir()) level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockState fill = wet ? over : Blocks.AIR.defaultBlockState();
        int wetTo = wet ? g : Integer.MIN_VALUE;
        if (!wet) {
            BlockPos beside = waterBeside(level, x, z, g - depth + 1, g);
            if (beside != null) {
                fill = level.getBlockState(beside);
                wetTo = beside.getY();
            }
        }
        for (int y = g; y > g - depth; y--) {
            level.setBlock(m.set(x, y, z), y <= wetTo ? fill : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
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

    /** The ground under a column's open air: down past lava, plants, trees and snow; MIN where water stands on it. */
    private static int ground(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        for (int i = 0; i < 40; i++) {
            BlockState s = level.getBlockState(m);
            if (s.getFluidState().is(FluidTags.LAVA) || s.isAir() || s.is(Blocks.SNOW) || TerrainProbe.isTreePart(s)
                    || s.canBeReplaced() && s.getFluidState().isEmpty()) {
                m.move(0, -1, 0);
                continue;
            }
            return s.getFluidState().isEmpty() ? m.getY() : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    // === Lava and water ===================================================

    /** The top of the water standing in a column, or MIN where its top is not water. */
    private static int waterTop(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        return level.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER) ? y : Integer.MIN_VALUE;
    }

    /**
     * Notes the water standing in a column the flow has come up against: its top, the bed under it and what it is, so
     * that once the lava has set it is opened again as it was (see {@link #restore}). Whatever the lava did to it in
     * the meantime -- a mod that lets fluids run as finite volumes pushes lava into water, and water on to lava, and
     * where they meet they set into stone -- a river is not dammed and a lake not filled in.
     */
    private static void noteWater(ServerLevel level, Fissure f, int x, int z) {
        long col = column(x, z);
        if (f.water.containsKey(col)) return;
        int top = waterTop(level, x, z);
        if (top == Integer.MIN_VALUE) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, top, z);
        BlockState what = level.getBlockState(m);
        int bed = top;
        while (bed > top - 64 && level.getBlockState(m.set(x, bed, z)).getFluidState().is(FluidTags.WATER)) bed--;
        f.water.put(col, (long) Block.getId(what) << 32 | (long) (top + 32768) << 16 | (bed + 32768));
    }

    /**
     * Whether water stands beside {@code p}, on any of its four sides, level with it or a block lower -- lava a block
     * over a lake's top beside it runs out on to the water -- or stood there when it was noted and has run off since;
     * the water is noted as met.
     */
    private static boolean wetBeside(ServerLevel level, Fissure f, BlockPos p) {
        boolean wet = false;
        for (int i = 0; i < 4; i++) {
            int nx = p.getX() + (i == 0 ? 1 : i == 1 ? -1 : 0), nz = p.getZ() + (i == 2 ? 1 : i == 3 ? -1 : 0);
            if (wasWater(f, nx, p.getY(), nz) || wasWater(f, nx, p.getY() - 1, nz)) {
                wet = true;
                continue;
            }
            if (!level.getBlockState(new BlockPos(nx, p.getY(), nz)).getFluidState().is(FluidTags.WATER)
                    && !level.getBlockState(new BlockPos(nx, p.getY() - 1, nz)).getFluidState().is(FluidTags.WATER)) continue;
            noteWater(level, f, nx, nz);
            wet = true;
        }
        return wet;
    }

    /** How far round the eruptive segment the water is noted before the lava comes out: as far as a flow runs. */
    private static final int SHORE_REACH = 64;

    /**
     * Notes the water standing round where the lava will come out, a chunk at a time, before any of it gets there: a
     * mod that runs fluids as finite volumes carries the lava on past where this mod lays it, down on to a lake's top,
     * and only water noted as it was can be opened again as it was.
     */
    private static void noteShores(ServerLevel level, Fissure f, long deadline) {
        if (f.shoreBand == null) {
            LongOpenHashSet band = new LongOpenHashSet();
            for (int a = f.seg0 - SHORE_REACH; a <= f.seg1 + SHORE_REACH; a += 8) {
                for (int c = -SHORE_REACH; c <= SHORE_REACH; c += 8) band.add(chunkKey(f.x(a, c) >> 4, f.z(a, c) >> 4));
            }
            f.shoreBand = band.toLongArray();
        }
        while (f.shoreAt < f.shoreBand.length && System.nanoTime() < deadline) {
            long k = f.shoreBand[f.shoreAt++];
            int ccx = (int) (k >> 32), ccz = (int) k;
            if (!Loaded.chunk(level, ccx, ccz)) continue;
            for (int i = 0; i < 256; i++) noteWater(level, f, ccx * 16 + (i >> 4), ccz * 16 + (i & 15));
        }
    }

    /**
     * Watches the noted water while the lava runs, a slice of it a tick: lava that has got on to it or into it -- carried
     * there by a mod that runs fluids as finite volumes, or fallen from a cliff -- goes in a hiss of steam, the water
     * back where it was; lava standing at its edge is chilled into the shore's crust.
     */
    private static void guardShores(ServerLevel level, Fissure f, long deadline) {
        if (f.water.isEmpty()) return;
        if (f.guardKeys == null || f.guardAt >= f.guardKeys.length) {
            f.guardKeys = f.water.keySet().toLongArray();
            f.guardAt = 0;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (f.guardAt < f.guardKeys.length && System.nanoTime() < deadline) {
            long col = f.guardKeys[f.guardAt++];
            int x = (int) (col >> 32), z = (int) col;
            if (!ready(level, x, z)) continue;
            long v = f.water.get(col);
            BlockState what = Block.stateById((int) (v >>> 32));
            int top = (int) (v >>> 16 & 0xFFFF) - 32768;
            for (int y = top - 1; y <= top + 1; y++) {
                if (!level.getBlockState(m.set(x, y, z)).getFluidState().is(FluidTags.LAVA)) continue;
                com.jeladastudios.ftsgeology.util.Freeze.set(level, m.immutable(), y <= top && openWater(what) ? what
                        : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                hissed++;
                if (level.random.nextInt(4) == 0) {
                    level.sendParticles(ParticleTypes.CLOUD, x + 0.5, y + 1.0, z + 0.5, 4, 0.4, 0.3, 0.4, 0.02);
                    level.playSound(null, m, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f + 0.4f * level.random.nextFloat());
                }
            }
            for (int i = 0; i < 4; i++) {
                int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0), nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
                for (int y = top; y <= top + 1; y++) {
                    if (wasWater(f, nx, y, nz) || !level.getBlockState(m.set(nx, y, nz)).getFluidState().is(FluidTags.LAVA)) continue;
                    quench(level, f, m.immutable());
                }
            }
        }
    }

    /** Whether a place was under the water noted in its column, or in the room the graben's drop opened under it. */
    private static boolean wasWater(Fissure f, int x, int y, int z) {
        long col = column(x, z);
        if (!f.water.containsKey(col)) return false;
        long v = f.water.get(col);
        int top = (int) (v >>> 16 & 0xFFFF) - 32768, bed = (int) (v & 0xFFFF) - 32768;
        return y > bed - 2 && y <= top;
    }

    /**
     * Lava come to the water's edge: it does not run in but is chilled where it stands, glassy at the skin -- a crust of
     * basalt and obsidian along the shore -- in a hiss of steam, and the flow goes no further that way.
     */
    private static void quench(ServerLevel level, Fissure f, BlockPos p) {
        BlockState rock = level.random.nextInt(4) == 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.BASALT.defaultBlockState();
        com.jeladastudios.ftsgeology.util.Freeze.set(level, p, TfcCompat.translate(level, p, rock), Block.UPDATE_ALL);
        f.shore.add(p.asLong());
        quenched++;
        level.sendParticles(ParticleTypes.CLOUD, p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 6, 0.4, 0.3, 0.4, 0.02);
        if (level.random.nextInt(3) == 0) {
            level.playSound(null, p, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1.5f, 0.6f + 0.4f * level.random.nextFloat());
        }
    }

    /**
     * The water the flow met, opened again once its lava has all set: from its bed to its top, whatever stone, rock or
     * lava now stands there gives way to the water it was. A player's blocks are left.
     */
    private static void restore(ServerLevel level, Fissure f, long deadline) {
        long[] cols = f.water.keySet().toLongArray();
        while (f.restoreAt < cols.length && System.nanoTime() < deadline) {
            long col = cols[f.restoreAt++];
            int x = (int) (col >> 32), z = (int) col;
            if (!ready(level, x, z)) continue;
            long v = f.water.get(col);
            BlockState what = Block.stateById((int) (v >>> 32));
            int top = (int) (v >>> 16 & 0xFFFF) - 32768, bed = (int) (v & 0xFFFF) - 32768;
            if (!openWater(what)) continue;
            LongSet built = PLAYER_BUILT.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> PlayerBuilt.inChunk(level, x >> 4, z >> 4));
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int y = bed + 1; y <= top; y++) {
                BlockState s = level.getBlockState(m.set(x, y, z));
                if (s.getFluidState().is(FluidTags.WATER) || built.contains(m.asLong()) || !setByLava(s)) continue;
                level.setBlock(m, what, Block.UPDATE_ALL);
                reopened++;
            }
            // Over it, open air before: the rock of lava that ran out on to the water's top is taken off it.
            for (int y = top + 1; y <= top + DEEPEST + 1; y++) {
                BlockState s = level.getBlockState(m.set(x, y, z));
                if (s.isAir() || built.contains(m.asLong()) || !setByLava(s)) continue;
                level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                reopened++;
            }
        }
        if (f.restoreAt >= cols.length) f.restored = true;
    }

    /** What lava leaves where it met water, or lava itself, or nothing: what the water may have back. */
    private static boolean setByLava(BlockState s) {
        return s.isAir() || s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.OBSIDIAN) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BLACKSTONE)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(ModBlocks.COOLING_LAVA_CRUST.get()) || s.is(ModBlocks.BASALT_LAYER.get());
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
        if (f.lavaCols == null) {
            f.lavaCols = new LongOpenHashSet();
            for (long k : f.cells) f.lavaCols.add(column(BlockPos.getX(k), BlockPos.getZ(k)));
        }
        if (f.front != null) return;
        f.front = new PriorityQueue<>();
        // From the newest of the flow only, where its ground is loaded: its edge is there.
        for (int i = Math.max(0, f.cells.size() - REOFFER); i < f.cells.size(); i++) {
            BlockPos p = BlockPos.of(f.cells.getLong(i));
            if (Loaded.at(level, p) && level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) offer(level, f, p.getX(), p.getZ(), p.getY());
        }
    }

    /** The cells of the flow the front is offered again from, after a restart. */
    private static final int REOFFER = 1500;

    /** Offers the four columns round one the lava stands in, with its surface at {@code surface}. */
    private static void offer(ServerLevel level, Fissure f, int x, int z, int surface) {
        for (int i = 0; i < 4; i++) {
            int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0), nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
            long col = column(nx, nz);
            if (!Loaded.at(level, nx, nz)) continue;
            if (f.lavaCols.contains(col)) {
                // Lava already laid there: a flow from higher up runs on over it (see stack).
                int top = lavaTop(level, nx, nz);
                if (top != Integer.MIN_VALUE && top + 1 <= surface) f.front.add(new Front(priority(f, nx, nz, top + 1, surface), col, surface));
                continue;
            }
            int g = ground(level, nx, nz);
            if (g == Integer.MIN_VALUE) {
                // Water, as like as not: noted, to be opened again whatever comes of it.
                noteWater(level, f, nx, nz);
                continue;
            }
            if (g + 1 > surface) continue;
            f.front.add(new Front(priority(f, nx, nz, g + 1, surface), col, surface));
        }
    }

    /**
     * The most blocks of lava a flow stands deep. Three made a lake of rock where it pooled; a real flow is a few metres
     * thick, and with less of it piled up where it lies it runs on further.
     */
    private static final int DEEPEST = 2;

    /** What running on downhill counts for against filling lower ground elsewhere, and the lobes' spread, in blocks. */
    private static final double DOWNHILL = 2.5, LOBES = 1.5;

    /**
     * The order the lava takes the columns round its edge in, lowest first, in sixteenths of a block. A flow running on
     * downhill from where it got to is taken a few blocks ahead of the lowest ground elsewhere: a lava flow is thick
     * and slow and keeps to the way it is going, a long tongue down a slope before it ponds where the ground levels
     * out. On the level, broad lobes, not a spreading disc.
     */
    private static int priority(Fissure f, int x, int z, int at, int surface) {
        double key = at + unit(mix(x * 73428767L ^ z), 9)
                + LOBES * com.jeladastudios.ftsgeology.util.ValueNoise.noise(x + f.cx * 7, z - f.cz * 3, 9.0);
        if (at < surface) key -= DOWNHILL;
        return (int) Math.round(16 * key);
    }

    /** Lays lava in the column a front names, if it still can; true if it did. */
    private static boolean spill(ServerLevel level, Fissure f, Front fr) {
        long col = fr.column();
        int x = (int) (col >> 32), z = (int) col;
        if (f.lavaCols.contains(col)) return stack(level, f, x, z, fr.allowed());
        // A block set at a chunk's edge updates the next chunk: that must be in too, or the server waits for it.
        if (!ready(level, x, z)) return false;
        int g = ground(level, x, z);
        if (g == Integer.MIN_VALUE || g + 1 > fr.allowed()) return false;
        BlockPos p = new BlockPos(x, g + 1, z);
        // The bed of water that ran off under the flow is the water's still: the lava does not take it.
        if (wasWater(f, x, p.getY(), z)) return false;
        if (!takes(level.getBlockState(p)) || !natural(level, null, p.below())) return false;
        if (wetBeside(level, f, p)) {
            // The water's edge: chilled where it stands, and no further.
            quench(level, f, p);
            f.lavaCols.add(col);
            f.lava++;
            return true;
        }
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
     * of lava on a block of lava, never more than two deep, its neighbours then offered at the new height. False if none
     * of the next few dozen can.
     */
    private static boolean rise(ServerLevel level, Fissure f) {
        int n = f.cells.size();
        for (int tries = 0; tries < Math.min(64, n); tries++) {
            if (f.riseAt >= n) f.riseAt = 0;
            BlockPos p = BlockPos.of(f.cells.getLong(f.riseAt++));
            if (!ready(level, p.getX(), p.getZ()) || !level.getBlockState(p).getFluidState().isSource()
                    || !level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) continue;
            BlockPos up = p.above();
            BlockState over = level.getBlockState(up);
            if (!takes(over) || wetBeside(level, f, up)) continue;
            int deep = 1;
            while (deep < DEEPEST && level.getBlockState(p.below(deep)).getFluidState().is(FluidTags.LAVA)) deep++;
            if (deep >= DEEPEST || lowerBeside(level, p)) continue;
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

    /** The top of the lava standing in a column, or MIN where its top is not a block of lava. */
    private static int lavaTop(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        var fluid = level.getBlockState(new BlockPos(x, y, z)).getFluidState();
        return fluid.is(FluidTags.LAVA) && fluid.isSource() ? y : Integer.MIN_VALUE;
    }

    /**
     * A block of lava on the lava already in a column, where a flow from higher up runs on over its own field -- the
     * vents' lava over the curtain's -- no deeper than two; true if laid. The front takes the low ground first, so a
     * flow spreads before it piles up.
     */
    private static boolean stack(ServerLevel level, Fissure f, int x, int z, int allowed) {
        if (!ready(level, x, z)) return false;
        int top = lavaTop(level, x, z);
        if (top == Integer.MIN_VALUE || top + 1 > allowed) return false;
        int deep = 0;
        while (deep < DEEPEST && level.getBlockState(new BlockPos(x, top - deep, z)).getFluidState().is(FluidTags.LAVA)) deep++;
        if (deep >= DEEPEST) return false;
        BlockPos p = new BlockPos(x, top + 1, z);
        if (!takes(level.getBlockState(p)) || wetBeside(level, f, p)) return false;
        level.setBlock(p, lava(level, p), Block.UPDATE_ALL);
        f.cells.add(p.asLong());
        f.lava++;
        laid++;
        offer(level, f, x, z, top + 1);
        return true;
    }

    /**
     * Whether a column's ground may be written: what a block set there sets going, in this mod or another, reads a few
     * blocks round it, and none of that may be a chunk still loading.
     */
    private static boolean ready(ServerLevel level, int x, int z) {
        return Loaded.near(level, x, z, 8);
    }

    /** Opens the fissure at a point along it: a trench of lava, which overflows on to the ground round it. */
    private static void carve(ServerLevel level, Fissure f, int a) {
        double c = f.wobble(a);
        int x = f.x(a, c), z = f.z(a, c);
        if (!ready(level, x, z)) return;
        front(level, f);
        // Under a lake or a river, or where the trench would open beside one, the magma meets water: no fountains of lava
        // there but blasts of steam and shattered rock (see show), which heap a ring of tuff on the shore (see tuffRings).
        int wetTop = waterTop(level, x, z);
        if (wetTop != Integer.MIN_VALUE) {
            f.wet.add(BlockPos.asLong(x, wetTop, z));
            noteWater(level, f, x, z);
            return;
        }
        int g = ground(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Only into solid ground nobody built, with ground under it: not over a tunnel, not beside a cellar.
        for (int y = g - 3; y <= g; y++) {
            if (!natural(level, null, m.set(x, y, z))) return;
        }
        for (int y = g - 2; y <= g + 1; y++) {
            if (wetBeside(level, f, m.set(x, y, z))) {
                f.wet.add(BlockPos.asLong(x, g, z));
                return;
            }
        }
        boolean any = false;
        for (int y = g - 2; y <= g; y++) {
            m.set(x, y, z);
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
            // On its own ground and the lava in its trench, never on a roof or in a tree.
            int base = ground(level, x, z);
            if (base == Integer.MIN_VALUE || !natural(level, null, new BlockPos(x, base, z)) || nearWet(f, x, z, 5)) continue;
            int top = base;
            while (top < base + 4 && level.getBlockState(new BlockPos(x, top + 1, z)).getFluidState().is(FluidTags.LAVA)) top++;
            f.vents.add(BlockPos.asLong(x, top, z));
        }
        // None of them where the ground is loaded: tried again a second on.
        if (f.vents.isEmpty()) return;
        // A fresh front: from now on the lava comes from the vents only. The trench away from them sets (see setTrench).
        front(level, f);
        f.front = new PriorityQueue<>();
        f.trenchAt = 0;
        Diagnostics.info("fissure eruption at {}, {}: drawn into {} vents", f.cx, f.cz, f.vents.size());
    }

    /** The trench away from the vents sets, crust over basalt, its rims heaped with spatter; a few blocks a tick. */
    private static void setTrench(ServerLevel level, Fissure f, long deadline) {
        while (f.trenchAt < f.trench.size() && System.nanoTime() < deadline) {
            BlockPos p = BlockPos.of(f.trench.getLong(f.trenchAt++));
            if (!ready(level, p.getX(), p.getZ())) {
                // Waits for its ground.
                f.trenchAt--;
                return;
            }
            if (nearVent(f, p.getX(), p.getZ(), 6)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().is(FluidTags.LAVA)) continue;
            boolean top = !level.getBlockState(p.above()).getFluidState().is(FluidTags.LAVA);
            com.jeladastudios.ftsgeology.util.Freeze.set(level, p, top ? ModBlocks.COOLING_LAVA_CRUST.get().defaultBlockState() : Blocks.BASALT.defaultBlockState(), Block.UPDATE_ALL);
            // Spatter heaped a block high along its sides.
            if (top) {
                for (int side = -1; side <= 1; side += 2) {
                    int rx = p.getX() + (int) Math.round(-f.sz * side * 1.5), rz = p.getZ() + (int) Math.round(f.sx * side * 1.5);
                    if (!Loaded.at(level, rx, rz)) continue;
                    int g = ground(level, rx, rz);
                    if (g == Integer.MIN_VALUE || g > p.getY() + 1) continue;
                    BlockPos r = new BlockPos(rx, g + 1, rz);
                    if (ready(level, r.getX(), r.getZ()) && takes(level.getBlockState(r)) && !level.getBlockState(r).getFluidState().is(FluidTags.LAVA)
                            && natural(level, null, r.below())) level.setBlock(r, scoria(level, false), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Whether the fissure met water within {@code r} blocks of a column. */
    private static boolean nearWet(Fissure f, int x, int z, int r) {
        for (long w : f.wet) {
            if (Math.abs(BlockPos.getX(w) - x) <= r && Math.abs(BlockPos.getZ(w) - z) <= r) return true;
        }
        return false;
    }

    /** How far out from where the fissure met water its tuff ring stands, and how far its ash falls. */
    private static final int RING_IN = 2, RING_OUT = 7, ASH_OUT = 14;

    /**
     * Rings of tuff round where the fissure met water. Magma meeting water does not pour out but shatters: blast after
     * blast of steam throws out ash and broken rock, which falls in a low ring round the vent -- a tuff ring, as on
     * the shores of Myvatn and at Laki. Here each stretch of the fissure under water heaps one on the land round it, a
     * block or two of tuff on the shore within a few blocks, a dusting of ash further out. Once, as the eruption draws
     * into its vents.
     */
    private static void tuffRings(ServerLevel level, Fissure f) {
        f.ringed = true;
        if (f.wet.isEmpty()) return;
        // The points by 16-block cell, so each column looks only at those near it.
        Long2ObjectOpenHashMap<LongArrayList> cells = new Long2ObjectOpenHashMap<>();
        for (long w : f.wet) cells.computeIfAbsent(chunkKey(BlockPos.getX(w) >> 4, BlockPos.getZ(w) >> 4), k -> new LongArrayList()).add(w);
        LongOpenHashSet seen = new LongOpenHashSet();
        BlockState ash = ModBlocks.VOLCANIC_ASH.get().defaultBlockState();
        for (long from : f.wet) for (int x = BlockPos.getX(from) - ASH_OUT; x <= BlockPos.getX(from) + ASH_OUT; x++) {
            for (int z = BlockPos.getZ(from) - ASH_OUT; z <= BlockPos.getZ(from) + ASH_OUT; z++) {
                if (!seen.add(column(x, z))) continue;
                double d = Double.MAX_VALUE;
                int top = Integer.MIN_VALUE;
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                    LongArrayList near = cells.get(chunkKey((x >> 4) + i, (z >> 4) + j));
                    if (near == null) continue;
                    for (long w : near) {
                        double e = Math.hypot(BlockPos.getX(w) - x, BlockPos.getZ(w) - z);
                        if (e < d) {
                            d = e;
                            top = BlockPos.getY(w);
                        }
                    }
                }
                if (d < RING_IN || d > ASH_OUT || !ready(level, x, z)) continue;
                int g = ground(level, x, z);
                // Land only, on the shore: not far up a slope over the water.
                if (g == Integer.MIN_VALUE || g > top + 3 || !natural(level, null, new BlockPos(x, g, z))) continue;
                long h = mix(x * 341873128712L ^ z * 132897987541L ^ f.id);
                if (d <= RING_OUT) {
                    // Highest a little inside its outer edge, two blocks there, one at its feet.
                    int high = d >= 3 && d <= RING_OUT - 2 && unit(h, 3) < 0.7 ? 2 : 1;
                    for (int i = 1; i <= high; i++) {
                        BlockPos p = new BlockPos(x, g + i, z);
                        if (!takes(level.getBlockState(p)) || level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) break;
                        level.setBlock(p, TfcCompat.translate(level, p, Blocks.TUFF.defaultBlockState()), Block.UPDATE_ALL);
                        ringBlocks++;
                    }
                } else if (unit(h, 4) < 0.6 * (ASH_OUT - d) / (ASH_OUT - RING_OUT)) {
                    BlockPos p = new BlockPos(x, g + 1, z);
                    if (!level.getBlockState(p).isAir() || !ash.canSurvive(level, p)) continue;
                    level.setBlock(p, ash.setValue(com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS, 1 + (int) (2 * unit(h, 5))),
                            Block.UPDATE_ALL);
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
                    if (!ready(level, q.getX(), q.getZ())) continue;
                    BlockState s = level.getBlockState(q);
                    if (!s.getFluidState().isSource() && (takes(s) || natural(level, null, q)) && natural(level, null, q.below())
                            && ready(level, q.getX(), q.getZ())) {
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
        if (!ready(level, x, z)) return;
        int g = ground(level, x, z);
        if (g == Integer.MIN_VALUE || g >= target) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, g, z);
        if (!natural(level, null, m) && !level.getBlockState(m).is(ModBlocks.COOLING_LAVA_CRUST.get())) return;
        for (int y = g + 1; y <= target; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (!takes(s) && !s.getFluidState().is(FluidTags.LAVA)) return;
            com.jeladastudios.ftsgeology.util.Freeze.set(level, m.immutable(), scoria(level, rim && y == target), Block.UPDATE_ALL);
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

    /** Lava this thin or thinner, in eighths of a block, is a film that leaves nothing when it sets. */
    private static final int FILM = 1;

    /**
     * Sets the lava standing at {@code p} into rock by how much of it there is: a full block into {@code full}, lava
     * that ran out thin (Flowing Fluids keeps it as eighths of a block) into a sheet of as many layers on firm ground,
     * a film or a stream falling through the air into nothing. Lava falling on to more lava or on to the ground stands
     * full. True if a full block was set.
     */
    private static boolean set(ServerLevel level, BlockPos p, FluidState fluid, BlockState full) {
        boolean falling = !fluid.isSource() && fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
        int amount = fluid.isSource() ? 8 : fluid.getAmount();
        if (falling) amount = level.getBlockState(p.below()).isAir() ? 0 : 8;
        if (amount >= 8) {
            com.jeladastudios.ftsgeology.util.Freeze.set(level, p, full, Block.UPDATE_ALL);
            return true;
        }
        // Quietly, the neighbours not told: a mod that runs fluids as finite volumes answers every lava block's
        // neighbour being told by moving lava about, which is all being set.
        if (amount <= FILM || !BasaltLayerBlock.firmUnder(level, p)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            films++;
            return false;
        }
        com.jeladastudios.ftsgeology.util.Freeze.set(level, p,
                ModBlocks.BASALT_LAYER.get().defaultBlockState().setValue(BasaltLayerBlock.LAYERS, amount), Block.UPDATE_CLIENTS);
        sheets++;
        sheetLayers += amount;
        return false;
    }

    /**
     * The lava sets: first all of it into a crust (glowing still, over basalt where it lies deep) or, where it ran out
     * thin, a sheet of basalt as thick as it was; then most of the crust on into young basalt.
     */
    private static void cool(ServerLevel level, Fissure f, long deadline) {
        boolean second = level.getGameTime() - f.stageAt >= COOL / 2;
        // Once its time is up, lava where the ground is not loaded is left to lie.
        boolean late = level.getGameTime() - f.stageAt >= COOL;
        while (System.nanoTime() < deadline) {
            if (f.cool1 < f.cells.size()) {
                BlockPos p = BlockPos.of(f.cells.getLong(f.cool1++));
                if (!ready(level, p.getX(), p.getZ())) {
                    if (late) continue;
                    // Waits for its ground to be loaded.
                    f.cool1--;
                    return;
                }
                FluidState fluid = level.getBlockState(p).getFluidState();
                if (!fluid.is(FluidTags.LAVA)) continue;
                boolean top = !level.getBlockState(p.above()).getFluidState().is(FluidTags.LAVA);
                if (set(level, p, fluid, top ? ModBlocks.COOLING_LAVA_CRUST.get().defaultBlockState() : Blocks.BASALT.defaultBlockState())) cooled++;
            } else if (second && f.cool2 < f.cells.size()) {
                BlockPos p = BlockPos.of(f.cells.getLong(f.cool2++));
                if (!ready(level, p.getX(), p.getZ())) {
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

    /**
     * The last of the cooling: the box the field lies in, swept for any lava still lying there -- a mod that lets fluids
     * flow as finite volumes (Flowing Fluids) moves lava off the blocks it was laid on, and that would lie molten for
     * good. It sets as the rest of the flow did (see {@link #set}): full lava into rock, thin lava into a sheet, a film
     * into nothing. Gone over again while a pass still finds some, three passes at most, within the budget.
     */
    /** The most lava followed out of the box and set, over the whole sweep, and how far out of the box it is followed. */
    private static final int BEYOND_MOST = 4096, BEYOND_REACH = 32;

    private static boolean outside(int[] b, BlockPos q) {
        return q.getX() < b[0] || q.getX() > b[2] || q.getZ() < b[1] || q.getZ() > b[3] || q.getY() < b[4];
    }

    /** What lava left lying sets into: basalt under more lava, a skin of crust, basalt or blackstone on top. */
    private static BlockState sweptRock(ServerLevel level, BlockPos p) {
        boolean top = !level.getBlockState(p.above()).getFluidState().is(FluidTags.LAVA);
        int r = level.random.nextInt(20);
        return !top ? Blocks.BASALT.defaultBlockState() : r < 3 ? ModBlocks.COOLING_LAVA_CRUST.get().defaultBlockState()
                : (r < 13 ? Blocks.BASALT : r < 18 ? Blocks.SMOOTH_BASALT : Blocks.BLACKSTONE).defaultBlockState();
    }

    private static void sweep(ServerLevel level, Fissure f, long deadline) {
        if (f.box == null) {
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
            for (long k : f.cells) {
                x0 = Math.min(x0, BlockPos.getX(k)); x1 = Math.max(x1, BlockPos.getX(k));
                y0 = Math.min(y0, BlockPos.getY(k)); y1 = Math.max(y1, BlockPos.getY(k));
                z0 = Math.min(z0, BlockPos.getZ(k)); z1 = Math.max(z1, BlockPos.getZ(k));
            }
            if (x0 > x1) {
                f.swept = true;
                return;
            }
            f.box = new int[]{x0 - 8, z0 - 8, x1 + 8, z1 + 8, y0 - 3, y1 + 4};
        }
        int[] b = f.box;
        int w = b[2] - b[0] + 1, n = w * (b[3] - b[1] + 1);
        while (f.sweepAt < n && System.nanoTime() < deadline) {
            int i = f.sweepAt++, x = b[0] + i % w, z = b[1] + i / w;
            if (!ready(level, x, z)) continue;
            // From the bottom up, so thin lava lying on more lava finds rock under it.
            for (int y = b[4]; y <= b[5]; y++) {
                BlockPos p = new BlockPos(x, y, z);
                FluidState fluid = level.getBlockState(p).getFluidState();
                if (!fluid.is(FluidTags.LAVA)) continue;
                f.sweepFound++;
                // At the box's edge, lava running on out of it: followed once the box is done.
                if (x == b[0] || x == b[2] || z == b[1] || z == b[3] || y == b[4]) {
                    for (Direction d : Direction.values()) {
                        if (d == Direction.UP) continue;
                        BlockPos q = p.relative(d);
                        if (outside(b, q) && level.getBlockState(q).getFluidState().is(FluidTags.LAVA)) f.beyond.add(q.asLong());
                    }
                }
                if (set(level, p, fluid, sweptRock(level, p))) swept++;
            }
        }
        if (f.sweepAt < n) return;
        // Lava a mod that lets it run as finite volumes carried on out of the box -- down a slope, into a hollow -- is
        // followed through the lava joined to it and set as well, up to a few thousand blocks of it.
        while (!f.beyond.isEmpty() && System.nanoTime() < deadline) {
            BlockPos p = BlockPos.of(f.beyond.removeLong(f.beyond.size() - 1));
            if (f.beyondSet >= BEYOND_MOST || !ready(level, p.getX(), p.getZ())) continue;
            FluidState fluid = level.getBlockState(p).getFluidState();
            if (!fluid.is(FluidTags.LAVA)) continue;
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                // Not far off into lava of the world's own, a cave's lava lake the flow ran down into.
                if (q.getX() < b[0] - BEYOND_REACH || q.getX() > b[2] + BEYOND_REACH || q.getZ() < b[1] - BEYOND_REACH
                        || q.getZ() > b[3] + BEYOND_REACH || q.getY() < b[4] - BEYOND_REACH) continue;
                if (level.getBlockState(q).getFluidState().is(FluidTags.LAVA)) f.beyond.add(q.asLong());
            }
            if (set(level, p, fluid, sweptRock(level, p))) swept++;
            f.beyondSet++;
            f.sweepFound++;
        }
        if (!f.beyond.isEmpty()) return;
        if (f.sweepFound > 0 && ++f.sweepPass < 3) {
            f.sweepAt = 0;
            f.sweepFound = 0;
            return;
        }
        f.swept = true;
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
                if (nearWet(f, x, z, 1)) continue;
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
            steam(level, f, near, now, 1.0);
            return;
        }
        double p = Math.min(1.0, (now - f.stageAt) / (double) FOCUS);
        steam(level, f, near, now, 1.0 - p);
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

    /**
     * Where the fissure runs under water: no fountains of fire but the water boiling over the dike, a white column of
     * steam with dark ash in it, and every so often a blast that throws spray and shattered rock (a phreatomagmatic
     * eruption, as in the lakes of Iceland's rift). Along the shore where the lava was chilled, steam goes on rising.
     * {@code strength} dies away as the eruption draws into its vents.
     */
    private static void steam(ServerLevel level, Fissure f, List<ServerPlayer> near, long now, double strength) {
        if (!f.wet.isEmpty() && level.random.nextDouble() < strength) {
            for (int i = 0; i < 3; i++) {
                long w = f.wet.getLong(level.random.nextInt(f.wet.size()));
                int x = BlockPos.getX(w), y = BlockPos.getY(w), z = BlockPos.getZ(w);
                if (!Loaded.at(level, x, z)) continue;
                double tall = (6 + 10 * level.random.nextDouble()) * (0.4 + 0.6 * strength);
                send(level, near, ParticleTypes.BUBBLE_COLUMN_UP, x + 0.5, y - 0.5, z + 0.5, 6, 0.6, 0.6, 0.6, 0.1);
                send(level, near, ParticleTypes.CLOUD, x + 0.5, y + 1 + tall * 0.5, z + 0.5, 14, 0.9, tall * 0.4, 0.9, 0.04);
                if (i == 0 && now % 3 == 0) send(level, near, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x + 0.5, y + tall, z + 0.5, 2, 1.5, 1.0, 1.5, 0.02);
                if (i == 0 && now % 5 == 0) send(level, near, ParticleTypes.LARGE_SMOKE, x + 0.5, y + 2 + tall * 0.6, z + 0.5, 5, 1.2, 1.5, 1.2, 0.03);
                if (i == 1 && level.random.nextInt(50) == 0) {
                    // A blast: spray and rock flung up, a crack heard far off.
                    send(level, near, ParticleTypes.EXPLOSION, x + 0.5, y + 1.5, z + 0.5, 3, 1.0, 1.0, 1.0, 0.0);
                    send(level, near, ParticleTypes.SPLASH, x + 0.5, y + 1.2, z + 0.5, 60, 2.0, 0.6, 2.0, 0.4);
                    send(level, near, ParticleTypes.ASH, x + 0.5, y + 4, z + 0.5, 40, 4.0, 3.0, 4.0, 0.02);
                    level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 5.0f, 0.5f + 0.2f * level.random.nextFloat());
                    blasts++;
                }
                if (i == 2 && now % 10 == 0) level.playSound(null, x, y, z, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 3.0f, 0.5f);
            }
        }
        if (!f.shore.isEmpty() && now % 2 == 0) {
            long s = f.shore.getLong(level.random.nextInt(f.shore.size()));
            if (Loaded.at(level, BlockPos.getX(s), BlockPos.getZ(s))) {
                send(level, near, ParticleTypes.CLOUD, BlockPos.getX(s) + 0.5, BlockPos.getY(s) + 1.2, BlockPos.getZ(s) + 0.5, 3, 0.4, 0.4, 0.4, 0.02);
            }
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
            double[] along = GeologyWorld.isOwn(level) ? axisStrike(level.getSeed(), GeologyParams.current(), at.getX(), at.getZ()) : null;
            PlateSample ps = TectonicMap.sampleCached(level, at.getX(), at.getZ());
            double n = ps == null ? 0 : Math.hypot(ps.faultStrikeX(), ps.faultStrikeZ());
            double a = level.random.nextDouble() * Math.PI;
            s = along != null ? new Site(at.getX(), at.getZ(), along[0], along[1], false)
                    : n > 1e-3 ? new Site(at.getX(), at.getZ(), ps.faultStrikeX() / n, ps.faultStrikeZ() / n, false)
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
        return String.format(Locale.ROOT, "fissure eruptions: %d begun, %d swarm quakes (largest M%.1f), %d columns dropped, %d cracks, %d lava laid, %d set, %d more found lying and set, %d sheets (%d layers), %d films gone; at the water %d chilled, %d hissed away, %d steam blasts, %d tuff, %d reopened; worst tick %.1f ms",
                begun, swarm, largest, sunkColumns, cracked, laid, cooled, swept, sheets, sheetLayers, films, quenched, hissed, blasts, ringBlocks, reopened, worstMs);
    }

    public static boolean any() {
        return begun > 0 || laid > 0 || swarm > 0;
    }

    public static void clear() {
        begun = sunkColumns = cracked = laid = cooled = swarm = swept = sheets = sheetLayers = films = quenched = hissed = reopened = blasts = ringBlocks = 0;
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
