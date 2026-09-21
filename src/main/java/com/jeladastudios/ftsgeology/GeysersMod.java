package com.jeladastudios.ftsgeology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.registry.ModItems;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for FT's Geology (Forge 1.20.1). Additive and safe on existing worlds; optional mods
 * such as Flowing Fluids are detected, never required.
 */
@Mod(GeysersMod.MODID)
public class GeysersMod {

    public static final String MODID = "fts_geology";
    public static final Logger LOGGER = LoggerFactory.getLogger("FTsGeology");

    public GeysersMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModFluids.FLUIDS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModSounds.SOUNDS.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModParticles.PARTICLES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModFeatures.FEATURES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModDensityFunctions.DENSITY_FUNCTIONS.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModBiomeSources.BIOME_SOURCES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModSurfaceRules.MATERIAL_RULES.register(modBus);

        // Populate the creative menu once tabs are built (mod bus event).
        modBus.addListener(this::onBuildCreativeTabs);

        // Let the fish be born in the mod's river water as well as in vanilla's (mod bus event).
        modBus.addListener(com.jeladastudios.ftsgeology.hydrology.RiverSpawns::register);

        // The mod's first packet. Registered in common setup because the channel has to exist on
        // both sides before anybody joins, and the handler itself is guarded for physical side.
        modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                e.enqueueWork(com.jeladastudios.ftsgeology.network.ModNetwork::register));

        // SERVER, not COMMON: every setting decides what the world does, and SERVER configs are synced
        // to clients on join. The file lives in <world>/serverconfig/fts_geology.toml; a file in the
        // instance's defaultconfigs/ folder is copied into each new world.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, GeyserConfig.SPEC, "fts_geology.toml");

        // RetrogenHandler registers itself through @EventBusSubscriber.
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("FTs Geology initialised.");
    }

    /**
     * Plate crust types are cached per world; drop them when a server stops so joining a different
     * world (or the same seed after a worldgen-mod change) re-reads the biome source instead of
     * reusing stale answers.
     */
    @SubscribeEvent
    public void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        com.jeladastudios.ftsgeology.tectonics.TectonicMap.clearCache();
        com.jeladastudios.ftsgeology.tectonics.HotspotMap.clearCache();
        com.jeladastudios.ftsgeology.hydrology.WaterTable.clearCache();
        com.jeladastudios.ftsgeology.volcano.VolcanoJob.clear();
        com.jeladastudios.ftsgeology.volcano.VolcanoField.clearCache();
        com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.clearFinishing();
        com.jeladastudios.ftsgeology.quake.Weathering.clear();
        com.jeladastudios.ftsgeology.quake.CaveCollapse.clear();
        com.jeladastudios.ftsgeology.quake.PendingEdits.clear();
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork.clear();
        com.jeladastudios.ftsgeology.command.SiteTeleport.clear();
        com.jeladastudios.ftsgeology.worldgen.LavaTubes.clear();
        com.jeladastudios.ftsgeology.worldgen.OreGenesis.clear();
        com.jeladastudios.ftsgeology.worldgen.GeothermalBasin.clear();
        TfcCompat.clear();
        // And quakes still mid-application, so a half-applied rupture cannot write into the next world.
        com.jeladastudios.ftsgeology.quake.Earthquake.cancelAll();
    }
    /**
     * Tells a joining player, once, about the optional mods this one reads. There are no hard
     * dependencies; vanilla water cannot drain an infinite source, which Flowing Fluids fixes.
     */
    @SubscribeEvent
    public void onPlayerJoin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!GeyserConfig.SUGGEST_OPTIONAL_MODS.get()) return;
        if (com.jeladastudios.ftsgeology.eruption.EruptionHandler.hasFiniteWater()) return;
        event.getEntity().sendSystemMessage(
                net.minecraft.network.chat.Component.translatable("message.fts_geology.suggest_flowing_fluids")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /** Adds the technical block items to the Natural Blocks creative tab for testing/debugging. */
    @SubscribeEvent
    public void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            // The instruments belong with the tools, not with the terrain: they are the only
            // things in the mod a player is meant to carry.
            event.accept(ModItems.SEISMOGRAPH.get());
            event.accept(ModItems.GEOLOGISTS_HAMMER.get());
            event.accept(ModItems.FAULT_COMPASS.get());
            event.accept(ModItems.FIELD_GUIDE.get());
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            acceptWorkedRocks(event);
        }
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(ModItems.GEYSER_IGNITER.get());
            event.accept(ModItems.VOLCANO_IGNITER.get());
            event.accept(ModItems.HOT_SPRING.get());
            event.accept(ModItems.NATIVE_SULFUR.get());
            event.accept(ModItems.SINTER.get());
            event.accept(ModItems.SINTER_CRUST.get());
            event.accept(ModItems.MUD_POT.get());
            event.accept(ModItems.STEAM_VENT.get());
            event.accept(ModItems.VOLCANIC_ASH.get());
            event.accept(ModItems.VOLCANIC_BLACK_SAND.get());
            event.accept(ModItems.TRAVERTINE.get());
            event.accept(ModItems.RHYOLITE.get());
            event.accept(ModItems.GABBRO.get());
            event.accept(ModItems.PERIDOTITE.get());
            event.accept(ModItems.SERPENTINITE.get());
            event.accept(ModItems.SCHIST.get());
            event.accept(ModItems.GNEISS.get());
            event.accept(ModItems.SLATE.get());
            event.accept(ModItems.MARBLE.get());
            event.accept(ModItems.QUARTZITE.get());
            event.accept(ModItems.SHALE.get());
            event.accept(ModItems.CHERT.get());
            event.accept(ModItems.COOLING_LAVA_CRUST.get());
            event.accept(ModItems.PYRITE.get());
            event.accept(ModItems.CHALCOPYRITE.get());
            event.accept(ModItems.MALACHITE.get());
            event.accept(ModItems.AZURITE.get());
            event.accept(ModItems.QUARTZ_VEIN.get());
            event.accept(ModItems.CINNABAR.get());
            event.accept(ModItems.GALENA.get());
            acceptWorkedRocks(event);
            event.accept(ModItems.MICROBIAL_MAT_ORANGE.get());
            event.accept(ModItems.MICROBIAL_MAT_YELLOW.get());
            event.accept(ModItems.MICROBIAL_MAT_BROWN.get());
            event.accept(ModItems.MICROBIAL_MAT_GREEN.get());
            event.accept(ModItems.GEYSER_CORE.get());
            event.accept(ModItems.GEYSER_CHAMBER.get());
        }
    }

    private void acceptWorkedRocks(BuildCreativeModeTabContentsEvent event) {
        event.accept(ModItems.POLISHED_TRAVERTINE.get());
        event.accept(ModItems.TRAVERTINE_SLAB.get());
        event.accept(ModItems.TRAVERTINE_STAIRS.get());
        event.accept(ModItems.TRAVERTINE_WALL.get());

        event.accept(ModItems.POLISHED_RHYOLITE.get());
        event.accept(ModItems.RHYOLITE_SLAB.get());
        event.accept(ModItems.RHYOLITE_STAIRS.get());
        event.accept(ModItems.RHYOLITE_WALL.get());

        event.accept(ModItems.POLISHED_GABBRO.get());
        event.accept(ModItems.GABBRO_SLAB.get());
        event.accept(ModItems.GABBRO_STAIRS.get());
        event.accept(ModItems.GABBRO_WALL.get());

        event.accept(ModItems.POLISHED_PERIDOTITE.get());
        event.accept(ModItems.PERIDOTITE_SLAB.get());
        event.accept(ModItems.PERIDOTITE_STAIRS.get());
        event.accept(ModItems.PERIDOTITE_WALL.get());

        event.accept(ModItems.POLISHED_SERPENTINITE.get());
        event.accept(ModItems.SERPENTINITE_SLAB.get());
        event.accept(ModItems.SERPENTINITE_STAIRS.get());
        event.accept(ModItems.SERPENTINITE_WALL.get());

        event.accept(ModItems.POLISHED_SCHIST.get());
        event.accept(ModItems.SCHIST_SLAB.get());
        event.accept(ModItems.SCHIST_STAIRS.get());
        event.accept(ModItems.SCHIST_WALL.get());

        event.accept(ModItems.POLISHED_GNEISS.get());
        event.accept(ModItems.GNEISS_SLAB.get());
        event.accept(ModItems.GNEISS_STAIRS.get());
        event.accept(ModItems.GNEISS_WALL.get());

        event.accept(ModItems.POLISHED_SLATE.get());
        event.accept(ModItems.SLATE_SLAB.get());
        event.accept(ModItems.SLATE_STAIRS.get());
        event.accept(ModItems.SLATE_WALL.get());

        event.accept(ModItems.POLISHED_MARBLE.get());
        event.accept(ModItems.MARBLE_SLAB.get());
        event.accept(ModItems.MARBLE_STAIRS.get());
        event.accept(ModItems.MARBLE_WALL.get());

        event.accept(ModItems.POLISHED_QUARTZITE.get());
        event.accept(ModItems.QUARTZITE_SLAB.get());
        event.accept(ModItems.QUARTZITE_STAIRS.get());
        event.accept(ModItems.QUARTZITE_WALL.get());

        event.accept(ModItems.POLISHED_SHALE.get());
        event.accept(ModItems.SHALE_SLAB.get());
        event.accept(ModItems.SHALE_STAIRS.get());
        event.accept(ModItems.SHALE_WALL.get());

        event.accept(ModItems.POLISHED_CHERT.get());
        event.accept(ModItems.CHERT_SLAB.get());
        event.accept(ModItems.CHERT_STAIRS.get());
        event.accept(ModItems.CHERT_WALL.get());
    }
}
