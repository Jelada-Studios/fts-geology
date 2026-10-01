package com.jeladastudios.ftsgeology.network;

import com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.WeatherTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * An open terminal or seismograph screen asking for its station's latest, or ({@code chat}) a seismograph's log written
 * to chat: answered only for one within reach of the player.
 */
public record TerminalRequestPacket(BlockPos pos, boolean chat) {

    public TerminalRequestPacket(BlockPos pos) {
        this(pos, false);
    }

    public static void encode(TerminalRequestPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeBoolean(p.chat);
    }

    public static TerminalRequestPacket decode(FriendlyByteBuf buf) {
        return new TerminalRequestPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(TerminalRequestPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.distanceToSqr(p.pos.getCenter()) > 64) return;
            if (!player.serverLevel().isLoaded(p.pos)) return;
            var be = player.serverLevel().getBlockEntity(p.pos);
            if (be instanceof WeatherTerminalBlockEntity terminal) {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new TerminalPacket(p.pos, terminal.data(player.serverLevel()), false));
            } else if (be instanceof SeismographBlockEntity station) {
                if (p.chat) {
                    for (Component c : station.report(player.serverLevel().getGameTime())) player.sendSystemMessage(c);
                    return;
                }
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new TerminalPacket(p.pos, station.data(player.serverLevel()), false));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
