package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyBiomeSource;
import com.jeladastudios.ftsgeology.worldgen.terrain.WorldgenRevision;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The biomes of mods that add theirs through TerraBlender (Biomes O' Plenty and others) in the mod's world types.
 *
 * <p>TerraBlender sets up the overworld's biome list only where the overworld's biome source is vanilla's multi-noise
 * one, and the mod's is its own, wrapped round one: that inner list was never set up, so the regions of those mods
 * never came up and their biomes were nowhere. Here the inner list is set up the way TerraBlender does it, through its
 * own call, and its biomes are added to the ones the world can have, so their features and structures are placed.</p>
 *
 * <p>Their ground: TerraBlender would hand the whole surface to its own copy of vanilla's rule for vanilla's biomes,
 * which has none of the mod's rocks, so the world's rule is left as it is and the rule each of those mods registered is
 * applied to that mod's biomes alone ({@link #surface}).</p>
 *
 * <p>TerraBlender is LGPL; it is called by reflection and needs nothing of it at build time. Only worlds made with
 * {@link WorldgenRevision#REGION_BIOMES} or later: an older world's new chunks would otherwise meet its old ones at a
 * seam of different biomes.</p>
 */
public final class TerraBlenderRegions {

    private TerraBlenderRegions() {}

    /** The rules of each mod that registered surface rules with TerraBlender, by its namespace, for this world. */
    private static volatile Map<String, SurfaceRules.RuleSource> rules = Map.of();

    /** Sets the mod's overworld up with TerraBlender's regions, before the levels are made. */
    public static void open(MinecraftServer server, long seed) {
        rules = Map.of();
        surface = null;
        if (!ModList.get().isLoaded("terrablender") || !WorldgenRevision.has(WorldgenRevision.REGION_BIOMES)) return;
        RegistryAccess access = server.registryAccess();
        LevelStem stem = access.registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        if (stem == null || !(stem.generator().getBiomeSource() instanceof GeologyBiomeSource ours)) return;
        if (!(ours.parent() instanceof MultiNoiseBiomeSource parent)) return;
        try {
            // TerraBlender's own setup, handed a stand-in generator over the inner biome source. The stand-in's
            // settings are a throwaway: TerraBlender marks the settings it is given, and marked, the world's own would
            // have its surface rule replaced.
            ChunkGenerator standIn = new NoiseBasedChunkGenerator(parent, Holder.direct(NoiseGeneratorSettings.dummy()));
            Class<?> levelUtils = Class.forName("terrablender.util.LevelUtils");
            Method init = levelUtils.getMethod("initializeBiomes", RegistryAccess.class, Holder.class, ResourceKey.class,
                    ChunkGenerator.class, long.class);
            // TerraBlender knows an overworld by its dimension type, and the tall world has one of its own: it is handed
            // vanilla's, which only tells it which regions to use.
            Holder<net.minecraft.world.level.dimension.DimensionType> overworld = access.registryOrThrow(Registries.DIMENSION_TYPE)
                    .getHolderOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD);
            init.invoke(null, access, overworld, LevelStem.OVERWORLD, standIn, seed);
            // The biomes the regions brought, added to what the mod's own source can give.
            List<Holder<Biome>> added = parent.possibleBiomes().stream()
                    .filter(b -> !ours.possibleBiomes().contains(b)).toList();
            if (!added.isEmpty()) {
                Class.forName("terrablender.worldgen.IExtendedBiomeSource")
                        .getMethod("appendDeferredBiomesList", List.class).invoke(ours, added);
            }
            rules = namespaced();
            surface = combined(access, rules);
            GeysersMod.LOGGER.info("TerraBlender's regions set up in FT's Geology's overworld: {} biomes added, surface rules of {}",
                    added.size(), rules.keySet());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            GeysersMod.LOGGER.warn("Could not set TerraBlender's regions up in FT's Geology's overworld; their biomes will not appear: {}",
                    e.toString());
        }
    }

    public static void close() {
        rules = Map.of();
        surface = null;
    }

    /** The overworld surface rules mods registered with TerraBlender, by namespace; vanilla's own left out. */
    private static Map<String, SurfaceRules.RuleSource> namespaced() throws ReflectiveOperationException {
        Class<?> manager = Class.forName("terrablender.api.SurfaceRuleManager");
        Class<?> category = Class.forName("terrablender.api.SurfaceRuleManager$RuleCategory");
        Object overworld = category.getField("OVERWORLD").get(null);
        Object source = manager.getMethod("getNamespacedRules", category, SurfaceRules.RuleSource.class)
                .invoke(null, overworld, NONE);
        @SuppressWarnings("unchecked")
        Map<String, SurfaceRules.RuleSource> all = (Map<String, SurfaceRules.RuleSource>) source.getClass().getMethod("sources").invoke(source);
        Map<String, SurfaceRules.RuleSource> out = new HashMap<>(all);
        out.remove("minecraft");
        return Map.copyOf(out);
    }

    /** A rule that places nothing, for TerraBlender's fallback, which is not used. */
    private static final SurfaceRules.RuleSource NONE = SurfaceRules.state(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

    /**
     * Each of those mods' rules behind a test for its own biomes, one after another, or null where there are none.
     * Built once a world opens, from the biomes the registry has in each namespace.
     */
    private static SurfaceRules.RuleSource combined(RegistryAccess access, Map<String, SurfaceRules.RuleSource> by) {
        if (by.isEmpty()) return null;
        List<SurfaceRules.RuleSource> parts = new java.util.ArrayList<>();
        var biomes = access.registryOrThrow(Registries.BIOME);
        for (Map.Entry<String, SurfaceRules.RuleSource> e : by.entrySet()) {
            List<ResourceKey<Biome>> keys = biomes.registryKeySet().stream()
                    .filter(k -> k.location().getNamespace().equals(e.getKey())).toList();
            if (keys.isEmpty()) continue;
            @SuppressWarnings("unchecked")
            ResourceKey<Biome>[] array = keys.toArray(new ResourceKey[0]);
            parts.add(SurfaceRules.ifTrue(SurfaceRules.isBiome(array), e.getValue()));
        }
        return parts.isEmpty() ? null : SurfaceRules.sequence(parts.toArray(new SurfaceRules.RuleSource[0]));
    }

    private static volatile SurfaceRules.RuleSource surface;

    /**
     * The ground of a TerraBlender mod's biome, from that mod's rule; null for any other biome, or where the rule places
     * nothing, and null altogether where no mod brought one. Applied for each context the world's surface rule is.
     */
    public static SurfaceRules.SurfaceRule surface(SurfaceRules.Context context) {
        SurfaceRules.RuleSource rule = surface;
        return rule == null ? null : rule.apply(context);
    }
}
