package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.RockOreBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.neoforged.neoforge.common.Tags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Vanilla's ores in the mod's rocks. The rocks are in the stone ore replaceables, so an ore vein reaching into them
 * takes them like stone, and the ore it leaves there becomes the rock's own (RockOreBlocks) where the rock has one.
 *
 * <p>A mod can narrow vanilla's ore features down to a list of its own: Spelunkery's targets are stone, deepslate and
 * its four rocks, so in a pack with it no vein ever took the mod's rocks, and a mountain of schist held no ore at all.
 * A vein that meets one of the rocks and none of its targets takes the rock as it would take stone.</p>
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {

    /** The rock under the last block a vein asked about on this thread, null when it was not one of the mod's rocks. */
    private static final ThreadLocal<BlockState> FTS_GEOLOGY_ROCK = new ThreadLocal<>();

    @Inject(method = "canPlaceOre", at = @At("HEAD"), cancellable = true)
    private static void fts_geology$rockAsStone(BlockState state, Function<BlockPos, BlockState> getter, RandomSource random,
                                                OreConfiguration config, OreConfiguration.TargetBlockState target,
                                                BlockPos.MutableBlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!RockOreBlocks.holdsOres(state) || !GeyserConfig.ROCK_ORES.get()) {
            // Cleared, so the ore a vein then sets here is not taken for one in the rock asked about before.
            if (FTS_GEOLOGY_ROCK.get() != null) FTS_GEOLOGY_ROCK.set(null);
            return;
        }
        // A target that takes the rock itself (the replaceables tag, or a pack's own rule): as it is. So is a vein of
        // anything but ore, dirt or gravel or another rock: those keep to the targets they were given.
        if (target.target.test(state, random) || !target.state.is(Tags.Blocks.ORES)) {
            FTS_GEOLOGY_ROCK.set(state);
            return;
        }
        boolean ok = OreFeature.canPlaceOre(Blocks.STONE.defaultBlockState(), getter, random, config, target, pos);
        // Set after the call: the call asked about stone and cleared it.
        FTS_GEOLOGY_ROCK.set(state);
        cir.setReturnValue(ok);
    }

    @ModifyArg(method = "doPlace", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"),
            index = 3)
    private BlockState fts_geology$inRock(BlockState ore) {
        BlockState rock = FTS_GEOLOGY_ROCK.get();
        return rock == null ? ore : RockOreBlocks.inRock(rock, ore);
    }
}
