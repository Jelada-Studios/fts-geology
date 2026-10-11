package com.jeladastudios.ftsgeology.worldgen;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise3D;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.Diagnostics;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * PROTOTYPE (round 154, branch ores-153): ore bodies big enough to be worth going after, that reach the ground and
 * show where it is bare rock.
 *
 * <p>The deposits {@link OreGenesis} lays are many and small and lie deep, and none of them is ever seen from the
 * surface. Here a few large ones are built from the ground down instead, the way a prospector meets them:</p>
 * <ul>
 *   <li><b>Porphyry copper</b> on an arc over a pluton: a stock tens of blocks across, chalcopyrite and copper ore
 *       through its core, a pyrite shell, quartz veinlets. Where the mountain has worn down into it the face is
 *       stained green and blue: the copper near the surface has turned to malachite and azurite.</li>
 *   <li><b>A gold-bearing quartz reef</b> in a collision belt: a white sheet one to three blocks thick, standing almost
 *       upright along the belt for a hundred blocks and more, gold in shoots along it. It crosses ridges and gullies,
 *       and shows wherever the slope is bare.</li>
 *   <li><b>Float</b>: pieces of the body in the beds of the streams near it, to follow up to the body.</li>
 * </ul>
 *
 * <p>A body only takes the place of natural rock, so under soil and grass it stays hidden and is seen only on cliffs,
 * crags and in gullies. Off by default ({@code oreOutcrops}); the mod's own world type only; new chunks only.</p>
 */
public final class OreOutcrops {

    private OreOutcrops() {}

