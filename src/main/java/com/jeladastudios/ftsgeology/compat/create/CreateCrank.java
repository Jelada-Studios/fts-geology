package com.jeladastudios.ftsgeology.compat.create;
import net.neoforged.neoforge.registries.DeferredHolder;


import net.minecraft.core.registries.BuiltInRegistries;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.registry.GasTabs;
import com.jeladastudios.ftsgeology.item.DescribedBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The blocks that join the gases to Create (MIT), registered only where Create is installed: this package is reached
 * from the rest of the mod by name alone ({@link #register}), so none of its classes, which stand on Create's, is ever
 * loaded without it.
 */
public final class CreateCrank {

    private CreateCrank() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, GeysersMod.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, GeysersMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, GeysersMod.MODID);

    public static final DeferredHolder<Block, Block> CRANKSHAFT = BLOCKS.register("crankshaft", () -> new CrankshaftBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f, 6.0f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredHolder<Item, Item> CRANKSHAFT_ITEM = ITEMS.register("crankshaft",
            () -> new DescribedBlockItem(CRANKSHAFT.get(), new Item.Properties(), 2));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrankshaftBlockEntity>> CRANKSHAFT_BE = BLOCK_ENTITIES.register("crankshaft",
            () -> BlockEntityType.Builder.of((pos, state) -> new CrankshaftBlockEntity(CreateCrank.CRANKSHAFT_BE.get(), pos, state),
                    CRANKSHAFT.get()).build(null));

    /** Called by name from the mod's constructor, only where Create is loaded. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(CreateCrank::onTabs);
        if (FMLEnvironment.dist == Dist.CLIENT) CreateCrankClient.register(modBus);
        GeysersMod.LOGGER.info("Gas engines turn Create's shafts through a crankshaft");
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == GasTabs.MAIN.getKey()) event.accept(CRANKSHAFT_ITEM.get());
    }
}
