package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import com.jeladastudios.ftsgeology.quake.QuakePlanner;
import java.util.concurrent.CompletableFuture;

/**
 * Runs earthquakes: plans them on a worker thread, then applies the deformation on the server
 * thread a slice at a time. A real rupture takes tens of seconds to run along its fault, so a quake
 * tearing its way along over a few seconds is also the accurate choice.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Earthquake {

    private Earthquake() {}

    /**
     * How every quake write goes: to the clients, without neighbour shape updates. A shape update ran
     * on six neighbours per block, cost more than the deformation itself, dropped every plant it
     * undermined as an item, and loaded the chunk next door on the server thread when the neighbour
     * lay in one that was not loaded. The settling passes clear what a shape update would have.
     */
    static final int FLAGS = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;

    /** A quake whose plan is ready and whose edits are being applied a few per tick. */
    private static final class Running {
        final ResourceKey<Level> dimension;
        final BlockPos epicentre;
        final QuakePlanner.Plan plan;
        final Deque<QuakePlanner.Edit> pending;
        /** The rupture itself, so a chunk that unloads mid-way can be parked and replayed later. */
        final FaultType type;
        final double depthMetres;
        final long seed;
        final boolean mayBreak;
        final List<QuakePlanner.TracePoint> trace;
        /** Chunks that went away while their ground was moving: the rest of their edits wait for them. */
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet parked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        /** When the ground may start moving: the warning window lets seismographs sound first. */
        long startAt;
        int shakeTicks;
        int applied;
        int ticks;
        /** Whether the shaking of buildings has been set going; see {@link ShakingDamage}. */
        boolean shaking;

        Running(ResourceKey<Level> dimension, QuakePlanner.Plan plan, long startAt, FaultType type,
                double depthMetres, long seed, boolean mayBreak, List<QuakePlanner.TracePoint> trace) {
            this.dimension = dimension;
            this.epicentre = plan.epicentre();
            this.plan = plan;
            this.startAt = startAt;
            this.type = type;
            this.depthMetres = depthMetres;
            this.seed = seed;
            this.mayBreak = mayBreak;
            this.trace = trace;
            this.pending = new ArrayDeque<>(plan.edits());
            // The camera shake is capped even when the edits run for minutes: real strong motion
            // lasts tens of seconds.
            this.shakeTicks = Mth.clamp(plan.edits().size()
                    / Math.max(1, GeyserConfig.QUAKE_BLOCKS_PER_TICK.get()) + 40, 40, 400);
        }
    }

    private static final List<Running> ACTIVE = new ArrayList<>();

    /** Quakes whose ground is still being read, a slice a tick, before they are planned. */
    private static final List<Preparing> PREPARING = new ArrayList<>();

    private record Preparing(ResourceKey<Level> dimension, QuakePlanner.SnapshotJob job, List<QuakePlanner.TracePoint> trace,
                             BlockPos epicentre, FaultType type, double magnitude, double depthMetres, long seed,
                             boolean mayBreak, long startAt) {}

    /** How long the ground of a new quake is read for in a tick. A quake is rare, and its warning gives the time. */
    private static final long PREPARE_NANOS = 15_000_000L;

    /** Whether a rupture is still moving the ground somewhere, or about to; the settling waits for it. */
    public static boolean moving() {
        return !ACTIVE.isEmpty() || !PREPARING.isEmpty();
    }
    private static int ambientTimer = 0;

    // === Public API =========================================================

    /**
     * Triggers a quake at a column, taking the fault type and strike from the tectonic model.
     * Returns false when the column is not on a fault - plate interiors do not rupture.
     */
    public static boolean triggerHere(ServerLevel level, BlockPos at, double magnitudeOverride) {
        PlateSample s = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, at.getX(), at.getZ());
        if (s.faultType() == FaultType.INTERIOR) return false;
        double magnitude = magnitudeOverride > 0 ? magnitudeOverride
                : rollMagnitude(s.faultType(), s.stress(), level.random);
        trigger(level, at, s.faultType(), magnitude, s.faultStrikeX(), s.faultStrikeZ());
        return true;
    }

    /**
     * Triggers a quake of an explicit type, so every deformation style can be demonstrated
     * side by side on flat ground regardless of the local geology.
     */
    public static void trigger(ServerLevel level, BlockPos epicentre, FaultType type,
                               double magnitude, double strikeX, double strikeZ) {
        trigger(level, epicentre, type, magnitude, strikeX, strikeZ, false);
    }

    /**
     * @param forced true when the caller picked the fault type rather than reading it from the
     *               ground; a forced rupture runs its full length through any terrain
     */
    public static void trigger(ServerLevel level, BlockPos epicentre, FaultType type,
                               double magnitude, double strikeX, double strikeZ, boolean forced) {
        trigger(level, epicentre, type, magnitude, strikeX, strikeZ, forced, false);
    }

    /** @param aftershock true for one of another quake's aftershocks, which has none of its own */
    public static void trigger(ServerLevel level, BlockPos epicentre, FaultType type,
                               double magnitude, double strikeX, double strikeZ, boolean forced, boolean aftershock) {
        if (!GeyserConfig.QUAKES_ENABLED.get() || type == FaultType.INTERIOR) return;

        // Put the hypocentre on the fault first, so quakes fired from different spots follow one line.
        BlockPos epi = epicentre;
        PlateSample here = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, epicentre.getX(), epicentre.getZ());
        if (here.onFault() && here.faultDistance() > 1.0) {
            epi = new BlockPos(
                    epicentre.getX() + (int) Math.round(here.faultNormalX() * here.faultDistance()),
                    epicentre.getY(),
                    epicentre.getZ() + (int) Math.round(here.faultNormalZ() * here.faultDistance()));
        }
        final BlockPos epicentreOnFault = epi;

        // Fixed polarity: which side dives comes from the boundary, not from the side sampled, so two
        // quakes on one margin never mirror it. Oceanic crust dives; between two of a kind, the lower
        // plate id does.
        double sx = strikeX, sz = strikeZ;
        if ((type == FaultType.CONVERGENT_SUBDUCTION || type == FaultType.CONVERGENT_COLLISION)
                && here.downGoing()) {
            sx = -strikeX;
            sz = -strikeZ;
        }

        // Every stage is timed and logged.
        long t0 = System.nanoTime();
        List<QuakePlanner.TracePoint> trace =
                QuakePlanner.traceFault(level, epicentreOnFault, type, magnitude, sx, sz, forced);
        long t1 = System.nanoTime();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("quake trace: {} points, {} blocks long, corridor +/-{} in {} ms",
                trace.size(), QuakePlanner.ruptureLengthBlocks(magnitude),
                QuakePlanner.deformationHalfWidth(type, magnitude), (t1 - t0) / 1_000_000);
        if (trace.isEmpty()) return;

        double depthM = quakeDepthMetres(type, magnitude, level.random);
        boolean mayBreak = GeyserConfig.QUAKES_BREAK_BUILDS.get();
        long seed = level.random.nextLong();
        ResourceKey<Level> dim = level.dimension();

        // The corridor's unloaded chunks are parked now; the loaded ones are read over the next ticks.
        java.util.Map<Long, List<QuakePlanner.TracePoint>> loaded =
                PendingEdits.register(level, epicentreOnFault, type, magnitude, depthM, seed, mayBreak, trace);
        long t2 = System.nanoTime();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("quake register done in {} ms", (t2 - t1) / 1_000_000);
        QuakePlanner.SnapshotJob job = new QuakePlanner.SnapshotJob(level, trace, type, magnitude, null,
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(loaded.keySet()),
                ck -> PendingEdits.park(level, net.minecraft.world.level.ChunkPos.getX(ck), net.minecraft.world.level.ChunkPos.getZ(ck), epicentreOnFault, type, magnitude,
                        depthM, seed, mayBreak, loaded.get(ck)));

        // Filed for the instruments; a station in an unloaded chunk reads back what it missed.
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork
                .record(level, epicentreOnFault, type, magnitude, depthM);

        announce(level, trace, epicentreOnFault, type, magnitude, depthM, aftershock);

        // The ground is held back by the warning window, which gives the planning below that long.
        long startAt = level.getGameTime() + GeyserConfig.QUAKE_WARNING_TICKS.get();
        // Its waves go out from the rupture as it runs, and are felt wherever each player is.
        FeltShaking.start(level, epicentreOnFault, trace, magnitude, depthM, startAt);
        // And the fault goes on slipping for days.
        if (!aftershock) Aftershocks.afterMain(level, trace, type, magnitude);

        PREPARING.add(new Preparing(dim, job, trace, epicentreOnFault, type, magnitude, depthM, seed, mayBreak, startAt));
    }

    /** Reads the ground of the quakes being prepared, a slice a tick, and has each planned once it is all in. */
    private static void prepare(net.minecraft.server.MinecraftServer server) {
        if (PREPARING.isEmpty()) return;
        long budget = PREPARE_NANOS / PREPARING.size();
        PREPARING.removeIf(p -> {
            ServerLevel level = server.getLevel(p.dimension());
            if (level == null) return true;
            if (!p.job().step(budget)) return false;
            com.jeladastudios.ftsgeology.util.Diagnostics.info("quake snapshot: {}", p.job().timing());
            plan(level, p);
            return true;
        });
    }

    private static void plan(ServerLevel level, Preparing p) {
        QuakePlanner.Snapshot snap = p.job().snapshot();
        List<QuakePlanner.TracePoint> trace = p.trace();
        BlockPos epicentreOnFault = p.epicentre();
        FaultType type = p.type();
        double magnitude = p.magnitude(), depthM = p.depthMetres();
        long seed = p.seed(), startAt = p.startAt();
        boolean mayBreak = p.mayBreak();
        ResourceKey<Level> dim = p.dimension();
        // Worker thread: the expensive half. Touches nothing but the immutable snapshot.
        CompletableFuture
                .supplyAsync(() -> {
                    long p0 = System.nanoTime();
                    QuakePlanner.Plan plan = QuakePlanner.plan(snap, trace, epicentreOnFault, type,
                            magnitude, depthM, new Random(seed), mayBreak);
                    com.jeladastudios.ftsgeology.util.Diagnostics.info("quake plan: {} edits in {} ms",
                            plan.edits().size(), (System.nanoTime() - p0) / 1_000_000);
                    return plan;
                }, Util.backgroundExecutor())
                .thenAcceptAsync(plan -> {
                    ACTIVE.add(new Running(dim, plan, startAt, type, depthM, seed, mayBreak, trace));
                    // Everything geothermal in the corridor stands down until the ground has
                    // stopped moving AND the debris has landed. See QuakeQuiet.
                    QuakeQuiet.open(level, plan.epicentre(), plan.ruptureLength(), plan.magnitude());
                    com.jeladastudios.ftsgeology.util.Diagnostics.info("quake apply starting: {} edits queued", plan.edits().size());
                }, level.getServer())
                .exceptionally(t -> {
                    GeysersMod.LOGGER.warn("Earthquake planning failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * A quake too small to break the ground, or one out where nobody is: filed for the instruments, and felt by
     * whoever is near enough, but with no rupture. An aftershock's smaller jolts are these.
     */
    public static void tremor(ServerLevel level, BlockPos at, FaultType type, double magnitude,
                              double strikeX, double strikeZ, boolean aftershock) {
        if (!GeyserConfig.QUAKES_ENABLED.get() || type == FaultType.INTERIOR) return;
        double depthM = quakeDepthMetres(type, magnitude, level.random);
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork.record(level, at, type, magnitude, depthM);
        List<QuakePlanner.TracePoint> trace = List.of(new QuakePlanner.TracePoint(at.getX(), at.getZ(), strikeX, strikeZ, 0.0));
        announce(level, trace, at, type, magnitude, depthM, aftershock);
        long startAt = level.getGameTime() + GeyserConfig.QUAKE_WARNING_TICKS.get();
        FeltShaking.start(level, at, trace, magnitude, depthM, startAt);
        ShakingDamage.start(level, at, trace, magnitude, depthM, startAt);
        Landslides.start(level, at, trace, magnitude, depthM, startAt);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("tremor: M{} at {} {}{}",
                String.format(Locale.ROOT, "%.1f", magnitude), at.getX(), at.getZ(), aftershock ? ", an aftershock" : "");
    }

    /** Stops every running quake and forgets everything parked. */
    public static int cancelAll() {
        int n = ACTIVE.size() + PREPARING.size();
        ACTIVE.clear();
        PREPARING.clear();
        PendingEdits.clear();
        Weathering.clear();
        QuakeQuiet.clear();     // nothing left to settle, so nothing left to wait for
        ShakingDamage.clear();
        Liquefaction.clear();
        Landslides.clear();
        Collapse.clear();
        return n;
    }

    // === Per-tick application ===============================================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        applyPending(event);
        FeltShaking.tick(event.getServer());
        Aftershocks.tick(event.getServer());
        tickAmbient(event);
    }

    private static void applyPending(TickEvent.ServerTickEvent event) {
        // Shares the whole mod's tick budget with retrogen and volcano construction; see TickBudget.
        com.jeladastudios.ftsgeology.util.TickBudget.open(event.getServer().getTickCount());

        // Replay any parked rupture whose chunk has arrived. Time-budgeted, on the server thread.
        PendingEdits.drain(event.getServer(),
                com.jeladastudios.ftsgeology.util.TickBudget.slice(0.4));

        // Ground the last quake tore up goes on settling in the background.
        Weathering.drain(event.getServer(),
                com.jeladastudios.ftsgeology.util.TickBudget.slice(0.3));

        // Cave roofs the shaking loaded past what they could carry, coming down along the rupture.
        CaveCollapse.drain(event.getServer(),
                com.jeladastudios.ftsgeology.util.TickBudget.slice(0.2));

        // Buildings shedding what the shaking loosened, a few chunks at a time while the ground moves.
        ShakingDamage.drain(event.getServer(),
                com.jeladastudios.ftsgeology.util.TickBudget.slice(0.2));

        // Buildings on moved ground, and the parts of others the shaking broke, coming down.
        Collapse.drain(event.getServer(),
                com.jeladastudios.ftsgeology.util.TickBudget.slice(0.2));

        // Wet sand going quick under the shaking: boils, and what stands on it settling.
        Liquefaction.drain(event.getServer(),
                com.jeladastudios.ftsgeology.util.TickBudget.slice(0.15));

        // Steep slopes shedding their cover.
        Landslides.drain(event.getServer(),
                com.jeladastudios.ftsgeology.util.TickBudget.slice(0.15));

        // New quakes' ground, read a slice at a time before they are planned.
        prepare(event.getServer());

        // Release quiet zones whose own debris has landed; after the drains, so it can happen this tick.
        for (ServerLevel l : event.getServer().getAllLevels()) {
            QuakeQuiet.tick(l);
        }

        if (ACTIVE.isEmpty()) return;
        int budget = GeyserConfig.QUAKE_BLOCKS_PER_TICK.get();
        // Hard wall-clock brake: a mis-estimated block count makes the quake slower, never the tick.
        // The visible half of the mod, so it may use whatever the tick has left.
        long deadline = System.nanoTime()
                + com.jeladastudios.ftsgeology.util.TickBudget.remaining();

        ACTIVE.removeIf(run -> {
            ServerLevel level = event.getServer().getLevel(run.dimension);
            if (level == null) return true;

            // Still in the warning window: filed and alerting, but the ground holds until startAt.
            if (level.getGameTime() < run.startAt) return false;
            if (!run.shaking) {
                run.shaking = true;
                ShakingDamage.start(level, run.epicentre, run.trace, run.plan.magnitude(), run.depthMetres,
                        level.getGameTime());
                Liquefaction.start(level, run.epicentre, run.trace, run.plan.magnitude(), run.depthMetres,
                        level.getGameTime());
                Landslides.start(level, run.epicentre, run.trace, run.plan.magnitude(), run.depthMetres,
                        level.getGameTime());
            }

            int placed = 0;
            int examined = 0;
            int scanLimit = budget * 4;
            while (placed < budget && examined < scanLimit && !run.pending.isEmpty()
                    && System.nanoTime() < deadline) {
                QuakePlanner.Edit e = run.pending.poll();
                examined++;
                long ck = net.minecraft.world.level.ChunkPos.asLong(e.pos().getX() >> 4, e.pos().getZ() >> 4);
                if (!run.parked.contains(ck) && level.hasChunkAt(e.pos())) {
                    QuakeWrites.set(level, e.pos(), com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, e.pos(), e.state()));
                    placed++;
                } else {
                    // The chunk went away since the snapshot: the rest of its edits wait for it, in order, and are
                    // written when it comes back. The whole rupture used to be planned there again from the ground
                    // half moved, and it moved twice: a column lifted twice over, the snow on it left in the air.
                    run.parked.add(ck);
                    PendingEdits.parkEdit(level, e);
                }
            }
            run.applied += placed;
            run.ticks++;
            if (run.ticks % 100 == 0) {
                com.jeladastudios.ftsgeology.util.Diagnostics.info("quake apply: {} placed, {} left, {} ticks",
                        run.applied, run.pending.size(), run.ticks);
            }
            run.shakeTicks--;
            boolean done = run.pending.isEmpty() && run.shakeTicks <= 0;
            if (done) {
                com.jeladastudios.ftsgeology.util.Diagnostics.info("quake finished: {} blocks over {} ticks, {} chunks left waiting",
                        run.applied, run.ticks, run.parked.size());
                // A chunk that went and came back while the rest was moving has its edits written now.
                PendingEdits.release(level, run.parked);
                com.jeladastudios.ftsgeology.util.Diagnostics.info("{}; {}; {}; {}; {}; {}", ShakingDamage.summary(),
                        FeltShaking.summary(), Liquefaction.summary(), Landslides.summary(), Collapse.summary(),
                        Structural.summary());
                // The shaking stops, but the ground it left is raw. Let it relax.
                Weathering.enqueue(level, run.plan.edits());
                // And the caves under it: an arch that stood for ten thousand years can fail in a minute.
                CaveCollapse.enqueue(level, run.plan);
                // What stood on ground that moved comes down with it.
                Collapse.wreckColumns(level, run.plan.wrecked(), 200);
                // The corridor stays shut until that settling is done.
                QuakeQuiet.settling(level, run.plan.epicentre());
                // New springs are seeded now, but do not start climbing until the zone releases.
                com.jeladastudios.ftsgeology.hydrology.SpringSeeding.afterQuake(
                        level, run.plan.epicentre(), run.plan.ruptureLength(), run.plan.magnitude());
            }
            return done;
        });
    }

    // === Ambient quakes =====================================================

    /**
     * Occasionally ruptures a stressed fault near a player. Only ever fires where somebody is
     * actually loaded in, and the chance is weighted by tectonic stress, so quiet plate interiors
     * stay quiet and active boundaries do not.
     */
    private static void tickAmbient(TickEvent.ServerTickEvent event) {
        int interval = GeyserConfig.QUAKE_AMBIENT_INTERVAL.get();
        if (interval <= 0 || !GeyserConfig.QUAKES_ENABLED.get()) return;
        if (++ambientTimer < interval) return;
        ambientTimer = 0;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) continue;
            ServerPlayer p = players.get(level.random.nextInt(players.size()));

            // Look for a fault a little way off, so the epicentre is nearby but not underfoot.
            int reach = GeyserConfig.QUAKE_SEARCH_RADIUS.get();
            int ox = level.random.nextInt(reach * 2 + 1) - reach;
            int oz = level.random.nextInt(reach * 2 + 1) - reach;
            int x = p.blockPosition().getX() + ox;
            int z = p.blockPosition().getZ() + oz;
            PlateSample s = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, x, z);
            if (s.faultType() == FaultType.INTERIOR) continue;
            // Recurrence, not a coin flip: the chance comes from a target interval in days, shorter
            // on a highly stressed fault.
            double days = GeyserConfig.QUAKE_RECURRENCE_DAYS.get() / Math.max(0.15, s.stress());
            double rollsPerDay = 24000.0 / Math.max(1, interval);
            if (level.random.nextDouble() > 1.0 / Math.max(1.0, days * rollsPerDay)) continue;

            int y = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z);
            if (y == Integer.MIN_VALUE) continue;
            BlockPos epi = new BlockPos(x, y, z);
            double m = rollMagnitude(s.faultType(), s.stress(), level.random);
            // A great earthquake may be announced minutes before by a smaller one on the same spot.
            if (!Aftershocks.foreshock(level, epi, s.faultType(), m, s.faultStrikeX(), s.faultStrikeZ())) {
                trigger(level, epi, s.faultType(), m, s.faultStrikeX(), s.faultStrikeZ());
            }
            return; // at most one ambient quake per roll
        }
    }

    // === Magnitude and depth ================================================

    /**
     * Magnitude from a truncated Gutenberg-Richter distribution (b = 1): each step up is about ten
     * times rarer. The bands follow the real order: subduction, collision, strike-slip, rift.
     */
    public static double rollMagnitude(FaultType type, double stress, RandomSource rng) {
        double lo, hi;
        switch (type) {
            case CONVERGENT_SUBDUCTION -> { lo = 6.5; hi = 9.2; }
            case CONVERGENT_COLLISION -> { lo = 6.0; hi = 8.0; }
            case TRANSFORM -> { lo = 5.2; hi = 7.5; }
            case DIVERGENT -> { lo = 4.5; hi = 6.2; }
            default -> { return 0.0; }
        }
        double span = hi - lo;
        // Inverse of the truncated Gutenberg-Richter distribution with b = 1.
        double u = rng.nextDouble();
        double m = lo - Math.log10(1.0 - u * (1.0 - Math.pow(10.0, -span)));
        // A locked, highly stressed fault has stored more to release, so it reaches higher within
        // its band - but the shape of the distribution stays the same.
        return Mth.clamp(m + stress * 0.6, lo, hi);
    }


    /**
     * How deep a quake breaks, in metres. Crustal faults break between a few kilometres and the bottom of the brittle
     * crust, twenty or so. A subduction zone's great quakes are on its megathrust, ten to forty-five kilometres down;
     * of its smaller ones some are in the slab under it, from sixty kilometres to a few hundred, and so is the odd one
     * under a collision belt, as under the Hindu Kush.
     */
    public static double quakeDepthMetres(FaultType type, double magnitude, RandomSource rng) {
        double km = switch (type) {
            case TRANSFORM -> 5 + 13 * rng.nextDouble();
            case DIVERGENT -> 3 + 9 * rng.nextDouble();
            case CONVERGENT_COLLISION -> magnitude < 7.5 && rng.nextDouble() < 0.1
                    ? 70 + 130 * rng.nextDouble() : 8 + 17 * rng.nextDouble();
            case CONVERGENT_SUBDUCTION -> magnitude < 7.5 && rng.nextDouble() < 0.3
                    ? 60 + 400 * Math.pow(rng.nextDouble(), 1.6) : 10 + 35 * rng.nextDouble();
            case INTERIOR -> 5 + 15 * rng.nextDouble();
        };
        return km * 1000.0;
    }

    /**
     * Told at once to everyone who will feel it: the alert travels at the speed of light, the shaking at the speed of
     * the waves, so a player far out has seconds to spare.
     */
    private static void announce(ServerLevel level, List<QuakePlanner.TracePoint> trace, BlockPos at, FaultType type,
                                 double magnitude, double depthMetres, boolean aftershock) {
        // The magnitude is formatted here, not in the lang file: Minecraft's translation formatter
        // only understands %s, %d and positional %N$s, and throws on a %.1f.
        Component msg = Component.translatable(aftershock ? "message.fts_geology.aftershock" : "message.fts_geology.earthquake",
                String.format(Locale.ROOT, "%.1f", magnitude), label(type), DepthScale.format(depthMetres))
                .withStyle(ChatFormatting.RED);
        double radius = 260 + magnitude * 60;
        double r2 = radius * radius;
        java.util.Set<ServerPlayer> told = new java.util.HashSet<>(FeltShaking.within(level, trace, magnitude));
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.getX() + 0.5, p.getY(), at.getZ() + 0.5) <= r2) told.add(p);
        }
        for (ServerPlayer p : told) p.sendSystemMessage(msg);
    }

    private static String label(FaultType type) {
        return switch (type) {
            case CONVERGENT_SUBDUCTION -> "subduction thrust";
            case CONVERGENT_COLLISION -> "collision thrust";
            case TRANSFORM -> "strike-slip";
            case DIVERGENT -> "rift normal fault";
            case INTERIOR -> "intraplate";
        };
    }
}
