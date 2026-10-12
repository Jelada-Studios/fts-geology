package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.worldgen.OreBodyBlocks;
import com.jeladastudios.ftsgeology.worldgen.OreOutcrops;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What a struck piece of a large ore body tells a prospector about the body: from the rock of the body itself, where its
 * ore lies; from a piece carried down a stream or gold in its gravel, how far up the stream the body is. Worked out off
 * the server thread, since following a stream up may need the river network of ground not yet visited, and sent when
 * ready.
 */
public final class OreBodyReading {

    private OreBodyReading() {}

    /** Reads the body a struck block belongs to, and sends the line to the player when it is worked out. */
    public static void send(ServerLevel level, ServerPlayer player, BlockPos at, BlockState struck) {
        read(level, at, struck).thenAcceptAsync(line -> {
                    if (line != null && !player.hasDisconnected()) player.sendSystemMessage(line);
                }, level.getServer())
                .exceptionally(t -> null);
    }

    /** The line a struck block gives, worked out off the server thread; null in it if the block tells nothing. */
    public static CompletableFuture<Component> read(ServerLevel level, BlockPos at, BlockState struck) {
        OreBodyBlocks.Kind k = OreBodyBlocks.kind(struck);
        if (k == OreBodyBlocks.Kind.NONE || !OreOutcrops.active(level)) return CompletableFuture.completedFuture(null);
        OreOutcrops.Kind want = k == OreBodyBlocks.Kind.PORPHYRY ? OreOutcrops.Kind.PORPHYRY : OreOutcrops.Kind.REEF;
        boolean carried = OreBodyBlocks.carried(struck);
        return CompletableFuture.supplyAsync(() -> carried ? upstream(level, at, want) : inBody(level, at, want), Util.backgroundExecutor());
    }

    private static Component kindName(OreOutcrops.Kind kind) {
        return Component.translatable("prospect.fts_geology.body.kind." + (kind == OreOutcrops.Kind.PORPHYRY ? "porphyry" : "reef"));
    }

    /** Struck in the body itself: where its ore lies from here. */
    static Component inBody(ServerLevel level, BlockPos at, OreOutcrops.Kind want) {
        OreOutcrops.Body best = null;
        for (OreOutcrops.Body b : OreOutcrops.near(level, at.getX(), at.getZ(), 260)) {
            if (b.kind() == want && Math.hypot(b.x() - at.getX(), b.z() - at.getZ()) <= b.reach() + 8) {
                best = b;
                break;
            }
        }
        if (best == null) return null;
        if (best.kind() == OreOutcrops.Kind.PORPHYRY) {
            int dx = best.x() - at.getX(), dz = best.z() - at.getZ(), dy = best.cy() - at.getY();
            int across = (int) Math.round(Math.hypot(dx, dz));
            Component where = across <= 3 ? Component.translatable("prospect.fts_geology.straight")
                    : Component.translatable("prospect.fts_geology.across", across,
                            Component.translatable("prospect.fts_geology.dir." + Prospecting.bearing(dx, dz)));
            Component depth = dy < 0
                    ? Component.translatable("prospect.fts_geology.below", DepthScale.format(-dy * DepthScale.metresPerBlock()), -dy)
                    : Component.translatable("prospect.fts_geology.level");
            return Component.translatable("prospect.fts_geology.body.porphyry", (int) Math.round(best.radius() * 2), where, depth)
                    .withStyle(ChatFormatting.YELLOW);
        }
        String a = Prospecting.bearing((int) Math.round(best.vx() * 100), (int) Math.round(best.vz() * 100));
        String b = Prospecting.bearing((int) Math.round(-best.vx() * 100), (int) Math.round(-best.vz() * 100));
        return Component.translatable("prospect.fts_geology.body.reef",
                Component.translatable("prospect.fts_geology.dir." + a), Component.translatable("prospect.fts_geology.dir." + b),
                best.half() * 2).withStyle(ChatFormatting.YELLOW);
    }

    /** Carried down a stream: which body it came from, how far up the stream and where it lies. */
    static Component upstream(ServerLevel level, BlockPos at, OreOutcrops.Kind want) {
        OreOutcrops.Body best = null;
        double bestAlong = Double.MAX_VALUE;
        List<OreOutcrops.Body> near = OreOutcrops.near(level, at.getX(), at.getZ(), OreOutcrops.FLOAT_REACH + 320);
        for (OreOutcrops.Body b : near) {
            if (b.kind() != want) continue;
            List<OreOutcrops.Seg> path = OreOutcrops.path(b);
            if (path.isEmpty()) continue;
            double[] ap = OreOutcrops.alongPath(path, at.getX() + 0.5, at.getZ() + 0.5);
            if (ap[0] > 16 || ap[1] >= bestAlong) continue;
            best = b;
            bestAlong = ap[1];
        }
        if (best == null) {
            // Not by a stream: a piece fallen down the slope from a body above it.
            OreOutcrops.Body above = null;
            double bestOut = OreOutcrops.SLOPE + 8;
            for (OreOutcrops.Body b : near) {
                if (b.kind() != want) continue;
                double out = OreOutcrops.edgeDistance(b, at.getX(), at.getZ());
                if (out < bestOut) {
                    bestOut = out;
                    above = b;
                }
            }
            if (above == null) {
                return Component.translatable("prospect.fts_geology.body.lost", kindName(want)).withStyle(ChatFormatting.GRAY);
            }
            int dx = above.x() - at.getX(), dz = above.z() - at.getZ();
            return Component.translatable("prospect.fts_geology.body.slope", kindName(want), (int) Math.round(Math.hypot(dx, dz)),
                    Component.translatable("prospect.fts_geology.dir." + Prospecting.bearing(dx, dz))).withStyle(ChatFormatting.YELLOW);
        }
        int dx = best.x() - at.getX(), dz = best.z() - at.getZ();
        int crow = (int) Math.round(Math.hypot(dx, dz));
        int up = (int) Math.round(bestAlong / 10.0) * 10;
        return Component.translatable("prospect.fts_geology.body.upstream", kindName(want), Math.max(10, up), crow,
                Component.translatable("prospect.fts_geology.dir." + Prospecting.bearing(dx, dz))).withStyle(ChatFormatting.YELLOW);
    }
}
