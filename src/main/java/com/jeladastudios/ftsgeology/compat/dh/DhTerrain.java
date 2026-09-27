package com.jeladastudios.ftsgeology.compat.dh;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/**
 * Hands Distant Horizons the mod's own far-terrain generator for the overworld of the mod's world types. Only touched
 * when Distant Horizons is installed; the classes here name its API, which is not there otherwise.
 */
public final class DhTerrain {

    private DhTerrain() {}

    /** Called once at setup: listens for Distant Horizons opening a level, if it is installed. */
    public static void init() {
        if (!ModList.get().isLoaded("distanthorizons")) return;
        try {
            Hooks.bind();
        } catch (Throwable t) {
            // A Distant Horizons whose API has moved on: it keeps generating its own way.
            GeysersMod.LOGGER.warn("Distant Horizons is installed but its API could not be bound ({}); its own far terrain is used", t.toString());
        }
    }

    /** Separate so that {@link DhTerrain} itself names nothing of Distant Horizons'. */
    private static final class Hooks {
        static void bind() {
            DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
                @Override
                public void onLevelLoad(DhApiEventParam<EventParam> event) {
                    register(event.value.levelWrapper);
                }
            });
            GeysersMod.LOGGER.info("Distant Horizons found: the far terrain of the mod's worlds is drawn from the plate model");
        }

        private static void register(IDhApiLevelWrapper wrapper) {
            // A client's level is drawn from what its server sends; only a server's is generated.
            if (!(wrapper.getWrappedMcObject() instanceof ServerLevel level)) return;
            if (!GeyserConfig.DH_TERRAIN.get() || !Level.OVERWORLD.equals(level.dimension()) || !GeologyWorld.isOwn(level)) {
                return;
            }
            DhApi.worldGenOverrides.registerWorldGeneratorOverride(wrapper, new GeologyLodGenerator(wrapper, level));
            GeysersMod.LOGGER.info("Distant Horizons: far terrain of {} from the plate model", level.dimension().location());
        }
    }

    public static String summary() {
        return ModList.get().isLoaded("distanthorizons") ? GeologyLodGenerator.summary() : "";
    }
}