    /** A grid cell holds at most one body; how far a body can reach from its anchor. */
    private static final int CELL = 320, REACH = 420;
    /** Prototype switch: a porphyry's weathered top as a rusty gossan (on), or only its copper stain (off). */
    private static final boolean GOSSAN = Boolean.parseBoolean(System.getProperty("fts.gossan", "true"));

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        if (!GeyserConfig.ORE_OUTCROPS.get()) return 0;
        ServerLevel world = level.getLevel();
        if (!GeologyWorld.isOwn(world)) return 0;
        Chunk c = new Chunk(level, cp);
        int minCx = Math.floorDiv(c.x0 - REACH, CELL), maxCx = Math.floorDiv(c.x0 + 15 + REACH, CELL);
        int minCz = Math.floorDiv(c.z0 - REACH, CELL), maxCz = Math.floorDiv(c.z0 + 15 + REACH, CELL);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                body(c, cx * CELL, cz * CELL, hash(c.seed, cx, cz, 0x0C2C0L));
            }
        }
        return c.placed;
    }

    private static double die(long h, int k) {
        return rand01(hash(h, k, 0, 0x5EEDL));
    }

    /** One body, if this cell holds one: what kind the rock under its anchor allows. */
    private static void body(Chunk c, int cellX, int cellZ, long h) {
        if (die(h, 0) >= 0.45) return;
        int ax = cellX + 40 + (int) (die(h, 1) * (CELL - 80)), az = cellZ + 40 + (int) (die(h, 2) * (CELL - 80));
        Lithology.Column col = Lithology.column(TerrainContext.seed(), TerrainContext.params(), ax, az);
        if (col == null) return;
        int g = OreGenesis.anchorGroundAt(c.world, ax, az);
        if (c.isAnchorChunk(ax, az)) {
            Diagnostics.info("Outcrop prototype: cell anchor {} {} setting {} pluton {} ground {}", ax, az, col.setting(),
                    Lithology.hasPluton(col), g);
        }
        if (g < c.level.getSeaLevel() + 12) return;          // a body in the hills and mountains, not under the sea
        if (col.setting() == Lithology.Setting.ARC && Lithology.hasPluton(col)) {
            porphyry(c, ax, g, az, h);
        } else if (col.setting() == Lithology.Setting.FOLD_BELT) {
            reef(c, ax, g, az, h);
        }
    }

    // === Porphyry copper =======================================================

    /** Three sets of thin quartz veinlets cutting the stock, one every {@code spacing} blocks. */
    private static boolean veinlet(double[][] planes, double dx, double dy, double dz, double spacing) {
        for (double[] n : planes) {
            double d = (dx * n[0] + dy * n[1] + dz * n[2]) / spacing;
            if (Math.abs(d - Math.floor(d) - 0.5) * spacing < 0.5) return true;
        }
        return false;
    }

    private static void porphyry(Chunk c, int ax, int g, int az, long h) {
        double radius = 22.0 + die(h, 3) * 14.0;
        double stretch = 1.5;
        // Set so the top of the stock stands above its anchor's ground: the land worn down into it.
        int cy = g - (int) Math.round(radius * stretch * 0.55);
        int lobes = (int) (h & 0x3FF), salt = (int) ((h >>> 20) & 0xFFFF);
        double[][] planes = new double[3][];
        for (int k = 0; k < 3; k++) {
            double a = die(h, 6 + k) * Math.PI, t = (die(h, 9 + k) - 0.5) * 1.2;
            planes[k] = new double[]{Math.cos(a) * Math.cos(t), Math.sin(t), Math.sin(a) * Math.cos(t)};
        }
        if (c.isAnchorChunk(ax, az)) {
            Diagnostics.info("Outcrop prototype: porphyry copper at {} {} {}, {} blocks across", ax, g, az, (int) (radius * 2));
        }
        int ext = (int) Math.ceil(radius * 1.35) + 1;
        for (int x = Math.max(c.x0, ax - ext); x <= Math.min(c.x0 + 15, ax + ext); x++) {
            for (int z = Math.max(c.z0, az - ext); z <= Math.min(c.z0 + 15, az + ext); z++) {
                double dx = x - ax, dz = z - az;
                // A lobed outline, not a circle.
                double a = Math.atan2(dz, dx);
                double rr = radius * (1.0 + 0.18 * Math.sin(3 * a + lobes) + 0.10 * noise(x + lobes, z, 18.0));
                double hd2 = dx * dx + dz * dz;
                if (hd2 > rr * rr * 1.1) continue;
                int top = c.ground(x, z);
                double yr = rr * stretch * 1.05;
                int y0 = Math.max(c.level.getMinBuildHeight() + 2, (int) Math.floor(cy - yr));
                int y1 = Math.min(top, (int) Math.ceil(cy + yr));
                for (int y = y0; y <= y1; y++) {
                    double dv = (y - cy) / stretch;
                    // Where it lies in the stock, with the zone boundaries wavy rather than shells.
                    double r = Math.sqrt(hd2 + dv * dv) / rr + 0.10 * noise3D(x + salt, y, z, 9.0, 9.0);
                    if (r > 1.0) continue;
                    boolean oxidised = top - y <= 5;
                    double grain = grain(c.seed, x, y, z, 0x91);
                    double pod = noise3D(x - salt, y, z + salt, 4.5, 4.5);
                    // The rock itself, altered in zones by the fluids: potassic (pink) at the core, bleached
                    // quartz-sericite round it, greenish propylitic rock outside.
                    Block b = r < 0.45 ? Blocks.GRANITE : r < 0.78 ? Blocks.CALCITE : Blocks.TUFF;
                    if (r < 0.8 && veinlet(planes, dx, y - cy, dz, r < 0.45 ? 5.0 : 8.0)) {
                        b = r < 0.45 && grain < 0.3 ? ModBlocks.CHALCOPYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
                    } else if (r < 0.45 && pod > 0.30) {
                        b = grain < 0.6 ? ModBlocks.CHALCOPYRITE.get() : y < 0 ? Blocks.DEEPSLATE_COPPER_ORE : Blocks.COPPER_ORE;
                    } else if (r < 0.78 && pod > 0.55) {
                        b = grain < 0.7 ? ModBlocks.PYRITE.get() : ModBlocks.CHALCOPYRITE.get();
                    } else if (pod > 0.78) {
                        b = ModBlocks.PYRITE.get();
                    }
                    // Near the surface the sulfides have weathered: the copper to green malachite and blue azurite, in
                    // the same pods, so the stain comes in patches.
                    if (oxidised && (b == ModBlocks.CHALCOPYRITE.get() || b == ModBlocks.PYRITE.get()
                            || b == Blocks.COPPER_ORE)) {
                        b = r < 0.6 ? (noise3D(x + 97, y, z - 97, 3.0, 3.0) > 0.0 ? ModBlocks.MALACHITE.get() : ModBlocks.AZURITE.get())
                                : Blocks.ORANGE_TERRACOTTA;     // pyrite out in the shell weathers to rust only
                    } else if (oxidised && GOSSAN && r < 0.9 && b != ModBlocks.QUARTZ_VEIN.get()) {
                        // The gossan: the rotted top of the body, iron oxides in yellows, oranges and browns, the colour a
                        // prospector sees from across the valley.
                        double n = noise3D(x - 41, y, z + 41, 5.0, 3.0);
                        b = n < -0.2 ? Blocks.BROWN_TERRACOTTA : n < 0.25 ? Blocks.ORANGE_TERRACOTTA : Blocks.YELLOW_TERRACOTTA;
                    }
                    c.set(x, y, z, b);
                }
            }
        }
        floatPieces(c, ax, g, az, FLOAT_REACH, false);
    }

    // === Gold-bearing quartz reef ==============================================

    private static void reef(Chunk c, int ax, int g, int az, long h) {
        PlateSample s = TectonicMap.sampleCached(c.world, ax, az);
        double sx = s.faultStrikeX(), sz = s.faultStrikeZ();
        if (Math.abs(sx) < 0.01 && Math.abs(sz) < 0.01) {
            double a = die(h, 3) * Math.PI;
            sx = Math.cos(a);
            sz = Math.sin(a);
        }
        double turn = (die(h, 4) - 0.5) * 0.5;
        double vx = sx * Math.cos(turn) - sz * Math.sin(turn), vz = sx * Math.sin(turn) + sz * Math.cos(turn);
        int half = 60 + (int) (die(h, 5) * 50);
        double thick = 2.0 + die(h, 6) * 1.6;
        double lean = (die(h, 7) - 0.5) * 0.6;                // dips steeply, 70-90 degrees
        int yTop = g + 70, yBottom = g - 80;
        int shoots = (int) (h & 0xFFF);
        if (c.isAnchorChunk(ax, az)) {
            Diagnostics.info("Outcrop prototype: gold quartz reef at {} {} {}, {} blocks long", ax, g, az, half * 2);
        }
        int ext = half + (int) Math.ceil(Math.abs(lean) * 80 + thick) + 6;
        for (int x = Math.max(c.x0, ax - ext); x <= Math.min(c.x0 + 15, ax + ext); x++) {
            for (int z = Math.max(c.z0, az - ext); z <= Math.min(c.z0 + 15, az + ext); z++) {
                double along = (x - ax) * vx + (z - az) * vz;
                if (Math.abs(along) > half) continue;
                double across = -(x - ax) * vz + (z - az) * vx;
                // It pinches out towards its ends, and swells and pinches along its length.
                double taper = Math.min(1.0, (half - Math.abs(along)) / 18.0);
                double t = thick * taper * (0.8 + 0.4 * (0.5 + 0.5 * noise((int) along + shoots, 0, 14.0)));
                if (t < 0.8) continue;
                double wave = 2.5 * Math.sin(along * 0.05 + shoots);
                int top = c.ground(x, z);
                for (int y = Math.max(yBottom, c.level.getMinBuildHeight() + 2); y <= Math.min(yTop, top); y++) {
                    double off = Math.abs(across - lean * (y - g) - wave);
                    if (off > t / 2 + 1.5) continue;
                    double grain = grain(c.seed, x, y, z, 0x60);
                    Block b;
                    if (off > t / 2) {
                        // The wall rock the fluids altered round the reef: pale carbonate, patchy.
                        if (grain > 0.55) continue;
                        b = Blocks.CALCITE;
                    } else {
                        // Gold in shoots plunging down the reef, and in them in clusters.
                        boolean shoot = noise((int) Math.round(along / 3) + shoots, (int) Math.round(y / 3.0), 6.0) > 0.35;
                        if (shoot && noise3D(x + shoots, y, z, 2.5, 2.5) > 0.05) b = y < 0 ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE;
                        else b = grain > 0.93 ? ModBlocks.PYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
                    }
                    c.set(x, y, z, b);
                }
            }
        }
        floatPieces(c, ax, g, az, FLOAT_REACH, true);
    }

    // === Float ================================================================

    /** How far down the streams pieces of a body are carried. */
    private static final double FLOAT_REACH = 400.0;

    /**
     * Pieces of the body in the beds of the streams below it: water runs off the body's slopes into them, so a bed lower
     * than the body and within a reach of it may hold a piece, the nearer the likelier. Following them uphill leads to it.
     */
    private static void floatPieces(Chunk c, int ax, int g, int az, double reach, boolean gold) {
        for (int x = c.x0; x <= c.x0 + 15; x++) {
            for (int z = c.z0; z <= c.z0 + 15; z++) {
                double d2 = (double) (x - ax) * (x - ax) + (double) (z - az) * (z - az);
                if (d2 > reach * reach) continue;
                int top = c.ground(x, z);
                if (top > g - 8) continue;                      // only below the body: downhill of it
                BlockPos bed = new BlockPos(x, top, z);
                BlockState s = c.level.getBlockState(bed);
                boolean wet = c.level.getFluidState(bed.above()).is(FluidTags.WATER);
                double chance;
                if (wet) {
                    if (!(s.is(Blocks.GRAVEL) || s.is(BlockTags.SAND) || s.is(Blocks.CLAY) || s.is(BlockTags.DIRT)
                            || s.is(Blocks.MUD) || OreGenesis.isHostRock(s))) continue;
                    chance = 0.12;
                } else {
                    // The bare bank of the stream, a gravel or sand bar or rock at the water's edge: pieces lie in the
                    // open there, and these are the ones a walker sees.
                    if (!(s.is(Blocks.GRAVEL) || s.is(BlockTags.SAND) || OreGenesis.isHostRock(s))) continue;
                    if (!c.besideWater(x, top, z)) continue;
                    chance = 0.10;
                }
                double near = 1.0 - Math.sqrt(d2) / reach;
                double roll = grain(c.seed, x, top, z, 0xF1);
                if (roll > chance * near * near) continue;
                double kind = grain(c.seed, x, top, z, 0xF2);
                Block b = gold ? (kind < 0.25 ? Blocks.GOLD_ORE : ModBlocks.QUARTZ_VEIN.get())
                        : (kind < 0.5 ? ModBlocks.MALACHITE.get() : kind < 0.8 ? Blocks.COPPER_ORE : ModBlocks.CHALCOPYRITE.get());
                c.level.setBlock(bed, TfcCompat.translate(c.level, bed, b.defaultBlockState()), Block.UPDATE_CLIENTS);
                c.placed++;
            }
        }
    }

    private static double grain(long seed, int x, int y, int z, int salt) {
        long k = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0x632BE59BD9B4E019L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ salt;
        return rand01(com.jeladastudios.ftsgeology.util.SeedHash.mix(k));
    }

    /** One chunk's writing. */
    private static final class Chunk {
        final WorldGenLevel level;
        final ServerLevel world;
        final long seed;
        final int x0, z0;
        final int[] ground = new int[256];
        int placed;

        Chunk(WorldGenLevel level, ChunkPos cp) {
            this.level = level;
            this.world = level.getLevel();
            this.seed = level.getSeed();
            this.x0 = cp.getMinBlockX();
            this.z0 = cp.getMinBlockZ();
            java.util.Arrays.fill(ground, Integer.MIN_VALUE);
        }

        boolean isAnchorChunk(int x, int z) {
            return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
        }

        /** The top of the ground in this chunk as it stands now (rock, soil or a stream's bed). */
        int ground(int x, int z) {
            int i = ((x - x0) << 4) | (z - z0);
            if (ground[i] == Integer.MIN_VALUE) ground[i] = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
            return ground[i];
        }

        /** Water in a neighbouring column of this chunk, its surface no more than a block above this ground. */
        boolean besideWater(int x, int top, int z) {
            int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] o : around) {
                int nx = x + o[0], nz = z + o[1];
                if (!isAnchorChunk(nx, nz)) continue;
                int n = ground(nx, nz);
                if (n > top || n < top - 3) continue;
                if (level.getFluidState(new BlockPos(nx, n + 1, nz)).is(FluidTags.WATER) && top - n <= 1 + 1) return true;
            }
            return false;
        }

        void set(int x, int y, int z, Block b) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.hasBlockEntity() || !OreGenesis.isHostRock(s)) return;
            level.setBlock(p, TfcCompat.translate(level, p, b.defaultBlockState()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }
    }
}
