package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;


/**
 * Where a pyroclastic flow's front is, so the client can draw its cloud billowing along behind it; as with
 * {@link EruptionPacket}, the state and not the particles.
 *
 * @param id    the flow, so the client can keep its trail
 * @param width how far to either side the flow reaches, in blocks
 * @param done  the flow has stopped: its cloud drifts off and thins out
 */
public record FlowPacket(int id, double x, double y, double z, float width, boolean done) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

    public static final Type<FlowPacket> TYPE = new Type<>(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.jeladastudios.ftsgeology.GeysersMod.MODID, "flow"));
    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, FlowPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of((buf, p) -> encode(p, buf), FlowPacket::decode);

    @Override
    public Type<FlowPacket> type() {
        return TYPE;
    }


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

    public static void handle(FlowPacket p, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.jeladastudios.ftsgeology.util.Sides.client(() -> com.jeladastudios.ftsgeology.client.ClientEruptions.flow(p)));
    }
}
