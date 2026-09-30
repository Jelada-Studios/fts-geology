package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.SoilTintPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
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
 * Grass drawn paler where the ground under it has dried out: the server says how dry each four-by-four cell of a
 * chunk is, and grass, ferns and the grass block's top take the biome's colour blended towards straw by that much.
 * Nothing is kept past the chunk; where nothing was said, the grass is the biome's own green.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientSoilTint {

    private ClientSoilTint() {}

    /** Dryness by chunk, sixteen cells from 0 to 15. */
    private static final Map<Long, byte[]> DRY = new ConcurrentHashMap<>();
    /** The colour of dry grass, and how far towards it the driest goes. */
    private static final int STRAW = 0xC2A864;
    private static final float MOST = 0.8f;

    public static void set(SoilTintPacket p) {
        long key = ChunkPos.asLong(p.chunkX(), p.chunkZ());
        boolean any = false;
        for (byte b : p.dry()) any |= b != 0;
        byte[] was = any ? DRY.put(key, p.dry()) : DRY.remove(key);
        if (was == null && !any) return;
        // The chunk's meshes keep the colours they were built with: build them again.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) return;
        int min = mc.level.getMinSection(), max = mc.level.getMaxSection();
        for (int sy = min; sy < max; sy++) mc.levelRenderer.setSectionDirty(p.chunkX(), sy, p.chunkZ());
    }

    /** How dry the grass at a place is, 0 to 1. */
    static float dryness(BlockPos pos) {
        byte[] d = DRY.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (d == null) return 0f;
        return d[((pos.getZ() & 15) >> 2) * 4 + ((pos.getX() & 15) >> 2)] / 15f;
    }

    /** The biome's grass colour, drier where the ground is. */
    public static int grass(BlockState state, BlockAndTintGetter level, BlockPos pos, int tint) {
        if (level == null || pos == null) return GrassColor.get(0.5, 1.0);
        BlockPos at = state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER
                ? pos.below() : pos;
        int green = BiomeColors.getAverageGrassColor(level, at);
        float t = dryness(at) * MOST;
        if (t <= 0f) return green;
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
