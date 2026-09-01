package com.pandabear.geysers.blockentity;

import com.pandabear.geysers.config.GeyserConfig;
import com.pandabear.geysers.registry.ModBlockEntities;
import com.pandabear.geysers.worldgen.RetrogenHandler;
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
 * The "seed" behind a placed Geyser Igniter. Counts down (smoking as it charges), then builds a
 * full geyser system deep below its column and removes itself — so a player can just plant a block
 * and get a geyser a few seconds later, no command needed.
 */
public class GeyserIgniterBlockEntity extends BlockEntity {

    private int timer = 0;

    public GeyserIgniterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GEYSER_IGNITER.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GeyserIgniterBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;

        be.timer++;
        int delay = GeyserConfig.IGNITER_DELAY_TICKS.get();

        // Charging cue: rising smoke + the occasional ember pop, intensifying near the end.
        if (server.getGameTime() % 5L == 0L) {
            float progress = Mth.clamp(be.timer / (float) delay, 0f, 1f);
            server.sendParticles(ParticleTypes.LARGE_SMOKE,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    1 + Math.round(progress * 3), 0.2, 0.1, 0.2, 0.01);
            if (progress > 0.6f) {
                server.sendParticles(ParticleTypes.LAVA,
                        pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.2, 0.1, 0.2, 0.0);
            }
        }

        if (be.timer >= delay) {
            be.ignite(server, pos);
        }
    }

    private void ignite(ServerLevel level, BlockPos pos) {
        int maxY = GeyserConfig.RETROGEN_MAX_Y.get();
        int chamberH = GeyserConfig.CHAMBER_TARGET_HEIGHT.get();
        int deepest = level.getMinBuildHeight() + 2;
        int highest = maxY - chamberH - 3;
        int coreY = Mth.clamp(GeyserConfig.RETROGEN_MIN_Y.get() + 1, deepest, highest);
        BlockPos corePos = new BlockPos(pos.getX(), coreY, pos.getZ());
        int magnitude = 15; // a good big geyser from a deliberately-placed igniter

        // Deep underground, per the mod's realism goal — the vent then bores up to the surface here.
        boolean placed = RetrogenHandler.forcePlace(level, corePos, magnitude, level.random);

        // Consume the igniter and mark the moment with a hiss.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1.2f, 0.7f);
        if (placed) {
            level.sendParticles(ParticleTypes.EXPLOSION,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.0);
        }

        Player near = level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 48, false);
        if (near != null) {
            near.sendSystemMessage(placed
                    ? Component.translatable("message.fts_geology.geyser_forming", coreY)
                    : Component.translatable("message.fts_geology.geyser_failed"));
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
