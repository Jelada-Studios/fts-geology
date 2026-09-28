package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Where a pyroclastic flow's front is, so the client can draw its cloud billowing along behind it; as with
 * {@link EruptionPacket}, the state and not the particles.
 *
 * @param id    the flow, so the client can keep its trail
 * @param width how far to either side the flow reaches, in blocks
 * @param done  the flow has stopped: its cloud drifts off and thins out
 */
public record FlowPacket(int id, double x, double y, double z, float width, boolean done) {

    public static void encode(FlowPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.id);
        buf.writeDouble(p.x);
        buf.writeDouble(p.y);
        buf.writeDouble(p.z);
        buf.writeFloat(p.width);
        buf.writeBoolean(p.done);
    }

    public static FlowPacket decode(FriendlyByteBuf buf) {
        return new FlowPacket(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(),
                buf.readBoolean());
    }

    public static void handle(FlowPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientEruptions.flow(p)));
        ctx.get().setPacketHandled(true);
    }
}
