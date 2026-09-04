package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.blockentity.SpringSourceBlockEntity;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The deep end of a hot spring: the part an earthquake cannot reach.
 *
 * <p>A hot spring used to be nothing but its pool, and the pool is surface furniture - a quake
 * strong enough to move the ground takes the whole thing away and there is nothing left to recover
 * from. That is backwards. Underground, a hydrothermal system is a plumbing problem: heated water
 * under pressure, looking for a way out. Shaking the ground rearranges the way out; it does not
 * remove the water or the heat.</p>
 *
 * <p>So this sits well below the reach of the quake code and does the only job that matters -
 * remembering that water wants to come up here - while {@link SpringSourceBlockEntity} works it
 * back to daylight. It is machinery rather than landscape, and like the geyser core it is
 * unbreakable, blast-proof and deliberately kept out of the natural-terrain list so a quake cannot
 * carve it away.</p>
 *
 * <p>It is never seen: it generates roughly 28 blocks down and stays sealed. The model borrows
 * deepslate rather than carrying a texture of its own for exactly that reason.</p>
 */
public class SpringSourceBlock extends BaseEntityBlock {

    public SpringSourceBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpringSourceBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.SPRING_SOURCE.get(),
                SpringSourceBlockEntity::serverTick);
    }
}
