package com.jeladastudios.ftsgeology.compat;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraftforge.fml.ModList;

import java.util.Set;

/**
 * Flowing Fluids moves a fluid out of the way of a block put into it. A river's water pushed out of its channel that
 * way went up on top of the block whole, a source nothing drains, and that mod ran its own water off it without end;
 * a hole under a river closed again pushed it up over the river's surface, where it lay. The rivers' water is put on
 * that mod's list of fluids it leaves alone, as its own API lets a mod do, so a block put into a river takes the
 * water's place as it does without that mod. Only in memory: that mod's settings file is not written, and it sends
 * the list to players with the rest of its settings.
 */
public final class FlowingFluidsRivers {

    private FlowingFluidsRivers() {}

    private static final String[] RIVER = {GeysersMod.MODID + ":river_water", GeysersMod.MODID + ":flowing_river_water"};

    /** Puts the rivers' water on Flowing Fluids' list, if that mod is here; again whenever its settings were replaced. */
    public static void keepOut() {
        if (!ModList.get().isLoaded("flowing_fluids")) return;
        try {
            Class<?> api = Class.forName("traben.flowing_fluids.api.FlowingFluidsAPI");
            Object instance = api.getMethod("getInstance", String.class).invoke(null, GeysersMod.MODID);
            Object config = api.getMethod("getConfig").invoke(instance);
            if (config == null) return;
            @SuppressWarnings("unchecked")
            Set<String> list = (Set<String>) config.getClass().getField("fluidBlacklist").get(config);
            boolean added = false;
            for (String id : RIVER) added |= list.add(id);
            if (added) GeysersMod.LOGGER.info("Flowing Fluids: the rivers' water is left alone, so blocks put into it take its place");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            GeysersMod.LOGGER.warn("Flowing Fluids: could not put the rivers' water on its list of fluids it leaves alone: {}", e.toString());
        }
    }
}
