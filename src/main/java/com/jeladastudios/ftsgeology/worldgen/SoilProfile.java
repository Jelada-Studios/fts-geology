package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
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
 * every hillside into podzol, so plain stone paints nothing. The feature runs everywhere, at a third of its
 * density in the quiet country where the only named rock is the generator's own granite, diorite, calcite and
 * dripstone, and in full near an active boundary or over a plume.</p>
 *
 * <p>Every column reads its own parent rock, and where the feature runs at all comes from stress
 * interpolated between the chunk corners, so a soil patch never ends on a chunk border.</p>
 */
public final class SoilProfile {

    private SoilProfile() {}

    /** How deep under the surface to look for the parent rock before giving up. */
    private static final int PROBE_DEPTH = 12;

    /** Setting strength where soil starts to show, the deep structure's own floor, and where it is full. */
    private static final double GATE_MIN = 0.25, GATE_FULL = 0.40;

    private static final long SALT = 0x7A43E1C95D2F6B08L;

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private enum Soil { NONE, LATERITE, RENDZINA, PODZOL }

    /** Paints this chunk's soil where the rock under it has anything to say. */
    public static void generate(WorldGenLevel level, ChunkPos cp) {
        // TFC lays its own rock and soil; the mod does not lay a second geology under it.
        if (TfcCompat.active()) return;
        if (!GeyserConfig.SOIL_FROM_BEDROCK.get()) return;

        ServerLevel model = level.getLevel();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        // Four corners, shared with the neighbouring chunks, so the area this runs in has no edge.
        double s00 = setting(model, x0, z0);
        double s10 = setting(model, x0 + 16, z0);
        double s01 = setting(model, x0, z0 + 16);
        double s11 = setting(model, x0 + 16, z0 + 16);
        // A geothermal basin's floor is the basin's to paint: red earth between its sinter flats made a patchwork.
        double b00 = GeothermalBasin.basin(model, x0, z0);
        double b10 = GeothermalBasin.basin(model, x0 + 16, z0);
        double b01 = GeothermalBasin.basin(model, x0, z0 + 16);
        double b11 = GeothermalBasin.basin(model, x0 + 16, z0 + 16);
        long seed = level.getSeed() ^ SALT;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                double s = Mth.lerp(dz / 16.0, Mth.lerp(dx / 16.0, s00, s10), Mth.lerp(dx / 16.0, s01, s11));
                double b = Mth.lerp(dz / 16.0, Mth.lerp(dx / 16.0, b00, b10), Mth.lerp(dx / 16.0, b01, b11));
                if (b > GeothermalBasin.FLOOR_MIN) continue;
                int x = x0 + dx, z = z0 + dz;
                RandomSource rng = RandomSource.create(SeedHash.columnSeed(seed, x, z));
                // A third of the patches in quiet country, rising over the gate band to all of them. Decided a cell
                // of thirty-two blocks at a time, not a column at a time: thinned column by column, a patch was a
                // sprinkle of single blocks.
                double gate = Mth.clamp(QUIET_SHARE + (s - GATE_MIN) / (GATE_FULL - GATE_MIN), QUIET_SHARE, 1.0);
                if (gate < 1.0 && SeedHash.rand01(SeedHash.hash(seed ^ 0x5A7CL, x >> 5, z >> 5, 0)) > gate) continue;

                // Patches on two scales, leaving about half the ground as ordinary soil.
                double n = ValueNoise.noise(x, z, 21.0) + 0.5 * ValueNoise.noise(x + 8192, z - 8192, 7.0);
                // A short feathered ramp: a patch is solid inside and frays only at its very edge. The ramp used to
                // span most of the patch, and the patch came out as a sprinkle of single blocks.
                double keep = (n - 0.14) / 0.05;
                if (keep <= 0.0 || (keep < 1.0 && rng.nextDouble() > keep)) continue;

                paint(level, x, z, rng);
            }
        }
    }

    /** The share of the painting that runs in quiet country, away from any boundary or plume. */
    private static final double QUIET_SHARE = 0.35;

    /** How active the ground is here: boundary stress, or a plume's strength, whichever is more. */
    private static double setting(ServerLevel model, int x, int z) {
        PlateSample p = TectonicMap.sampleCached(model, x, z);
        double boundary = p.faultType() == FaultType.INTERIOR ? 0.0 : p.stress();
        return Math.max(boundary, HotspotMap.plumeStrength(model, x, z));
    }

    /** What the rock under this column is, read through whatever soil is lying on it. */
    private static Soil probe(LevelReader level, int x, int g, int z) {
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
        // Iron-rich: the iron oxidises and stains the ground red. Not tuff: ash weathers to a dark andosol, and as
        // laterite it painted every arc, where the rock is mostly ash, red.
        if (s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BLACKSTONE)
                || s.is(ModBlocks.GABBRO.get()) || s.is(ModBlocks.PERIDOTITE.get())
                || s.is(ModBlocks.SERPENTINITE.get())) {
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

    /** One column of soil, if there is soil there and named rock under it. */
    private static void paint(WorldGenLevel level, int x, int z, RandomSource rng) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;    // a lake bed is not a soil profile
        // Nor a caldera's floor, which is ash and altered rock; see ThermalBiomes.isCaldera.
        if (com.jeladastudios.ftsgeology.tectonics.ThermalBiomes.isCaldera(level.getLevel(), x, z)) return;

        BlockPos at = new BlockPos(x, g, z);
        BlockState here = level.getBlockState(at);
        // Only actual soil is repainted. Sand, gravel, bare rock and everything the mod has already
        // laid down are left exactly as they are - this is a soil colour, not a resurfacing.
        if (!here.is(BlockTags.DIRT)) return;
        if (EruptionHandler.isPlayerPlaced(here)) return;

        Soil soil = probe(level, x, g, z);
        if (soil == Soil.NONE) return;

        BlockState put = block(soil, x, z).defaultBlockState();
        // Plants cannot stand on terracotta or calcite, and no shape update will knock them off.
        if (!put.is(BlockTags.DIRT)) TerrainProbe.clearGroundCover(level, x, g, z, 2);
        level.setBlock(at, put, FLAGS);
    }

    private static Block block(Soil soil, int x, int z) {
        // A slow field picks the block, not a die a column at a time: the patch is a few blobs of colour a handful of
        // blocks across, not confetti.
        int r = Mth.clamp((int) Math.floor(5.0 + 5.0 * ValueNoise.noise(x + 4096, z + 4096, 5.0)), 0, 9);
        return switch (soil) {
            // Red earth: red terracotta leads, the browner blocks break it up.
            case LATERITE -> r < 5 ? Blocks.RED_TERRACOTTA
                    : r < 7 ? Blocks.TERRACOTTA
                    : r < 9 ? Blocks.BROWN_TERRACOTTA
                    : Blocks.COARSE_DIRT;
            // Pale and thin, with the parent carbonate showing through where it is thinnest.
            case RENDZINA -> r < 3 ? Blocks.COARSE_DIRT
                    : r < 7 ? Blocks.CALCITE
                    : Blocks.WHITE_TERRACOTTA;
            // Leached: the grey-brown horizon of a podzol, over stony ground.
            case PODZOL -> r < 6 ? Blocks.PODZOL
                    : r < 8 ? Blocks.COARSE_DIRT
                    : Blocks.GRAVEL;
            default -> Blocks.DIRT;
        };
    }
}
