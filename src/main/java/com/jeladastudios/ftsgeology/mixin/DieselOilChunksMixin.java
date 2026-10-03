package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.worldgen.PetroleumFields;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create Diesel Generators (MIT) gives each chunk its oil from noise and its biome; in the overworld the oil is where the
 * fields are (see {@link PetroleumFields#dieselOil}), so its oil scanner and pumpjack find the geology. That is in every
 * overworld the fields are laid in, not only the mod's own world types: they are, and a scanner that found nothing over
 * the field {@code /geology find oil} named was no use. Only there when that mod is; without it the target does not exist and
 * this is passed over. Oil a chunk has already given up is that mod's own record and is left to it.
 */
@Pseudo
@Mixin(targets = "com.jesz.createdieselgenerators.world.OilChunksSavedData", remap = false)
public abstract class DieselOilChunksMixin {

    @Inject(method = "getBaseOilAmount", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void fts_geology$oilWhereTheFieldsAre(ServerLevel level, ChunkPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!GeyserConfig.PETROLEUM.get() || !GeyserConfig.ORE_GENESIS_ENABLED.get()
                || !GeyserConfig.GEOLOGY_AT_GENERATION.get() || level.dimension() != Level.OVERWORLD) return;
        cir.setReturnValue(PetroleumFields.dieselOil(level, pos));
    }
}
