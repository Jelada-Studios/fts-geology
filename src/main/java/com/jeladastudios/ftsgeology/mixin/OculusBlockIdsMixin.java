package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.client.ShaderWater;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Where Oculus takes a shader pack's block ids: see {@link ShaderWater}. Only there when Oculus is; without it the
 * target does not exist and this is passed over.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", remap = false)
public abstract class OculusBlockIdsMixin {

    @ModifyVariable(method = "setBlockStateIds", at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private Object2IntMap<BlockState> fts_geology$riverIsWater(Object2IntMap<BlockState> ids) {
        return ShaderWater.alias(ids);
    }
}
