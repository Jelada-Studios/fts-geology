package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
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

    private ModItems() {}
}
