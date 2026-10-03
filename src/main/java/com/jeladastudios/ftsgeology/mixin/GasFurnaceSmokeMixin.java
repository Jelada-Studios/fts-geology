package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.client.ClientGasFired;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * No smoke out of a furnace, a smoker or a blast furnace that burns gas: hydrogen burns to water, methane and biogas
 * clean to water and carbon dioxide, and neither leaves soot. Vanilla puffs smoke out of any of them while lit, and a
 * blast furnace on hydrogen smoked as if on coal. Which ones burn gas the server tells (gas.GasFiredFurnaces); a
 * furnace's flame stays.
 */
@Mixin({FurnaceBlock.class, BlastFurnaceBlock.class, SmokerBlock.class})
public abstract class GasFurnaceSmokeMixin {

    @Redirect(method = "animateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"),
            require = 0)
    private void fts_geology$noGasSmoke(Level level, ParticleOptions particle, double x, double y, double z,
                                        double dx, double dy, double dz, BlockState state, Level same, BlockPos pos,
                                        RandomSource random) {
        if (particle == ParticleTypes.SMOKE && ClientGasFired.has(pos)) return;
        level.addParticle(particle, x, y, z, dx, dy, dz);
    }
}
