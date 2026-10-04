package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.weather.RainContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player on the server ticks from its connection, outside the world's round of entities, and the mods that warm,
 * cool or wet a player (Tough As Nails among them) ask the world's weather in that tick: it is the weather where the
 * player stands.
 */
@Mixin(Player.class)
public abstract class PlayerRainContextMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void fts_geology$playerHere(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p) RainContext.push(p.getBlockX(), p.getBlockZ());
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fts_geology$playerDone(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer) RainContext.pop();
    }
}
