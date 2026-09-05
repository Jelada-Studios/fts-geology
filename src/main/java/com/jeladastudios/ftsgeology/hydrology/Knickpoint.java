package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.quake.QuakeQuiet;
import com.jeladastudios.ftsgeology.util.TickBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * A step in a river's bed, eating its way upstream until the river is graded again.
 *
 * <h2>Why not stream power</h2>
 * The obvious model is {@code E = K A^m S^n} over a D8 flow field, and on a continuous heightmap it
 * is the right one. On a voxel grid it degenerates three ways at once, and each of them is fatal
 * rather than cosmetic:
 * <ul>
 *   <li>A flat reach has {@code S = 0}, so {@code E = 0} - the river cannot cut along its own bed,
 *       which is the one place a river certainly does cut.</li>
 *   <li>A one block step has {@code S = 1}, so {@code E} spikes - waterfalls eating backwards
 *       instead of valleys opening out.</li>
 *   <li>Minecraft terrain is full of one-block hollows and D8 stalls in every one of them, unless
 *       the server thread first runs a global depression fill.</li>
 * </ul>
 * A coarse-plus-fine hybrid does not rescue it either: slope on a 16 block grid is {@code ΔY/16} and
 * on the block grid it is {@code ΔY/1}, so pairing a coarse accumulation with a fine slope is
 * dimensionally wrong and blows the erosion rate up by a factor of sixteen exactly at the seam.
 *
 * <h2>What this does instead</h2>
 * Knickpoint retreat, which is the real process and happens to be purely local. Drop the base level
 * at a river's mouth - an earthquake does exactly this - and a step appears in the bed. That step
 * migrates upstream, lowering the channel behind it, and dies when the profile is smooth again.
 *
 * <p>Each column only ever looks at its own downstream neighbour, so nothing here needs a chunk that
 * is not loaded, and nothing needs to know the size of the catchment. And because every pass makes
 * a step smaller, the work is <b>self-terminating</b>: it cannot run away, cannot cut to bedrock,
 * and cannot plane a mountain flat if a server is left running overnight. That last point is not a
 * detail - it is the whole reason this is event-driven rather than continuous.</p>
 */
public final class Knickpoint {

    private Knickpoint() {}

    /** A bed step smaller than this is a rapid, not a knickpoint. Leave it be. */
    private static final int STEP_THRESHOLD = 2;

    /** Columns examined per tick, across all running retreats. */
    private static final int COLUMNS_PER_TICK = 120;

    /** How far upstream a single retreat may travel before it is called finished. */
    private static final int MAX_REACH = 220;

    /** Retreats waiting or running. Small: one per quake that touched a river. */
    private static final Deque<Retreat> QUEUE = new ArrayDeque<>();

    private static final int MAX_QUEUED = 3;

    /** One migrating step, working its way up a channel. */
    private static final class Retreat {
        final ResourceKey<Level> dimension;
        /** Corridor columns not yet tested for being a channel with a step in it. */
        final Deque<long[]> candidates = new ArrayDeque<>();
        final Deque<long[]> front = new ArrayDeque<>();   // columns still to examine, {x, z}
        /**
         * Columns that resisted this tick, held back until the next one.
         *
         * <p>They used to go straight back onto {@link #front}, and the drain loop pulled them out
         * again immediately - in the same tick. Granite resists four times in five, so instead of
         * a hard channel retreating slowly over game time it span on the spot, burning the tick's
         * column allowance and inflating the progress counter until the whole retreat hit its
         * ceiling and was thrown away half finished.</p>
         */
        final Deque<long[]> deferred = new ArrayDeque<>();
        final Set<Long> seen = new HashSet<>();
        int cut;
        int seeded;
        int examined;

        Retreat(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }

