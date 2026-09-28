package com.jeladastudios.ftsgeology.compat.dh;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiFogConfig;
import net.minecraftforge.fml.ModList;

/**
 * Ash in the air where Distant Horizons draws the far terrain. Distant Horizons turns the game's own fog off by default,
 * which took the ash fog with it: the sky went grey and nothing closed in. While ash hangs round the viewer the game's
 * fog is turned back on through Distant Horizons' API, and its own fog over the far terrain is thickened with the ash,
 * so the mountains go grey too; when the air clears, the player's settings are handed back as they were. Nothing is
 * written to its config file. Client only.
 */
public final class DhAshFog {

    private DhAshFog() {}

    private static Boolean present;
    /** Whether the fog is being held on, and the thickness last handed over. */
    private static boolean holding;
    private static float given = -1f;

    /** Called every client tick with how thick the ash is round the viewer, 0 to 1. */
    public static void update(double ash) {
        if (present == null) present = ModList.get().isLoaded("distanthorizons");
        if (!present) return;
        try {
            Hooks.update(ash);
        } catch (Throwable t) {
            // An API that has moved on: the sky still greys, and the fog goes on being Distant Horizons' own.
            present = false;
            GeysersMod.LOGGER.warn("Distant Horizons' fog settings could not be reached ({}); ash fog stays off under it", t.toString());
        }
    }

    /** Separate so that {@link DhAshFog} itself names nothing of Distant Horizons'. */
    private static final class Hooks {
        static void update(double ash) {
            IDhApiConfig configs = DhApi.Delayed.configs;
            if (configs == null) return;
            IDhApiFogConfig fog = configs.graphics().fog();
            if (ash > 0.02) {
                if (!holding) {
                    set(fog.enableVanillaFog(), Boolean.TRUE);
                    holding = true;
                }
                float thick = (float) Math.min(1.0, ash * 1.2);
                if (Math.abs(thick - given) > 0.04f) {
                    set(fog.farFog().farFogMinThickness(), thick);
                    set(fog.farFog().farFogStartDistance(), (float) (0.4 * (1.0 - ash)));
                    given = thick;
                }
            } else if (holding) {
                fog.enableVanillaFog().clearValue();
                fog.farFog().farFogMinThickness().clearValue();
                fog.farFog().farFogStartDistance().clearValue();
                holding = false;
                given = -1f;
            }
        }

        private static <T> void set(IDhApiConfigValue<T> value, T to) {
            if (value.getCanBeOverrodeByApi()) value.setValue(to);
        }
    }
}
