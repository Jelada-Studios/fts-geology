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

    /**
     * The river's water is drawn like water. Without this the fluid falls back on the solid render type and a river
     * comes out as a blue wall: opaque, unlit underneath and with nothing visible through it.
     */
    @SubscribeEvent
    public static void clientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                    com.jeladastudios.ftsgeology.registry.ModFluids.RIVER_WATER.get(),
                    net.minecraft.client.renderer.RenderType.translucent());
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                    com.jeladastudios.ftsgeology.registry.ModFluids.FLOWING_RIVER_WATER.get(),
                    net.minecraft.client.renderer.RenderType.translucent());
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                    com.jeladastudios.ftsgeology.registry.ModBlocks.RIVER_WATER.get(),
                    net.minecraft.client.renderer.RenderType.translucent());
        });
    }

    /**
     * The river's water takes the biome's water colour, as vanilla's does. The fluid already did in the world, through
     * water's own fluid type; but a renderer that colours a block from its particle texture and the block colours --
     * Distant Horizons for its far terrain -- found nothing registered for this block and drew every river the grey of
     * the untinted water texture.
     */
    @SubscribeEvent
    public static void blockColors(net.minecraftforge.client.event.RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> level != null && pos != null
                        ? net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(level, pos) : 0x3F76E4,
                com.jeladastudios.ftsgeology.registry.ModBlocks.RIVER_WATER.get());
    }

    /** The geothermal turbine's rotor turns: a renderer for it, and its model baked though no block names it. */
    @SubscribeEvent
    public static void registerRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(com.jeladastudios.ftsgeology.registry.ModBlockEntities.GEOTHERMAL_TURBINE.get(),
                TurbineRenderer::new);
    }

    @SubscribeEvent
    public static void registerModels(net.minecraftforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(TurbineRenderer.ROTOR);
    }

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.GEYSER_MIST.get(), GeothermalParticles.MistProvider::new);
        event.registerSpriteSet(ModParticles.MUD_BLOB.get(), GeothermalParticles.MudProvider::new);
        event.registerSpriteSet(ModParticles.SULFUR_HAZE.get(), GeothermalParticles.HazeProvider::new);
        event.registerSpriteSet(ModParticles.VOLCANIC_SMOKE.get(),
                com.jeladastudios.ftsgeology.client.particle.VolcanicParticles.SmokeProvider::new);
        event.registerSpriteSet(ModParticles.ASH_CLOUD.get(),
                com.jeladastudios.ftsgeology.client.particle.VolcanicParticles.CloudProvider::new);
        event.registerSpriteSet(ModParticles.ASH_FLAKE.get(),
                com.jeladastudios.ftsgeology.client.particle.VolcanicParticles.FlakeProvider::new);
        event.registerSpriteSet(ModParticles.VENT_SMOKE.get(),
                com.jeladastudios.ftsgeology.client.particle.VolcanicParticles.VentProvider::new);
    }
}