    /**
     * Looks for river channels a quake has left with a step in them, and queues the retreat.
     *
     * <p>Called once, when the quiet zone over a rupture releases - so the ground has already
     * stopped moving and its debris has already landed. Running while either was still happening
     * would be cutting a bed that is about to be rearranged.</p>
     */
    public static void afterQuake(ServerLevel level, BlockPos epicentre, double ruptureLength) {
        // Off by default. The one gate for the whole feature, and it is deliberately here rather
        // than at the two call sites: with nothing queued, drain() has nothing to do, so a single
        // check switches off the seeding scan, the retreat and the tick cost together.
        //
        // The code stays because the model is sound - the retreat provably terminates, and that was
        // measured. What is not yet cheap enough is deciding which columns are channel at all, and
        // that is a known piece of work rather than an abandoned one: isChannel still asks the water
        // table, which is nine terrain-generator height queries behind a helper that looks free.
        if (!GeyserConfig.RIVER_EROSION_ENABLED.get()) return;
        if (QUEUE.size() >= MAX_QUEUED) return;

        int radius = (int) Math.min(160, Math.round(ruptureLength / 4.0) + 32);
        Retreat r = new Retreat(level.dimension());

        // Candidates only. Nothing is tested here.
        //
        // This used to walk the whole disc calling isChannel on every fourth column, and that is
        // what locked the server up: about five thousand points, each asking the terrain generator
        // for twenty-five column heights, in one tick with no budget. Whether a column is a channel
        // and whether it has a step are now decided in candidates(), a few hundred at a time inside
        // the tick budget - so the seeding cannot stall a tick however large the rupture was.
        for (int dx = -radius; dx <= radius; dx += 4) {
            for (int dz = -radius; dz <= radius; dz += 4) {
                if (dx * dx + dz * dz > radius * radius) continue;
                r.candidates.add(new long[]{epicentre.getX() + dx, epicentre.getZ() + dz});
            }
        }
        if (r.candidates.isEmpty()) return;

        QUEUE.add(r);
        GeysersMod.LOGGER.info("Knickpoint: {} candidate columns near {}",
                r.candidates.size(), epicentre);
    }

    /**
     * Sifts candidate columns for real channel steps, a few per tick.
     *
     * @return how many columns were examined
     */
    private static int sift(ServerLevel level, Retreat r, int limit) {
        int done = 0;
        while (done < limit) {
            long[] c = r.candidates.poll();
            if (c == null) return done;
            done++;
            int x = (int) c[0], z = (int) c[1];
            if (!RiverProfile.isChannel(level, x, z)) continue;
            if (stepAt(level, x, z) < STEP_THRESHOLD) continue;
            if (r.seen.add(key(x, z))) {
                r.front.add(new long[]{x, z});
                r.seeded++;
            }
        }
        return done;
    }

    /**
     * Works the retreats, within the mod's shared per-tick budget.
     *
     * <p>Shares {@link TickBudget} rather than opening its own. Five subsystems each spending a full
     * budget in the same tick was a real bug in this project once, and a sixth would be a poor way
     * to repeat it.</p>
     */
    public static void drain(MinecraftServer server, long budgetNanos) {
        if (QUEUE.isEmpty() || server == null) return;
        long deadline = System.nanoTime() + budgetNanos;

        Retreat r = QUEUE.peek();
        ServerLevel level = server.getLevel(r.dimension);
        if (level == null) { QUEUE.poll(); return; }

        // Sifting first: candidates become front entries a few hundred at a time.
        int done = sift(level, r, COLUMNS_PER_TICK / 2);

        while (done < COLUMNS_PER_TICK && System.nanoTime() < deadline) {
            long[] c = r.front.poll();
            if (c == null) {
                if (!r.candidates.isEmpty()) break;      // still sifting; come back next tick
                if (!r.deferred.isEmpty()) break;        // resisted columns get their turn next tick
                GeysersMod.LOGGER.info(
                        "Knickpoint finished: {} blocks cut, {} channel columns, {} visits",
                        r.cut, r.seeded, r.examined);
                QUEUE.poll();
                return;
            }
            done++;
            if (r.examined > MAX_REACH * 8) {          // a hard stop, whatever the terrain does
                GeysersMod.LOGGER.info("Knickpoint stopped at its ceiling: {} blocks cut", r.cut);
                QUEUE.poll();
                return;
            }
            retreatOne(level, (int) c[0], (int) c[1], r);
        }

        // Whatever resisted rejoins the queue only now the tick's loop is over, so a hard rock
        // slows the retreat down in game time instead of spinning inside one tick.
        while (!r.deferred.isEmpty()) r.front.add(r.deferred.poll());
    }

