package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where and when the ground breaks: every stretch of plate boundary keeps its own clock.
 *
 * <p>A real fault is not a coin tossed near whoever is standing by it. Each stretch of it loads up as the plates
 * creep past each other and lets go when it can hold no more, then is quiet for a while, then more and more likely
 * to go again -- the renewal of a seismic cycle. So every 256 blocks of boundary here keeps the game time it last
 * broke, and the chance it breaks now grows with the time since: a Rayleigh renewal, quiet straight after a
 * rupture and "due" long after one, with a mean of {@code quakeRecurrenceDays} over the boundary's stress. A stretch
 * first seen gets a past drawn from that same cycle, so arriving somewhere does not set everything off at once.</p>
 *
 * <p>Sizes follow Gutenberg and Richter from magnitude 5 up to the boundary's largest: ten fives for every six, ten
 * sixes for every seven. The small ones are felt only near the fault and the great ones far out, so a player living
 * on a boundary feels one every two or three game days and one some thousand blocks off every ten or so, most of
 * them from far away -- which the old way, a quake only ever within a hundred and twenty-eight blocks of a player,
 * could never give.</p>
 *
 * <p>The stretches looked after are those within {@code quakeFeltRange} of a player and within
 * {@code seismographRange} of a seismograph. A quake a player feels is a quake as ever: warned of, felt, broken
 * along its fault (from 5.5 up) with the parts nobody has loaded waiting for them; one nobody feels is only filed for
 * the seismographs, which read it off when they next look. A rupture resets the clocks of the stretches it ran
 * along.</p>
 */
public final class FaultClocks {

    private FaultClocks() {}

    /** Blocks of boundary one clock keeps. */
    static final int SEG = 256;
    /** The smallest quake a clock counts: from here up the sizes are Gutenberg-Richter's. */
    static final double SMALLEST = 5.0;
    /** Cells whose boundary is looked up in a tick, at most: a player arriving somewhere new costs a few seconds. */
    private static final int LOOKUPS_PER_TICK = 24;
    /** Seismographs remembered, at most. */
    private static final int MOST_STATIONS = 64;

    /** One stretch of boundary: its point on the line, its kind, how hard it is pushed and which way it runs. */
    record Segment(int x, int z, FaultType type, double stress, double strikeX, double strikeZ) {}

    /** Per dimension: cells looked up (null value: no boundary in it), and cells still to be looked up. */
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Segment>> CELLS = new HashMap<>();
    private static final Map<ResourceKey<Level>, LongArrayFIFOQueue> TODO = new HashMap<>();
    private static final Map<ResourceKey<Level>, LongOpenHashSet> QUEUED = new HashMap<>();
    private static final Segment NONE = new Segment(0, 0, FaultType.INTERIOR, 0, 0, 0);
    private static int timer;
    private static long rolled, broke, felt, filed, loaded, small, smallFelt;
    /** The smallest of the small quakes. */
    static final double SMALL_FROM = 2.5;

    /** The clocks and the stations of one dimension, kept with the world. */
    static final class Clocks extends SavedData {
        /** Cell to the game time its stretch last broke (possibly before the world began). */
        final Long2LongOpenHashMap last = new Long2LongOpenHashMap();
        final LongOpenHashSet stations = new LongOpenHashSet();

        static Clocks load(CompoundTag tag) {
            Clocks c = new Clocks();
            long[] k = tag.getLongArray("Cells"), t = tag.getLongArray("Last");
            for (int i = 0; i < Math.min(k.length, t.length); i++) c.last.put(k[i], t[i]);
            for (long s : tag.getLongArray("Stations")) c.stations.add(s);
            return c;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putLongArray("Cells", last.keySet().toLongArray());
            long[] t = new long[last.size()];
            int i = 0;
            for (long k : last.keySet()) t[i++] = last.get(k);
            tag.putLongArray("Last", t);
            tag.putLongArray("Stations", stations.toLongArray());
            return tag;
        }
    }

