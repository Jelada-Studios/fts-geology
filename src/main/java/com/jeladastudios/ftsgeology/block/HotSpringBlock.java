package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.blockentity.HotSpringBlockEntity;
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
 * The bed of a hot spring — sits under a pool of water and radiates warmth (see
 * {@link HotSpringBlockEntity}). Generated at the bottom of natural hot-spring pools, and also
 * placeable so players can build their own spa (put water on top for the effect).
 */
public class HotSpringBlock extends BaseEntityBlock {

    public HotSpringBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HotSpringBlockEntity(pos, state);
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
        return createTickerHelper(type, ModBlockEntities.HOT_SPRING.get(),
                HotSpringBlockEntity::serverTick);
    }

    /** The spring welling up through its bed: a stream of bubbles, and now and then its burble. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
        if (!level.getFluidState(pos.above()).is(net.minecraft.tags.FluidTags.WATER)) return;
        if (random.nextInt(3) == 0) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, pos.getX() + 0.3 + random.nextDouble() * 0.4,
                    pos.getY() + 1.05, pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0.0, 0.06 + random.nextDouble() * 0.04, 0.0);
        }
        if (random.nextInt(40) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    net.minecraft.sounds.SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, net.minecraft.sounds.SoundSource.BLOCKS,
                    0.25f + random.nextFloat() * 0.15f, 0.8f + random.nextFloat() * 0.3f, false);
        }
    }
}
