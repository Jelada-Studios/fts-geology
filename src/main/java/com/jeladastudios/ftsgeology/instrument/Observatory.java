package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.block.WeatherInstrumentBlock;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.volcano.VolcanoUnrest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * What a volcano observatory's instruments read, and the alert it sets from them.
 *
 * <ul>
 *   <li><b>Tiltmeter</b>: magma rising under a volcano lifts its flanks, so the ground tilts up toward the mountain, a
 *   few microradians far out and hundreds near the vent as an eruption nears. Between eruptions it reads the earth tide's
 *   tenth of a microradian.</li>
 *   <li><b>GPS station</b>: how fast the plate it stands on drifts, a few centimetres a year, which way, and how far a
 *   restless volcano has lifted the ground under it.</li>
 *   <li><b>Gas meter</b>: carbon dioxide and sulphur dioxide in the air round it: the air's own 420 parts in a million
 *   of CO2 and no SO2 at all, rising as the gas comes up with the magma, and deadly in a hollow the gas lies in.</li>
 * </ul>
 *
 * <p>The alert is the one volcano observatories give: green, normal; yellow, restless; orange, an eruption likely; red,
 * an eruption under way or about to be. Set from the unrest of the volcano whose reach the instrument is in.</p>
 */
public final class Observatory {

    private Observatory() {}

    public enum Alert { GREEN, YELLOW, ORANGE, RED }

    /** The alert for the volcano whose reach a place is in: green outside every restless one's. */
    public static Alert alert(ServerLevel level, BlockPos pos) {
        VolcanoUnrest.Restless r = VolcanoUnrest.nearest(level, pos.getX(), pos.getZ());
        if (r == null) return Alert.GREEN;
        if (r.erupting() || r.progress() >= 0.8) return Alert.RED;
        if (r.progress() >= 0.45) return Alert.ORANGE;
        return Alert.YELLOW;
    }

    public static Component alertLine(Alert a) {
        ChatFormatting color = switch (a) {
            case GREEN -> ChatFormatting.GREEN;
            case YELLOW -> ChatFormatting.YELLOW;
            case ORANGE -> ChatFormatting.GOLD;
            case RED -> ChatFormatting.RED;
        };
        return Component.translatable("message.fts_geology.alert." + a.name().toLowerCase(Locale.ROOT)).withStyle(color);
    }

    /** Microradians the ground tilts here, and the bearing it tilts up toward, in degrees from north. */
    public static double[] tilt(ServerLevel level, BlockPos pos) {
        VolcanoUnrest.Restless r = VolcanoUnrest.nearest(level, pos.getX(), pos.getZ());
        // The earth tide: a tenth of a microradian, swinging round twice a day.
        double tide = 0.1 * Math.sin(level.getDayTime() / 24000.0 * 4 * Math.PI);
        if (r == null) return new double[]{Math.abs(tide), tide >= 0 ? 0 : 180};
        double dx = r.summit().getX() - pos.getX(), dz = r.summit().getZ() - pos.getZ();
        double d = Math.max(1, Math.hypot(dx, dz));
        // The swell four blocks nearer the summit against four further out: the slope it puts on the ground.
        double nearer = VolcanoUnrest.swell(r, pos.getX() + 4 * dx / d, pos.getZ() + 4 * dz / d);
        double further = VolcanoUnrest.swell(r, pos.getX() - 4 * dx / d, pos.getZ() - 4 * dz / d);
        double micro = (nearer - further) / 100.0 / (8 * DepthScale.metresPerBlockHorizontal()) * 1e6;
        double bearing = Math.toDegrees(Math.atan2(dx, -dz));
        return new double[]{Math.abs(micro + tide), ((bearing % 360) + 360) % 360};
    }

    /** The ground's drift in millimetres a year and its bearing, and how far a restless volcano has lifted it, in cm. */
    public static double[] gps(ServerLevel level, BlockPos pos) {
        // The same plate the fault compass reads, in its units: centimetres a year, here in millimetres.
        com.jeladastudios.ftsgeology.tectonics.PlateSample s = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, pos.getX(), pos.getZ());
        double mm = s.plateSpeed() * 10.0;
        double bearing = s.plateBearing();
        VolcanoUnrest.Restless r = VolcanoUnrest.nearest(level, pos.getX(), pos.getZ());
        double up = r == null ? 0 : VolcanoUnrest.swellCm(r, pos.getX(), pos.getZ());
        return new double[]{mm, bearing, up};
    }

    /** Carbon dioxide and sulphur dioxide in the air at the meter, in parts per million. */
    public static double[] gas(ServerLevel level, BlockPos pos) {
        double co2 = 420, so2 = 0;
        double near = VolcanoUnrest.near(level, pos);
        VolcanoUnrest.Restless r = VolcanoUnrest.nearest(level, pos.getX(), pos.getZ());
        if (near > 0) {
            co2 += 4000 * near;
            so2 += 12 * near * (0.4 + 0.6 * (r == null ? 0 : r.progress()));
        }
        if (com.jeladastudios.ftsgeology.volcano.VolcanicGas.at(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) != null) {
            co2 = Math.max(co2, 60_000);
            so2 = Math.max(so2, 20);
        }
        return new double[]{co2, so2};
    }

    /** An observatory instrument's reading, in a line, with the alert after it. */
    public static Component reading(ServerLevel level, BlockPos pos, WeatherInstrumentBlock.Instrument in) {
        String k = "message.fts_geology.instrument." + in.key();
        Component alert = alertLine(alert(level, pos));
        return switch (in) {
            case TILTMETER -> {
                double[] t = tilt(level, pos);
                yield Component.translatable(k, String.format(Locale.ROOT, t[0] >= 10 ? "%.0f" : "%.1f", t[0]),
                        Component.translatable("message.fts_geology.compass." + WeatherInstrumentBlock.compass(t[1])), alert);
            }
            case GPS -> {
                double[] g = gps(level, pos);
                yield Component.translatable(k, String.format(Locale.ROOT, "%.0f", g[0]),
                        Component.translatable("message.fts_geology.compass." + WeatherInstrumentBlock.compass(g[1])),
                        String.format(Locale.ROOT, "%+.0f", g[2]), alert);
            }
            default -> {
                double[] g = gas(level, pos);
                yield Component.translatable(k, String.format(Locale.ROOT, "%.0f", g[0]), String.format(Locale.ROOT, "%.1f", g[1]), alert);
            }
        };
    }

    /** All three readings and the alert at a place, in a line, for the log and for tests. */
    public static String summary(ServerLevel level, BlockPos pos) {
        double[] t = tilt(level, pos), g = gps(level, pos), c = gas(level, pos);
        return String.format(Locale.ROOT, "alert %s; tilt %.1f urad toward %.0f; drift %.0f mm/yr toward %.0f, up %.0f cm; CO2 %.0f ppm, SO2 %.1f ppm",
                alert(level, pos), t[0], t[1], g[0], g[1], g[2], c[0], c[1]);
    }

    /** A comparator's reading of one: over its range, or the alert in four steps where the instrument is quiet. */
    public static double signal(ServerLevel level, BlockPos pos, WeatherInstrumentBlock.Instrument in) {
        return switch (in) {
            case TILTMETER -> Math.min(1.0, tilt(level, pos)[0] / 100.0);
            case GPS -> Math.min(1.0, gps(level, pos)[2] / 40.0);
            default -> Math.min(1.0, (gas(level, pos)[0] - 420) / 5000.0);
        };
    }
}
