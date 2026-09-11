package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.util.ValueNoise;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Ore where the geology that makes ore actually is.
 *
 * <p>Ore is not scattered through rock at random. Every deposit is the residue of one particular
 * process, and each process belongs to one tectonic setting, so the plate model already knows where
 * each kind should be:</p>
 * <ul>
 *   <li><b>Porphyry copper</b> under a subduction arc - chalcopyrite in a stockwork of veins around
 *       the plutons that feed the volcanoes, rotted near the surface by groundwater into green
 *       malachite and blue azurite.</li>
 *   <li><b>Orogenic gold</b> in a collision belt - ribbon quartz with gold and pyrite squeezed into
 *       the foliation of the root, and emerald or lapis where limestone was cooked.</li>
 *   <li><b>Massive sulfides</b> at a rift - lenses of pyrite and chalcopyrite left by hot water
 *       circulating through new crust.</li>
 *   <li><b>Fault gouge</b> along a transform - galena, pyrite and quartz cementing crushed rock.</li>
 *   <li><b>Hydrothermal veins</b> wherever a fault or a geothermal field moves hot water, zoned by
 *       depth, with cinnabar only in the shallow, cooler part.</li>
 *   <li><b>Coal and ironstone</b> in the quiet basins between the belts, as seams in shale.</li>
 * </ul>
 *
 * <h2>Written into one chunk only</h2>
 * Deposits stop at the chunk edge: a write into a neighbour hits a chunk another thread is building,
 * or loads it on the server thread. Underground, a vein ending at a border reads like any other.
 */
public final class OreGenesis {

    private OreGenesis() {}

    /** Mixed into the chunk seed so the ore dice are independent of every other system's. */
    private static final long SALT = 0x5851F42D4C957F2DL;

    /** Places this chunk's deposits and returns how many blocks it wrote. */
    public static int generate(WorldGenLevel level, ChunkPos cp) {
        if (!GeyserConfig.ORE_GENESIS_ENABLED.get()) return 0;
        ServerLevel world = level.getLevel();
        int cx = cp.getMinBlockX() + 8, cz = cp.getMinBlockZ() + 8;
        PlateSample centre = TectonicMap.sampleCached(world, cx, cz);
        Deposit d = new Deposit(level, cp);

        sedimentaryBasin(d, centre);

        if (centre.stress() >= 0.20) {
            switch (centre.faultType()) {
                case CONVERGENT_SUBDUCTION -> porphyryCopper(d, centre);
                case CONVERGENT_COLLISION -> {
                    orogenicGold(d);
                    quartzRidges(d, world);
                }
                case DIVERGENT -> massiveSulfides(d);
                case TRANSFORM -> {
                    faultGouge(d);
                    quartzRidges(d, world);
                }
                default -> {}
            }
        }

        // Away from the belts a geothermal field is a plume, so the plume grid is asked directly:
        // it is arithmetic, where GeothermalSuitability cost most of the ore pass.
        double plume = HotspotMap.plumeStrength(world, cx, cz);
        if (centre.stress() >= 0.35 || plume > 0.45) {
            hydrothermalVeins(d, centre, plume);
        }
        return d.placed;
    }

    /** One chunk's writing: its corner, its own dice, its surface read once, and a running count. */
    private static final class Deposit {
        private static final int UNREAD = Integer.MIN_VALUE + 1;

        final WorldGenLevel level;
        final int x0, z0;
        final RandomSource rng;
        final int[] ground = new int[256];
        int placed;

        Deposit(WorldGenLevel level, ChunkPos cp) {
            this.level = level;
            this.x0 = cp.getMinBlockX();
            this.z0 = cp.getMinBlockZ();
            this.rng = RandomSource.create(level.getSeed() ^ SALT ^ (((long) cp.x) << 32 | (cp.z & 0xFFFFFFFFL)));
            Arrays.fill(ground, UNREAD);
        }

        boolean inside(int x, int z) {
            return x - x0 >= 0 && x - x0 < 16 && z - z0 >= 0 && z - z0 < 16;
        }

