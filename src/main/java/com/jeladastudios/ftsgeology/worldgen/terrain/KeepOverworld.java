package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.Map;

/**
 * Keeps the mod's overworld when a data pack brings an overworld of its own. A pack's {@code dimension/overworld}
 * replaces whatever world type was chosen, so with such a terrain pack installed "FT's Geology" was lost whether it
 * was the default or picked by hand: the world came out as the pack's. Where the chosen world type's overworld is
 * the mod's and a pack's overworld is not, the mod's terrain stays and the pack's biome list becomes the one the
 * mod's biome source lays its own biomes over, so the pack's biomes grow on the mod's ground.
 */
public final class KeepOverworld {

    private KeepOverworld() {}

    /** The pack dimensions to build the world from, with the overworld the mod's when the world type chose it. */
    public static Registry<LevelStem> keep(Registry<LevelStem> chosen, Registry<LevelStem> packs) {
        LevelStem ours = chosen.get(LevelStem.OVERWORLD);
        LevelStem theirs = packs.get(LevelStem.OVERWORLD);
        if (ours == null || theirs == null) return packs;
        if (!(ours.generator() instanceof GeologyChunkGenerator mine)) return packs;
        if (theirs.generator() instanceof GeologyChunkGenerator) return packs;
        BiomeSource parent = theirs.generator().getBiomeSource();
        if (parent instanceof GeologyBiomeSource g) parent = g.parent();
        BiomeSource biomes = mine.getBiomeSource() instanceof GeologyBiomeSource g ? g.withParent(parent) : parent;
        LevelStem kept = new LevelStem(ours.type(), new GeologyChunkGenerator(biomes, mine.generatorSettings()));
        MappedRegistry<LevelStem> out = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.experimental());
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> e : packs.entrySet()) {
            boolean over = e.getKey().equals(LevelStem.OVERWORLD);
            out.register(e.getKey(), over ? kept : e.getValue(), packs.lifecycle(e.getValue()));
        }
        GeysersMod.LOGGER.info("A data pack brings its own overworld ({}); keeping FT's Geology's terrain with that "
                + "overworld's biomes ({})", theirs.generator().getClass().getSimpleName(), parent.getClass().getSimpleName());
        return out.freeze();
    }
}
