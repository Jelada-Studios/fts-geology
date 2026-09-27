package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What a large earthquake leaves behind: the fault goes on slipping in smaller jolts for days, many at first and fewer
 * and fewer after, the largest of them about a magnitude and a fifth below the main shock (Båth's law). The number
 * falls off with time as Omori found in 1894, {@code n(t) ~ 1 / (t + c)^p}; the sizes follow the same
 * Gutenberg-Richter law as every other quake. They strike along the fault that broke, a little to either side of it.
 *
 * <p>An aftershock large enough breaks the ground like any quake; a smaller one is only felt, and read by the
 * seismographs. The schedule is kept with the world, so a restart does not end a sequence. The same schedule holds a
 * great earthquake announced by a foreshock, due minutes later.</p>
 */
public final class Aftershocks {

    private Aftershocks() {}

    /** The smallest main shock that has a sequence worth following. */
    public static final double MAIN_MIN = 5.5;
    /** From this size up an aftershock breaks the ground like any quake; below it, it is only felt. */
    static final double RUPTURE_MIN = 5.5;
    /** Båth's law: the largest aftershock is about this much smaller than the main shock. */
    private static final double BATH = 1.2;
    /** Omori's p, and c in ticks: the rate falls a little faster than one over the time, from half a minute in. */
    private static final double P = 1.1, C = 600.0;
    /** The smallest aftershock scheduled: smaller ones are too weak to feel far, and too many. */
    private static final double SMALLEST = 3.5;
    /** Share of a great ambient earthquake that is announced minutes before by a foreshock. */
    private static final double GREAT = 7.0;

    /** One scheduled shock: an aftershock, or a main shock a foreshock came before. */
    public record Shock(long at, int x, int z, double magnitude, FaultType type, double strikeX, double strikeZ, boolean main) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("At", at);
            t.putInt("X", x);
            t.putInt("Z", z);
            t.putDouble("M", magnitude);
            t.putString("Type", type.name());
            t.putDouble("SX", strikeX);
            t.putDouble("SZ", strikeZ);
            t.putBoolean("Main", main);
            return t;
        }

        static Shock load(CompoundTag t) {
            FaultType type;
            try {
                type = FaultType.valueOf(t.getString("Type"));
            } catch (IllegalArgumentException e) {
                type = FaultType.TRANSFORM;
            }
            return new Shock(t.getLong("At"), t.getInt("X"), t.getInt("Z"), t.getDouble("M"), type,
                    t.getDouble("SX"), t.getDouble("SZ"), t.getBoolean("Main"));
        }
    }

    /** The shocks still to come in one dimension, soonest first. */
    static final class Schedule extends SavedData {
        final List<Shock> due = new ArrayList<>();

        static Schedule load(CompoundTag tag) {
            Schedule s = new Schedule();
            for (Tag t : tag.getList("Shocks", Tag.TAG_COMPOUND)) s.due.add(Shock.load((CompoundTag) t));
            s.due.sort(Comparator.comparingLong(Shock::at));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Shock s : due) list.add(s.save());
            tag.put("Shocks", list);
            return tag;
        }

        void add(Shock s) {
            due.add(s);
            due.sort(Comparator.comparingLong(Shock::at));
            setDirty();
        }
    }

    static Schedule of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Schedule::load, Schedule::new, "fts_geology_aftershocks");
    }

    /**
     * A main shock has broken the ground: its aftershocks are drawn up now. Their times follow Omori, their sizes
     * Gutenberg-Richter up to half a magnitude under the main shock, with one near Båth's size among the first third.
     */
    public static void afterMain(ServerLevel level, List<QuakePlanner.TracePoint> trace, FaultType type, double magnitude) {
        if (!GeyserConfig.QUAKE_AFTERSHOCKS.get() || magnitude < MAIN_MIN || trace.isEmpty()) return;
        double smallest = Math.max(SMALLEST, magnitude - 3.0);
        double largest = magnitude - 0.5;
        int n = (int) Math.round(Math.pow(10.0, magnitude - BATH - smallest) * GeyserConfig.AFTERSHOCK_RATE.get());
        n = Mth.clamp(n, 1, GeyserConfig.AFTERSHOCK_MAX.get());
        double span = GeyserConfig.AFTERSHOCK_DAYS.get() * 24000.0;
        RandomSource rng = level.random;
        long now = level.getGameTime();
        int reach = Math.max(24, QuakePlanner.deformationHalfWidth(type, magnitude));
        Schedule schedule = of(level);
        int biggest = rng.nextInt(Math.max(1, n / 3 + 1));
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < n; i++) times.add(omori(rng.nextDouble(), span));
        times.sort(Long::compare);
        for (int i = 0; i < n; i++) {
            double m = i == biggest
                    ? Math.min(largest, magnitude - BATH + rng.nextGaussian() * 0.15)
                    : smallest - Math.log10(1.0 - rng.nextDouble() * (1.0 - Math.pow(10.0, -(largest - smallest))));
            QuakePlanner.TracePoint t = trace.get(rng.nextInt(trace.size()));
            // Off to either side of the fault, as far as the ground it moved.
            double off = (rng.nextDouble() * 2 - 1) * reach;
            int x = t.x() + (int) Math.round(-t.strikeZ() * off);
            int z = t.z() + (int) Math.round(t.strikeX() * off);
            schedule.add(new Shock(now + GeyserConfig.QUAKE_WARNING_TICKS.get() + 100 + times.get(i), x, z, m, type,
                    t.strikeX(), t.strikeZ(), false));
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info("aftershocks: {} scheduled over {} days after an M{}, M{} to M{}",
                n, GeyserConfig.AFTERSHOCK_DAYS.get(), String.format(java.util.Locale.ROOT, "%.1f", magnitude),
                String.format(java.util.Locale.ROOT, "%.1f", smallest), String.format(java.util.Locale.ROOT, "%.1f", largest));
    }

    /** A time in ticks from the main shock, drawn from Omori's decay over {@code span} ticks. */
    static long omori(double u, double span) {
        double a = Math.pow(C, 1 - P), b = Math.pow(span + C, 1 - P);
        return Math.max(0L, Math.round(Math.pow(a - u * (a - b), 1.0 / (1 - P)) - C));
    }

    /**
     * Whether a great earthquake is announced by a foreshock: if so, the foreshock strikes now and the main shock is
     * put on the schedule for a few minutes later, and true is returned.
     */
    public static boolean foreshock(ServerLevel level, BlockPos at, FaultType type, double magnitude,
                                    double strikeX, double strikeZ) {
        if (!GeyserConfig.QUAKE_AFTERSHOCKS.get() || magnitude < GREAT
                || level.random.nextDouble() >= GeyserConfig.FORESHOCK_SHARE.get()) {
            return false;
        }
        long delay = 1200 + level.random.nextInt(4800);
        of(level).add(new Shock(level.getGameTime() + delay, at.getX(), at.getZ(), magnitude, type, strikeX, strikeZ, true));
        Earthquake.tremor(level, at, type, magnitude - 2.0 - level.random.nextDouble() * 0.5, strikeX, strikeZ, false);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("foreshock at {} {}: an M{} due in {} ticks", at.getX(), at.getZ(),
                String.format(java.util.Locale.ROOT, "%.1f", magnitude), delay);
        return true;
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            Schedule s = level.getDataStorage().get(Schedule::load, "fts_geology_aftershocks");
            if (s == null || s.due.isEmpty()) continue;
            long now = level.getGameTime();
            // One a second at most: a burst of due shocks after a long absence comes as a quick swarm, not all at once.
            if (s.due.get(0).at() > now) continue;
            Shock shock = s.due.remove(0);
            s.setDirty();
            fire(level, shock);
        }
    }

    private static void fire(ServerLevel level, Shock s) {
        int y = level.getSeaLevel();
        if (level.hasChunkAt(new BlockPos(s.x(), y, s.z()))) {
            int g = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, s.x(), s.z());
            if (g != Integer.MIN_VALUE) y = g;
        }
        BlockPos at = new BlockPos(s.x(), y, s.z());
        if (s.main()) {
            // The great earthquake the foreshock came before, with its own sequence to follow.
            Earthquake.trigger(level, at, s.type(), s.magnitude(), s.strikeX(), s.strikeZ(), false);
            return;
        }
        // A large aftershock breaks the ground where somebody is to see it; elsewhere, and when small, it is felt and
        // read by the instruments only.
        boolean near = false;
        double rupture = QuakePlanner.ruptureLengthBlocks(s.magnitude()) * 2.0 + 256;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(s.x(), p.getY(), s.z()) <= rupture * rupture) near = true;
        }
        if (s.magnitude() >= RUPTURE_MIN && near) {
            Earthquake.trigger(level, at, s.type(), s.magnitude(), s.strikeX(), s.strikeZ(), false, true);
        } else {
            Earthquake.tremor(level, at, s.type(), s.magnitude(), s.strikeX(), s.strikeZ(), true);
        }
    }

    /** The next shocks due in a dimension, for the command. */
    public static List<Shock> pending(ServerLevel level) {
        Schedule s = level.getDataStorage().get(Schedule::load, "fts_geology_aftershocks");
        return s == null ? List.of() : List.copyOf(s.due);
    }

    /** Forgets every scheduled shock in a dimension; how many there were. */
    public static int clear(ServerLevel level) {
        Schedule s = level.getDataStorage().get(Schedule::load, "fts_geology_aftershocks");
        if (s == null) return 0;
        int n = s.due.size();
        s.due.clear();
        s.setDirty();
        GeysersMod.LOGGER.debug("aftershocks cleared: {}", n);
        return n;
    }
}
