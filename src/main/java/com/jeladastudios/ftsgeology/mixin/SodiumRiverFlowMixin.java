package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The same as {@link RiverFlowRenderMixin} in Sodium's own fluid renderer, which on NeoForge 1.21.1 takes the place
 * Embeddium had on Forge. Only there when Sodium is; without it the target does not exist and this is passed over. The
 * handlers name no type of Sodium's, so nothing of it is needed to build.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer", remap = false)
public abstract class SodiumRiverFlowMixin {

    @ModifyVariable(method = "render", at = @At("LOAD"), ordinal = 0, require = 0, remap = false)
    private Vec3 fts_geology$riverFlow(Vec3 flow, @Local(argsOnly = true) FluidState fluid,
                                       @Local(argsOnly = true, ordinal = 0) BlockPos pos) {
        if (!(fluid.getType() instanceof RiverWaterFluid)) return flow;
        // The river's way is in its own block state; the level is only asked by a mod that runs the water itself.
        var level = Minecraft.getInstance().level;
        return level == null ? flow : fluid.getFlow(level, pos);
    }

    /** A river block's water as high as a registered hook says it stands, in Sodium's corner heights. */
    @Inject(method = "fluidHeight", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void fts_geology$riverSurface(net.minecraft.world.level.BlockAndTintGetter world, net.minecraft.world.level.material.Fluid fluid,
                                          BlockPos pos, net.minecraft.core.Direction direction,
                                          org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        FluidState state = world.getFluidState(pos);
        if (!(state.getType() instanceof RiverWaterFluid) || !fluid.isSame(state.getType())) return;
        float h = com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.surface(world, pos, state);
        if (!Float.isNaN(h)) cir.setReturnValue(h);
    }
}
