package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.weather.LocalWeather;
import com.jeladastudios.ftsgeology.weather.RainContext;
import com.jeladastudios.ftsgeology.weather.Storms;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The weather asked of a place answered for that place (see {@link Storms}): it rains at a block only under a storm; the
 * client's rain and thunder are the player's own; and on the server the rain is the place's while an entity, a player
 * or a block entity ticks ({@link RainContext}), and the chunk's while its snow, cauldrons and lightning are worked out,
 * so that a mod asking the world whether it rains is told about the place it asks for.
 */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {

    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void fts_geology$rainingHere(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel level = regional();
        if (level == null) return;
        if (Storms.intensityAt(level, pos.getX(), pos.getZ()) < Storms.WET) {
            cir.setReturnValue(false);
            return;
        }
        // Vanilla's own tests past its first, which asks the world's rain rather than this block's: open to the sky, nothing
        // over it, and rain, not snow, falling in its biome. Answered here whole, so nothing of the place's is lost on the
        // way, whatever is ticking.
        Level self = (Level) (Object) this;
        cir.setReturnValue(self.canSeeSky(pos) && self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY()
                && self.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN);
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void fts_geology$rainLevel(float partial, CallbackInfoReturnable<Float> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            if (LocalWeather.active()) cir.setReturnValue(LocalWeather.rain(partial));
            return;
        }
        ServerLevel level = regional();
        // The place something ticking asks from first: a chunk's value can be left standing by a mod that cuts a chunk's
        // tick short.
        if (level != null && RainContext.any()) {
            if (RainContext.clear() || Storms.intensityAt(level, RainContext.x(), RainContext.z()) < Storms.WET) cir.setReturnValue(0f);
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
        ServerLevel level = regional();
        if (level != null && RainContext.any()) {
            if (RainContext.clear() || Storms.thunderAt(level, RainContext.x(), RainContext.z()) < 0.1f) cir.setReturnValue(0f);
            return;
        }
        float[] here = LocalWeather.CHUNK.get();
        if (here != null && here[1] < 0.1f) cir.setReturnValue(0f);
    }

    /**
     * The world's sky reckoned dark or light by its time of day alone where its rain is regional: one value for the
     * whole world, which makes it day or night for every mob, so a storm anywhere turned noon into night everywhere.
     */
    @Inject(method = "updateSkyBrightness", at = @At("HEAD"))
    private void fts_geology$clearSky(CallbackInfo ci) {
        if (regional() != null) RainContext.pushClear();
    }

    @Inject(method = "updateSkyBrightness", at = @At("RETURN"))
    private void fts_geology$clearSkyDone(CallbackInfo ci) {
        if (regional() != null) RainContext.pop();
    }

    /** This world, where its rain is regional and asked on the server's own thread; else null. */
    private ServerLevel regional() {
        return (Object) this instanceof ServerLevel level && Storms.on(level) && level.getServer().isSameThread() ? level : null;
    }
}
