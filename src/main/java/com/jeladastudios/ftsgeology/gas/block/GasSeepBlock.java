package com.jeladastudios.ftsgeology.gas.block;

import com.jeladastudios.ftsgeology.gas.block.entity.GasSeepBlockEntity;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * A natural gas seep in serpentinite: methane and a little hydrogen from the rock itself, made where sea water works on
 * the mantle rock of an ophiolite -- the Chimaera of Lycia (Yanartas), whose flames have burned out of the hillside for
 * thousands of years. Lit, it burns where the gas comes out, in the air over it; out, its gas goes into the air. Flint
 * and steel or a fire charge lights it, and so does anything that lights gas; a want of air puts it out.
 */
public class GasSeepBlock extends BaseEntityBlock {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public GasSeepBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(LIT, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(LIT);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GasSeepBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> t) {
        if (level.isClientSide || t != GasBlockEntities.GAS_SEEP.get()) return null;
        return (l, p, s, be) -> ((GasSeepBlockEntity) be).serverTick();
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (state.getValue(LIT) || !(held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE))) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        level.setBlock(pos, state.setValue(LIT, true), Block.UPDATE_ALL);
        level.playSound(null, pos, held.is(Items.FIRE_CHARGE) ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE,
                SoundSource.BLOCKS, 1.0f, 1.0f);
        if (held.is(Items.FIRE_CHARGE)) {
            if (!player.getAbilities().instabuild) held.shrink(1);
        } else {
            held.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
        }
        return InteractionResult.CONSUME;
    }

    /** Standing on a lit seep is standing in its flame. */
    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (state.getValue(LIT) && !entity.fireImmune() && !entity.isSteppingCarefully()) entity.setSecondsOnFire(3);
        super.stepOn(level, pos, state, entity);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
        if (!state.getValue(LIT)) return;
        for (int i = 0; i < 3; i++) {
            double x = pos.getX() + 0.25 + r.nextDouble() * 0.5, z = pos.getZ() + 0.25 + r.nextDouble() * 0.5;
            level.addParticle(i == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, x, pos.getY() + 1.02, z,
                    0.0, 0.03 + r.nextDouble() * 0.04, 0.0);
        }
        if (r.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5, 0.0, 0.03, 0.0);
        }
        if (r.nextInt(30) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, SoundEvents.FIRE_AMBIENT,
                    SoundSource.BLOCKS, 0.6f, 0.8f + r.nextFloat() * 0.3f, false);
        }
    }
}
