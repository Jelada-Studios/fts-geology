package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * The caves a sunk river runs in ({@link com.jeladastudios.ftsgeology.hydrology.Karst}).
 *
 * <p>Where the network has a river run underground, a passage is cut along its course under the dry valley, a few
 * blocks under the valley's bed, with the river's water running along its floor. Where the river sinks, the swallow
 * hole: a shaft from the valley floor down into the cave, the river's water falling down it. Where it comes up, the
 * passage climbs to the valley floor and opens there, the river running on from its mouth. Everything is worked out
 * from the river's own course, so every chunk cuts its own share of a cave and the shares meet.</p>
 *
 * <p>Only rock is cut: nothing a player placed, no water or lava that was there before -- a cave that met the sea's
 * water or a lava lake would drain it or set the river boiling -- and never so near the surface that the valley floor
 * over it would stand a block thick.</p>
 */
public final class KarstCaves {

    private KarstCaves() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** How far under the valley's bed the cave's floor runs, and how tall and wide the passage is, at the normal layout. */
    private static final double DEPTH = 8.0, DEPTH_TALL = 2.0, HEIGHT = 4.0, WIDE_MIN = 1.6, WIDE_MAX = 3.2;
    /** Over how many blocks the passage climbs to the valley floor where the river comes up. */
    private static final double RAMP = 14.0;
    /** How wide the swallow hole's shaft is. */
    private static final double SHAFT = 1.8;
    /** Rock left standing over the passage, at least. */
    private static final int ROOF = 2;
    /** How far past a chunk the courses are gathered, so a cave's ends are told apart from its middle. */
    private static final int MARGIN = 64;

