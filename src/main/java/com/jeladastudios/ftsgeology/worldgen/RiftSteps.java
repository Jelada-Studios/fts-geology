package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Half-block steps on a rift valley's fault scarps. Where the ground rises by exactly one block, the lower column
 * gets a slab of its own rock on top, so a scarp reads as broken rock rather than a stair of full blocks; a step of
 * two or more is the scarp itself and stays. Only in the rift valley role, only where the rock has a slab, never
 * beside water, and only in the mod's own world type, whose rifts have scarps.
 */
public final class RiftSteps {

    private RiftSteps() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static volatile Map<Block, Block> slabs;

    /** The slab of each rock that has one. Basalt has none in this version; blackstone's is the same dark rock. */
    private static Map<Block, Block> slabs() {
        if (slabs != null) return slabs;
        Map<Block, Block> m = new HashMap<>();
        m.put(Blocks.STONE, Blocks.STONE_SLAB);
        m.put(Blocks.COBBLESTONE, Blocks.COBBLESTONE_SLAB);
        m.put(Blocks.ANDESITE, Blocks.ANDESITE_SLAB);
        m.put(Blocks.DIORITE, Blocks.DIORITE_SLAB);
        m.put(Blocks.GRANITE, Blocks.GRANITE_SLAB);
        m.put(Blocks.SANDSTONE, Blocks.SANDSTONE_SLAB);
        m.put(Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_SLAB);
        m.put(Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE_SLAB);
        m.put(Blocks.COBBLED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE_SLAB);
        m.put(Blocks.BASALT, Blocks.BLACKSTONE_SLAB);
        m.put(Blocks.SMOOTH_BASALT, Blocks.BLACKSTONE_SLAB);
        m.put(Blocks.BLACKSTONE, Blocks.BLACKSTONE_SLAB);
        m.put(ModBlocks.TRAVERTINE.get(), ModBlocks.TRAVERTINE_SLAB.get());
        m.put(ModBlocks.RHYOLITE.get(), ModBlocks.RHYOLITE_SLAB.get());
        m.put(ModBlocks.GABBRO.get(), ModBlocks.GABBRO_SLAB.get());
        m.put(ModBlocks.PERIDOTITE.get(), ModBlocks.PERIDOTITE_SLAB.get());
        m.put(ModBlocks.SERPENTINITE.get(), ModBlocks.SERPENTINITE_SLAB.get());
        m.put(ModBlocks.SCHIST.get(), ModBlocks.SCHIST_SLAB.get());
        m.put(ModBlocks.GNEISS.get(), ModBlocks.GNEISS_SLAB.get());
        m.put(ModBlocks.SLATE.get(), ModBlocks.SLATE_SLAB.get());
        m.put(ModBlocks.MARBLE.get(), ModBlocks.MARBLE_SLAB.get());
        m.put(ModBlocks.QUARTZITE.get(), ModBlocks.QUARTZITE_SLAB.get());
        m.put(ModBlocks.SHALE.get(), ModBlocks.SHALE_SLAB.get());
        m.put(ModBlocks.CHERT.get(), ModBlocks.CHERT_SLAB.get());
        slabs = m;
        return m;
    }

    public static void generate(WorldGenLevel level, ChunkPos cp) {
        if (TfcCompat.active() || !GeologyWorld.isOwn(level.getLevel())) return;
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        // A chunk with no rift valley at any corner has no scarp to step.
        if (!rift(x0, z0) && !rift(x0 + 15, z0) && !rift(x0, z0 + 15) && !rift(x0 + 15, z0 + 15)) return;
        Map<Block, Block> slabs = slabs();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = x0 + dx, z = z0 + dz;
                if (!rift(x, z)) continue;
                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE) continue;
                // The lower column of a one-block step: a four-neighbour exactly one higher, none lower by more.
                boolean step = false;
                for (int k = 0; k < 4; k++) {
                    int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    int h = TerrainProbe.groundY(level, nx, nz);
                    if (h == g + 1) step = true;
                }
                if (!step) continue;
                BlockState top = level.getBlockState(at.set(x, g, z));
                Block slab = slabs.get(top.getBlock());
                if (slab == null) continue;
                BlockState above = level.getBlockState(at.set(x, g + 1, z));
                if (!above.isAir()) continue;
                // Not at the water's edge: a slab on a bank breaks the shore.
                boolean wet = false;
                for (int k = 0; k < 4 && !wet; k++) {
                    int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    wet = !level.getFluidState(at.set(nx, g + 1, nz)).isEmpty() || !level.getFluidState(at.set(nx, g, nz)).isEmpty();
                }
                if (wet) continue;
                level.setBlock(at.set(x, g + 1, z), slab.defaultBlockState(), FLAGS);
            }
        }
    }

    private static boolean rift(int x, int z) {
        return GeologyRoles.roleAt(x, z) == GeologyRoles.Role.RIFT_VALLEY;
    }
}
