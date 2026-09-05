package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Where the water runs, and how fast the rock under it gives way.
 *
 * <h2>The channel is the world generator's, not ours</h2>
 * The mod had no notion of a river at all, and the obvious move - deriving a channel network from
 * our own noise, the way {@link com.jeladastudios.ftsgeology.tectonics.TectonicMap} derives plates -
 * would have put a <b>second</b> river system into a world that already has one. In a world running
 * Terralith that contradiction would be visible from the air. The mod's standing rule is that it
 * never requires another mod and always defers to one that is present, and two competing river
 * networks is the loudest possible way to break it.
 *
 * <p>So a channel is found by reading the generator's own surface, through the same chunk-free call
 * {@link WaterTable} already uses. Whatever put the rivers there - vanilla, Terralith, anything -
 * this agrees with it by construction.</p>
 */
public final class RiverProfile {

    private RiverProfile() {}

    /** How far out the valley test looks for higher ground on either side. */
    private static final int VALLEY_REACH = 6;

    /** How much higher the banks must stand for this to count as a channel. */
    private static final int BANK_RISE = 2;

    /**
     * Is this column part of a river channel?
     *
     * <p>Asked of the ground as it is now rather than of a biome name, so it keeps working after an
     * earthquake has moved the ground and after we have cut into it ourselves. A channel is a column
     * with higher ground on both sides across at least one axis - which is what a valley floor is -
     * and near enough to the water table to actually carry water.</p>
     */
    public static boolean isChannel(ServerLevel level, int x, int z) {
        int here = surface(level, x, z);
        if (here == Integer.MIN_VALUE) return false;

        // Well above the water table is a dry ridge, not a river.
        int table = WaterTable.tableY(level, x, z);
        if (here > table + 3) return false;

        return flankedOn(level, x, z, here, 1, 0) || flankedOn(level, x, z, here, 0, 1);
    }

    /** Higher ground both ways along one axis: the cross section of a valley. */
    private static boolean flankedOn(ServerLevel level, int x, int z, int here, int dx, int dz) {
        int leftRise = 0, rightRise = 0;
        for (int d = 1; d <= VALLEY_REACH; d++) {
            int l = surface(level, x - dx * d, z - dz * d);
            int r = surface(level, x + dx * d, z + dz * d);
            if (l != Integer.MIN_VALUE) leftRise = Math.max(leftRise, l - here);
            if (r != Integer.MIN_VALUE) rightRise = Math.max(rightRise, r - here);
        }
        return leftRise >= BANK_RISE && rightRise >= BANK_RISE;
    }

    /**
     * Which way the water leaves this column: the steepest of the eight neighbours.
     *
     * <p>Note what this is <b>not</b> used for. The rejected stream-power model fed the slope into
     * an erosion rate, and on a voxel grid that degenerates twice over: a flat reach has slope zero
     * so the river cannot cut at all, and a single block step has slope one so it cuts explosively.
     * Here the slope only chooses a <i>direction</i>, and a direction is exactly the thing a
     * discrete grid can give honestly.</p>
     *
     * @return the neighbouring column water would run to, or null if this is a low point
     */
    public static BlockPos downstream(ServerLevel level, int x, int z) {
        int here = ground(level, x, z);
        if (here == Integer.MIN_VALUE) return null;

        BlockPos best = null;
        double steepest = 0.0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int h = ground(level, x + dx, z + dz);
                if (h == Integer.MIN_VALUE || h >= here) continue;
                // Divided by the distance, so a diagonal is not preferred merely for reaching
                // further. Skipping this is why generated drainage so often runs in 45 degree
                // staircases.
                double dist = (dx != 0 && dz != 0) ? Math.sqrt(2.0) : 1.0;
                double grade = (here - h) / dist;
                if (grade > steepest) {
                    steepest = grade;
                    best = new BlockPos(x + dx, h, z + dz);
                }
            }
        }
        return best;
    }

    /**
     * How readily the rock at this column is cut, from 0 (barely) to 1 (easily).
     *
     * <p>This is the whole reason a canyon and a flood plain do not need separate code. The same
     * retreat, run through hard rock, leaves a narrow slot; run through shale it opens out. The
     * landform is a consequence of the material, which is the mod's argument about everything
     * else too.</p>
     */
    public static double erodibility(ServerLevel level, int x, int y, int z) {
        BlockState s = level.getBlockState(new BlockPos(x, y, z));
        return switch (RockTypes.classify(s)) {
            case SEDIMENT, SOIL -> 1.0;         // gravel, sand, dirt: goes at once
            case SEDIMENTARY -> 0.7;            // shale, sandstone
            case BIOGENIC -> 0.6;               // the mod's own carbonate
            case METAMORPHIC -> 0.35;           // schist, gneiss, marble
            case VOLCANIC -> 0.3;               // basalt, rhyolite
            case PLUTONIC -> 0.2;               // granite, gabbro: a gorge, slowly
            default -> 0.5;
        };
    }

    /**
     * The generator's surface here, free of any loaded chunk. Same source as {@link WaterTable}.
     *
     * <p><b>This does not change when blocks do.</b> It is the shape the generator would make, so it
     * is right for asking "is there a valley here" over ground nobody has loaded - and quite wrong
     * for asking "how deep have we cut". Use {@link #ground} for anything that has to notice its own
     * work.</p>
     */
    public static int surface(ServerLevel level, int x, int z) {
        int h = level.getChunkSource().getGenerator().getBaseHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
        return h <= level.getMinBuildHeight() ? Integer.MIN_VALUE : h;
    }

    /**
     * The ground as it actually stands, for work that has to see what it has already done.
     *
     * <p>Kept separate from {@link #surface} deliberately. A retreat that measured its step against
     * the generator's height would cut a block, measure exactly the same step again, and cut for
     * ever - the generator's surface has no idea the block is gone. Every guarantee the retreat
     * makes about terminating rests on reading the real ground here.</p>
     */
    public static int ground(ServerLevel level, int x, int z) {
        return TerrainProbe.groundY(level, x, z);
    }

    /** Anything a channel should refuse to cut through, whoever put it there. */
    public static boolean protectedGround(BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                || com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s);
    }

    /** Convenience: the four horizontal neighbours, for spreading a cut sideways. */
    public static Direction[] sides() {
        return new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    }
}
