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
    /** The altitude belts, foot to top ({@link AltitudeBelts}); null where the world's preset names none, as an old one. */
    private final Holder<Biome>[] belts;

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
        @SuppressWarnings("unchecked")
        Holder<Biome>[] b = new Holder[AltitudeBelts.Belt.values().length];
        boolean all = true;
        for (AltitudeBelts.Belt belt : AltitudeBelts.Belt.values()) {
            b[belt.ordinal()] = byRole.get(belt.key);
            if (b[belt.ordinal()] == null) all = false;
        }
        this.belts = all ? b : null;
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
        // A mountain's belts go by height: up in the sky every column is the snowfield, and a forest belt was never
        // found. Each column is asked at its own ground instead, where the offset puts it.
        if (surface && RawGround.ready()) {
            return com.jeladastudios.ftsgeology.hydrology.RiverNetwork.builtOnly(
                    () -> onTheGround(origin, radius, horizontalStep, wanted, sampler, level));
        }
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

    /**
     * The search vanilla makes, out from the origin in a spiral, each column asked once, a little over its ground (or
     * the sea's surface over the sea); the place reported on the ground, the coordinates a player clicks to go there.
     */
    private com.mojang.datafixers.util.Pair<net.minecraft.core.BlockPos, Holder<Biome>> onTheGround(
            net.minecraft.core.BlockPos origin, int radius, int horizontalStep,
            java.util.function.Predicate<Holder<Biome>> wanted, Climate.Sampler sampler,
            net.minecraft.world.level.LevelReader level) {
        int rings = Math.floorDiv(radius, horizontalStep);
        for (net.minecraft.core.BlockPos.MutableBlockPos p : net.minecraft.core.BlockPos.spiralAround(
                net.minecraft.core.BlockPos.ZERO, rings, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH)) {
            int x = origin.getX() + p.getX() * horizontalStep, z = origin.getZ() + p.getZ() * horizontalStep;
            double h = RawGround.heightAt(x, z);
            int ground = Double.isNaN(h) ? level.getMaxBuildHeight() - 16
                    : Math.min(level.getMaxBuildHeight() - 1, (int) Math.max(h, SEA_LEVEL));
            Holder<Biome> b = getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(ground + 2), QuartPos.fromBlock(z), sampler);
            if (wanted.test(b)) {
                int y = ground;
                if (level instanceof net.minecraft.server.level.ServerLevel server) {
                    y = server.getChunkSource().getGenerator().getBaseHeight(x, z,
                            net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, server,
                            server.getChunkSource().randomState());
                }
                return com.mojang.datafixers.util.Pair.of(new net.minecraft.core.BlockPos(x, y, z), b);
            }
        }
        return null;
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
                    // Its forests, meadows and scree are the belts' as any range's; the mountain's name is on its snow.
                    // Its own biome is a cold one, and on the scree under the snow line it laid snow all the same.
                    if (beltsOn()) {
                        AltitudeBelts.Belt belt = beltAt(qx, qy, qz, sampler);
                        if (belt.ordinal() <= AltitudeBelts.Belt.SCREE.ordinal()) return belts[belt.ordinal()];
                    }
                    return landmarks[site.which()];
                }
            }
        }
        // Beside a mountain belt the ground comes up out of the sea while the climate is still at sea: a coastal plain
        // came out as ocean, sand and gravel under grass-green hills. Where the ground stands dry it is the land round
        // it. Only in worlds made since, or the new chunks would meet the old at a seam.
        if (sea && !underground(base) && WorldgenRevision.has(WorldgenRevision.LAND_COASTS)) {
            Holder<Biome> land = dryCoast(qx, qz, sampler);
            if (land != null) {
                base = land;
                sea = false;
            }
        }
        // Terralith's sea cliff of columnar basalt falls where the climate puts a coast, whatever the rock: a low plain
        // of grass over a rift's shale and sandstone came out bristling with basalt spires, a third of a coast. Columns
        // are a thick lava flow cooling, so the cliff keeps to the coasts whose rock is basalt; elsewhere the coast is
        // the shore round it. Only in worlds made since, for the seams.
        if (!sea && !underground(base) && basaltCliffs(base) && WorldgenRevision.has(WorldgenRevision.BASALT_COASTS)) {
            Holder<Biome> shore = otherShore(qx, qy, qz, sampler);
            if (shore != null) base = shore;
        }
        // The climate lays its beach where its own coast falls, and the ground does not keep to it: sand up a hillside
        // over the sea, or a field's width inland. A beach is the strip at the water; ground well over the sea is the
        // land beside it. Only in worlds made since, for the seams.
        if (!sea && TfcCompat.beach(base) && !underground(base) && WorldgenRevision.has(WorldgenRevision.HIGH_BEACHES)) {
            Holder<Biome> land = highBeach(qx, qz, sampler);
            if (land != null) base = land;
        }
        if (!sea && !underground(base)) {
            boolean onChannel = river != null
                    && com.jeladastudios.ftsgeology.hydrology.RiverNetwork.onRiver(bx, bz, ON_CHANNEL);
            if (onChannel && !TfcCompat.beach(base)) return river;
            if (TfcCompat.river(base)) {
                Holder<Biome> land = ashore(qx, qy, qz, sampler, base);
                if (!WorldgenRevision.has(WorldgenRevision.GREENER_GROUND)) return land;
                // Worlds made since: the climate's own river, where no channel of ours runs, is land, and the plates
                // have their say over it as over any land. Left a river, it was a dry river among a coast's beaches
                // and in the middle of a geothermal basin, where /locate sent a player to find no water.
                base = TfcCompat.river(land) ? inland(qx, qy, qz, sampler, land) : land;
            }
        }
        Role role = GeologyRoles.roleAt(bx, bz);
        Holder<Biome> ours = roles[role.ordinal()];
        // Vanilla's own mountains, where the plates have none to say: belted as the ranges are.
        if (ours == null) {
            return beltsOn() && mountain(base) && !sea ? belts[beltAt(qx, qy, qz, sampler).ordinal()] : base;
        }
        // Every one of ours is a biome of the surface. The same column underground is a cave biome, and putting
        // a mountainside over it would take the moss out of a lush cave and the city out of the deep dark.
        if (underground(base)) return base;
        // The plates decide the rock, never the weather. The bare ones can stand in any climate, but a warm
        // green valley laid over the tundra would only look wrong, so up there the snow keeps its own biome.
        if (WARM.contains(role) && frozen(base)) return base;
        if (role == Role.OCEANIC_RIDGE) return sea ? ours : base;
        // The coast is still the parent's to place; only the rivers were taken over, above.
        if (sea || TfcCompat.beach(base)) return base;
        if (role == Role.OROGENIC_HIGHLAND && beltsOn()) return belts[beltAt(qx, qy, qz, sampler).ordinal()];
        // A volcanic arc is clothed by height as a fold belt is: Kilimanjaro's rain forest, heath, alpine desert and ice,
        // Etna's, Fuji's, Rainier's. Bare ash and rock is left where the climate is too dry for anything to take: there
        // the arc stays the volcanic highland. A volcano's own cone is painted by the volcano, whatever the biome.
        if (role == Role.VOLCANIC_HIGHLAND && beltsOn() && WorldgenRevision.has(WorldgenRevision.VOLCANIC_BELTS)
                && !dry(qx, qz, sampler)) {
            return belts[beltAt(qx, qy, qz, sampler).ordinal()];
        }
        return ours;
    }

    /**
     * The biome a large volcano writes over its own ground ({@code volcano.VolcanoGround}): bare ground (its crater, young
     * lava, fresh ash, a dry highland shield's flank) the volcanic highland, the rest of the mountain belted by height as
     * an arc is. The volcano's skin, not the climate, says what is bare: it grasses a flank over in any climate. Null in a
     * world without the belts.
     */
    public Holder<Biome> volcanoGround(int qx, int qy, int qz, Climate.Sampler sampler, boolean bare) {
        Holder<Biome> highland = roles[Role.VOLCANIC_HIGHLAND.ordinal()];
        if (!beltsOn() || highland == null) return null;
        if (bare) return highland;
        return belts[beltAt(qx, qy, qz, sampler).ordinal()];
    }

    /** The climate's humidity under which a volcanic arc stays bare highland, its ash unweathered. */
    private static final double ARC_DRY = -0.35;

    /** Each column's humidity, once worked out. */
    private final com.jeladastudios.ftsgeology.util.ColumnCache<Double> humidity = new com.jeladastudios.ftsgeology.util.ColumnCache<>(14);

    private boolean dry(int qx, int qz, Climate.Sampler sampler) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(qx, qz);
        Double h = humidity.get(key);
        if (h == null) {
            // One sample for both: the belt asked for next wants the same sample's temperature, kept as beltAt keeps it.
            Climate.TargetPoint p = sampler.sample(qx, 0, qz);
            h = (double) Climate.unquantizeCoord(p.humidity());
            humidity.put(key, h);
            if (seaLevel.get(key) == null) {
                double t = Climate.unquantizeCoord(p.temperature());
                seaLevel.put(key, AltitudeBelts.summer(AltitudeBelts.seaLevel(t))
                        + AltitudeBelts.waver(QuartPos.toBlock(qx), QuartPos.toBlock(qz)));
            }
        }
        return h < ARC_DRY;
    }

    /** Whether the mountains are belted: a world made since the belts came, with them named in its preset. */
    private boolean beltsOn() {
        return belts != null && WorldgenRevision.has(WorldgenRevision.CLIMATE_BELTS);
    }

    /** The summer temperature at the sea under each column, with its wavering, once worked out. */
    private final com.jeladastudios.ftsgeology.util.ColumnCache<Double> seaLevel = new com.jeladastudios.ftsgeology.util.ColumnCache<>(14);

    /** The belt a quart of a mountain is in, from the climate round it and its height. See {@link AltitudeBelts}. */
    private AltitudeBelts.Belt beltAt(int qx, int qy, int qz, Climate.Sampler sampler) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(qx, qz);
        Double c = seaLevel.get(key);
        if (c == null) {
            double t = Climate.unquantizeCoord(sampler.sample(qx, 0, qz).temperature());
            c = AltitudeBelts.summer(AltitudeBelts.seaLevel(t)) + AltitudeBelts.waver(QuartPos.toBlock(qx), QuartPos.toBlock(qz));
            seaLevel.put(key, c);
        }
        double metres = TerrainFields.METRES_PER_BLOCK / TerrainContext.params().horizontal();
        return AltitudeBelts.belt(AltitudeBelts.at(c, QuartPos.toBlock(qy) + 2, (int) SEA_LEVEL, metres));
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

    /** How far out, in quarts, the land round a climate's river with no channel in it is looked for, nearest first. */
    private static final int[] RIVER_LAND = {4, 8, 16, 24};

    /**
     * The land a climate's river with no channel in it stands in, looked for further out than {@link #ashore}: the
     * nearest land biome round it, a shore if there is nothing else, the river itself if there is not even that.
     */
    private Holder<Biome> inland(int qx, int qy, int qz, Climate.Sampler sampler, Holder<Biome> river) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(qx, qz);
        Holder<Biome> known = inlandOf.get(key);
        if (known != null) return known;
        Holder<Biome> found = river;
        search:
        for (int r : RIVER_LAND) {
            Holder<Biome> shore = null;
            for (int[] w : WAYS) {
                Holder<Biome> near = parent.getNoiseBiome(qx + w[0] * r, qy, qz + w[1] * r, sampler);
                if (TfcCompat.river(near) || TfcCompat.ocean(near) || underground(near)) continue;
                if (!TfcCompat.beach(near)) {
                    found = near;
                    break search;
                }
                if (shore == null) shore = near;
            }
            // Only shore at this distance: that is the land here, without looking further for more.
            if (shore != null) {
                found = shore;
                break;
            }
        }
        inlandOf.put(key, found);
        return found;
    }

    /** Each column's answer from {@link #inland}: structures and the search ask the same columns over and over. */
    private final com.jeladastudios.ftsgeology.util.ColumnCache<Holder<Biome>> inlandOf = new com.jeladastudios.ftsgeology.util.ColumnCache<>(12);

    /** Below this the offset's ground is under the sea whatever the rest of the terrain does to it; not looked at. */
    private static final double COAST_LOW = 60.0;
    /** How far out, in quarts, the land a dry coast belongs to is looked for, nearest first. */
    private static final int[] INLAND = {4, 8, 16, 32, 64};
    private static final int[][] WAYS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};

    /** A column's answer, the same at every height of it; {@code land} null where the sea is the sea. */
    private record Coast(Holder<Biome> land) {}

    private final com.jeladastudios.ftsgeology.util.ColumnCache<Coast> coasts = new com.jeladastudios.ftsgeology.util.ColumnCache<>(14);

    /**
     * The land biome for a column the climate calls sea but whose ground stands out of the water, or null. Dry is the
     * built column's own answer ({@link RawGround#wet}), asked only where the offset alone could put ground near the
     * surface. The land is the nearest column round it the parent calls land, read at the ground's height.
     */
    private Holder<Biome> dryCoast(int qx, int qz, Climate.Sampler sampler) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(qx, qz);
        Coast known = coasts.get(key);
        if (known != null) return known.land();
        Holder<Biome> land = null;
        int bx = QuartPos.toBlock(qx) + 2, bz = QuartPos.toBlock(qz) + 2;
        if (RawGround.ready()) {
            double h = RawGround.heightAt(bx, bz);
            if (h > COAST_LOW && !RawGround.wet(bx, bz)) land = landBeside(qx, qz, h, sampler);
        }
        coasts.put(key, new Coast(land));
        return land;
    }

    /** How far over the sea, in blocks, the climate's beach may stand and still be a beach. */
    private static final double BEACH_RISE = 4.0;

    private final com.jeladastudios.ftsgeology.util.ColumnCache<Coast> beaches = new com.jeladastudios.ftsgeology.util.ColumnCache<>(14);

    /**
     * The land biome for a column the climate calls beach whose ground stands dry and well over the sea, or null where it
     * is a beach indeed (or no land lies near). The land is found as a dry coast's is.
     */
    private Holder<Biome> highBeach(int qx, int qz, Climate.Sampler sampler) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(qx, qz);
        Coast known = beaches.get(key);
        if (known != null) return known.land();
        Holder<Biome> land = null;
        int bx = QuartPos.toBlock(qx) + 2, bz = QuartPos.toBlock(qz) + 2;
        if (RawGround.ready()) {
            double h = RawGround.heightAt(bx, bz);
            if (h >= SEA_LEVEL + BEACH_RISE && !RawGround.wet(bx, bz)) land = landBeside(qx, qz, h, sampler);
        }
        beaches.put(key, new Coast(land));
        return land;
    }

    /**
     * The land the climate puts nearest a column standing at {@code h}, read at that height: the climate's own coast is a
     * strip of shore between its sea and its land, and ground past it on the land side of the real water is the land
     * beyond the strip. A shore only if there is no other; null if there is none either.
     */
    private Holder<Biome> landBeside(int qx, int qz, double h, Climate.Sampler sampler) {
        int qy = QuartPos.fromBlock((int) Math.max(h, SEA_LEVEL) + 2);
        Holder<Biome> shore = null;
        for (int r : INLAND) {
            for (int[] w : WAYS) {
                Holder<Biome> near = parent.getNoiseBiome(qx + w[0] * r, qy, qz + w[1] * r, sampler);
                if (TfcCompat.ocean(near) || TfcCompat.river(near) || TfcCompat.beach(near) || underground(near)) continue;
                if (!shoreline(near)) return near;
                if (shore == null) shore = near;
            }
        }
        return shore;
    }

    private final com.jeladastudios.ftsgeology.util.ColumnCache<Coast> basaltShores = new com.jeladastudios.ftsgeology.util.ColumnCache<>(14);
    /** How far out, in quarts, the shore a basalt cliff off basalt is given is looked for, nearest first. */
    private static final int[] SHORE_RINGS = {2, 4, 8, 16, 32};

    /**
     * The shore for a column the climate calls a basalt cliff where the rock is not basalt, or null where it is (the
     * cliff stays). The nearest beach or shore the climate puts round it that is not a basalt cliff; failing one, the
     * stony shore; failing that, the land beside it.
     */
    private Holder<Biome> otherShore(int qx, int qy, int qz, Climate.Sampler sampler) {
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(qx, qz);
        Coast known = basaltShores.get(key);
        if (known != null) return known.land();
        Holder<Biome> shore = null;
        int bx = QuartPos.toBlock(qx) + 2, bz = QuartPos.toBlock(qz) + 2;
        if (!basaltCoast(bx, bz)) {
            search:
            for (int r : SHORE_RINGS) {
                for (int[] w : WAYS) {
                    Holder<Biome> near = parent.getNoiseBiome(qx + w[0] * r, qy, qz + w[1] * r, sampler);
                    if (basaltCliffs(near) || underground(near) || TfcCompat.ocean(near) || TfcCompat.river(near)) continue;
                    if (TfcCompat.beach(near) || shoreline(near)) {
                        shore = near;
                        break search;
                    }
                }
            }
            if (shore == null) shore = stonyShore();
            if (shore == null && RawGround.ready()) shore = landBeside(qx, qz, RawGround.heightAt(bx, bz), sampler);
        }
        basaltShores.put(key, new Coast(shore));
        return shore;
    }

    /**
     * Whether the rock at a coast is basalt: a mantle plume's flood basalts, the ocean crust of an island on an ocean
     * plate, the lavas down the middle of a rift. An arc's andesite and tuff is not, nor a rift's sandstone shoulders.
     */
    static boolean basaltCoast(int x, int z) {
        var c = com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.column(TerrainContext.seed(), TerrainContext.params(), x, z);
        return switch (c.setting()) {
            case HOTSPOT -> c.weight() >= 0.5;
            case OCEAN_FLOOR -> true;
            case RIFT -> c.weight() >= 0.5 && com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.coverDepth(c) >= RIFT_BASALT_FILL;
            default -> false;
        };
    }

    /** How deep a rift's fill has to be for its coast to be basalt: the axis, where the flows lie thickest. */
    private static final int RIFT_BASALT_FILL = 30;

    private volatile Holder<Biome> stony;
    private volatile boolean stonyLooked;

    /** Vanilla's stony shore, if the parent can give it; else any shore of its own. */
    private Holder<Biome> stonyShore() {
        if (!stonyLooked) {
            Holder<Biome> any = null, found = null;
            for (Holder<Biome> b : parent.possibleBiomes()) {
                String id = b.unwrapKey().map(k -> k.location().toString()).orElse("");
                if (id.equals("minecraft:stony_shore")) found = b;
                else if (any == null && shoreline(b) && !basaltCliffs(b)) any = b;
            }
            stony = found != null ? found : any;
            stonyLooked = true;
        }
        return stony;
    }

    /** Terralith's sea cliff of columnar basalt. */
    private boolean basaltCliffs(Holder<Biome> biome) {
        return (kindOf(biome) & BASALT_CLIFFS) != 0;
    }

    /** A coast's own biome that is not tagged a beach: vanilla's stony shore, a terrain mod's rocky or gravel shores. */
    private static boolean shoreline(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.location().getPath().contains("shore")).orElse(false);
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

    private static final int UNDERGROUND = 1, FROZEN = 2, MOUNTAIN = 4, BASALT_CLIFFS = 8;

    /** Whether a biome is one of vanilla's mountain ones, from the meadow up to the peaks. */
    private boolean mountain(Holder<Biome> biome) {
        return (kindOf(biome) & MOUNTAIN) != 0;
    }

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
            if (k.location().getNamespace().equals("minecraft") && (p.equals("meadow") || p.equals("grove")
                    || p.equals("snowy_slopes") || p.endsWith("_peaks"))) bits |= MOUNTAIN;
            if (p.startsWith("snowy") || p.startsWith("frozen") || p.startsWith("ice")
                    || p.equals("grove") || p.equals("jagged_peaks")) bits |= FROZEN;
            if (k.location().getNamespace().equals("terralith") && p.equals("basalt_cliffs")) bits |= BASALT_CLIFFS;
            return bits;
        }).orElse(0);
        kinds.put(biome, kind);
        return kind;
    }
}
