package com.jeladastudios.ftsgeology.client;



import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.common.EventBusSubscriber;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grass drawn paler where the ground under it has dried out, and a deeper green where it is soaked or the groundwater
 * is near: the server says how each four-by-four cell of a chunk looks, and grass, ferns and the grass block's top take
 * the biome's colour blended towards straw, or darkened, by that much. The cells are blended into each other, across
 * the chunks' edges too, so no square shows. Nothing is kept past the chunk; where nothing was said, the grass is the
 * biome's own green.
 */
@EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
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
        heard++;
        long key = ChunkPos.asLong(p.chunkX(), p.chunkZ());
        boolean any = false;
        for (byte b : p.dry()) any |= b != 0;
        byte[] was = any ? DRY.put(key, p.dry()) : DRY.remove(key);
        if (was == null && !any) return;
        if (was != null && java.util.Arrays.equals(was, p.dry())) return;
        // The meshes keep the colours they were built with: build them again, the chunk's and, as its cells blend into
        // theirs at the edges, those of the chunks round it -- a few chunks a tick (see onClientTick).
        for (int cx = p.chunkX() - 1; cx <= p.chunkX() + 1; cx++) {
            for (int cz = p.chunkZ() - 1; cz <= p.chunkZ() + 1; cz++) REDRAW.add(ChunkPos.asLong(cx, cz));
        }
    }

    /**
     * Chunks whose grass is to be drawn again, each once however many of its neighbours changed. Every change used to
     * build nine chunks again at once from the bottom of the world to the top -- sixty sections a chunk in the tall
     * world -- and where the ground was wet and changing, by a river in the rain, that was hundreds of sections a
     * second for the game to build over again.
     */
    private static final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet REDRAW = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    /** Chunks drawn again a tick, at most. */
    private static final int REDRAW_A_TICK = 8;
    /** Sections drawn again for the grass's colour, counted for the debug screen, and the count of the last second. */
    private static int marked, markedLastSecond, heard, heardLastSecond;
    private static long secondStarted;

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) {
            REDRAW.clear();
            return;
        }
        for (int n = 0; n < REDRAW_A_TICK && !REDRAW.isEmpty(); n++) {
            long key = REDRAW.removeFirstLong();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
            if (chunk == null) continue;
            // Only the sections the grass can be in: from a little under the lowest ground of the chunk to its highest.
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                    low = Math.min(low, y);
                    high = Math.max(high, y);
                }
            }
            int from = Math.max(mc.level.getMinSection(), net.minecraft.core.SectionPos.blockToSectionCoord(low - 3));
            int to = Math.min(mc.level.getMaxSection() - 1, net.minecraft.core.SectionPos.blockToSectionCoord(high + 1));
            for (int sy = from; sy <= to; sy++) {
                mc.levelRenderer.setSectionDirty(cx, sy, cz);
                marked++;
            }
        }
        long now = net.minecraft.Util.getMillis();
        if (now - secondStarted >= 1000) {
            markedLastSecond = marked;
            heardLastSecond = heard;
            heard = 0;
            marked = 0;
            secondStarted = now;
        }
    }

    /** The grass's colour on the debug screen (F3): chunks waiting to be drawn again, and sections drawn in the last second. */
    @SubscribeEvent
    public static void onDebugText(net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
        if (Minecraft.getInstance().level == null || !Minecraft.getInstance().getDebugOverlay().showDebugScreen()) return;
        if (DRY.isEmpty() && REDRAW.isEmpty() && markedLastSecond == 0 && heardLastSecond == 0) return;
        event.getLeft().add("Grass colour: " + DRY.size() + " chunks tinted, " + REDRAW.size() + " waiting, "
                + markedLastSecond + " sections drawn again a second, " + heardLastSecond + " changes heard a second");
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
    static final net.minecraft.world.level.block.Block[] GRASSES = {Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
            Blocks.FERN, Blocks.LARGE_FERN, Blocks.POTTED_FERN, Blocks.SUGAR_CANE};

    /**
     * Other mods' grass over their own soils, where they are installed: Immersive Weathering's grassy soils and rooted
     * grass, The Roads More Travelled's worn grass. It dries with the ground under it as the game's does.
     */
    static net.minecraft.world.level.block.Block[] otherGrasses() {
        return java.util.stream.Stream.of("immersive_weathering:grassy_silt", "immersive_weathering:grassy_earthen_clay",
                        "immersive_weathering:grassy_sandy_dirt", "immersive_weathering:rooted_grass_block", "trmt:eroded_grass_block")
                .map(net.minecraft.resources.ResourceLocation::parse)
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
        REDRAW.clear();
    }
}
