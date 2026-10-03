package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.config.ClientConfig;
import com.mojang.blaze3d.audio.Library;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * How many sounds may stream at once ({@link ClientConfig#SOUND_STREAMS}). Vanilla splits the sound card's sources into
 * a square root's worth of streams, at most eight, and the rest for plain sounds: with ambience, music and a weather
 * mod's loops in a pack all eight were held, and a loop started after that -- the rain's -- was never heard. The plain
 * sounds keep some two hundred and forty.
 */
@Mixin(Library.class)
public abstract class SoundStreamsMixin {

    /** The first eight in {@code init}: the cap on the streams (the second is the floor under the plain sounds). */
    @ModifyConstant(method = "init", constant = @Constant(intValue = 8, ordinal = 0), require = 0)
    private int fts_geology$moreStreams(int vanilla) {
        try {
            return ClientConfig.SOUND_STREAMS.get();
        } catch (IllegalStateException notLoadedYet) {
            return vanilla;
        }
    }
}
