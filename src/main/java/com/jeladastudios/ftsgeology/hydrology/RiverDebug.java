package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.ModNetwork;
import com.jeladastudios.ftsgeology.network.RiverDebugPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The river debug view: once a second, each player who asked for it is sent what the survey knows within
 * {@link #RADIUS_CHUNKS} chunks of them, and the client draws it. Nothing here changes the world.
 */
public final class RiverDebug {

    private RiverDebug() {}

    /** How far round a player the view reaches, in chunks. */
    static final int RADIUS_CHUNKS = 10;

    private static final Set<UUID> WATCHING = ConcurrentHashMap.newKeySet();

    public static void set(ServerPlayer player, boolean on) {
        if (on) WATCHING.add(player.getUUID());
        else WATCHING.remove(player.getUUID());
    }

    public static void clear() {
        WATCHING.clear();
    }

    /** Sends every watching player their view, once a second. */
    static void tick(MinecraftServer server) {
        if (WATCHING.isEmpty() || server.getTickCount() % 20 != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                if (!WATCHING.contains(p.getUUID())) continue;
                ModNetwork.sendRiverDebug(p, collect(level, p.getBlockX(), p.getBlockZ()));
            }
        }
    }

    /** What the survey knows within range of a point. Server thread only. */
    public static RiverDebugPacket collect(ServerLevel level, int px, int pz) {
        RiverSurvey survey = RiverSurvey.of(level);
        long now = level.getGameTime();
        int pcx = px >> 4, pcz = pz >> 4;
        List<RiverDebugPacket.ChunkInfo> out = new ArrayList<>();
        for (int cx = pcx - RADIUS_CHUNKS; cx <= pcx + RADIUS_CHUNKS; cx++) {
            for (int cz = pcz - RADIUS_CHUNKS; cz <= pcz + RADIUS_CHUNKS; cz++) {
                RiverSurvey.Rec r = survey.get(cx, cz);
                if (r == null || !r.river()) continue;
                // The first river cell, where the network is asked about the chunk's water.
                // West of the origin the coordinate itself is negative, so "not found" is not a sign.
                int rx = Integer.MIN_VALUE, rz = Integer.MIN_VALUE;
                for (int i = 0; i < 256 && rx == Integer.MIN_VALUE; i++) {
                    if (r.at(i & 15, i >> 4)) { rx = cx * 16 + (i & 15); rz = cz * 16 + (i >> 4); }
                }
                byte state;
                long river = 0;
                float fx = 0, fz = 0, speed = 1.0f;
                // Only what has been read: the view must not start reads of its own.
                RiverNetwork.Node node = rx == Integer.MIN_VALUE ? null : RiverNetwork.peek(rx, rz);
                if (node != null) {
                    river = node.river();
                    speed = (float) RiverSurvey.speed(node);
                    double[] flow = RiverNetwork.flow(rx, rz);
                    if (flow != null) { fx = (float) flow[0]; fz = (float) flow[1]; }
                }
                if (!r.current()) state = RiverDebugPacket.STALE;
                else if (!r.planned) state = RiverDebugPacket.PENDING;
                else if (node == null) state = RiverDebugPacket.NO_NETWORK;
                else if (node.lake()) state = RiverDebugPacket.LAKE;
                else if (r.coast || (node.directed() && node.dist() < RiverNetwork.MOUTH_ZONE)) state = RiverDebugPacket.MOUTH;
                else state = RiverDebugPacket.PLANNED;
                List<RiverDebugPacket.BendInfo> bends = new ArrayList<>(r.bends.size());
                for (RiverSurvey.Bend b : r.bends) {
                    int next = (int) Math.max(0, Math.min(Integer.MAX_VALUE, b.next - now));
                    bends.add(new RiverDebugPacket.BendInfo(b.x, b.z, b.nx, b.nz, b.width, b.steps, b.done, b.speed,
                            next, b.dead, b.why == null ? "" : b.why));
                }
                out.add(new RiverDebugPacket.ChunkInfo(cx, cz, r.yW, state, river, fx, fz, speed,
                        r.centre == null ? new byte[0] : r.centre, bends));
            }
        }
        return new RiverDebugPacket(out);
    }

    /** The view as text, one line a chunk and one a bend, for the dump command and the log. */
    public static List<String> lines(RiverDebugPacket p) {
        List<String> out = new ArrayList<>();
        for (RiverDebugPacket.ChunkInfo c : p.chunks()) {
            out.add(String.format(Locale.ROOT, "chunk %d,%d: %s, water Y %d, river #%s, flow (%.2f, %.2f), speed %.2f, centre %d cells, bends %d",
                    c.cx(), c.cz(), stateName(c.state()), c.yW(), riverName(c.river()), c.fx(), c.fz(), c.speed(),
                    c.centre().length / 2, c.bends().size()));
            for (RiverDebugPacket.BendInfo b : c.bends()) {
                out.add(String.format(Locale.ROOT, "  bend %d,%d: width %d, step %d of %d, next in %d min, speed %.2f%s%s",
                        b.x(), b.z(), b.width(), b.done(), b.steps(), b.nextTicks() / 1200, b.speed(),
                        b.dead() ? ", dead" : "", b.why().isEmpty() ? "" : " (" + b.why() + ")"));
            }
        }
        return out;
    }

    public static String stateName(byte state) {
        return switch (state) {
            case RiverDebugPacket.STALE -> "not surveyed yet";
            case RiverDebugPacket.PENDING -> "waiting for the river network";
            case RiverDebugPacket.LAKE -> "lake: no bends";
            case RiverDebugPacket.MOUTH -> "mouth: no bends";
            case RiverDebugPacket.NO_NETWORK -> "planned; the river network was not read this far";
            case RiverDebugPacket.PLANNED -> "planned";
            default -> "?";
        };
    }

    /** A short name for a river network, from its key. */
    public static String riverName(long river) {
        return river == 0 ? "-" : Long.toString(Math.abs(river) % 46656, 36).toUpperCase(Locale.ROOT);
    }
}
