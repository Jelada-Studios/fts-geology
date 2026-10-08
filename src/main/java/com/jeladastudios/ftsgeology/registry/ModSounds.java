package com.jeladastudios.ftsgeology.registry;
import net.neoforged.neoforge.registries.DeferredHolder;


import net.minecraft.core.registries.BuiltInRegistries;
import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.ForgeRegistries;

/**
 * The mod's own sounds.
 *
 * <h2>Mono, not stereo</h2>
 * Both files are converted to a single channel on purpose. Minecraft only applies distance
 * attenuation and panning to <b>mono</b> Ogg Vorbis; a stereo file plays at the same volume
 * wherever the listener is standing, which for a siren that is supposed to warn you about somewhere
 * else would be exactly wrong. This is the single most common mistake in modded audio and it is
 * invisible until someone walks away from the source and notices the sound is not fading.
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, GeysersMod.MODID);

    /** The seismograph's alert, sounded through the warning window before the ground moves. */
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_SIREN = register("quake_siren");

    /** The ground itself, while a rupture is being applied. */
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_RUMBLE = register("quake_rumble");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(GeysersMod.MODID, name)));
    }

    private ModSounds() {}
}
