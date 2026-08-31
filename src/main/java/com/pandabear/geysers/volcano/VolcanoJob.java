package com.pandabear.geysers.volcano;

import com.pandabear.geysers.GeysersMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

    public VolcanoJob(ServerLevel level, String label) {
        this.dimension = level.dimension();
        this.label = label;
    }

    public void add(Step step) {
        steps.add(step);
    }

    public int size() {
        return steps.size();
    }

    // === The queue ==========================================================

    private static final List<VolcanoJob> QUEUE = new ArrayList<>();

    /** Never let more than this many volcanoes wait at once; a backlog helps nobody. */
    private static final int MAX_QUEUED = 4;

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
        QUEUE.clear();
        return n;
    }

    public static boolean busy() {
        return !QUEUE.isEmpty();
    }

    /**
     * Runs queued volcano steps until the budget runs out. The budget is a hard wall-clock deadline
     * rather than a step count, so a single unexpectedly heavy step can slow the build down but can
     * never lock the tick up.
     */
    public static void drain(MinecraftServer server, long budgetNanos) {
        if (QUEUE.isEmpty() || server == null) return;
        long deadline = System.nanoTime() + budgetNanos;

        while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
            VolcanoJob job = QUEUE.get(0);
            ServerLevel level = server.getLevel(job.dimension);
            if (level == null) { QUEUE.remove(0); continue; }

            while (!job.steps.isEmpty() && System.nanoTime() < deadline) {
                Step s = job.steps.poll();
                try {
                    s.run(level);
                } catch (Exception e) {
                    GeysersMod.LOGGER.warn("Volcano step failed ({}): {}", job.label, e.toString());
                }
                job.done++;
            }
            if (job.steps.isEmpty()) {
                GeysersMod.LOGGER.debug("Volcano finished: {} ({} steps)", job.label, job.done);
                QUEUE.remove(0);
            }
        }
    }
}
