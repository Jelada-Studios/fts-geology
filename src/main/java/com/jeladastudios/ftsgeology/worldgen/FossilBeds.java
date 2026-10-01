package com.jeladastudios.ftsgeology.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Jurassic Reborn's fossils where fossils are: in the sedimentary rock of the basins, never in granite, gneiss or basalt.
 *
 * <p>That mod puts every one of its hundred-odd species in every chunk of the overworld, in any stone, its age only a
 * band of height; plant fossils, amber and gypsum the same way. On the mod's own world type, with this setting on, its
 * fossil, amber and gypsum features are taken out (by name, from its data; its code is not touched) and laid by the
 * geology instead (see {@code OreGenesis}): bone beds -- a few animals of one age together -- in the sedimentary cover
 * of a basin, rich in a few stretches of country (a Morrison, a Holzmaden) and rare elsewhere; sea creatures in shale and
 * limestone, land animals in the sandstone and red beds of rivers and deltas; plant fossils in the shale roof of the coal
 * seams; amber with the young coal, where the resin of the forests that made it was buried; gypsum in the evaporite
 * beds of dry basins and in the seal over some oil fields. Which age a species belongs to is read from that mod's own
 * data, from the band of height it gives the species: its bands run in the order of the ages, oldest lowest. In a basin
 * the ages lie in that order through the sedimentary cover, the oldest at its foot on the crystalline basement and the
 * youngest under the soil, so the older the deeper still holds, but in the rock rather than at fixed heights. Its
 * petrified trees and ice shards stay as they are. Elsewhere, and without it, nothing changes.</p>
 */
public final class FossilBeds {

    private FossilBeds() {}

    static final String JR = "jurassicreborn";

    /** The ages, oldest first, each taking the species whose band has its middle under the next one's floor. */
    public enum Age {
        ORDOVICIAN(-38), DEVONIAN(-10), PERMIAN(4), TRIASSIC(16), JURASSIC(30), CRETACEOUS(48), CENOZOIC(Integer.MAX_VALUE);

        final int below;

        Age(int below) {
            this.below = below;
        }

        static Age of(double middle) {
            for (Age a : values()) if (middle < a.below) return a;
            return CENOZOIC;
        }
    }

    /** One age's band of height, from its species', and its sea creatures and land animals. */
    static final class Stage {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        final List<Block> sea = new ArrayList<>(), land = new ArrayList<>();

        boolean has() {
            return lo <= hi && (!sea.isEmpty() || !land.isEmpty());
        }
    }

    /** The species that lived in the sea; the rest lived on land or in its rivers and lakes, and lie there. */
    private static final String[] MARINE = {"calymene", "cameroceras", "endoceras", "orthoceras", "dunkleosteus",
            "coelacanth", "asteroceras", "perisphinctes", "titanites", "parapuzosia", "pleuroceras", "mosasaurus",
            "tylosaurus", "livyatan", "megalodon", "kairuku", "plesiosaur", "ichthyosaur", "elasmosaurus"};

    /** A species whose band is wider than this ranges through many ages, and goes into each it reaches. */
    private static final int LONG_RANGING = 40;

    private static volatile Map<Age, Stage> stages;
    private static volatile Boolean geological;

