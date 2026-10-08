package com.jeladastudios.ftsgeology.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What an explosion hurts with, which 1.21 no longer hands out: the gases tell their own blasts from others by it. */
@Mixin(Explosion.class)
public interface ExplosionAccessor {

    @Accessor("damageSource")
    DamageSource fts_geology$damageSource();
}
