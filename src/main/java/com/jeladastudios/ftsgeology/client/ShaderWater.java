package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The river's water drawn as water by a shader pack. A pack gives each block it treats specially a number in its
 * {@code block.properties} -- water is {@code block.8=minecraft:water} in most -- and the shaders pick the waves, the
 * reflections and the colour of the depths by that number. The river is a block of its own, which no pack lists, so
 * under shaders it came out as a plain see-through block. Whatever number a pack gives water, the river's states get
 * the same.
 */
public final class ShaderWater {

    private ShaderWater() {}

    public static Object2IntMap<BlockState> alias(Object2IntMap<BlockState> ids) {
        if (ids == null) return null;
        BlockState water = Blocks.WATER.defaultBlockState();
        if (!ids.containsKey(water)) return ids;
        int id = ids.getInt(water);
        Object2IntOpenHashMap<BlockState> out = new Object2IntOpenHashMap<>(ids);
        out.defaultReturnValue(ids.defaultReturnValue());
        int added = 0;
        for (BlockState s : ModBlocks.RIVER_WATER.get().getStateDefinition().getPossibleStates()) {
            if (out.containsKey(s)) continue;
            out.put(s, id);
            added++;
        }
        GeysersMod.LOGGER.info("Shader pack: the river's {} block states drawn as water (id {})", added, id);
        return out;
    }
}
