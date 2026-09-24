package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hides underground magma behind rock.
 *
 * <h2>Why</h2>
 * The mod buries magma in several places - a geyser heat bed, the magma chambers above a subducting
 * slab, the hot pockets under a rift. All of it is meant to be felt rather than seen: it is the heat
 * source, not scenery. But nothing checked whether it happened to be placed in the wall of a cave,
 * and a flat slab of magma blocks glowing out of a cavern reads as a bug rather than as geology.
 *
 * <p>So any magma this mod places underground gets a rock skin on whichever faces are open. The heat
 * still works - the geyser reads its neighbours, not the view - and a player who digs into it still
 * finds the chamber, which is the point.</p>
 *
 * <p>Takes any level, not just the live one, because deep structure is also written while a chunk
 * is still being generated.</p>
 */
public final class MagmaSealing {

    private MagmaSealing() {}

    /**
     * Wraps one magma cell in stone on every side that is open to air or fluid.
     *
     * @param sealTop whether the face pointing up should be covered too; false where something is
     *                deliberately meant to sit directly on the magma, like a spring floor
     */
    public static void seal(LevelAccessor level, BlockPos magma, boolean sealTop) {
        for (Direction d : Direction.values()) {
            if (!sealTop && d == Direction.UP) continue;
            BlockPos p = magma.relative(d);
            if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;   // never load a chunk for one face
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.BEDROCK) || s.is(Blocks.MAGMA_BLOCK)) continue;
            if (EruptionHandler.isPlayerPlaced(s)) continue;
            // Only fill what is actually open; solid rock already does the job.
            if (s.isAir() || !s.getFluidState().isEmpty() || TerrainProbe.isVegetation(s)) {
                level.setBlock(p, com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, p, skinFor(magma)), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    /** Seals a whole rectangular slab of magma - the shape a geyser heat bed takes. */
    public static void sealSlab(LevelAccessor level, BlockPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos p = centre.offset(dx, 0, dz);
                if (!level.getBlockState(p).is(Blocks.MAGMA_BLOCK)) continue;
                seal(level, p, false);   // the chamber above the bed sits on it on purpose
            }
        }
    }

    /**
     * Rock to hide it behind. Deepslate below the transition, stone above it, so the patch matches
     * the layer it is in instead of announcing itself.
     */
    private static BlockState skinFor(BlockPos at) {
        return at.getY() < 0
                ? Blocks.DEEPSLATE.defaultBlockState()
                : Blocks.STONE.defaultBlockState();
    }
}
