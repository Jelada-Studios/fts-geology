package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;

/**
 * Fish in the mod's own rivers.
 *
 * <p>The river water breathes and floats and fills a bucket, because it carries the water fluid tag, but nothing was
 * ever born in it. Vanilla's rule for a water animal reads the block itself rather than the tag -- {@code
 * getBlockState(pos.above()).is(Blocks.WATER)} -- and it only looks in a band thirteen blocks under sea level, where
 * an upland river never is. So the rule failed twice over, and a salmon dropped in one lived happily while no salmon
 * ever appeared on its own.</p>
 *
 * <p>Forge lets a mod widen a rule rather than replace it, so vanilla's answer stands everywhere it used to and this
 * one only adds the mod's own water. Which fish may appear in a given river is still the biome's business: the
 * channel carries {@code minecraft:river}, and that biome asks for salmon.</p>
 */
public final class RiverSpawns {

    private RiverSpawns() {}

    /** Widens the fish rules once, while the game loads. */
    public static void register(SpawnPlacementRegisterEvent event) {
        also(event, EntityType.SALMON);
        also(event, EntityType.COD);
        also(event, EntityType.TROPICAL_FISH);
        also(event, EntityType.PUFFERFISH);
        also(event, EntityType.SQUID);
    }

    /**
     * True where a water animal may be born in the mod's river water: standing in it with its head under it too, so a
     * fish never appears in the single wet block of a shallow.
     */
    public static <T extends Entity> boolean inRiverWater(EntityType<T> type, ServerLevelAccessor level,
                                                          MobSpawnType reason, BlockPos pos, RandomSource random) {
        Block river = ModBlocks.RIVER_WATER.get();
        if (!level.getBlockState(pos).is(river)) return false;
        BlockState above = level.getBlockState(pos.above());
        return above.is(river) || above.is(Blocks.WATER);
    }

    private static <T extends Entity> void also(SpawnPlacementRegisterEvent event, EntityType<T> type) {
        event.register(type, RiverSpawns::inRiverWater, SpawnPlacementRegisterEvent.Operation.OR);
    }
}
