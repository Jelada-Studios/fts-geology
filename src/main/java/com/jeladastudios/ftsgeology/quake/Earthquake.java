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
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
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
 * thread a slice at a time.
 *
 * <h2>Why it is spread over ticks</h2>
 * Budgeting the block edits protects the tick rate, but it is also the physically correct thing to
 * do. A real rupture travels along the fault at a couple of kilometres per second and a large
 * earthquake lasts tens of seconds - the ground does not deform all at once. So a quake visibly
 * tearing its way along the fault over a few seconds is more accurate than an instant snap, not
 * less.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Earthquake {

    private Earthquake() {}

    /** A quake whose plan is ready and whose edits are being applied a few per tick. */
    private static final class Running {
        final ResourceKey<Level> dimension;
        final BlockPos epicentre;
        final QuakePlanner.Plan plan;
        final Deque<QuakePlanner.Edit> pending;
        /**
         * Game time the ground is allowed to start moving. The quake is filed with the seismic
         * network the instant it is triggered, but the deformation is held back until here, so a
         * station has a warning window to sound its siren in before anything shakes - which is the
         * whole point of an early-warning network, and only possible because the alert travels
         * faster than the ground does.
         */
        long startAt;
        int shakeTicks;
        int applied;
        int ticks;

        Running(ResourceKey<Level> dimension, QuakePlanner.Plan plan, long startAt) {
            this.dimension = dimension;
            this.epicentre = plan.epicentre();
            this.plan = plan;
            this.startAt = startAt;
            this.pending = new ArrayDeque<>(plan.edits());
            // The ground keeps deforming for as long as the edit list lasts, but the SHAKING is
            // capped: a 400k-edit megathrust would otherwise rattle the camera for a solid minute,
            // which stops reading as an earthquake and starts reading as a broken game. Real strong
            // motion lasts tens of seconds even for an M9.
            this.shakeTicks = Mth.clamp(plan.edits().size()
                    / Math.max(1, GeyserConfig.QUAKE_BLOCKS_PER_TICK.get()) + 40, 40, 400);
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
     * Triggers a quake of an explicit type, so all three deformation styles can be demonstrated
     * side by side on flat ground regardless of the local geology.
     */
    public static void trigger(ServerLevel level, BlockPos epicentre, FaultType type,
                               double magnitude, double strikeX, double strikeZ) {
        trigger(level, epicentre, type, magnitude, strikeX, strikeZ, false);
    }

    /**
     *  forced true when the caller picked the fault type rather than reading it from the
     *               ground. A forced rupture is allowed to run its full length through terrain that
     *               is not that kind of boundary, which is what makes the demonstration command
     *               work anywhere.
     */
    public static void trigger(ServerLevel level, BlockPos epicentre, FaultType type,
                               double magnitude, double strikeX, double strikeZ, boolean forced) {
        if (!GeyserConfig.QUAKES_ENABLED.get() || type == FaultType.INTERIOR) return;

        // Put the hypocentre ON the fault before tracing.
        //
        // The rupture starts wherever it is told to and then follows the local strike, so triggering
        // one while standing thirty blocks off the boundary ran the whole thing down a line thirty
        // blocks off - parallel-ish, but displaced. Two quakes fired from different spots on the
        // same fault therefore came out on two different lines, which is the "the direction keeps
        // changing" report. A real rupture is confined to the fault plane, so the epicentre is
        // projected onto the boundary along its own normal first.
        BlockPos epi = epicentre;
        PlateSample here = TectonicMap.sample(level, epicentre.getX(), epicentre.getZ());
        if (here.onFault() && here.faultDistance() > 1.0) {
            epi = new BlockPos(
                    epicentre.getX() + (int) Math.round(here.faultNormalX() * here.faultDistance()),
                    epicentre.getY(),
                    epicentre.getZ() + (int) Math.round(here.faultNormalZ() * here.faultDistance()));
        }
        final BlockPos epicentreOnFault = epi;

        // Give the margin a fixed polarity.
        //
        // Subduction and collision are both one-sided: one plate goes under, and which side gets the
        // trench or the foreland basin is decided by the sign of `across`, which comes from the
        // strike, which comes from a normal pointing from OUR plate to the neighbour. Sample the
        // same boundary from the other side and that normal is negated, so the whole margin
        // mirrors. Two quakes on one boundary could therefore drop the side the previous one had
        // lifted - which is geological nonsense: the ocean floor dives under the continent no
        // matter where you happen to be standing when it goes off.
        //
        // So the direction is taken from the BOUNDARY rather than from the sampled side. The
        // oceanic plate is the one that dives; between two of a kind the lower plate id does, which
        // is arbitrary but stable, and stability is the whole point here.
        double sx = strikeX, sz = strikeZ;
        if ((type == FaultType.CONVERGENT_SUBDUCTION || type == FaultType.CONVERGENT_COLLISION)
                && downGoingIsOurs(here)) {
            sx = -strikeX;
            sz = -strikeZ;
        }

        // Every stage is timed and logged. Three rounds of guessing where the cost was did not find
        // it; the log naming the slow stage - or stopping before one of these lines - will.
        long t0 = System.nanoTime();
        List<QuakePlanner.TracePoint> trace =
                QuakePlanner.traceFault(level, epicentreOnFault, type, magnitude, sx, sz, forced);
        long t1 = System.nanoTime();
        GeysersMod.LOGGER.info("quake trace: {} points, {} blocks long, corridor +/-{} in {} ms",
                trace.size(), QuakePlanner.ruptureLengthBlocks(magnitude),
                QuakePlanner.deformationHalfWidth(type, magnitude), (t1 - t0) / 1_000_000);
        if (trace.isEmpty()) return;

        QuakePlanner.Snapshot snap = QuakePlanner.snapshot(level, trace, type, magnitude);
        long t2 = System.nanoTime();
        GeysersMod.LOGGER.info("quake snapshot: {} columns in {} ms",
                snap.size(), (t2 - t1) / 1_000_000);

        double depthM = quakeDepthMetres(type, level.random);
        boolean mayBreak = GeyserConfig.QUAKES_BREAK_BUILDS.get();
        long seed = level.random.nextLong();
        ResourceKey<Level> dim = level.dimension();

        PendingEdits.register(level, epicentreOnFault, type, magnitude, depthM, seed, mayBreak, trace);
        long t3 = System.nanoTime();
        GeysersMod.LOGGER.info("quake register done in {} ms", (t3 - t2) / 1_000_000);

        // Filed for the instruments. A seismograph in an unloaded chunk cannot be told about this
        // now, so the network keeps it and the station reads back through whatever it missed when
        // its chunk comes round again - which is what an unattended station does.
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork
                .record(level, epicentreOnFault, type, magnitude, depthM);

        announce(level, epicentreOnFault, type, magnitude, depthM);

        // No sound here any more. It used to fire a GENERIC_EXPLODE at trigger time; the warning
        // and the boom now belong to the instruments and the ground itself. The ground is also
        // held back by the warning window, so the planning below has that long to finish in - a
        // large rupture that used to snap into being now has ten seconds of runway.
        long startAt = level.getGameTime() + GeyserConfig.QUAKE_WARNING_TICKS.get();

        // Worker thread: the expensive half. Touches nothing but the immutable snapshot.
        CompletableFuture
                .supplyAsync(() -> {
                    long p0 = System.nanoTime();
                    QuakePlanner.Plan plan = QuakePlanner.plan(snap, trace, epicentreOnFault, type,
                            magnitude, depthM, new Random(seed), mayBreak);
                    GeysersMod.LOGGER.info("quake plan: {} edits in {} ms",
                            plan.edits().size(), (System.nanoTime() - p0) / 1_000_000);
                    return plan;
                }, Util.backgroundExecutor())
                .thenAcceptAsync(plan -> {
                    ACTIVE.add(new Running(dim, plan, startAt));
                    GeysersMod.LOGGER.info("quake apply starting: {} edits queued", plan.edits().size());
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

        if (ACTIVE.isEmpty()) return;
        int budget = GeyserConfig.QUAKE_BLOCKS_PER_TICK.get();
        // Hard wall-clock brake. However badly the block count is mis-estimated, a tick can never
        // run away: the quake just takes longer. This is what stops the game locking up.
        //
        // The visible half of the mod, so it may use everything the tick has left rather than a
        // fixed share: a player is standing there watching the ground move.
        long deadline = System.nanoTime()
                + com.jeladastudios.ftsgeology.util.TickBudget.remaining();

        ACTIVE.removeIf(run -> {
            ServerLevel level = event.getServer().getLevel(run.dimension);
            if (level == null) return true;

            // Still in the warning window: filed, seismographs alerting, but the ground has not
            // moved yet. Hold the whole run until its start time arrives.
            //
            // Read from the run's own level rather than the overworld. Game time happens to be
            // shared across dimensions today, so both give the same answer, but startAt was set
            // from this level and comparing a clock against itself does not rely on that.
            if (level.getGameTime() < run.startAt) return false;

            int placed = 0;
            int examined = 0;
            int scanLimit = budget * 4;
            while (placed < budget && examined < scanLimit && !run.pending.isEmpty()
                    && System.nanoTime() < deadline) {
                QuakePlanner.Edit e = run.pending.poll();
                examined++;
                if (level.hasChunkAt(e.pos())) {
                    level.setBlock(e.pos(), e.state(), 2);
                    placed++;
                }
            }
            run.applied += placed;
            run.ticks++;
            if (run.ticks % 100 == 0) {
                GeysersMod.LOGGER.info("quake apply: {} placed, {} left, {} ticks",
                        run.applied, run.pending.size(), run.ticks);
            }
            shake(level, run);
            run.shakeTicks--;
            boolean done = run.pending.isEmpty() && run.shakeTicks <= 0;
            if (done) {
                GeysersMod.LOGGER.info("quake finished: {} blocks over {} ticks", run.applied, run.ticks);
                // The shaking stops, but the ground it left is raw. Let it relax.
                Weathering.enqueue(level, run.plan.edits());
            }
            return done;
        });
    }

    /** Rattles players near the epicentre while the ground is still moving. */
    private static void shake(ServerLevel level, Running run) {
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
        }
        // The rumble, started on the FIRST tick of the shaking and restarted at the clip's own
        // length. `% 420 == 0` was wrong twice over: ticks is incremented before this runs, so it
        // is never zero, and the shaking only lasts 40 to 400 ticks anyway - so the sound could
        // not fire at all, which is why none was heard.
        //
        // Volume above 1 is what sets the audible range in Minecraft (16 blocks per unit), so it
        // is scaled to reach about as far as the ground is actually moving rather than being left
        // at a polite 1.0 and going unheard by everyone the quake is happening to.
        if (run.ticks % 420 == 1) {
            level.playSound(null, run.epicentre,
                    com.jeladastudios.ftsgeology.registry.ModSounds.QUAKE_RUMBLE.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    (float) Mth.clamp(4.0 + run.plan.magnitude(), 4.0, 12.0), 1.0f);
        }
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
            // Recurrence, not a coin flip per interval.
            //
            // A fault does not rupture because a timer went off; it ruptures when it has stored
            // enough strain, which takes a characteristic time. So the chance per roll is derived
            // from a target RECURRENCE INTERVAL in in-game days, shortened on a highly stressed
            // boundary and lengthened on a sleepy one. That turns earthquakes from something that
            // happens every few minutes into something worth travelling to see.
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
    /**
    /**
     * Magnitude, drawn the way nature draws it.
     *
     * <h2>Gutenberg-Richter</h2>
     * Earthquakes are not spread evenly across their range. Almost everywhere on Earth, each step up
     * in magnitude is about <b>ten times rarer</b> than the one below it - that is the
     * Gutenberg-Richter law, and the exponent is close to 1 on every fault anyone has measured. So
     * the magnitude comes from a truncated exponential across the band rather than a flat roll: a
     * fault that can reach M9 mostly produces small events and only occasionally produces the giant,
     * which is what makes the giant worth waiting for.
     *
     * <p>The bands themselves follow the real ordering: subduction megathrusts are the largest
     * earthquakes the planet makes, collision zones are close behind, strike-slip faults sit in the
     * middle, and spreading ridges are the mildest despite opening the most visible fissures.</p>
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
     * Focal depth. Subduction quakes nucleate far down the descending slab, while rift and
     * strike-slip quakes are shallow crustal events - which is exactly why the shallow ones do so
     * much surface damage for their size.
     */
    /**
     * Is the plate the sample was taken on the one that goes under?
     *
     * <p>Dense oceanic crust always loses. Between two plates of the same kind nothing in the
     * physics picks a winner, so the lower id is used: arbitrary, but the same answer from either
     * side of the line, which is what stops the margin mirroring between quakes.</p>
     */
    private static boolean downGoingIsOurs(PlateSample s) {
        boolean ours = s.plateKind().isOceanic();
        boolean theirs = s.neighbourKind().isOceanic();
        if (ours != theirs) return ours;
        return Long.compareUnsigned(s.plateId(), s.neighbourId()) < 0;
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
