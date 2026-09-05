package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mid-ocean ridges: what a divergent boundary looks like when it happens under water.
 *
 * <h2>Why this exists</h2>
 * Most of the planet's volcanism is not on land. It happens along sixty-odd thousand kilometres of
 * spreading ridge on the sea floor, where two oceanic plates pull apart, the mantle rises to fill the
 * gap and melts from the drop in pressure alone. Until now the mod knew a boundary was there - the
 * plate map drew it, the suitability table scored it - but the sea floor above it was ordinary sand,
 * so the single largest volcanic system on Earth was the one thing you could not go and look at.
 *
 * <h2>What gets built</h2>
 * <ul>
 *   <li>A <b>swell</b>: the ridge itself, standing above the abyssal plain because the new crust is
 *       hot and buoyant. It subsides again as it ages and moves away, which is why the profile falls
 *       off with distance from the axis rather than ending at a cliff.</li>
 *   <li>An <b>axial valley</b> down the crest - the median rift, where the crust is literally being
 *       torn open. It is the signature that tells a ridge apart from any other undersea hill.</li>
 *   <li><b>Pillow lava</b> on the flanks: the rounded lobes basalt freezes into when it erupts
 *       underwater.</li>
 *   <li><b>Black smokers</b> in the valley: chimneys of precipitated mineral standing over the vent,
 *       trailing a plume. These are also where life on Earth may have started, which makes them
 *       worth walking to.</li>
 *   <li><b>Sediment</b> thickening away from the axis, because older sea floor has had longer to
 *       collect it. Digging across a ridge therefore reads the age of the crust.</li>
 * </ul>
 *
 * <p>Everything is built on the sea floor and never replaces a player block. Nothing is allowed to
 * break the surface, so a ridge never turns into an unexpected island.</p>
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
    public static void generate(ServerLevel level, ChunkPos cp, RandomSource rng) {
        if (!GeyserConfig.OCEANIC_RIDGE_ENABLED.get()) return;

        PlateSample centre = TectonicMap.sampleCached(level, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
        if (centre.faultType() != FaultType.DIVERGENT) return;
        // Continental rifting builds a valley on land (that is the earthquake system's job); only an
        // ocean basin gets a spreading ridge.
        // Decided from the LOCAL column, not from the plate as a whole. Two plates whose CENTRES sit
        // on land are both classed continental, so a boundary running between them under a sea got no
        // ridge at all - which is why none ever appeared. What matters is simply whether this piece of
        // the boundary is under water.
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
    private static int column(ServerLevel level, int x, int z, int sea, double reach, RandomSource rng) {
        PlateSample s = TectonicMap.sampleCached(level, x, z);
        if (s.faultType() != FaultType.DIVERGENT) return 0;
        double d = s.faultDistance();
        if (d > reach) return 0;

        int floorY = TerrainProbe.groundY(level, x, z);
        if (floorY == Integer.MIN_VALUE) return 0;
        if (floorY >= sea - 6) return 0;                       // shallow water or dry land: not a ridge
        // There has to be open water standing over it; anything else is not sea floor.
        if (level.getBlockState(new BlockPos(x, floorY + 1, z)).getFluidState().isEmpty()) return 0;

        // The swell. New crust is hot and rides high; it cools, contracts and sinks as it spreads,
        // so height falls away from the axis instead of stopping at an edge.
        //
        // Smoothstep rather than t*t, because smoothstep has ZERO gradient at the axis: the crest is
        // a broad plateau and the slope lives out on the flanks, which is the real profile of a
        // mid-ocean ridge. t*t fell fastest exactly at the crest, and since the result is rounded to
        // whole blocks that became a staircase of terraces every three blocks, every one of them a
        // perfect line parallel to the boundary. That was the corduroy on the sea floor.
        double t = 1.0 - Mth.clamp(d / reach, 0.0, 1.0);
        double shape = t * t * (3.0 - 2.0 * t);
        // And then break the contour lines outright. A smooth profile rounded to whole blocks always
        // produces perfect level sets; nothing on a real sea floor is a perfect level set.
        // Faded out at the far margin so the ridge dies into the abyssal plain instead of ending in
        // a scatter of pits three blocks deep.
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

        // The sediment blanket. None at all in the axial valley, where the crust is being made right
        // now, thickening quickly away from it because older sea floor has had longer to collect it.
        // Without this the ridge paved a two-hundred-block swathe of the sea bed in bare basalt; with
        // it, a trench cut across the ridge reads the age of the crust off the wall, which is the
        // observation that confirmed sea-floor spreading in the first place.
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

    /**
     * One pillow: a low rounded lobe of chilled basalt on the axial floor.
     *
     * <p>Basalt erupting into cold water does not spread in sheets. It squeezes out through a crack,
     * the outside freezes on contact and the inside keeps inflating, so the flow advances as a heap
     * of rounded lobes - the single most recognisable rock on the sea floor. One stray block reads as
     * a mistake; a lump two blocks across reads as a pillow, and a floor covered in them reads as a
     * spreading ridge.</p>
     */
    private static int pillow(ServerLevel level, int x, int y, int z, int sea, RandomSource rng) {
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

    /**
     * Cheap value noise in [-1, 1], deterministic from world coordinates alone.
     *
     * <p>Used only to ragged the edges of things whose underlying profile is smooth. A smooth profile
     * rounded to whole blocks produces perfect contour lines, and perfect contour lines are the one
     * thing that instantly reads as generated rather than grown.</p>
     */
    static double noise(int x, int z, double scale) {
        double fx = x / scale, fz = z / scale;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double ax = fx - x0, az = fz - z0;
        ax = ax * ax * (3.0 - 2.0 * ax);
        az = az * az * (3.0 - 2.0 * az);
        return Mth.lerp(az,
                Mth.lerp(ax, lattice(x0, z0), lattice(x0 + 1, z0)),
                Mth.lerp(ax, lattice(x0, z0 + 1), lattice(x0 + 1, z0 + 1)));
    }

    /** One lattice value in [-1, 1]. */
    static double lattice(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
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
    private static int blackSmoker(ServerLevel level, int x, int baseY, int z, int sea, RandomSource rng) {
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
        // The plume. Soul sand's rising bubble column is the closest thing the game has to the black
        // smoke that gives these vents their name, and unlike a magma column it lifts rather than
        // drags, so swimming into one is a discovery instead of a drowning.
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
    private static boolean set(ServerLevel level, int x, int y, int z, BlockState state) {
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return false;
        level.setBlock(p, state, 2);
        return true;
    }
}
