package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The warm heart of a hot spring. It doesn't erupt — it just sits under a pool and:
 * <ul>
 *   <li>wisps steam off the water surface,</li>
 *   <li>keeps anything soaking in the pool from freezing, and (optionally) grants brief
 *       Regeneration — a cosy place to warm up,</li>
 *   <li>melts nearby snow and ice, carving a thawed micro-oasis around itself.</li>
 * </ul>
 *
 * <p><b>Tough As Nails:</b> the generated pool hides a lava/magma heat source a couple blocks
 * below the floor. TAN already treats nearby lava/magma as warm, so soaking in the pool raises
 * your body temperature there with no code dependency on TAN at all.</p>
 */
public class HotSpringBlockEntity extends BlockEntity {

    public HotSpringBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOT_SPRING.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, HotSpringBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        long time = server.getGameTime();

        // The bed does not watch its own pool any more.
        //
        // It used to, and used to ask for a repair when it went dry. That whole path is gone: the
        // mineral water line underneath is what knows a spring exists, it checks its own vent every
        // couple of seconds, and it grows a new pool when there is none. A bed reporting upwards was
        // an extra way to say the same thing, and it produced its own bugs - a bed buried under a
        // newer one shouted that it was dry for the life of the world.

        // Steam over the whole pool, not just the one column above the vent.
        //
        // How much of it you see depends on how cold the air is: a spring in a snowfield smokes
        // visibly while the same water in a jungle barely shows anything, because what you are
        // looking at is condensation rather than the water itself. It is also the cheapest thing
        // that makes a pool read as HOT instead of as a puddle, which matters a lot on screen.
        if (time % 6L == 0L) {
            int r = GeyserConfig.HOT_SPRING_RADIUS.get();
            float temperature = server.getBiome(pos).value().getBaseTemperature();
            int puffs = temperature < 0.2f ? 6 : temperature < 0.9f ? 4 : 2;
            for (int i = 0; i < puffs; i++) {
                int dx = server.random.nextInt(r * 2 + 1) - r;
                int dz = server.random.nextInt(r * 2 + 1) - r;
                BlockPos surface = waterTop(server, pos.offset(dx, 0, dz));
                if (surface == null) continue;
                server.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                        surface.getX() + 0.5, surface.getY() + 0.2, surface.getZ() + 0.5,
                        1, 0.35, 0.02, 0.35, 0.006);
                // The odd bubble breaking at the surface, where the water is being warmed.
                if (server.random.nextInt(4) == 0) {
                    server.sendParticles(ParticleTypes.BUBBLE_POP,
                            surface.getX() + 0.5, surface.getY() - 0.3, surface.getZ() + 0.5,
                            1, 0.3, 0.05, 0.3, 0.0);
                }
            }
        }

        // Warmth + thaw, once a second.
        if (time % 20L == 0L) {
            int r = GeyserConfig.HOT_SPRING_RADIUS.get();
            AABB area = new AABB(pos).inflate(r, 2, r);
            for (LivingEntity e : server.getEntitiesOfClass(LivingEntity.class, area,
                    le -> le.isAlive() && le.isInWater())) {
                e.setTicksFrozen(0); // never freeze while soaking
                if (GeyserConfig.HOT_SPRING_REGEN.get()) {
                    e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0, true, false));
                }
            }
            // Not while the ground is moving: this writes air and water over an 8-block box every
            // second, which fights the deformation and the blocks settling out of it.
            if (!com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(server, pos)) {
                thawAround(server, pos, r);
            }
        }
    }


    /** Top block of the water column over this spot, or null if there is no pool here. */
    private static BlockPos waterTop(ServerLevel level, BlockPos at) {
        BlockPos top = null;
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos c = at.above(dy);
            if (!level.getBlockState(c).getFluidState().is(FluidTags.WATER)) break;
            top = c;
        }
        return top;
    }
    /** Melts snow layers/blocks, ice, and powder snow within radius — a warm thawed patch. */
    private static void thawAround(ServerLevel level, BlockPos center, int r) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, 2, r))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) {
                level.setBlock(p.immutable(), Blocks.AIR.defaultBlockState(), 3);
            } else if (s.is(Blocks.ICE) || s.is(Blocks.FROSTED_ICE)) {
                level.setBlock(p.immutable(), Blocks.WATER.defaultBlockState(), 3);
            }
        }
    }
}
