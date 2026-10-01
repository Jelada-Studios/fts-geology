package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * What a weather station reads, and what it can tell of the days ahead.
 *
 * <p>The readings are the air's own (see {@link Atmosphere} and {@link Storms}): the pressure at the instrument, less
 * with height as a barometer's is, and brought to sea level as a forecaster brings it; the wind, stronger high up and
 * none out of it, in knots; the air's temperature and humidity; the rain falling into a gauge open to the sky.</p>
 *
 * <p>The forecast is the weather's own future, as far as it is set: the highs and lows drift where they will be and
 * deepen or fill as they will, and the storms out now move along their paths; what new storms will form is not known,
 * only how likely rain is in the air coming, so it is given as a chance, and the further ahead, the more it is only the
 * season's odds. A station with fewer instruments sees less far.</p>
 */
public final class Meteorology {

    private Meteorology() {}

    // === Readings =========================================================

    /** The pressure brought to sea level, hectopascals: what a forecaster compares. */
    public static double seaLevelPressure(ServerLevel level, BlockPos pos) {
        return Atmosphere.pressure(level, pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    /** The pressure the instrument itself feels, hectopascals: less the higher it stands. */
    public static double stationPressure(ServerLevel level, BlockPos pos) {
        return seaLevelPressure(level, pos) * Math.exp(-altitude(level, pos) / 8434.0);
    }

    /**
     * A place's height over the sea, metres: some sixteen a block in the vanilla-high world, whose peaks near y 250 are
     * some three thousand metres, and twelve in the tall one, where Everest's 8848 stand at y 800.
     */
    public static double altitude(ServerLevel level, BlockPos pos) {
        return (pos.getY() - level.getSeaLevel()) * (com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.tall() ? 12.0 : 16.0);
    }

    /**
     * The wind at an instrument, metres a second and the bearing it blows from (degrees, 0 north, 90 east): stronger
     * the higher it stands over the ground round it, and none where it is walled in.
     */
    public static double[] wind(ServerLevel level, BlockPos pos) {
        double[] w = Atmosphere.wind(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        double speed = Math.hypot(w[0], w[1]) * 20;
        if (!level.canSeeSky(pos.above())) speed *= 0.15;
        int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        speed *= 1 + Mth.clamp((pos.getY() - ground) / 40.0, 0, 1.5);
        // Blowing towards +x, +z; it comes from the other way. North is -z.
        double from = Math.toDegrees(Math.atan2(-w[0], w[1]));
        return new double[]{speed, Mth.positiveModulo(from, 360.0)};
    }

    public static double knots(double metresPerSecond) {
        return metresPerSecond * 1.94384;
    }

    public static double temperature(ServerLevel level, BlockPos pos) {
        return Atmosphere.temperature(level, pos);
    }

    public static double humidity(ServerLevel level, BlockPos pos) {
        return Atmosphere.humidity(level, pos);
    }

    /** Rain falling into a gauge open to the sky, millimetres an hour; snow counts as the water it melts into. */
    public static double rainRate(ServerLevel level, BlockPos pos) {
        if (!level.canSeeSky(pos.above())) return 0;
        if (level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.NONE) return 0;
        float i = Storms.intensityAt(level, pos.getX(), pos.getZ());
        return i < Storms.WET ? 0 : rateOf(i);
    }

    /** Millimetres an hour for a rain's strength: a drizzle under one, a steady rain a few, a downpour tens. */
    static double rateOf(double i) {
        return 40 * i * i;
    }

    // === The forecast =====================================================

    /** A stretch of the days ahead: from which hour, the chances of rain, of a downpour and of thunder, the air. */
    public record Period(int fromHour, float rain, float heavy, float thunder, float coldest, float warmest, float windKnots,
                         float pressure) {}

    /**
     * The days ahead at a station, in stretches of {@code hours} game hours, {@code count} of them. {@code sight}, 0 to
     * 1, is how much of the weather the station sees: with a barometer alone it knows the pressure and little else, and
     * past its first stretches gives the season's odds; with the wind, the humidity and the temperature as well, it sees
     * the systems' course.
     */
    public static List<Period> forecast(ServerLevel level, BlockPos pos, int count, int hours, double sight) {
        List<Period> out = new ArrayList<>();
        long now = level.getGameTime();
        double x = pos.getX() + 0.5, z = pos.getZ() + 0.5;
        List<Storms.Storm> storms = Storms.storms(level);
        double climate = Storms.wantedAt(level, pos, now);
        for (int p = 0; p < count; p++) {
            int from = p * hours;
            double dry = 1, noHeavy = 1, noThunder = 1, air = 0, windMax = 0, pressure = 0;
            double coldest = Double.MAX_VALUE, warmest = -Double.MAX_VALUE;
            int samples = 3;
            for (int k = 0; k < samples; k++) {
                long ahead = (long) ((from + (k + 0.5) * hours / samples) * 1000L);
                long when = now + ahead;
                // The storms out now, where their paths take them.
                double rain = 0;
                for (Storms.Storm s : storms) {
                    if (when >= s.dies) continue;
                    double dt = when - now;
                    double v = s.at(x - s.vx * dt, z - s.vz * dt, when);
                    if (v < Storms.WET) continue;
                    rain = 1 - (1 - rain) * (1 - v);
                    if (v >= Storms.HEAVY) noHeavy *= 0.4;
                    if (s.thunder && v >= 0.3) noThunder *= 0.5;
                }
                dry *= 1 - Math.min(0.95, rain * 1.4);
                // New storms, as likely as the air then makes them, and less in the first hours: they take time to form.
                double want = Storms.wantedAt(level, pos, when);
                double gather = Math.min(1.0, ahead / 6000.0);
                dry *= Math.pow(1 - Math.min(0.9, want), 0.9 * gather);
                air += want / samples;
                double[] w = Atmosphere.windAt(level, x, z, when);
                windMax = Math.max(windMax, Math.hypot(w[0], w[1]) * 20);
                double pr = Atmosphere.pressure(level, x, z, when);
                pressure += pr / samples;
                double t = Atmosphere.temperatureAt(level, pos, ahead, rain);
                coldest = Math.min(coldest, t);
                warmest = Math.max(warmest, t);
            }
            double rainChance = 1 - dry;
            double heavyChance = Math.max(1 - noHeavy, rainChance * Math.min(1, air * 1.2) * 0.35);
            double thunderChance = Math.max(1 - noThunder, rainChance * (pressure < 1000 ? 0.3 : 0.12) * (warmest > 15 ? 1 : 0.4));
            // Further ahead, and the less the station sees, the more it is only the season's odds.
            double doubt = Mth.clamp(from / 72.0 * (1.6 - sight), 0, 0.85);
            double odds = 1 - Math.pow(1 - Math.min(0.9, climate), 1.8);
            rainChance = Mth.lerp(doubt, rainChance, odds);
            heavyChance = Mth.lerp(doubt, heavyChance, odds * 0.3);
            thunderChance = Mth.lerp(doubt, thunderChance, odds * 0.12);
            out.add(new Period(from, (float) rainChance, (float) heavyChance, (float) thunderChance, (float) coldest, (float) warmest,
                    (float) knots(windMax), (float) pressure));
        }
        return out;
    }

    /** How the sea-level pressure has changed at a station over the last three hours, hectopascals: the barometer's own word. */
    public static double tendency(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        return Atmosphere.pressure(level, pos.getX() + 0.5, pos.getZ() + 0.5, now)
                - Atmosphere.pressure(level, pos.getX() + 0.5, pos.getZ() + 0.5, now - 3000);
    }
}
