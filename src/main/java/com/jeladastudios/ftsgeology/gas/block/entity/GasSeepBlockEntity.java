package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasConfig;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.block.GasSeepBlock;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import com.jeladastudios.ftsgeology.gas.world.GasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The seep's gas, once a second: into the air over it, or burnt there with that air's oxygen when it is lit. The gas of
 * the Chimaera is nearly all methane with a tenth of hydrogen; a few tenths of a mole a second is a flame of a few tens of
 * kilowatts, a camp fire's.
 */
public class GasSeepBlockEntity extends BlockEntity {

    /** Moles a second, and what they are. */
    private static final double FLOW = 0.3, METHANE = 0.87, HYDROGEN = 0.09;

    public GasSeepBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.GAS_SEEP.get(), pos, state);
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel sl) || (sl.getGameTime() + worldPosition.asLong()) % 20 != 0) return;
        if (!GasConfig.ENABLED.get()) return;
        GasManager gm = GasManager.get(sl);
        BlockPos above = worldPosition.above();
        if (!gm.isLoaded(above) || !gm.simulated(above.getX(), above.getZ())) return;
        BlockState state = getBlockState();
        boolean lit = state.getValue(GasSeepBlock.LIT);
        if (gm.isGasTight(sl.getBlockState(above), above)) {
            if (lit) setLit(sl, state, false);
            return;
        }
        GasMix stream = new GasMix();
        stream.add(Gas.METHANE, FLOW * METHANE);
        stream.add(Gas.HYDROGEN, FLOW * HYDROGEN);
        stream.add(Gas.NITROGEN, FLOW * (1.0 - METHANE - HYDROGEN));
        if (lit) {
            GasMix cell = gm.getOrCreateCell(above);
            if (cell == null) return;
            if (GasManager.putsOutFlames(cell)) {
                setLit(sl, state, false);                             // too little air to burn in: the flame goes out
                gm.release(above, stream);
                return;
            }
            double heat = Combustion.heatContent(stream);
            double released = Combustion.burnWithAir(stream, cell, cell.total() * 0.35);
            gm.markDirty(above);
            if (released < heat * 0.3) setLit(sl, state, false);      // no air to burn in: the flame chokes
            else burnAround(sl, above);
        }
        // The flame's products, or the gas itself while the seep is out.
        gm.release(above, stream);
    }

    private void setLit(ServerLevel sl, BlockState state, boolean lit) {
        sl.setBlock(worldPosition, state.setValue(GasSeepBlock.LIT, lit), Block.UPDATE_ALL);
        if (!lit) sl.playSound(null, worldPosition, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.0f);
    }

    /** What stands in the flame burns. */
    private void burnAround(ServerLevel sl, BlockPos above) {
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, new AABB(above).inflate(0.1, 0.4, 0.1))) {
            if (!e.fireImmune()) e.setSecondsOnFire(4);
        }
    }
}
