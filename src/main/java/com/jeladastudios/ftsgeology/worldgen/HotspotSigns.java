package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground telling you a plume is under it, long before you can see a geyser.
 *
 * <h2>Why this exists rather than simply making hotspots commoner</h2>
 * A hotspot is the richest thing in the mod - a whole geyser basin - and on the default settings the
 * mean spacing between plumes is about twenty thousand blocks. That is a landmark you stumble on
 * once a world, and testing quite reasonably said it was too hard to find in survival.
 *
 * <p>The tempting fix is to make them common, and it is the wrong one. At twenty-five metres per
 * horizontal block that spacing is roughly five hundred kilometres, while Earth's forty-odd major
 * hotspots sit thousands of kilometres apart - the mod is already several times denser than the
 * planet it is modelling, and a mod used as a classroom simulation should not quietly stop being
 * true in order to be convenient.</p>
 *
 * <p>So the plume stays rare and becomes <b>legible</b> instead. Approaching one, the ground starts
 * to say so: sulfur staining, patches of pale crust, warm shallow water, the odd steaming vent -
 * sparse at the rim of the dome, unmistakable near the middle. Which is what a geothermal field
 * actually looks like from a distance, and it means finding one is a matter of reading the
 * landscape rather than walking twenty thousand blocks and getting lucky.</p>
 *
 * <p>It also gives the sulfur, crust, mud and vent blocks their first job in world generation.
 * Until now they existed only in the creative menu.</p>
 */
public final class HotspotSigns {

    private HotspotSigns() {}

    /** Below this share of plume strength the ground says nothing at all. */
    private static final double THRESHOLD = 0.12;

    /** Chance per chunk of a sign at the very centre of a dome, before strength scales it. */
    private static final double PEAK_CHANCE = 0.55;

    /** Most cells one chunk may be given, so a dome centre is dappled rather than paved. */
    private static final int MAX_PER_CHUNK = 5;

    /**
     * Scatters a few surface signs through this chunk, if it stands over a plume.
     *
     * <p>Cheap and silent off a hotspot: one map sample, then nothing. The density rises with the
     * square of strength so the outer half of a dome stays very sparse - a hint you notice only
     * once you are looking - and the middle reads clearly.</p>
     */
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng) {
        HotspotMap.Hotspot spot = HotspotMap.sample(
                level, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
        if (spot.strength() <= THRESHOLD) return;

        // Squared, so this fades out towards the rim instead of stopping at a contour line.
        double intensity = spot.strength() * spot.strength();
        int wanted = (int) Math.round(MAX_PER_CHUNK * intensity);
        if (rng.nextDouble() < PEAK_CHANCE * intensity) wanted++;
        if (wanted <= 0) return;

        for (int i = 0; i < wanted; i++) {
            int x = cp.getMinBlockX() + rng.nextInt(16);
            int z = cp.getMinBlockZ() + rng.nextInt(16);
            place(level, x, z, intensity, rng);
        }
    }

    private static void place(ServerLevel level, int x, int z, double intensity, RandomSource rng) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        if (g <= level.getSeaLevel()) return;                    // not on the sea floor
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;      // not into a lake

        BlockPos at = new BlockPos(x, g, z);
        BlockState here = level.getBlockState(at);
        if (here.is(Blocks.BEDROCK)) return;
        if (com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(here)) return;

        // What kind of sign, weighted so the dramatic ones stay near the middle.
        double roll = rng.nextDouble();
        if (roll < 0.34) {
            set(level, at, ModBlocks.SINTER_CRUST.get().defaultBlockState());
        } else if (roll < 0.60) {
            set(level, at, ModBlocks.NATIVE_SULFUR.get().defaultBlockState());
        } else if (roll < 0.78) {
            set(level, at, Blocks.COARSE_DIRT.defaultBlockState());
        } else if (roll < 0.92 && intensity > 0.35) {
            // A mud pot needs a properly hot patch, so it keeps to the inner dome.
            set(level, at, ModBlocks.MUD_POT.get().defaultBlockState());
        } else if (intensity > 0.55) {
            // And a steaming vent only where the plume is close underneath.
            set(level, at, ModBlocks.STEAM_VENT.get().defaultBlockState());
        } else {
            set(level, at, ModBlocks.SINTER_CRUST.get().defaultBlockState());
        }

        // Ground this poisoned does not hold plants.
        TerrainProbe.clearVegetation(level, x, g, z, 1);
    }

    private static void set(ServerLevel level, BlockPos at, BlockState state) {
        level.setBlock(at, state, 2);
    }
}
