package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.blockentity.GeothermalTurbineBlockEntity;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * A geothermal turbine. Set on a well lined with casing down into a hot water reservoir, it makes Forge Energy from
 * the heat the well reaches and hands it to whatever is beside it or on top of it; see
 * {@link GeothermalTurbineBlockEntity}. Right-click reads what it is making and why. While it runs, its rotor turns --
 * the faster, the more it makes -- and steam comes off its top.
 */
public class GeothermalTurbineBlock extends BaseEntityBlock {

    /** How hard it runs, in four steps: 0 stood still, 3 flat out. The rotor's speed and the steam follow it. */
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 3);

    public GeothermalTurbineBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(POWER);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GeothermalTurbineBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.GEOTHERMAL_TURBINE.get(), level.isClientSide
                ? GeothermalTurbineBlockEntity::clientTick : GeothermalTurbineBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof GeothermalTurbineBlockEntity be)) return InteractionResult.PASS;
        for (Component c : be.report()) player.sendSystemMessage(c);
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        int power = state.getValue(POWER);
        if (power == 0) return;
        // Up through the rotor, out of the open top.
        double x = pos.getX() + 0.5, y = pos.getY() + 0.9, z = pos.getZ() + 0.5;
        for (int i = 0; i < power; i++) {
            level.addParticle(ParticleTypes.CLOUD, x + (random.nextDouble() - 0.5) * 0.4, y,
                    z + (random.nextDouble() - 0.5) * 0.4, 0.0, 0.06 + random.nextDouble() * 0.04, 0.0);
        }
        if (random.nextInt(40) == 0) {
            level.playLocalSound(x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.15f,
                    0.6f + random.nextFloat() * 0.3f, false);
        }
    }
}
