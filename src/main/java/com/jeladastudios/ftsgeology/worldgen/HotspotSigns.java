package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.block.SteamVentBlock;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground telling you geothermal heat is under it, long before you see a geyser: fumarole fields
 * of chimneys, mud, sulfur staining and pale crust.
 *
 * <p>Plumes stay rare, since the default spacing is already denser than Earth's hotspots; this makes
 * them findable by reading the landscape instead.</p>
 */
public final class HotspotSigns {

    private HotspotSigns() {}

    /** Below this share of plume strength the ground says nothing at all. */
    private static final double THRESHOLD = 0.12;

    /** Chance that a given chunk in the heart of a dome starts a field. */
    private static final double FIELD_CHANCE = 0.055;

    /**
     * Puts a fumarole field in this chunk, occasionally, on geothermal ground. A field follows the
     * crack letting the steam up: it walks a bearing for twenty or thirty blocks and lays bands out
     * from that line, chimneys and mud on the trace, sulfur beside it, pale crust at the edges.
     */
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng) {
        int cx = cp.getMinBlockX() + 8, cz = cp.getMinBlockZ() + 8;

        // Any geothermal ground counts: a plume, a spreading ridge or a subduction arc.
        double strength = Math.max(
                HotspotMap.sample(level, cx, cz).strength(),
                boundaryHeat(level, cx, cz));
        if (strength <= THRESHOLD) return;

        // Squared, so fields cluster towards the middle of a dome and thin out at the rim.
        double intensity = strength * strength;
        if (rng.nextDouble() > FIELD_CHANCE * intensity * 4.0) return;

        int x = cp.getMinBlockX() + rng.nextInt(16);
        int z = cp.getMinBlockZ() + rng.nextInt(16);
        field(level, x, z, intensity, rng);
    }

    /**
     * How hard a plate boundary is venting here, on the plume-strength scale. Only rifts and
     * subduction arcs, the settings with shallow magma under them.
     */
    private static double boundaryHeat(ServerLevel level, int x, int z) {
        com.jeladastudios.ftsgeology.tectonics.PlateSample plate =
                com.jeladastudios.ftsgeology.tectonics.TectonicMap.sampleCached(level, x, z);
        return switch (plate.faultType()) {
            case DIVERGENT, CONVERGENT_SUBDUCTION -> plate.stress();
            default -> 0.0;
        };
    }

    /** One fumarole field: a fracture trace with its alteration haloes. */
    private static void field(ServerLevel level, int x, int z, double intensity, RandomSource rng) {
        double bearing = rng.nextDouble() * Math.PI * 2;
        double dx = Math.cos(bearing), dz = Math.sin(bearing);
        int length = 14 + rng.nextInt(20);
        int halfWidth = 2 + rng.nextInt(3);

        for (int t = 0; t < length; t++) {
            // The trace wanders, the way a crack does.
            bearing += (rng.nextDouble() - 0.5) * 0.22;
            dx = Math.cos(bearing);
            dz = Math.sin(bearing);
            int cx = x + (int) Math.round(dx * t);
            int cz = z + (int) Math.round(dz * t);

            for (int w = -halfWidth; w <= halfWidth; w++) {
                // Perpendicular to the trace.
                int px = cx + (int) Math.round(-dz * w);
                int pz = cz + (int) Math.round(dx * w);
                int band = Math.abs(w);

                // Thin out towards the edge of the band so it has no hard border.
                double keep = 1.0 - (band / (double) (halfWidth + 1));
                if (rng.nextDouble() > keep) continue;

                paint(level, px, pz, band, intensity, rng);
            }
        }
    }

    /**
     * One cell of a field, chosen by how far it is from the fracture trace.
     *
     * @param band 0 on the trace itself, rising outward
     */
    private static void paint(ServerLevel level, int x, int z, int band, double intensity,
                              RandomSource rng) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return;
        if (g <= level.getSeaLevel()) return;                    // not on the sea floor
        if (TerrainProbe.hasFluidAbove(level, x, z)) return;      // not into a lake

        BlockPos at = new BlockPos(x, g, z);
        BlockState here = level.getBlockState(at);
        if (here.is(Blocks.BEDROCK)) return;
        if (com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(here)) return;

        TerrainProbe.clearVegetation(level, x, g, z, 1);

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
                    : roll <= 2 ? Blocks.MUD.defaultBlockState()
                    : ModBlocks.SINTER_CRUST.get().defaultBlockState());
        } else if (band == 1) {
            // Beside it: sulfur condensing out of the vapour.
            set(level, at, rng.nextInt(3) == 0
                    ? ModBlocks.SINTER_CRUST.get().defaultBlockState()
                    : ModBlocks.NATIVE_SULFUR.get().defaultBlockState());
        } else {
            // Out at the edge: ground the runoff has bleached, dying into ordinary soil.
            set(level, at, rng.nextInt(4) == 0
                    ? Blocks.COARSE_DIRT.defaultBlockState()
                    : ModBlocks.SINTER_CRUST.get().defaultBlockState());
        }
    }

    /** A two or three block chimney of the mineral its own steam has laid down. */
    public static void chimney(ServerLevel level, BlockPos ground, RandomSource rng) {
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

    private static void set(ServerLevel level, BlockPos at, BlockState state) {
        level.setBlock(at, state, 2);
    }
}
