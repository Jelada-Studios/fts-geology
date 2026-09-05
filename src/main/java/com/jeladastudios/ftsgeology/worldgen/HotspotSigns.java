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

    /** Chance that a given chunk in the heart of a dome starts a field. */
    private static final double FIELD_CHANCE = 0.055;

    /**
     * Puts a fumarole field in this chunk, occasionally, if it stands over a plume.
     *
     * <h2>A line, not a sprinkle</h2>
     * The first version scattered single blocks at random through every chunk of a dome, and testing
     * called it right: it read as confetti. Real geothermal ground does not work that way - the vents
     * follow the crack that is letting the steam up, so a field is a <b>line</b> with the hottest
     * material along its middle and the cooler alteration spreading either side.
     *
     * <p>So these are rarer and far more deliberate. A field takes a bearing, walks it for twenty or
     * thirty blocks, and lays bands out from that line: chimneys and mud on the trace itself, sulfur
     * staining beside it, pale crust fading out at the edges. You come across one seldom, and when
     * you do it is unmistakably a thing rather than scenery noise.</p>
     */
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng) {
        HotspotMap.Hotspot spot = HotspotMap.sample(
                level, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
        if (spot.strength() <= THRESHOLD) return;

        // Squared, so fields cluster towards the middle of a dome and thin out at the rim.
        double intensity = spot.strength() * spot.strength();
        if (rng.nextDouble() > FIELD_CHANCE * intensity * 4.0) return;

        int x = cp.getMinBlockX() + rng.nextInt(16);
        int z = cp.getMinBlockZ() + rng.nextInt(16);
        field(level, x, z, intensity, rng);
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
            // On the trace: where the steam actually comes out.
            //
            // No intensity gate here any more. It used to need intensity over 0.45, which is the
            // inner 231 blocks of a 700-block dome - about a ninth of it by area - so every field
            // outside that was a fumarole field with no fumaroles in it. Testing found the mud and
            // the crust and no chimneys anywhere, which is exactly that: the bands were painting
            // and only this one line was refusing.
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
    private static void chimney(ServerLevel level, BlockPos ground, RandomSource rng) {
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
