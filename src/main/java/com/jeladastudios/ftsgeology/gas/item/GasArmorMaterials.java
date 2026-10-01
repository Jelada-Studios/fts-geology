package com.jeladastudios.ftsgeology.gas.item;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.registry.GasItems;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.function.Supplier;

public enum GasArmorMaterials implements ArmorMaterial {
    /** Durability = filter life in seconds of toxic exposure. */
    GAS_MASK("gas_mask", 900, 1, 0, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(GasItems.FILTER.get())),
    BREATHING_APPARATUS("breathing_apparatus", 400, 3, 0, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(Items.IRON_INGOT));

    private final String name;
    private final int durability;
    private final int defense;
    private final float toughness;
    private final SoundEvent sound;
    private final Supplier<Ingredient> repair;

    GasArmorMaterials(String name, int durability, int defense, float toughness, SoundEvent sound, Supplier<Ingredient> repair) {
        this.name = name;
        this.durability = durability;
        this.defense = defense;
        this.toughness = toughness;
        this.sound = sound;
        this.repair = repair;
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        return durability;
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return defense;
    }

    @Override
    public int getEnchantmentValue() {
        return 9;
    }

    @Override
    public SoundEvent getEquipSound() {
        return sound;
    }

    @Override
    public Ingredient getRepairIngredient() {
        return repair.get();
    }

    @Override
    public String getName() {
        // Worn, they are drawn with vanilla's armour textures until their own are drawn.
        return this == GAS_MASK ? "minecraft:chainmail" : "minecraft:iron";
    }

    @Override
    public float getToughness() {
        return toughness;
    }

    @Override
    public float getKnockbackResistance() {
        return 0;
    }
}
