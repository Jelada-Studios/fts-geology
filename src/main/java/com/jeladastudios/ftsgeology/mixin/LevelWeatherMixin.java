package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.weather.LocalWeather;
import com.jeladastudios.ftsgeology.weather.Storms;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The weather asked of a place answered for that place (see {@link Storms}): it rains at a block only under a storm; the
 * client's rain and thunder are the player's own; and while the server works out a chunk's snow, cauldrons and
 * lightning, the rain is that chunk's.
 */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {

    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void fts_geology$rainingHere(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self instanceof ServerLevel level && Storms.on(level) && level.getServer().isSameThread()
                && Storms.intensityAt(level, pos.getX(), pos.getZ()) < Storms.WET) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void fts_geology$rainLevel(float partial, CallbackInfoReturnable<Float> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            if (LocalWeather.active()) cir.setReturnValue(LocalWeather.rain(partial));
            return;
        }
        float[] here = LocalWeather.CHUNK.get();
        if (here != null && here[0] < Storms.WET) cir.setReturnValue(0f);
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void fts_geology$thunderLevel(float partial, CallbackInfoReturnable<Float> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            if (LocalWeather.active()) cir.setReturnValue(LocalWeather.thunder(partial));
            return;
        }
        float[] here = LocalWeather.CHUNK.get();
        if (here != null && here[1] < 0.1f) cir.setReturnValue(0f);
    }
}
