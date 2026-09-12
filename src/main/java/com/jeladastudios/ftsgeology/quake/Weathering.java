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
 * Lets the ground an earthquake tore up settle afterwards, over a few passes along the corridor.
 *
 * <ul>
 *   <li><b>Spikes fall:</b> a column three or more above all four neighbours loses its top block.</li>
 *   <li><b>What stood on it comes down:</b> a stack left hanging is set back down on the new ground,
 *       blocks and states intact. Plants whose ground fell away are cleared, as on a fresh scarp.</li>
 *   <li><b>Crests shed to their foot:</b> a column four above its lowest neighbour moves its top
 *       block onto that neighbour, so a scarp grows a talus apron.</li>
 * </ul>
 *
 * <p>Only columns the quake edited are visited. {@code unsupportedBlocksFall} turns falling off and
 * {@code fallingIncludesPlayerBlocks} leaves builds where they are.</p>
 */
public final class Weathering {

    private Weathering() {}

    /**
     * How many passes the corridor gets. The first and the last reseat what was growing on the
     * ground; the ones between take the raw edges off the rock.
     */
    private static final int PASSES = 5;

    /** How far above the new ground to look for the underside of a hanging stack; covers the deepest cut. */
    private static final int GAP_SEARCH = 40;

    /** Tallest hanging stack brought down. Separate from {@link #GAP_SEARCH} so a big tree fits after a deep drop. */
    private static final int STACK_LIMIT = 48;

    /** How far a tree may ride the ground down before it counts as a landslide. Zero: undermined trees fall. */
    private static final int RIDE_LIMIT = 0;

    /** How far from a log a leaf still counts as attached. Vanilla's own limit. */
    private static final int LEAF_SUPPORT_RANGE = 6;

    /** How far the corridor is widened before settling, so a wide canopy's outer leaves are visited too. */
    private static final int CORRIDOR_DILATION = 12;

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
        /** Per column, the highest Y the quake turned to air: the anchor {@link #reseat} measures ground from. */
        final Long2IntMap excavated;
        int cursor;
        int pass;
        int moved;

        /** Bounding box, so {@link #pendingNear} does not walk every column each tick. Generous, never early. */
        final int minX, maxX, minZ, maxZ;

        Job(ResourceKey<Level> dimension, long[] columns, Long2IntMap excavated) {
            this.dimension = dimension;
            this.columns = columns;
            this.excavated = excavated;
            int lx = Integer.MAX_VALUE, hx = Integer.MIN_VALUE;
            int lz = Integer.MAX_VALUE, hz = Integer.MIN_VALUE;
            for (long c : columns) {
                int x = (int) (c >> 32), z = (int) c;
                if (x < lx) lx = x;
                if (x > hx) hx = x;
                if (z < lz) lz = z;
                if (z > hz) hz = z;
            }
            this.minX = lx; this.maxX = hx; this.minZ = lz; this.maxZ = hz;
        }