    /**
     * One column: lower it towards its outflow, and pass the step to whoever feeds it.
     *
     * <p>The cut is capped at one block per visit. That is what turns a step into a <i>migrating</i>
     * step rather than an instantaneous trench, and it is also the reason the whole thing converges:
     * every visit strictly reduces the difference it is working on.</p>
     */
    private static void retreatOne(ServerLevel level, int x, int z, Retreat r) {
        // Not ready yet is not the same as finished.
        //
        // These two used to return without putting the column back, and since `seen` had already
        // recorded it no upstream neighbour could rediscover it - so a chunk that happened to be
        // unloaded, or an aftershock passing overhead, silently killed the retreat wave. Held for
        // the next tick instead.
        if (!level.hasChunkAt(new BlockPos(x, level.getSeaLevel(), z))) {
            r.deferred.add(new long[]{x, z});
            return;
        }
        // Ground a quake is still working on belongs to the quake. See QuakeQuiet.
        if (QuakeQuiet.isQuiet(level, x, z)) {
            r.deferred.add(new long[]{x, z});
            return;
        }

        r.examined++;
        int step = stepAt(level, x, z);
        if (step < STEP_THRESHOLD) return;               // graded here; the wave stops

        int here = RiverProfile.ground(level, x, z);
        if (here == Integer.MIN_VALUE) return;

        BlockPos bed = new BlockPos(x, here, z);
        BlockState s = level.getBlockState(bed);
        if (RiverProfile.protectedGround(s)) return;

        // Was this bed under water before we touched it? Decide BEFORE cutting.
        boolean wasSubmerged = !level.getBlockState(bed.above()).getFluidState().isEmpty();

        // Hard rock resists: it is visited as often but gives way less of the time, so a gorge in
        // granite retreats slowly and a channel in shale opens quickly.
        //
        // Deferred, not re-queued: see Retreat.deferred. Resistance has to cost game time, not tick
        // budget, and it must not count as progress - inflating the visit counter is what made a
        // granite channel hit its ceiling and get abandoned half cut.
        if (level.random.nextDouble() > RiverProfile.erodibility(level, x, here, z)) {
            r.deferred.add(new long[]{x, z});
            return;
        }

        level.setBlock(bed, Blocks.AIR.defaultBlockState(), 2);
        r.cut++;
        water(level, x, here, z, wasSubmerged);

        // Hand the step to the columns that drain INTO this one - upstream is where it goes.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = x + dx, nz = z + dz;
                BlockPos out = RiverProfile.downstream(level, nx, nz);
                if (out == null || out.getX() != x || out.getZ() != z) continue;
                if (!RiverProfile.isChannel(level, nx, nz)) continue;
                if (r.seen.add(key(nx, nz))) r.front.add(new long[]{nx, nz});
            }
        }
        // And this column again, in case it still stands above its outflow.
        r.front.add(new long[]{x, z});
    }

    /**
     * Puts water in the cut, or deliberately does not.
     *
     * <p>Written with flag 2, so no neighbour update is scheduled. Letting vanilla see this would be
     * expensive out of all proportion: one carved block schedules fluid ticks across its
     * neighbourhood, water cascades down the new bed without forming sources on the slope, and a
     * single retreat could set off hundreds of updates a tick.</p>
     *
     * <p>Whether water belongs there at all is {@link WaterTable}'s answer, not a guess. A bed below
     * the water table fills; a bed above it stays dry - which is what a wadi or an arroyo is, and
     * one of the more recognisable things a dry country does to a river.</p>
     */
    private static void water(ServerLevel level, int x, int cutY, int z, boolean wasSubmerged) {
        // A bed that had water on it keeps water on it, whatever the water table says.
        //
        // Without this the deepened bed of a river running above its own water table was left as an
        // air pocket with the river still standing over it - the same hanging water that cost a
        // whole round on hot springs, written again for rivers. The water table decides whether a
        // DRY channel starts to fill; it has no business emptying a river that is already there.
        if (wasSubmerged) {
            level.setBlock(new BlockPos(x, cutY, z), Blocks.WATER.defaultBlockState(), 2);
            return;
        }
        int table = WaterTable.tableY(level, x, z);
        if (cutY > table) return;                        // above the water table: a dry channel
        level.setBlock(new BlockPos(x, cutY, z), Blocks.WATER.defaultBlockState(), 2);
    }

    /** How far this column stands above the neighbour it drains to. */
    private static int stepAt(ServerLevel level, int x, int z) {
        int here = RiverProfile.ground(level, x, z);
        if (here == Integer.MIN_VALUE) return 0;
        BlockPos out = RiverProfile.downstream(level, x, z);
        if (out == null) return 0;
        return here - out.getY();
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Drops everything. For {@code /geology quake cancel} and world unload. */
    public static void clear() {
        QUEUE.clear();
    }

    /** Are any retreats running? */
    public static boolean busy() {
        return !QUEUE.isEmpty();
    }

    /** For the standalone convergence measurement: how many columns are still queued. */
    public static int pending() {
        Retreat r = QUEUE.peek();
        return r == null ? 0 : r.front.size();
    }

}
