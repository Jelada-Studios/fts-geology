package com.jeladastudios.ftsgeology.mixin;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.RockOreBlocks;
import com.jeladastudios.ftsgeology.worldgen.CoalGround;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.neoforged.neoforge.common.Tags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 *
 * <p>Coal keeps to where it forms (see {@link CoalGround}): a coal vein outside a basin takes slate only.</p>
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {

    /** The rock under the last block a vein asked about on this thread, null when it was not one of the mod's rocks. */
    private static final ThreadLocal<BlockState> FTS_GEOLOGY_ROCK = new ThreadLocal<>();

    /** The room the coal vein being placed on this thread has, null for any other vein or where the rule is off. */
    private static final ThreadLocal<CoalGround.Room> FTS_GEOLOGY_COAL = new ThreadLocal<>();

    @Shadow
    protected static boolean shouldSkipAirCheck(RandomSource random, float chance) {
        throw new AssertionError();
    }

    @Inject(method = "place", at = @At("HEAD"))
    private void fts_geology$coalRoom(FeaturePlaceContext<OreConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        CoalGround.Room room = CoalGround.isCoalVein(context.config())
                ? CoalGround.roomFor(context.level().getLevel(), context.chunkGenerator(), context.origin()) : null;
        if (room != null) FTS_GEOLOGY_COAL.set(room);
        else if (FTS_GEOLOGY_COAL.get() != null) FTS_GEOLOGY_COAL.remove();
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void fts_geology$coalDone(FeaturePlaceContext<OreConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        if (FTS_GEOLOGY_COAL.get() != null) FTS_GEOLOGY_COAL.remove();
    }

    @Inject(method = "canPlaceOre", at = @At("HEAD"), cancellable = true)
    private static void fts_geology$rockAsStone(BlockState state, Function<BlockPos, BlockState> getter, RandomSource random,
                                                OreConfiguration config, OreConfiguration.TargetBlockState target,
                                                BlockPos.MutableBlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        CoalGround.Room room = FTS_GEOLOGY_COAL.get();
        if (room != null && target.state.is(BlockTags.COAL_ORES) && !CoalGround.holds(state, room)) {
            if (FTS_GEOLOGY_ROCK.get() != null) FTS_GEOLOGY_ROCK.set(null);
            cir.setReturnValue(false);
            return;
        }
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
        // Vanilla's own test, asked of stone.
        boolean ok = target.target.test(Blocks.STONE.defaultBlockState(), random)
                && (shouldSkipAirCheck(random, config.discardChanceOnAirExposure) || !Feature.isAdjacentToAir(getter, pos));
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
