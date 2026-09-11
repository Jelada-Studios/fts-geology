package com.jeladastudios.ftsgeology.command;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Moves a player to a place /geology find or field turned up, once the ground there has loaded.
 *
 * <p>The place is usually thousands of blocks away and not generated yet. Loading it from the server
 * thread stops the game until it is built, and loading it from inside the chunk system's own callback
 * hung the server for good. Here a ticket keeps the area loading on the world generation threads, the
 * server looks each tick whether it is ready, and moves the player only then.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class SiteTeleport {

    private SiteTeleport() {}

    /** How long a destination is waited for; the ticket drops itself after this if nothing removes it. */
    private static final int WAIT_TICKS = 1200;
    /** Keyed by request, so two waits for the same place do not share, and drop, one ticket. */
    private static final TicketType<Long> TICKET = TicketType.create("fts_geology_teleport", Long::compareTo, WAIT_TICKS);
    /** Chunks fully loaded round the destination: five by five. */
    private static final int RADIUS = 2;

    private record Request(long id, CommandSourceStack source, UUID player, ServerLevel level, int x, int z,
                           ChunkPos centre, int startTick, long startNanos) {}

    /** Touched on the server thread only. */
    private static final List<Request> PENDING = new ArrayList<>();
    private static long nextId;

    /**
     * Starts loading round x, z and moves the source's player there when it is ready. Without a player,
     * as from the console, it reports the ground height instead. Call on the server thread.
     */
    static void request(CommandSourceStack source, ServerLevel level, int x, int z) {
        ChunkPos centre = new ChunkPos(x >> 4, z >> 4);
        UUID player = source.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        // One wait per player: a second command replaces the first.
        if (player != null) {
            PENDING.removeIf(r -> {
                if (!player.equals(r.player())) return false;
                release(r);
                return true;
            });
        }
        Request r = new Request(nextId++, source, player, level, x, z, centre, level.getServer().getTickCount(),
                System.nanoTime());
        level.getChunkSource().addRegionTicket(TICKET, centre, RADIUS, r.id());
        PENDING.add(r);
        source.sendSuccess(() -> Component.translatable("command.fts_geology.teleport.waiting")
                .withStyle(ChatFormatting.GRAY), false);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        Iterator<Request> it = PENDING.iterator();
        while (it.hasNext()) {
            Request r = it.next();
            ServerPlayer p = r.player() == null ? null : server.getPlayerList().getPlayer(r.player());
            if (r.player() != null && p == null) {
                // Left the game while waiting.
                release(r);
                it.remove();
            } else if (ready(r)) {
                int y = r.level().getHeight(Heightmap.Types.MOTION_BLOCKING, r.x(), r.z());
                long ms = (System.nanoTime() - r.startNanos()) / 1_000_000;
                if (p != null) {
                    p.teleportTo(r.level(), r.x() + 0.5, y + 1, r.z() + 0.5, p.getYRot(), p.getXRot());
                } else {
                    r.source().sendSuccess(() -> Component.translatable("command.fts_geology.teleport.ready",
                            r.x(), y, r.z(), ms), false);
                }
                GeysersMod.LOGGER.info("Teleport destination {} {} {} loaded after {} ms", r.x(), y, r.z(), ms);
                release(r);
                it.remove();
            } else if (server.getTickCount() - r.startTick() >= WAIT_TICKS - 20) {
                r.source().sendFailure(Component.translatable("command.fts_geology.teleport.timeout"));
                GeysersMod.LOGGER.info("Teleport destination {} {} not loaded after {} ticks", r.x(), r.z(),
                        WAIT_TICKS - 20);
                release(r);
                it.remove();
            }
        }
    }

    /** The destination chunk and the eight round it are loaded, so standing there reads nothing that is not. */
    private static boolean ready(Request r) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (r.level().getChunkSource().getChunkNow(r.centre().x + dx, r.centre().z + dz) == null) return false;
            }
        }
        return true;
    }

    private static void release(Request r) {
        r.level().getChunkSource().removeRegionTicket(TICKET, r.centre(), RADIUS, r.id());
    }

    /** Drops every wait; called when a server stops. */
    public static void clear() {
        PENDING.clear();
    }
}
