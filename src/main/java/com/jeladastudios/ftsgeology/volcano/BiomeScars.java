package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ground an eruption has just laid bare, made the volcanic highland it now is: where lava has frozen and where a
 * pyroclastic flow has burnt its way down. Nothing grows back on it soon, so nothing pretends to: the biome goes with the
 * ground, as {@code /fillbiome} would set it, and the players near are sent the change. Only in the mod's own world
 * types, where that biome is part of the land.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class BiomeScars {

    private BiomeScars() {}

    private static final ResourceKey<Biome> HIGHLAND = ResourceKey.create(Registries.BIOME,
            new ResourceLocation(GeysersMod.MODID, "volcanic_highland"));
    /** How often the marks are written, ticks. */
    private static final int EVERY = 40;

    /** Per world, each quart column marked, with the height of its ground. */
    private static final Map<ServerLevel, Long2IntOpenHashMap> MARKED = new HashMap<>();

    /** Notes ground at a block an eruption has just laid bare. */
    public static void mark(ServerLevel level, int x, int y, int z) {
        if (!GeyserConfig.VOLCANO_GROUND_BIOME.get() || !GeologyWorld.isOwn(level)) return;
        long key = ChunkPos.asLong(QuartPos.fromBlock(x), QuartPos.fromBlock(z));
        MARKED.computeIfAbsent(level, l -> new Long2IntOpenHashMap()).merge(key, y, Math::max);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % EVERY != 0 || MARKED.isEmpty()) return;
        for (Map.Entry<ServerLevel, Long2IntOpenHashMap> e : MARKED.entrySet()) write(e.getKey(), e.getValue());
        MARKED.clear();
    }

    private static void write(ServerLevel level, Long2IntOpenHashMap marks) {
        Holder<Biome> highland = level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(HIGHLAND).orElse(null);
        if (highland == null) return;
        // By chunk: the quart columns marked in it, with their ground's height.
        Map<Long, Long2IntOpenHashMap> byChunk = new HashMap<>();
        for (Long2IntOpenHashMap.Entry m : marks.long2IntEntrySet()) {
            int qx = ChunkPos.getX(m.getLongKey()), qz = ChunkPos.getZ(m.getLongKey());
            byChunk.computeIfAbsent(ChunkPos.asLong(qx >> 2, qz >> 2), k -> new Long2IntOpenHashMap()).put(m.getLongKey(), m.getIntValue());
        }
        List<ChunkAccess> changed = new ArrayList<>();
        var sampler = level.getChunkSource().randomState().sampler();
        for (Map.Entry<Long, Long2IntOpenHashMap> c : byChunk.entrySet()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(c.getKey()), ChunkPos.getZ(c.getKey()));
            if (chunk == null) continue;
            Long2IntOpenHashMap cols = c.getValue();
            // From a little under the ground to well over it: the cave biomes deeper down are left as they are.
            chunk.fillBiomesFromNoise((qx, qy, qz, s) -> {
                int ground = cols.getOrDefault(ChunkPos.asLong(qx, qz), Integer.MIN_VALUE);
                if (ground != Integer.MIN_VALUE && qy >= QuartPos.fromBlock(ground - 8) && qy <= QuartPos.fromBlock(ground + 48)) {
                    return highland;
                }
                return chunk.getNoiseBiome(qx, qy, qz);
            }, sampler);
            chunk.setUnsaved(true);
            changed.add(chunk);
        }
        if (!changed.isEmpty()) level.getChunkSource().chunkMap.resendBiomesForChunks(changed);
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        MARKED.clear();
    }
}
