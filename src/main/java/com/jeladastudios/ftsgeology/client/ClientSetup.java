package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.client.particle.GeothermalParticles;
import com.jeladastudios.ftsgeology.registry.ModParticles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Everything the mod does that only exists on a client.
 *
 * <p>Deliberately the only place. A particle's <i>type</i> is registered on both sides - the server
 * names it when it sends one - but the class that draws it exists only here, and a dedicated server
 * that so much as loads it crashes. Keeping the client half behind one {@code Dist.CLIENT}
 * subscriber makes that boundary a thing you can see rather than a thing you have to remember.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {}

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.GEYSER_MIST.get(), GeothermalParticles.MistProvider::new);
        event.registerSpriteSet(ModParticles.MUD_BLOB.get(), GeothermalParticles.MudProvider::new);
        event.registerSpriteSet(ModParticles.SULFUR_HAZE.get(), GeothermalParticles.HazeProvider::new);
    }
}
