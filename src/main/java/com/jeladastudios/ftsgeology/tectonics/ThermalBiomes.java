package com.jeladastudios.ftsgeology.tectonics;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recognises ground the world generator already painted as geothermal, such as Terralith's
 * {@code yellowstone} and {@code caldera}, so {@link HotspotMap} can treat it as a live plume and
 * geysers land where the biome says they should.
 *
 * <p>Yellowstone is a hotspot and a caldera is what one leaves behind, so this reads the landscape
 * the way a geologist would. It writes nothing and matches by name, not mod id, so no terrain mod is
 * required; without one nothing matches.</p>
 */
public final class ThermalBiomes {

    private ThermalBiomes() {}

    /**
     * A name fragment worth reacting to, and how strongly.
     *
     * <p>Ordered most specific first: the first fragment found in the biome id wins, so
     * {@code terralith:yellowstone} is read as a geyser field rather than merely volcanic.</p>
     */
    private record Match(String fragment, double strength, boolean allowsVolcano, String label) {}

    private static final Match[] MATCHES = {
            new Match("yellowstone", 1.00, true,  "a Yellowstone-type thermal basin"),
            new Match("geyser",      1.00, true,  "a geyser field"),
            // A caldera has already collapsed, so no new cone belongs in it, but it stays thermally
            // alive: springs and geysers still do.
            new Match("caldera",     0.95, false, "a collapsed caldera"),
            new Match("crater",      0.90, false, "a volcanic crater"),
            new Match("hot_spring",  0.85, true,  "hot-spring country"),
            new Match("hotspring",   0.85, true,  "hot-spring country"),
            new Match("thermal",     0.85, true,  "thermal ground"),
            new Match("fumarole",    0.85, true,  "fumarole ground"),
            new Match("volcan",      0.80, true,  "volcanic ground"),
            new Match("lava_field",  0.70, true,  "a lava field"),
            new Match("basalt",      0.55, true,  "old basalt flows"),
    };

    /** No match. */
    private static final Match NONE = new Match("", 0.0, true, "");

    /**
     * How thermal the biome at this column is, 0 for ordinary ground and 1 for a named geyser field.
     */
    public static double strength(ServerLevel level, int blockX, int blockZ) {
        return lookup(level, blockX, blockZ).strength();
    }

    /**
     * Plain-language name for what the world generator put here, or an empty string. Used by the
     * inspection command so it is obvious WHY a column is being reported as a hotspot.
     */
    public static String label(ServerLevel level, int blockX, int blockZ) {
        return lookup(level, blockX, blockZ).label();
    }

    /** True unless the world generator has already put a collapsed edifice here. */
    public static boolean allowsVolcano(ServerLevel level, int blockX, int blockZ) {
        return lookup(level, blockX, blockZ).allowsVolcano();
    }

    // === Lookup =============================================================

    /**
     * Cached per quart position. Biomes are painted in 4x4x4 cells anyway, so resolving finer than
     * that would be re-asking the same question, and the map and suitability commands sample tens of
     * thousands of columns at a time.
     */
    private static final Map<Long, Match> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 60000;

    private static Match lookup(ServerLevel level, int blockX, int blockZ) {
        if (!GeyserConfig.BIOME_ANCHORING.get()) return NONE;

        long key = ((long) QuartPos.fromBlock(blockX) & 0xFFFFFFFFL)
                | (((long) QuartPos.fromBlock(blockZ) & 0xFFFFFFFFL) << 32);
        Match hit = CACHE.get(key);
        if (hit != null) return hit;

        Match found = classify(level, blockX, blockZ);
        if (CACHE.size() > CACHE_MAX) CACHE.clear();
        CACHE.put(key, found);
        return found;
    }

    /**
     * Asks the world biome source what sits here, at the chunk generator's surface height. Sea level
     * would read a different biome under a mountain, often a cave biome, and the generator height
     * answers for columns far from any loaded chunk.
     */
    private static Match classify(ServerLevel level, int blockX, int blockZ) {
        try {
            ServerChunkCache chunkSource = level.getChunkSource();
            Climate.Sampler sampler = chunkSource.randomState().sampler();
            BiomeSource biomes = chunkSource.getGenerator().getBiomeSource();

            int surface = chunkSource.getGenerator().getBaseHeight(blockX, blockZ,
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                    level, chunkSource.randomState());
            int sampleY = Math.max(surface - 2, level.getSeaLevel());

            Holder<Biome> biome = biomes.getNoiseBiome(
                    QuartPos.fromBlock(blockX),
                    QuartPos.fromBlock(sampleY),
                    QuartPos.fromBlock(blockZ), sampler);

            ResourceLocation id = biome.unwrapKey().map(k -> k.location()).orElse(null);
            if (id == null) return NONE;
            String path = id.getPath().toLowerCase(Locale.ROOT);
            // Cave biomes sit under everything and must never be mistaken for surface geology.
            if (path.contains("cave") || path.contains("deep_dark")) return NONE;
            for (Match m : MATCHES) {
                if (path.contains(m.fragment())) return m;
            }
            return NONE;
        } catch (Throwable t) {
            // An exotic world generator that cannot answer simply gets no anchoring.
            return NONE;
        }
    }
    /** Dropped alongside the other tectonic caches when a server stops. */
    public static void clearCache() {
        CACHE.clear();
    }
}
