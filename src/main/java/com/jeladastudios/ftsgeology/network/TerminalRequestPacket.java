package com.jeladastudios.ftsgeology.network;

import com.jeladastudios.ftsgeology.blockentity.WeatherTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** An open terminal screen asking for its station's latest: answered only for a terminal within reach of the player. */
public record TerminalRequestPacket(BlockPos pos) {

    public static void encode(TerminalRequestPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
    }

    public static TerminalRequestPacket decode(FriendlyByteBuf buf) {
        return new TerminalRequestPacket(buf.readBlockPos());
    }

    public static void handle(TerminalRequestPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.distanceToSqr(p.pos.getCenter()) > 64) return;
            if (!player.serverLevel().isLoaded(p.pos)) return;
            if (player.serverLevel().getBlockEntity(p.pos) instanceof WeatherTerminalBlockEntity be) {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new TerminalPacket(p.pos, be.data(player.serverLevel()), false));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
