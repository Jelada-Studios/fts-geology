package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles.Role;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
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
        Role role = GeologyRoles.roleAt(QuartPos.toBlock(qx), QuartPos.toBlock(qz));
        Holder<Biome> ours = roles[role.ordinal()];
        if (ours == null) return base;
        // Every one of ours is a biome of the surface. The same column underground is a cave biome, and putting
        // a mountainside over it would take the moss out of a lush cave and the city out of the deep dark.
        if (underground(base)) return base;
        // The plates decide the rock, never the weather. The bare ones can stand in any climate, but a warm
        // green valley laid over the tundra would only look wrong, so up there the snow keeps its own biome.
        if (WARM.contains(role) && frozen(base)) return base;
        boolean sea = TfcCompat.ocean(base);
        if (role == Role.OCEANIC_RIDGE) return sea ? ours : base;
        // The water is the parent's to place: a river, a beach or the sea keeps whatever it was.
        if (sea || TfcCompat.river(base) || TfcCompat.beach(base)) return base;
        return ours;
    }

    /** The ones of ours that are green and warm, and so out of place under snow. */
    private static final java.util.EnumSet<Role> WARM =
            java.util.EnumSet.of(Role.GEOTHERMAL_BASIN, Role.RIFT_VALLEY, Role.ALLUVIAL_PLAIN);

    /** Whether a biome belongs under the ground. See {@link #kindOf}. */
    private boolean underground(Holder<Biome> biome) {
        return (kindOf(biome) & UNDERGROUND) != 0;
    }

    /** Whether a biome is one of the snowy ones. */
    private boolean frozen(Holder<Biome> biome) {
        return (kindOf(biome) & FROZEN) != 0;
    }

    private static final int UNDERGROUND = 1, FROZEN = 2;

    /** What each biome the parent hands back is, worked out once per biome rather than for every quarter-block. */
    private final java.util.Map<Holder<Biome>, Integer> kinds = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Matched by name, as {@code ThermalBiomes} does, because 1.20 has no tag for caves and a terrain mod's own caves
     * and snow should count too.
     */
    private int kindOf(Holder<Biome> biome) {
        Integer known = kinds.get(biome);
        if (known != null) return known;
        int kind = biome.unwrapKey().map(k -> {
            String p = k.location().getPath();
            int bits = 0;
            if (p.contains("cave") || p.contains("deep_dark")) bits |= UNDERGROUND;
            if (p.startsWith("snowy") || p.startsWith("frozen") || p.startsWith("ice")
                    || p.equals("grove") || p.equals("jagged_peaks")) bits |= FROZEN;
            return bits;
        }).orElse(0);
        kinds.put(biome, kind);
        return kind;
    }
}
