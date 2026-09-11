package com.jeladastudios.ftsgeology.util;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Writes every thread's stack to a file when the server thread stops finishing ticks for a while.
 *
 * <p>A hang in a large pack otherwise leaves only a frozen game behind. The stacks show what the server
 * was waiting on and who holds it. Reads nothing from the world and writes only that one file.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class StallReport {

    private StallReport() {}

    private static final int STACK_DEPTH = 60;

    private static volatile long lastTick = System.nanoTime();
    private static volatile Thread watcher;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) lastTick = System.nanoTime();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        stop();
        int seconds = GeyserConfig.STALL_REPORT_SECONDS.get();
        if (seconds <= 0) return;
        MinecraftServer server = event.getServer();
        lastTick = System.nanoTime();
        Thread t = new Thread(() -> watch(server, seconds * 1_000_000_000L), "FTsGeology stall watch");
        t.setDaemon(true);
        watcher = t;
        t.start();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    private static void stop() {
        Thread t = watcher;
        watcher = null;
        if (t != null) t.interrupt();
    }

    private static void watch(MinecraftServer server, long limit) {
        boolean reported = false;
        while (watcher == Thread.currentThread()) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            long quiet = System.nanoTime() - lastTick;
            if (quiet < limit) {
                reported = false;
            } else if (!reported && server.isRunning() && !paused()) {
                // Once per stall: the stacks at the start of it are the useful ones.
                reported = true;
                write(server, quiet / 1_000_000_000L);
            }
        }
    }

    /** A paused single player game stops ticking on purpose. */
    private static boolean paused() {
        return FMLEnvironment.dist.isClient() && com.jeladastudios.ftsgeology.client.ClientPause.isPaused();
    }

    private static void write(MinecraftServer server, long seconds) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StringBuilder out = new StringBuilder();
        out.append("The server thread has not finished a tick for ").append(seconds).append(" s.\n\n");
        long[] deadlocked = mx.findDeadlockedThreads();
        out.append("Deadlocked thread ids: ").append(deadlocked == null ? "none" : Arrays.toString(deadlocked))
                .append("\n\n");
        long serverId = server.getRunningThread().getId();
        ThreadInfo[] infos = mx.dumpAllThreads(true, true);
        // The server thread first, then the rest by name.
        Arrays.sort(infos, Comparator.comparing((ThreadInfo i) -> i.getThreadId() != serverId)
                .thenComparing(ThreadInfo::getThreadName));
        for (ThreadInfo info : infos) {
            out.append('"').append(info.getThreadName()).append("\" id=").append(info.getThreadId())
                    .append(' ').append(info.getThreadState());
            if (info.getLockName() != null) out.append(" on ").append(info.getLockName());
            if (info.getLockOwnerName() != null) out.append(" held by \"").append(info.getLockOwnerName()).append('"');
            out.append('\n');
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, STACK_DEPTH); i++) out.append("    at ").append(stack[i]).append('\n');
            if (stack.length > STACK_DEPTH) out.append("    ...\n");
            out.append('\n');
        }
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("fts_geology_stall.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out.toString());
            GeysersMod.LOGGER.warn("The server thread has not ticked for {} s; thread stacks written to {}", seconds, file);
        } catch (IOException e) {
            GeysersMod.LOGGER.warn("The server thread has not ticked for {} s; could not write {}: {}", seconds, file,
                    e.toString());
        }
    }
}
