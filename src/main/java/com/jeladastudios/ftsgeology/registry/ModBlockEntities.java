package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.GeyserChamberBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.GeyserIgniterBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.SpringSourceBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.HotSpringBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.VolcanoCoreBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.VolcanoIgniterBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GeysersMod.MODID);

    public static final RegistryObject<BlockEntityType<GeyserCoreBlockEntity>> GEYSER_CORE =
            BLOCK_ENTITIES.register("geyser_core", () -> BlockEntityType.Builder
                    .of(GeyserCoreBlockEntity::new, ModBlocks.GEYSER_CORE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<GeyserChamberBlockEntity>> GEYSER_CHAMBER =
            BLOCK_ENTITIES.register("geyser_chamber", () -> BlockEntityType.Builder
                    .of(GeyserChamberBlockEntity::new, ModBlocks.GEYSER_CHAMBER.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<GeyserIgniterBlockEntity>> GEYSER_IGNITER =
            BLOCK_ENTITIES.register("geyser_igniter", () -> BlockEntityType.Builder
                    .of(GeyserIgniterBlockEntity::new, ModBlocks.GEYSER_IGNITER.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<HotSpringBlockEntity>> HOT_SPRING =
            BLOCK_ENTITIES.register("hot_spring", () -> BlockEntityType.Builder
                    .of(HotSpringBlockEntity::new, ModBlocks.HOT_SPRING.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<SpringSourceBlockEntity>> SPRING_SOURCE =
            BLOCK_ENTITIES.register("spring_source", () -> BlockEntityType.Builder
                    .of(SpringSourceBlockEntity::new, ModBlocks.SPRING_SOURCE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<VolcanoCoreBlockEntity>> VOLCANO_CORE =
            BLOCK_ENTITIES.register("volcano_core", () -> BlockEntityType.Builder
                    .of(VolcanoCoreBlockEntity::new, ModBlocks.VOLCANO_CORE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<VolcanoIgniterBlockEntity>> VOLCANO_IGNITER =
            BLOCK_ENTITIES.register("volcano_igniter", () -> BlockEntityType.Builder
                    .of(VolcanoIgniterBlockEntity::new, ModBlocks.VOLCANO_IGNITER.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<SeismographBlockEntity>> SEISMOGRAPH =
            BLOCK_ENTITIES.register("seismograph", () -> BlockEntityType.Builder
                    .of(SeismographBlockEntity::new, ModBlocks.SEISMOGRAPH.get())
                    .build(null));

    private ModBlockEntities() {}
}
