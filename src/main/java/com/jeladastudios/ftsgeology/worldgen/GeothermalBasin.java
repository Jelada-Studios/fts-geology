package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.quake.QuakeQuiet;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.ThermalBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground a geyser basin stands on. The sinter flat is the floor of the whole basin, as at Norris
 * or the Upper Geyser Basin, and the pools sit in it.
 *
 * <p>The floor is a function of how deep inside a basin a column is, not a hull around spring
 * cores, which would need a saved record of every core placed.</p>
 *
 * <p>{@link HotspotMap#basinStrength} ends in {@code getBaseHeight}, so it is sampled at the four
 * chunk corners, shared with neighbouring chunks, and interpolated per column. That also gives a
 * ramp at the edge rather than a chunk-aligned wall.</p>
 */
public final class GeothermalBasin {

    private GeothermalBasin() {}

    /** Below this share of plume strength there is no basin; the same gate {@link HotspotSigns} uses. */
    private static final double PLUME_THRESHOLD = 0.12;

    /** Where the floor starts appearing at all, as a fraction of basin depth. */
    private static final double FLOOR_MIN = 0.30;

    /** Where it becomes continuous. Between the two it thins out, so the edge is a fade. */
    private static final double FLOOR_FULL = 0.60;

    /**
     * The most a column may differ from its neighbour and still count as floor, so the paint does
     * not climb valley sides.
     */
    private static final int MAX_STEP = 2;

    /** Paints this chunk's share of the basin floor, if it is standing in one. */
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng) {
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return;

        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        // Four corners, not 256 columns and not one centre. See the class note.
        double s00 = basin(level, x0, z0);
        double s10 = basin(level, x0 + 16, z0);
        double s01 = basin(level, x0, z0 + 16);
        double s11 = basin(level, x0 + 16, z0 + 16);
        if (Math.max(Math.max(s00, s10), Math.max(s01, s11)) <= FLOOR_MIN) return;

        // The ground of the whole chunk in one pass, so the flatness test below is free rather than
        // trebling the number of height queries.
        int[] ground = new int[256];
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                ground[dx * 16 + dz] = TerrainProbe.groundY(level, x0 + dx, z0 + dz);
            }
        }

        int painted = 0;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                double u = dx / 16.0, v = dz / 16.0;
                double s = Mth.lerp(v, Mth.lerp(u, s00, s10), Mth.lerp(u, s01, s11));
                if (s <= FLOOR_MIN) continue;
                // Thins out towards the rim instead of ending on a line, the way the sterile halo
                // around a single spring already does.
                double keep = (s - FLOOR_MIN) / (FLOOR_FULL - FLOOR_MIN);
                if (keep < 1.0 && rng.nextDouble() > keep) continue;
                if (!isFloor(ground, dx, dz)) continue;
                if (paint(level, x0 + dx, z0 + dz, ground[dx * 16 + dz], s, rng)) painted++;
            }
        }
        if (painted > 0) {
            GeysersMod.LOGGER.debug("Geothermal basin floor at {},{}: {} columns", x0, z0, painted);
        }
    }

    /**
     * How deep inside a basin this column sits, 0 outside one. Mirrors the first branch of
     * {@link HotspotMap#basinStrength}: a thermal biome is a basin by itself, but the seed grid needs
     * a plume under it.
     */
    private static double basin(ServerLevel level, int x, int z) {
        double p = ThermalBiomes.strength(level, x, z);
        if (p >= 0.8) return p;          // Terralith's Yellowstone and friends, free of charge

        double plume = HotspotMap.sample(level, x, z).strength() <= PLUME_THRESHOLD
                ? 0.0
                : HotspotMap.basinStrength(level, x, z);

        return Math.max(plume, boundary(level, x, z));
    }

    /**
     * Geothermal ground along a spreading ridge or subduction arc, as opposed to over a plume.
     * Collision and transform boundaries get hot springs but no basin floor, having no shallow heat
     * source. Sampled at the chunk corners through the quart-cached tectonic map.
     */
    private static double boundary(ServerLevel level, int x, int z) {
        com.jeladastudios.ftsgeology.tectonics.PlateSample plate =
                com.jeladastudios.ftsgeology.tectonics.TectonicMap.sampleCached(level, x, z);
        return switch (plate.faultType()) {
            // Stress already folds in distance to the fault, so the field fades out as the boundary
            // does rather than ending at a radius.
            case DIVERGENT, CONVERGENT_SUBDUCTION -> plate.stress();
            default -> 0.0;
        };
    }

    /** Flat enough to be floor rather than the bank above it. */
    private static boolean isFloor(int[] ground, int dx, int dz) {
        int here = ground[dx * 16 + dz];
        if (here == Integer.MIN_VALUE) return false;
        for (int i = 0; i < 4; i++) {
            int nx = dx + (i == 0 ? -1 : i == 1 ? 1 : 0);
            int nz = dz + (i == 2 ? -1 : i == 3 ? 1 : 0);
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;   // chunk edge: what we have
            int n = ground[nx * 16 + nz];
            if (n == Integer.MIN_VALUE) continue;
            if (Math.abs(n - here) > MAX_STEP) return false;
        }
        return true;
    }

    /**
     * One column of basin floor.
     *
     * @return true if anything was written
     */
    private static boolean paint(ServerLevel level, int x, int z, int g, double s, RandomSource rng) {
        if (QuakeQuiet.isQuiet(level, x, z)) return false;    // ground still moving
        if (g <= level.getSeaLevel()) return false;
        if (TerrainProbe.hasFluidAbove(level, x, z)) return false;   // a pool or a lake

        BlockPos at = new BlockPos(x, g, z);
        BlockState here = level.getBlockState(at);
        if (here.is(Blocks.BEDROCK)) return false;
        if (EruptionHandler.isPlayerPlaced(here)) return false;
        // A spring's own work always wins, its colour bands above all.
        if (HotSpringShape.isCrust(here) || isBasinFloor(here)) return false;

        // Patches, not a sprinkle: two slow noise fields give sinter flats, crusted ground and wet hollows.
        double flat = com.jeladastudios.ftsgeology.util.ValueNoise.noise(x, z, 34.0);
        double wet = com.jeladastudios.ftsgeology.util.ValueNoise.noise(x + 4096, z - 4096, 19.0);

        TerrainProbe.clearVegetation(level, x, g, z, 2);

        if (over(wet, 0.52, 0.20, rng) && s > 0.45) {
            // A mud flat: mud pots among vanilla mud, not one block stamped over and over.
            level.setBlock(at, rng.nextInt(7) == 0
                    ? ModBlocks.MUD_POT.get().defaultBlockState()
                    : Blocks.MUD.defaultBlockState(), 2);
            if (rng.nextInt(440) == 0) HotspotSigns.chimney(level, at, rng);
            return true;
        }

        if (over(flat, 0.12, 0.22, rng)) {
            // The sinter flat itself: the pale bare floor the basin is named for.
            level.setBlock(at, flatBlock(rng).defaultBlockState(), 2);
            if (s > 0.7 && rng.nextInt(800) == 0) HotspotSigns.chimney(level, at, rng);
            return true;
        }

        // Between the flats, ground the runoff has poisoned - the same palette the halo round a
        // single spring already uses, so the two meet without a seam.
        Block b = rng.nextInt(3) == 0 ? ModBlocks.SINTER_CRUST.get() : HotSpringSites.haloBlock(level);
        level.setBlock(at, b.defaultBlockState(), 2);
        // Bobby-socks trees: killed by the silica, left bleached and standing. Rare, or the basin
        // turns into a dead forest instead of an open flat.
        if (rng.nextInt(110) == 0) HotSpringSites.deadTree(level, at);
        return true;
    }

    /**
     * Is this noise value past a threshold, decided with a die inside a band either side of it? Two
     * materials chosen this way interfinger across the band instead of meeting on a line.
     *
     * @param band how far either side of the threshold the two materials mix
     */
    private static boolean over(double value, double threshold, double band, RandomSource rng) {
        double p = (value - (threshold - band)) / (2.0 * band);
        if (p <= 0.0) return false;
        if (p >= 1.0) return true;
        return rng.nextDouble() < p;
    }

    /** Carbonate and silica, in the proportions a long-lived flat lays them down. */
    private static Block flatBlock(RandomSource rng) {
        int r = rng.nextInt(10);
        if (r < 5) return ModBlocks.SINTER.get();
        if (r < 8) return ModBlocks.TRAVERTINE.get();
        return Blocks.CALCITE;
    }

    /** Anything this has already laid down, so a second pass cannot repaint its own work. */
    private static boolean isBasinFloor(BlockState s) {
        return s.is(ModBlocks.SINTER_CRUST.get())
                || s.is(ModBlocks.TRAVERTINE.get())
                || s.is(ModBlocks.MUD_POT.get())
                || s.is(ModBlocks.STEAM_VENT.get())
                || s.is(ModBlocks.NATIVE_SULFUR.get());
    }
}
