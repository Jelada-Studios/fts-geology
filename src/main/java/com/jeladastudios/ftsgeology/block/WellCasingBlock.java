package com.jeladastudios.ftsgeology.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A length of well casing: steel pipe set down a bored hole. A geothermal turbine draws its steam up a string of them
 * and reads its heat where the string ends; see {@link com.jeladastudios.ftsgeology.blockentity.GeothermalTurbineBlockEntity}.
 */
public class WellCasingBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 16, 11);

    public WellCasingBlock(Properties props) {
        super(props);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }
}
