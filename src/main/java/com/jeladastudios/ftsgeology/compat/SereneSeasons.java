package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * The season, where Serene Seasons is installed: read through its public API by name (the user allowed it, 2026-10-01;
 * Serene Seasons is all rights reserved, so nothing of it is copied or bound to at build time). Without it there are no
 * seasons and nothing here is ever asked for.
 *
 * <p>The year is read as a phase, 0 to 1 from the start of early spring, from its cycle's ticks; the tropical biomes'
 * wet and dry halves as their own.</p>
 */
public final class SereneSeasons {

    private SereneSeasons() {}

    private static final Api API = Api.find();

    private record Api(Method state, Method tropical, Method cycleTicks, Method cycleDuration, Method tropicalSeason) {
        static Api find() {
            if (!ModList.get().isLoaded("sereneseasons")) return null;
            try {
                Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
                Class<?> state = Class.forName("sereneseasons.api.season.ISeasonState");
                Api api = new Api(helper.getMethod("getSeasonState", Level.class), helper.getMethod("usesTropicalSeasons", Holder.class),
                        state.getMethod("getSeasonCycleTicks"), state.getMethod("getCycleDuration"), state.getMethod("getTropicalSeason"));
                GeysersMod.LOGGER.info("Serene Seasons: the rain follows its seasons");
                return api;
            } catch (ReflectiveOperationException | LinkageError e) {
                GeysersMod.LOGGER.warn("Serene Seasons is installed but its seasons were not found as expected; the rain keeps no seasons: {}", e.toString());
                return null;
            }
        }
    }

    /** Whether the seasons can be read. */
    public static boolean active() {
        return API != null;
    }

    /** The phase of the year in a level, 0 to 1 from the start of early spring; NaN without the seasons. */
    public static double phase(Level level) {
        if (API == null) return Double.NaN;
        try {
            Object s = API.state().invoke(null, level);
            if (s == null) return Double.NaN;
            int ticks = (Integer) API.cycleTicks().invoke(s), length = (Integer) API.cycleDuration().invoke(s);
            if (length <= 0) return Double.NaN;
            double p = (ticks % length) / (double) length;
            return p < 0 ? p + 1 : p;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Whether a biome keeps the tropics' wet and dry seasons rather than the four. */
    public static boolean tropical(Holder<Biome> biome) {
        if (API == null) return false;
        try {
            return (Boolean) API.tropical().invoke(null, biome);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** The tropical season now, 0 to 5 (early, mid and late dry, then early, mid and late wet); -1 without it. */
    public static int tropicalSeason(Level level) {
        if (API == null) return -1;
        try {
            Object s = API.state().invoke(null, level);
            Object t = s == null ? null : API.tropicalSeason().invoke(s);
            return t instanceof Enum<?> e ? e.ordinal() : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
