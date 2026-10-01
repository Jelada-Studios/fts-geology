package com.jeladastudios.ftsgeology.gas.block;

import com.jeladastudios.ftsgeology.gas.block.entity.GasTankBlockEntity;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Pressure vessel. Pipes connect on every side. Ruptures (and releases its gas) when blown up. */
public class GasTankBlock extends MachineBlock {
    public GasTankBlock(Properties props) {
        super(props, GasBlockEntities.GAS_TANK);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof GasTankBlockEntity be) {
            double f = be.tank().pressure() / be.tank().maxPressure;
            return f <= 0.001 ? 0 : 1 + (int) Math.min(14, f * 14);
        }
        return 0;
    }

    @Override
    public boolean dropFromExplosion(Explosion explosion) {
        return false;
    }

    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof GasTankBlockEntity be) be.rupture();
        super.onBlockExploded(state, level, pos, explosion);
    }
}
