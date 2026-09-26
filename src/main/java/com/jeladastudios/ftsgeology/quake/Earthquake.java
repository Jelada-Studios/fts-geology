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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
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
        /** The corridor by chunk, worked out the first time a chunk is found unloaded. */
        java.util.Map<Long, List<QuakePlanner.TracePoint>> byChunk;
        /** When the ground may start moving: the warning window lets seismographs sound first. */
        long startAt;
        int shakeTicks;
        /**
         * How long the ground goes on rumbling. Separate from {@link #shakeTicks}: strong motion is
         * over in tens of seconds, while a large quake's deformation takes minutes to apply.
         */
        final int rumbleTicks;
        int applied;
        int ticks;

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
            // About twenty seconds for a small tremor, a full minute for a great earthquake.
            this.rumbleTicks = Mth.clamp((int) Math.round(plan.magnitude() * 145), 400, 1300);
        }
    }

    private static final List<Running> ACTIVE = new ArrayList<>();
    private static int ambientTimer = 0;

    // === Public API =========================================================

    /**
     * Triggers a quake at a column, taking the fault type and strike from the tectonic model.
     * Returns false when the column is not on a fault - plate interiors do not rupture.
     */
    public static boolean triggerHere(ServerLevel level, BlockPos at, double magnitudeOverride) {
        PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());
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
        if (!GeyserConfig.QUAKES_ENABLED.get() || type == FaultType.INTERIOR) return;

        // Put the hypocentre on the fault first, so quakes fired from different spots follow one line.
        BlockPos epi = epicentre;
        PlateSample here = TectonicMap.sample(level, epicentre.getX(), epicentre.getZ());
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

        QuakePlanner.Snapshot snap = QuakePlanner.snapshot(level, trace, type, magnitude);
        long t2 = System.nanoTime();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("quake snapshot: {} columns in {} ms",
                snap.size(), (t2 - t1) / 1_000_000);

        double depthM = quakeDepthMetres(type, level.random);
        boolean mayBreak = GeyserConfig.QUAKES_BREAK_BUILDS.get();
        long seed = level.random.nextLong();
        ResourceKey<Level> dim = level.dimension();

        PendingEdits.register(level, epicentreOnFault, type, magnitude, depthM, seed, mayBreak, trace);
        long t3 = System.nanoTime();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("quake register done in {} ms", (t3 - t2) / 1_000_000);

        // Filed for the instruments; a station in an unloaded chunk reads back what it missed.
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork
                .record(level, epicentreOnFault, type, magnitude, depthM);

        announce(level, epicentreOnFault, type, magnitude, depthM);

        // The ground is held back by the warning window, which gives the planning below that long.
        long startAt = level.getGameTime() + GeyserConfig.QUAKE_WARNING_TICKS.get();

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

    /** Stops every running quake and forgets everything parked. */
    public static int cancelAll() {
        int n = ACTIVE.size();
        ACTIVE.clear();
        PendingEdits.clear();
        Weathering.clear();
        QuakeQuiet.clear();     // nothing left to settle, so nothing left to wait for
        return n;
    }

    // === Per-tick application ===============================================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        applyPending(event);
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

            int placed = 0;
            int examined = 0;
            int scanLimit = budget * 4;
            while (placed < budget && examined < scanLimit && !run.pending.isEmpty()
                    && System.nanoTime() < deadline) {
                QuakePlanner.Edit e = run.pending.poll();
                examined++;
                if (level.hasChunkAt(e.pos())) {
                    level.setBlock(e.pos(), com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, e.pos(), e.state()), FLAGS);
                    placed++;
                } else {
                    // The chunk went away since the snapshot: parked, not dropped, so the rupture is
                    // replayed there when it comes back.
                    if (run.byChunk == null) {
                        run.byChunk = PendingEdits.segmentsByChunk(run.type, run.plan.magnitude(), run.trace);
                    }
                    int cx = e.pos().getX() >> 4, cz = e.pos().getZ() >> 4;
                    PendingEdits.park(level, cx, cz, run.epicentre, run.type, run.plan.magnitude(),
                            run.depthMetres, run.seed, run.mayBreak,
                            run.byChunk.get(net.minecraft.world.level.ChunkPos.asLong(cx, cz)));
                }
            }
            run.applied += placed;
            run.ticks++;
            if (run.ticks % 100 == 0) {
                com.jeladastudios.ftsgeology.util.Diagnostics.info("quake apply: {} placed, {} left, {} ticks",
                        run.applied, run.pending.size(), run.ticks);
            }
            shake(level, run);
            run.shakeTicks--;
            boolean done = run.pending.isEmpty() && run.shakeTicks <= 0;
            if (done) {
                com.jeladastudios.ftsgeology.util.Diagnostics.info("quake finished: {} blocks over {} ticks", run.applied, run.ticks);
                // The shaking stops, but the ground it left is raw. Let it relax.
                Weathering.enqueue(level, run.plan.edits());
                // And the caves under it: an arch that stood for ten thousand years can fail in a minute.
                CaveCollapse.enqueue(level, run.plan);
                // The corridor stays shut until that settling is done.
                QuakeQuiet.settling(level, run.plan.epicentre());
                // New springs are seeded now, but do not start climbing until the zone releases.
                com.jeladastudios.ftsgeology.hydrology.SpringSeeding.afterQuake(
                        level, run.plan.epicentre(), run.plan.ruptureLength(), run.plan.magnitude());
            }
            return done;
        });
    }

    /** Rattles players near the epicentre while the ground is still moving. */
    private static void shake(ServerLevel level, Running run) {
        rumble(level, run);   // outlives the camera shake; see Running.rumbleTicks
        if (run.shakeTicks <= 0) return;
        double radius = 40 + run.plan.magnitude() * 14;
        double r2 = radius * radius;
        for (ServerPlayer p : level.players()) {
            double d2 = p.distanceToSqr(run.epicentre.getX() + 0.5, p.getY(), run.epicentre.getZ() + 0.5);
            if (d2 > r2) continue;
            double falloff = 1.0 - Math.sqrt(d2) / radius;
            double kick = 0.035 * falloff * (0.4 + run.plan.magnitude() / 9.0);
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(
                    v.x + (level.random.nextDouble() - 0.5) * kick,
                    v.y + (p.onGround() ? level.random.nextDouble() * kick * 0.6 : 0.0),
                    v.z + (level.random.nextDouble() - 0.5) * kick);
            p.hurtMarked = true;

            // The view moving. Re-sent a few times a second; the packet's run-out outlasts the gap,
            // so a shake fades on its own if the packets stop.
            if (level.getGameTime() % 5L == 0L) {
                float strength = (float) (falloff * (0.6 + run.plan.magnitude() / 3.0));
                com.jeladastudios.ftsgeology.network.ModNetwork.sendShake(p, strength, 20);
            }

            dust(level, p, falloff);
        }
    }

    /**
     * Dust shaken off the ground around a player while the rupture runs. Rides the player loop
     * above, so it searches nothing and writes no blocks.
     */
    private static void dust(ServerLevel level, ServerPlayer p, double falloff) {
        // A few times a second rather than every tick. Twenty puffs a second per player reads as fog.
        if (level.getGameTime() % 3L != 0L) return;

        int puffs = 1 + (int) Math.round(falloff * 4);
        for (int i = 0; i < puffs; i++) {
            int x = Mth.floor(p.getX()) + level.random.nextInt(25) - 12;
            int z = Mth.floor(p.getZ()) + level.random.nextInt(25) - 12;
            if (!level.hasChunkAt(new BlockPos(x, level.getSeaLevel(), z))) continue;

            int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, g - 1, z);
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(ground);
            // Nothing to shake loose off water, and nothing to see off air.
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;

            // Made of the ground it comes off: yellow over sand, black over basalt.
            level.sendParticles(
                    new net.minecraft.core.particles.BlockParticleOption(
                            net.minecraft.core.particles.ParticleTypes.BLOCK, s),
                    x + 0.5, g + 0.1, z + 0.5,
                    3, 0.4, 0.15, 0.4, 0.02);
        }
    }

    /**
     * The ground noise, restarted at the clip's length until the rumble is over. Volume above 1 sets
     * the audible radius (sixteen blocks per unit), so it carries about as far as the ground moves.
     */
    private static void rumble(ServerLevel level, Running run) {
        if (run.ticks > run.rumbleTicks) return;
        if (run.ticks % 420 != 1) return;              // the clip is 21 seconds long
        level.playSound(null, run.epicentre,
                com.jeladastudios.ftsgeology.registry.ModSounds.QUAKE_RUMBLE.get(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                (float) Mth.clamp(4.0 + run.plan.magnitude(), 4.0, 12.0), 1.0f);
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
            PlateSample s = TectonicMap.sample(level, x, z);
            if (s.faultType() == FaultType.INTERIOR) continue;
            // Recurrence, not a coin flip: the chance comes from a target interval in days, shorter
            // on a highly stressed fault.
            double days = GeyserConfig.QUAKE_RECURRENCE_DAYS.get() / Math.max(0.15, s.stress());
            double rollsPerDay = 24000.0 / Math.max(1, interval);
            if (level.random.nextDouble() > 1.0 / Math.max(1.0, days * rollsPerDay)) continue;

            int y = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z);
            if (y == Integer.MIN_VALUE) continue;
            trigger(level, new BlockPos(x, y, z), s.faultType(),
                    rollMagnitude(s.faultType(), s.stress(), level.random),
                    s.faultStrikeX(), s.faultStrikeZ());
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


    public static double quakeDepthMetres(FaultType type, RandomSource rng) {
        double base = type.typicalQuakeDepth() * 1000.0;   // the enum reports kilometres
        return base * (0.5 + rng.nextDouble());
    }

    private static void announce(ServerLevel level, BlockPos at, FaultType type,
                                 double magnitude, double depthMetres) {
        // The magnitude is formatted here, not in the lang file: Minecraft's translation formatter
        // only understands %s, %d and positional %N$s, and throws on a %.1f.
        Component msg = Component.translatable("message.fts_geology.earthquake",
                String.format(Locale.ROOT, "%.1f", magnitude), label(type), DepthScale.format(depthMetres))
                .withStyle(ChatFormatting.RED);
        double radius = 260 + magnitude * 60;
        double r2 = radius * radius;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.getX() + 0.5, p.getY(), at.getZ() + 0.5) <= r2) {
                p.sendSystemMessage(msg);
            }
        }
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
