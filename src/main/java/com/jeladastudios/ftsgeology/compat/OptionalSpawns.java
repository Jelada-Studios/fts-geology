package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.registry.ModBiomeModifiers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Animals of another mod, added to the mod's own biomes only when that mod is there. The animals are named by id and
 * looked up as the biomes are built, so with the other mod absent the entries simply find nothing: the data pack
 * never names a type the game does not have, which is what brought the server down when a biome list tried it.
 *
 * <p>The mountains get the animals of mountains, the rift the animals of the African rift, the geothermal basin those
 * of Yellowstone: the plates decide the country, and the country decides what lives in it.</p>
 */
public record OptionalSpawns(HolderSet<Biome> biomes, List<Entry> spawns) implements BiomeModifier {

    /** One animal: its id, how often it is picked against the rest, and how many come together. */
    public record Entry(ResourceLocation entity, int weight, int min, int max) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("entity").forGetter(Entry::entity),
                Codec.INT.fieldOf("weight").forGetter(Entry::weight),
                Codec.INT.fieldOf("min").forGetter(Entry::min),
                Codec.INT.fieldOf("max").forGetter(Entry::max)).apply(i, Entry::new));
    }

    public static final Codec<OptionalSpawns> CODEC = RecordCodecBuilder.create(i -> i.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(OptionalSpawns::biomes),
            Entry.CODEC.listOf().fieldOf("spawns").forGetter(OptionalSpawns::spawns)).apply(i, OptionalSpawns::new));

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.ADD || !biomes.contains(biome)) return;
        for (Entry e : spawns) {
            if (!ForgeRegistries.ENTITY_TYPES.containsKey(e.entity())) continue;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(e.entity());
            if (type == null) continue;
            builder.getMobSpawnSettings().addSpawn(type.getCategory(),
                    new MobSpawnSettings.SpawnerData(type, e.weight(), e.min(), e.max()));
        }
    }

    @Override
    public Codec<? extends BiomeModifier> codec() {
        return ModBiomeModifiers.OPTIONAL_SPAWNS.get();
    }
}
