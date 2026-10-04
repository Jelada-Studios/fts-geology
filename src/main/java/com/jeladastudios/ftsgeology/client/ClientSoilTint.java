package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.SoilTintPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grass drawn paler where the ground under it has dried out, and a deeper green where it is soaked or the groundwater
 * is near: the server says how each four-by-four cell of a chunk looks, and grass, ferns and the grass block's top take
 * the biome's colour blended towards straw, or darkened, by that much. The cells are blended into each other, across
 * the chunks' edges too, so no square shows. Nothing is kept past the chunk; where nothing was said, the grass is the
 * biome's own green.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientSoilTint {

    private ClientSoilTint() {}

    /** Each chunk's sixteen cells, from -15 lushest to 15 straw. */
    private static final Map<Long, byte[]> DRY = new ConcurrentHashMap<>();
    /** The colour of dry grass, and how far towards it the driest goes. */
    private static final int STRAW = 0xC2A864;
    private static final float MOST = 0.8f;
    /** How much darker the lushest grass is drawn. */
    private static final float LUSH = 0.15f;

    public static void set(SoilTintPacket p) {
        long key = ChunkPos.asLong(p.chunkX(), p.chunkZ());
        boolean any = false;
        for (byte b : p.dry()) any |= b != 0;
        byte[] was = any ? DRY.put(key, p.dry()) : DRY.remove(key);
        if (was == null && !any) return;
        // The meshes keep the colours they were built with: build them again, the chunk's and, as its cells blend into
        // theirs at the edges, those of the chunks round it.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) return;
        int min = mc.level.getMinSection(), max = mc.level.getMaxSection();
        for (int cx = p.chunkX() - 1; cx <= p.chunkX() + 1; cx++) {
            for (int cz = p.chunkZ() - 1; cz <= p.chunkZ() + 1; cz++) {
                if (mc.level.getChunkSource().getChunk(cx, cz, false) == null) continue;
                for (int sy = min; sy < max; sy++) mc.levelRenderer.setSectionDirty(cx, sy, cz);
            }
        }
    }

    /** One cell, by its index across the world (four columns to a cell): -1 lushest to 1 straw, 0 where nothing was said. */
    private static float cell(int cx, int cz) {
        byte[] d = DRY.get(ChunkPos.asLong(cx >> 2, cz >> 2));
        return d == null ? 0f : d[(cz & 3) * 4 + (cx & 3)] / 15f;
    }

    /** How the grass at a place looks, -1 lushest to 1 straw: the four cells round it, blended by how near their middles are. */
    static float tint(BlockPos pos) {
        double fx = (pos.getX() + 0.5) / 4.0 - 0.5, fz = (pos.getZ() + 0.5) / 4.0 - 0.5;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        float tx = (float) (fx - x0), tz = (float) (fz - z0);
        float north = Mth.lerp(tx, cell(x0, z0), cell(x0 + 1, z0));
        float south = Mth.lerp(tx, cell(x0, z0 + 1), cell(x0 + 1, z0 + 1));
        return Mth.lerp(tz, north, south);
    }

    /** The biome's grass colour, drier where the ground is, deeper where it is wet. */
    public static int grass(BlockState state, BlockAndTintGetter level, BlockPos pos, int tint) {
        if (level == null || pos == null) return GrassColor.get(0.5, 1.0);
        BlockPos at = state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER
                ? pos.below() : pos;
        int green = BiomeColors.getAverageGrassColor(level, at);
        float look = tint(at);
        if (look == 0f) return green;
        if (look < 0f) {
            // Lusher: darker, the red and blue down more than the green, so it reads as a fuller green.
            float s = -look * LUSH;
            int r = (int) ((green >> 16 & 255) * (1 - s * 0.9f));
            int g = (int) ((green >> 8 & 255) * (1 - s * 0.25f));
            int b = (int) ((green & 255) * (1 - s * 0.9f));
            return r << 16 | g << 8 | b;
        }
        float t = look * MOST;
        int r = (int) ((green >> 16 & 255) * (1 - t) + (STRAW >> 16 & 255) * t);
        int g = (int) ((green >> 8 & 255) * (1 - t) + (STRAW >> 8 & 255) * t);
        int b = (int) ((green & 255) * (1 - t) + (STRAW & 255) * t);
        return r << 16 | g << 8 | b;
    }

    /** The blocks drawn this way: vanilla's grass-coloured ones. */
    static final net.minecraft.world.level.block.Block[] GRASSES = {Blocks.GRASS_BLOCK, Blocks.GRASS, Blocks.TALL_GRASS,
            Blocks.FERN, Blocks.LARGE_FERN, Blocks.POTTED_FERN, Blocks.SUGAR_CANE};

    /**
     * Other mods' grass over their own soils, where they are installed: Immersive Weathering's grassy soils and rooted
     * grass, The Roads More Travelled's worn grass. It dries with the ground under it as the game's does.
     */
    static net.minecraft.world.level.block.Block[] otherGrasses() {
        return java.util.stream.Stream.of("immersive_weathering:grassy_silt", "immersive_weathering:grassy_earthen_clay",
                        "immersive_weathering:grassy_sandy_dirt", "immersive_weathering:rooted_grass_block", "trmt:eroded_grass_block")
                .map(net.minecraft.resources.ResourceLocation::new)
                .filter(net.minecraft.core.registries.BuiltInRegistries.BLOCK::containsKey)
                .map(net.minecraft.core.registries.BuiltInRegistries.BLOCK::get)
                .toArray(net.minecraft.world.level.block.Block[]::new);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() != null && event.getLevel().isClientSide()) DRY.remove(event.getChunk().getPos().toLong());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DRY.clear();
    }
}
