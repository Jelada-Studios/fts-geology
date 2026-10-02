package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.client.ClientWeather;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The rain Oculus tells a shader pack (its {@code rainStrength} and {@code wetness}): see {@link ClientWeather#shaderRain}.
 * Only there when Oculus is; without it the target does not exist and this is passed over.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class OculusRainMixin {

    @Inject(method = "getRainStrength", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void fts_geology$overcast(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(ClientWeather.shaderRain(cir.getReturnValueF()));
    }
}
