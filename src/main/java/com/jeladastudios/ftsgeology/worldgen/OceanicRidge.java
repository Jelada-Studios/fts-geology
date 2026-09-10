package com.jeladastudios.ftsgeology.worldgen;

import static com.jeladastudios.ftsgeology.util.ValueNoise.noise;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mid-ocean ridges: a divergent boundary under water.
 *
 * <ul>
 *   <li>a <b>swell</b> of hot, buoyant new crust, falling off with distance from the axis;</li>
 *   <li>an <b>axial valley</b> down the crest, where the crust is torn open;</li>
 *   <li><b>pillow lava</b> on the floor near the axis;</li>
 *   <li><b>black smokers</b> in the valley;</li>
 *   <li><b>sediment</b> thickening away from the axis, so a trench across it reads the crust's age.</li>
 * </ul>
 *
 * <p>Built on the sea floor only, never over a player block, and never breaking the surface.</p>
 */
public final class OceanicRidge {

    private OceanicRidge() {}

    /** How much sea floor a ridge may rebuild in one chunk. */
    private static final int BUDGET = 900;

    /** Half-width of the median rift, in blocks. */
    private static final int VALLEY_HALF = 6;

    /**
     * Builds whatever part of a ridge crosses this chunk. Cheap no-op anywhere that is not a
     * submerged spreading boundary.
     */
    public static void generate(WorldGenLevel level, ChunkPos cp, RandomSource rng) {
        if (!GeyserConfig.OCEANIC_RIDGE_ENABLED.get()) return;

        PlateSample centre = TectonicMap.sampleCached(level.getLevel(), cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
        if (centre.faultType() != FaultType.DIVERGENT) return;
        // Only where this piece of the boundary is under water; a rift on land is the earthquake
        // system's job.
        int centreFloor = TerrainProbe.groundY(level, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
        if (centreFloor == Integer.MIN_VALUE || centreFloor >= level.getSeaLevel() - 6) return;

        double reach = Math.min(GeyserConfig.FAULT_WIDTH.get(), 150.0);
        if (centre.faultDistance() > reach) return;

        int sea = level.getSeaLevel();
        int budget = BUDGET;

        for (int i = 0; i < 256 && budget > 0; i++) {
            // Same scattered order the deep structure uses, so a chunk that runs out of budget is
            // thinned everywhere rather than finished on one side and blank on the other.
            int k = (i * 97) & 0xFF;
            int x = cp.getMinBlockX() + (k >> 4);
            int z = cp.getMinBlockZ() + (k & 15);
            budget -= column(level, x, z, sea, reach, rng);
        }
    }

    /** Rebuilds one sea-floor column of the ridge. Returns how many blocks it wrote. */
    private static int column(WorldGenLevel level, int x, int z, int sea, double reach, RandomSource rng) {
        PlateSample s = TectonicMap.sampleCached(level.getLevel(), x, z);
        if (s.faultType() != FaultType.DIVERGENT) return 0;
        double d = s.faultDistance();
        if (d > reach) return 0;

        int floorY = TerrainProbe.groundY(level, x, z);
        if (floorY == Integer.MIN_VALUE) return 0;
        if (floorY >= sea - 6) return 0;                       // shallow water or dry land: not a ridge
        // There has to be open water standing over it; anything else is not sea floor.
        if (level.getBlockState(new BlockPos(x, floorY + 1, z)).getFluidState().isEmpty()) return 0;

        // The swell, on a smoothstep: a broad crest with the slope on the flanks, no terraces at the axis.
        double t = 1.0 - Mth.clamp(d / reach, 0.0, 1.0);
        double shape = t * t * (3.0 - 2.0 * t);
        // Noise breaks the contour lines, faded out at the margin so the ridge dies into the plain.
        double relief = (noise(x, z, 11.0) * 1.7 + noise(x, z, 29.0) * 1.1)
                * Mth.clamp(t * 3.0, 0.0, 1.0);
        // The swell only ever rises. Digging is the axial valley's job, and it is applied below.
        int crest = Math.max(0, (int) Math.round((9.0 + 11.0 * t) * shape + relief));

        // The median rift: the crest is split down the middle by the gap the plates are opening.
        if (d < VALLEY_HALF) {
            crest -= (int) Math.round(11.0 * (1.0 - d / VALLEY_HALF));
        }

        int target = floorY + crest;
        // Never let a ridge break the surface and become an accidental island.
        target = Math.min(target, sea - 4);

        // Sediment: none in the axial valley, thickening away from it as the crust ages.
        int sediment = Math.max(0, (int) Math.round(
                Math.pow(1.0 - t, 1.5) * 5.0 + noise(x, z, 17.0) * 1.4));

        int placed = 0;
        if (target > floorY) {
            for (int y = floorY + 1; y <= target; y++) {
                boolean covered = y > target - sediment;
                if (set(level, x, y, z, covered ? sedimentBlock(rng) : crustBlock(rng))) placed++;
            }
            // Pillow lava: the rounded lobes basalt freezes into when it erupts into cold water.
            // Only near the axis, where the sea floor is still bare rock.
            if (t > 0.55 && sediment == 0 && rng.nextInt(11) == 0) {
                placed += pillow(level, x, target + 1, z, sea, rng);
            }
        } else if (target < floorY) {
            for (int y = floorY; y > target; y--) {
                // Flooded, not hollowed: this is the sea floor, so what replaces rock is water.
                if (set(level, x, y, z, Blocks.WATER.defaultBlockState())) placed++;
            }
        }
        // A black smoker, only in the valley where the crust is actually parting.
        if (d < VALLEY_HALF && rng.nextInt(260) == 0) {
            placed += blackSmoker(level, x, target, z, sea, rng);
        }
        return Math.max(placed, 1);
    }

    /** One pillow: a low rounded lobe of chilled basalt, two blocks across so it reads as one. */
    private static int pillow(WorldGenLevel level, int x, int y, int z, int sea, RandomSource rng) {
        int r = 1 + rng.nextInt(2);
        int h = 1 + rng.nextInt(2);
        if (y + h >= sea - 2) return 0;                        // never break the surface
        int placed = 0;
        for (int dy = 0; dy < h; dy++) {
            int rr = r - dy;
            if (rr < 0) break;
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if (dx * dx + dz * dz > rr * rr + rr) continue;
                    BlockState b = (rng.nextInt(3) == 0 ? Blocks.BASALT : Blocks.SMOOTH_BASALT)
                            .defaultBlockState();
                    if (set(level, x + dx, y + dy, z + dz, b)) placed++;
                }
            }
        }
        return placed;
    }

