package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.compat.DynamicTreesFelling;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What holds a slope up: the works people build against landslides, and the forest that grows on one. Each lowers the
 * chance that a slope's loose cover gives way, as it does on real hillsides:
 *
 * <ul>
 *   <li><b>A retaining wall</b> at the foot: built masonry or concrete a few blocks down the slope, standing as high as
 *   the ground it holds and set into the ground, not on it. A thin, low or unfounded wall holds less, and the slope
 *   behind it can come down over it, its top course with it.</li>
 *   <li><b>Anchors</b>: iron bars or chains driven down through the loose cover into the rock under it (soil nails and
 *   rock bolts).</li>
 *   <li><b>Drains</b>: a column of gravel or a pipe sunk into the slope lets the water out of it, and a soaked slope is
 *   what a downpour brings down; drains count for the slides in rain.</li>
 *   <li><b>Trees</b>: roots bind the soil, and a wooded slope slides less than a bare one.</li>
 * </ul>
 *
 * <p>A terrace is a stair of retaining walls, each step's riser a wall: its treads are flat, and its risers are built
 * columns, which never slide. All of it is read from the blocks; only player-placed blocks count as works.</p>
 */
public final class SlopeSupport {

    private SlopeSupport() {}

    /** How far down the slope a wall is looked for, and how thick it may be counted. */
    private static final int WALL_REACH = 6, WALL_THICK = 3;
    /** How far round a column anchors, drains and trees are looked for. */
    private static final int ANCHOR_REACH = 2, DRAIN_REACH = 3, TREE_REACH = 2;

    /** What a slope's supports come to: how much of the chance of sliding is left, and the wall at its foot if any. */
    public record Support(double left, Wall wall) {}

    /**
     * A wall at the foot: where its face is, its top, how thick and how sound, and how much of the column's height over
     * the foot it reaches up to (1 for a wall as high as the ground it holds).
     */
    public record Wall(BlockPos face, int top, int thickness, double soundness, double reach) {
        /** The share of the slide this wall stops for the column it was found for. */
        public double held() {
            return 0.9 * soundness * reach;
        }
    }

    private static final Support NONE = new Support(1.0, null);

    /** How a slope column at {@code top} falling to {@code down} is held up; {@code wet} for a slide in rain. */
    public static Support at(ServerLevel level, int x, int top, int z, Direction down, boolean wet) {
        if (!GeyserConfig.SLOPE_SUPPORT.get()) return NONE;
        double left = 1.0;
        Wall wall = wall(level, x, top, z, down);
        if (wall != null) {
            // A sound wall as high as the ground behind it holds nearly all of it; a lower one only what it reaches.
            left *= 1.0 - wall.held();
        }
        int anchors = anchors(level, x, top, z);
        if (anchors > 0) left *= anchors >= 2 ? 0.3 : 0.5;
        if (wet && drained(level, x, top, z)) left *= 0.4;
        if (wooded(level, x, top, z)) left *= 0.6;
        return new Support(left, wall);
    }

