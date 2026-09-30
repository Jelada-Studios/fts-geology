package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBiomeModifiers;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One ore of a kind. Several mods of a pack add the same metal's ore -- tin, lead, silver, uranium in four and five --
 * each its own block with its own look, placed by its own features: the same ground holds two tins, and twice as much
 * tin. The ores are grouped by Forge's {@code forge:ores/<metal>} tags, and by name ({@code tin_ore},
 * {@code deepslate_tin_ore}, {@code oretin}) for a mod that tags none, and in each group one mod's ore is kept, the first
 * of {@code oreUnifyOrder}, in each of its forms: stone, deepslate, or another rock named in the id (a mod's ore in
 * calcite or basalt is a form of its own, left alone).
 *
 * <p>Two things make it so. Where that mod places its ore in a biome, the others' features placing the same ore there
 * are taken out ({@link Remove}, a biome modifier): as much of the metal as one mod would put, not twice. And a new
 * chunk, as it comes in, has any of the others' ore left in it -- from a feature of a biome the kept mod leaves out, or
 * placed by code of its own -- turned into the kept one, section by section, looked for in each section's palette
 * first so that a chunk with none costs nearly nothing. Chunks made before stay as they were unless
 * {@code unifyOresInExistingChunks} is on. Blocks only, by their ids: no mod's code is touched.</p>
 *
 * <p>With {@code geologicalModOres} on, in the own world type, every mod's features for the metals the geology knows
 * ({@code OreGenesis.MOD_METALS}) are taken out, and {@code OreGenesis} lays the kept ores in deposits of their own:
 * tin in greisen, lead and zinc in limestone, chromium in ophiolite, uranium in sandstone.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class OreUnification {

    private OreUnification() {}

    /** Each other ore to the kept one of its group and form; built from the tags on first use. */
    private static volatile Map<Block, BlockState> replace;
    /** The kept ore of each metal and form ("tin/deepslate"), a group of one mod's ores included. */
    private static volatile Map<String, Block> kept = Map.of();
    /** The metal of every ore in a group. */
    private static volatile Map<Block, String> metalOf = Map.of();
    /** Other mods' ores of the metals the geology lays, to the rock they sit in: see {@link #geoReplacements}. */
    private static volatile Map<Block, BlockState> strip = Map.of();
    private static volatile Map<Block, BlockState> geoReplace;

    public static void clear() {
        replace = null;
        strip = Map.of();
        geoReplace = null;
        BY_STATE.clear();
        kept = Map.of();
        metalOf = Map.of();
    }

    /**
     * The ore a pack has for a metal, in stone or deepslate: the kept one of its group where several mods add it, the
     * one mod's where only one does; null where no mod adds it. For the geology to lay other mods' ores where they
     * belong (see {@code OreGenesis}).
     */
    public static Block oreOf(String metal, boolean deep) {
        replacements();
        Block b = kept.get(metal + "/" + (deep ? "deepslate" : "stone"));
        return b != null ? b : kept.get(metal + "/" + (deep ? "stone" : "deepslate"));
    }

    private static boolean on() {
        return GeyserConfig.UNIFY_ORES.get() && !com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.active();
    }

    /**
     * Whether other mods' ores are laid by the geology (see {@code OreGenesis}): on, and the overworld is the mod's own
     * world type, the only one whose rock the geology knows.
     */
    public static boolean geological() {
        if (!GeyserConfig.GEOLOGICAL_MOD_ORES.get() || com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.active()) return false;
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        return stem != null && com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(stem.generator());
    }

    /** The replacements, worked out from the ore tags now bound. */
    static Map<Block, BlockState> replacements() {
        Map<Block, BlockState> r = replace;
        if (r != null) return r;
        synchronized (OreUnification.class) {
            if (replace != null) return replace;
            replace = build();
            return replace;
        }
    }

    /** An ore's name the way mods write it with no tag: {@code lead_ore}, {@code deepslate_lead_ore}, {@code lead_ore_deepslate}, {@code orelead}. */
    private static final java.util.regex.Pattern UNTAGGED = java.util.regex.Pattern.compile(
            "^(?:deepslate_)?([a-z]+)_ore(?:_deepslate)?$|^(?:deepslate)?ore([a-z]+)$");

    private static Map<Block, BlockState> build() {
        List<? extends String> order = GeyserConfig.UNIFY_ORES_ORDER.get();
        Map<Block, BlockState> out = new HashMap<>();
        Set<Block> grouped = new HashSet<>();
        // Metal to form (stone, deepslate, the rock it sits in) to its ores.
        Map<String, Map<String, List<Block>>> metals = new java.util.TreeMap<>();
        BuiltInRegistries.BLOCK.getTagNames().forEach(tag -> {
            ResourceLocation id = tag.location();
            if (!id.getNamespace().equals("forge") || !id.getPath().startsWith("ores/")) return;
            String metal = id.getPath().substring(5);
            for (Holder<Block> h : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                if (!grouped.add(h.value())) continue;          // in another group already: left to that one
                metals.computeIfAbsent(metal, k -> new HashMap<>()).computeIfAbsent(form(h), k -> new ArrayList<>()).add(h.value());
            }
        });
        // A mod that tags none of its ores still names them so: joined to the metal's group where there is one.
        for (Block b : BuiltInRegistries.BLOCK) {
            if (grouped.contains(b)) continue;
            java.util.regex.Matcher mt = UNTAGGED.matcher(BuiltInRegistries.BLOCK.getKey(b).getPath());
            if (!mt.matches()) continue;
            String metal = mt.group(1) != null ? mt.group(1) : mt.group(2);
            Map<String, List<Block>> forms = metals.get(metal);
            if (forms == null) continue;
            grouped.add(b);
            forms.computeIfAbsent(form(b.builtInRegistryHolder()), k -> new ArrayList<>()).add(b);
        }
        Map<String, Block> keep = new HashMap<>();
        Map<Block, String> metalMap = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Block>>> e : metals.entrySet()) {
            for (List<Block> bs : e.getValue().values()) for (Block b : bs) metalMap.put(b, e.getKey());
        }
        metalOf = metalMap;
        List<String> report = new ArrayList<>();
        Map<Block, BlockState> stripMap = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Block>>> metal : metals.entrySet()) {
            for (Map.Entry<String, List<Block>> e : metal.getValue().entrySet()) {
                List<Block> members = e.getValue();
                Set<String> mods = new HashSet<>();
                for (Block b : members) mods.add(BuiltInRegistries.BLOCK.getKey(b).getNamespace());
                Block kept = null;
                for (Block b : members) if (kept == null || before(b, kept, order)) kept = b;
                keep.put(metal.getKey() + "/" + e.getKey(), kept);
                if (mods.size() < 2) continue;
                // Where the geology lays the metal, the other mods' ore of it in stone or deepslate goes back to rock.
                if (com.jeladastudios.ftsgeology.worldgen.OreGenesis.MOD_METALS.contains(metal.getKey())
                        && (e.getKey().equals("stone") || e.getKey().equals("deepslate"))) {
                    BlockState rock = (e.getKey().equals("deepslate") ? net.minecraft.world.level.block.Blocks.DEEPSLATE
                            : net.minecraft.world.level.block.Blocks.STONE).defaultBlockState();
                    String keptMod = BuiltInRegistries.BLOCK.getKey(kept).getNamespace();
                    for (Block b : members) if (!BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals(keptMod)) stripMap.put(b, rock);
                }
                StringBuilder from = new StringBuilder();
                for (Block b : members) {
                    if (b == kept || BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals(BuiltInRegistries.BLOCK.getKey(kept).getNamespace())) continue;
                    out.put(b, kept.defaultBlockState());
                    from.append(from.length() == 0 ? "" : " ").append(BuiltInRegistries.BLOCK.getKey(b));
                }
                report.add(metal.getKey() + "/" + e.getKey() + " -> " + BuiltInRegistries.BLOCK.getKey(kept) + " (from " + from + ")");
            }
        }
        kept = keep;
        strip = stripMap;
        if (!out.isEmpty()) GeysersMod.LOGGER.info("Ores made one ({} blocks): {}", out.size(), String.join(", ", report));
        return out;
    }

    private static final TagKey<Block> IN_STONE = TagKey.create(Registries.BLOCK, new ResourceLocation("forge", "ores_in_ground/stone"));
    private static final TagKey<Block> IN_DEEPSLATE = TagKey.create(Registries.BLOCK, new ResourceLocation("forge", "ores_in_ground/deepslate"));
    private static final TagKey<Block> IN_NETHERRACK = TagKey.create(Registries.BLOCK, new ResourceLocation("forge", "ores_in_ground/netherrack"));

    /** Rocks an ore may sit in, by name: ores in different rock are different blocks on purpose, never made one. */
    private static final String[] ROCKS = {"deepslate", "blackstone", "basalt", "netherrack", "nether", "end_stone", "endstone",
            "granite", "diorite", "andesite", "tuff", "calcite", "dripstone", "sandstone", "sand", "gravel", "cobble", "marble",
            "limestone", "slate", "shale", "gneiss", "schist", "quartzite", "gabbro", "peridotite", "rhyolite"};

    /**
     * Which form of an ore a block is: the rock its name puts it in (Spelunkery's granite, tuff and andesite ores are
     * their own, not copies of the stone one), or Forge's ground tags, or plain stone.
     */
    private static String form(Holder<Block> h) {
        String path = BuiltInRegistries.BLOCK.getKey(h.value()).getPath();
        if (path.contains("raw_") || path.endsWith("_block")) return "other:" + path;   // never mixed with an ore
        // AllTheOres' "other" ores are its ore in some other ground, not a copy of the stone one.
        if (path.startsWith("other_") || path.contains("_other_")) return "other";
        for (String rock : ROCKS) if (path.contains(rock)) return rock.equals("nether") ? "netherrack" : rock;
        if (h.is(IN_DEEPSLATE)) return "deepslate";
        if (h.is(IN_NETHERRACK)) return "netherrack";
        if (h.is(IN_STONE)) return "stone";
        return "stone";
    }

    /** Whether ore {@code a} comes before {@code b}: its mod earlier in the order, or by name. */
    private static boolean before(Block a, Block b, List<? extends String> order) {
        ResourceLocation ia = BuiltInRegistries.BLOCK.getKey(a), ib = BuiltInRegistries.BLOCK.getKey(b);
        int ra = order.indexOf(ia.getNamespace()), rb = order.indexOf(ib.getNamespace());
        if (ra < 0) ra = Integer.MAX_VALUE;
        if (rb < 0) rb = Integer.MAX_VALUE;
        if (ra != rb) return ra < rb;
        int c = ia.getNamespace().compareTo(ib.getNamespace());
        if (c != 0) return c < 0;
        return ia.getPath().length() < ib.getPath().length();
    }

    // === New chunks ============================================================

    /**
     * What a new overworld chunk's ores turn into where the geology lays other mods' metals: the unification's
     * replacements, and on top of them every other mod's ore of those metals back to its rock. Their features are
     * taken out (see {@link Remove}), but a mod that places its ore by code of its own still puts it in; left, it would
     * be made the kept ore and scattered through the ground the deposits are meant to hold. A metal only one mod adds
     * keeps that mod's own placing beside the deposits', since the two cannot be told apart.
     */
    static Map<Block, BlockState> geoReplacements() {
        Map<Block, BlockState> g = geoReplace;
        if (g != null) return g;
        Map<Block, BlockState> r = replacements();
        Map<Block, BlockState> m = new HashMap<>(on() ? r : Map.of());
        m.putAll(strip);
        geoReplace = m;
        return m;
    }

    /** Every state of each replaced block to what it becomes, looked up by identity: the chunk pass reads each block once. */
    private static final Map<Map<Block, BlockState>, it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<BlockState, BlockState>> BY_STATE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<BlockState, BlockState> byState(Map<Block, BlockState> blocks) {
        return BY_STATE.computeIfAbsent(blocks, b -> {
            var m = new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<BlockState, BlockState>();
            for (Map.Entry<Block, BlockState> e : b.entrySet()) {
                for (BlockState s : e.getKey().getStateDefinition().getPossibleStates()) m.put(s, e.getValue());
            }
            return m;
        });
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        boolean geo = event.isNewChunk() && net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension()) && geological();
        if (!geo && (!on() || (!event.isNewChunk() && !GeyserConfig.UNIFY_EXISTING_CHUNKS.get()))) return;
        Map<Block, BlockState> blocks = geo ? geoReplacements() : replacements();
        if (blocks.isEmpty()) return;
        it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<BlockState, BlockState> r = byState(blocks);
        boolean changed = false;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir() || !section.getStates().maybeHas(r::containsKey)) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState to = r.get(section.getBlockState(x, y, z));
                        if (to == null) continue;
                        section.setBlockState(x, y, z, to, false);
                        changed = true;
                    }
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }

    // === Features placed twice ==================================================

    /** The ores a placed feature puts, if it is an ore feature; empty otherwise. */
    private static List<Block> ores(Holder<PlacedFeature> placed) {
        ConfiguredFeature<?, ?> cf = placed.value().feature().value();
        if (!(cf.config() instanceof OreConfiguration oc)) return List.of();
        List<Block> out = new ArrayList<>();
        for (OreConfiguration.TargetBlockState t : oc.targetStates) out.add(t.state.getBlock());
        return out;
    }

    /**
     * Takes out of a biome the ore features that place only other mods' ore of a group whose kept ore another feature
     * of the same biome already places. Where the kept mod places none there, the others' features stay (and their ore
     * is turned into the kept one as the chunk comes in).
     */
    public record Remove() implements BiomeModifier {
        public static final Codec<Remove> CODEC = Codec.unit(new Remove());

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase != Phase.REMOVE) return;
            // The geology laying these metals itself: every mod's ore feature for them goes, in the overworld, whose
            // rock the geology knows. A feature's other blocks that are no ore of a group (the raw metal blocks some
            // mods scatter through the vein) go with it; one ore of a metal the geology does not lay keeps it.
            if (geological() && biome.is(net.minecraft.tags.BiomeTags.IS_OVERWORLD)) {
                replacements();
                for (GenerationStep.Decoration step : new GenerationStep.Decoration[]{
                        GenerationStep.Decoration.UNDERGROUND_ORES, GenerationStep.Decoration.UNDERGROUND_DECORATION}) {
                    builder.getGenerationSettings().getFeatures(step).removeIf(f -> {
                        boolean laid = false;
                        for (Block b : ores(f)) {
                            String m = metalOf.get(b);
                            if (m == null) continue;
                            if (!com.jeladastudios.ftsgeology.worldgen.OreGenesis.MOD_METALS.contains(m)) return false;
                            laid = true;
                        }
                        return laid;
                    });
                }
            }
            if (!on()) return;
            Map<Block, BlockState> r = replacements();
            if (r.isEmpty()) return;
            for (GenerationStep.Decoration step : new GenerationStep.Decoration[]{
                    GenerationStep.Decoration.UNDERGROUND_ORES, GenerationStep.Decoration.UNDERGROUND_DECORATION}) {
                List<Holder<PlacedFeature>> features = builder.getGenerationSettings().getFeatures(step);
                Set<Block> placedKept = new HashSet<>();
                for (Holder<PlacedFeature> f : features) {
                    for (Block b : ores(f)) if (!r.containsKey(b)) placedKept.add(b);
                }
                features.removeIf(f -> {
                    List<Block> os = ores(f);
                    if (os.isEmpty()) return false;
                    for (Block b : os) {
                        BlockState kept = r.get(b);
                        if (kept == null || !placedKept.contains(kept.getBlock())) return false;
                    }
                    return true;
                });
            }
        }

        @Override
        public Codec<? extends BiomeModifier> codec() {
            return ModBiomeModifiers.UNIFY_ORES.get();
        }
    }
}