    private static final LongAdder COLUMNS = new LongAdder(), SHAFTS = new LongAdder(), MOUTHS = new LongAdder(),
            WATER = new LongAdder(), REFUSED = new LongAdder(), ENTRIES = new LongAdder();

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        if (TfcCompat.active() || !GeologyWorld.isOwn(level.getLevel()) || !RiverNetwork.ready()) return 0;
        if (!GeyserConfig.RIVERS.get() || !GeyserConfig.KARST.get()) return 0;
        int cx0 = cp.getMinBlockX(), cz0 = cp.getMinBlockZ(), cx1 = cx0 + 15, cz1 = cz0 + 15;
        List<RiverNetwork.Point> all = RiverNetwork.sunkNear(cx0 - MARGIN, cz0 - MARGIN, cx1 + MARGIN, cz1 + MARGIN);
        if (all.isEmpty()) return 0;
        double h = RiverNetwork.horizontal();
        double depth = DEPTH + DEPTH_TALL * (h - 1.0), height = HEIGHT + (h - 1.0) * 0.7;
        int placed = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (RiverNetwork.Point p : all) {
            boolean entry = true, exit = p.channel();
            // Told by the river's own course: a joining river is brought in to a point of it, and ends there, so its end
            // is no sign of water coming in from upstream underground.
            for (RiverNetwork.Point q : all) {
                if (q == p || !q.channel()) continue;
                if (same(q.ex(), q.ez(), p.x(), p.z())) entry = false;
                if (same(q.x(), q.z(), p.ex(), p.ez())) exit = false;
            }
            placed += passage(level, cp, at, p, depth, height, exit);
            if (entry) {
                ENTRIES.increment();
                placed += shaft(level, cp, at, p, depth);
            }
        }
        return placed;
    }

    /** The passage along one length, its water on the floor; climbing to the valley floor at its end if it comes up there. */
    private static int passage(WorldGenLevel level, ChunkPos cp, BlockPos.MutableBlockPos at, RiverNetwork.Point p,
                               double depth, double height, boolean exit) {
        double ax = p.ex() - p.x(), az = p.ez() - p.z();
        double len2 = ax * ax + az * az, len = Math.sqrt(len2);
        double wide = Math.max(WIDE_MIN, Math.min(WIDE_MAX, p.halfWidth() * 0.8 + 0.8)) * (1.0 + 0.25 * (RiverNetwork.horizontal() - 1.0));
        int x0 = Math.max(cp.getMinBlockX(), (int) Math.floor(Math.min(p.x(), p.ex()) - wide - 1));
        int x1 = Math.min(cp.getMinBlockX() + 15, (int) Math.ceil(Math.max(p.x(), p.ex()) + wide + 1));
        int z0 = Math.max(cp.getMinBlockZ(), (int) Math.floor(Math.min(p.z(), p.ez()) - wide - 1));
        int z1 = Math.min(cp.getMinBlockZ() + 15, (int) Math.ceil(Math.max(p.z(), p.ez()) + wide + 1));
        if (x0 > x1 || z0 > z1) return 0;
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState()
                .setValue(RiverWaterFluid.FLOW, RiverWaterFluid.wayOf(ax, az));
        int placed = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double t = len2 < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x + 0.5 - p.x()) * ax + (z + 0.5 - p.z()) * az) / len2));
                double dx = p.x() + ax * t - (x + 0.5), dz = p.z() + az * t - (z + 0.5);
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > wide) continue;
                double bed = p.bed() + (p.bedEnd() - p.bed()) * t;
                double floor = bed - depth;
                // Where the river comes up, the passage climbs over the last stretch to the valley floor and opens there.
                boolean ramp = false;
                if (exit) {
                    double fromEnd = (1.0 - t) * len;
                    if (fromEnd < RAMP) {
                        double k = 1.0 - fromEnd / RAMP;
                        floor = floor + (bed - 1.0 - floor) * k * k * (3 - 2 * k);
                        ramp = true;
                    }
                }
                int fy = (int) Math.floor(floor);
                int top = fy + (int) Math.round(height * Math.sqrt(Math.max(0.0, 1.0 - (d / wide) * (d / wide))));
                int ground = TerrainProbe.groundY(level, x, z);
                if (ground == Integer.MIN_VALUE) continue;
                // A roof over it, except where it opens to the valley.
                if (!ramp) top = Math.min(top, ground - ROOF - 1);
                if (top <= fy) continue;
                if (!clear(level, at, x, fy + 1, top, z)) {
                    REFUSED.increment();
                    continue;
                }
                for (int y = fy + 1; y <= top; y++) {
                    // The river's own water stays: another length's stream, or the river running on from the mouth.
                    if (level.getBlockState(at.set(x, y, z)).is(ModBlocks.RIVER_WATER.get())) continue;
                    level.setBlock(at, Blocks.CAVE_AIR.defaultBlockState(), FLAGS);
                    placed++;
                }
                // The floor holds: a hollow the caves left under it is stopped with stone.
                if (!level.getBlockState(at.set(x, fy, z)).isSolidRender(level, at)) {
                    level.setBlock(at, Blocks.STONE.defaultBlockState(), FLAGS);
                }
                if (!ramp && d <= wide - 0.5) {
                    level.setBlock(at.set(x, fy + 1, z), water, FLAGS);
                    WATER.increment();
                }
                COLUMNS.increment();
            }
        }
        if (exit && placed > 0) MOUTHS.increment();
        return placed;
    }

    /** The swallow hole where a river sinks: a shaft from the valley floor into the cave, its water falling down it. */
    private static int shaft(WorldGenLevel level, ChunkPos cp, BlockPos.MutableBlockPos at, RiverNetwork.Point p, double depth) {
        double r = SHAFT * (1.0 + 0.3 * (RiverNetwork.horizontal() - 1.0));
        int x0 = Math.max(cp.getMinBlockX(), (int) Math.floor(p.x() - r)), x1 = Math.min(cp.getMinBlockX() + 15, (int) Math.ceil(p.x() + r));
        int z0 = Math.max(cp.getMinBlockZ(), (int) Math.floor(p.z() - r)), z1 = Math.min(cp.getMinBlockZ() + 15, (int) Math.ceil(p.z() + r));
        if (x0 > x1 || z0 > z1) return 0;
        int fy = (int) Math.floor(p.bed() - depth);
        // The river comes in at its own water and falls from there to the stream on the cave's floor.
        int wTop = (int) Math.floor(p.water());
        BlockState falling = ModBlocks.RIVER_WATER.get().defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
        int placed = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double dx = x + 0.5 - p.x(), dz = z + 0.5 - p.z();
                if (dx * dx + dz * dz > r * r) continue;
                int ground = TerrainProbe.groundY(level, x, z);
                if (ground == Integer.MIN_VALUE || ground <= fy + 1) continue;
                int top = Math.max(ground, wTop);
                if (!clear(level, at, x, fy + 2, top, z)) {
                    REFUSED.increment();
                    continue;
                }
                for (int y = fy + 2; y <= top; y++) {
                    level.setBlock(at.set(x, y, z), y <= wTop ? falling : Blocks.AIR.defaultBlockState(), FLAGS);
                    placed++;
                }
            }
        }
        if (placed > 0) SHAFTS.increment();
        return placed;
    }

    /** Whether every block from y0 to y1 here may be cut: rock or soil the world laid, no other fluid, nothing built. */
    private static boolean clear(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) {
            BlockState s = level.getBlockState(at.set(x, y, z));
            // The river's own water is no bar: the cave runs under it and the swallow hole takes it in.
            if (s.isAir() || s.is(ModBlocks.RIVER_WATER.get())) continue;
            if (!s.getFluidState().isEmpty() || s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s) || level.getBlockEntity(at) != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(float x1, float z1, float x2, float z2) {
        return Math.abs(x1 - x2) < 1e-3f && Math.abs(z1 - z2) < 1e-3f;
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "karst: %d cave columns, %d with water, %d swallow holes (%d entries met), %d springs, %d refused",
                COLUMNS.sum(), WATER.sum(), SHAFTS.sum(), ENTRIES.sum(), MOUTHS.sum(), REFUSED.sum());
    }
}
