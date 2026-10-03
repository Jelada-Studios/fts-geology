package com.jeladastudios.ftsgeology.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thin sheet of basalt, in layers like snow: lava that ran out thin and set where it lay.
 *
 * <p>A lava flow is metres thick where it pooled and centimetres at its toes. Set into whole blocks, every film of lava
 * a flow left behind (Flowing Fluids keeps lava as finite volumes, down to an eighth of a block) became a block of rock,
 * and a fissure eruption buried its valley under several times the rock its lava could have made. Lava that stood an
 * eighth to seven eighths of a block deep sets into as many layers of this instead (see {@code LavaSetting}).</p>
 *
 * <p>Rock, not a covering: it is solid, so water flowing over it does not wash it off as it would snow, and it does
 * not wear away. It needs a firm surface under it, as a layer of snow does.</p>
 */
public class BasaltLayerBlock extends Block {

    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;   // 1..8

    private static final VoxelShape[] SHAPES = new VoxelShape[9];
    static {
        for (int i = 0; i <= 8; i++) SHAPES[i] = Block.box(0.0D, 0.0D, 0.0D, 16.0D, i * 2.0D, 16.0D);
    }

    public BasaltLayerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LAYERS, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(LAYERS);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(LAYERS) == 8 ? 0.2F : 1.0F;
    }

    /** Whether a sheet of rock can lie on what is under {@code pos}: a firm top, or a full stack of itself. */
    public static boolean firmUnder(LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (below.isAir() || !below.getFluidState().isEmpty()) return false;
        return below.isFaceSturdy(level, pos.below(), Direction.UP)
                || below.getBlock() instanceof BasaltLayerBlock && below.getValue(LAYERS) == 8;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return firmUnder(level, pos);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos,
                                  BlockPos neighbourPos) {
        return canSurvive(state, level, pos) ? super.updateShape(state, dir, neighbour, level, pos, neighbourPos)
                : Blocks.AIR.defaultBlockState();
    }

    /** More of it onto it thickens it, up to a full block. */
    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext ctx) {
        if (ctx.getItemInHand().is(asItem()) && state.getValue(LAYERS) < 8) {
            return !ctx.replacingClickedOnBlock() || ctx.getClickedFace() == Direction.UP;
        }
        return false;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState here = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (here.is(this)) return here.setValue(LAYERS, Math.min(8, here.getValue(LAYERS) + 1));
        return super.getStateForPlacement(ctx);
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return type == PathComputationType.LAND && state.getValue(LAYERS) < 5;
    }
}
