package com.jeladastudios.ftsgeology.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sound engine behind the sound manager, to ask whether the rain's recordings are really sounding (see {@code ClientWeather}). */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {

    @Accessor("soundEngine")
    SoundEngine fts_geology$soundEngine();
}
