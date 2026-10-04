package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.weather.RainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a block entity ticks on the server (a solar panel, a rain collector), the weather it asks about is its own. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityRainContextMixin {

    @Unique private boolean fts_geology$pushed;

    @Inject(method = "tick", at = @At("HEAD"))
    private void fts_geology$blockEntityHere(CallbackInfo ci) {
        // The client ticks its block entities too, on its own thread, beside an integrated server's.
        fts_geology$pushed = RainContext.onServerThread();
        if (fts_geology$pushed) {
            BlockPos pos = ((TickingBlockEntity) this).getPos();
            RainContext.push(pos.getX(), pos.getZ());
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fts_geology$blockEntityDone(CallbackInfo ci) {
        if (fts_geology$pushed) RainContext.pop();
    }
}
