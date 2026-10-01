package com.jeladastudios.ftsgeology.network;

import com.jeladastudios.ftsgeology.instrument.StationNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A station renamed from its own screen: by whoever set it up, an operator, or anyone where nobody did (see
 * {@link StationNetwork#rename}). The screen is brought up to date after.
 */
public record StationRenamePacket(BlockPos pos, String name) {

    public static void encode(StationRenamePacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeUtf(p.name, StationNetwork.NAME);
    }

    public static StationRenamePacket decode(FriendlyByteBuf buf) {
        return new StationRenamePacket(buf.readBlockPos(), buf.readUtf(StationNetwork.NAME));
    }

    public static void handle(StationRenamePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.distanceToSqr(p.pos.getCenter()) > 64 || !player.serverLevel().isLoaded(p.pos)) return;
            if (StationNetwork.rename(player.serverLevel(), player, p.pos, p.name)) {
                TerminalRequestPacket.answer(player, new TerminalRequestPacket(p.pos));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