    /** The ages and their species, read once a server from Jurassic Reborn's placement data; empty without it. */
    static Map<Age, Stage> stages() {
        Map<Age, Stage> s = stages;
        if (s != null) return s;
        synchronized (FossilBeds.class) {
            if (stages != null) return stages;
            Map<Age, Stage> out = new EnumMap<>(Age.class);
            for (Age a : Age.values()) out.put(a, new Stage());
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null && present()) {
                var found = server.getResourceManager().listResources("worldgen/placed_feature",
                        rl -> rl.getNamespace().equals(JR) && rl.getPath().endsWith("_fossil_placed.json"));
                List<Object[]> wide = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (var e : found.entrySet()) {
                    String path = e.getKey().getPath();
                    names.add(path.substring(path.lastIndexOf('/') + 1, path.length() - "_fossil_placed.json".length()));
                }
                names.sort(null);
                for (String name : names) {
                    if (name.equals("flora")) continue;
                    Block block = block(name + "_fossil");
                    if (block == null) continue;
                    int[] band = band(found.get(new ResourceLocation(JR, "worldgen/placed_feature/" + name + "_fossil_placed.json")));
                    if (band == null) continue;
                    boolean marine = false;
                    for (String m : MARINE) if (name.startsWith(m)) marine = true;
                    if (band[1] - band[0] > LONG_RANGING) {
                        wide.add(new Object[]{block, band, marine});
                        continue;
                    }
                    Stage st = out.get(Age.of((band[0] + band[1]) / 2.0));
                    st.lo = Math.min(st.lo, band[0]);
                    st.hi = Math.max(st.hi, band[1]);
                    (marine ? st.sea : st.land).add(block);
                }
                for (Object[] w : wide) {
                    int[] band = (int[]) w[1];
                    for (Stage st : out.values()) {
                        if (st.lo > st.hi || st.hi <= band[0] || st.lo >= band[1]) continue;
                        ((boolean) w[2] ? st.sea : st.land).add((Block) w[0]);
                    }
                }
            }
            int n = 0;
            StringBuilder line = new StringBuilder();
            for (var e : out.entrySet()) {
                Stage st = e.getValue();
                if (!st.has()) continue;
                n += st.sea.size() + st.land.size();
                line.append(' ').append(e.getKey().name().toLowerCase(Locale.ROOT)).append(' ').append(st.lo).append("..")
                        .append(st.hi).append(" (").append(st.sea.size()).append(" sea, ").append(st.land.size()).append(" land)");
            }
            if (n > 0) com.jeladastudios.ftsgeology.util.Diagnostics.info("fossil beds: Jurassic Reborn's ages{}", line);
            stages = out;
            return out;
        }
    }

    /** A placed feature file's height range, {min, max}, or null. */
    private static int[] band(net.minecraft.server.packs.resources.Resource res) {
        if (res == null) return null;
        try (Reader r = res.openAsReader()) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray placement = o.getAsJsonArray("placement");
            if (placement == null) return null;
            for (JsonElement el : placement) {
                JsonObject p = el.getAsJsonObject();
                if (!"minecraft:height_range".equals(p.get("type").getAsString())) continue;
                JsonObject h = p.getAsJsonObject("height");
                JsonObject lo = h.getAsJsonObject("min_inclusive"), hi = h.getAsJsonObject("max_inclusive");
                if (lo == null || hi == null || !lo.has("absolute") || !hi.has("absolute")) return null;
                return new int[]{lo.get("absolute").getAsInt(), hi.get("absolute").getAsInt()};
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    static boolean present() {
        return net.minecraftforge.fml.ModList.get().isLoaded(JR);
    }

    /** Whether the fossils are laid by the geology: the setting on, Jurassic Reborn there, the mod's own world type. */
    public static boolean geological() {
        Boolean g = geological;
        if (g != null) return g;
        if (!present() || !GeyserConfig.FOSSIL_BEDS.get() || com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.active()) return false;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        boolean own = stem != null && com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(stem.generator());
        geological = own;
        return own;
    }

    /** One of Jurassic Reborn's blocks by name, or null. */
    static Block block(String name) {
        Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(JR, name));
        return b == Blocks.AIR ? null : b;
    }

    public static void clear() {
        stages = null;
        geological = null;
    }

    /** What the geology has laid since the server started. */
    static final java.util.concurrent.atomic.LongAdder BEDS = new java.util.concurrent.atomic.LongAdder(),
            BONES = new java.util.concurrent.atomic.LongAdder(), PLANTS = new java.util.concurrent.atomic.LongAdder(),
            AMBER = new java.util.concurrent.atomic.LongAdder(), GYPSUM = new java.util.concurrent.atomic.LongAdder();

    /** A line for the log, or null when nothing was laid. */
    public static String summary() {
        long beds = BEDS.sumThenReset(), bones = BONES.sumThenReset(), plants = PLANTS.sumThenReset(),
                amber = AMBER.sumThenReset(), gypsum = GYPSUM.sumThenReset();
        if (beds + plants + amber + gypsum == 0) return null;
        return String.format(Locale.ROOT, "fossil beds: %d beds with %d bones, %d plant fossils in coal roofs, %d amber, %d gypsum",
                beds, bones, plants, amber, gypsum);
    }

    /**
     * Takes Jurassic Reborn's own fossil, amber and gypsum features out of the overworld's biomes when the geology lays
     * them (its petrified trees and ice shards stay).
     */
    public record Remove() implements BiomeModifier {
        public static final Codec<Remove> CODEC = Codec.unit(new Remove());

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase != Phase.REMOVE || !biome.is(net.minecraft.tags.BiomeTags.IS_OVERWORLD) || !geological()) return;
            for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
                builder.getGenerationSettings().getFeatures(step).removeIf(f -> f.unwrapKey().map(k -> {
                    ResourceLocation id = k.location();
                    if (!id.getNamespace().equals(JR)) return false;
                    String p = id.getPath();
                    return p.contains("fossil") || p.contains("amber") || p.contains("gypsum");
                }).orElse(false));
            }
        }

        @Override
        public Codec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }
}
