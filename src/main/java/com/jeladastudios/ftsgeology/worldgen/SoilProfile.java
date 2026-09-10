package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Soil that shows what it weathered out of: red laterite over basalt and other iron-rich rock, thin
 * pale rendzina over limestone and marble, leached podzol over granite. Appearance only.
 *
 * <p>Only named rock counts. {@code RockTypes.classify} calls plain stone plutonic, which would turn
 * every hillside into podzol, so the feature stays where the mod has put geology.</p>
 *
 * <p>Bedrock is regional, so it is probed four times per chunk, and the per-column pass runs only
 * when a probe found named rock. Each column rolls its own dice.</p>
 */
public final class SoilProfile {

    private SoilProfile() {}

    /** How deep under the surface to look for the parent rock before giving up. */
    private static final int PROBE_DEPTH = 12;

    private static final long SALT = 0x7A43E1C95D2F6B08L;

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private enum Soil { NONE, LATERITE, RENDZINA, PODZOL }

    /** Paints this chunk's soil, if the rock under it has anything to say. */
    public static void generate(WorldGenLevel level, ChunkPos cp) {
        if (!GeyserConfig.SOIL_FROM_BEDROCK.get()) return;

        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        // Four probes, and the early exit that keeps this affordable everywhere else.
        Soil a = probe(level, x0 + 3, z0 + 3);
        Soil b = probe(level, x0 + 12, z0 + 3);
        Soil c = probe(level, x0 + 3, z0 + 12);
        Soil d = probe(level, x0 + 12, z0 + 12);
        if (a == Soil.NONE && b == Soil.NONE && c == Soil.NONE && d == Soil.NONE) return;

        long seed = level.getSeed() ^ SALT;
        Soil[] probes = { a, b, c, d };
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = x0 + dx, z = z0 + dz;
                RandomSource rng = RandomSource.create(SeedHash.columnSeed(seed, x, z));
                Soil soil = pick(probes, dx, dz, rng);
                if (soil == Soil.NONE) continue;

                // Patches on two scales, leaving about half the ground as ordinary soil.
                double n = ValueNoise.noise(x, z, 21.0) + 0.5 * ValueNoise.noise(x + 8192, z - 8192, 7.0);
                // A feathered ramp rather than a cut, so a patch breaks up at its edge.
                double keep = (n - 0.10) / 0.16;
                if (keep <= 0.0 || (keep < 1.0 && rng.nextDouble() > keep)) continue;

                paint(level, x, z, soil, rng);
            }
        }
    }

    /**
     * Which of the four probes this column follows: weighted by distance and settled with a die, so
     * two soils change over a band where both appear rather than along a line.
     */
    private static Soil pick(Soil[] probes, int dx, int dz, RandomSource rng) {
        // The four probe points, in the order they were taken.
        final int[] px = { 3, 12, 3, 12 };
        final int[] pz = { 3, 3, 12, 12 };

        double total = 0.0;
        double[] weight = new double[4];
        for (int i = 0; i < 4; i++) {
            double ddx = dx - px[i], ddz = dz - pz[i];
            // Inverse square of the distance, so influence falls away quickly enough that a probe
            // still dominates its own corner instead of the whole chunk turning into an average.
            weight[i] = 1.0 / (1.0 + (ddx * ddx + ddz * ddz) * 0.10);
            total += weight[i];
        }

        double roll = rng.nextDouble() * total;
        for (int i = 0; i < 4; i++) {
            roll -= weight[i];
            if (roll <= 0.0) return probes[i];
        }
        return probes[3];
    }

    /** What the rock under this column is, read through whatever soil is lying on it. */
    private static Soil probe(LevelReader level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return Soil.NONE;

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = g; y > g - PROBE_DEPTH && y > level.getMinBuildHeight(); y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (isCover(s)) continue;                  // still in the soil and loose stuff
            return parentRock(s);                      // the first real rock down there
        }
        return Soil.NONE;
    }

    /** Soil, sand and gravel: what sits ON the rock rather than being it. */
    private static boolean isCover(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.PODZOL)
                || s.is(Blocks.COARSE_DIRT) || s.is(BlockTags.TERRACOTTA)
                || s.is(BlockTags.SNOW) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)
                || TerrainProbe.isVegetation(s);
    }

    /**
     * The soil a given rock weathers into. Only named rock gets an answer; see the class note for
     * why plain stone deliberately does not.
     */
    private static Soil parentRock(BlockState s) {
        // Iron-rich: the iron oxidises and stains the ground red.
        if (s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BLACKSTONE)
                || s.is(Blocks.TUFF)
                || s.is(ModBlocks.GABBRO.get()) || s.is(ModBlocks.PERIDOTITE.get())
                || s.is(ModBlocks.SERPENTINITE.get())
                || s.is(ModBlocks.COOLING_LAVA_CRUST.get())) {
            return Soil.LATERITE;
        }
        // Carbonate: a thin, pale, alkaline soil that never gets deep.
        if (s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
                || s.is(ModBlocks.TRAVERTINE.get()) || s.is(ModBlocks.MARBLE.get())
                || s.is(ModBlocks.SINTER.get())) {
            return Soil.RENDZINA;
        }
        // Felsic and silica-rich: acid, leached, and poor.
        if (s.is(Blocks.GRANITE) || s.is(ModBlocks.RHYOLITE.get())
                || s.is(ModBlocks.QUARTZITE.get()) || s.is(ModBlocks.CHERT.get())) {
            return Soil.PODZOL;
        }
        return Soil.NONE;
    }

    /** One column of soil, if there is soil there to change. */
    private static void paint(WorldGenLevel level, int x, int z, Soil soil, RandomSource rng) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;    // a lake bed is not a soil profile

        BlockPos at = new BlockPos(x, g, z);
        BlockState here = level.getBlockState(at);
        // Only actual soil is repainted. Sand, gravel, bare rock and everything the mod has already
        // laid down are left exactly as they are - this is a soil colour, not a resurfacing.
        if (!here.is(BlockTags.DIRT)) return;
        if (EruptionHandler.isPlayerPlaced(here)) return;

        BlockState put = block(soil, rng).defaultBlockState();
        // Plants cannot stand on terracotta or calcite, and no shape update will knock them off.
        if (!put.is(BlockTags.DIRT)) TerrainProbe.clearVegetation(level, x, g, z, 2);
        level.setBlock(at, put, FLAGS);
    }

    private static Block block(Soil soil, RandomSource rng) {
        int r = rng.nextInt(10);
        return switch (soil) {
            // Red earth: red terracotta leads, the browner blocks break it up.
            case LATERITE -> r < 5 ? Blocks.RED_TERRACOTTA
                    : r < 7 ? Blocks.TERRACOTTA
                    : r < 9 ? Blocks.BROWN_TERRACOTTA
                    : Blocks.COARSE_DIRT;
            // Pale and thin, with the parent carbonate showing through where it is thinnest.
            case RENDZINA -> r < 5 ? Blocks.COARSE_DIRT
                    : r < 8 ? Blocks.CALCITE
                    : Blocks.WHITE_TERRACOTTA;
            // Leached: the grey-brown horizon of a podzol, over stony ground.
            case PODZOL -> r < 6 ? Blocks.PODZOL
                    : r < 8 ? Blocks.COARSE_DIRT
                    : Blocks.GRAVEL;
            default -> Blocks.DIRT;
        };
    }
}
