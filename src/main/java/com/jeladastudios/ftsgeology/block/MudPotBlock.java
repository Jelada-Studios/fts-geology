package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A mud pot: thick grey mud kept boiling by the steam coming up through it.
 *
 * <p>It was a plain block, and the only thing marking it as hot was a vanilla bubble-pop borrowed
 * from a fish tank. What a mud pot actually does is heave: gas pushes a slug of paste up, it opens,
 * and it slaps back. So the mud is thrown as a few heavy gobbets that arc and land, which is the
 * one detail that makes it read as mud rather than as water.</p>
 *
 * <p>Client side only, from the ordinary random block tick - see {@code SteamVentBlock} - so a
 * field of these costs the server nothing at all.</p>
 */
public class MudPotBlock extends Block {

    public MudPotBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // Nothing under a ceiling: a pot buried in the ground has nowhere to throw anything.
        if (!level.getBlockState(pos.above()).isAir()) return;
        // Rare, and in bursts. A pot that bubbles steadily reads as a machine; one that sits still
        // and then heaves reads as something viscous.
        if (random.nextInt(6) != 0) return;

        double x = pos.getX() + 0.5, y = pos.getY() + 1.0, z = pos.getZ() + 0.5;
        int blobs = 1 + random.nextInt(3);
        for (int i = 0; i < blobs; i++) {
            level.addParticle(ModParticles.MUD_BLOB.get(),
                    x + (random.nextDouble() - 0.5) * 0.5,
                    y,
                    z + (random.nextDouble() - 0.5) * 0.5,
                    (random.nextDouble() - 0.5) * 0.09,
                    0.16 + random.nextDouble() * 0.14,
                    (random.nextDouble() - 0.5) * 0.09);
        }
        // The steam that drove it, thin and low.
        if (random.nextInt(3) == 0) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    x, y + 0.1, z, 0.0, 0.01, 0.0);
        }
    }
}
