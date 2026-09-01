package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Lets the ground an earthquake tore up settle afterwards.
 *
 * <h2>Why</h2>
 * A rupture leaves raw geometry: single-block spikes standing where their neighbours were carved
 * away, and scarps with edges sharper than rock can actually hold. Real ground does not stay like
 * that for a moment. Gravity takes the overhanging crest off and piles it at the foot as scree, and
 * within days a fresh fault scarp already has a talus apron under it. Skipping that step is what
 * makes a modelled landscape look modelled.
 *
 * <h2>What it does</h2>
 * Three rules, applied a few hundred columns per tick over the couple of minutes after the shaking
 * stops, so the ground visibly relaxes rather than snapping into its final shape:
 *
 * <ul>
 *   <li><b>Spikes fall.</b> A column standing three or more blocks above every one of its four
 *       neighbours has nothing holding it up, so it loses its top block.</li>
 *   <li><b>What stood on it comes down with it.</b> Ground that drops leaves whatever was above it
 *       hanging in mid-air. The stack is set back down on the new ground - trees, soil, and builds
 *       alike, because a floating house is a worse outcome than a settled one and <em>falling is
 *       not breaking</em>: every block survives the drop with its state intact. A tree is the one
 *       exception: one whose ground fell more than three blocks did not subside, it
 *       <em>failed</em>, so it goes with the landslide and the scarp is left bare. That is what an
 *       earthquake photograph actually looks like.</li>
 *   <li><b>Crests shed to their foot.</b> Where a column stands four or more above its lowest
 *       neighbour, the top block is <em>moved</em> onto that neighbour. Material is conserved, which
 *       is what makes it talus rather than erasure: the scarp gets lower and an apron grows under
 *       it.</li>
 * </ul>
 *
 * <p>It only ever visits columns the quake itself edited. The two rock rules move a block at most
 * one step and never touch a player block, so they cannot run away or flatten anything they did not
 * make. The falling rule only ever moves a stack straight down onto the ground beneath it, so it
 * cannot destroy anything either; {@code unsupportedBlocksFall} turns it off entirely, and
 * {@code fallingIncludesPlayerBlocks} leaves builds hanging while still bringing terrain down.</p>
 */
public final class Weathering {

    private Weathering() {}

    /**
     * How many passes the corridor gets. The first and the last reseat what was growing on the
     * ground; the ones between take the raw edges off the rock.
     */
    private static final int PASSES = 5;

    /** How far above the new ground a column is searched for something left hanging. */
    private static final int SEARCH = 24;

    /** A tree can ride the ground down this far. Past it, the slope failed and took the tree. */
    private static final int RIDE_LIMIT = 3;

    /** Columns examined per tick. Low on purpose: this is meant to be watched, not to happen. */
    private static final int COLUMNS_PER_TICK = 250;

    /** Ground must stand at least this far above ALL neighbours before it counts as a spike. */
    private static final int SPIKE = 3;

    /** Ground must stand at least this far above its LOWEST neighbour before the crest sheds. */
    private static final int SCARP = 4;

    /** One rupture corridor relaxing. */
    private static final class Job {
        final ResourceKey<Level> dimension;
        final long[] columns;
        int cursor;
        int pass;
        int moved;

        Job(ResourceKey<Level> dimension, long[] columns) {
            this.dimension = dimension;
            this.columns = columns;
        }
    }

    private static final Deque<Job> QUEUE = new ArrayDeque<>();

    /**
     * Queues the corridor of a finished quake for weathering. The column list is taken from the
     * edits that were actually planned, so this touches exactly the ground the quake moved.
     */
    public static void enqueue(ServerLevel level, List<QuakePlanner.Edit> edits) {
        if (edits.isEmpty()) return;
        LongOpenHashSet seen = new LongOpenHashSet();
        for (QuakePlanner.Edit e : edits) {
            seen.add(key(e.pos().getX(), e.pos().getZ()));
        }
        QUEUE.add(new Job(level.dimension(), seen.toLongArray()));
        GeysersMod.LOGGER.info("weathering queued: {} columns", seen.size());
    }

    /** Drops everything still settling; used when a server stops or a command cancels. */
    public static int clear() {
        int n = QUEUE.size();
        QUEUE.clear();
        return n;
    }

    /** Relaxes a slice of the corridor. Bounded by both a column count and a wall-clock deadline. */
    public static void drain(MinecraftServer server, long budgetNanos) {
        if (QUEUE.isEmpty() || server == null) return;
        long deadline = System.nanoTime() + budgetNanos;

        Job job = QUEUE.peek();
        ServerLevel level = server.getLevel(job.dimension);
        if (level == null) { QUEUE.poll(); return; }

        int done = 0;
        while (done < COLUMNS_PER_TICK && System.nanoTime() < deadline) {
            if (job.cursor >= job.columns.length) {
                job.cursor = 0;
                if (++job.pass >= PASSES) {
                    GeysersMod.LOGGER.info("weathering finished: {} blocks moved over {} columns",
                            job.moved, job.columns.length);
                    QUEUE.poll();
                    return;
                }
            }
            long k = job.columns[job.cursor++];
            done++;
            int cx = (int) (k >> 32), cz = (int) k;
            // Passes 0 and PASSES-1 bring down what is left hanging; the ones between take the raw
            // edges off the rock. With falling switched off, every pass goes to the rock rules
            // rather than being wasted.
            boolean fallPass = (job.pass == 0 || job.pass == PASSES - 1)
                    && GeyserConfig.UNSUPPORTED_BLOCKS_FALL.get();
            if (fallPass ? reseat(level, cx, cz) : relax(level, cx, cz)) job.moved++;
        }
    }

