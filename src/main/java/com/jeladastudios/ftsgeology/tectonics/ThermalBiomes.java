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

import com.jeladastudios.ftsgeology.util.ColumnCache;

import java.util.Locale;

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
            // Burnt's Geothermal biome: its own springs and sulphur go with our basins rather than beside them.
            new Match("geothermal",  0.90, true,  "a geothermal field"),
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

    /**
     * Is this a caldera's floor (Terralith's {@code caldera} and the like)? The mod lays no soil of its own there: the
     * ground inside a collapsed volcano is ash, pumice and hydrothermally altered rock, and the soil it painted
     * round its springs and vents read as a lawn in a crater.
     */
    public static boolean isCaldera(ServerLevel level, int blockX, int blockZ) {
        return lookup(level, blockX, blockZ).fragment().equals("caldera");
    }

    /** True unless the world generator has already put a collapsed edifice here. */
    public static boolean allowsVolcano(ServerLevel level, int blockX, int blockZ) {
        return lookup(level, blockX, blockZ).allowsVolcano();
    }

    // === Lookup =============================================================

    /**
     * Cached per 16-block cell, read at the cell's centre so every column in it gets the same answer
     * whichever asks first. Thermal biomes are hundreds of blocks across, and the map and suitability
     * commands sample tens of thousands of columns at a time.
     */
    private static final ColumnCache<Match> CACHE = new ColumnCache<>(15);

    private static Match lookup(ServerLevel level, int blockX, int blockZ) {
        if (!GeyserConfig.BIOME_ANCHORING.get()) return NONE;

        int cellX = blockX >> 4, cellZ = blockZ >> 4;
        long key = ColumnCache.key(cellX, cellZ);
        Match hit = CACHE.get(key);
        if (hit != null) return hit;

        Match found = classify(level, (cellX << 4) + 8, (cellZ << 4) + 8);
        CACHE.put(key, found);
        return found;
    }

    /**
     * Asks the world biome source what sits here, read above the terrain. Up there the noise depth is
     * negative, which always matches a surface biome and never a cave biome, so no terrain height is
     * needed: finding one ran the whole noise column and was most of this mod's retrogen cost.
     */
    private static Match classify(ServerLevel level, int blockX, int blockZ) {
        try {
            ServerChunkCache chunkSource = level.getChunkSource();
            Climate.Sampler sampler = chunkSource.randomState().sampler();
            BiomeSource biomes = chunkSource.getGenerator().getBiomeSource();

            int sampleY = level.getMaxBuildHeight() - 8;

            Holder<Biome> biome = biomes.getNoiseBiome(
                    QuartPos.fromBlock(blockX),
                    QuartPos.fromBlock(sampleY),
                    QuartPos.fromBlock(blockZ), sampler);

            ResourceLocation id = biome.unwrapKey().map(k -> k.location()).orElse(null);
            if (id == null) return NONE;
            // Our own biomes are where they are because the geology put them there. Reading them back as
            // evidence of geology would let a geothermal basin argue itself into being hotter.
            if (id.getNamespace().equals(com.jeladastudios.ftsgeology.GeysersMod.MODID)) return NONE;
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
