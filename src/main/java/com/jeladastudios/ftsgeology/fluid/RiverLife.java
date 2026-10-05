package com.jeladastudios.ftsgeology.fluid;

import com.jeladastudios.ftsgeology.config.ClientConfig;
import com.jeladastudios.ftsgeology.registry.ModParticles;
import com.jeladastudios.ftsgeology.weather.LocalWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * What a river carries, set going on the client as it ticks the water round the player (vanilla's own way for water's
 * bubbles and drips): foam at the foot of every fall down a step, some on fast water and more in rain; and leaves and
 * twigs off the trees on the banks, more where the trees are, in wind and in rain. Nothing here is the server's.
 */
public final class RiverLife {

    private RiverLife() {}

    /** River particles alive now (kept by the particles themselves), and the most there may be at once. */
    public static int alive;
    public static final int MOST = 250;

    public static void animate(Level level, BlockPos pos, FluidState state, RandomSource rnd) {
        if (!level.isClientSide || alive >= MOST) return;
        // Foam drawn as a sheet on the water (client.RiverFoamMesh) is not thrown up as particles as well.
        boolean foam = ClientConfig.RIVER_FOAM.get() && !ClientConfig.RIVER_FOAM_SHEET.get(), debris = ClientConfig.RIVER_DEBRIS.get();
        if (!foam && !debris) return;
        if (falling(state)) {
            if (!foam) return;
            // The foot of the fall: the water this curtain comes down into.
            BlockPos foot = pos.below();
            FluidState f = level.getFluidState(foot);
            for (int i = 0; i < 4 && falling(f); i++) {
                foot = foot.below();
                f = level.getFluidState(foot);
            }
            if (!f.is(FluidTags.WATER) || falling(f)) return;
            double top = foot.getY() + f.getHeight(level, foot) + 0.01;
            int n = 1 + rnd.nextInt(3);
            for (int i = 0; i < n; i++) {
                level.addParticle(ModParticles.RIVER_FOAM.get(), pos.getX() + rnd.nextDouble(), top, pos.getZ() + rnd.nextDouble(), 0, 0, 0);
            }
            return;
        }
        if (RiverWaterFluid.runningWay(level, pos, state) == 0) return;
        if (!level.getBlockState(pos.above()).isAir()) return;
        double top = pos.getY() + state.getHeight(level, pos) + 0.01;
        double x = pos.getX() + rnd.nextDouble(), z = pos.getZ() + rnd.nextDouble();
        float rain = LocalWeather.active() ? LocalWeather.rain(1f) : level.getRainLevel(1f);
        if (foam && rnd.nextFloat() < 0.015f + 0.06f * rain) {
            level.addParticle(ModParticles.RIVER_FOAM.get(), x, top, z, 0, 0, 0);
        }
        if (debris) {
            float wind = (float) Math.hypot(LocalWeather.windX(), LocalWeather.windZ());
            if (rnd.nextFloat() >= 0.01f * (1f + 2f * rain + wind / 5f)) return;
            // Off the trees on the banks: a bare reach carries little.
            int trees = 0;
            for (int i = 0; i < 3; i++) {
                if (level.getBlockState(pos.offset(rnd.nextInt(11) - 5, 1 + rnd.nextInt(8), rnd.nextInt(11) - 5)).is(BlockTags.LEAVES)) trees++;
            }
            if (trees == 0 && rnd.nextFloat() > 0.12f) return;
            level.addParticle(rnd.nextFloat() < 0.7f ? ModParticles.RIVER_LEAF.get() : ModParticles.RIVER_TWIG.get(), x, top, z, 0, 0, 0);
        }
    }

    private static boolean falling(FluidState f) {
        return f.hasProperty(FlowingFluid.FALLING) && f.getValue(FlowingFluid.FALLING);
    }
}
