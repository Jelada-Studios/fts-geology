package com.pandabear.geysers.tectonics;

import com.pandabear.geysers.config.GeyserConfig;
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
 * Recognises ground that the world generator has <em>already</em> painted as geothermal, and lets
 * our own geology settle on top of it.
 *
 * <h2>The problem this solves</h2>
 * Terralith ships biomes called {@code yellowstone} and {@code caldera}. They look the part - hot
 * colours, sinter-pale ground, the right trees - and they are exactly where a player expects to find
 * geysers. But our plume grid is pure seed maths and knows nothing about them, so the two systems
 * landed in different places: Terralith's Yellowstone had no geysers in it, and our hotspot sat in an
 * ordinary forest.
 *
 * <p>We cannot tell Terralith where to put its biomes - that would mean taking over the world's biome
 * source, with all the mod conflicts that implies. But we can go where it already went. This reads
 * the biome at a column and reports how thermal it is; {@link HotspotMap} then treats strongly
 * thermal ground as a live mantle plume.</p>
 *
 * <h2>Why that is honest geology, not a hack</h2>
 * Yellowstone <b>is</b> a hotspot, and a caldera is what hotspot volcanism leaves behind when the
 * chamber empties and the roof falls in. Treating a biome named after either as evidence of a plume
 * underneath it is reading the landscape the way a geologist would, not inventing a connection.
 *
 * <h2>Why it is safe</h2>
 * Nothing here writes anything, nothing is registered, and matching is by name rather than by mod id,
 * so Terralith, Tectonic, Biomes O' Plenty or anything else works without a dependency. With no such
 * mod installed nothing matches and the plume grid behaves exactly as before.
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
            // A caldera is a system that has ALREADY blown its roof off and collapsed. The edifice is
            // sitting there in the terrain, so building a fresh volcano inside it would be nonsense.
            // It stays thermally alive though - Yellowstone is a caldera and holds half the geysers
            // on Earth - so springs and geysers belong here even though a new cone does not.
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
     * Asks the world biome source what sits here.
     *
     * <h2>Read at the surface, not at sea level</h2>
     * This used to sample at {@code seaLevel}, which is the wrong place: a biome is a 3D field, and
     * Y=63 under a mountain-top caldera is a different biome entirely - very often a cave biome. That
     * single line caused both of the odd results seen in testing. Terralith's <b>caldera</b> reported
     * no thermal ground at all, because the biome read at sea level under it was not the caldera; and
     * a plume was reported over ordinary desert because the CAVE biome beneath it happened to be
     * called "thermal caves".
     *
     * <p>The height comes from the chunk generator's own base-height function rather than the
     * heightmap, so this still answers for columns nowhere near a loaded chunk - which matters,
     * because the map render and the {@code find} search ask about tens of thousands of them.</p>
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
