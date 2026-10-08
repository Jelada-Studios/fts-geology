package com.jeladastudios.ftsgeology.gas.item;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.registry.GasItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * The gas mask's and the breathing apparatus's armour: a registry entry since 1.21, with the durability on the item.
 */
public final class GasArmorMaterials {

    private GasArmorMaterials() {}

    public static final DeferredRegister<ArmorMaterial> MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, GeysersMod.MODID);

    /** How many uses each lasts, set on the item. */
    public static final int GAS_MASK_DURABILITY = 900, BREATHING_APPARATUS_DURABILITY = 400;

    // Worn, they are drawn with vanilla's armour textures until their own are drawn.
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> GAS_MASK = MATERIALS.register("gas_mask",
            () -> new ArmorMaterial(defense(1), 9, SoundEvents.ARMOR_EQUIP_LEATHER,
                    () -> Ingredient.of(GasItems.FILTER.get()),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.withDefaultNamespace("chainmail"))), 0f, 0f));

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> BREATHING_APPARATUS = MATERIALS.register("breathing_apparatus",
            () -> new ArmorMaterial(defense(3), 9, SoundEvents.ARMOR_EQUIP_IRON,
                    () -> Ingredient.of(Items.IRON_INGOT),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.withDefaultNamespace("iron"))), 0f, 0f));

    /** The same defense for every piece: each material makes one piece only. */
    private static EnumMap<ArmorItem.Type, Integer> defense(int points) {
        EnumMap<ArmorItem.Type, Integer> m = new EnumMap<>(ArmorItem.Type.class);
        for (ArmorItem.Type t : ArmorItem.Type.values()) m.put(t, points);
        return m;
    }
}
