package com.jeladastudios.ftsgeology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.registry.ModItems;
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
 * Entry point for the Hydrothermal Geysers mod (Forge 1.20.1).
 *
 * <p>Design goals:
 * <ul>
 *   <li>Additive: safe to bolt onto pre-existing server worlds via Retrogen.</li>
 *   <li>Non-destructive: never edits terrain at or above {@code retrogenMaxY} (default -30).</li>
 *   <li>Compatible with Flowing Fluids (finite water) and Water Erosion — we place/remove
 *       vanilla {@code Fluids.WATER} source blocks and let those mods drive surface behaviour.</li>
 * </ul>
 */
@Mod(GeysersMod.MODID)
public class GeysersMod {

    public static final String MODID = "fts_geology";
    public static final Logger LOGGER = LoggerFactory.getLogger("FTsGeology");

    public GeysersMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);

        // Populate the creative menu once tabs are built (mod bus event).
        modBus.addListener(this::onBuildCreativeTabs);

        // Config file renamed with the mod. A Forge config file is only created once: changing a
        // DEFAULT never reaches a world that already has the file, which silently kept several
        // rounds of performance tuning from ever taking effect. The new name gives everyone a clean
        // file with the current defaults, once.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GeyserConfig.SPEC, "fts_geology.toml");

        // RetrogenHandler subscribes to the Forge bus via @EventBusSubscriber, so it
        // registers automatically. EruptionHandler is a stateless static utility (no events).
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
        com.jeladastudios.ftsgeology.volcano.VolcanoJob.clear();
        com.jeladastudios.ftsgeology.quake.Weathering.clear();
        com.jeladastudios.ftsgeology.quake.PendingEdits.clear();
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork.clear();
    }
    /**
     * Tells a joining player, once, about the optional mods this one reads.
     *
     * <p>There are no hard dependencies and there will not be any: the mod has to run on a bare
     * Forge install, in a school as much as in a pack. But water is the one place where the ceiling
     * is vanilla's own physics rather than anything here - an infinite source cannot be made to
     * drain, so geyser runoff has to be laid and taken back by hand - and a player has no way of
     * knowing that a different mod would make it behave properly. So it is mentioned, once, and
     * never again.</p>
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
        }
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(ModItems.GEYSER_IGNITER.get());
            event.accept(ModItems.VOLCANO_IGNITER.get());
            event.accept(ModItems.HOT_SPRING.get());
            event.accept(ModItems.NATIVE_SULFUR.get());
            event.accept(ModItems.SINTER.get());
            event.accept(ModItems.MICROBIAL_MAT_ORANGE.get());
            event.accept(ModItems.MICROBIAL_MAT_YELLOW.get());
            event.accept(ModItems.MICROBIAL_MAT_BROWN.get());
            event.accept(ModItems.MICROBIAL_MAT_GREEN.get());
            event.accept(ModItems.GEYSER_CORE.get());
            event.accept(ModItems.GEYSER_CHAMBER.get());
        }
    }
}
