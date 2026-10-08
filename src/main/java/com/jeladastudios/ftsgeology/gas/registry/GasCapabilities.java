package com.jeladastudios.ftsgeology.gas.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.ElectrodynamicsPower;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.IGasHandler;
import com.jeladastudios.ftsgeology.gas.block.entity.GasMachineBlockEntity;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.jetbrains.annotations.Nullable;

/**
 * The gases' block capability, and every capability the mod's blocks offer: gas, Forge Energy, fluids, items and,
 * where Voltaic is installed, Electrodynamics' electricity. On NeoForge a block entity no longer answers getCapability
 * itself; each type is registered here with what it offers on a side.
 */
public final class GasCapabilities {

    /** A block that holds or passes gas, on a side (null = the inside). */
    public static final BlockCapability<IGasHandler, @Nullable Direction> GAS_HANDLER = BlockCapability.createSided(
            ResourceLocation.fromNamespaceAndPath(GeysersMod.MODID, "gas_handler"), IGasHandler.class);

    /** Registered on the mod bus. */
    public static void register(RegisterCapabilitiesEvent event) {
        // The gas machines: gas on the sides they connect on, energy where they have it.
        for (DeferredHolder<BlockEntityType<?>, ? extends BlockEntityType<?>> type : GasBlockEntities.BLOCK_ENTITIES.getEntries()) {
            @SuppressWarnings("unchecked")
            BlockEntityType<GasMachineBlockEntity> machine = (BlockEntityType<GasMachineBlockEntity>) type.get();
            if (type.get() == GasBlockEntities.GAS_SEEP.get()) continue;
            event.registerBlockEntity(GAS_HANDLER, machine, GasMachineBlockEntity::gasHandlerOn);
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, machine, GasMachineBlockEntity::energyStorageOn);
            ElectrodynamicsPower.register(event, machine, GasMachineBlockEntity::voltsOn);
        }
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, GasBlockEntities.ELECTROLYZER.get(), (be, side) -> be.waterOn(side));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, GasBlockEntities.BIOGAS_DIGESTER.get(), (be, side) -> be.itemsOn(side));
        // The geothermal and oil machines.
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.GEOTHERMAL_TURBINE.get(), (be, side) -> be.energyOn(side));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.THERMOELECTRIC.get(), (be, side) -> be.energyOn(side));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.WELL_PUMP.get(), (be, side) -> be.energyOn(side));
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.WELL_PUMP.get(), (be, side) -> be.fluidOn(side));
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.WELLHEAD.get(), (be, side) -> be.fluidOn(side));
        event.registerBlockEntity(GAS_HANDLER, ModBlockEntities.WELLHEAD.get(), (be, side) -> be.gasOn(side));
        // Electrodynamics' electricity, where Voltaic is installed.
        ElectrodynamicsPower.register(event, ModBlockEntities.GEOTHERMAL_TURBINE.get(), (be, side) -> be.voltsOn(side));
        ElectrodynamicsPower.register(event, ModBlockEntities.THERMOELECTRIC.get(), (be, side) -> be.voltsOn(side));
        ElectrodynamicsPower.register(event, ModBlockEntities.WELL_PUMP.get(), (be, side) -> be.voltsOn(side));
    }

    @Nullable
    public static GasTank tankAt(Level level, BlockPos pos, @Nullable Direction side) {
        if (!level.isLoaded(pos)) return null;
        IGasHandler h = level.getCapability(GAS_HANDLER, pos, side);
        return h == null ? null : h.getTank(side);
    }

    @Nullable
    public static IGasHandler handlerAt(Level level, BlockPos pos, @Nullable Direction side) {
        if (!level.isLoaded(pos)) return null;
        return level.getCapability(GAS_HANDLER, pos, side);
    }

    private GasCapabilities() {
    }
}
