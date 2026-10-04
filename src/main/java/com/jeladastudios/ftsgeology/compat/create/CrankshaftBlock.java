package com.jeladastudios.ftsgeology.compat.create;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The crankshaft's end and its bearing, set on a gas engine at either end of its flywheel's axle: the engine then turns
 * a Create shaft out of the far face instead of making only FE. Gas engines do that work in the world (they drive
 * compressors, pumps and generators from the crank). Faces away from what it was set against.
 */
public class CrankshaftBlock extends DirectionalKineticBlock implements IBE<CrankshaftBlockEntity> {

    private static final VoxelShape NORTH = Block.box(1, 1, 6, 15, 15, 16), SOUTH = Block.box(1, 1, 0, 15, 15, 10),
            EAST = Block.box(0, 1, 1, 10, 15, 15), WEST = Block.box(6, 1, 1, 16, 15, 15),
            UP = Block.box(1, 0, 1, 15, 10, 15), DOWN = Block.box(1, 6, 1, 15, 16, 15);

    public CrankshaftBlock(Properties props) {
        super(props);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public Class<CrankshaftBlockEntity> getBlockEntityClass() {
        return CrankshaftBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CrankshaftBlockEntity> getBlockEntityType() {
        return CreateCrank.CRANKSHAFT_BE.get();
    }
}
