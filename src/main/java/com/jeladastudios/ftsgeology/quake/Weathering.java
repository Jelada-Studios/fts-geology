package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
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

    /**
     * How far above the new ground to look for the underside of whatever is left hanging.
     *
     * <p>Has to cover the deepest the ground can drop, which is {@link QuakePlanner#MAX_CAPTURE_DEPTH}
     * plus room for the weathering passes to lower it further.</p>
     */
    private static final int GAP_SEARCH = 40;

    /**
     * How tall a hanging stack may be before it is left alone.
     *
     * <p>Separate from {@link #GAP_SEARCH} on purpose. These used to be one number, and a single
     * 24-block window had to hold the gap AND the whole tree: a large quake drops the ground twenty
     * blocks, which put the canopy beyond the end of the window, so the trunk was cleared and the
     * leaves were left floating to decay on vanilla's slow timer. That is the "logs gone, leaves
     * still up there" report. Terralith's big trees need most of this.</p>
     */
    private static final int STACK_LIMIT = 48;

    /**
     * A tree can ride the ground down this far before the slope counts as having failed.
     *
     * <p>Zero: undermine a tree at all and it comes down. Three blocks of subsidence really would
     * carry a tree with it, but the survivors read as trees the quake had missed rather than as
     * trees that rode it out, and one left standing on a pillar spoils the whole scene.</p>
     */
    private static final int RIDE_LIMIT = 0;

    /**
     * How far from a leaf a log may be before the leaf counts as orphaned. Vanilla's own limit.
     */
    private static final int LEAF_SUPPORT_RANGE = 6;

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
        /**
         * Per column, the highest Y the quake actually turned to air, or {@link Integer#MIN_VALUE}.
         *
         * <p>This is the anchor {@link #reseat} needs and could not get from the world itself. See
         * the note there: {@code groundY} cannot tell a floating slab from the ground, but the
         * quake knows exactly how deep it dug, and nothing below that is any of our business.</p>
         */
        final Long2IntMap excavated;
        int cursor;
        int pass;
        int moved;

        Job(ResourceKey<Level> dimension, long[] columns, Long2IntMap excavated) {
            this.dimension = dimension;
            this.columns = columns;
            this.excavated = excavated;
        }
    }

    private static final Deque<Job> QUEUE = new ArrayDeque<>();

    /**
     * Columns whose chunk was not loaded when the settling pass reached them, parked by chunk.
     *
     * <p>Without this they were simply dropped. A rupture corridor is tens of thousands of columns
     * and a player stands in one place, so most of it is unloaded while the passes run - the ground
     * a player flew out to look at afterwards had never been settled at all, and that is why trees
     * were still hanging over it. The quake itself already solves this for its own edits, by parking
     * them in {@link PendingEdits} until the chunk comes back; the settling that follows had no
     * such thing.</p>
     *
     * <p>In memory only, like the quake's own parking: a restart loses whatever had not settled
     * yet, which costs a little tidiness and no correctness.</p>
     */
    private static final java.util.Map<String, Long2IntOpenHashMap> PARKED = new java.util.HashMap<>();

    private static String parkKey(ResourceKey<Level> dim, int cx, int cz) {
        return dim.location() + "@" + cx + "," + cz;
    }

    /** Re-queues the settling that was parked for a chunk, now that it is back. */
    public static void onChunkLoaded(ServerLevel level, ChunkPos cp) {
        Long2IntOpenHashMap cols = PARKED.remove(parkKey(level.dimension(), cp.x, cp.z));
        if (cols == null || cols.isEmpty()) return;
        QUEUE.add(new Job(level.dimension(), cols.keySet().toLongArray(), cols));
    }

    /**
     * Queues the corridor of a finished quake for weathering. The column list is taken from the
     * edits that were actually planned, so this touches exactly the ground the quake moved.
     */
    public static void enqueue(ServerLevel level, List<QuakePlanner.Edit> edits) {
        if (edits.isEmpty()) return;
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        seen.defaultReturnValue(Integer.MIN_VALUE);
        for (QuakePlanner.Edit e : edits) {
            // Dilated by the leaf-support range. A canopy hangs over columns whose ground the quake
            // never touched, so a set built from the edits alone stops one tree-width short of the
            // leaves it just orphaned. Dilating a long thin corridor grows it by its perimeter, not
            // its area - about 15% more columns on a big rupture.
            int ex = e.pos().getX(), ez = e.pos().getZ();
            // How deep the quake emptied this column, carried through the dilation so the ring
            // around the corridor inherits its neighbour's floor. A hint that turns out to be
            // above the local ground simply finds solid rock straight away and does nothing, so
            // spreading it outward is safe.
            int airTop = e.state().isAir() ? e.pos().getY() : Integer.MIN_VALUE;
            for (int dx = -LEAF_SUPPORT_RANGE; dx <= LEAF_SUPPORT_RANGE; dx++) {
                for (int dz = -LEAF_SUPPORT_RANGE; dz <= LEAF_SUPPORT_RANGE; dz++) {
                    long k = key(ex + dx, ez + dz);
                    if (airTop > seen.get(k)) seen.put(k, airTop);
                    else seen.putIfAbsent(k, Integer.MIN_VALUE);
                }
            }
        }
        QUEUE.add(new Job(level.dimension(), seen.keySet().toLongArray(), seen));
        GeysersMod.LOGGER.info("weathering queued: {} columns", seen.size());
    }

    /** Drops everything still settling; used when a server stops or a command cancels. */
    public static int clear() {
        int n = QUEUE.size();
        QUEUE.clear();
        PARKED.clear();
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

            // Park rather than drop. Never force a load: the settling waits for the next visit,
            // exactly as parked deformation does.
            if (level.getChunkSource().getChunkNow(cx >> 4, cz >> 4) == null) {
                Long2IntOpenHashMap park = PARKED.computeIfAbsent(
                        parkKey(job.dimension, cx >> 4, cz >> 4),
                        key -> {
                            Long2IntOpenHashMap m = new Long2IntOpenHashMap();
                            m.defaultReturnValue(Integer.MIN_VALUE);
                            return m;
                        });
                park.put(k, job.excavated.get(k));   // the floor hint has to survive the wait too
                continue;
            }
            // Passes 0 and PASSES-1 bring down what is left hanging; the ones between take the raw
            // edges off the rock. With falling switched off, every pass goes to the rock rules
            // rather than being wasted.
            boolean fallPass = (job.pass == 0 || job.pass == PASSES - 1)
                    && GeyserConfig.UNSUPPORTED_BLOCKS_FALL.get();
            boolean moved;
            if (fallPass) {
                moved = reseat(level, cx, cz, job.excavated.get(k));
                // On the last pass the trunks are already gone, so anything still hanging is
                // canopy that lost its tree.
                if (job.pass == PASSES - 1) {
                    moved |= dropOrphanedLeaves(level, cx, cz);
                    moved |= dropUnsupportedWater(level, cx, cz);
                }
            } else {
                moved = relax(level, cx, cz);
            }
            if (moved) job.moved++;
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
     * <h2>Where the ground is measured from</h2>
     * Not from {@link TerrainProbe#groundY}, which is what this used to do and what left ice
     * shelves and soil rafts hanging over a rift for good. {@code groundY} walks down from the
     * heightmap and skips air, fluid, plants and tree parts - so a slab of ice or dirt floating in
     * mid-air <b>is</b> the ground as far as it is concerned. It returned the top of the raft,
     * {@code base = g + 1} was open sky, the drop came out as zero, and the method gave up before
     * it had looked at anything. A tree standing on such a raft rode out the same way, which is the
     * rest of the "some trees are still in the air" report.
     *
     * <p>So the anchor comes from the quake instead: {@code excavatedTop} is the highest cell it
     * actually turned to air in this column, and the real ground is the first solid block at or
     * below that. Nothing under the excavation is ever examined, which is what keeps this from
     * mistaking a cave roof for a raft and dropping the countryside into it. Columns with no hint -
     * the dilated ring, where there is orphaned canopy but never a raft - keep the old behaviour.</p>
     *
     * @param excavatedTop highest Y the quake emptied here, or {@link Integer#MIN_VALUE} if it
     *                     never touched this column
     * @return true if this column changed
     */
    private static boolean reseat(ServerLevel level, int x, int z, int excavatedTop) {
        int g = excavatedTop == Integer.MIN_VALUE
                ? TerrainProbe.groundY(level, x, z)
                : solidAtOrBelow(level, x, excavatedTop, z);
        if (g == Integer.MIN_VALUE) return false;
        if (!level.hasChunkAt(new BlockPos(x, g, z))) return false;

        boolean mayMoveBuilds = GeyserConfig.FALLING_INCLUDES_BUILDS.get();

        // The gap: how far the ground fell out from under whatever is up there.
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int roof = level.getMaxBuildHeight() - 1;
        int gapLimit = Math.min(g + 1 + GAP_SEARCH, roof);
        int base = g + 1;
        while (base < gapLimit && level.getBlockState(m.set(x, base, z)).isAir()) base++;
        int drop = base - (g + 1);
        if (drop <= 0 || base >= gapLimit) return false;
        int limit = Math.min(base + STACK_LIMIT, roof);

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

    /**
     * Clears leaves the quake has orphaned.
     *
     * <h2>Why this is needed at all</h2>
     * A canopy spreads over columns whose ground never moved, so {@link #reseat} never looks at
     * them: the trunk column drops and is cleared, and the leaves around it are left hanging over
     * untouched ground with a drop of zero.
     *
     * <p>Vanilla would normally rot them away. It cannot here. Leaf decay is driven by the
     * {@code distance} property, which is only recomputed when a neighbour update arrives, and
     * every edit this mod makes is written with flag 2 - client update, <b>no neighbour
     * notification</b>. So the leaves keep whatever distance they had, {@code randomTick} never
     * sees the 7 that would rot them, and they hang there permanently. They were not decaying
     * slowly; most of them were never going to decay at all.</p>
     *
     * <p>Rather than send neighbour updates for hundreds of thousands of quake edits, the canopy is
     * checked directly: a leaf with no log within {@link #LEAF_SUPPORT_RANGE} has lost its tree and
     * goes immediately. The search walks outward in rings and stops at the first log, so a leaf
     * still attached to something costs almost nothing to clear; only genuinely orphaned canopy
     * pays for the full box, and the pass runs under the same wall-clock deadline as everything
     * else here.</p>
     *
     * @return true if this column changed
     */
    private static boolean dropOrphanedLeaves(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = Math.min(g + 1 + GAP_SEARCH + STACK_LIMIT, level.getMaxBuildHeight() - 1);
        boolean changed = false;
        for (int y = g + 1; y <= top; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (!s.is(BlockTags.LEAVES)) continue;
            if (s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT)) continue;
            if (hasLogNear(level, x, y, z)) continue;
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            changed = true;
        }
        return changed;
    }

    /**
     * Drops water the quake left standing in mid-air.
     *
     * <p>Carving the ground out from under a pond leaves its cells hanging. Vanilla would collapse
     * them, but only on a neighbour update, and the quake writes with flag 2 - so the pond simply
     * stays up there in the shape of the ground that used to hold it. Same root cause as the
     * canopy, and just as visible.</p>
     *
     * <p>A cell is only cleared when the block under it is open air, so a pool that still has a
     * floor is left exactly as it is. Clearing from the bottom up lets one pass take a whole
     * hanging column rather than peeling one layer per pass.</p>
     *
     * @return true if this column changed
     */
    private static boolean dropUnsupportedWater(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int roof = Math.min(g + 1 + GAP_SEARCH + STACK_LIMIT, level.getMaxBuildHeight() - 1);
        boolean changed = false;

        // Walk up from the ground looking for the BOTTOM of a body of water that has air under it,
        // then take the whole body.
        //
        // Testing each cell on its own was not enough: in a pond left hanging by a quake, only the
        // lowest layer has air beneath it - every cell above has water below, so nothing read as
        // unsupported and the pond stayed up there whole. That is the "all the water is hanging in
        // the air and it did not even pour out" report.
        int y = g + 1;
        while (y <= roof) {
            if (!level.getBlockState(m.set(x, y, z)).isAir()) { y++; continue; }
            // Found air. Anything directly above it that is fluid is a hanging body.
            int base = y + 1;
            if (base > roof || level.getBlockState(m.set(x, base, z)).getFluidState().isEmpty()) {
                y++;
                continue;
            }
            int top = base;
            while (top <= roof && !level.getBlockState(m.set(x, top, z)).getFluidState().isEmpty()) {
                top++;
            }
            for (int c = base; c < top; c++) {
                level.setBlock(new BlockPos(x, c, z), Blocks.AIR.defaultBlockState(), 2);
            }
            changed = true;
            y = top;
        }
        return changed;
    }

    /** Is there a log within {@link #LEAF_SUPPORT_RANGE}? Searched in rings, nearest first. */
    private static boolean hasLogNear(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int floor = level.getMinBuildHeight(), roof = level.getMaxBuildHeight() - 1;
        for (int r = 1; r <= LEAF_SUPPORT_RANGE; r++) {
            for (int dy = -r; dy <= r; dy++) {
                int wy = y + dy;
                if (wy < floor || wy > roof) continue;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // Only the shell of this ring; the inside was covered by a smaller r.
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                        if (!level.hasChunkAt(m.set(x + dx, wy, z + dz))) continue;
                        if (level.getBlockState(m).is(BlockTags.LOGS)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * First real ground at or below {@code top}, using the same idea of "ground" as
     * {@link TerrainProbe#groundY} - air, fluid, plants and tree parts are not it - but starting
     * from a height the caller chooses instead of from the heightmap.
     *
     * <p>That one difference is the whole point: started from the top of the world it would find
     * a floating raft, started from the floor of the quake's own excavation it cannot.</p>
     *
     * @return the Y of the ground, or {@link Integer#MIN_VALUE} if there is none within reach
     */
    private static int solidAtOrBelow(ServerLevel level, int x, int top, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int start = Math.min(top, level.getMaxBuildHeight() - 1);
        int floor = Math.max(level.getMinBuildHeight(), start - (GAP_SEARCH + STACK_LIMIT));
        for (int y = start; y >= floor; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty()) continue;
            if (isPlant(s) || s.is(Blocks.MANGROVE_ROOTS)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
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
