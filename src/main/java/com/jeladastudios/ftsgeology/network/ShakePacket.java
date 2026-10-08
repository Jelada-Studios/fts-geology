package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;


/**
 * "Shake the view this hard, for this long."
 *
 * <h2>An instruction, not a stream of positions</h2>
 * The obvious design sends the camera offset every tick, and it would be both twenty times the
 * traffic and worse to look at: at twenty packets a second the shake is sampled at the tick rate
 * rather than the frame rate, so it steps instead of vibrating. Sending an intensity and a duration
 * instead lets the client run its own smooth jitter at whatever frame rate it has, and lets the
 * server re-send only when the strength changes.
 *
 * @param intensity peak angular displacement in degrees, before the client's own decay
 * @param ticks     how long it takes to decay to nothing
 * @param speed     how quick the swaying is: 1 for the heavy S wave, faster for the P wave's jolt
 */
public record ShakePacket(float intensity, int ticks, float speed) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

    public static final Type<ShakePacket> TYPE = new Type<>(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.jeladastudios.ftsgeology.GeysersMod.MODID, "shake"));
    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, ShakePacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of((buf, p) -> encode(p, buf), ShakePacket::decode);

    @Override
    public Type<ShakePacket> type() {
        return TYPE;
    }


    public static void encode(ShakePacket p, FriendlyByteBuf buf) {
        buf.writeFloat(p.intensity);
        buf.writeVarInt(p.ticks);
        buf.writeFloat(p.speed);
    }

    public static ShakePacket decode(FriendlyByteBuf buf) {
        return new ShakePacket(buf.readFloat(), buf.readVarInt(), buf.readFloat());
    }

    public static void handle(ShakePacket p, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                // Guarded rather than called directly: the class it reaches is client-only, and on a
                // dedicated server loading it at all would be a crash on the first quake.
                com.jeladastudios.ftsgeology.util.Sides.client(() -> com.jeladastudios.ftsgeology.client.ClientShake.add(
                                p.intensity(), p.ticks(), p.speed())));
    }
}
