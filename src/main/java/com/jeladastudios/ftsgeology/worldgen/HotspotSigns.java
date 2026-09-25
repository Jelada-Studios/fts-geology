package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;

import com.jeladastudios.ftsgeology.block.SteamVentBlock;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.SeedHash;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground telling you geothermal heat is under it, long before you see a geyser: fumarole fields
 * of chimneys, mud, sulfur staining and pale crust.
 *
 * <p>Plumes stay rare, since the default spacing is already denser than Earth's hotspots; this makes
 * them findable by reading the landscape instead.</p>
 *
 * <p>A field is seeded from the chunk it starts in. Every chunk it crosses walks it the same way and
 * paints only its own cells, so a field runs across chunk borders and comes out the same whichever
 * chunk is built first.</p>
 */
public final class HotspotSigns {

    private HotspotSigns() {}

    /** Below this share of plume strength the ground says nothing at all. */
    private static final double THRESHOLD = 0.12;

    /** Chance that a given chunk in the heart of a dome starts a field. */
    private static final double FIELD_CHANCE = 0.055;

    /** How many chunks away a field can start and still reach this one: 15 + 33 + 4 blocks. */
    private static final int REACH_CHUNKS = 3;

    private static final long FIELD_SALT = 0x5F0E3A1D7C2B9E41L;

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Paints this chunk's share of every fumarole field that reaches it. A field follows the crack
     * letting the steam up: it walks a bearing for twenty or thirty blocks and lays bands out from
     * that line, chimneys and mud on the trace, sulfur beside it, pale crust at the edges.
     */
    public static void generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel model = level.getLevel();
        // Not on or around a large volcano: its flanks are its own ground, and a chimney near the
        // summit ends up hanging over the crater once it is carved. A caldera's floor is the exception. The
        // volcano's edge is asked about cell by cell in paint; only a chunk well inside it is skipped whole.
        int mx = cp.getMiddleBlockX(), mz = cp.getMiddleBlockZ();
        if (com.jeladastudios.ftsgeology.volcano.VolcanoField.largeMargin(model, mx, mz) < -GeothermalBasin.VOLCANO_FADE - 16
                && !com.jeladastudios.ftsgeology.volcano.VolcanoField.onCalderaFloor(model, mx, mz)) return;
        long seed = level.getSeed();
        for (int ox = -REACH_CHUNKS; ox <= REACH_CHUNKS; ox++) {
            for (int oz = -REACH_CHUNKS; oz <= REACH_CHUNKS; oz++) {
                int sx = cp.x + ox, sz = cp.z + oz;
                long h = SeedHash.hash(seed, sx, sz, FIELD_SALT);
                double roll = SeedHash.rand01(h);
                // Rolled before the map is asked, since most chunks start nothing whatever the ground.
                if (roll > FIELD_CHANCE * 4.0) continue;

                int cx = (sx << 4) + 8, cz = (sz << 4) + 8;
                // Any geothermal ground counts: a plume, a spreading ridge or a subduction arc.
                double strength = Math.max(HotspotMap.sample(model, cx, cz).strength(),
                        boundaryHeat(model, cx, cz));
                if (strength <= THRESHOLD) continue;
                // Squared, so fields cluster towards the middle of a dome and thin out at the rim.
                double intensity = strength * strength;
                if (roll > FIELD_CHANCE * intensity * 4.0) continue;

                RandomSource rng = RandomSource.create(SeedHash.mix(h));
                int x = (sx << 4) + rng.nextInt(16);
                int z = (sz << 4) + rng.nextInt(16);
                field(level, cp, x, z, rng, h);
            }
        }
    }

    /**
     * How hard a plate boundary is venting here, on the plume-strength scale. Only rifts and
     * subduction arcs, the settings with shallow magma under them.
     */
    private static double boundaryHeat(ServerLevel level, int x, int z) {
        PlateSample plate = TectonicMap.sampleCached(level, x, z);
        return switch (plate.faultType()) {
            case DIVERGENT, CONVERGENT_SUBDUCTION -> plate.stress();
            default -> 0.0;
        };
    }

    /**
     * Which way a fracture trace runs: along the fault where a boundary's heat feeds the field, give or take
     * fifteen degrees; out from the plume over a hotspot, as its dykes run; round the ring fault on a caldera
     * floor; and anywhere at all where none of those holds.
     */
    private static double fieldBearing(ServerLevel model, int x, int z, RandomSource rng) {
        double jitter = (rng.nextDouble() - 0.5) * Math.PI / 6;
        var ring = com.jeladastudios.ftsgeology.volcano.VolcanoField.nearestLarge(model, x, z);
        if (ring != null && com.jeladastudios.ftsgeology.volcano.VolcanoField.onCalderaFloor(model, x, z)) {
            com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Fumarole field at {},{}: round the ring fault", x, z);
            return Math.atan2(z - ring.z(), x - ring.x()) + Math.PI / 2 + jitter;
        }
        double heat = boundaryHeat(model, x, z);
        double plume = HotspotMap.plumeStrength(model, x, z);
        if (heat > 0 && heat >= plume) {
            PlateSample plate = TectonicMap.sampleCached(model, x, z);
            com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Fumarole field at {},{}: along the {} strike, off by {} degrees",
                    x, z, plate.faultType(), Math.round(Math.toDegrees(jitter)));
            return Math.atan2(plate.faultStrikeZ(), plate.faultStrikeX()) + jitter;
        }
        double[] centre = HotspotMap.plumeCentre(model, x, z);
        if (centre != null) {
            com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Fumarole field at {},{}: radial from the plume", x, z);
            return Math.atan2(z - centre[1], x - centre[0]) + jitter;
        }
        return rng.nextDouble() * Math.PI * 2;
    }

    /** One fumarole field, a fracture trace with its alteration haloes, painted where it crosses this chunk. */
    private static void field(WorldGenLevel level, ChunkPos cp, int x, int z, RandomSource rng, long fieldHash) {
        double bearing = fieldBearing(level.getLevel(), x, z, rng);
        int length = 14 + rng.nextInt(20);
        int halfWidth = 2 + rng.nextInt(3);
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();

        for (int t = 0; t < length; t++) {
            // The trace wanders, the way a crack does.
            bearing += (rng.nextDouble() - 0.5) * 0.22;
            double dx = Math.cos(bearing), dz = Math.sin(bearing);
            int cx = x + (int) Math.round(dx * t);
            int cz = z + (int) Math.round(dz * t);

            for (int w = -halfWidth; w <= halfWidth; w++) {
                // Perpendicular to the trace.
                int px = cx + (int) Math.round(-dz * w);
                int pz = cz + (int) Math.round(dx * w);
                if (px < minX || px > minX + 15 || pz < minZ || pz > minZ + 15) continue;
                int band = Math.abs(w);

                // The cell's own dice, so the walk above rolls the same in every chunk it crosses.
                RandomSource cell = RandomSource.create(SeedHash.columnSeed(fieldHash, px, pz));
                // Thin out towards the edge of the band so it has no hard border.
                double keep = 1.0 - (band / (double) (halfWidth + 1));
                if (cell.nextDouble() > keep) continue;

                paint(level, px, pz, band, cell);
            }
        }
    }

    /**
     * One cell of a field, chosen by how far it is from the fracture trace.
     *
     * @param band 0 on the trace itself, rising outward
     */
    private static void paint(WorldGenLevel level, int x, int z, int band, RandomSource rng) {
        boolean bare = com.jeladastudios.ftsgeology.tectonics.ThermalBiomes.isCaldera(level.getLevel(), x, z);
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        if (g <= level.getSeaLevel()) return;                    // not on the sea floor
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;      // not into a lake

        BlockPos at = new BlockPos(x, g, z);
        BlockState here = level.getBlockState(at);
        if (here.is(Blocks.BEDROCK)) return;
        if (EruptionHandler.isPlayerPlaced(here)) return;
        if (volcanic(here)) return;                               // a volcano's own rock
        // A field runs out along a large volcano's circle, not along the chunk grid.
        if (rng.nextDouble() >= GeothermalBasin.volcanoClearance(level.getLevel(), x, z)) return;

        TerrainProbe.clearGroundCover(level, x, g, z, 1);

        if (band == 0) {
            // On the trace: where the steam comes out.
            if (rng.nextInt(5) == 0) {
                chimney(level, at, rng);
                return;
            }
            // Mud pots sit in mud. On their own they read as one block stamped over and over;
            // mixed with the vanilla article they read as a wet patch with pots in it.
            int roll = rng.nextInt(6);
            set(level, at, roll == 0 ? ModBlocks.MUD_POT.get().defaultBlockState()
                    : roll <= 2 && !bare ? Blocks.MUD.defaultBlockState()
                    : ModBlocks.SINTER_CRUST.get().defaultBlockState());
        } else if (band == 1) {
            // Beside it: sulfur condensing out of the vapour.
            set(level, at, rng.nextInt(3) == 0
                    ? ModBlocks.SINTER_CRUST.get().defaultBlockState()
                    : ModBlocks.NATIVE_SULFUR.get().defaultBlockState());
        } else {
            // Out at the edge: ground the runoff has bleached, dying into ordinary soil.
            // In a caldera there is no soil to die into: the altered ground runs out into gravel.
            set(level, at, rng.nextInt(4) == 0
                    ? (bare ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState()
                    : ModBlocks.SINTER_CRUST.get().defaultBlockState());
        }
    }

    /** Rock a volcano laid down, which geothermal paint leaves alone. */
    static boolean volcanic(BlockState s) {
        return s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BLACKSTONE)
                || s.is(Blocks.TUFF) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.OBSIDIAN)
                || s.is(ModBlocks.COOLING_LAVA_CRUST.get());
    }

    /** A two or three block chimney of the mineral its own steam has laid down. */
    public static void chimney(LevelAccessor level, BlockPos ground, RandomSource rng) {
        BlockState base = ModBlocks.STEAM_VENT.get().defaultBlockState()
                .setValue(SteamVentBlock.PART, SteamVentBlock.Part.BASE);
        BlockState neck = base.setValue(SteamVentBlock.PART, SteamVentBlock.Part.NECK);
        BlockState cap = base.setValue(SteamVentBlock.PART, SteamVentBlock.Part.CAP);

        boolean tall = rng.nextBoolean();
        // Room to stand up in? A chimney half buried in a hillside looks like a mistake.
        int need = tall ? 3 : 2;
        for (int i = 1; i <= need; i++) {
            if (!level.getBlockState(ground.above(i)).isAir()) return;
        }

        set(level, ground, base);
        if (tall) {
            set(level, ground.above(1), neck);
            set(level, ground.above(2), cap);
        } else {
            set(level, ground.above(1), cap);
        }
    }

    private static void set(LevelAccessor level, BlockPos at, BlockState state) {
        level.setBlock(at, TfcCompat.translate(level, at, state), FLAGS);
    }
}
