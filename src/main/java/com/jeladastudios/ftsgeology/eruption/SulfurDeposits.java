package com.jeladastudios.ftsgeology.eruption;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lays down native sulfur around fumaroles and volcanic vents.
 *
 * <h2>The geology</h2>
 * A fumarole vents sulfur-bearing gas. When it hits the air the gas oxidises and drops its sulfur as
 * a bright yellow crust around the opening - the deposits miners still carry out of Kawah Ijen by
 * hand. That makes sulfur the acidic, volcanic counterpart to the calcite and travertine this mod
 * already deposits around alkaline geyser runoff (see
 * {@link EruptionHandler#depositTravertineRunoff}). Which mineral you find tells you what kind of
 * hydrothermal system you are standing on, which is the sort of contrast worth being able to see
 * in-game.
 *
 * <p>Deposits are gradual and never overwrite player blocks, matching how travertine is laid down.</p>
 */
public final class SulfurDeposits {

    private SulfurDeposits() {}

    /**
     * Attempts a sulfur crust around one vent opening. Called periodically by volcano vents and
     * geyser fumaroles; each call has only a small chance of actually placing something, so crusts
     * build up over time rather than appearing at once.
     */
    public static void depositAround(ServerLevel level, BlockPos vent) {
        if (!GeyserConfig.SULFUR_ENABLED.get()) return;
        if (level.random.nextDouble() >= GeyserConfig.SULFUR_DEPOSIT_CHANCE.get()) return;

        // Pick one neighbouring cell of the vent rim and crust it over.
        Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
        BlockPos rim = vent.relative(d);
        tryPlace(level, rim);
        // Occasionally the crust creeps a little further out, as real fumarole fields do.
        if (level.random.nextInt(4) == 0) {
            tryPlace(level, rim.relative(Direction.Plane.HORIZONTAL.getRandomDirection(level.random)));
        }
    }

    /**
     * Crusts one cell if it is a sensible place for sulfur: it has to be resting on solid ground,
     * be open air or loose natural rock, and never be part of a build or already sulfur.
     */
    private static void tryPlace(ServerLevel level, BlockPos p) {
        BlockState here = level.getBlockState(p);
        if (here.is(ModBlocks.NATIVE_SULFUR.get()) || here.is(Blocks.BEDROCK)) return;
        if (EruptionHandler.isPlayerPlaced(here)) return;
        if (!here.isAir() && !here.getFluidState().isEmpty()) return;   // do not sit in water or lava

        BlockState below = level.getBlockState(p.below());
        if (below.isAir() || !below.getFluidState().isEmpty()) return;  // needs solid ground
        if (EruptionHandler.isPlayerPlaced(below)) return;

        if (here.isAir() || EruptionHandler.isNaturalTerrain(here)) {
            level.setBlock(p, ModBlocks.NATIVE_SULFUR.get().defaultBlockState(), 2);
        }
    }
}
