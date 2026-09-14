package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles.Role;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Map;
import java.util.stream.Stream;

/**
 * The biome source of the mod's world type: a thin layer over whatever source the world already had. It asks the
 * parent first and answers for itself only where {@link GeologyRoles} has something to say. Everywhere else the
 * parent's biome stands, so a vanilla world stays a vanilla world and a terrain mod's biomes survive being
 * wrapped.
 *
 * <p>One rule keeps it honest: a land biome never replaces a river, a beach or the sea, and a sea biome never
 * replaces land. The water network and the coastline belong to the parent.</p>
 */
public class GeologyBiomeSource extends BiomeSource {

    public static final Codec<GeologyBiomeSource> CODEC = RecordCodecBuilder.create(i -> i.group(
            BiomeSource.CODEC.fieldOf("parent").forGetter(s -> s.parent),
            Codec.unboundedMap(Codec.STRING, Biome.CODEC).fieldOf("biomes").forGetter(s -> s.byRole)
    ).apply(i, GeologyBiomeSource::new));

    private final BiomeSource parent;
    private final Map<String, Holder<Biome>> byRole;
    private final Holder<Biome>[] roles;

    @SuppressWarnings("unchecked")
    public GeologyBiomeSource(BiomeSource parent, Map<String, Holder<Biome>> byRole) {
        this.parent = parent;
        this.byRole = byRole;
        this.roles = new Holder[Role.values().length];
        for (Role r : Role.values()) roles[r.ordinal()] = byRole.get(r.key);
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(parent.possibleBiomes().stream(), byRole.values().stream()).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        Holder<Biome> base = parent.getNoiseBiome(qx, qy, qz, sampler);
        Holder<Biome> ours = roles[GeologyRoles.roleAt(QuartPos.toBlock(qx), QuartPos.toBlock(qz)).ordinal()];
        if (ours == null) return base;
        boolean sea = base.is(BiomeTags.IS_OCEAN) || base.is(BiomeTags.IS_DEEP_OCEAN);
        if (ours == roles[Role.OCEANIC_RIDGE.ordinal()]) return sea ? ours : base;
        // The water is the parent's to place: a river, a beach or the sea keeps whatever it was.
        if (sea || base.is(BiomeTags.IS_RIVER) || base.is(BiomeTags.IS_BEACH)) return base;
        return ours;
    }
}
