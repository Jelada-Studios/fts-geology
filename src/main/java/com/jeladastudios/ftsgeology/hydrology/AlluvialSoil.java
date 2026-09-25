package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;

/**
 * Crops grow faster on a floodplain.
 *
 * <p>A river in flood spreads the silt it carries over the plain beside it, and every flood lays down a fresh skin of
 * fine, rich ground: the Nile's valley, the plain between the Tigris and the Euphrates, the Ganges delta were farmed
 * first because of it. On the mod's alluvial plain a crop or a melon or pumpkin stem on tilled soil is
 * {@code alluvialCropGrowth} times as likely to grow on each of its random ticks, so it ripens, and a stem fruits, that
 * many times as fast. Bone meal is not the soil's doing and is left as it is.</p>
 */
public final class AlluvialSoil {

    private AlluvialSoil() {}

    private static final ResourceKey<Biome> ALLUVIAL_PLAIN =
            ResourceKey.create(Registries.BIOME, new ResourceLocation(GeysersMod.MODID, "alluvial_plain"));

    public static void grow(BlockEvent.CropGrowEvent.Pre e) {
        if (e.getResult() != Event.Result.DEFAULT || !(e.getLevel() instanceof ServerLevel level)) return;
        double m = GeyserConfig.ALLUVIAL_CROP_GROWTH.get();
        if (m <= 1.0) return;
        Block block = e.getState().getBlock();
        if (!(block instanceof CropBlock) && !(block instanceof StemBlock)) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockState(pos.below()).getBlock() instanceof FarmBlock)) return;
        if (!level.getBiome(pos).is(ALLUVIAL_PLAIN)) return;
        // The tick grows the crop by the game's own roll, one in (25 / speed) + 1; letting it grow besides with the
        // chance q makes the two together m times that roll, whatever the field round it.
        double p = 1.0 / ((int) (25.0F / Speed.of(block, level, pos)) + 1);
        double q = Math.min(1.0, (m - 1.0) * p / (1.0 - p));
        if (level.getRandom().nextDouble() < q) e.setResult(Event.Result.ALLOW);
    }

    /** Reaches the game's own reckoning of how well a crop grows where it stands. Never made. */
    private static final class Speed extends CropBlock {
        private Speed(Properties properties) {
            super(properties);
        }

        static float of(Block block, BlockGetter level, BlockPos pos) {
            return getGrowthSpeed(block, level, pos);
        }
    }
}
