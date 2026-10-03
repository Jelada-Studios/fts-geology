package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A volcano being built a slice at a time.
 *
 * <h2>Why not just build it</h2>
 * A large shield covers thousands of columns and writes tens of thousands of blocks. Done inside one
 * tick that is a visible stall, and doing it from a chunk-load event was what used to hang world
 * creation. But building a <em>smaller</em> volcano to stay cheap is the wrong trade: these are meant
 * to be the landmarks of the world.
 *
 * <p>So the builder produces an ordered list of steps instead - the site clearance, one step per ring
 * of the edifice, one per row of the apron, the summit, the plumbing, the vents - and they are drained
 * from the server tick against a wall-clock budget, exactly the way earthquake deformation is. The
 * mountain takes a few seconds to appear and the tick rate never notices. Steps run in order on the
 * server thread, so they can safely share state through the closure they are built from.</p>
 */
public final class VolcanoJob {

    /** One unit of building work. Kept small enough that a single step is never a stall by itself. */
    @FunctionalInterface
    public interface Step {
        void run(ServerLevel level);
    }

    private final ResourceKey<Level> dimension;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final String label;
    private int done;
    /** Wall time spent running this job's steps, and its slowest single step. */
    private long nanos, slowest;
    private int slowestIndex;
    /** The ground the job writes on, kept loaded while it runs: its middle and how far out it reaches, or none. */
    private int areaX, areaZ, areaReach = -1;
    /** The level the ticket holding that ground is on, once taken, and the tick it was taken at. */
    private ServerLevel held;
    private long heldId;
    private int heldSince;

    public VolcanoJob(ServerLevel level, String label) {
        this.dimension = level.dimension();
        this.label = label;
    }

    public void add(Step step) {
        steps.add(step);
    }

    /**
     * The ground the steps write on. A step builds only where the ground is loaded: a column of a chunk that has gone
     * reads as no ground, and the step passes over it. A small volcano placed as a player flew past was built after its
     * chunks had gone -- its cone, its apron and the clearing of its trees passed over -- while the summit's steps, which
     * set their blocks straight, loaded what they wrote on: a crater and its lava lake on a thread of basalt twenty
     * blocks over the forest. With its ground named, a job holds it loaded until it is done, and waits for it.
     */
    public VolcanoJob area(int x, int z, int reach) {
        this.areaX = x;
        this.areaZ = z;
        this.areaReach = Math.max(0, reach);
        return this;
    }

    // === The queue ==========================================================

    private static final List<VolcanoJob> QUEUE = new ArrayList<>();

    /** Never let more than this many volcanoes wait at once; a backlog helps nobody. */
    private static final int MAX_QUEUED = 4;
    /** How long a job waits for its ground to load before it is given up; the ticket drops itself a little after. */
    private static final int WAIT_TICKS = 2400;
    private static final TicketType<Long> TICKET = TicketType.create("fts_geology_volcano", Long::compareTo, WAIT_TICKS + 600);
    private static long nextTicket;

    /** Hands a finished plan to the tick loop. Refused if the queue is already backed up. */
    public static boolean enqueue(VolcanoJob job) {
        if (job.steps.isEmpty()) return false;
        if (QUEUE.size() >= MAX_QUEUED) {
            GeysersMod.LOGGER.debug("Volcano skipped ({} already building): {}", QUEUE.size(), job.label);
            return false;
        }
        QUEUE.add(job);
        GeysersMod.LOGGER.debug("Volcano queued: {} ({} steps)", job.label, job.steps.size());
        return true;
    }

    /** Drops everything still waiting; used when a server stops or a command cancels. */
    public static int clear() {
        int n = QUEUE.size();
        for (VolcanoJob job : QUEUE) job.release();
        QUEUE.clear();
        return n;
    }

    public static boolean busy() {
        return !QUEUE.isEmpty();
    }

    /** Whether the ground the job writes on is all loaded; asked first, it takes the ticket that loads it. */
    private boolean ready(ServerLevel level) {
        if (areaReach < 0) return true;
        if (held == null) {
            held = level;
            heldId = nextTicket++;
            heldSince = level.getServer().getTickCount();
            level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(areaX >> 4, areaZ >> 4), ticketRadius(), heldId);
        }
        return VolcanoBuilder.loaded(level, areaX, areaZ, areaReach);
    }

    /** Chunks the ticket loads round the middle, enough for the whole reach and the blocks beside it. */
    private int ticketRadius() {
        return (areaReach >> 4) + 1;
    }

    /** Whether the job has waited for its ground longer than it is worth, never having started. */
    private boolean expired(ServerLevel level) {
        return held != null && done == 0 && level.getServer().getTickCount() - heldSince > WAIT_TICKS;
    }

    private void release() {
        if (held == null) return;
        held.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(areaX >> 4, areaZ >> 4), ticketRadius(), heldId);
        held = null;
    }

    /**
     * Runs queued volcano steps until the budget runs out. The budget is a hard wall-clock deadline
     * rather than a step count, so a single unexpectedly heavy step can slow the build down but can
     * never lock the tick up. A job whose ground is still loading lets the next one run.
     */
    public static void drain(MinecraftServer server, long budgetNanos) {
        if (QUEUE.isEmpty() || server == null) return;
        long deadline = System.nanoTime() + budgetNanos;

        while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
            VolcanoJob job = null;
            ServerLevel level = null;
            for (int i = 0; i < QUEUE.size(); ) {
                VolcanoJob j = QUEUE.get(i);
                ServerLevel l = server.getLevel(j.dimension);
                if (l == null || j.expired(l)) {
                    if (l != null) GeysersMod.LOGGER.warn("Volcano given up, its ground never loaded: {}", j.label);
                    j.release();
                    QUEUE.remove(i);
                    continue;
                }
                if (j.ready(l)) {
                    job = j;
                    level = l;
                    break;
                }
                i++;
            }
            if (job == null) return;

            while (!job.steps.isEmpty() && System.nanoTime() < deadline) {
                Step s = job.steps.poll();
                long started = System.nanoTime();
                try {
                    s.run(level);
                } catch (Exception e) {
                    GeysersMod.LOGGER.warn("Volcano step failed ({}): {}", job.label, e.toString());
                }
                long took = System.nanoTime() - started;
                job.nanos += took;
                if (took > job.slowest) {
                    job.slowest = took;
                    job.slowestIndex = job.done;
                }
                job.done++;
            }
            if (job.steps.isEmpty()) {
                // Logged with its cost, so a slow build can be traced to the step that made it slow.
                com.jeladastudios.ftsgeology.util.Diagnostics.info("Volcano finished: {} ({} steps, {} ms, slowest step #{} {} ms)",
                        job.label, job.done, String.format(java.util.Locale.ROOT, "%.1f", job.nanos / 1e6),
                        job.slowestIndex, String.format(java.util.Locale.ROOT, "%.1f", job.slowest / 1e6));
                job.release();
                QUEUE.remove(job);
            }
        }
    }
}
