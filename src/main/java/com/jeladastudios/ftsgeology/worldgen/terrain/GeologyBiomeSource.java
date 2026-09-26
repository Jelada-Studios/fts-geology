package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles.Role;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
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
    /** The biome a river runs in, under the map key {@code river}. Null in a preset that does not name one. */
    private final Holder<Biome> river;
    /** Everest, K2 and the Matterhorn, in the order {@link DemLibrary#LANDMARKS} counts them. */
    private final Holder<Biome>[] landmarks;

    @SuppressWarnings("unchecked")
    public GeologyBiomeSource(BiomeSource parent, Map<String, Holder<Biome>> byRole) {
        this.parent = parent;
        this.byRole = byRole;
        this.roles = new Holder[Role.values().length];
        for (Role r : Role.values()) roles[r.ordinal()] = byRole.get(r.key);
        this.river = byRole.get("river");
        @SuppressWarnings("unchecked")
        Holder<Biome>[] marks = new Holder[DemLibrary.LANDMARKS.length];
        for (int i = 0; i < marks.length; i++) marks[i] = byRole.get(DemLibrary.LANDMARKS[i]);
        this.landmarks = marks;
    }

    /** The same roles over another parent's biomes: a terrain mod's overworld list, when it brings one. */
    public GeologyBiomeSource withParent(BiomeSource other) {
        return new GeologyBiomeSource(other, byRole);
    }

    public BiomeSource parent() {
        return parent;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(parent.possibleBiomes().stream(), byRole.values().stream()).distinct();
    }

    /**
     * /locate, without freezing the server. The search asks for a biome every 32 blocks out to 6400 and at every 64
     * of height -- millions of columns -- and a column's biome here asks the river network whether a channel runs
     * through it, which, where no chunk has been made yet, works out a whole square of rivers. So the search reads only
     * the rivers already worked out; and when it wants one of the mod's own biomes, which are all of the surface, it
     * looks at one height above the ground instead of every height down to the bottom of the world.
     */
    @Override
    public com.mojang.datafixers.util.Pair<net.minecraft.core.BlockPos, Holder<Biome>> findClosestBiome3d(
            net.minecraft.core.BlockPos origin, int radius, int horizontalStep, int verticalStep,
            java.util.function.Predicate<Holder<Biome>> wanted, Climate.Sampler sampler,
            net.minecraft.world.level.LevelReader level) {
        boolean surface = parent.possibleBiomes().stream().noneMatch(wanted);
        net.minecraft.core.BlockPos from = surface
                ? new net.minecraft.core.BlockPos(origin.getX(), level.getMaxBuildHeight() - 16, origin.getZ()) : origin;
        int step = surface ? level.getHeight() : verticalStep;
        var found = com.jeladastudios.ftsgeology.hydrology.RiverNetwork.builtOnly(
                () -> super.findClosestBiome3d(from, radius, horizontalStep, step, wanted, sampler, level));
        // Found up in the sky, so it is reported on the ground: the coordinates a player clicks to go there.
        if (found != null && surface && level instanceof net.minecraft.server.level.ServerLevel server) {
            net.minecraft.core.BlockPos at = found.getFirst();
            int y = server.getChunkSource().getGenerator().getBaseHeight(at.getX(), at.getZ(),
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, server,
                    server.getChunkSource().randomState());
            return com.mojang.datafixers.util.Pair.of(new net.minecraft.core.BlockPos(at.getX(), y, at.getZ()),
                    found.getSecond());
        }
        return found;
    }

    /** How much of a channel has to reach a column before the river biome follows it there. */
    private static final double ON_CHANNEL = 0.5;
    /** How much of a named mountain's height a column has to carry before it is called by that mountain's name. */
    private static final double ON_LANDMARK = 0.18;
    /** The sea's surface: a crop's ground over it is dry land whatever the climate says. */
    private static final double SEA_LEVEL = 63.0;
    /** How far out, in quarts, a stranded river biome looks for the land it should have been. */
    private static final int[][] ASHORE = {{12, 0}, {-12, 0}, {0, 12}, {0, -12}, {24, 0}, {0, 24}};

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        Holder<Biome> base = parent.getNoiseBiome(qx, qy, qz, sampler);
        int bx = QuartPos.toBlock(qx), bz = QuartPos.toBlock(qz);
        boolean sea = TfcCompat.ocean(base);
        // The rivers are the mod's own now. Vanilla puts its river biome on the zero line of a noise field, and a
        // noise field's zero line is a closed curve -- which is how a river came to run in a ring round an island
        // and how two of them came to run side by side. So the biome follows the channel that was actually traced
        // down the ground: over one, it is a river wherever it is; away from one, whatever the land beside it is.
        // A named mountain takes its own name wherever its ground is most of what a column stands on. The share is
        // the crop's own height here against the tallest it reaches, so the name belongs to the mountain and not to
        // the whole square the crop covers -- the valleys round it stay the country they were.
        // A crop can reach out over the sea, and where it lifts the ground out of the water the climate still calls it
        // ocean: a mountainside three hundred blocks up came out as deep lukewarm ocean. There the mountain's name
        // goes on whatever of its ground stands dry.
        if (!underground(base) && landmarks.length > 0) {
            LandmarkSites.Site site = LandmarkSites.near(TerrainContext.seed(), TerrainContext.params(), bx, bz);
            if (site != null && landmarks[site.which()] != null) {
                double share = TerrainFields.landmarkShare(TerrainContext.seed(), TerrainContext.params(), bx, bz);
                if (sea ? share > 0.0 && RawGround.ready() && RawGround.heightAt(bx, bz) > SEA_LEVEL
                        : share > ON_LANDMARK) {
                    return landmarks[site.which()];
                }
            }
        }
        if (!sea && !underground(base)) {
            boolean onChannel = river != null
                    && com.jeladastudios.ftsgeology.hydrology.RiverNetwork.onRiver(bx, bz, ON_CHANNEL);
            if (onChannel && !TfcCompat.beach(base)) return river;
            if (TfcCompat.river(base)) return ashore(qx, qy, qz, sampler, base);
        }
        Role role = GeologyRoles.roleAt(bx, bz);
        Holder<Biome> ours = roles[role.ordinal()];
        if (ours == null) return base;
        // Every one of ours is a biome of the surface. The same column underground is a cave biome, and putting
        // a mountainside over it would take the moss out of a lush cave and the city out of the deep dark.
        if (underground(base)) return base;
        // The plates decide the rock, never the weather. The bare ones can stand in any climate, but a warm
        // green valley laid over the tundra would only look wrong, so up there the snow keeps its own biome.
        if (WARM.contains(role) && frozen(base)) return base;
        if (role == Role.OCEANIC_RIDGE) return sea ? ours : base;
        // The coast is still the parent's to place; only the rivers were taken over, above.
        if (sea || TfcCompat.beach(base)) return base;
        return ours;
    }

    /**
     * What a column would have been if vanilla had not called it a river. The river line is a contour, so a step
     * to either side of it leaves it; the first neighbour that is not water is the land this column belongs to.
     * Falls back to the river itself, which is no worse than before.
     */
    private Holder<Biome> ashore(int qx, int qy, int qz, Climate.Sampler sampler, Holder<Biome> base) {
        for (int[] o : ASHORE) {
            Holder<Biome> near = parent.getNoiseBiome(qx + o[0], qy, qz + o[1], sampler);
            if (!TfcCompat.river(near) && !TfcCompat.ocean(near) && !TfcCompat.beach(near)
                    && !underground(near)) {
                return near;
            }
        }
        return base;
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
