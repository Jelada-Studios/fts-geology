package com.jeladastudios.ftsgeology.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads the shape of the land before anything is placed on it.
 *
 * <h2>Why this exists</h2>
 * Every placement bug the mod has had traces back to one wrong assumption: that
 * {@code Heightmap.WORLD_SURFACE} gives you the ground. It does not - it gives the topmost block
 * that is not air, which on grassy or forested terrain is a flower, a fern or a tree canopy. Build
 * on that and hot springs land a block too high with their magma bed poking out of a cliff, vent
 * lava sits on top of the soil and runs into the forest, and earthquakes refuse to move anything
 * because a tuft of grass looks like somebody built it.
 *
 * <p>{@link #groundY} walks down past all of that to real soil or rock, and the site checks below
 * let a feature ask "is this ground actually flat and dry enough for me?" before it commits. That is
 * what lets lava be seated INTO the terrain rather than dumped on top of it, so it has nowhere to
 * flow rather than being fenced in after the fact.</p>
 */
public final class TerrainProbe {

    private TerrainProbe() {}

    /** How far down {@link #groundY} will walk before giving up (tall jungle trees plus slack). */
    private static final int MAX_WALK_DOWN = 64;

    /**
     * Ground cover that is part of the landscape rather than part of a build: grass, flowers, crops,
     * mushrooms, vines, snow layers. Safe to clear, and never to be mistaken for player work.
     *
     * <p>Leaves and logs are deliberately NOT included. They are skipped when looking for the ground
     * but still count as "might be a build", because a cabin is made of logs and the mod promises
     * never to break player blocks.</p>
     */
    public static boolean isVegetation(BlockState s) {
        if (s.isAir()) return false;
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

    /** Tree material: skipped when hunting for ground, but never treated as clearable. */
    private static boolean isTreePart(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.MANGROVE_ROOTS);
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
     * <p>The test is deliberately strict, because the whole point is that lava or water seated here
     * has physically nowhere to go: the patch must be at one consistent level (within
     * {@code tolerance}), free of standing fluid, and clear of the sea. A feature that cannot find
     * such a site should simply not be placed - one vent that sits properly beats five that set the
     * forest on fire.</p>
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
     * Only ever clears {@link #isVegetation} blocks, so builds and trees are untouched.
     */
    public static void clearVegetation(ServerLevel level, int x, int groundY, int z, int height) {
        for (int dy = 1; dy <= height; dy++) {
            BlockPos p = new BlockPos(x, groundY + dy, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (!isVegetation(s)) return;   // hit something real: stop, do not tunnel upward
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
    }
}
