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
 * The ground a geyser basin stands on, rather than the springs standing on grass.
 *
 * <h2>What this fixes</h2>
 * The springs themselves have been right for several rounds - the pools, the colour bands, the
 * sterile halo of dead crust and bleached trees around each one. But each of those haloes stops
 * about ten blocks out and ordinary meadow begins, so a geyser basin read as a handful of hot
 * springs dropped onto a field. Which is not what one looks like: at Norris or the Upper Geyser
 * Basin the sinter flat is <b>the floor of the whole basin</b>, pale and bare from one side to the
 * other, and the pools sit in it.
 *
 * <h2>Why this is not a hull around the springs</h2>
 * The obvious implementation is to cluster the spring cores in an area and floor the region they
 * enclose. Two things rule it out. The first is mechanical: inside a basin
 * {@code RetrogenHandler.placeHotSpringAt} builds exactly one pool rather than a terrace chain, so
 * the springs in a basin are single systems scattered through separate chunks, and clustering them
 * would need a persistent record of every core the generator has ever placed - new saved state, and
 * a new class of bug to go with it.
 *
 * <p>The second is that it would be modelling the wrong thing. The sinter flat is not a deposit
 * ringing each pool; it is what the basin floor is made of, laid down over the whole thing by water
 * that has been coming up through it for a very long time. So the floor is a function of <b>how
 * deep inside a basin a column is</b>, which the mod already knows how to answer, and the springs
 * are simply the places where the water is still reaching daylight.</p>
 *
 * <h2>The cost, counted through the call tree this time</h2>
 * {@link HotspotMap#basinStrength} looks like pure seed arithmetic and is not:
 *
 * <pre>
 * basinStrength -&gt; ThermalBiomes.strength -&gt; lookup -&gt; classify -&gt; getBaseHeight
 * </pre>
 *
 * and {@code getBaseHeight} runs the whole 3D noise router - the same call that froze a server for
 * thirteen minutes when river erosion asked for it once per candidate column. {@code lookup} caches
 * at quart (four block) resolution, which is exactly the kind of reassurance that has already failed
 * once: the erosion seeding scan stepped by four as well, and hit the cache almost never.
 *
 * <p>So basin strength is sampled at the <b>four corners of the chunk</b> - shared with the
 * neighbouring chunks, so amortised to about one new lookup each - and every column in between is
 * interpolated. That also settles, for free, the mistake {@code DeepStructure} made: sampling once
 * at a chunk centre and applying the answer to all 256 columns draws a chunk-aligned wall at every
 * threshold crossing. Interpolating gives a ramp instead.</p>
 */
public final class GeothermalBasin {

    private GeothermalBasin() {}

    /**
     * Below this share of plume strength there is no basin here at all.
     *
     * <p>The same number {@link HotspotSigns} uses, and for the same reason: the seed grid that
     * lays out basins does so everywhere in the world, so without a heat gate this would put a
     * sinter flat in the middle of a temperate forest with nothing under it.</p>
     */
    private static final double PLUME_THRESHOLD = 0.12;

    /** Where the floor starts appearing at all, as a fraction of basin depth. */
    private static final double FLOOR_MIN = 0.30;

    /** Where it becomes continuous. Between the two it thins out, so the edge is a fade. */
    private static final double FLOOR_FULL = 0.60;

    /**
     * The most a column may stand above or below its neighbour and still count as basin floor.
     *
     * <p>A basin floor is flat - that is most of what makes it read as one. Without this the paint
     * would climb the valley sides and turn a hillside white, which looks like a bug rather than
     * like geology.</p>
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
     * How deep inside a basin this column sits, 0 outside one.
     *
     * <p>This mirrors the first branch of {@link HotspotMap#basinStrength} deliberately rather than
     * simply calling it: a biome another mod has already painted as thermal ground <i>is</i> the
     * basin and needs no plume under it, but the mod's own seed grid does, and calling
     * {@code basinStrength} alone would floor a random cell of ordinary countryside every third
     * grid square.</p>
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
     * Geothermal ground along a plate boundary, as opposed to over a plume.
     *
     * <h2>Why a hotspot was not the only place that deserved this</h2>
     * A plume is the <i>rarest</i> way to get a geothermal field and it was the only one the mod
     * dressed. Iceland is a spreading ridge; Japan, the Andes and Kamchatka are subduction arcs; and
     * between them those settings hold most of the geothermal ground on Earth. A rift valley with
     * volcanoes and hot springs standing on ordinary meadow was the same mistake the basin floor was
     * written to fix, one setting over.
     *
     * <h2>Only the two that melt rock</h2>
     * {@code GeothermalSuitability} also scores collision and transform boundaries for hot springs,
     * and correctly - the Himalaya and the North Anatolian fault both have them, because a fault
     * conducts water whatever else it does. But a sinter flat is not made by warm water alone; it
     * needs a shallow heat engine driving it, and neither of those settings has one. So they get
     * springs, as they already did, and no basin floor.
     *
     * <h2>Cost</h2>
     * {@link com.jeladastudios.ftsgeology.tectonics.TectonicMap#sampleCached} is cached per quart
     * position, the same as the biome lookup above it, and this is called at four chunk corners
     * rather than per column - so it rides along with sampling that was happening anyway.
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
        // A spring's own work always wins. Its bed, its crust and above all its colour bands are the
        // thing this is meant to be a background for; repainting them would flatten the one part of
        // a basin that already looked right.
        if (HotSpringShape.isCrust(here) || isBasinFloor(here)) return false;

        // Patches, not a sprinkle. Scattering four materials per column at random reads as noise -
        // which is exactly what testing said about the first version of the fumarole fields. Two
        // slow noise fields give the floor areas instead: sinter flats, crusted ground between them,
        // and the odd wet hollow.
        double flat = OceanicRidge.noise(x, z, 34.0);
        double wet = OceanicRidge.noise(x + 4096, z - 4096, 19.0);

        TerrainProbe.clearVegetation(level, x, g, z, 2);

        if (over(wet, 0.52, 0.20, rng) && s > 0.45) {
            // A mud flat. Mud pots on their own read as one block stamped over and over; in vanilla
            // mud they read as pots in a wet patch, which is the same trick the fumarole fields use.
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
        Block b = rng.nextInt(3) == 0 ? ModBlocks.SINTER_CRUST.get() : RetrogenHandler.haloBlock(level);
        level.setBlock(at, b.defaultBlockState(), 2);
        // Bobby-socks trees: killed by the silica, left bleached and standing. Rare, or the basin
        // turns into a dead forest instead of an open flat.
        if (rng.nextInt(110) == 0) RetrogenHandler.deadTree(level, at);
        return true;
    }

    /**
     * Is this noise value past a threshold - decided with a die inside a band either side of it?
     *
     * <h2>Why the boundaries were knife-edged</h2>
     * The materials were chosen by testing smooth noise against a bare number, and a bare number on
     * smooth noise draws a smooth curve: the mud flat ended and the sinter flat began along a single
     * clean line, which testing quite rightly said looked cut rather than grown. Real ground does
     * not do that. A mud flat gives way to sinter through a stretch where there is some of each,
     * because both are still being laid down there.
     *
     * <p>So over the band the answer is probabilistic and slides from "almost never" to "almost
     * always". Two materials chosen this way interfinger over the width of the band instead of
     * meeting on an edge - which is both what the ground does and what was asked for.</p>
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
