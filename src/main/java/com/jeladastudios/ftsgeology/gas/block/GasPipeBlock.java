package com.jeladastudios.ftsgeology.gas.block;

import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import com.jeladastudios.ftsgeology.gas.registry.GasCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** Copper gas pipe. Connects to anything exposing a gas tank on the touching side. */
public class GasPipeBlock extends MachineBlock {
    public static final Map<Direction, BooleanProperty> PROPS = PipeBlock.PROPERTY_BY_DIRECTION;
    private static final VoxelShape CORE = Block.box(5, 5, 5, 11, 11, 11);
    private static final VoxelShape[] ARMS = {
            Block.box(5, 0, 5, 11, 5, 11),   // down
            Block.box(5, 11, 5, 11, 16, 11), // up
            Block.box(5, 5, 0, 11, 11, 5),   // north
            Block.box(5, 5, 11, 11, 11, 16), // south
            Block.box(0, 5, 5, 5, 11, 11),   // west
            Block.box(11, 5, 5, 16, 11, 11)  // east
    };
    private static final VoxelShape[] SHAPES = new VoxelShape[64];

    static {
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape s = CORE;
            for (int i = 0; i < 6; i++) {
                if ((mask & (1 << i)) != 0) s = Shapes.or(s, ARMS[i]);
            }
            SHAPES[mask] = s;
        }
    }

    public GasPipeBlock(Properties props) {
        super(props, GasBlockEntities.GAS_PIPE);
        BlockState def = stateDefinition.any();
        for (BooleanProperty p : PROPS.values()) def = def.setValue(p, false);
        registerDefaultState(def);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PROPS.values().toArray(new BooleanProperty[0]));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int mask = 0;
        for (Direction d : Direction.values()) {
            if (state.getValue(PROPS.get(d))) mask |= 1 << d.get3DDataValue();
        }
        return SHAPES[mask];
    }

    public static boolean connects(LevelAccessor level, BlockPos pos, Direction dir) {
        BlockPos n = pos.relative(dir);
        BlockEntity be = level.getBlockEntity(n);
        if (be == null) return false;
        // A furnace, a smoker or a blast furnace takes a pipe as its own gas burner (see gas.GasFurnaces).
        if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) return true;
        return be.getCapability(GasCapabilities.GAS_HANDLER, dir.getOpposite())
                .map(h -> h.connectsTo(dir.getOpposite())).orElse(false);
    }

    private BlockState withConnections(LevelAccessor level, BlockPos pos, BlockState state) {
        for (Direction d : Direction.values()) state = state.setValue(PROPS.get(d), connects(level, pos, d));
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return withConnections(ctx.getLevel(), ctx.getClickedPos(), defaultBlockState());
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(PROPS.get(dir), connects(level, pos, dir));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        super.neighborChanged(state, level, pos, block, from, moving);
        BlockState updated = withConnections(level, pos, state);
        if (updated != state) level.setBlock(pos, updated, 2);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
