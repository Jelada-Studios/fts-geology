package com.jeladastudios.ftsgeology.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads the shape of the land before anything is placed on it. {@code Heightmap.WORLD_SURFACE} gives
 * the topmost non-air block, often a flower or a tree canopy; {@link #groundY} walks past that to real
 * soil or rock, and the site checks tell a feature whether the ground is flat and dry enough.
 */
public final class TerrainProbe {

    private TerrainProbe() {}

    /** How far down {@link #groundY} will walk before giving up (tall jungle trees plus slack). */
    private static final int MAX_WALK_DOWN = 64;

    /**
     * Ground cover that is part of the landscape rather than part of a build: grass, flowers, crops,
     * mushrooms, vines, snow layers. Safe to clear, and never to be mistaken for player work.
     *
     * <p>Leaves and logs are not included: skipped when finding ground, but a cabin is made of logs,
     * so they are never cleared as cover.</p>
     */
    public static boolean isVegetation(BlockState s) {
        if (s.isAir()) return false;
        // Water is in replaceable_by_trees since mangroves; it is not cover, and clearing it drained a caldera's lake.
        if (!s.getFluidState().isEmpty()) return false;
        return s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SAPLINGS)
                || s.is(BlockTags.CROPS)
                || s.is(BlockTags.SMALL_FLOWERS)
                || s.is(BlockTags.TALL_FLOWERS)
                || s.is(BlockTags.REPLACEABLE_BY_TREES)
                || s.is(Blocks.GRASS) || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)
                || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.VINE) || s.is(Blocks.GLOW_LICHEN)
                || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.SNOW)
                || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM)
                || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.LILY_PAD)
                || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS) || s.is(Blocks.KELP)
                || s.is(Blocks.KELP_PLANT);
    }

    /**
     * Tree material, huge mushrooms and bee nests included: skipped when hunting for ground. Not cleared as
     * cover, since a cabin is made of logs; only a volcano's own site clearing takes it. A mushroom cap read
     * as ground made a step of several blocks.
     */
    public static boolean isTreePart(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.MANGROVE_ROOTS)
                || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BEE_NEST);
    }

    /** How far a leaf may be from a log before vanilla would rot it, and a huge mushroom's cap from its stem. */
    public static final int LEAF_REACH = 6, CAP_REACH = 4;

    /**
     * Takes what a clearing cut off its trunk from one column: leaves no log holds and huge mushroom caps no stem
     * holds, by {@link #crownHeld}. Clearing writes without neighbour updates, so the leaves would never rot on
     * their own.
     */
    public static void dropLooseCrowns(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return;
        int g = groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        int top = Math.min(g + 40, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        for (int y = g + 1; y <= top; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (!isCrown(s) || crownHeld(level, p, s)) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                    | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        }
    }

    /** A block of a tree's or a huge mushroom's crown that has to be held up by a trunk: leaves that rot, caps. */
    public static boolean isCrown(BlockState s) {
        if (s.is(BlockTags.LEAVES)) {
            return !(s.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    && s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT));
        }
        return s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK);
    }

    /**
     * Whether a crown block still hangs from a trunk: a log within {@link #LEAF_REACH} steps through leaves, which is
     * vanilla's own distance rule, or a stem within {@link #CAP_REACH} steps through cap. Any log within reach is not
     * enough: in a dark forest the next tree's trunk is always that close, and a cut crown was left hanging by it.
     */
    public static boolean crownHeld(ServerLevel level, BlockPos start, BlockState state) {
        boolean leaf = state.is(BlockTags.LEAVES);
        int reach = leaf ? LEAF_REACH : CAP_REACH;
        java.util.function.Predicate<BlockState> trunk = leaf ? s -> s.is(BlockTags.LOGS) : s -> s.is(Blocks.MUSHROOM_STEM);
        java.util.function.Predicate<BlockState> crown = leaf ? s -> s.is(BlockTags.LEAVES)
                : s -> s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK);
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        queue.add(start);
        seen.add(start.asLong());
        int floor = level.getMinBuildHeight(), roof = level.getMaxBuildHeight() - 1;
        for (int step = 0; step < reach && !queue.isEmpty(); step++) {
            for (int n = queue.size(); n > 0; n--) {
                BlockPos p = queue.poll();
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos q = p.relative(d);
                    if (q.getY() < floor || q.getY() > roof || !seen.add(q.asLong())) continue;
                    if (!level.hasChunkAt(q)) continue;
                    BlockState s = level.getBlockState(q);
                    if (trunk.test(s)) return true;
                    if (crown.test(s)) queue.add(q);
                }
            }
        }
        return false;
    }

    /**
     * Y of the topmost REAL ground block in a column - soil, sand or rock - ignoring plants, trees,
     * snow cover and any fluid above it. Returns {@link Integer#MIN_VALUE} if the column has no
     * ground within reach (all air, or buried under more than {@link #MAX_WALK_DOWN} of cover).
     */
    public static int groundY(LevelReader level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int floor = level.getMinBuildHeight();
        int g = walkDown(level, m, x, y, z, floor);
        if (g != Integer.MIN_VALUE) return g;
        // A tree at a chunk's edge, put there by a neighbour that was finished first, can hang its leaves over a column
        // whose ground is far below: a big cone built the neighbour up sixty blocks and grew a forest on it, and the walk
        // from the leaves gave up before it reached the ground under them. Start again under the leaves.
        int under = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return under < y ? walkDown(level, m, x, under, z, floor) : Integer.MIN_VALUE;
    }

    private static int walkDown(LevelReader level, BlockPos.MutableBlockPos m, int x, int y, int z, int floor) {
        for (int steps = 0; steps < MAX_WALK_DOWN && y > floor; steps++, y--) {
            m.set(x, y, z);
            BlockState s = level.getBlockState(m);
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty()) continue;   // water or lava sitting on the ground
            if (isVegetation(s) || isTreePart(s)) continue;
            return y;                                     // first genuine ground block
        }
        return Integer.MIN_VALUE;
    }

    /** A column this far below the ground on every side of it is a hole into a cave, not the ground. */
    private static final int PIT_DEPTH = 6;
    private static final int PIT_REACH = 4;

    /**
     * The ground a mountain is built on, for each column of a chunk, worked out before any of it is written: the
     * natural ground {@code natural[lx * 16 + lz]}, unless the column is the bottom of a hole. A cell walled in by
     * solid neighbours somewhere above its floor (a hole a block wide, a pocket under a roof) is built from the wall's
     * top; one with ground {@link #PIT_DEPTH} higher within {@link #PIT_REACH} on every side (a wider hole open to
     * the sky) from the rim. A big cave breaks the surface in holes a few blocks wide; a volcano built from the
     * bottom of each stood on the cave floor as a pillar of rock, the surface's tree on top. Only this chunk is
     * read, and before it is touched: a neighbour already raised by the mountain would pass for a wall, and the
     * answer would depend on which chunk came first. The foot of a cliff has low ground on its open side and is
     * left alone; a hole across a chunk line is only half seen.
     */
    public static int[] buildGround(LevelReader level, int minX, int minZ, int[] natural) {
        int[] out = new int[256];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[] dxs = {1, -1, 0, 0}, dzs = {0, 0, 1, -1};
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int i = lx * 16 + lz, ground = natural[i];
                out[i] = ground;
                if (ground == Integer.MIN_VALUE) continue;
                int x = minX + lx, z = minZ + lz;
                // Walled in: the highest level over the floor where three of the four neighbours are solid.
                int top = ground;
                for (int d = 0; d < 4; d++) {
                    int nx = lx + dxs[d], nz = lz + dzs[d];
                    if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16 && natural[nx * 16 + nz] > top) top = natural[nx * 16 + nz];
                }
                boolean walled = false;
                for (int y = top; y > ground + 1 && !walled; y--) {
                    int solid = 0;
                    for (int d = 0; d < 4; d++) {
                        int nx = lx + dxs[d], nz = lz + dzs[d];
                        if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) continue;
                        if (isSolidGround(level.getBlockState(m.set(x + dxs[d], y, z + dzs[d])))) solid++;
                    }
                    if (solid >= 3) { out[i] = y; walled = true; }
                }
                if (walled) continue;
                // A wider hole: higher ground on every side within reach, as far as this chunk shows.
                int rim = Integer.MAX_VALUE, known = 0;
                boolean pit = true;
                for (int dx = -1; dx <= 1 && pit; dx++) {
                    for (int dz = -1; dz <= 1 && pit; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int high = Integer.MIN_VALUE;
                        for (int r = 1; r <= PIT_REACH; r++) {
                            int nx = lx + dx * r, nz = lz + dz * r;
                            if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) break;
                            if (natural[nx * 16 + nz] > high) high = natural[nx * 16 + nz];
                        }
                        if (high == Integer.MIN_VALUE) continue;   // this side lies in the next chunk
                        known++;
                        if (high - ground < PIT_DEPTH) pit = false;
                        else if (high < rim) rim = high;
                    }
                }
                if (pit && known >= 5) out[i] = rim - 1;
            }
        }
        return out;
    }

    private static boolean isSolidGround(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && !isVegetation(s) && !isTreePart(s);
    }

    /** True when the column carries standing fluid above its ground (a lake, sea or lava pool). */
    public static boolean hasFluidAbove(LevelReader level, int x, int z) {
        int g = groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return false;
        return !level.getBlockState(new BlockPos(x, g + 1, z)).getFluidState().isEmpty();
    }

    /**
     * Verdict on whether a patch of ground can host a feature that must not leak.
     *
     * @param ok      true when the site passed every check
     * @param groundY the level the whole patch sits at
     * @param reason  short explanation, for the inspection commands
     */
    public record Site(boolean ok, int groundY, String reason) {
        public static Site no(String reason) { return new Site(false, Integer.MIN_VALUE, reason); }
    }

    /**
     * Looks for ground that a recessed basin can be cut into without leaking.
     *
     * <p>Strict on purpose, so lava or water seated here has nowhere to go: one consistent level
     * (within {@code tolerance}), no standing fluid, clear of the sea.</p>
     *
     * @param radius    half-width of the patch that has to be level
     * @param tolerance how many blocks of height variation are tolerated across it
     */
    public static Site findLevelSite(LevelReader level, int x, int z, int radius, int tolerance) {
        int centre = groundY(level, x, z);
        if (centre == Integer.MIN_VALUE) return Site.no("no ground here");
        if (centre <= level.getMinBuildHeight() + 6) return Site.no("too close to bedrock");
        if (centre >= level.getMaxBuildHeight() - 8) return Site.no("too close to the build ceiling");

        int guard = radius + 1;   // also check a ring OUTSIDE the feature, so it cannot spill over
        int lo = centre, hi = centre;
        for (int dx = -guard; dx <= guard; dx++) {
            for (int dz = -guard; dz <= guard; dz++) {
                int g = groundY(level, x + dx, z + dz);
                if (g == Integer.MIN_VALUE) return Site.no("open air or void nearby");
                if (hasFluidAbove(level, x + dx, z + dz)) return Site.no("standing water or lava nearby");
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
                if (hi - lo > tolerance) return Site.no("ground is too uneven");
            }
        }
        return new Site(true, centre, "level, dry ground");
    }

    /**
     * Removes plant cover from a column so nothing is left to catch fire or float over a new basin.
     * Only ever clears {@link #isVegetation} blocks, so builds and trees are untouched. Written without
     * neighbour shape updates, which load edge chunks, so the top of a tall plant or cane is taken too.
     */
    public static void clearVegetation(net.minecraft.world.level.LevelAccessor level, int x, int groundY,
                                       int z, int height) {
        for (int dy = 1; dy <= height; dy++) {
            BlockPos p = new BlockPos(x, groundY + dy, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (!isVegetation(s)) return;   // hit something real: stop, do not tunnel upward
            level.setBlock(p, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            if (dy == height && level.getBlockState(p.above()).is(s.getBlock())) height++;
        }
    }

    /**
     * {@link #clearVegetation} for painting the ground under standing trees: stops at a leaf or a log as it stops at
     * a wall, so a tree at the edge of a sinter flat keeps its whole crown instead of losing the rows the paint
     * reached. Leaves count as vegetation elsewhere because a volcano's site clearing wants them gone.
     */
    public static void clearGroundCover(net.minecraft.world.level.LevelAccessor level, int x, int groundY,
                                        int z, int height) {
        for (int dy = 1; dy <= height; dy++) {
            BlockPos p = new BlockPos(x, groundY + dy, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (isTreePart(s) || !isVegetation(s)) return;
            level.setBlock(p, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            if (dy == height && level.getBlockState(p.above()).is(s.getBlock())) height++;
        }
    }
}
