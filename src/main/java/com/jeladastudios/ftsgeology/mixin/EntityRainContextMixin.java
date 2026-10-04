package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.weather.RainContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While an entity ticks on the server, the weather it asks the world about is the weather where it stands. */
@Mixin(ServerLevel.class)
public abstract class EntityRainContextMixin {

    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void fts_geology$entityHere(Entity entity, CallbackInfo ci) {
        RainContext.push(entity.getBlockX(), entity.getBlockZ());
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void fts_geology$entityDone(Entity entity, CallbackInfo ci) {
        RainContext.pop();
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"))
    private void fts_geology$passengerHere(Entity vehicle, Entity passenger, CallbackInfo ci) {
        RainContext.push(passenger.getBlockX(), passenger.getBlockZ());
    }

    @Inject(method = "tickPassenger", at = @At("RETURN"))
    private void fts_geology$passengerDone(Entity vehicle, Entity passenger, CallbackInfo ci) {
        RainContext.pop();
    }
}
