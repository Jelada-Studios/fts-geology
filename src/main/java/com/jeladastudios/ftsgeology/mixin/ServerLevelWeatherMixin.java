package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.weather.LocalWeather;
import com.jeladastudios.ftsgeology.weather.Storms;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server's weather made regional (see {@link Storms}): a chunk's snow, cauldrons and lightning follow the storm over
 * it; {@code /weather} brings a storm over the players or clears the sky over them; a night slept through clears it.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {

    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void fts_geology$chunkWeather(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!Storms.on(level)) return;
        int x = chunk.getPos().getMiddleBlockX(), z = chunk.getPos().getMiddleBlockZ();
        LocalWeather.CHUNK.set(new float[]{Storms.intensityAt(level, x, z), Storms.thunderAt(level, x, z)});
    }

    @Inject(method = "tickChunk", at = @At("RETURN"))
    private void fts_geology$chunkWeatherDone(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        LocalWeather.CHUNK.remove();
    }

    @Inject(method = "setWeatherParameters", at = @At("HEAD"))
    private void fts_geology$weatherCommand(int clearTime, int weatherTime, boolean raining, boolean thundering, CallbackInfo ci) {
        Storms.command((ServerLevel) (Object) this, raining, thundering, raining ? weatherTime : clearTime);
    }

    @Inject(method = "resetWeatherCycle", at = @At("HEAD"))
    private void fts_geology$slept(CallbackInfo ci) {
        Storms.slept((ServerLevel) (Object) this);
    }
}
