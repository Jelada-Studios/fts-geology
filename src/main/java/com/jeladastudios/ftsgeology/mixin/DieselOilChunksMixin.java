package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.worldgen.PetroleumFields;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Diesel Generators (MIT) gives each chunk its oil from noise and its biome; on the mod's own world types the oil
 * is where the fields are (see {@link PetroleumFields#dieselOil}), so its oil scanner and pumpjack find the geology. Only
 * there when that mod is; without it the target does not exist and this is passed over. Oil a chunk has already given
 * up is that mod's own record and is left to it.
 */
@Pseudo
@Mixin(targets = "com.jesz.createdieselgenerators.world.OilChunksSavedData", remap = false)
public abstract class DieselOilChunksMixin {

    @Inject(method = "getBaseOilAmount", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void fts_geology$oilWhereTheFieldsAre(ServerLevel level, ChunkPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!GeyserConfig.PETROLEUM.get() || !GeologyWorld.isOwn(level)) return;
        cir.setReturnValue(PetroleumFields.dieselOil(level, pos));
    }
}
