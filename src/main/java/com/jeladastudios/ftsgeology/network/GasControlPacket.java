package com.jeladastudios.ftsgeology.network;

import com.jeladastudios.ftsgeology.gas.block.entity.GasMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A button pressed on a gas machine's panel: the valve's opening and throttle, the separator's gas, the sensor's mode
 * (see {@link GasMachineBlockEntity#control}). The panel is brought up to date after.
 */
public record GasControlPacket(BlockPos pos, String key) {

    public static void encode(GasControlPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeUtf(p.key, 32);
    }

    public static GasControlPacket decode(FriendlyByteBuf buf) {
        return new GasControlPacket(buf.readBlockPos(), buf.readUtf(32));
    }

    public static void handle(GasControlPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player.distanceToSqr(p.pos.getCenter()) > 64 || !player.serverLevel().isLoaded(p.pos)) return;
            if (player.serverLevel().getBlockEntity(p.pos) instanceof GasMachineBlockEntity machine && machine.control(p.key, player)) {
                TerminalRequestPacket.answer(player, new TerminalRequestPacket(p.pos));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
