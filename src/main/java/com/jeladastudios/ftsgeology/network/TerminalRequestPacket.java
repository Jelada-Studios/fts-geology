package com.jeladastudios.ftsgeology.network;

import com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.WeatherTerminalBlockEntity;
import com.jeladastudios.ftsgeology.instrument.StationNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * An open terminal or seismograph screen asking for its station's latest, or ({@code chat}) a seismograph's log written
 * to chat: answered only for one within reach of the player. Or, through a terminal ({@code via}) the player stands
 * at, for any station on the network, wherever it is (see {@link StationNetwork}); {@code open} asks for its screen to
 * be opened, not only brought up to date.
 */
public record TerminalRequestPacket(BlockPos pos, boolean chat, @Nullable BlockPos via, boolean open) {

    public TerminalRequestPacket(BlockPos pos) {
        this(pos, false, null, false);
    }

    public TerminalRequestPacket(BlockPos pos, boolean chat) {
        this(pos, chat, null, false);
    }

    /** A station on the network seen through the terminal at {@code via}. */
    public static TerminalRequestPacket through(BlockPos via, BlockPos station, boolean open) {
        return new TerminalRequestPacket(station, false, via, open);
    }

    public static void encode(TerminalRequestPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeBoolean(p.chat);
        buf.writeBoolean(p.via != null);
        if (p.via != null) buf.writeBlockPos(p.via);
        buf.writeBoolean(p.open);
    }

    public static TerminalRequestPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean chat = buf.readBoolean();
        BlockPos via = buf.readBoolean() ? buf.readBlockPos() : null;
        return new TerminalRequestPacket(pos, chat, via, buf.readBoolean());
    }

    public static void handle(TerminalRequestPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) answer(player, p);
        });
        ctx.get().setPacketHandled(true);
    }

    /** Answers one request from a player. */
    static void answer(ServerPlayer player, TerminalRequestPacket p) {
        ServerLevel level = player.serverLevel();
        if (p.via != null && !p.via.equals(p.pos)) {
            // Afar, through the terminal the player stands at: the station anywhere on the network.
            if (player.distanceToSqr(p.via.getCenter()) > 64 || !level.isLoaded(p.via)
                    || !(level.getBlockEntity(p.via) instanceof WeatherTerminalBlockEntity)) return;
            CompoundTag data = StationNetwork.view(level, p.pos);
            if (data == null) return;
            send(player, new TerminalPacket(p.pos, StationNetwork.decorate(level, player, p.via, p.pos, data), p.open));
            return;
        }
        if (player.distanceToSqr(p.pos.getCenter()) > 64) return;
        if (!level.isLoaded(p.pos)) return;
        var be = level.getBlockEntity(p.pos);
        if (be instanceof WeatherTerminalBlockEntity terminal) {
            send(player, new TerminalPacket(p.pos, StationNetwork.decorate(level, player, p.pos, p.pos, terminal.data(level)), p.open));
        } else if (be instanceof com.jeladastudios.ftsgeology.gas.block.entity.GasMachineBlockEntity machine) {
            send(player, new TerminalPacket(p.pos, machine.panelData(), false));
        } else if (be instanceof SeismographBlockEntity station) {
            if (p.chat) {
                for (Component c : station.report(level.getGameTime())) player.sendSystemMessage(c);
                return;
            }
            send(player, new TerminalPacket(p.pos, StationNetwork.decorate(level, player, null, p.pos, station.data(level)), p.open));
        }
    }

    private static void send(ServerPlayer player, TerminalPacket packet) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
