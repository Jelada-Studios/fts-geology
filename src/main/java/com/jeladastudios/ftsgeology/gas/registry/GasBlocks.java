package com.jeladastudios.ftsgeology.gas.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.block.GasPipeBlock;
import com.jeladastudios.ftsgeology.gas.block.GasSensorBlock;
import com.jeladastudios.ftsgeology.gas.block.GasTankBlock;
import com.jeladastudios.ftsgeology.gas.block.GasValveBlock;
import com.jeladastudios.ftsgeology.gas.block.OrientedMachineBlock;
import com.jeladastudios.ftsgeology.gas.item.GasTankItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Function;
import java.util.function.Supplier;

public final class GasBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GeysersMod.MODID);

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f, 6.0f).sound(SoundType.METAL);
    }

    private static BlockBehaviour.Properties glowing(int light) {
        return metal().lightLevel(s -> s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT) ? light : 0);
    }

    public static final RegistryObject<Block> GAS_PIPE = register("gas_pipe",
            () -> new GasPipeBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1.5f, 4.0f)
                    .sound(SoundType.COPPER).noOcclusion()));
    public static final RegistryObject<Block> GAS_TANK = register("gas_tank",
            () -> new GasTankBlock(metal().strength(4.0f, 8.0f)), b -> new GasTankItem(b, new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Block> GAS_VALVE = register("gas_valve",
            () -> new GasValveBlock(glowing(15)));
    public static final RegistryObject<Block> GAS_COMPRESSOR = register("gas_compressor",
            () -> new OrientedMachineBlock.AllDirections(metal(), GasBlockEntities.GAS_COMPRESSOR));
    public static final RegistryObject<Block> GAS_ENGINE = register("gas_engine",
            () -> new OrientedMachineBlock(glowing(7), GasBlockEntities.GAS_ENGINE, false));
    public static final RegistryObject<Block> ELECTROLYZER = register("electrolyzer",
            () -> new OrientedMachineBlock(glowing(4), GasBlockEntities.ELECTROLYZER, false));
    public static final RegistryObject<Block> BIOGAS_DIGESTER = register("biogas_digester",
            () -> new OrientedMachineBlock(metal(), GasBlockEntities.BIOGAS_DIGESTER, false));
    public static final RegistryObject<Block> GAS_SEPARATOR = register("gas_separator",
            () -> new OrientedMachineBlock(metal(), GasBlockEntities.GAS_SEPARATOR, false));
    public static final RegistryObject<Block> GAS_SENSOR = register("gas_sensor",
            () -> new GasSensorBlock(metal().strength(2.0f, 4.0f)));
    public static final RegistryObject<Block> VENTILATION_FAN = register("ventilation_fan",
            () -> new OrientedMachineBlock.AllDirections(metal(), GasBlockEntities.VENTILATION_FAN));
    /** A burning methane seep in serpentinite (the Chimaera); its rock drops when it is broken. */
    public static final RegistryObject<Block> GAS_SEEP = register("gas_seep",
            () -> new com.jeladastudios.ftsgeology.gas.block.GasSeepBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN)
                    .requiresCorrectToolForDrops().strength(2.0f, 6.0f).sound(SoundType.STONE)
                    .lightLevel(s -> s.getValue(BlockStateProperties.LIT) ? 12 : 0)));

    private static RegistryObject<Block> register(String name, Supplier<Block> block) {
        return register(name, block, b -> new BlockItem(b, new Item.Properties()));
    }

    private static RegistryObject<Block> register(String name, Supplier<Block> block, Function<Block, Item> item) {
        RegistryObject<Block> obj = BLOCKS.register(name, block);
        GasItems.ITEMS.register(name, () -> item.apply(obj.get()));
        return obj;
    }

    private GasBlocks() {
    }
}