    /**
     * Brings down whatever the quake left hanging over this column.
     *
     * <p>Reads the run of air directly above the new ground, then the stack sitting on top of that
     * run, and sets the whole stack back down. <b>Falling is not breaking:</b> every block survives
     * the drop and keeps its state, it simply ends up lower. That is what lets this ignore whether a
     * player put it there - a settled wall is a far better outcome than a floating one, and nothing
     * is lost either way.</p>
     *
     * <p>The one thing that does not survive is plant matter whose ground fell more than
     * {@link #RIDE_LIMIT}. A tree can ride a subsiding slope down a few blocks, roots and all; past
     * that the slope did not subside, it <em>failed</em>, and a fresh landslide scarp is bare. Rock
     * and anything built is never deleted, however far it fell.</p>
     *
     * <p>A gap filled with fluid is left alone, rather than punching a hole in a lake to move a tree
     * through it.</p>
     *
     * @return true if this column changed
     */
    private static boolean reseat(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;
        if (!level.hasChunkAt(new BlockPos(x, g, z))) return false;

        boolean mayMoveBuilds = GeyserConfig.FALLING_INCLUDES_BUILDS.get();

        // The gap: how far the ground fell out from under whatever is up there.
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int limit = Math.min(g + 1 + SEARCH, level.getMaxBuildHeight() - 1);
        int base = g + 1;
        while (base < limit && level.getBlockState(m.set(x, base, z)).isAir()) base++;
        int drop = base - (g + 1);
        if (drop <= 0 || base >= limit) return false;

        // Read the hanging stack. Anything solid comes down; only a fluid stops the column, because
        // dropping a stack through standing water would drain the lake it is sitting in.
        int top = base;
        boolean allPlant = true;
        while (top < limit) {
            BlockState s = level.getBlockState(m.set(x, top, z));
            if (s.isAir()) break;
            if (!s.getFluidState().isEmpty()) return false;
            // isPlayerPlaced() does not recognise logs or leaves as natural, so a tree reads as a
            // build to it. Checking isPlant() first is what keeps trees falling even when a player
            // has switched build-dropping off - a floating forest was the original complaint.
            if (!mayMoveBuilds && !isPlant(s) && EruptionHandler.isPlayerPlaced(s)) return false;
            if (!isPlant(s)) allPlant = false;
            top++;
        }
        int height = top - base;
        if (height <= 0) return false;

        if (allPlant && drop > RIDE_LIMIT) {
            // Too far to have ridden it down. This ground did not subside, it gave way, and a fresh
            // landslide scarp is bare rock. Only vegetation goes this way: clearing a build here
            // would be destroying it rather than dropping it.
            for (int i = 0; i < height; i++) {
                level.setBlock(new BlockPos(x, base + i, z), Blocks.AIR.defaultBlockState(), 2);
            }
            return true;
        }

        // The whole stack comes down together, in order, so it lands the same way up.
        BlockState[] stack = new BlockState[height];
        for (int i = 0; i < height; i++) stack[i] = level.getBlockState(m.set(x, base + i, z));
        for (int i = 0; i < height; i++) {
            level.setBlock(new BlockPos(x, base + i, z), Blocks.AIR.defaultBlockState(), 2);
        }
        for (int i = 0; i < height; i++) {
            level.setBlock(new BlockPos(x, g + 1 + i, z), stack[i], 2);
        }
        return true;
    }

    /** Everything a tree or a plant is made of, and nothing else. */
    private static boolean isPlant(BlockState s) {
        return s.is(BlockTags.LOGS)
                || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.WART_BLOCKS)
                || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || s.is(Blocks.RED_MUSHROOM_BLOCK)
                || TerrainProbe.isVegetation(s);
    }

    /** Applies the two rock rules to one column. Returns true if anything moved. */
    private static boolean relax(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;
        if (!level.hasChunkAt(new BlockPos(x, g, z))) return false;

        BlockPos crest = new BlockPos(x, g, z);
        BlockState top = level.getBlockState(crest);
        if (top.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(top)) return false;
        if (!top.getFluidState().isEmpty()) return false;

        int highest = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        BlockPos foot = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            int n = TerrainProbe.groundY(level, nx, nz);
            // A neighbour with no ground at all is a cliff edge or a cave mouth. Leave the column
            // alone rather than shovelling it into a hole.
            if (n == Integer.MIN_VALUE) return false;
            highest = Math.max(highest, n);
            if (n < lowest) { lowest = n; foot = new BlockPos(nx, n + 1, nz); }
        }

        if (g - highest >= SPIKE) {
            // Nothing holds it up on any side.
            level.setBlock(crest, Blocks.AIR.defaultBlockState(), 2);
            return true;
        }
        if (g - lowest >= SCARP && foot != null) {
            // The crest sheds one block and it comes to rest at the foot. Material is conserved,
            // which is the difference between talus and simply deleting the overhang.
            BlockState at = level.getBlockState(foot);
            if (!at.isAir() && !TerrainProbe.isVegetation(at)) return false;
            if (EruptionHandler.isPlayerPlaced(at)) return false;
            level.setBlock(crest, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(foot, top, 2);
            return true;
        }
        return false;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