    private static Clocks of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Clocks::load, Clocks::new, "fts_geology_fault_clocks");
    }

    /** A seismograph stands here: the boundary round it keeps its clocks while nobody is near. */
    public static void station(ServerLevel level, BlockPos pos) {
        Clocks c = of(level);
        if (c.stations.contains(pos.asLong()) || c.stations.size() >= MOST_STATIONS) return;
        c.stations.add(pos.asLong());
        c.setDirty();
    }

    /**
     * Where the stretch of boundary at a place is in its cycle: {game days since it last broke, its mean days between
     * breaks}, or null where no boundary runs through the place's cell. What a trench across a fault reads from its
     * broken layers, and a map of the seismic gaps shows. A stretch nobody has watched is given its past here as it would
     * be when first watched.
     */
    public static double[] cycle(ServerLevel level, int x, int z) {
        long k = cell(Math.floorDiv(x, SEG), Math.floorDiv(z, SEG));
        Long2ObjectOpenHashMap<Segment> cells = CELLS.computeIfAbsent(level.dimension(), d -> new Long2ObjectOpenHashMap<>());
        Segment s = cells.get(k);
        if (s == null) {
            s = segmentIn(level, ChunkPos.getX(k), ChunkPos.getZ(k));
            cells.put(k, s);
        }
        if (s == NONE) return null;
        Clocks clocks = of(level);
        long now = level.getGameTime();
        double mean = meanTicks(s);
        if (!clocks.last.containsKey(k)) {
            long age = (long) (mean * Math.sqrt(2.0 / Math.PI) * Math.abs(level.random.nextGaussian()));
            clocks.last.put(k, now - age);
            clocks.setDirty();
        }
        return new double[]{Math.max(0, now - clocks.last.get(k)) / 24000.0, mean / 24000.0};
    }

    public static void clear() {
        CELLS.clear();
        TODO.clear();
        QUEUED.clear();
        timer = 0;
        rolled = broke = felt = filed = loaded = small = smallFelt = 0;
    }

    private static long cell(int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }

    /**
     * Game ticks of a stretch's mean recurrence: sixteen times the configured days, over its stress. Shaking is felt as
     * far as a real quake's is (see FeltShaking): a magnitude 5 seventy kilometres off, where it used to be ten. At the
     * old pace every boundary within reach was felt breaking ten times an hour, and did damage every day or two.
     */
    private static double meanTicks(Segment s) {
        return GeyserConfig.QUAKE_RECURRENCE_DAYS.get() * RECURRENCE * 24000.0 / Math.max(0.15, s.stress());
    }

    /** How much less often the stretches break, and have their small quakes, than the config's numbers say. */
    private static final double RECURRENCE = 16.0, SMALL_SHARE = 0.125;

    // === Every tick ==========================================================

    public static void tick(MinecraftServer server) {
        if (!GeyserConfig.QUAKES_ENABLED.get()) return;
        int interval = GeyserConfig.QUAKE_AMBIENT_INTERVAL.get();
        if (interval <= 0) return;
        for (ServerLevel level : server.getAllLevels()) lookUp(level);
        if (++timer < interval) return;
        timer = 0;
        for (ServerLevel level : server.getAllLevels()) roll(level, interval);
    }

    /** Finds the boundary in a few of the cells waiting to be looked at. */
    private static void lookUp(ServerLevel level) {
        LongArrayFIFOQueue todo = TODO.get(level.dimension());
        if (todo == null || todo.isEmpty()) return;
        Long2ObjectOpenHashMap<Segment> cells = CELLS.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        LongOpenHashSet queued = QUEUED.get(level.dimension());
        for (int n = 0; n < LOOKUPS_PER_TICK && !todo.isEmpty(); n++) {
            long k = todo.dequeueLong();
            queued.remove(k);
            cells.put(k, segmentIn(level, ChunkPos.getX(k), ChunkPos.getZ(k)));
        }
    }

    /**
     * The stretch of boundary a cell holds: the point on the line nearest the cell's middle, if that point is inside
     * the cell. Every stretch of a line through the world falls in one cell or another.
     */
    static Segment segmentIn(ServerLevel level, int cx, int cz) {
        int x0 = cx * SEG, z0 = cz * SEG;
        PlateSample s = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, x0 + SEG / 2, z0 + SEG / 2);
        double d = s.faultDistance();
        if (!(d < SEG)) return NONE;
        int px = (int) Math.round(x0 + SEG / 2 + s.faultNormalX() * d), pz = (int) Math.round(z0 + SEG / 2 + s.faultNormalZ() * d);
        if (px < x0 || px >= x0 + SEG || pz < z0 || pz >= z0 + SEG) return NONE;
        PlateSample on = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, px, pz);
        if (on.faultType() == FaultType.INTERIOR) return NONE;
        return new Segment(px, pz, on.faultType(), on.stress(), on.faultStrikeX(), on.faultStrikeZ());
    }

    /** The cells within {@code reach} blocks of a point, looked up already or queued for it. */
    private static void around(ServerLevel level, int x, int z, int reach, LongOpenHashSet into) {
        Long2ObjectOpenHashMap<Segment> cells = CELLS.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        LongArrayFIFOQueue todo = TODO.computeIfAbsent(level.dimension(), k -> new LongArrayFIFOQueue());
        LongOpenHashSet queued = QUEUED.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
        int r = reach / SEG + 1, ccx = Math.floorDiv(x, SEG), ccz = Math.floorDiv(z, SEG);
        long r2 = (long) (reach + SEG) * (reach + SEG);
        for (int cx = ccx - r; cx <= ccx + r; cx++) {
            for (int cz = ccz - r; cz <= ccz + r; cz++) {
                long dx = cx * (long) SEG + SEG / 2 - x, dz = cz * (long) SEG + SEG / 2 - z;
                if (dx * dx + dz * dz > r2) continue;
                long k = cell(cx, cz);
                if (cells.containsKey(k)) {
                    into.add(k);
                } else if (queued.add(k)) {
                    todo.enqueue(k);
                }
            }
        }
    }

    /** Runs every stretch in reach on through {@code ticks} of its cycle, and breaks those whose time it is. */
    private static void roll(ServerLevel level, int ticks) {
        List<ServerPlayer> players = level.players();
        Clocks clocks = of(level);
        LongOpenHashSet near = new LongOpenHashSet();
        int feltReach = GeyserConfig.QUAKE_FELT_RANGE.get();
        for (ServerPlayer p : players) around(level, p.getBlockX(), p.getBlockZ(), feltReach, near);
        int hear = GeyserConfig.SEISMOGRAPH_RANGE.get();
        LongOpenHashSet gone = new LongOpenHashSet();
        for (long st : clocks.stations) {
            BlockPos at = BlockPos.of(st);
            // A station known to be gone is let go; one in a chunk nobody has loaded is taken as still there.
            if (com.jeladastudios.ftsgeology.util.Loaded.at(level, at)
                    && !(level.getBlockEntity(at) instanceof com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity)) {
                gone.add(st);
                continue;
            }
            around(level, at.getX(), at.getZ(), Math.min(hear, 4096), near);
        }
        if (!gone.isEmpty()) {
            clocks.stations.removeAll(gone);
            clocks.setDirty();
        }
        if (near.isEmpty()) return;
        Long2ObjectOpenHashMap<Segment> cells = CELLS.get(level.dimension());
        long now = level.getGameTime();
        List<Segment> due = new ArrayList<>();
        for (long k : near) {
            Segment s = cells.get(k);
            if (s == null || s == NONE) continue;
            double mean = meanTicks(s);
            if (!clocks.last.containsKey(k)) {
                // First seen: somewhere in its cycle, as a stretch of boundary nobody has watched would be.
                long age = (long) (mean * Math.sqrt(2.0 / Math.PI) * Math.abs(level.random.nextGaussian()));
                clocks.last.put(k, now - age);
                clocks.setDirty();
            }
            double age = Math.max(0, now - clocks.last.get(k));
            // Rayleigh renewal with this mean: the chance it goes in the next ticks, given it has held this long.
            double a = Math.PI / 4.0 / (mean * mean);
            double p = 1.0 - Math.exp(-a * ((age + ticks) * (age + ticks) - age * age));
            rolled++;
            if (level.random.nextDouble() < p) due.add(s);
        }
        for (Segment s : due) breakAt(level, clocks, s, players);
        if (GeyserConfig.SMALL_QUAKES.get()) smallQuakes(level, near, cells, ticks, players);
    }

    /**
     * The small quakes between the large ones, 2.5 to 5: each stretch has them at its own rate by its stress, sizes by
     * Gutenberg-Richter (most of them small), anywhere along it. Nothing breaks; one a player feels, if only faintly,
     * is felt and recorded, and the rest only recorded for the seismographs.
     */
    private static void smallQuakes(ServerLevel level, LongOpenHashSet near, Long2ObjectOpenHashMap<Segment> cells, int ticks,
                                    List<ServerPlayer> players) {
        double rate = GeyserConfig.SMALL_QUAKE_RATE.get() * SMALL_SHARE * ticks / 24000.0;
        if (rate <= 0) return;
        for (long k : near) {
            Segment s = cells.get(k);
            if (s == null || s == NONE) continue;
            // How many in this roll, Poisson: nearly always none, now and then one.
            double none = Math.exp(-rate * Math.max(0.15, s.stress()));
            double draw = level.random.nextDouble();
            while (draw > none) {
                smallQuake(level, s, players);
                draw *= level.random.nextDouble();
            }
        }
    }

    private static void smallQuake(ServerLevel level, Segment s, List<ServerPlayer> players) {
        double m = SMALL_FROM - Math.log10(1.0 - level.random.nextDouble() * (1.0 - Math.pow(10.0, -(SMALLEST - SMALL_FROM))));
        double along = (level.random.nextDouble() - 0.5) * SEG;
        int x = (int) Math.round(s.x() + s.strikeX() * along), z = (int) Math.round(s.z() + s.strikeZ() * along);
        double floor = FeltShaking.floor(), nearest = Double.MAX_VALUE;
        double depthM = Earthquake.quakeDepthMetres(s.type(), m, level.random);
        boolean anyone = false;
        for (ServerPlayer p : players) {
            double d = Math.hypot(p.getX() - x, p.getZ() - z);
            nearest = Math.min(nearest, d);
            if (FeltShaking.intensity(m, d, depthM) >= floor) anyone = true;
        }
        int y = com.jeladastudios.ftsgeology.util.Loaded.at(level, x, z)
                ? com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z) : level.getSeaLevel();
        if (y == Integer.MIN_VALUE) y = level.getSeaLevel();
        BlockPos epi = new BlockPos(x, y, z);
        small++;
        if (anyone) {
            smallFelt++;
            Earthquake.tremor(level, epi, s.type(), m, s.strikeX(), s.strikeZ(), false, depthM);
        } else {
            com.jeladastudios.ftsgeology.instrument.SeismicNetwork.record(level, epi, s.type(), m, depthM);
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info("small quake: M{} {} at {} {}, {} blocks from the nearest player: {}",
                String.format(Locale.ROOT, "%.1f", m), s.type(), x, z, nearest == Double.MAX_VALUE ? "-" : String.valueOf((int) nearest),
                anyone ? "felt" : "filed for the seismographs");
    }

    /** One stretch lets go. */
    private static void breakAt(ServerLevel level, Clocks clocks, Segment s, List<ServerPlayer> players) {
        double m = magnitude(s.type(), level.random);
        long now = level.getGameTime();
        broke++;
        // The rupture runs along the fault both ways: the stretches it crossed have let go too.
        int half = QuakePlanner.ruptureLengthBlocks(m) / 2;
        Long2ObjectOpenHashMap<Segment> cells = CELLS.get(level.dimension());
        int r = half / SEG + 1, ccx = Math.floorDiv(s.x(), SEG), ccz = Math.floorDiv(s.z(), SEG);
        for (int cx = ccx - r; cx <= ccx + r; cx++) {
            for (int cz = ccz - r; cz <= ccz + r; cz++) {
                long k = cell(cx, cz);
                Segment o = cells.get(k);
                if (o == null || o == NONE || o.type() != s.type()) continue;
                double d2 = Mth.square((double) o.x() - s.x()) + Mth.square((double) o.z() - s.z());
                if (d2 > Mth.square((double) half + SEG / 2)) continue;
                clocks.last.put(k, now);
            }
        }
        // What the rupture let go of is passed on to the stretches just beyond its ends: they are loaded the more, and
        // brought nearer their own breaking, as each great quake on the North Anatolian Fault from 1939 to 1999 brought on
        // the next one west of it. Each by up to a third of its cycle, the more the nearer.
        int beyond = r + 2;
        for (int cx = ccx - beyond; cx <= ccx + beyond; cx++) {
            for (int cz = ccz - beyond; cz <= ccz + beyond; cz++) {
                long k = cell(cx, cz);
                Segment o = cells.get(k);
                if (o == null || o == NONE || o.type() != s.type() || !clocks.last.containsKey(k)) continue;
                double d = Math.sqrt(Mth.square((double) o.x() - s.x()) + Mth.square((double) o.z() - s.z()));
                if (d <= half + SEG / 2.0 || d > half + SEG * 2.5) continue;
                double push = (1.0 - (d - half - SEG / 2.0) / (SEG * 2.0)) * meanTicks(o) / 3.0;
                clocks.last.put(k, clocks.last.get(k) - (long) Math.max(0, push));
                loaded++;
            }
        }
        clocks.last.put(cell(ccx, ccz), now);
        clocks.setDirty();

        // Felt by anyone? Then it is a quake as any; otherwise only the seismographs hear of it.
        boolean anyone = false, faintly = false;
        double nearest = Double.MAX_VALUE;
        double range = FeltShaking.feltRange(m);
        // From the rupture, which runs along the fault both ways as far as a quake this size breaks.
        double reach = Math.min(8000.0, 0.5 * Math.pow(10.0, 0.69 * m - 3.22) * 1000.0
                / com.jeladastudios.ftsgeology.tectonics.DepthScale.metresPerBlockHorizontal());
        double sn = Math.hypot(s.strikeX(), s.strikeZ());
        for (ServerPlayer p : players) {
            double dx = p.getX() - s.x(), dz = p.getZ() - s.z();
            if (sn > 1e-6) {
                double along = Mth.clamp((dx * s.strikeX() + dz * s.strikeZ()) / sn, -reach, reach);
                dx -= along * s.strikeX() / sn;
                dz -= along * s.strikeZ() / sn;
            }
            double d = Math.sqrt(dx * dx + dz * dz);
            nearest = Math.min(nearest, d);
            if (d > range) continue;
            double i = FeltShaking.intensity(m, d);
            if (i >= FeltShaking.FELT) anyone = true;
            else if (i >= FeltShaking.floor()) faintly = true;
        }
        int y = com.jeladastudios.ftsgeology.util.Loaded.at(level, s.x(), s.z())
                ? com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, s.x(), s.z()) : level.getSeaLevel();
        if (y == Integer.MIN_VALUE) y = level.getSeaLevel();
        BlockPos epi = new BlockPos(s.x(), y, s.z());
        if (anyone) {
            felt++;
            if (m >= Aftershocks.RUPTURE_MIN) {
                // A great earthquake may be announced minutes before by a smaller one on the same spot.
                if (!Aftershocks.foreshock(level, epi, s.type(), m, s.strikeX(), s.strikeZ())) {
                    Earthquake.trigger(level, epi, s.type(), m, s.strikeX(), s.strikeZ());
                }
            } else {
                Earthquake.tremor(level, epi, s.type(), m, s.strikeX(), s.strikeZ(), false);
            }
        } else if (faintly) {
            // Far off: only a faint tremor where anyone is, and the instruments' record; its ground waits unbroken.
            felt++;
            Earthquake.tremor(level, epi, s.type(), m, s.strikeX(), s.strikeZ(), false);
        } else {
            filed++;
            com.jeladastudios.ftsgeology.instrument.SeismicNetwork.record(level, epi, s.type(), m,
                    Earthquake.quakeDepthMetres(s.type(), m, level.random));
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info("fault clock: M{} {} at {} {}, {} blocks from the nearest player: {}",
                String.format(Locale.ROOT, "%.1f", m), s.type(), s.x(), s.z(),
                nearest == Double.MAX_VALUE ? "-" : String.valueOf((int) nearest), anyone ? "felt" : faintly ? "felt faintly" : "filed for the seismographs");
    }

    /** The largest a boundary of this kind breaks in. */
    static double largest(FaultType type) {
        return switch (type) {
            case CONVERGENT_SUBDUCTION -> 9.2;
            case CONVERGENT_COLLISION -> 8.0;
            case TRANSFORM -> 7.5;
            case DIVERGENT -> 6.2;
            case INTERIOR -> SMALLEST;
        };
    }

    /** Gutenberg-Richter with b = 1 from {@link #SMALLEST} to the boundary's largest. */
    static double magnitude(FaultType type, net.minecraft.util.RandomSource rng) {
        double span = largest(type) - SMALLEST;
        return SMALLEST - Math.log10(1.0 - rng.nextDouble() * (1.0 - Math.pow(10.0, -span)));
    }

    public static long rolled() {
        return rolled;
    }

    public static String summary() {
        int cells = 0, segments = 0;
        for (Long2ObjectOpenHashMap<Segment> m : CELLS.values()) {
            cells += m.size();
            for (Segment s : m.values()) if (s != NONE) segments++;
        }
        return String.format(Locale.ROOT, "fault clocks: %d cells looked up, %d stretches of boundary, %d rolls, %d broke (%d felt, %d filed), %d stretches beyond them loaded, %d small quakes (%d felt)",
                cells, segments, rolled, broke, felt, filed, loaded, small, smallFelt);
    }
}
