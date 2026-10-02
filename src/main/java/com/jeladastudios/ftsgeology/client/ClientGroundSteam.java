package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Steam off hot ground in the rain and after it: rain on a geyser field's sinter, on lava crust still cooling, on a
 * fumarole's mound, a mud pot or magma, boils off it in white wisps -- as a hot field steams after a shower -- and goes on
 * for a few minutes once the rain has passed, while the ground is still wet. Client only, from a few columns round the
 * player each tick; the server is never asked.
 */
public final class ClientGroundSteam {

    private ClientGroundSteam() {}

    /** How far round the player the ground is looked at, and how many columns a tick at the most. */
    private static final int REACH = 20, MOST = 14;
    /** How fast the hot ground wets in rain, and dries after: by half in two minutes. */
    private static final float WETS = 1f / 600f, DRIES = (float) Math.exp(-0.693 / 2400.0);

    private static float wet;

    /** One client tick, with the rain the player stands in. */
    static void tick(LocalPlayer player, float rain) {
        wet = rain > 0.02f ? Math.min(1f, wet + WETS * (0.3f + rain)) : wet * DRIES;
        if (wet < 0.03f) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        RandomSource r = level.random;
        int tries = Math.round(2 + MOST * wet);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < tries; i++) {
            int x = player.getBlockX() + r.nextInt(2 * REACH + 1) - REACH, z = player.getBlockZ() + r.nextInt(2 * REACH + 1) - REACH;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            at.set(x, y, z);
            if (!hot(level.getBlockState(at))) continue;
            // Only ground the rain falls on.
            if (!level.canSeeSky(at.above())) continue;
            double px = x + r.nextDouble(), pz = z + r.nextDouble(), py = y + 1.05;
            level.addParticle(r.nextInt(3) == 0 ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.CLOUD,
                    px, py, pz, (r.nextDouble() - 0.5) * 0.01, 0.03 + r.nextDouble() * 0.03 * wet, (r.nextDouble() - 0.5) * 0.01);
            if (r.nextInt(60) == 0) {
                level.playLocalSound(px, py, pz, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.06f + 0.08f * wet,
                        1.6f + r.nextFloat() * 0.3f, false);
            }
        }
    }

    /** Ground hot enough to boil the rain off it. */
    private static boolean hot(BlockState s) {
        return s.is(ModBlocks.SINTER.get()) || s.is(ModBlocks.SINTER_CRUST.get()) || s.is(ModBlocks.COOLING_LAVA_CRUST.get())
                || s.is(ModBlocks.STEAM_VENT.get()) || s.is(ModBlocks.MUD_POT.get()) || s.is(Blocks.MAGMA_BLOCK);
    }

    /** A new world: dry ground. */
    static void reset() {
        wet = 0f;
    }

    /** For trying it out: the hot ground this wet now, 0 to 1. */
    public static void set(float w) {
        wet = Math.max(0f, Math.min(1f, w));
    }
}
