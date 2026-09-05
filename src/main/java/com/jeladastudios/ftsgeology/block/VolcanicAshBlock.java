package com.jeladastudios.ftsgeology.block;

import net.minecraft.core.BlockPos;
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
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ash lying on the ground after an eruption, in layers like snow.
 *
 * <h2>Why a layer and not a block of ground</h2>
 * The ash fall shipped first by <b>replacing</b> the surface block: grass became tuff, gravel or
 * coarse dirt out to a hundred blocks downwind. It worked, and testing found the flaw at once - the
 * volcano ate itself. The cone is inside its own fall radius, basalt reads as natural terrain rather
 * than as somebody's build, so the mountain's own rock was quietly turned into dirt and gravel by
 * its own eruption.
 *
 * <p>Patching the radius would have missed the real mistake, which is that an ash fall does not
 * <i>replace</i> anything. It settles on top. Everything wanted here follows from getting that
 * right: the grass survives underneath, the cone stays basalt and merely goes grey, a second
 * eruption thickens the deposit instead of re-stamping it, and a player can shovel a path through
 * it. The fall could not do any of those while it was rewriting the ground.</p>
 *
 * <p>This is vanilla's snow layer with the melting taken out - ash does not thaw, it gets buried.
 * Nothing here is a new mechanic, which is the point: it behaves the way players already expect a
 * thin covering to behave.</p>
 */
public class VolcanicAshBlock extends Block {

    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;   // 1..8

    private static final VoxelShape[] SHAPES = new VoxelShape[9];
    static {
        for (int i = 0; i <= 8; i++) {
            SHAPES[i] = Block.box(0.0D, 0.0D, 0.0D, 16.0D, i * 2.0D, 16.0D);
        }
    }

    public VolcanicAshBlock(Properties properties) {
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
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext ctx) {
        // One layer is walked over rather than climbed, as with snow: a dusting should not trip you.
        return SHAPES[state.getValue(LAYERS) - 1];
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos,
                                     CollisionContext ctx) {
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

    /**
     * Ash needs something under it. It settles out of the air onto whatever is there, so the test is
     * simply whether that surface can hold it - not what the surface is made of.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (below.is(Blocks.ICE) || below.is(Blocks.PACKED_ICE) || below.is(Blocks.BARRIER)) return false;
        if (below.isAir()) return false;
        return below.isFaceSturdy(level, pos.below(), Direction.UP)
                || (below.is(this) && below.getValue(LAYERS) == 8);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return canSurvive(state, level, pos)
                ? super.updateShape(state, dir, neighbour, level, pos, neighbourPos)
                : Blocks.AIR.defaultBlockState();
    }

    /** More ash onto ash deepens it, up to a full block. */
    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext ctx) {
        int layers = state.getValue(LAYERS);
        if (ctx.getItemInHand().is(asItem()) && layers < 8) {
            return ctx.replacingClickedOnBlock() ? ctx.getClickedFace() == Direction.UP : true;
        }
        return layers == 1;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState here = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (here.is(this)) {
            return here.setValue(LAYERS, Math.min(8, here.getValue(LAYERS) + 1));
        }
        return super.getStateForPlacement(ctx);
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                                  PathComputationType type) {
        return type == PathComputationType.LAND && state.getValue(LAYERS) < 5;
    }
}