        /** Ground height of a column in this chunk, read once; MIN_VALUE outside it or where there is none. */
        int ground(int x, int z) {
            if (!inside(x, z)) return Integer.MIN_VALUE;
            int i = ((x - x0) << 4) | (z - z0);
            if (ground[i] == UNREAD) ground[i] = TerrainProbe.groundY(level, x, z);
            return ground[i];
        }

        /** Replaces host rock with ore, inside this chunk and below {@code top}. */
        void set(int x, int y, int z, Block block, int top) {
            if (!inside(x, z) || y > top || y <= level.getMinBuildHeight()) return;
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.is(block) || s.hasBlockEntity() || !isHostRock(s)) return;
            level.setBlock(p, block.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }

        /** Replaces a column's surface with a mineral trace, where the surface is natural soil or rock. */
        void surface(int x, int z, Block block) {
            int g = ground(x, z);
            if (g == Integer.MIN_VALUE) return;
            BlockPos p = new BlockPos(x, g, z);
            BlockState s = level.getBlockState(p);
            if (!(s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL) || isHostRock(s))) return;
            if (!level.getBlockState(p.above()).getFluidState().isEmpty()) return;
            level.setBlock(p, block.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }

        /** Stands a block on a column's surface, as part of a low ridge, where there is open air for it. */
        void ridge(int x, int z, Block block) {
            int g = ground(x, z);
            if (g == Integer.MIN_VALUE) return;
            BlockPos p = new BlockPos(x, g + 1, z);
            BlockState below = level.getBlockState(p.below());
            if (!(below.is(BlockTags.DIRT) || isHostRock(below))) return;
            BlockState here = level.getBlockState(p);
            if (!here.isAir() && !TerrainProbe.isVegetation(here)) return;
            level.setBlock(p, block.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }
    }

    // === Basins ================================================================

