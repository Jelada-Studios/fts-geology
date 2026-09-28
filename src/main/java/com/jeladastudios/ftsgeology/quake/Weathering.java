package com.jeladastudios.ftsgeology.quake;

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
 * {@code fallingIncludesPlayerBlocks} leaves builds where they are. Every write goes without neighbour
 * shape updates ({@link Earthquake#FLAGS}): those cost more than the quake, dropped every plant they
 * undermined as an item, and loaded the chunk next door on the server thread.</p>
 */
public final class Weathering {

    private Weathering() {}

    /**
     * How many passes the corridor gets. The first and the last reseat what was growing on the
     * ground; the one between takes the raw edges off the rock.
     */
    private static final int PASSES = 5;
    /**
     * The pass that brings down what hangs a second time, after the rock has relaxed, and the finishing ones: twice,
     * since taking a column down changes what its neighbours stand over.
     */
    private static final int SECOND_FALL = 2, FINISH = 3;

    /** How far above the new ground to look for the underside of a hanging stack; covers the deepest cut. */
    private static final int GAP_SEARCH = 40;

    /** Tallest hanging stack brought down. Separate from {@link #GAP_SEARCH} so a big tree fits after a deep drop. */
    private static final int STACK_LIMIT = 48;

    /** How far across from a felled trunk its crown is taken: a canopy's reach, and a little of the next tree's. */
    static final int CROWN_REACH = 6;

    /** How far the corridor is widened before settling, so a wide canopy's outer leaves are visited too. */
    private static final int CORRIDOR_DILATION = 12;

    /** Columns examined per tick at most; the wall-clock budget is the real brake. */
    private static final int COLUMNS_PER_TICK = 1000;

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
        /** Per felled trunk column, the Y its trunk stood on, so the log can count trees. */
        final Long2IntOpenHashMap felledBase = new Long2IntOpenHashMap();
        /** Time spent, and the longest slice, so a slow corridor shows in the log. */
        long nanos, worstNanos;
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
            // What stood on the ground and moved with it was not dug away: its air is not a floor hint.
            int airTop = e.state().isAir() && !e.carried() ? e.pos().getY() : Integer.MIN_VALUE;
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
        com.jeladastudios.ftsgeology.util.Diagnostics.info("weathering queued: {} columns ({} from {} edits, {} on the edge)",
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
        // The ground settles once it has stopped moving. A landslide's settling run while the rupture was still
        // lifting a forest took a tree half moved for one left hanging and felled it.
        if (Earthquake.moving()) return;
        long deadline = System.nanoTime() + budgetNanos;

        Job job = QUEUE.peek();
        ServerLevel level = server.getLevel(job.dimension);
        if (level == null) { QUEUE.poll(); return; }

        int done = 0;
        long started = System.nanoTime();
        try {
        while (done < COLUMNS_PER_TICK && System.nanoTime() < deadline) {
            if (job.cursor >= job.columns.length) {
                job.cursor = 0;
                if (++job.pass >= PASSES) {
                    com.jeladastudios.ftsgeology.util.Diagnostics.info("weathering finished: {} blocks moved over {} columns, {} trees felled, {} ms, longest tick {} ms",
                            job.moved, job.columns.length, job.felledBase.size(), job.nanos / 1_000_000,
                            job.worstNanos / 1_000_000);
                    QUEUE.poll();
                    // The ground has settled: the rivers and lakes on it are laid again.
                    com.jeladastudios.ftsgeology.hydrology.RiverRepair.afterQuake(level, job.columns);
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
            // The first and the last pass bring down what hangs; the one between relaxes the rock.
            boolean fallPass = (job.pass == 0 || job.pass == SECOND_FALL)
                    && GeyserConfig.UNSUPPORTED_BLOCKS_FALL.get();
            boolean moved;
            if (job.pass >= FINISH) {
                moved = finish(level, cx, cz);
            } else if (fallPass) {
                moved = reseat(level, cx, cz, job.excavated.get(k), job);
                // After the rock has relaxed and reseat has run again: spires, hanging water.
                if (job.pass == SECOND_FALL) {
                    moved |= topple(level, cx, cz);
                    moved |= dropUnsupportedWater(level, cx, cz);
                }
            } else if (job.pass == 1) {
                moved = relax(level, cx, cz, k, job);
            } else {
                moved = false;
            }
            if (moved) job.moved++;
        }
        } finally {
            long spent = System.nanoTime() - started;
            job.nanos += spent;
            job.worstNanos = Math.max(job.worstNanos, spent);
        }
    }

    /**
     * Brings down whatever the quake left hanging over this column, as one stack, keeping every block
     * and its state. A tree whose ground went is felled instead, trunk and crown, in {@link #fell}. Ground
     * cover left over air is cleared, since without shape updates nothing else would take it. A gap
     * holding fluid is otherwise left alone so a lake is never drained.
     *
     * <p>Ground is found from {@code excavatedTop}, the highest cell the quake emptied, not from
     * {@link TerrainProbe#groundY}, which takes a floating raft for the ground. Nothing below the
     * excavation is examined, so a cave roof is never mistaken for a raft.</p>
     *
     * @param excavatedTop highest Y the quake emptied here, or {@link Integer#MIN_VALUE} if none
     * @return true if this column changed
     */
    private static boolean reseat(ServerLevel level, int x, int z, int excavatedTop, Job job) {
        // Nothing dug away here, so nothing was left hanging: the air under a crown or a bridge is its own.
        if (excavatedTop == Integer.MIN_VALUE) return false;
        int g = solidAtOrBelow(level, x, excavatedTop, z);
        if (g == Integer.MIN_VALUE) return false;
        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(x, g, z))) return false;

        boolean mayMoveBuilds = GeyserConfig.FALLING_INCLUDES_BUILDS.get();

        // The gap: how far the ground fell out from under whatever is up there.
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int roof = level.getMaxBuildHeight() - 1;
        int gapLimit = Math.min(g + 1 + GAP_SEARCH, roof);
        int base = g + 1;
        while (base < gapLimit && level.getBlockState(m.set(x, base, z)).isAir()) base++;
        int drop = base - (g + 1);
        // Only what rested on the dug cells hangs. A stack with air of its own under it, over the highest of them,
        // stood over air before the quake: a crown, a branch, a roof.
        if (base > excavatedTop + 1) return false;
        // A tree left over water, on a bank the quake took into the river, is felled like one over air.
        int over = base;
        while (over < gapLimit && !level.getBlockState(m.set(x, over, z)).getFluidState().isEmpty()) over++;
        if (over > base && over < gapLimit && isTrunk(level.getBlockState(m.set(x, over, z)))) {
            return fell(level, x, over, z, job, true);
        }
        if (drop <= 0 || base >= gapLimit) return false;
        int limit = Math.min(base + STACK_LIMIT, roof);

        // Read the hanging stack. Only fluid stops it: dropping through water would drain a lake.
        int top = base;
        boolean allPlant = true, allCover = true;
        int trunk = Integer.MIN_VALUE;
        while (top < limit) {
            BlockState s = level.getBlockState(m.set(x, top, z));
            if (s.isAir()) break;
            if (!s.getFluidState().isEmpty()) return false;
            // Trees are checked first, so they fall even when builds may not move.
            if (!mayMoveBuilds && !isPlant(s) && EruptionHandler.isPlayerPlaced(s)) return false;
            if (!isPlant(s)) allPlant = false;
            if (!TerrainProbe.isVegetation(s)) allCover = false;
            if (trunk == Integer.MIN_VALUE && isTrunk(s)) trunk = top;
            top++;
        }
        int height = top - base;
        if (height <= 0) return false;
        // A crown or a branch reaching out over this column from a tree that still stands on its ground stood over
        // air before the quake as well: it is held, not hanging. Only a tree standing on nothing, and leaves nothing
        // holds, come down.
        BlockState foot = level.getBlockState(m.set(x, base, z));
        if (isPlant(foot) && !TerrainProbe.isVegetation(foot)) {
            if (trunk != Integer.MIN_VALUE ? grounded(level, x, trunk, z)
                    : foot.is(BlockTags.LEAVES) && TerrainProbe.crownHeld(level, new BlockPos(x, base, z), foot)) {
                return false;
            }
        }
        if (allPlant && trunk == Integer.MIN_VALUE) {
            // Grass, a fern or leaves left in the air: gone, as a shape update would have had them. A crown
            // whose tree was felled went with it; what is still here belongs to nothing.
            for (int y = base; y < top; y++) {
                QuakeWrites.set(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
            return true;
        }
        // The tree goes; whatever it stood on still comes down, unless that is only its own leaves, as under
        // an acacia's branch: those go too, not into a heap on the ground.
        if (trunk != Integer.MIN_VALUE) {
            fell(level, x, trunk, z, job, true);
            height = trunk - base;
            if (height <= 0) return true;
            boolean leavesOnly = true;
            for (int i = 0; i < height && leavesOnly; i++) leavesOnly = isPlant(level.getBlockState(m.set(x, base + i, z)));
            if (leavesOnly) {
                for (int i = 0; i < height; i++) {
                    QuakeWrites.set(level, new BlockPos(x, base + i, z), Blocks.AIR.defaultBlockState());
                }
                return true;
            }
        }

        // The whole stack comes down together, in order, so it lands the same way up.
        BlockState[] stack = new BlockState[height];
        for (int i = 0; i < height; i++) stack[i] = level.getBlockState(m.set(x, base + i, z));
        for (int i = 0; i < height; i++) {
            QuakeWrites.set(level, new BlockPos(x, base + i, z), Blocks.AIR.defaultBlockState());
        }
        for (int i = 0; i < height; i++) {
            QuakeWrites.set(level, new BlockPos(x, g + 1 + i, z), stack[i]);
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

    /**
     * Brings down the spires a rupture left standing in its trench: a column of rock standing
     * {@link #SLENDER_HEIGHT} or more above the ground on all four sides. Its blocks are lowered onto the
     * lowest neighbour, top first, and a two-block stump is left, the way a broken spire is. Only on
     * ground the quake moved, so natural hoodoos are never touched.
     */
    private static boolean topple(ServerLevel level, int x, int z) {
        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(x, 0, z))) return false;
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;

        // The ground round it: the spire stands above the highest of the four.
        int around = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(nx, g, nz))) return false;
            int n = TerrainProbe.groundY(level, nx, nz);
            if (n == Integer.MIN_VALUE) return false;
            around = Math.max(around, n);
        }
        int height = g - around;
        if (height < SLENDER_HEIGHT) return false;

        // Rock all the way: a tree, a fluid or bedrock in the stack makes it something else.
        boolean mayMoveBuilds = GeyserConfig.FALLING_INCLUDES_BUILDS.get();
        for (int y = around + 1; y <= g; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty() || s.is(Blocks.BEDROCK) || isPlant(s)) return false;
            if (!mayMoveBuilds && EruptionHandler.isPlayerPlaced(s)) return false;
        }

        // Each block goes to the lowest of the eight neighbours, strictly lower than it was, so it ends.
        boolean moved = false;
        int keep = around + 2;
        for (int y = g; y > keep; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            BlockPos rest = lowestNeighbourTop(level, x, y, z);
            if (rest == null) break;                // nowhere lower to go; the rest stays
            QuakeWrites.set(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            QuakeWrites.set(level, rest, s);
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
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(nx, y, nz))) continue;
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

    /**
     * Fells the tree whose trunk starts at {@code from} in this column: the trunk, the other three columns of a
     * two-by-two trunk standing beside it, and the crown round them all, at once. Taken whole, no leaf is left
     * to rot on its own and drop an item, and no half of a big pine is left standing bare.
     */
    private static boolean fell(ServerLevel level, int x, int from, int z, Job job, boolean withNeighbours) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // A Dynamic Trees tree comes down whole, falling over as a felled one does, by its own mod's way of felling.
        BlockPos base = new BlockPos(x, from, z);
        if (com.jeladastudios.ftsgeology.compat.DynamicTreesFelling.isBranch(level.getBlockState(base))
                && com.jeladastudios.ftsgeology.compat.DynamicTreesFelling.fell(level, base,
                        net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(level.random))) {
            job.felledBase.put(key(x, z), from);
            return true;
        }
        int roof = Math.min(from + STACK_LIMIT, level.getMaxBuildHeight() - 1);
        int top = from;
        while (top <= roof && isPlant(level.getBlockState(m.set(x, top, z)))) {
            QuakeWrites.set(level, new BlockPos(x, top, z), Blocks.AIR.defaultBlockState());
            top++;
        }
        job.felledBase.put(key(x, z), from);
        int reach = CROWN_REACH;
        if (withNeighbours) {
            // The rest of a two-by-two trunk: a log beside this one at its foot, or a block either side of it.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = x + dx, nz = z + dz;
                    if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(nx, from, nz))) continue;
                    for (int y = from - 1; y <= from + 1; y++) {
                        if (!isTrunk(level.getBlockState(m.set(nx, y, nz)))) continue;
                        // Down to its own foot first, so no stump of it is left standing.
                        int foot = y;
                        while (foot - 1 > level.getMinBuildHeight() && isTrunk(level.getBlockState(m.set(nx, foot - 1, nz)))) foot--;
                        fell(level, nx, foot, nz, job, false);
                        // A big tree's crown spreads further than a single trunk's: a dark oak's outer leaves
                        // sit seven blocks out, and left behind they rot one by one into items.
                        reach = CROWN_REACH + 2;
                        break;
                    }
                }
            }
        }
        takeCrown(level, x, from - 1, top + 8, z, reach);
        return true;
    }

    /**
     * Takes the crown round a felled trunk: leaves and mushroom caps within {@code reach} of it, between
     * the trunk's foot and a little over its top. A neighbouring tree may lose a few overlapping leaves;
     * finding each leaf's own trunk cost more than the quake itself.
     */
    static void takeCrown(ServerLevel level, int x, int lo, int hi, int z, int reach) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int floor = Math.max(lo, level.getMinBuildHeight()), roof = Math.min(hi, level.getMaxBuildHeight() - 1);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int cx = x + dx, cz = z + dz;
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(cx, floor, cz))) continue;
                for (int y = floor; y <= roof; y++) {
                    if (!TerrainProbe.isCrown(level.getBlockState(m.set(cx, y, cz)))) continue;
                    QuakeWrites.set(level, new BlockPos(cx, y, cz), Blocks.AIR.defaultBlockState());
                }
            }
        }
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
                QuakeWrites.set(level, new BlockPos(x, c, z), Blocks.AIR.defaultBlockState());
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

    /** How much of a tree is walked looking for where it stands; one bigger than this is taken to stand. */
    private static final int TREE_WALK = 256;

    /**
     * Whether the tree this wood belongs to stands on anything: a block of it, reached through its trunk and branches,
     * with ground under it.
     */
    private static boolean grounded(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        BlockPos start = new BlockPos(x, y, z);
        queue.add(start);
        seen.add(start.asLong());
        while (!queue.isEmpty()) {
            if (seen.size() > TREE_WALK) return true;
            BlockPos p = queue.poll();
            BlockState under = level.getBlockState(m.set(p.getX(), p.getY() - 1, p.getZ()));
            if (!under.isAir() && under.getFluidState().isEmpty() && !isPlant(under)) return true;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m) || seen.contains(m.asLong()) || !isTrunk(level.getBlockState(m))) continue;
                        seen.add(m.asLong());
                        queue.add(m.immutable());
                    }
                }
            }
        }
        return false;
    }

    /** What holds a tree or a huge mushroom up. */
    private static boolean isTrunk(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(Blocks.MUSHROOM_STEM);
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
    private static boolean relax(ServerLevel level, int x, int z, long k, Job job) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;
        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(x, g, z))) return false;

        BlockPos crest = new BlockPos(x, g, z);
        BlockState top = level.getBlockState(crest);
        if (top.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(top)) return false;
        // Ground something stands on holds: a tree's roots, or a wall's footing. Shedding it left the tree hanging.
        BlockState on = level.getBlockState(crest.above());
        if (!on.isAir() && !TerrainProbe.isVegetation(on)) return false;
        if (!top.getFluidState().isEmpty()) return false;

        int highest = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        BlockPos foot = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            // A neighbour in a chunk that is not loaded is never read: that would load it here, on the
            // server thread. The column waits for its next pass.
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(nx, g, nz))) return false;
            int n = TerrainProbe.groundY(level, nx, nz);
            // A neighbour with no ground at all is a cliff edge or a cave mouth. Leave the column
            // alone rather than shovelling it into a hole.
            if (n == Integer.MIN_VALUE) return false;
            highest = Math.max(highest, n);
            if (n < lowest) { lowest = n; foot = new BlockPos(nx, n + 1, nz); }
        }

        if (g - highest >= SPIKE) {
            // Nothing holds it up on any side.
            QuakeWrites.set(level, crest, Blocks.AIR.defaultBlockState());
            dug(job, k, g);
            return true;
        }
        if (g - lowest >= SCARP && foot != null) {
            // The crest sheds one block onto the foot: talus, not deletion.
            BlockState at = level.getBlockState(foot);
            if (!at.isAir() && !TerrainProbe.isVegetation(at)) return false;
            if (EruptionHandler.isPlayerPlaced(at)) return false;
            QuakeWrites.set(level, crest, Blocks.AIR.defaultBlockState());
            QuakeWrites.set(level, foot, top);
            dug(job, k, g);
            return true;
        }
        return false;
    }

    /** The ground at {@code y} went from this column: what stood on it is brought down by the next falling pass. */
    private static void dug(Job job, long k, int y) {
        if (y > job.excavated.get(k)) job.excavated.put(k, y);
    }

    /**
     * How far a column may stand over or under all four of its neighbours once the ground has been finished: not at
     * all. Real ground has no column standing alone a block proud of all round it, and the moving ground left a grain
     * of them -- rough, pitted slopes where the ground had been smooth.
     */
    private static final int FINISHED_STEP = 1;

    /**
     * The last look at the ground a quake left: a column standing on its own over all four neighbours is cut down to
     * the highest of them, a pit sunk under all four is filled from the lowest, and soil laid bare where the ground
     * round it is grown over grows over too, as does ground bare of the snow lying all round it. A real scarp or
     * fissure is a line, and has neighbours along it at its own height, so this leaves it be; what it takes is the
     * odd pillar and hole the moving left.
     */
    private static boolean finish(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE || !com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(x, g, z))) return false;
        int highest = Integer.MIN_VALUE, lowest = Integer.MAX_VALUE;
        BlockState lowTop = null;
        int grown = 0, snowy = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(nx, g, nz))) return false;
            int n = TerrainProbe.groundY(level, nx, nz);
            if (n == Integer.MIN_VALUE) return false;
            highest = Math.max(highest, n);
            if (n < lowest) {
                lowest = n;
                lowTop = level.getBlockState(m.set(nx, n, nz));
            }
            if (level.getBlockState(m.set(nx, n, nz)).is(Blocks.GRASS_BLOCK)) grown++;
            BlockState over = level.getBlockState(m.set(nx, n + 1, nz));
            if (over.is(Blocks.SNOW) || level.getBlockState(m.set(nx, n, nz)).is(Blocks.SNOW_BLOCK)) snowy++;
        }
        boolean moved = false;
        // Cover left over a gap or on more of itself -- a snow layer a block over its ground, two layers one on the
        // other -- comes down onto the ground. A mountain the quake had been over was left shelved with them.
        BlockState a1 = level.getBlockState(m.set(x, g + 1, z));
        for (int y = g + 2; y <= g + 4; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            boolean cover = s.getFluidState().isEmpty() && TerrainProbe.isVegetation(s) && !isTrunk(s);
            if (cover && (a1.isAir() || a1.is(Blocks.SNOW) && s.is(Blocks.SNOW))) {
                QuakeWrites.set(level, m.immutable(), Blocks.AIR.defaultBlockState());
                BlockPos onGround = new BlockPos(x, g + 1, z);
                if (a1.isAir() && s.canSurvive(level, onGround)) QuakeWrites.set(level, onGround, s);
                moved = true;
            }
            break;
        }
        // A pillar: trimmed down, a block at a time from its top, as long as what comes off is ground. One a tree
        // stands on is a knoll and stays; grass on one goes with the top.
        BlockState on = level.getBlockState(m.set(x, g + 1, z));
        if (g - highest > FINISHED_STEP - 1 && (isTrunk(on) || !on.isAir() && !TerrainProbe.isVegetation(on))) return false;
        if (g - highest > FINISHED_STEP - 1 && TerrainProbe.isVegetation(on)) {
            for (int y = g + 1; y <= g + 2 && TerrainProbe.isVegetation(level.getBlockState(m.set(x, y, z))); y++) {
                QuakeWrites.set(level, m.immutable(), Blocks.AIR.defaultBlockState());
            }
        }
        while (g - highest > FINISHED_STEP - 1) {
            BlockState s = level.getBlockState(m.set(x, g, z));
            if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s) || !s.getFluidState().isEmpty()) break;
            QuakeWrites.set(level, m.immutable(), Blocks.AIR.defaultBlockState());
            g--;
            moved = true;
        }
        // A pit: filled from what its lowest neighbour is made of.
        while (lowest - g > FINISHED_STEP - 1 && lowTop != null && !EruptionHandler.isPlayerPlaced(lowTop)
                && lowTop.getFluidState().isEmpty() && !TerrainProbe.isVegetation(lowTop)) {
            BlockState over = level.getBlockState(m.set(x, g + 1, z));
            if (!over.isAir() && !TerrainProbe.isVegetation(over)) break;
            QuakeWrites.set(level, m.immutable(), lowTop);
            g++;
            moved = true;
        }
        // Bare soil among grown-over ground grows over.
        BlockState top = level.getBlockState(m.set(x, g, z));
        if (grown >= 2 && (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT))
                && level.getBlockState(m.set(x, g + 1, z)).isAir()) {
            QuakeWrites.set(level, new BlockPos(x, g, z), Blocks.GRASS_BLOCK.defaultBlockState());
            moved = true;
        }
        // Snow lies on it again where it lies all round, and the column had it before, or the sky still snows on it.
        BlockPos over = new BlockPos(x, g + 1, z);
        if ((snowy >= 2 || on.is(Blocks.SNOW) && moved) && level.getBlockState(over).isAir()
                && Blocks.SNOW.defaultBlockState().canSurvive(level, over)
                && level.getBiome(over).value().coldEnoughToSnow(over)) {
            QuakeWrites.set(level, over, Blocks.SNOW.defaultBlockState());
            moved = true;
        }
        return moved;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
