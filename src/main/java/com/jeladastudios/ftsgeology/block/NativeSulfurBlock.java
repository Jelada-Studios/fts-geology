package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Native sulfur: the yellow crust that condenses straight out of volcanic gas.
 *
 * <p>It is a deposit left by something still leaking, so the gas is what should be visible - and
 * the mod was drawing it, where it drew it at all, as ordinary white smoke.</p>
 *
 * <h2>The one thing that has to be right: it does not rise</h2>
 * Sulfur dioxide is heavier than air. It creeps downhill, gathers in hollows, and that is exactly
 * why volcanic gas is dangerous in low ground and why a sulfur flat looks like it has a layer lying
 * on it rather than a plume above it. So the haze is pushed sideways with a faint downward drift,
 * and given a long life so a field of vents accumulates a sheet instead of puffing.
 *
 * <p>No gas mechanic behind it, deliberately - the mod does not model gas and is not starting now.
 * This is the look of it and nothing else.</p>
 */
public class NativeSulfurBlock extends Block {

    public NativeSulfurBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!level.getBlockState(pos.above()).isAir()) return;
        // Sparse per block: a sulfur field is many blocks, and the layer should build out of a lot
        // of rare wisps rather than every block smoking at once.
        if (random.nextInt(14) != 0) return;

        level.addParticle(ModParticles.SULFUR_HAZE.get(),
                pos.getX() + random.nextDouble(),
                pos.getY() + 1.0 + random.nextDouble() * 0.3,
                pos.getZ() + random.nextDouble(),
                (random.nextDouble() - 0.5) * 0.02,
                0.004,                                   // barely up, then it sags
                (random.nextDouble() - 0.5) * 0.02);
    }
}
