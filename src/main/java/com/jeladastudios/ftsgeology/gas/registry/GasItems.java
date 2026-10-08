package com.jeladastudios.ftsgeology.gas.registry;


import net.minecraft.core.registries.BuiltInRegistries;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.item.BreathingApparatusItem;
import com.jeladastudios.ftsgeology.gas.item.GasCameraItem;
import com.jeladastudios.ftsgeology.gas.item.GasDetectorItem;
import com.jeladastudios.ftsgeology.gas.item.GasMaskItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.ForgeRegistries;
import net.neoforged.neoforge.registries.RegistryObject;

public final class GasItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, GeysersMod.MODID);

    public static final RegistryObject<Item> GAS_DETECTOR = ITEMS.register("gas_detector",
            () -> new GasDetectorItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> GAS_CAMERA = ITEMS.register("gas_camera",
            () -> new GasCameraItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> GAS_MASK = ITEMS.register("gas_mask",
            () -> new GasMaskItem(new Item.Properties()));
    public static final RegistryObject<Item> FILTER = ITEMS.register("gas_filter",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> BREATHING_APPARATUS = ITEMS.register("breathing_apparatus",
            () -> new BreathingApparatusItem(new Item.Properties()));

    private GasItems() {
    }
}