    /** Fresh volcanic rock: what the ridge is actually made of. */
    private static BlockState crustBlock(RandomSource rng) {
        Block b = switch (rng.nextInt(6)) {
            case 0 -> Blocks.SMOOTH_BASALT;
            case 1 -> Blocks.BLACKSTONE;
            case 2 -> Blocks.TUFF;
            default -> Blocks.BASALT;
        };
        return b.defaultBlockState();
    }

    /** The blanket of sediment that settles on crust as it ages and drifts away from the axis. */
    private static BlockState sedimentBlock(RandomSource rng) {
        return (rng.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState();
    }

    /**
     * A hydrothermal chimney: a tube of precipitated mineral standing over the vent with a plume
     * rising out of its mouth, ringed with the sulfide crust that gives these things their name.
     *
     * <p>The heat source is sealed inside the tube, so the chimney glows and smokes without opening a
     * whirlpool in the water above it.</p>
     */
    private static int blackSmoker(WorldGenLevel level, int x, int baseY, int z, int sea, RandomSource rng) {
        int height = 3 + rng.nextInt(4);
        if (baseY + height >= sea - 3) return 0;
        int placed = 0;

        // The heat, buried under the vent.
        if (set(level, x, baseY, z, Blocks.MAGMA_BLOCK.defaultBlockState())) placed++;
        MagmaSealing.seal(level, new BlockPos(x, baseY, z), true);

        // The tube: a ring of dark mineral with an open throat.
        for (int h = 1; h <= height; h++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    // Taper it: the top is a narrow spire, not a box.
                    if (h > height - 2 && (dx != 0 && dz != 0)) continue;
                    Block b = rng.nextInt(3) == 0 ? Blocks.TUFF : Blocks.BLACKSTONE;
                    if (set(level, x + dx, baseY + h, z + dz, b.defaultBlockState())) placed++;
                }
            }
        }
        // The plume: soul sand's bubble column lifts rather than drags, so swimming in is no drowning.
        if (set(level, x, baseY + 1, z, Blocks.SOUL_SAND.defaultBlockState())) placed++;
        for (int h = 2; h <= height; h++) {
            if (set(level, x, baseY + h, z, Blocks.WATER.defaultBlockState())) placed++;
        }

        // Sulfide crust around the base, the undersea counterpart of a fumarole's sulfur.
        if (GeyserConfig.SULFUR_ENABLED.get()) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (rng.nextInt(4) != 0) continue;
                    if (set(level, x + dx, baseY, z + dz,
                            ModBlocks.NATIVE_SULFUR.get().defaultBlockState())) placed++;
                }
            }
        }
        return placed;
    }

    /** Writes one block, refusing bedrock and anything a player made. */
    private static boolean set(WorldGenLevel level, int x, int y, int z, BlockState state) {
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return false;
        if (!level.hasChunk(x >> 4, z >> 4)) return false;   // pillows and smokers reach over the edge
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        if (s == state) return false;   // already this: a write that changes nothing still costs one
        if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return false;
        level.setBlock(p, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);   // see DeepStructure.set
        return true;
    }
}
