package com.jeladastudios.ftsgeology.api;

import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.hydrology.WaterTable;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.weather.LocalWeather;
import com.jeladastudios.ftsgeology.worldgen.terrain.AltitudeBelts;
import com.jeladastudios.ftsgeology.worldgen.terrain.ColumnClimate;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one door into FT's Geology for an addon. What is here keeps its name, its arguments and its meaning from one
 * version to the next, whatever the classes behind it do; what is new is added beside it. An addon that asks only this
 * class does not break when the mod's insides change.
 *
 * <p>The server side answers for the overworld of one of the mod's own world types; asked of another world, it says
 * so ({@link #isGeologyWorld}) and the rest answer as for plain ground. Everything here may be called from the server
 * thread or from a world generation thread; it reads, and changes nothing.</p>
 *
 * <p>The client side is in {@link Client}, and only for the client.</p>
 *
 * <p>Events: {@link GeologySurfacePaintedEvent} on the server when the mod has finished a chunk's ground, and
 * {@link ThunderHeardEvent} on the client when a peal of the mod's thunder is played. For a mod that runs the rivers'
 * water ({@link #registerHydraulics}): {@link RiverBlocksChangedEvent}, {@link FloodEvent}, {@link DroughtEvent}.</p>
 *
 * <p>Version 2 adds the rivers' discharge and sources, the weather's forcing on them, the hydraulics registration and
 * the client's hooks for a mod that runs the rivers' water itself; and the world's generation revision, the regional
 * weather at a point on the server, and a few questions about river water and volcanoes. Nothing of version 1 changed.</p>
 */
public final class FtsGeologyApi {

    private FtsGeologyApi() {}

    /** This API's version: raised when something is added, never when something is taken away. Ask {@code >= 2}. */
    public static final int VERSION = 2;

    // === The server ==========================================================

    /** Whether a level is built by the mod's own terrain (its normal or tall world type, or the default it sets). */
    public static boolean isGeologyWorld(ServerLevel level) {
        return GeologyWorld.isOwn(level);
    }

    /**
     * The river at a column, where there is one: how far the column is from the channel's middle, the channel's half
     * width at its flat bed, the water's height, and which way the water runs, as a unit vector across the ground
     * (zero in a lake). Blocks throughout. Empty where no channel or lake reaches the column, or outside the mod's world.
     *
     * @param within how far past a channel's bed a column still counts, blocks; 0 for the water itself
     */
    public static Optional<River> river(int x, int z, double within) {
        if (!RiverNetwork.ready()) return Optional.empty();
        RiverNetwork.At a = RiverNetwork.at(x, z);
        if (a.distance() == Double.MAX_VALUE || !a.within(within)) return Optional.empty();
        return Optional.of(new River(a.distance(), a.halfWidth(), a.water(), a.fx(), a.fz(), a.lake()));
    }

    /**
     * A river at a column: {@code distance} from its middle and {@code halfWidth} of its bed in blocks, the
     * {@code water} level, the direction {@code flowX, flowZ} it runs in, and whether it is a {@code lake}.
     */
    public record River(double distance, double halfWidth, double water, double flowX, double flowZ, boolean lake) {}

    /**
     * What the plates make of a column, by name: {@code fold_belt}, {@code volcanic_arc}, {@code rift}, ... as the
     * mod's {@code /geology terrain here} names them; {@code none} outside the mod's world.
     */
    public static String role(ServerLevel level, int x, int z) {
        if (!isGeologyWorld(level)) return "none";
        return GeologyRoles.roleAt(x, z).name().toLowerCase(Locale.ROOT);
    }

    /**
     * The belt of a mountain a point at height {@code y} would be clothed in, from the climate round it: {@code
     * montane_forest}, {@code subalpine_forest}, {@code treeline}, {@code alpine_meadow}, {@code alpine_scree} or
     * {@code snowfield} -- the names of the mod's belt biomes. Asked of any column, mountain or not.
     */
    public static String belt(ServerLevel level, int x, int y, int z) {
        ColumnClimate.At c = ColumnClimate.at(level, x, z);
        double summer = AltitudeBelts.summer(c.yearAtSea()) + AltitudeBelts.waver(x, z);
        return AltitudeBelts.belt(AltitudeBelts.at(summer, y, level.getSeaLevel(), ColumnClimate.metres())).key;
    }

    /** The height of the tree line over a column, from the climate round it. */
    public static int treeLine(ServerLevel level, int x, int z) {
        return ColumnClimate.at(level, x, z).treeLine(x, z, level.getSeaLevel());
    }

    /** The height of the snow line over a column, from the climate round it. */
    public static int snowLine(ServerLevel level, int x, int z) {
        return ColumnClimate.at(level, x, z).snowLine(x, z, level.getSeaLevel());
    }

    /**
     * How many blocks under the ground the water table stands at a column, by the mod's groundwater model; 0 where it is
     * at the surface, -1 where it is not known (the model off, or the column not generated).
     */
    public static int waterTableDepth(ServerLevel level, int x, int z) {
        int table = WaterTable.tableY(level, x, z);
        if (table == Integer.MIN_VALUE) return -1;
        int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return Math.max(0, ground - table);
    }

    /**
     * The rock that lies at a point by the mod's lithology -- what the plates put there before any soil, as it shows on
     * a cliff -- as a block state; plain stone where the model has nothing particular, or outside the mod's world.
     */
    public static BlockState surfaceRock(ServerLevel level, int x, int y, int z) {
        return com.jeladastudios.ftsgeology.worldgen.lithology.LithologyRule.rockState(level, x, y, z);
    }

    /** The soils the mod paints by the rock under the ground and the climate. */
    public enum Soil { NONE, LATERITE, BROWN_EARTH, RENDZINA, PODZOL, GRUS, LEACHED }

    /**
     * The soil the mod has laid at a column, by the rock under it and the climate: red {@code LATERITE} where it is hot
     * and wet, {@code BROWN_EARTH} over the same rock in cooler country, a thin {@code RENDZINA} over limestone and
     * marble (a flowery turf), {@code PODZOL} in cool wet forest, {@code GRUS} over granite where it is hot and dry,
     * {@code LEACHED} over granite elsewhere, {@code NONE} where it paints no soil. Reads the column's blocks: ask it of
     * generated ground.
     */
    public static Soil soil(ServerLevel level, int x, int z) {
        String kind = com.jeladastudios.ftsgeology.worldgen.SoilProfile.kindAt(level, x, z);
        return kind == null ? Soil.NONE : Soil.valueOf(kind);
    }

    /** How strong a mantle plume is under a column, 0 none to 1 its centre. */
    public static double plumeStrength(ServerLevel level, int x, int z) {
        return HotspotMap.plumeStrength(level, x, z);
    }

    // === Version 2: the rivers' water ========================================

    /** What a question about the rivers came to. */
    public enum Status {
        /** There is an answer. */
        PRESENT,
        /** There is none: no channel or lake reaches there, or the level is not the mod's. */
        ABSENT,
        /** The river network there is not worked out yet: ask again later (on the server thread it is being worked out). */
        NOT_READY
    }

    /**
     * A channel or lake at a column. Blocks throughout, discharge in cubic metres a second; never NaN.
     *
     * @param distance    from the channel's middle line (0 in a lake)
     * @param halfWidth   the channel's half width at its flat bed
     * @param water       the water's level
     * @param bed         the channel's floor under its middle line (a lake's floor where the column is in a lake)
     * @param flowX       which way the water runs, a unit vector across the ground; 0, 0 in a lake
     * @param lake        whether the column is in a lake
     * @param lakeId      the lake's identity, the same for every column of it and every time it is asked; 0 outside one
     * @param lakeLevel   the lake's water; 0 outside one
     * @param lakeOutlet  whether the column is on the way out of a lake, drawn from the lake to where its river goes on
     * @param head        whether the column is at the head of a river, where it rises
     * @param fromHead    how far down its river the column is, from the head
     * @param sunk        whether the river runs underground here, in a cave under a dry valley (see {@link #sources})
     * @param discharge   the long-run discharge carried here: 0.05 for every lattice node of its catchment (a spring's
     *                    rill about 1.6, a six-block river of the normal world about 8, one at the cap about 100), under
     *                    what the drawn channel carries; the climate's part is in the factors
     * @param catchment   the ground draining through here, blocks squared; at least this much where {@code capped}
     * @param capped      whether the catchment is past the network's count, and the discharge only "at least"
     * @param baseFactor  the season and the region's spell over the catchment now (1 on average), see {@link #forcing}
     * @param stormFactor the catchment's quick rise in a heavy rain on soaked ground now (1 with no rain)
     */
    public record Channel(double distance, double halfWidth, double water, double bed, double flowX, double flowZ,
                          boolean lake, long lakeId, double lakeLevel, boolean lakeOutlet, boolean head, double fromHead,
                          boolean sunk, double discharge, double catchment, boolean capped, double baseFactor,
                          double stormFactor) {}

    /** An answer about a channel: the channel itself only where {@code status} is PRESENT. */
    public record ChannelAnswer(Status status, Channel channel) {}

    /**
     * The channel or lake at a column, within {@code within} blocks past its bed ({@link #river} with more in it). Waits
     * as long as it takes for the network off the server thread; see {@link #channel(ServerLevel, int, int, double, long)}.
     */
    public static ChannelAnswer channel(ServerLevel level, int x, int z, double within) {
        return channel(level, x, z, within, -1L);
    }

    /**
     * The channel or lake at a column, within {@code within} blocks past its bed. Any thread. Off the server thread it
     * waits up to {@code waitMs} for the river network to be worked out there (below zero, as long as it takes; a square
     * of it takes a second or two) and answers NOT_READY past it; on the server thread it never waits, and a square not
     * yet worked out answers NOT_READY while it is worked out in the background. The same question always comes to the
     * same answer, whatever thread asks and whatever is loaded. The first question about a stretch of river may be slow
     * (its catchment is gathered once): ask from a background thread where you can.
     */
    public static ChannelAnswer channel(ServerLevel level, int x, int z, double within, long waitMs) {
        if (!isGeologyWorld(level)) return new ChannelAnswer(Status.ABSENT, null);
        var a = com.jeladastudios.ftsgeology.hydrology.RiverFlow.ask(level, x, z, within, waitMs);
        if (a.status() != com.jeladastudios.ftsgeology.hydrology.RiverFlow.PRESENT) {
            return new ChannelAnswer(a.status() == com.jeladastudios.ftsgeology.hydrology.RiverFlow.NOT_READY
                    ? Status.NOT_READY : Status.ABSENT, null);
        }
        return new ChannelAnswer(Status.PRESENT, new Channel(a.distance(), a.halfWidth(), a.water(), a.bed(), a.flowX(),
                a.flowZ(), a.lake(), a.lakeId(), a.lakeLevel(), a.lakeOutlet(), a.head(), a.fromHead(), a.sunk(),
                a.discharge(), a.catchment(), a.capped(), a.baseFactor(), a.stormFactor()));
    }

    /** Where water comes into the river network, or leaves it. */
    public enum InflowKind {
        /** Where a river rises: its whole discharge comes in here. */
        HEAD,
        /** The way out of a lake: what the lake's own ground gives (the rivers into the lake bring their own). */
        LAKE_OUTLET,
        /** Where a channel crosses into a node's cell: what that ground and the streams too small to draw add. */
        LATERAL,
        /**
         * Where water leaves: a swallow hole a river falls into (its {@code linkId} names the cave), or with a
         * {@code linkId} of 0 a pond a river ends in, the ground closed round it. Treat it as an outlet, as the sea.
         */
        SINK,
        /** Where a cave's river comes out again, on the river it runs on in: what went into the sinks of its link. */
        RESURGENCE
    }

    /**
     * A source: a point on a channel's middle line, as a block column, and its long-run discharge (m^3/s). Below the
     * cap, a column's {@link Channel#discharge} is the sum of the sources up its river. {@code linkId} ties a cave's
     * sinks to its resurgence, the same every time the world is loaded; {@code linkLength} is the cave's length in blocks.
     */
    public record Inflow(int x, int z, double discharge, InflowKind kind, boolean capped, long linkId, double linkLength) {}

    /** An answer about a chunk's sources: the list only where {@code status} is PRESENT (it may be empty). */
    public record SourcesAnswer(Status status, List<Inflow> inflows) {}

    /**
     * The sources whose point lies in a chunk. Any thread. On the server thread a chunk not worked out yet answers
     * NOT_READY and is worked out in the background; off it, it is worked out there and then.
     */
    public static SourcesAnswer sources(ServerLevel level, int chunkX, int chunkZ) {
        if (!isGeologyWorld(level)) return new SourcesAnswer(Status.ABSENT, List.of());
        var list = com.jeladastudios.ftsgeology.hydrology.RiverFlow.sources(level, chunkX, chunkZ);
        if (list == null) return new SourcesAnswer(Status.NOT_READY, null);
        List<Inflow> out = new java.util.ArrayList<>(list.size());
        for (var s : list) {
            out.add(new Inflow(s.x(), s.z(), s.discharge(), InflowKind.valueOf(s.kind().name()), s.capped(), s.linkId(),
                    s.linkLength()));
        }
        return new SourcesAnswer(out.isEmpty() ? Status.ABSENT : Status.PRESENT, out);
    }

    /**
     * What the weather does to the rivers of a chunk now, over their long-run discharge.
     *
     * @param baseFactor    multiplies every source: the season (snow-fed rivers at the melt, a regime's wet season)
     *                      and the region's wet or dry spell; 1 on average
     * @param stormFactor   multiplies HEAD and LAKE_OUTLET sources: the quick rise of a catchment in a heavy rain on
     *                      soaked ground; 1 with no rain. Not for LATERAL: their storm water comes through
     *                      {@code runoffExcess} on your own ground
     * @param runoffExcess  by cell (4 by 4 blocks, index {@code (z & 15) / 4 * 4 + (x & 15) / 4}): rain running off the
     *                      ground beyond what it takes in, blocks of water a second a column, never below 0; for land
     *                      columns
     * @param openWater     by cell: rain on open water less what the air takes back, against the average, blocks a
     *                      second a column, either sign; for river and lake columns
     * @param leakance      by cell: how freely a river's bed leaks into the ground, per second
     * @param drawdown      by cell: how far the wells round it have drawn the ground's water down, blocks; a river over
     *                      it loses {@code leakance * drawdown} blocks a second, no more than
     *                      {@code leakance * (surface - bed + 1)} once the water is under its bed
     * @param cellsKnown    whether the chunk is loaded and its cells' values known; where not, the arrays are zero
     * @param seasonal      whether the seasons are kept (Serene Seasons)
     * @param seasonPhase   the time of year, 0 to 1 (0 without seasons); only information
     * @param seasonFactor  the season's part of baseFactor; only information
     * @param drought       how deep in a drought the region is, 0 to 1; only information (it is in baseFactor)
     * @param updatedTick   the game time the cells were last worked out, every 200 ticks
     */
    public record Forcing(double baseFactor, double stormFactor, float[] runoffExcess, float[] openWater, float[] leakance,
                          float[] drawdown, boolean cellsKnown, boolean seasonal, double seasonPhase, double seasonFactor,
                          double drought, long updatedTick) {}

    /**
     * The forcing of a chunk, loaded or not. Any thread; cheap. Every unit is in your clock, twenty ticks a second, though
     * the mod's ground runs faster (a game day is a week of it).
     */
    public static Forcing forcing(ServerLevel level, int chunkX, int chunkZ) {
        var v = com.jeladastudios.ftsgeology.hydrology.RiverForcing.at(level, chunkX, chunkZ);
        return new Forcing(v.baseFactor(), v.stormFactor(), v.runoffExcess(), v.openWater(), v.leakance(), v.drawdown(),
                v.cellsKnown(), v.seasonal(), v.seasonPhase(), v.seasonFactor(), v.drought(), v.updatedTick());
    }

    /**
     * A mod that runs the rivers' water itself, in a level. While one is registered the mod's own river rules stand
     * down there whatever its config says: the rivers' upkeep, their spilling through a bank, floods and seasonal high
     * water, the reservoirs' filling, the streams over a well's cone drying, the lakes falling in a drought, and a
     * fissure's graben filling with water. A dam too weak for the water against it still breaks (the wall is the mod's,
     * the water yours), the ground still moves its water with it ({@link RiverBlocksChangedEvent}), no rain pond is laid
     * in a chunk {@link Hydraulics#owns} owns, entities in the rivers are pushed by {@link Hydraulics#flow}, and Create
     * draws through {@link Hydraulics#drain}. Server thread; at any time (before the first tick is best). Registering
     * again replaces it; once is enough.
     */
    public static void registerHydraulics(ServerLevel level, Hydraulics hydraulics) {
        com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.register(level, hydraulics);
    }

    /** The mod's own river rules come back in a level, as its config has them. */
    public static void unregisterHydraulics(ServerLevel level) {
        com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.unregister(level);
    }

    /**
     * Whether a block is water the mod laid for a while and will take back: a flood over a river's banks, a river's
     * seasonal high water, a rain pond in a hollow. River water ({@code fts_geology:river_water}) all of it, not vanilla
     * water. The thin puddle of a rain shower is its own block ({@code fts_geology:puddle}), not a fluid. Server thread.
     */
    public static boolean isFloodWater(ServerLevel level, BlockPos pos) {
        return com.jeladastudios.ftsgeology.hydrology.Floods.isFloodWater(level, pos);
    }

    /** Whether a block is the rivers' water, still or falling ({@code fts_geology:river_water}). Any thread. */
    public static boolean isRiverWater(BlockState state) {
        return state.getFluidState().getType() instanceof com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
    }

    /**
     * Whether the river at a block is running, not standing: by the registered hydraulics' flow where one runs the
     * level (faster than 5 cm a second), else by the block's own way. Server thread; false where the block is no river
     * water.
     */
    public static boolean flowing(ServerLevel level, BlockPos pos) {
        FluidState f = level.getFluidState(pos);
        if (!(f.getType() instanceof com.jeladastudios.ftsgeology.fluid.RiverWaterFluid)) return false;
        return com.jeladastudios.ftsgeology.fluid.RiverWaterFluid.runningWay(level, pos, f) != 0;
    }

    // === Version 2: the world and the weather ================================

    /**
     * Which of the mod's terrain revisions a world was made with: a world keeps the ground of the version that made it,
     * and new ground in it is laid as then. 0 outside the mod's world. Changes this API names: 7 the volcanic arcs belted
     * by height (wet arcs no longer {@code volcanic_highland}), 8 the large volcanoes' own ground, 9 green ledges, 10 no
     * beach well over the sea, 11 rivers cut on through a strip of sand to the sea.
     */
    public static int worldgenRevision(ServerLevel level) {
        return isGeologyWorld(level) ? com.jeladastudios.ftsgeology.worldgen.terrain.WorldgenRevision.revision() : 0;
    }

    /**
     * Whether the mod's regional weather runs in a level: its storms, highs and lows. Only the overworld, with
     * {@code regionalRain} on and not under TerraFirmaCraft. Where it does not, {@link #rainAt} is the level's one
     * weather and {@link #windAt} the prevailing wind alone. Any thread.
     */
    public static boolean weatherActive(ServerLevel level) {
        return com.jeladastudios.ftsgeology.weather.Storms.on(level);
    }

    /**
     * The wind at a place, blocks a second, towards +x and +z: round the highs and lows, gusts not counted. In the
     * overworld without {@link #weatherActive regional weather}, the prevailing wind alone; none in any other level.
     * Server thread.
     */
    public static net.minecraft.world.phys.Vec3 windAt(ServerLevel level, double x, double z) {
        if (!net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())) return net.minecraft.world.phys.Vec3.ZERO;
        double[] w = com.jeladastudios.ftsgeology.weather.Atmosphere.wind(level, x, z);
        return new net.minecraft.world.phys.Vec3(w[0] * 20.0, 0, w[1] * 20.0);
    }

    /**
     * How hard it rains at a place, 0 to 1, by the regional storms. On the server thread, now and with the lift over
     * the hills; on another thread, as the storms stood at their last copy (every five seconds), without it. Where the
     * {@link #weatherActive regional weather} does not run, on any thread, the level's one weather: a thunderstorm 1,
     * rain 0.4, none 0.
     */
    public static double rainAt(ServerLevel level, int x, int z) {
        if (!weatherActive(level)) return com.jeladastudios.ftsgeology.weather.Storms.worldRain(level);
        if (level.getServer().isSameThread()) return com.jeladastudios.ftsgeology.weather.Storms.intensityAt(level, x, z);
        return com.jeladastudios.ftsgeology.weather.Storms.sky().rain(x, z);
    }

    /**
     * The wind's gusting at a place on the server, as {@link Client#gust} has it on a client: how much of its mean
     * strength it blows with now, about 0.3 to 1.8, from the game time, the wind and the rain there. A client standing
     * there reads the same at the same tick. Server thread.
     */
    public static float gustAt(ServerLevel level, double x, double z) {
        double t = level.getGameTime();
        net.minecraft.world.phys.Vec3 w = windAt(level, x, z);
        float speed = (float) Math.hypot(w.x, w.z);
        float storm = com.jeladastudios.ftsgeology.weather.Storms.intensityAt(level, Mth.floor(x), Mth.floor(z));
        return gustOf(t, speed, storm);
    }

    /** The gusting for a time, a wind speed and a rain: the same reckoning on the server and on a client. */
    static float gustOf(double t, float speed, float storm) {
        double amp = Mth.clamp(0.12 + 0.04 * speed + 0.35 * storm, 0.0, 0.75);
        double n = 0.65 * wave(t, 50.0, 17) + 0.35 * wave(t, 12.0, 91);
        return (float) Math.max(0.3, 1.0 + amp * n);
    }

    private static double wave(double t, double scale, int salt) {
        int i = Mth.floor(t);
        return Mth.lerp(t - i, ValueNoise.noise(i, salt, scale), ValueNoise.noise(i + 1, salt, scale));
    }

    /**
     * Whether a large volcano's crater lies within {@code radius} blocks of a column (only one not extinct, with
     * {@code activeOnly}). Unlike {@link #nearActiveVent} it reads nothing loaded: the same at world generation as later,
     * and safe there. Any thread.
     */
    public static boolean nearVolcano(ServerLevel level, int x, int z, int radius, boolean activeOnly) {
        for (var s : com.jeladastudios.ftsgeology.volcano.VolcanoField.sitesInBox(level, x - radius, z - radius, x + radius, z + radius)) {
            if (activeOnly && s.activity() == com.jeladastudios.ftsgeology.volcano.VolcanoActivity.EXTINCT) continue;
            if (Math.hypot(s.x() - x, s.z() - z) <= radius) return true;
        }
        return false;
    }

    /**
     * Whether a live vent lies within {@code radius} blocks of a column: the crater of a large volcano that is not extinct,
     * or a geyser's vent loaded now. It reads what is loaded: not for world generation (see {@link #nearVolcano}).
     */
    public static boolean nearActiveVent(ServerLevel level, int x, int z, int radius) {
        for (var s : com.jeladastudios.ftsgeology.volcano.VolcanoField.sitesInBox(level, x - radius, z - radius, x + radius, z + radius)) {
            if (s.activity() == com.jeladastudios.ftsgeology.volcano.VolcanoActivity.EXTINCT) continue;
            if (Math.hypot(s.x() - x, s.z() - z) <= radius) return true;
        }
        return com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity.anyWithin(level, x, z, radius);
    }

    // === The client ==========================================================

    /** The weather over the player, as the mod's regional weather has it. Client only. */
    public static final class Client {

        private Client() {}

        /** Whether the server's regional weather is reaching this client; where not, the rest read as vanilla's. */
        public static boolean active() {
            return LocalWeather.active();
        }

        /** How hard it rains where the player stands, 0 to 1, eased between ticks. */
        public static float rain(float partialTick) {
            return LocalWeather.rain(partialTick);
        }

        /** How hard it is coming to rain here, 0 to 1: what the rain is easing towards. */
        public static float rainComing() {
            return LocalWeather.rainComing();
        }

        /** How much of a thunderstorm is over the player, 0 to 1. */
        public static float thunder(float partialTick) {
            return LocalWeather.thunder(partialTick);
        }

        /** The wind where the player stands, blocks a second towards +x. */
        public static float windX() {
            return LocalWeather.windX();
        }

        /** The wind where the player stands, blocks a second towards +z. */
        public static float windZ() {
            return LocalWeather.windZ();
        }

        /** How thick a ground fog lies round the player, 0 to 1. */
        public static float fog() {
            return LocalWeather.fog();
        }

        /**
         * The wind's gusting: how much of its mean strength it blows with now, about 0.3 to 1.8, rising and falling over
         * a few seconds and more so the stronger the wind and the stormier the sky. The same at the same moment on every
         * client. Multiply a wind sound's loudness or a plant's sway by it.
         */
        public static float gust(float partialTick) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return 1f;
            double t = mc.level.getGameTime() + partialTick;
            float speed = (float) Math.hypot(windX(), windZ());
            return gustOf(t, speed, LocalWeather.storm(partialTick));
        }

        /**
         * A mod's own height for the rivers' water as it draws it: the mod's fluid drawing, its foam and the leaves on the
         * water ask it first. Client only; once (registering again replaces it).
         */
        public static void registerSurface(SurfaceHeight surface) {
            com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.clientSurface(surface);
        }

        /**
         * A mod's own flow for the rivers on the client: what pushes the player and the boat they steer, and the way the
         * running water is drawn moving. Client only; once (registering again replaces it).
         */
        public static void registerFlow(ClientFlow flow) {
            com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.clientFlow(flow);
        }

        /** What falls from the sky. {@code HAIL} is kept for when the mod has it; it is not given now. */
        public enum Precipitation { NONE, RAIN, SNOW, HAIL }

        /** What is falling where the player stands now: nothing, rain or snow, by the local weather and the biome. */
        public static Precipitation precipitation() {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return Precipitation.NONE;
            float rain = active() ? rain(1f) : mc.level.getRainLevel(1f);
            if (rain <= 0.01f) return Precipitation.NONE;
            var at = mc.player.blockPosition();
            return switch (mc.level.getBiome(at).value().getPrecipitationAt(at)) {
                case RAIN -> Precipitation.RAIN;
                case SNOW -> Precipitation.SNOW;
                default -> Precipitation.NONE;
            };
        }
    }
}
