package com.jeladastudios.ftsgeology.weather;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A bed by day in a thunderstorm, as vanilla allows it: its sky is dark enough to sleep under. The world's sky is
 * reckoned by the time of day alone where the rain is regional ({@link RainContext#pushClear}), so the darkness of the
 * storm over the player is reckoned here instead, by vanilla's own sum, from the rain and thunder where they lie.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class StormSleep {

    private StormSleep() {}

    /** How dark vanilla's sky has to be for a night: 4 and more of 11. */
    private static final int NIGHT = 4;

    @SubscribeEvent
    public static void onSleepingTime(SleepingTimeCheckEvent event) {
        Player player = event.getEntity();
        if (event.getResult() != Event.Result.DEFAULT || !(player.level() instanceof ServerLevel level) || !Storms.on(level)) return;
        int x = player.getBlockX(), z = player.getBlockZ();
        float rain = Storms.intensityAt(level, x, z);
        if (rain < Storms.WET) return;
        float thunder = Storms.thunderAt(level, x, z) * rain;
        double wet = 1.0 - rain * 5.0 / 16.0, stormy = 1.0 - thunder * 5.0 / 16.0;
        double sun = 0.5 + 2.0 * Mth.clamp(Mth.cos(level.getTimeOfDay(1.0f) * ((float) Math.PI * 2f)), -0.25, 0.25);
        if ((int) ((1.0 - sun * wet * stormy) * 11.0) >= NIGHT) event.setResult(Event.Result.ALLOW);
    }
}