    /**
     * What holds the slope at a column, in words: its fall, its loose cover, the wall at its foot, anchors, drains and
     * trees, and the share of the chance of sliding left in a quake and in rain.
     */
    public static String describe(ServerLevel level, int x, int z) {
        int top = TerrainProbe.groundY(level, x, z);
        if (top == Integer.MIN_VALUE) return "no ground here";
        Direction down = null;
        int drop = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int g = TerrainProbe.groundY(level, x + d.getStepX() * 3, z + d.getStepZ() * 3);
            if (g != Integer.MIN_VALUE && top - g > drop) {
                drop = top - g;
                down = d;
            }
        }
        if (down == null) return String.format(java.util.Locale.ROOT, "%d %d %d: flat, nothing to slide", x, top, z);
        Wall w = wall(level, x, top, z, down);
        Support quake = at(level, x, top, z, down, false), rain = at(level, x, top, z, down, true);
        return String.format(java.util.Locale.ROOT,
                "%d %d %d: falls %d over 3 to the %s (steep from 3); wall %s; anchors %d; drained %s; wooded %s; "
                        + "chance left in a quake %.2f, in rain %.2f",
                x, top, z, drop, down.getName(),
                w == null ? "none" : String.format(java.util.Locale.ROOT, "%d thick, top %d, sound %.2f, reach %.2f, holds %.2f",
                        w.thickness(), w.top(), w.soundness(), w.reach(), w.held()),
                anchors(level, x, top, z), drained(level, x, top, z), wooded(level, x, top, z), quake.left(), rain.left());
    }

    /** Whether a block was put there by a player. */
    private static boolean placed(ServerLevel level, BlockPos p) {
        return PlayerBuilt.contains(level, p);
    }

    /** Whether a placed block is one a retaining wall can be built of, and how well it holds: 0 for none. */
    private static double wallFabric(BlockState s) {
        return switch (Structural.fabric(s)) {
            case C, E, F -> 1.0;
            case B -> 0.9;
            case A -> 0.75;        // dry stone: the terraces of the Mediterranean and the Andes stand on it
            case D -> 0.5;         // timber cribbing
            default -> 0.0;
        };
    }

    /**
     * The retaining wall at the foot of a column, if one: the first built column down the slope within reach whose
     * blocks are wall fabric. Its soundness is the fabric's, less for a wall one block thick holding more than three of
     * height, for a wall standing on the ground rather than set into it, and for one lower than what it holds.
     */
    static Wall wall(ServerLevel level, int x, int top, int z, Direction down) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= WALL_REACH; i++) {
            int wx = x + down.getStepX() * i, wz = z + down.getStepZ() * i;
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(wx, top, wz))) return null;
            // The wall's top: the highest placed wall block in the column, from a little over the slope down.
            int wallTop = Integer.MIN_VALUE, wallBottom = Integer.MIN_VALUE;
            double fabric = 1.0;
            for (int y = top + 12; y >= top - 16; y--) {
                m.set(wx, y, wz);
                BlockState s = level.getBlockState(m);
                double f = s.isAir() ? 0.0 : wallFabric(s);
                if (f > 0.0 && placed(level, m)) {
                    if (wallTop == Integer.MIN_VALUE) wallTop = y;
                    wallBottom = y;
                    fabric = Math.min(fabric, f);
                } else if (wallTop != Integer.MIN_VALUE) {
                    break;
                }
            }
            if (wallTop == Integer.MIN_VALUE) continue;
            // How thick: built wall columns one after another down the slope, at the same height.
            int thick = 1;
            for (int j = 1; j < WALL_THICK; j++) {
                BlockPos n = new BlockPos(wx + down.getStepX() * j, wallTop - 1, wz + down.getStepZ() * j);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) break;
                BlockState s = level.getBlockState(n);
                if (s.isAir() || wallFabric(s) == 0.0 || !placed(level, n)) break;
                thick++;
            }
            // The ground at the wall's foot, down the slope past it; set into the ground if its base is under that.
            int fx = wx + down.getStepX() * thick, fz = wz + down.getStepZ() * thick;
            int footGround = com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(fx, wallBottom, fz))
                    ? TerrainProbe.groundY(level, fx, fz) : Integer.MIN_VALUE;
            if (footGround == Integer.MIN_VALUE || footGround > wallTop) footGround = wallBottom;
            boolean founded = wallBottom < footGround;
            int held = wallTop - footGround;   // the height of ground it holds back
            double sound = fabric;
            if (!founded) sound *= 0.6;
            if (thick == 1 && held > 3) sound *= 0.7;
            // How much of the column's rise over the foot the wall stands up to.
            double reach = Math.min(1.0, (held + 1) / (double) Math.max(1, top - footGround));
            return new Wall(new BlockPos(wx, wallTop, wz), wallTop, thick, Math.max(0.0, Math.min(1.0, sound)), reach);
        }
        return null;
    }

    /** How many anchors near a column reach through its loose cover into the rock under it. */
    private static int anchors(ServerLevel level, int x, int top, int z) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -ANCHOR_REACH; dx <= ANCHOR_REACH; dx++) {
            for (int dz = -ANCHOR_REACH; dz <= ANCHOR_REACH; dz++) {
                int ax = x + dx, az = z + dz;
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(ax, top, az))) continue;
                int g = TerrainProbe.groundY(level, ax, az);
                if (g == Integer.MIN_VALUE) continue;
                // Down from the surface: anchor all the way through the loose cover, its last block in rock. The head
                // may stand a block proud of the ground or be flush with it.
                int y = level.getBlockState(m.set(ax, g + 1, az)).isAir() ? g : g + 1, depth = 0;
                boolean anchored = false;
                for (; y >= g - 8; y--) {
                    m.set(ax, y, az);
                    BlockState s = level.getBlockState(m);
                    boolean rod = s.is(Blocks.IRON_BARS) || s.is(Blocks.CHAIN);
                    if (!rod) break;
                    if (!placed(level, m)) break;
                    depth++;
                }
                if (depth >= 3) {
                    BlockState under = level.getBlockState(m.set(ax, y, az));
                    BlockState last = level.getBlockState(m.set(ax, y + 1, az));
                    anchored = !under.isAir() && !Landslides.loose(under) && !(last.isAir());
                }
                if (anchored) n++;
            }
        }
        return n;
    }

    /** Whether a drain is sunk into the slope near a column: placed gravel or pipe, three or more deep. */
    private static boolean drained(ServerLevel level, int x, int top, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -DRAIN_REACH; dx <= DRAIN_REACH; dx++) {
            for (int dz = -DRAIN_REACH; dz <= DRAIN_REACH; dz++) {
                int ax = x + dx, az = z + dz;
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(ax, top, az))) continue;
                int g = TerrainProbe.groundY(level, ax, az);
                if (g == Integer.MIN_VALUE) continue;
                int depth = 0;
                for (int y = g; y >= g - 8; y--) {
                    m.set(ax, y, az);
                    BlockState s = level.getBlockState(m);
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                    boolean drain = s.is(Blocks.GRAVEL) || id.contains("pipe");
                    if (!drain || !placed(level, m)) break;
                    depth++;
                }
                if (depth >= 3) return true;
            }
        }
        return false;
    }

    /** Whether trees stand on or right beside a column: their roots bind the soil. */
    private static boolean wooded(ServerLevel level, int x, int top, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -TREE_REACH; dx <= TREE_REACH; dx++) {
            for (int dz = -TREE_REACH; dz <= TREE_REACH; dz++) {
                for (int dy = 1; dy <= 3; dy++) {
                    m.set(x + dx, top + dy, z + dz);
                    if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m)) continue;
                    BlockState s = level.getBlockState(m);
                    if ((s.is(BlockTags.LOGS) || DynamicTreesFelling.isBranch(s)) && !placed(level, m)) return true;
                }
            }
        }
        return false;
    }

    /**
     * A wall the slope behind it came down against: one that was not sound enough loses its top course, pushed over
     * down the slope. Returns how many blocks it lost.
     */
    static int overtop(ServerLevel level, Wall wall, Direction down, int width) {
        if (wall == null || wall.soundness() >= 0.7) return 0;
        int lost = 0;
        Direction side = down.getClockWise();
        for (int w = -width; w <= width; w++) {
            BlockPos p = wall.face().offset(side.getStepX() * w, 0, side.getStepZ() * w);
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (s.isAir() || wallFabric(s) == 0.0 || !placed(level, p)) continue;
            if (level.random.nextDouble() > 1.0 - wall.soundness()) continue;
            var f = com.jeladastudios.ftsgeology.util.Falls.fall(level, p, s, true);
            if (f != null) {
                f.setDeltaMovement(new net.minecraft.world.phys.Vec3(down.getStepX() * 0.3, 0.1, down.getStepZ() * 0.3));
                f.hurtMarked = true;
            }
            lost++;
        }
        return lost;
    }
}
