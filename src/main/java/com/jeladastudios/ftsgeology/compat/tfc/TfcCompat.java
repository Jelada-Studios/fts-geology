package com.jeladastudios.ftsgeology.compat.tfc;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TerraFirmaCraft, when it is there. TFC keeps its own rock, twenty of them, laid in its own layers, and its own soil
 * and sand; a volcano built of vanilla basalt or a vein cased in vanilla stone stands out in its world like a mod.
 * Everything the mod writes into the ground goes through {@link #translate}, which on a TFC world swaps each block
 * for TFC's: the volcanic rocks for TFC's basalt and andesite, the mod's own metamorphic and plutonic rocks for
 * TFC's of the same name, and plain stone, gravel, cobble and soil for the rock and soil that are already there,
 * read from the column itself. Where TFC has no counterpart (sinter, travertine, the ore minerals, serpentinite)
 * the mod's block stays.
 *
 * <p>TFC's biomes carry none of vanilla's biome tags, so the plates' reading of sea and river goes through
 * {@link #ocean} and {@link #river}, which also ask TFC's own tags. Nothing here names a TFC class: the blocks are
 * looked up by id, so the mod builds and runs without TFC and wakes up when it is loaded.</p>
 */
public final class TfcCompat {

    private TfcCompat() {}

    private static volatile Boolean active;

    /** Whether TFC is loaded and the compatibility is switched on. */
    public static boolean active() {
        Boolean a = active;
        if (a == null) {
            a = ModList.get().isLoaded("tfc") && GeyserConfig.TFC_COMPAT.get();
            active = a;
        }
        return a;
    }

    // === Biomes =============================================================

    private static final TagKey<Biome> TFC_OCEAN = TagKey.create(Registries.BIOME, new ResourceLocation("tfc", "is_ocean"));
    private static final TagKey<Biome> TFC_RIVER = TagKey.create(Registries.BIOME, new ResourceLocation("tfc", "is_river"));

    /** Sea, by vanilla's tags or TFC's. */
    public static boolean ocean(Holder<Biome> b) {
        return b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) || b.is(TFC_OCEAN);
    }

    /** A river, by vanilla's tag or TFC's. */
    public static boolean river(Holder<Biome> b) {
        return b.is(BiomeTags.IS_RIVER) || b.is(TFC_RIVER);
    }

    /** A beach: vanilla's tag, or TFC's shore and tidal flats, which it does not tag. */
    public static boolean beach(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_BEACH)) return true;
        return b.unwrapKey().map(k -> k.location().getNamespace().equals("tfc")
                && (k.location().getPath().equals("shore") || k.location().getPath().equals("tidal_flats"))).orElse(false);
    }

    // === Blocks =============================================================

    /**
     * What each block the mod writes becomes in a TFC world: TFC's id with {@code %r} for the column's own rock,
     * {@code %s} for its soil and {@code %d} for the sand that rock weathers to. Built once the blocks exist.
     */
    private static Map<Block, String> map;

    private static Map<Block, String> map() {
        Map<Block, String> m = map;
        if (m != null) return m;
        m = new HashMap<>();
        // The country rock: whatever TFC laid in this column.
        m.put(Blocks.STONE, "rock/raw/%r");
        m.put(Blocks.DEEPSLATE, "rock/hardened/%r");
        m.put(Blocks.COBBLESTONE, "rock/cobble/%r");
        m.put(Blocks.COBBLED_DEEPSLATE, "rock/cobble/%r");
        m.put(Blocks.GRAVEL, "rock/gravel/%r");
        // Volcanic rock, which is its own rock wherever it is.
        m.put(Blocks.BASALT, "rock/raw/basalt");
        m.put(Blocks.SMOOTH_BASALT, "rock/hardened/basalt");
        m.put(Blocks.BLACKSTONE, "rock/raw/basalt");
        m.put(Blocks.MAGMA_BLOCK, "rock/magma/basalt");
        m.put(Blocks.TUFF, "rock/gravel/basalt");       // TFC has no tuff; ash reads as dark gravel
        m.put(Blocks.ANDESITE, "rock/raw/andesite");
        m.put(Blocks.DIORITE, "rock/raw/diorite");
        m.put(Blocks.GRANITE, "rock/raw/granite");
        m.put(Blocks.CALCITE, "rock/raw/limestone");
        // The mod's own rocks that TFC also has.
        m.put(ModBlocks.GABBRO.get(), "rock/raw/gabbro");
        m.put(ModBlocks.GNEISS.get(), "rock/raw/gneiss");
        m.put(ModBlocks.SCHIST.get(), "rock/raw/schist");
        m.put(ModBlocks.SLATE.get(), "rock/raw/slate");
        m.put(ModBlocks.MARBLE.get(), "rock/raw/marble");
        m.put(ModBlocks.QUARTZITE.get(), "rock/raw/quartzite");
        m.put(ModBlocks.SHALE.get(), "rock/raw/shale");
        m.put(ModBlocks.CHERT.get(), "rock/raw/chert");
        m.put(ModBlocks.RHYOLITE.get(), "rock/raw/rhyolite");
        // The ores the deposits lay: TFC's of the same metal, in the column's own rock.
        m.put(Blocks.GOLD_ORE, "ore/normal_native_gold/%r");
        m.put(Blocks.DEEPSLATE_GOLD_ORE, "ore/normal_native_gold/%r");
        m.put(Blocks.COPPER_ORE, "ore/normal_native_copper/%r");
        m.put(Blocks.DEEPSLATE_COPPER_ORE, "ore/normal_native_copper/%r");
        m.put(Blocks.IRON_ORE, "ore/normal_hematite/%r");
        m.put(Blocks.COAL_ORE, "ore/bituminous_coal/%r");
        m.put(Blocks.DEEPSLATE_COAL_ORE, "ore/bituminous_coal/%r");
        m.put(Blocks.EMERALD_ORE, "ore/emerald/%r");
        m.put(Blocks.DEEPSLATE_EMERALD_ORE, "ore/emerald/%r");
        m.put(Blocks.LAPIS_ORE, "ore/lapis_lazuli/%r");
        m.put(Blocks.DEEPSLATE_LAPIS_ORE, "ore/lapis_lazuli/%r");
        // Soil and sand: TFC's, of the kind already round the column.
        m.put(Blocks.DIRT, "dirt/%s");
        m.put(Blocks.COARSE_DIRT, "dirt/%s");
        m.put(Blocks.ROOTED_DIRT, "rooted_dirt/%s");
        m.put(Blocks.GRASS_BLOCK, "grass/%s");
        m.put(Blocks.MUD, "mud/%s");
        m.put(Blocks.SAND, "sand/%d");
        map = m;
        return m;
    }

    /** The colour of sand each TFC rock weathers to, as TFC has it. */
    private static final Map<String, String> SAND = Map.ofEntries(
            Map.entry("granite", "brown"), Map.entry("diorite", "white"), Map.entry("gabbro", "black"),
            Map.entry("shale", "black"), Map.entry("claystone", "brown"), Map.entry("limestone", "white"),
            Map.entry("conglomerate", "green"), Map.entry("dolomite", "black"), Map.entry("chert", "yellow"),
            Map.entry("chalk", "white"), Map.entry("rhyolite", "red"), Map.entry("basalt", "red"),
            Map.entry("andesite", "red"), Map.entry("dacite", "red"), Map.entry("quartzite", "yellow"),
            Map.entry("slate", "brown"), Map.entry("phyllite", "brown"), Map.entry("schist", "green"),
            Map.entry("gneiss", "green"), Map.entry("marble", "white"));

    private static final Map<String, Block> RESOLVED = new ConcurrentHashMap<>();

    /**
     * The block to write at a position instead of {@code state}: the same state off a TFC world, TFC's counterpart
     * on one, or the state itself where TFC has none.
     */
    public static BlockState translate(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!active()) return state;
        String pattern = map().get(state.getBlock());
        if (pattern == null) return state;
        String id = pattern;
        if (id.contains("%r") || id.contains("%d")) {
            String rock = localRock(level, pos.getX(), pos.getZ());
            id = id.replace("%r", rock).replace("%d", SAND.getOrDefault(rock, "brown"));
        }
        if (id.contains("%s")) id = id.replace("%s", localSoil(level, pos.getX(), pos.getZ()));
        Block t = RESOLVED.computeIfAbsent(id, k -> {
            Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tfc", k));
            return b == null ? Blocks.AIR : b;
        });
        return t == Blocks.AIR ? state : t.defaultBlockState();
    }

    /** Whether a block is one of TFC's raw or hardened rocks: the country rock of a TFC world. */
    public static boolean isRock(BlockState state) {
        if (!active()) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && id.getNamespace().equals("tfc")
                && (id.getPath().startsWith("rock/raw/") || id.getPath().startsWith("rock/hardened/"));
    }

    /**
     * Whether a block is TFC's natural ground: its raw, hardened and magma rock, gravel, soil, grass, sand, clay and
     * snow and ice. Whether TFC tags them with vanilla's stone and soil tags is not relied on; cobble and bricks, which
     * build houses, are left out.
     */
    public static boolean isGround(BlockState state) {
        if (!active()) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !id.getNamespace().equals("tfc")) return false;
        String p = id.getPath();
        return p.startsWith("rock/raw/") || p.startsWith("rock/hardened/") || p.startsWith("rock/magma/")
                || p.startsWith("rock/gravel/") || p.startsWith("dirt/") || p.startsWith("grass/")
                || p.startsWith("sand/") || p.startsWith("clay/") || p.startsWith("clay_grass/")
                || p.startsWith("mud/") || p.startsWith("rooted_dirt/") || p.equals("snow_pile")
                || p.equals("ice_pile") || p.equals("sea_ice") || p.equals("peat") || p.equals("peat_grass");
    }

    /** TFC's snow and ice that a hot spring melts: its snow pile, ice pile and sea ice. */
    public static boolean meltsToAir(BlockState state) {
        if (!active()) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && id.getNamespace().equals("tfc") && id.getPath().equals("snow_pile");
    }

    /** TFC's ice, which a hot spring melts to water. */
    public static boolean meltsToWater(BlockState state) {
        if (!active()) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && id.getNamespace().equals("tfc")
                && (id.getPath().equals("ice_pile") || id.getPath().equals("sea_ice"));
    }

    // === Reading the column =================================================

    /** The rock and soil TFC laid under each chunk, read once. Dropped when the server stops. */
    private static final Map<Long, String[]> COLUMNS = new ConcurrentHashMap<>();

    public static void clear() {
        COLUMNS.clear();
        active = null;
    }

    private static String[] column(LevelAccessor level, int x, int z) {
        long key = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
        String[] known = COLUMNS.get(key);
        if (known != null) return known;
        String rock = "granite", soil = "loam";
        boolean rockFound = false, soilFound = false;
        int lx = (x & ~15) + 8, lz = (z & ~15) + 8;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = top; y >= Math.max(level.getMinBuildHeight(), top - 60) && !(rockFound && soilFound); y--) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(m.set(lx, y, lz)).getBlock());
            if (id == null || !id.getNamespace().equals("tfc")) continue;
            String path = id.getPath();
            if (!rockFound && (path.startsWith("rock/raw/") || path.startsWith("rock/hardened/"))) {
                rock = path.substring(path.lastIndexOf('/') + 1);
                rockFound = true;
            } else if (!soilFound && (path.startsWith("grass/") || path.startsWith("dirt/"))) {
                soil = path.substring(path.indexOf('/') + 1);
                soilFound = true;
            }
        }
        String[] found = {rock, soil};
        COLUMNS.put(key, found);
        return found;
    }

    /** The TFC rock of the chunk round a column, by name, granite where none was found. */
    public static String localRock(LevelAccessor level, int x, int z) {
        return column(level, x, z)[0];
    }

    /** The TFC soil variant (silt, loam, sandy_loam, silty_loam) round a column, loam where none was found. */
    public static String localSoil(LevelAccessor level, int x, int z) {
        return column(level, x, z)[1];
    }
}
