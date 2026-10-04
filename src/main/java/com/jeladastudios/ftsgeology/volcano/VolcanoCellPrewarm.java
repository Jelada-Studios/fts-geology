package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The large volcanoes' cells round each player worked out ahead of them, on a thread of their own. A cell is worked
 * out the first time a chunk in it is made, and the search for its site -- tens of candidates, each with the structures
 * that might be due under it -- can take a second or more; a chunk the server waits for, at the edge of a player's
 * view, held the game for that long. Worked out a cell or two ahead, it is there when the chunks come.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class VolcanoCellPrewarm {

    private VolcanoCellPrewarm() {}

    /** How often the players are looked at, ticks, and how many cells out round each. */
    private static final int EVERY = 100, RINGS = 2;

    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FTs Geology volcano cells");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % EVERY != 0) return;
        if (!GeyserConfig.LARGE_VOLCANOES.get()) return;
        ServerLevel level = event.getServer().overworld();
        int cell = VolcanoField.cell();
        for (ServerPlayer p : level.players()) {
            int cx = Math.floorDiv(p.getBlockX(), cell), cz = Math.floorDiv(p.getBlockZ(), cell);
            for (int dx = -RINGS; dx <= RINGS; dx++) {
                for (int dz = -RINGS; dz <= RINGS; dz++) {
                    int x = cx + dx, z = cz + dz;
                    if (VolcanoField.known(x, z)) continue;
                    long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                    if (!QUEUED.add(key)) continue;
                    WORKER.execute(() -> {
                        try {
                            if (level.getServer().isRunning()) VolcanoField.warm(level, x, z);
                        } catch (RuntimeException e) {
                            GeysersMod.LOGGER.debug("Volcano cell {},{} not worked out ahead: {}", x, z, e.toString());
                        } finally {
                            QUEUED.remove(key);
                        }
                    });
                }
            }
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        QUEUED.clear();
    }
}
