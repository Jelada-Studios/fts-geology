package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.volcano.OceanEdifice;
import com.jeladastudios.ftsgeology.volcano.VolcanoField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seamounts: the small volcanoes that stud the ocean floor in their tens of thousands and never reach the
 * surface. Most grow near a spreading ridge and ride away from it on the new crust; a few that once stood in
 * shallow water carry a flat top the waves cut.
 *
 * <p>Each sits on a coarse grid seeded from the world, planned from its cell alone, so every chunk that asks
 * builds the same cone and writes only its own columns.</p>
 */
public final class SeamountField {

    private SeamountField() {}

    private static final int CELL = 320, MARGIN = 64;
    /** Widest a seamount reaches from its centre. */
    private static final int MAX_REACH = 80;
    /** Water a seamount keeps over its top, at the least. */
    private static final int CLEARANCE = 5;

    /** A planned seamount: its centre, the floor it stands on, its height and foot, and its summit. */
    record Mount(int x, int z, int floorY, int height, double radius, double topR, boolean crater, boolean old,
                 int noise) {}

    private static final Map<Long, Optional<Mount>> CACHE = new ConcurrentHashMap<>();

    /** Writes this chunk's part of every seamount that reaches it and returns how many blocks it laid. */
    public static int generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel world = level.getLevel();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int laid = 0;
        for (int cx = Math.floorDiv(x0 - MAX_REACH, CELL); cx <= Math.floorDiv(x0 + 15 + MAX_REACH, CELL); cx++) {
            for (int cz = Math.floorDiv(z0 - MAX_REACH, CELL); cz <= Math.floorDiv(z0 + 15 + MAX_REACH, CELL); cz++) {
                Mount m = mount(world, cx, cz);
                if (m == null) continue;
                long nx = Math.max(x0, Math.min(x0 + 15, m.x())) - m.x(), nz = Math.max(z0, Math.min(z0 + 15, m.z())) - m.z();
                double reach = m.radius() * 1.12 + 1;
                if (nx * nx + nz * nz > reach * reach) continue;
                RandomSource rng = RandomSource.create(0L);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int gx = x0 + lx, gz = z0 + lz;
                        rng.setSeed(SeedHash.columnSeed(m.noise() * 0x9E3779B97F4A7C15L, gx, gz));
                        laid += column(level, m, gx, gz, rng);
                    }
                }
            }
        }
        return laid;
    }

    /** The seamount of a grid cell, or null where the cell has none. */
    static Mount mount(ServerLevel level, int cx, int cz) {
        long seed = SeedHash.hash(level.getSeed(), cx, cz, 0x5EA3L);
        Optional<Mount> hit = CACHE.get(seed);
        if (hit != null) return hit.orElse(null);
        if (CACHE.size() > 20_000) CACHE.clear();
        Mount m = plan(level, cx, cz, seed);
        CACHE.put(seed, Optional.ofNullable(m));
        return m;
    }

    private static Mount plan(ServerLevel level, int cx, int cz, long seed) {
        int span = CELL - 2 * MARGIN;
        int x = cx * CELL + MARGIN + (int) (SeedHash.rand01(SeedHash.hash(seed, 1, 0, 0x11L)) * span);
        int z = cz * CELL + MARGIN + (int) (SeedHash.rand01(SeedHash.hash(seed, 2, 0, 0x12L)) * span);
        PlateSample s = TectonicMap.sampleCached(level, x, z);
        // Near a spreading ridge, where most of them are born, they are thicker on the ground and still sharp.
        boolean ridge = s.faultType() == FaultType.DIVERGENT;
        if (SeedHash.rand01(SeedHash.hash(seed, 3, 0, 0x13L)) >= (ridge ? 0.7 : 0.45)) return null;
        // Any open sea deep enough to hide one: a plate's crust type is read from a few biome probes and misses most.
        int depth = VolcanoField.oceanDepth(level, x, z);
        if (depth < 12) {
            com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Seamount cell at {},{}: {} of sea", x, z, depth);
            return null;
        }
        // An island's own flank is its business.
        if (VolcanoField.nearLarge(level, x, z, 48)) {
            com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Seamount cell at {},{}: on an island's flank", x, z);
            return null;
        }
        int height = Math.min(depth - CLEARANCE, 10 + (int) (SeedHash.rand01(SeedHash.hash(seed, 4, 0, 0x14L)) * 22));
        if (height < 8) return null;
        boolean old = !ridge && SeedHash.rand01(SeedHash.hash(seed, 5, 0, 0x15L)) < 0.25;
        double topR = old ? 6 + 8 * SeedHash.rand01(SeedHash.hash(seed, 6, 0, 0x16L)) : 0.0;
        boolean crater = !old && SeedHash.rand01(SeedHash.hash(seed, 7, 0, 0x17L)) < (ridge ? 0.5 : 0.3);
        double radius = Math.min(MAX_REACH / 1.12 - 1, topR + height * 1.9 + 4);
        int floorY = level.getSeaLevel() - depth - 1;
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Seamount at {},{}: floor {}, height {}, flat top {}, crater {}",
                x, z, floorY, height, old, crater);
        return new Mount(x, z, floorY, height, radius, topR, crater, old,
                (int) (SeedHash.hash(seed, 8, 0, 0x18L) & 0xFFFF));
    }

    /** One column of a seamount: pillow lava, bare on a young cone and under sediment on an old one. */
    static int column(WorldGenLevel level, Mount m, int gx, int gz, RandomSource rng) {
        double d = Math.hypot(gx - m.x(), gz - m.z());
        double rr = m.radius() * (1.0 + 0.1 * ValueNoise.noise(gx + m.noise(), gz - m.noise(), 17.0));
        if (d >= rr) return 0;
        double h;
        if (d <= m.topR()) {
            h = m.height() + 0.4 * ValueNoise.noise(gx - m.noise(), gz + m.noise(), 6.0);
        } else {
            double t = (d - m.topR()) / Math.max(1.0, rr - m.topR());
            h = m.height() * Math.pow(1.0 - t, 1.25) + ValueNoise.noise(gx - m.noise(), gz + m.noise(), 6.0);
        }
        if (m.crater() && d < 4.5) h -= 3.0 * (1.0 - d / 4.5);
        int sea = level.getSeaLevel();
        int target = Math.min(m.floorY() + (int) Math.round(h), sea - 1 - CLEARANCE);
        int bed = OceanEdifice.seabed(level, gx, gz);
        if (bed == Integer.MIN_VALUE || target <= bed || bed >= sea - 1) return 0;
        // Only under open water.
        if (level.getBlockState(new BlockPos(gx, bed + 1, gz)).getFluidState().isEmpty()) return 0;
        for (int y = bed + 1; y <= target; y++) {
            BlockPos p = new BlockPos(gx, y, gz);
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
            level.setBlock(p, y == target ? top(m, rng, d) : pillow(rng), Block.UPDATE_CLIENTS);
        }
        return target - bed;
    }

    private static BlockState pillow(RandomSource rng) {
        int roll = rng.nextInt(20);
        return (roll < 11 ? Blocks.BASALT : roll < 17 ? Blocks.SMOOTH_BASALT : roll < 19 ? Blocks.BLACKSTONE
                : Blocks.TUFF).defaultBlockState();
    }

    private static BlockState top(Mount m, RandomSource rng, double d) {
        int roll = rng.nextInt(10);
        if (m.old()) {
            // Sand and shell grit on a planed top; ooze on the flanks, with a basalt ledge now and then.
            if (d <= m.topR()) return (roll < 6 ? Blocks.SAND : roll < 9 ? Blocks.GRAVEL : Blocks.BASALT).defaultBlockState();
            return (roll < 4 ? Blocks.SAND : roll < 7 ? Blocks.CLAY : roll < 9 ? Blocks.GRAVEL : Blocks.BASALT).defaultBlockState();
        }
        return roll < 7 ? pillow(rng) : (roll < 9 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState();
    }
}
