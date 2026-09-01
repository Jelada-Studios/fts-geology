package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.volcano.VolcanoBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Place this on a mountain top and, after a short charge, it carves an entire volcano beneath it —
 * crater lava lake, deep magma chamber, conduit and lava branches — then removes itself.
 */
public class VolcanoIgniterBlockEntity extends BlockEntity {

    private int timer = 0;

    public VolcanoIgniterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.VOLCANO_IGNITER.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, VolcanoIgniterBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        be.timer++;
        int delay = GeyserConfig.IGNITER_DELAY_TICKS.get();

        if (server.getGameTime() % 5L == 0L) {
            float p = Mth.clamp(be.timer / (float) delay, 0f, 1f);
            server.sendParticles(ParticleTypes.LARGE_SMOKE,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 2 + Math.round(p * 4), 0.3, 0.2, 0.3, 0.01);
            if (p > 0.6f) {
                server.sendParticles(ParticleTypes.LAVA,
                        pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.0);
            }
        }

        if (be.timer >= delay) {
            int magnitude = 8 + server.random.nextInt(12); // 8–19
            boolean built = VolcanoBuilder.build(server, pos, magnitude);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            server.playSound(null, pos, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 2.0f, 0.5f);

            Player near = server.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 64, false);
            if (near != null) {
                near.sendSystemMessage(built
                        ? Component.translatable("message.fts_geology.volcano_formed")
                        : Component.translatable("message.fts_geology.volcano_failed"));
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Timer", timer);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        timer = tag.getInt("Timer");
    }
}
