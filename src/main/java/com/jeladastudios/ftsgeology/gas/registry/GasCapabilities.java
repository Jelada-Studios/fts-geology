package com.jeladastudios.ftsgeology.gas.registry;

import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.IGasHandler;
import com.jeladastudios.ftsgeology.gas.world.GasChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;

public final class GasCapabilities {
    public static final Capability<IGasHandler> GAS_HANDLER = CapabilityManager.get(new CapabilityToken<>() {
    });

    public static void register(RegisterCapabilitiesEvent event) {
        event.register(IGasHandler.class);
        event.register(GasChunkData.class);
    }

    /** The gas tank a block exposes on {@code side}, or null. */
    @Nullable
    public static GasTank tankAt(Level level, BlockPos pos, @Nullable Direction side) {
        if (!level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        IGasHandler h = be.getCapability(GAS_HANDLER, side).orElse(null);
        return h == null ? null : h.getTank(side);
    }

    @Nullable
    public static IGasHandler handlerAt(Level level, BlockPos pos, @Nullable Direction side) {
        if (!level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        return be.getCapability(GAS_HANDLER, side).orElse(null);
    }

    private GasCapabilities() {
    }
}
