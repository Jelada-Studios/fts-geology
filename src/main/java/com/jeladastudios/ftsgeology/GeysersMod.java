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
        com.jeladastudios.ftsgeology.registry.ModSounds.SOUNDS.register(modBus);

        // Populate the creative menu once tabs are built (mod bus event).
        modBus.addListener(this::onBuildCreativeTabs);

        // SERVER, not COMMON.
        //
        // Every setting here decides what the world does - how often a fault ruptures, how deep a
        // chamber is cut, whether unsupported ground falls. That is the server's business, and a
        // COMMON config is not: it is written on both sides and never synced, so a client joining a
        // server kept its own copy and quietly disagreed with the world it was standing in. SERVER
        // configs are sent to the client on join, which makes the server authoritative - the
        // behaviour you actually want the moment two people share a world.
        //
        // Two consequences worth knowing. The file moves to <world>/serverconfig/fts_geology.toml,
        // so settings now travel with the world instead of the installation. And because it is
        // per-world, a new world starts from the defaults - put a tuned file in the instance's
        // `defaultconfigs/` folder and Forge copies it into every world you create after that.
        //
        // Done during alpha on purpose: changing the type orphans any existing config file, and the
        // cheapest moment to pay that is while almost nobody has one.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, GeyserConfig.SPEC, "fts_geology.toml");

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
        com.jeladastudios.ftsgeology.hydrology.WaterTable.clearCache();
        com.jeladastudios.ftsgeology.volcano.VolcanoJob.clear();
        com.jeladastudios.ftsgeology.quake.Weathering.clear();
        com.jeladastudios.ftsgeology.quake.PendingEdits.clear();
        com.jeladastudios.ftsgeology.instrument.SeismicNetwork.clear();
        // Also the quakes still mid-application. Without this a rupture that was half applied when
        // the world closed stayed in the static list, and the next world loaded in the same session
        // - a different save, the same dimension key - had those edits written into it. The warning
        // window makes it easier to hit: a quake can now sit there for ten seconds doing nothing
        // before it writes its first block.
        com.jeladastudios.ftsgeology.quake.Earthquake.cancelAll();
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
