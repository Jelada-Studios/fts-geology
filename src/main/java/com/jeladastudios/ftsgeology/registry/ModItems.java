package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.item.FaultCompassItem;
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

    private ModItems() {}
}
