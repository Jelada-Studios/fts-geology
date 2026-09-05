package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Soil that shows what it weathered out of.
 *
 * <h2>Why the ground should not be the same brown everywhere</h2>
 * The mod puts named rock into the world - basalt at a hotspot, gabbro and peridotite at a spreading
 * ridge, marble and travertine where carbonate has been cooked - and then covers all of it with the
 * same grass, so the geology is only visible where it happens to be exposed. That is backwards.
 * Soil <b>is</b> the rotted top of the rock beneath it, and its colour is the most legible single
 * clue about what a place is made of:
 *
 * <ul>
 *   <li>over basalt and the other iron-rich rocks, iron oxidises and the ground goes red - laterite,
 *       and the reason tropical soils are the colour they are;</li>
 *   <li>over limestone and marble, a thin pale calcareous soil - rendzina;</li>
 *   <li>over granite and its relatives, an acid soil with its nutrients washed down out of reach -
 *       podzol, and the reason those uplands are heath rather than farmland.</li>
 * </ul>
 *
 * <p>Nothing here models chemistry. It is the visible half of that, on its own, deliberately.</p>
 *
 * <h2>Why RockTypes.classify is not used directly, though it exists</h2>
 * It would have painted the entire world. {@code classify} deliberately falls back to
 * {@link com.jeladastudios.ftsgeology.instrument.RockTypes.Rock#PLUTONIC} for plain vanilla stone -
 * an honest answer to the question it is asked, since the upper continental crust really is granitic
 * on average - but "granitic on average" is not the same as "this is granite", and treating it as
 * such would have turned every hillside in the world into podzol. So the table below lists only
 * <b>named</b> rock, and anything else is no opinion and no change. The feature is therefore
 * confined to ground where the mod has actually put geology: near plate boundaries, around
 * volcanoes, and where {@code DeepStructure} has brought boundary rock up to the surface.
 *
 * <h2>Cost</h2>
 * Bedrock is a regional property - a granite pluton is hundreds of blocks across - so it is probed
 * <b>four times per chunk</b> rather than once per column, and the expensive per-column pass runs
 * only when at least one of those four probes came back with an opinion. Over ordinary countryside
 * the whole thing is four probes and out.
 */
public final class SoilProfile {

    private SoilProfile() {}

    /** How deep under the surface to look for the parent rock before giving up. */
    private static final int PROBE_DEPTH = 12;

    private enum Soil { NONE, LATERITE, RENDZINA, PODZOL }

    /** Paints this chunk's soil, if the rock under it has anything to say. */
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng) {
        if (!GeyserConfig.SOIL_FROM_BEDROCK.get()) return;

        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        // Four probes, and the early exit that keeps this affordable everywhere else.
        Soil a = probe(level, x0 + 3, z0 + 3);
        Soil b = probe(level, x0 + 12, z0 + 3);
        Soil c = probe(level, x0 + 3, z0 + 12);
        Soil d = probe(level, x0 + 12, z0 + 12);
        if (a == Soil.NONE && b == Soil.NONE && c == Soil.NONE && d == Soil.NONE) return;

        Soil[] probes = { a, b, c, d };
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                Soil soil = pick(probes, dx, dz, rng);
                if (soil == Soil.NONE) continue;

                // Patches, not speckle. Two scales so the edges of a patch are ragged rather than
                // round, and a good half of the ground is left as ordinary soil - the point is that
                // the place reads red or pale, not that every block of it does.
                double n = OceanicRidge.noise(x0 + dx, z0 + dz, 21.0)
                        + 0.5 * OceanicRidge.noise(x0 + dx + 8192, z0 + dz - 8192, 7.0);
                // A ramp rather than a cut. A hard threshold on smooth noise draws a smooth CURVE,
                // which is a contour line - and a contour line around a patch of soil reads as
                // drawn on. Feathering it over a band lets the patch break up at its own edge.
                double keep = (n - 0.10) / 0.16;
                if (keep <= 0.0 || (keep < 1.0 && rng.nextDouble() > keep)) continue;

                paint(level, x0 + dx, z0 + dz, soil, rng);
            }
        }
    }

    /**
     * Which of the four probes this column follows.
     *
     * <h2>The seam this replaces was mine, and my own measurement could not see it</h2>
     * This used to be {@code dx < 8 ? (dz < 8 ? a : c) : (dz < 8 ? b : d)} - nearest probe, winner
     * takes the column - with a comment saying it avoided the chunk-aligned wall
     * {@code DeepStructure} once drew. It does avoid that one. It draws an <b>eight-block quadrant
     * wall</b> instead, and where two rock types meet under a chunk the soil changed along a
     * dead-straight line down the middle of it.
     *
     * <p>What makes that worth writing down is that the round's measurement passed. It counted how
     * often the noise mask's patch edges landed on a chunk boundary - 5.8% against 6.3% by chance,
     * genuinely clean - and never once looked at the boundary between two soil TYPES, which is the
     * line testing then photographed. Measuring the thing that was changed rather than the thing
     * beside it is now the third bug of this shape.</p>
     *
     * <p>So the choice is weighted by distance and settled with a die: right on top of a probe it
     * always wins, and half way between two of them it is a coin toss, so the change from one soil
     * to the other happens across a band of ground where both appear rather than along an edge.</p>
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
    private static Soil probe(ServerLevel level, int x, int z) {
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
    private static void paint(ServerLevel level, int x, int z, Soil soil, RandomSource rng) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;    // a lake bed is not a soil profile

        BlockPos at = new BlockPos(x, g, z);
        BlockState here = level.getBlockState(at);
        // Only actual soil is repainted. Sand, gravel, bare rock and everything the mod has already
        // laid down are left exactly as they are - this is a soil colour, not a resurfacing.
        if (!here.is(BlockTags.DIRT)) return;
        if (EruptionHandler.isPlayerPlaced(here)) return;

        level.setBlock(at, block(soil, rng).defaultBlockState(), 2);
    }

    private static Block block(Soil soil, RandomSource rng) {
        int r = rng.nextInt(10);
        return switch (soil) {
            // Red earth. It used to lead on plain terracotta with brown behind it, and testing could
            // not tell whether there was any red soil in the world at all - fairly, because neither
            // of those blocks is red, they are both a muted orange-brown. Iron oxide is RED, and
            // that is the entire point of the laterite entry, so red terracotta leads now and the
            // browner blocks are what breaks it up.
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
