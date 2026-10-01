package com.jeladastudios.ftsgeology.gas.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class GasTags {
    /** Blocks that ignite flammable gas (blocks with a LIT property only when lit). */
    public static final TagKey<Block> IGNITION_SOURCES = block("ignition_sources");
    /** Full blocks that still let gas through (leaves, scaffolding...). */
    public static final TagKey<Block> GAS_PERMEABLE = block("gas_permeable");
    /** Mining these can strike sparks. */
    public static final TagKey<Block> SPARKING_BLOCKS = block("sparking_blocks");

    /** Organic matter accepted by the biogas digester. */
    public static final TagKey<Item> BIOMASS = item("biomass");


    /** Entities that do not breathe. */
    public static final TagKey<EntityType<?>> GAS_IMMUNE = TagKey.create(Registries.ENTITY_TYPE, id("gas_immune"));

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK, id(name));
    }

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM, id(name));
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation(GeysersMod.MODID, name);
    }

    private GasTags() {
    }
}
