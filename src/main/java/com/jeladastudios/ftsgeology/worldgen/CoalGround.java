package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

/**
 * Coal where coal forms. It is the peat of swamp forests buried in a sinking basin and cooked: it lies in the
 * sediment of foreland, rift and intracratonic basins (see {@link OreGenesis#basin}), in shale and the rock round it;
 * low-grade metamorphism turns it to anthracite in slate. In schist and gneiss it has gone to graphite, and lavas,
 * granites and the sea floor never had any. Vanilla scatters coal through every stone; in the mod's world types a
 * vein of it now keeps to a basin, and outside one only slate takes it.
 */
public final class CoalGround {

    private CoalGround() {}

    /** Where a coal vein may go: anywhere it formed (a basin), or only into slate. */
    public enum Room { BASIN, SLATE_ONLY }

    /** Whether a vein of ore places coal. */
    public static boolean isCoalVein(OreConfiguration config) {
        for (OreConfiguration.TargetBlockState t : config.targetStates) {
            if (t.state.is(BlockTags.COAL_ORES)) return true;
        }
        return false;
    }

    /**
     * The room a coal vein starting at {@code origin} has, or null where the rule does not hold: another world type,
     * or the setting is off.
     */
    public static Room roomFor(ServerLevel level, ChunkGenerator generator, BlockPos origin) {
        if (!GeyserConfig.COAL_IN_BASINS.get() || !com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(generator)) {
            return null;
        }
        if (!OreGenesis.basin(level, origin.getX(), origin.getZ())) return Room.SLATE_ONLY;
        // A range's metamorphic core is no basin, whatever sagged beside it: what coal it had went to graphite.
        com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Column col = com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.column(
                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), origin.getX(), origin.getZ());
        if (col.setting() == com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Setting.FOLD_BELT && col.grade() > 0.33) {
            return Room.SLATE_ONLY;
        }
        return Room.BASIN;
    }

    /** Whether a column lies in a basin where coal formed. */
    public static boolean basin(ServerLevel level, int x, int z) {
        return OreGenesis.basin(level, x, z);
    }

    /** Whether coal may take this block: in a basin its shale, slate and plain stone; elsewhere slate alone. */
    public static boolean holds(BlockState host, Room room) {
        if (host.is(ModBlocks.SLATE.get())) return true;
        if (room != Room.BASIN) return false;
        return host.is(Blocks.STONE) || host.is(Blocks.DEEPSLATE) || host.is(ModBlocks.SHALE.get());
    }
}