        boolean overlaps(int x, int z, int radius) {
            return x + radius >= minX && x - radius <= maxX
                    && z + radius >= minZ && z - radius <= maxZ;
        }
    }

    private static final Deque<Job> QUEUE = new ArrayDeque<>();

    /**
     * Columns whose chunk was unloaded when their pass came, parked by chunk and re-queued when it
     * loads. In memory only: a restart loses unfinished settling, not correctness.
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
     * Queues the corridor of a finished quake. The planned edits are collapsed to unique columns and
     * only the boundary is dilated: dilating every edit took seconds on a large rupture, dilating the
     * perimeter takes milliseconds.
     */
    public static void enqueue(ServerLevel level, List<QuakePlanner.Edit> edits) {
        if (edits.isEmpty()) return;

        // Pass 1: unique columns, each remembering the highest cell the quake turned to air. That
        // hint is what reseat() anchors on; see the note there.
        Long2IntOpenHashMap base = new Long2IntOpenHashMap();
        base.defaultReturnValue(Integer.MIN_VALUE);
        for (QuakePlanner.Edit e : edits) {
            long k = key(e.pos().getX(), e.pos().getZ());
            int airTop = e.state().isAir() ? e.pos().getY() : Integer.MIN_VALUE;
            if (airTop > base.get(k)) base.put(k, airTop);
            else if (!base.containsKey(k)) base.put(k, Integer.MIN_VALUE);
        }

        Long2IntOpenHashMap seen = new Long2IntOpenHashMap(base);
        seen.defaultReturnValue(Integer.MIN_VALUE);

        // Pass 2: the boundary - a column with at least one of its four neighbours outside the set.
        long[] cols = base.keySet().toLongArray();
        LongOpenHashSet edge = new LongOpenHashSet();
        for (long k : cols) {
            int x = (int) (k >> 32), z = (int) k;
            if (!base.containsKey(key(x + 1, z)) || !base.containsKey(key(x - 1, z))
                    || !base.containsKey(key(x, z + 1)) || !base.containsKey(key(x, z - 1))) {
                edge.add(k);
            }
        }

        // Pass 3: dilate the boundary only, carrying each edge column's floor hint outward with it.
        for (long k : edge) {
            int x = (int) (k >> 32), z = (int) k;
            int airTop = base.get(k);
            for (int dx = -CORRIDOR_DILATION; dx <= CORRIDOR_DILATION; dx++) {
                for (int dz = -CORRIDOR_DILATION; dz <= CORRIDOR_DILATION; dz++) {
                    long kk = key(x + dx, z + dz);
                    if (airTop > seen.get(kk)) seen.put(kk, airTop);
                    else if (!seen.containsKey(kk)) seen.put(kk, Integer.MIN_VALUE);
                }
            }
        }

        QUEUE.add(new Job(level.dimension(), seen.keySet().toLongArray(), seen));
        GeysersMod.LOGGER.info("weathering queued: {} columns ({} from {} edits, {} on the edge)",
                seen.size(), base.size(), edits.size(), edge.size());
    }

    /** Drops everything still settling; used when a server stops or a command cancels. */
    public static int clear() {
        int n = QUEUE.size();
        QUEUE.clear();
        PARKED.clear();
        return n;
    }

    /**
     * Is settling still outstanding near here? Asked by {@link QuakeQuiet} before springs and volcanoes
     * rebuild. Only queued work counts: parked work waits on chunks nobody may revisit, and counting it
     * would hold a zone shut for ever.
     */
    public static synchronized boolean pendingNear(ServerLevel level, int x, int z, int radius) {
        for (Job job : QUEUE) {
            if (!job.dimension.equals(level.dimension())) continue;
            if (job.overlaps(x, z, radius)) return true;
        }
        return false;
    }

    /** Settles a slice of the corridor, bounded by a column count and a wall-clock deadline. */
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
            // Passes 0 and the last bring down what hangs; the ones between relax the rock.
            boolean fallPass = (job.pass == 0 || job.pass == PASSES - 1)
                    && GeyserConfig.UNSUPPORTED_BLOCKS_FALL.get();
            boolean moved;
            if (fallPass) {
                moved = reseat(level, cx, cz, job.excavated.get(k));
                // Last pass, after reseat: canopy that lost its tree, spires, hanging water.
                if (job.pass == PASSES - 1) {
                    moved |= topple(level, cx, cz, job.excavated.get(k));
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
     * Brings down whatever the quake left hanging over this column, as one stack, keeping every block
     * and its state. Plants whose ground fell past {@link #RIDE_LIMIT} are cleared instead, like a fresh
     * landslide scarp. A gap holding fluid is left alone so a lake is never drained.
     *
     * <p>Ground is found from {@code excavatedTop}, the highest cell the quake emptied, not from
     * {@link TerrainProbe#groundY}, which takes a floating raft for the ground. Nothing below the
     * excavation is examined, so a cave roof is never mistaken for a raft.</p>
     *
     * @param excavatedTop highest Y the quake emptied here, or {@link Integer#MIN_VALUE} if none
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

        // Read the hanging stack. Only fluid stops it: dropping through water would drain a lake.
        int top = base;
        boolean allPlant = true;
        while (top < limit) {
            BlockState s = level.getBlockState(m.set(x, top, z));
            if (s.isAir()) break;
            if (!s.getFluidState().isEmpty()) return false;
            // Trees are checked first, so they fall even when builds may not move.
            if (!mayMoveBuilds && !isPlant(s) && EruptionHandler.isPlayerPlaced(s)) return false;
            if (!isPlant(s)) allPlant = false;
            top++;
        }
        int height = top - base;
        if (height <= 0) return false;

        if (allPlant && drop > RIDE_LIMIT) {
            // Too far to have ridden it down: the vegetation goes, as on a fresh landslide scarp.
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
        // A puff of dust where a stack lands, for a drop worth seeing and only sometimes.
        if (drop >= 2 && level.random.nextInt(24) == 0) {
            level.sendParticles(
                    new net.minecraft.core.particles.BlockParticleOption(
                            net.minecraft.core.particles.ParticleTypes.BLOCK, stack[0]),
                    x + 0.5, g + 1.0, z + 0.5,
                    4, 0.35, 0.1, 0.35, 0.03);
        }
        return true;
    }

    /** A column this thin cannot stand this tall in ground a quake has just shaken. */
    private static final int SLENDER_HEIGHT = 4;

    /** Horizontal neighbours at a given height, above which the stack counts as braced. */
    private static final int BRACED_NEIGHBOURS = 2;

    /**
     * Brings down one-block spires a rupture left standing in its trench. Only on ground the quake
     * moved, so natural hoodoos are never touched, and the blocks are lowered onto the foot, not deleted.
     */
    private static boolean topple(ServerLevel level, int x, int z, int excavatedTop) {
        int floor = excavatedTop == Integer.MIN_VALUE
                ? TerrainProbe.groundY(level, x, z)
                : solidAtOrBelow(level, x, excavatedTop, z);
        if (floor == Integer.MIN_VALUE) return false;
        if (!level.hasChunkAt(new BlockPos(x, floor, z))) return false;

        boolean mayMoveBuilds = GeyserConfig.FALLING_INCLUDES_BUILDS.get();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        // How high the stack runs, and how much of it stands on its own.
        int top = floor;
        int lonely = 0;
        int ceiling = Math.min(floor + STACK_LIMIT, level.getMaxBuildHeight() - 1);
        while (top + 1 <= ceiling) {
            BlockState s = level.getBlockState(m.set(x, top + 1, z));
            if (s.isAir() || !s.getFluidState().isEmpty()) break;
            if (s.is(Blocks.BEDROCK)) return false;
            if (!mayMoveBuilds && !isPlant(s) && EruptionHandler.isPlayerPlaced(s)) return false;
            top++;
            if (bracing(level, x, top, z) < BRACED_NEIGHBOURS) lonely++;
        }

        int height = top - floor;
        if (height < SLENDER_HEIGHT) return false;
        // Braced for most of its height: a shoulder of rock, not a spire. Leave it.
        if (lonely * 2 < height) return false;

        // Each block goes to the lowest of the eight neighbours, strictly lower than it was, so it ends.
        boolean moved = false;
        int keep = floor + SLENDER_HEIGHT / 2;      // a stump is left, the way a broken spire is
        for (int y = top; y > keep; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            BlockPos rest = lowestNeighbourTop(level, x, y, z);
            if (rest == null) continue;             // nowhere lower to go; it stays
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(rest, s, 2);
            moved = true;
        }
        return moved;
    }

    /**
     * Where a block falling off a spire comes to rest: on top of the lowest column around it.
     *
     * @return the position to place it, or null if nothing nearby is lower than where it is now
     */
    private static BlockPos lowestNeighbourTop(ServerLevel level, int x, int y, int z) {
        BlockPos best = null;
        int bestY = y;                              // must land BELOW where it started
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = x + dx, nz = z + dz;
                if (!level.hasChunkAt(m.set(nx, y, nz))) continue;
                int t = TerrainProbe.groundY(level, nx, nz);
                if (t == Integer.MIN_VALUE) continue;
                if (t + 1 >= bestY) continue;
                if (!level.getBlockState(m.set(nx, t + 1, nz)).isAir()) continue;
                bestY = t + 1;
                best = new BlockPos(nx, t + 1, nz);
            }
        }
        return best;
    }

    /** How many of the four horizontal neighbours are solid at this height. */
    private static int bracing(ServerLevel level, int x, int y, int z) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState s = level.getBlockState(m.set(x + d.getStepX(), y, z + d.getStepZ()));
            if (!s.isAir() && s.getFluidState().isEmpty() && !isPlant(s)) n++;
        }
        return n;
    }

    /**
     * Clears leaves with no log within {@link #LEAF_SUPPORT_RANGE}. Vanilla would never rot them: the
     * quake writes without neighbour updates, so their distance property is never recomputed.
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
            if (TerrainProbe.hasLogNear(level, x, y, z, LEAF_SUPPORT_RANGE)) continue;
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            changed = true;
        }
        return changed;
    }

    /**
     * Drops water the quake left standing in mid-air: every fluid body with air under its lowest cell.
     *
     * @return true if this column changed
     */
    private static boolean dropUnsupportedWater(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int roof = Math.min(g + 1 + GAP_SEARCH + STACK_LIMIT, level.getMaxBuildHeight() - 1);
        boolean changed = false;

        // Find the bottom of each fluid body with air under it and take the whole body at once.
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

    /**
     * First real ground at or below {@code top}, by {@link TerrainProbe#groundY}'s idea of ground but
     * started from the quake's excavation, so a floating raft above it is never taken for the ground.
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
            // The crest sheds one block onto the foot: talus, not deletion.
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
