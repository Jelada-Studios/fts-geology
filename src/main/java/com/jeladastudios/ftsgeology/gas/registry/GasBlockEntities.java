package com.jeladastudios.ftsgeology.gas.registry;
import net.neoforged.neoforge.registries.DeferredHolder;


import net.minecraft.core.registries.BuiltInRegistries;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.block.entity.BiogasDigesterBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.ElectrolyzerBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasCompressorBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasEngineBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasHeaterBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasPipeBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasSensorBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasSeparatorBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasTankBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.GasValveBlockEntity;
import com.jeladastudios.ftsgeology.gas.block.entity.VentilationFanBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class GasBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, GeysersMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasPipeBlockEntity>> GAS_PIPE =
            register("gas_pipe", GasPipeBlockEntity::new, GasBlocks.GAS_PIPE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasTankBlockEntity>> GAS_TANK =
            register("gas_tank", GasTankBlockEntity::new, GasBlocks.GAS_TANK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasValveBlockEntity>> GAS_VALVE =
            register("gas_valve", GasValveBlockEntity::new, GasBlocks.GAS_VALVE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasCompressorBlockEntity>> GAS_COMPRESSOR =
            register("gas_compressor", GasCompressorBlockEntity::new, GasBlocks.GAS_COMPRESSOR);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasEngineBlockEntity>> GAS_ENGINE =
            register("gas_engine", GasEngineBlockEntity::new, GasBlocks.GAS_ENGINE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ElectrolyzerBlockEntity>> ELECTROLYZER =
            register("electrolyzer", ElectrolyzerBlockEntity::new, GasBlocks.ELECTROLYZER);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BiogasDigesterBlockEntity>> BIOGAS_DIGESTER =
            register("biogas_digester", BiogasDigesterBlockEntity::new, GasBlocks.BIOGAS_DIGESTER);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasSeparatorBlockEntity>> GAS_SEPARATOR =
            register("gas_separator", GasSeparatorBlockEntity::new, GasBlocks.GAS_SEPARATOR);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasHeaterBlockEntity>> GAS_HEATER =
            register("gas_heater", GasHeaterBlockEntity::new, GasBlocks.GAS_HEATER);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasSensorBlockEntity>> GAS_SENSOR =
            register("gas_sensor", GasSensorBlockEntity::new, GasBlocks.GAS_SENSOR);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VentilationFanBlockEntity>> VENTILATION_FAN =
            register("ventilation_fan", VentilationFanBlockEntity::new, GasBlocks.VENTILATION_FAN);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.jeladastudios.ftsgeology.gas.block.entity.GasSeepBlockEntity>> GAS_SEEP =
            register("gas_seep", com.jeladastudios.ftsgeology.gas.block.entity.GasSeepBlockEntity::new, GasBlocks.GAS_SEEP);

    private static <T extends BlockEntity> DeferredHolder<BlockEntityType<?>, BlockEntityType<T>> register(
            String name, BlockEntityType.BlockEntitySupplier<T> factory, Supplier<? extends Block> block) {
        return BLOCK_ENTITIES.register(name, () -> BlockEntityType.Builder.of(factory, block.get()).build(null));
    }

    private GasBlockEntities() {
    }
}
