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
        com.jeladastudios.ftsgeology.registry.ModFluids.FLUID_TYPES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModFluids.FLUIDS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModSounds.SOUNDS.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModParticles.PARTICLES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModFeatures.FEATURES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModDensityFunctions.DENSITY_FUNCTIONS.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModBiomeSources.BIOME_SOURCES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModChunkGenerators.CHUNK_GENERATORS.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModSurfaceRules.MATERIAL_RULES.register(modBus);
        com.jeladastudios.ftsgeology.registry.ModBiomeModifiers.SERIALIZERS.register(modBus);

        // The gases: their own registers, tab and settings, so the module stays whole in itself.
        com.jeladastudios.ftsgeology.gas.registry.GasBlocks.BLOCKS.register(modBus);
        com.jeladastudios.ftsgeology.gas.registry.GasItems.ITEMS.register(modBus);
        com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities.BLOCK_ENTITIES.register(modBus);
        com.jeladastudios.ftsgeology.gas.registry.GasTabs.TABS.register(modBus);
        modBus.addListener(com.jeladastudios.ftsgeology.gas.registry.GasCapabilities::register);
        // Where Create is installed, a gas engine's crankshaft turns its shafts (compat.create, reached by name: it is built on
        // Create's classes, and left out of a build made without Create's jar).
        if (net.minecraftforge.fml.ModList.get().isLoaded("create")) {
            try {
                Class.forName("com.jeladastudios.ftsgeology.compat.create.CreateCrank").getMethod("register", IEventBus.class).invoke(null, modBus);
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("Create's crankshaft not registered: {}", e.toString());
            }
        }

        // Populate the creative menu once tabs are built (mod bus event).
        modBus.addListener(this::onBuildCreativeTabs);

        // Let the fish be born in the mod's river water as well as in vanilla's (mod bus event).
        modBus.addListener(com.jeladastudios.ftsgeology.hydrology.RiverSpawns::register);

        // The mod's first packet. Registered in common setup because the channel has to exist on
        // both sides before anybody joins, and the handler itself is guarded for physical side.
        modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                e.enqueueWork(com.jeladastudios.ftsgeology.network.ModNetwork::register));

        // Distant Horizons' far terrain from the plate model, when it is installed.
        modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                e.enqueueWork(com.jeladastudios.ftsgeology.compat.dh.DhTerrain::init));
        // A gas burner under a Create boiler heats it, where Create is installed.
        modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                e.enqueueWork(com.jeladastudios.ftsgeology.compat.CreateBoiler::register));

        // SERVER, not COMMON: every setting decides what the world does, and SERVER configs are synced
        // to clients on join. The file lives in <world>/serverconfig/fts_geology.toml; a file in the
        // instance's defaultconfigs/ folder is copied into each new world.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, GeyserConfig.SPEC, "fts_geology.toml");
        // The gases' settings, beside it in the same folder.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, com.jeladastudios.ftsgeology.gas.GasConfig.SPEC, "fts_geology-gases.toml");
        // The player's own sound and sky settings, on their machine.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, com.jeladastudios.ftsgeology.config.ClientConfig.SPEC, "fts_geology-client.toml");

        // The geology advancements' trigger, registered before advancements are read.
        com.jeladastudios.ftsgeology.advancement.GeologyTrigger.init();

        // Above the snow line only the animals of the high snow are born (game bus event).
        MinecraftForge.EVENT_BUS.addListener(com.jeladastudios.ftsgeology.worldgen.SnowLineSpawns::check);

        // Crops ripen faster on the floodplain's silt (game bus event).
        MinecraftForge.EVENT_BUS.addListener(com.jeladastudios.ftsgeology.hydrology.AlluvialSoil::grow);

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
        com.jeladastudios.ftsgeology.worldgen.terrain.ColumnClimate.clear();
        com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.clearFinishing();
        com.jeladastudios.ftsgeology.quake.Weathering.clear();
        com.jeladastudios.ftsgeology.quake.CaveCollapse.clear();
        com.jeladastudios.ftsgeology.quake.PendingEdits.clear();
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork.clear();
        if (com.jeladastudios.ftsgeology.quake.FaultClocks.rolled() > 0) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.quake.FaultClocks.summary());
        com.jeladastudios.ftsgeology.quake.FaultClocks.clear();
        String modOres = com.jeladastudios.ftsgeology.worldgen.OreGenesis.modOresSummary();
        if (modOres != null) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", modOres);
        com.jeladastudios.ftsgeology.compat.OreUnification.clear();
        String fossils = com.jeladastudios.ftsgeology.worldgen.FossilBeds.summary();
        if (fossils != null) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", fossils);
        com.jeladastudios.ftsgeology.worldgen.FossilBeds.clear();
        if (com.jeladastudios.ftsgeology.quake.RoofLoad.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.quake.RoofLoad.summary());
        com.jeladastudios.ftsgeology.quake.RoofLoad.clear();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.hydrology.Subsidence.summary());
        com.jeladastudios.ftsgeology.hydrology.Subsidence.clear();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.hydrology.HollowPonds.summary());
        com.jeladastudios.ftsgeology.hydrology.HollowPonds.clear();
        if (com.jeladastudios.ftsgeology.hydrology.Reservoirs.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.hydrology.Reservoirs.summary());
        com.jeladastudios.ftsgeology.hydrology.Reservoirs.clear();
        if (com.jeladastudios.ftsgeology.hydrology.Floods.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.hydrology.Floods.summary());
        com.jeladastudios.ftsgeology.hydrology.Floods.clear();
        if (com.jeladastudios.ftsgeology.weather.Storms.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.weather.Storms.summary());
        com.jeladastudios.ftsgeology.weather.Storms.clear();
        if (com.jeladastudios.ftsgeology.weather.Atmosphere.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.weather.Atmosphere.summary());
        com.jeladastudios.ftsgeology.weather.Atmosphere.clear();
        if (com.jeladastudios.ftsgeology.hydrology.Puddles.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.hydrology.Puddles.summary());
        com.jeladastudios.ftsgeology.hydrology.Puddles.clear();
        if (com.jeladastudios.ftsgeology.hydrology.RiverSpill.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.hydrology.RiverSpill.summary());
        com.jeladastudios.ftsgeology.hydrology.RiverSpill.clear();
        if (com.jeladastudios.ftsgeology.hydrology.RiverFloods.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.hydrology.RiverFloods.summary());
        com.jeladastudios.ftsgeology.hydrology.RiverFloods.clear();
        if (com.jeladastudios.ftsgeology.volcano.FissureEruptions.any()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", com.jeladastudios.ftsgeology.volcano.FissureEruptions.summary());
        com.jeladastudios.ftsgeology.volcano.FissureEruptions.clear();
        com.jeladastudios.ftsgeology.command.SiteTeleport.clear();
        com.jeladastudios.ftsgeology.blockentity.GeothermalTurbineBlockEntity.clearAll();
        com.jeladastudios.ftsgeology.compat.CreateRivers.clear();
        com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity.clearAll();
        com.jeladastudios.ftsgeology.worldgen.LavaTubes.clear();
        com.jeladastudios.ftsgeology.worldgen.OreGenesis.clear();
        com.jeladastudios.ftsgeology.worldgen.GeothermalBasin.clear();
        com.jeladastudios.ftsgeology.worldgen.RetrogenHandler.clear();
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

    /** Adds the technical block items to the creative tabs for testing and debugging; on the mod bus (see the constructor). */
    public void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            // The instruments belong with the tools, not with the terrain: they are the only
            // things in the mod a player is meant to carry.
            event.accept(ModItems.SEISMOGRAPH.get());
            event.accept(ModItems.WEATHER_TERMINAL.get());
            event.accept(ModItems.BAROMETER.get());
            event.accept(ModItems.ANEMOMETER.get());
            event.accept(ModItems.HYGROMETER.get());
            event.accept(ModItems.RAIN_GAUGE.get());
            event.accept(ModItems.THERMOMETER.get());
            event.accept(ModItems.SOIL_PROBE.get());
            event.accept(ModItems.TILTMETER.get());
            event.accept(ModItems.GPS_STATION.get());
            event.accept(ModItems.GAS_METER.get());
            event.accept(ModItems.GEOTHERMAL_TURBINE.get());
            event.accept(ModItems.THERMOELECTRIC_GENERATOR.get());
            event.accept(ModItems.WELL_PUMP.get());
            event.accept(ModItems.WELL_CASING.get());
            event.accept(ModItems.WELLHEAD.get());
            event.accept(ModItems.CRUDE_OIL_BUCKET.get());
            event.accept(ModItems.SEA_WATER_BUCKET.get());
            event.accept(ModItems.GEOLOGISTS_HAMMER.get());
            event.accept(ModItems.FAULT_COMPASS.get());
            event.accept(ModItems.CORE_DRILL.get());
            event.accept(ModItems.GEOTHERMAL_PROBE.get());
            event.accept(ModItems.GEOLOGY_MAP.get());
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
            event.accept(ModItems.OIL_SANDSTONE.get());
            event.accept(ModItems.CHERT.get());
            event.accept(ModItems.COOLING_LAVA_CRUST.get());
            event.accept(ModItems.BASALT_LAYER.get());
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
