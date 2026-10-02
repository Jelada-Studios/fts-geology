package com.jeladastudios.ftsgeology.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The channel each playing sound holds. The engine counts a sound as playing while it holds one, though a stream that
 * never began holds its channel silent for good; the channel itself says whether its source plays.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {

    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> fts_geology$channels();
}
