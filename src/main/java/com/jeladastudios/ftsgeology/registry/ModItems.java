package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.item.FaultCompassItem;
import com.jeladastudios.ftsgeology.item.FieldGuideItem;
import com.jeladastudios.ftsgeology.item.GeologistsHammerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * BlockItems for the technical blocks. They aren't obtainable in survival (blocks are
 * placed by worldgen and have no loot table), but registering items makes the
 * {@code models/item/*.json} meaningful and lets admins/testers place cores by hand in
 * creative to debug the simulation.
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, GeysersMod.MODID);

    public static final RegistryObject<Item> GEYSER_CORE = ITEMS.register("geyser_core",
            () -> new BlockItem(ModBlocks.GEYSER_CORE.get(), new Item.Properties()));

    public static final RegistryObject<Item> GEYSER_CHAMBER = ITEMS.register("geyser_chamber",
            () -> new BlockItem(ModBlocks.GEYSER_CHAMBER.get(), new Item.Properties()));

    public static final RegistryObject<Item> GEYSER_IGNITER = ITEMS.register("geyser_igniter",
            () -> new BlockItem(ModBlocks.GEYSER_IGNITER.get(), new Item.Properties()));

    public static final RegistryObject<Item> HOT_SPRING = ITEMS.register("hot_spring",
            () -> new BlockItem(ModBlocks.HOT_SPRING.get(), new Item.Properties()));

    public static final RegistryObject<Item> VOLCANO_IGNITER = ITEMS.register("volcano_igniter",
            () -> new BlockItem(ModBlocks.VOLCANO_IGNITER.get(), new Item.Properties()));

    public static final RegistryObject<Item> NATIVE_SULFUR = ITEMS.register("native_sulfur",
            () -> new BlockItem(ModBlocks.NATIVE_SULFUR.get(), new Item.Properties()));


    public static final RegistryObject<Item> SINTER = ITEMS.register("sinter",
            () -> new BlockItem(ModBlocks.SINTER.get(), new Item.Properties()));

    public static final RegistryObject<Item> SINTER_CRUST = ITEMS.register("sinter_crust",
            () -> new BlockItem(ModBlocks.SINTER_CRUST.get(), new Item.Properties()));

    public static final RegistryObject<Item> MUD_POT = ITEMS.register("mud_pot",
            () -> new BlockItem(ModBlocks.MUD_POT.get(), new Item.Properties()));

    public static final RegistryObject<Item> STEAM_VENT = ITEMS.register("steam_vent",
            () -> new BlockItem(ModBlocks.STEAM_VENT.get(), new Item.Properties()));

    public static final RegistryObject<Item> TRAVERTINE = ITEMS.register("travertine",
            () -> new BlockItem(ModBlocks.TRAVERTINE.get(), new Item.Properties()));

    public static final RegistryObject<Item> RHYOLITE = ITEMS.register("rhyolite",
            () -> new BlockItem(ModBlocks.RHYOLITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> GABBRO = ITEMS.register("gabbro",
            () -> new BlockItem(ModBlocks.GABBRO.get(), new Item.Properties()));

    public static final RegistryObject<Item> PERIDOTITE = ITEMS.register("peridotite",
            () -> new BlockItem(ModBlocks.PERIDOTITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> SERPENTINITE = ITEMS.register("serpentinite",
            () -> new BlockItem(ModBlocks.SERPENTINITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> SCHIST = ITEMS.register("schist",
            () -> new BlockItem(ModBlocks.SCHIST.get(), new Item.Properties()));

    public static final RegistryObject<Item> GNEISS = ITEMS.register("gneiss",
            () -> new BlockItem(ModBlocks.GNEISS.get(), new Item.Properties()));

    public static final RegistryObject<Item> SLATE = ITEMS.register("slate",
            () -> new BlockItem(ModBlocks.SLATE.get(), new Item.Properties()));

    public static final RegistryObject<Item> MARBLE = ITEMS.register("marble",
            () -> new BlockItem(ModBlocks.MARBLE.get(), new Item.Properties()));

    public static final RegistryObject<Item> QUARTZITE = ITEMS.register("quartzite",
            () -> new BlockItem(ModBlocks.QUARTZITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> SHALE = ITEMS.register("shale",
            () -> new BlockItem(ModBlocks.SHALE.get(), new Item.Properties()));

    public static final RegistryObject<Item> CHERT = ITEMS.register("chert",
            () -> new BlockItem(ModBlocks.CHERT.get(), new Item.Properties()));

    public static final RegistryObject<Item> COOLING_LAVA_CRUST = ITEMS.register("cooling_lava_crust",
            () -> new BlockItem(ModBlocks.COOLING_LAVA_CRUST.get(), new Item.Properties()));

    // --- Mineral blocks ------------------------------------------------------

    public static final RegistryObject<Item> PYRITE = ITEMS.register("pyrite",
            () -> new BlockItem(ModBlocks.PYRITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> CHALCOPYRITE = ITEMS.register("chalcopyrite",
            () -> new BlockItem(ModBlocks.CHALCOPYRITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> MALACHITE = ITEMS.register("malachite",
            () -> new BlockItem(ModBlocks.MALACHITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> AZURITE = ITEMS.register("azurite",
            () -> new BlockItem(ModBlocks.AZURITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> QUARTZ_VEIN = ITEMS.register("quartz_vein",
            () -> new BlockItem(ModBlocks.QUARTZ_VEIN.get(), new Item.Properties()));

    public static final RegistryObject<Item> CINNABAR = ITEMS.register("cinnabar",
            () -> new BlockItem(ModBlocks.CINNABAR.get(), new Item.Properties()));

    public static final RegistryObject<Item> GALENA = ITEMS.register("galena",
            () -> new BlockItem(ModBlocks.GALENA.get(), new Item.Properties()));

    // --- Worked rock forms (polished, slab, stairs, wall) -------------------

    public static final RegistryObject<Item> POLISHED_TRAVERTINE = ITEMS.register("polished_travertine",
            () -> new BlockItem(ModBlocks.POLISHED_TRAVERTINE.get(), new Item.Properties()));
    public static final RegistryObject<Item> TRAVERTINE_SLAB = ITEMS.register("travertine_slab",
            () -> new BlockItem(ModBlocks.TRAVERTINE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> TRAVERTINE_STAIRS = ITEMS.register("travertine_stairs",
            () -> new BlockItem(ModBlocks.TRAVERTINE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> TRAVERTINE_WALL = ITEMS.register("travertine_wall",
            () -> new BlockItem(ModBlocks.TRAVERTINE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_RHYOLITE = ITEMS.register("polished_rhyolite",
            () -> new BlockItem(ModBlocks.POLISHED_RHYOLITE.get(), new Item.Properties()));
    public static final RegistryObject<Item> RHYOLITE_SLAB = ITEMS.register("rhyolite_slab",
            () -> new BlockItem(ModBlocks.RHYOLITE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> RHYOLITE_STAIRS = ITEMS.register("rhyolite_stairs",
            () -> new BlockItem(ModBlocks.RHYOLITE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> RHYOLITE_WALL = ITEMS.register("rhyolite_wall",
            () -> new BlockItem(ModBlocks.RHYOLITE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_GABBRO = ITEMS.register("polished_gabbro",
            () -> new BlockItem(ModBlocks.POLISHED_GABBRO.get(), new Item.Properties()));
    public static final RegistryObject<Item> GABBRO_SLAB = ITEMS.register("gabbro_slab",
            () -> new BlockItem(ModBlocks.GABBRO_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> GABBRO_STAIRS = ITEMS.register("gabbro_stairs",
            () -> new BlockItem(ModBlocks.GABBRO_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> GABBRO_WALL = ITEMS.register("gabbro_wall",
            () -> new BlockItem(ModBlocks.GABBRO_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_PERIDOTITE = ITEMS.register("polished_peridotite",
            () -> new BlockItem(ModBlocks.POLISHED_PERIDOTITE.get(), new Item.Properties()));
    public static final RegistryObject<Item> PERIDOTITE_SLAB = ITEMS.register("peridotite_slab",
            () -> new BlockItem(ModBlocks.PERIDOTITE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> PERIDOTITE_STAIRS = ITEMS.register("peridotite_stairs",
            () -> new BlockItem(ModBlocks.PERIDOTITE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> PERIDOTITE_WALL = ITEMS.register("peridotite_wall",
            () -> new BlockItem(ModBlocks.PERIDOTITE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_SERPENTINITE = ITEMS.register("polished_serpentinite",
            () -> new BlockItem(ModBlocks.POLISHED_SERPENTINITE.get(), new Item.Properties()));
    public static final RegistryObject<Item> SERPENTINITE_SLAB = ITEMS.register("serpentinite_slab",
            () -> new BlockItem(ModBlocks.SERPENTINITE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> SERPENTINITE_STAIRS = ITEMS.register("serpentinite_stairs",
            () -> new BlockItem(ModBlocks.SERPENTINITE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> SERPENTINITE_WALL = ITEMS.register("serpentinite_wall",
            () -> new BlockItem(ModBlocks.SERPENTINITE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_SCHIST = ITEMS.register("polished_schist",
            () -> new BlockItem(ModBlocks.POLISHED_SCHIST.get(), new Item.Properties()));
    public static final RegistryObject<Item> SCHIST_SLAB = ITEMS.register("schist_slab",
            () -> new BlockItem(ModBlocks.SCHIST_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> SCHIST_STAIRS = ITEMS.register("schist_stairs",
            () -> new BlockItem(ModBlocks.SCHIST_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> SCHIST_WALL = ITEMS.register("schist_wall",
            () -> new BlockItem(ModBlocks.SCHIST_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_GNEISS = ITEMS.register("polished_gneiss",
            () -> new BlockItem(ModBlocks.POLISHED_GNEISS.get(), new Item.Properties()));
    public static final RegistryObject<Item> GNEISS_SLAB = ITEMS.register("gneiss_slab",
            () -> new BlockItem(ModBlocks.GNEISS_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> GNEISS_STAIRS = ITEMS.register("gneiss_stairs",
            () -> new BlockItem(ModBlocks.GNEISS_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> GNEISS_WALL = ITEMS.register("gneiss_wall",
            () -> new BlockItem(ModBlocks.GNEISS_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_SLATE = ITEMS.register("polished_slate",
            () -> new BlockItem(ModBlocks.POLISHED_SLATE.get(), new Item.Properties()));
    public static final RegistryObject<Item> SLATE_SLAB = ITEMS.register("slate_slab",
            () -> new BlockItem(ModBlocks.SLATE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> SLATE_STAIRS = ITEMS.register("slate_stairs",
            () -> new BlockItem(ModBlocks.SLATE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> SLATE_WALL = ITEMS.register("slate_wall",
            () -> new BlockItem(ModBlocks.SLATE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_MARBLE = ITEMS.register("polished_marble",
            () -> new BlockItem(ModBlocks.POLISHED_MARBLE.get(), new Item.Properties()));
    public static final RegistryObject<Item> MARBLE_SLAB = ITEMS.register("marble_slab",
            () -> new BlockItem(ModBlocks.MARBLE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> MARBLE_STAIRS = ITEMS.register("marble_stairs",
            () -> new BlockItem(ModBlocks.MARBLE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> MARBLE_WALL = ITEMS.register("marble_wall",
            () -> new BlockItem(ModBlocks.MARBLE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_QUARTZITE = ITEMS.register("polished_quartzite",
            () -> new BlockItem(ModBlocks.POLISHED_QUARTZITE.get(), new Item.Properties()));
    public static final RegistryObject<Item> QUARTZITE_SLAB = ITEMS.register("quartzite_slab",
            () -> new BlockItem(ModBlocks.QUARTZITE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> QUARTZITE_STAIRS = ITEMS.register("quartzite_stairs",
            () -> new BlockItem(ModBlocks.QUARTZITE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> QUARTZITE_WALL = ITEMS.register("quartzite_wall",
            () -> new BlockItem(ModBlocks.QUARTZITE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_SHALE = ITEMS.register("polished_shale",
            () -> new BlockItem(ModBlocks.POLISHED_SHALE.get(), new Item.Properties()));
    public static final RegistryObject<Item> SHALE_SLAB = ITEMS.register("shale_slab",
            () -> new BlockItem(ModBlocks.SHALE_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> SHALE_STAIRS = ITEMS.register("shale_stairs",
            () -> new BlockItem(ModBlocks.SHALE_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> SHALE_WALL = ITEMS.register("shale_wall",
            () -> new BlockItem(ModBlocks.SHALE_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> POLISHED_CHERT = ITEMS.register("polished_chert",
            () -> new BlockItem(ModBlocks.POLISHED_CHERT.get(), new Item.Properties()));
    public static final RegistryObject<Item> CHERT_SLAB = ITEMS.register("chert_slab",
            () -> new BlockItem(ModBlocks.CHERT_SLAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> CHERT_STAIRS = ITEMS.register("chert_stairs",
            () -> new BlockItem(ModBlocks.CHERT_STAIRS.get(), new Item.Properties()));
    public static final RegistryObject<Item> CHERT_WALL = ITEMS.register("chert_wall",
            () -> new BlockItem(ModBlocks.CHERT_WALL.get(), new Item.Properties()));

    public static final RegistryObject<Item> MICROBIAL_MAT_ORANGE = ITEMS.register("microbial_mat_orange",
            () -> new BlockItem(ModBlocks.MICROBIAL_MAT_ORANGE.get(), new Item.Properties()));

    public static final RegistryObject<Item> MICROBIAL_MAT_YELLOW = ITEMS.register("microbial_mat_yellow",
            () -> new BlockItem(ModBlocks.MICROBIAL_MAT_YELLOW.get(), new Item.Properties()));

    public static final RegistryObject<Item> MICROBIAL_MAT_BROWN = ITEMS.register("microbial_mat_brown",
            () -> new BlockItem(ModBlocks.MICROBIAL_MAT_BROWN.get(), new Item.Properties()));

    public static final RegistryObject<Item> MICROBIAL_MAT_GREEN = ITEMS.register("microbial_mat_green",
            () -> new BlockItem(ModBlocks.MICROBIAL_MAT_GREEN.get(), new Item.Properties()));

    // --- Instruments ---------------------------------------------------------
    //
    // These are the first things in the mod a player holds rather than stumbles across. Everything
    // the simulation knows was previously reachable only through /geology, which means it was
    // reachable only by someone who already knew the mod was there. An instrument makes the model
    // discoverable, and in a classroom it is the difference between a demonstration and a lesson.

    public static final RegistryObject<Item> SEISMOGRAPH = ITEMS.register("seismograph",
            () -> new BlockItem(ModBlocks.SEISMOGRAPH.get(), new Item.Properties()));

    /**
     * Reads a rock and the beds under it. Durable, because it is struck against stone.
     *
     * <p>No {@code stacksTo(1)} here: {@code durability()} already sets the stack size to one, and
     * asking for both throws "Unable to have damage AND stack" - at <em>registration</em> time, so
     * it compiles cleanly and then takes the whole mod down on launch. Caught by booting a
     * dedicated server rather than by the compiler, which is the argument for doing that every
     * time an item is added.</p>
     */
    public static final RegistryObject<Item> GEOLOGISTS_HAMMER = ITEMS.register("geologists_hammer",
            () -> new GeologistsHammerItem(new Item.Properties().durability(256)));

    /** Strike of the nearest boundary, and what the two plates are doing across it. */
    public static final RegistryObject<Item> FAULT_COMPASS = ITEMS.register("fault_compass",
            () -> new FaultCompassItem(new Item.Properties().stacksTo(1)));

    /** In-game field guide documenting landscape reading, instruments, hydrothermal systems, volcanoes, earthquakes, and commands. */
    public static final RegistryObject<Item> FIELD_GUIDE = ITEMS.register("field_guide",
            () -> new FieldGuideItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}
}
