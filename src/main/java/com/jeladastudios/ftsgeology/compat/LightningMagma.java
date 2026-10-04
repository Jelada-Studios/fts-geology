package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.volcano.VolcanoField;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * The magma Immersive Weathering's lightning left on bare rock (LGPL; its data read, not its code). It turns any
 * overworld stone a bolt strikes into a magma block, and a second bolt on that into lava; and the fire the bolt lights
 * on magma never goes out, vanilla counting magma as everlasting fuel. A bolt on rock leaves a scorch and at most a
 * thin glassy crust, never melt that stays molten: the mod's data stills both rules (its sand turning to fulgurite is
 * true to life and stays). This puts the rock back under the bolts that struck before, when an operator asks.
 *
 * <p>Only a magma block on top of the ground, on rock or soil, is taken for a bolt's: magma that is buried, beside lava,
 * among the scoria of a cinder cone, near a spring or a vent, or on a large volcano is the earth's own and is kept.</p>
 */
public final class LightningMagma {

    private LightningMagma() {}

    /** What was done: magma blocks turned back to rock, fires put out, and magma left alone as the earth's own. */
    public record Cleaned(int blocks, int fires, int kept) {}

    /** How near a large volcano's mountain magma is taken for the volcano's, blocks. */
    private static final int VOLCANO_MARGIN = 24;
    /** How near lava or a hot spring, vent or crust magma is taken for theirs, blocks. */
    private static final int HOT_NEAR = 3;

    /** Turns the lightning-struck magma on loaded ground within {@code radius} columns of here back into rock. */
    public static Cleaned clean(ServerLevel level, BlockPos centre, int radius) {
        int blocks = 0, fires = 0, kept = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                // The bolt strikes the topmost block that stops it; a fire or a snow layer may lie over that.
                for (int y = top; y >= top - 2; y--) {
                    if (!level.getBlockState(m.set(x, y, z)).is(Blocks.MAGMA_BLOCK)) continue;
                    BlockPos at = m.immutable();
                    if (!struck(level, at)) {
                        kept++;
                        break;
                    }
                    BlockPos up = at.above();
                    if (level.getBlockState(up).is(BlockTags.FIRE)) {
                        level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        fires++;
                    }
                    level.setBlock(at, rockAround(level, at), Block.UPDATE_ALL);
                    blocks++;
                    break;
                }
            }
        }
        return new Cleaned(blocks, fires, kept);
    }

    /** Whether a magma block on the ground is a bolt's: open to the sky, on rock or soil, nothing hot round it. */
    private static boolean struck(ServerLevel level, BlockPos at) {
        if (!level.canSeeSky(at.above())) return false;
        BlockState below = level.getBlockState(at.below());
        if (!below.is(BlockTags.BASE_STONE_OVERWORLD) && !below.is(BlockTags.DIRT) && !below.is(Blocks.GRAVEL)) return false;
        if (VolcanoField.largeMargin(level, at.getX(), at.getZ()) < VOLCANO_MARGIN) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -HOT_NEAR; dx <= HOT_NEAR; dx++) {
            for (int dy = -HOT_NEAR; dy <= HOT_NEAR; dy++) {
                for (int dz = -HOT_NEAR; dz <= HOT_NEAR; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    BlockState s = level.getBlockState(m);
                    if (s.getFluidState().is(FluidTags.LAVA) || hot(s)) return false;
                    // Another magma block: a bolt's too only if it lies open on the ground as this one does.
                    if (s.is(Blocks.MAGMA_BLOCK) && !level.canSeeSky(m.above())) return false;
                }
            }
        }
        return true;
    }

    /** The ground of springs, vents, cones and fresh lava, by whose side magma is the earth's own. */
    private static boolean hot(BlockState s) {
        return s.is(ModBlocks.HOT_SPRING.get()) || s.is(ModBlocks.SPRING_SOURCE.get()) || s.is(ModBlocks.GEYSER_CORE.get())
                || s.is(ModBlocks.STEAM_VENT.get()) || s.is(ModBlocks.MUD_POT.get()) || s.is(ModBlocks.SINTER.get())
                || s.is(ModBlocks.SINTER_CRUST.get()) || s.is(ModBlocks.COOLING_LAVA_CRUST.get())
                || s.is(ModBlocks.BASALT_LAYER.get()) || s.is(ModBlocks.VOLCANIC_ASH.get()) || s.is(ModBlocks.VOLCANO_CORE.get())
                || s.is(Blocks.RED_TERRACOTTA) || s.is(Blocks.BLACKSTONE) || s.is(Blocks.OBSIDIAN);
    }

    /** The rock the bolt melted: the stone most common round it, else what lies under it, else stone. */
    private static BlockState rockAround(ServerLevel level, BlockPos at) {
        Map<BlockState, Integer> count = new HashMap<>();
        for (Direction d : Direction.values()) {
            if (d == Direction.UP) continue;
            BlockState s = level.getBlockState(at.relative(d));
            if (s.is(BlockTags.BASE_STONE_OVERWORLD)) count.merge(s, 1, Integer::sum);
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElseGet(() -> {
                    BlockState below = level.getBlockState(at.below());
                    return below.is(BlockTags.BASE_STONE_OVERWORLD) ? below : Blocks.STONE.defaultBlockState();
                });
    }
}
