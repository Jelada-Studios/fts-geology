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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

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
 * {@link ThunderHeardEvent} on the client when a peal of the mod's thunder is played.</p>
 */
public final class FtsGeologyApi {

    private FtsGeologyApi() {}

    /** This API's version: raised when something is added, never when something is taken away. */
    public static final int VERSION = 1;

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

    /**
     * Whether a live vent lies within {@code radius} blocks of a column: the crater of a large volcano that is not extinct,
     * or a geyser's vent loaded now.
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
            double amp = Mth.clamp(0.12 + 0.04 * speed + 0.35 * LocalWeather.storm(partialTick), 0.0, 0.75);
            double n = 0.65 * wave(t, 50.0, 17) + 0.35 * wave(t, 12.0, 91);
            return (float) Math.max(0.3, 1.0 + amp * n);
        }

        private static double wave(double t, double scale, int salt) {
            int i = Mth.floor(t);
            return Mth.lerp(t - i, ValueNoise.noise(i, salt, scale), ValueNoise.noise(i + 1, salt, scale));
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
