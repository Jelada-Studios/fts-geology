package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.worldgen.terrain.WrapMemo;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;

/**
 * A noise chunk wraps each branch its router names once, however often the router uses it; see {@link WrapMemo}.
 * The setting up starts where the chunk makes its map of wrapped functions, first thing, and ends as it returns.
 * Optional: where another mod has changed either, this is left out and chunks cost what they did.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkWrapMixin {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "()Ljava/util/HashMap;"), require = 0)
    private HashMap<?, ?> fts_geology$beginWrap() {
        WrapMemo.begin();
        return new HashMap<>();
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void fts_geology$endWrap(CallbackInfo ci) {
        WrapMemo.end();
    }
}