    /**
     * Coal and ironstone, as seams in shale, in the quiet country between the active belts.
     *
     * <p>Presence and height are decided per column, so neighbouring columns differ by at most one
     * block, border or not.</p>
     */
    private static void sedimentaryBasin(Deposit d, PlateSample s) {
        if (s.stress() > 0.70) return;   // the heart of an active belt is not a basin

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = d.x0 + lx, z = d.z0 + lz;
                int ground = d.ground(x, z);
                if (ground == Integer.MIN_VALUE) continue;
                int top = ground - 4;

                // The upper seam: a buried delta swamp around Y=32, under a shale roof.
                if (ValueNoise.noise(x, z, 80.0) > -0.25) {
                    int y = (int) Math.round(32.0 + 8.0 * Math.sin(x * 0.0075) + 6.0 * Math.cos(z * 0.0069)
                            + 1.5 * Math.sin(x * 0.2) + 1.5 * Math.cos(z * 0.2));
                    if (y < ground - 5 && y > d.level.getMinBuildHeight() + 10) {
                        d.set(x, y + 1, z, ModBlocks.SHALE.get(), top);
                        d.set(x, y, z, Blocks.COAL_ORE, top);
                        d.set(x, y - 1, z, roll(x, y, z, 3) == 0 ? Blocks.COAL_ORE : ModBlocks.SHALE.get(), top);
                    }
                }

                // The deep seam: carbonaceous shale buried long enough to sit in deepslate country.
                if (ValueNoise.noise(x + 7919, z - 7919, 64.0) > 0.30) {
                    int y = (int) Math.round(-8.0 + 5.0 * Math.sin(x * 0.0056 + 1.2) + Math.sin(z * 0.13));
                    d.set(x, y, z, roll(x, y, z, 3) == 0 ? ModBlocks.SHALE.get() : Blocks.DEEPSLATE_COAL_ORE, top);
                }

                // Ironstone: a thin oolitic horizon around Y=18, with grains of ore scattered through it.
                if (ValueNoise.noise(x - 4099, z + 4099, 48.0) > 0.35 && roll(x, 18, z, 3) == 0) {
                    int y = (int) Math.round(18.0 + 3.0 * Math.sin(z * 0.011) + 2.0 * Math.cos(x * 0.009));
                    if (ground > y + 6) d.set(x, y, z, Blocks.IRON_ORE, top);
                }
            }
        }
    }

    /** A die for one position: the same whatever order anything else was rolled in. */
    private static int roll(int x, int y, int z, int sides) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xD1B54A32D192ED03L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) Math.floorMod(h, (long) sides);
    }

    // === Tectonic settings =====================================================

    /** Porphyry copper around an arc's plutons, and the oxidised cap over it. */
    private static void porphyryCopper(Deposit d, PlateSample s) {
        double across = Mth.clamp(s.faultDistance() / GeyserConfig.FAULT_WIDTH.get(), 0.0, 1.0);
        if (across > 0.65) return;   // around the arc root, not out on the fore-arc

        // The stockwork at depth: chalcopyrite, copper and quartz in a web of small veins.
        int clusters = 2 + d.rng.nextInt(3);
        for (int c = 0; c < clusters; c++) {
            int cx = d.x0 + 2 + d.rng.nextInt(12);
            int cz = d.z0 + 2 + d.rng.nextInt(12);
            int cy = d.level.getMinBuildHeight() + 15 + d.rng.nextInt(40);
            int length = 4 + d.rng.nextInt(6);
            int ground = d.ground(cx, cz);
            if (ground == Integer.MIN_VALUE || cy >= ground - 8) continue;
            for (int step = 0; step < length; step++) {
                int y = cy + step;
                int x = cx + roll(cx, y, cz, 3) - 1;
                int z = cz + roll(cz, y, cx, 3) - 1;
                Block b = switch (roll(x, y, z, 4)) {
                    case 1 -> ModBlocks.QUARTZ_VEIN.get();
                    case 2 -> y < 0 ? Blocks.DEEPSLATE_COPPER_ORE : Blocks.COPPER_ORE;
                    default -> ModBlocks.CHALCOPYRITE.get();
                };
                d.set(x, y, z, b, ground - 6);
            }
        }

        // The supergene cap: near the surface, oxygenated groundwater has rotted the sulfides into
        // copper carbonates - malachite green and azurite blue, in pockets under the soil.
        int pockets = 1 + d.rng.nextInt(3);
        for (int p = 0; p < pockets; p++) {
            int px = d.x0 + 2 + d.rng.nextInt(12);
            int pz = d.z0 + 2 + d.rng.nextInt(12);
            int depth = 12 + d.rng.nextInt(15);
            int ground = d.ground(px, pz);
            if (ground == Integer.MIN_VALUE || ground < 25) continue;
            int py = Mth.clamp(ground - depth, 6, ground - 5);
            // Over a shallow pocket the oxidised cap shows at the surface: the gossan a prospector
            // walks the hills looking for.
            if (ground - py <= 20) gossan(d, px, pz);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = px + dx, y = py + dy, z = pz + dz;
                        if (roll(x, y, z, 2) == 0) continue;
                        Block b = roll(x, y + 7, z, 2) == 0 ? ModBlocks.MALACHITE.get() : ModBlocks.AZURITE.get();
                        d.set(x, y, z, b, ground - 4);
                    }
                }
            }
        }
    }

    /** Rust-stained ground with flecks of malachite green and azurite blue over a copper pocket. */
    private static void gossan(Deposit d, int px, int pz) {
        for (int x = px - 2; x <= px + 2; x++) {
            for (int z = pz - 2; z <= pz + 2; z++) {
                int r = roll(x, 7919, z, 12);
                if (r >= 7) continue;
                d.surface(x, z, r == 0 ? ModBlocks.MALACHITE.get()
                        : r == 1 ? ModBlocks.AZURITE.get()
                        : r <= 3 ? Blocks.RED_TERRACOTTA : Blocks.COARSE_DIRT);
            }
        }
    }

    /**
     * Quartz veins weathered out as low ridges along the strike of a collision belt or a transform:
     * the hard vein stands proud of the softer rock around it. Decided per column from the fault's own
     * strike, a line every 37 blocks across it, broken into segments, so no ridge stops at a chunk border.
     */
    private static void quartzRidges(Deposit d, ServerLevel world) {
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = d.x0 + lx, z = d.z0 + lz;
                PlateSample p = TectonicMap.sampleCached(world, x, z);
                if (p.stress() < 0.35) continue;
                double len = Math.hypot(p.faultStrikeX(), p.faultStrikeZ());
                if (len < 1.0e-6) continue;
                double sx = p.faultStrikeX() / len, sz = p.faultStrikeZ() / len;
                long across = Math.round(-x * sz + z * sx);
                if (Math.floorMod(across, 37L) != 0) continue;
                long along = Math.round(x * sx + z * sz);
                if (ValueNoise.noise((int) along, (int) (across / 37) * 97, 18.0) < 0.35) continue;
                d.ridge(x, z, ModBlocks.QUARTZ_VEIN.get());
            }
        }
    }

    /** Orogenic gold in the foliation of a collision root, and skarn gems where limestone was cooked. */
    private static void orogenicGold(Deposit d) {
        int veins = 2 + d.rng.nextInt(3);
        for (int i = 0; i < veins; i++) {
            int x = d.x0 + d.rng.nextInt(16);
            int z = d.z0 + d.rng.nextInt(16);
            int baseRoll = d.rng.nextInt(1 << 20);
            int height = 5 + d.rng.nextInt(10);
            int ground = d.ground(x, z);
            if (ground == Integer.MIN_VALUE) continue;
            int base = d.level.getMinBuildHeight() + 10 + baseRoll % Math.max(1, ground - 25);
            for (int dy = 0; dy < height; dy++) {
                int y = base + dy;
                int r = roll(x, y, z, 5);
                Block b = r == 0 ? (y < 0 ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE)
                        : r == 1 ? ModBlocks.PYRITE.get()
                        : ModBlocks.QUARTZ_VEIN.get();
                d.set(x, y, z, b, ground - 5);
            }
        }

        if (d.rng.nextInt(2) == 0) {
            int x = d.x0 + d.rng.nextInt(16);
            int z = d.z0 + d.rng.nextInt(16);
            int yRoll = d.rng.nextInt(1 << 20);
            boolean emerald = d.rng.nextBoolean();
            boolean marbleCap = d.rng.nextBoolean();
            int ground = d.ground(x, z);
            if (ground > 15) {
                int y = 4 + yRoll % Math.max(1, ground - 20);
                d.set(x, y, z, emerald ? Blocks.EMERALD_ORE : Blocks.LAPIS_ORE, ground - 5);
                if (marbleCap) d.set(x, y + 1, z, ModBlocks.MARBLE.get(), ground - 5);
            }
        }
    }

    /** Volcanogenic massive sulfide lenses in the new crust of a rift. */
    private static void massiveSulfides(Deposit d) {
        int pods = 2 + d.rng.nextInt(3);
        for (int p = 0; p < pods; p++) {
            int px = d.x0 + d.rng.nextInt(16);
            int pz = d.z0 + d.rng.nextInt(16);
            int yRoll = d.rng.nextInt(1 << 20);
            int r = 1 + d.rng.nextInt(2);
            int ground = d.ground(px, pz);
            if (ground == Integer.MIN_VALUE) continue;
            int py = d.level.getMinBuildHeight() + 10 + yRoll % Math.max(1, ground - 20);
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = px + dx, y = py + dy, z = pz + dz;
                        if (dx * dx + dy * dy + dz * dz > r * r || roll(x, y, z, 3) == 0) continue;
                        Block b = roll(x, y + 11, z, 3) == 0 ? ModBlocks.CHALCOPYRITE.get() : ModBlocks.PYRITE.get();
                        d.set(x, y, z, b, ground - 5);
                    }
                }
            }
        }
    }

    /** Crushed rock along a transform fault, cemented by the fluids the fault carries. */
    private static void faultGouge(Deposit d) {
        for (int i = 0; i < 3; i++) {
            int x = d.x0 + d.rng.nextInt(16);
            int z = d.z0 + d.rng.nextInt(16);
            int baseRoll = d.rng.nextInt(1 << 20);
            int height = 6 + d.rng.nextInt(12);
            int ground = d.ground(x, z);
            if (ground == Integer.MIN_VALUE) continue;
            int base = d.level.getMinBuildHeight() + 8 + baseRoll % Math.max(1, ground - 20);
            for (int dy = 0; dy < height; dy++) {
                int y = base + dy;
                int r = roll(x, y, z, 12);
                if (r < 4) continue;   // a third of it stays plain crushed rock
                Block b = switch (r % 4) {
                    case 0 -> ModBlocks.GALENA.get();
                    case 1 -> ModBlocks.PYRITE.get();
                    case 2 -> ModBlocks.QUARTZ_VEIN.get();
                    default -> ModBlocks.SHALE.get();
                };
                d.set(x, y, z, b, ground - 4);
            }
        }
    }

    /**
     * Veins along the strike of a fault, or through a geothermal field: the fractures carry the hot
     * water, and the water drops its load as it cools on the way up.
     */
    private static void hydrothermalVeins(Deposit d, PlateSample s, double plume) {
        double sx = s.faultStrikeX(), sz = s.faultStrikeZ();
        if (Math.abs(sx) < 0.01 && Math.abs(sz) < 0.01) {
            sx = 1.0;
            sz = 0.0;
        }
        int bottom = d.level.getMinBuildHeight() + 12;
        int veins = 1 + (s.stress() > 0.6 ? 1 : 0) + (plume > 0.6 ? 1 : 0);
        for (int v = 0; v < veins; v++) {
            int startX = d.x0 + 2 + d.rng.nextInt(12);
            int startZ = d.z0 + 2 + d.rng.nextInt(12);
            int length = 8 + d.rng.nextInt(12);
            int start = d.ground(startX, startZ);
            if (start == Integer.MIN_VALUE) continue;
            int top = Math.min(start - 4, GeyserConfig.RETROGEN_MAX_Y.get() + 30);
            if (top <= bottom) continue;

            for (int step = 0; step < length; step++) {
                int x = startX + (int) Math.round(sx * step) - roll(startX, step, startZ, 2);
                int z = startZ + (int) Math.round(sz * step) - roll(startZ, step, startX, 2);
                int ground = d.ground(x, z);
                if (ground == Integer.MIN_VALUE) continue;   // walked out of the chunk
                // Now and then the vein's cinnabar weathers out at the surface above it.
                if (roll(x, 4243, z, 14) == 0) d.surface(x, z, ModBlocks.CINNABAR.get());
                int base = bottom + roll(x, step, z, Math.max(1, top - bottom));
                int span = 4 + roll(z, step, x, 8);
                for (int dy = 0; dy < span; dy++) {
                    int y = base + dy;
                    if (y >= ground - 3) break;
                    Block b;
                    // Zoned by depth: cinnabar only up in the shallow, cooler part of the system.
                    if (y > 10 && roll(x, y, z, 3) == 0) {
                        b = ModBlocks.CINNABAR.get();
                    } else {
                        b = switch (roll(x, y + 5, z, 6)) {
                            case 0 -> ModBlocks.PYRITE.get();
                            case 1 -> ModBlocks.GALENA.get();
                            case 2 -> y < 0 ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE;
                            case 3 -> ModBlocks.CHALCOPYRITE.get();
                            default -> ModBlocks.QUARTZ_VEIN.get();
                        };
                    }
                    d.set(x, y, z, b, ground - 3);
                }
            }
        }
    }

    /** Natural rock a deposit may replace, including ore that is already there. */
    private static boolean isHostRock(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(Blocks.CALCITE) || s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(ModBlocks.GABBRO.get()) || s.is(ModBlocks.PERIDOTITE.get())
                || s.is(ModBlocks.SERPENTINITE.get()) || s.is(ModBlocks.SCHIST.get())
                || s.is(ModBlocks.GNEISS.get()) || s.is(ModBlocks.SLATE.get())
                || s.is(ModBlocks.MARBLE.get()) || s.is(ModBlocks.QUARTZITE.get())
                || s.is(ModBlocks.SHALE.get()) || s.is(ModBlocks.CHERT.get())
                || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES);
    }
}
