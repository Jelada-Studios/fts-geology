package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

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
 */
public record ShakePacket(float intensity, int ticks) {

    public static void encode(ShakePacket p, FriendlyByteBuf buf) {
        buf.writeFloat(p.intensity);
        buf.writeVarInt(p.ticks);
    }

    public static ShakePacket decode(FriendlyByteBuf buf) {
        return new ShakePacket(buf.readFloat(), buf.readVarInt());
    }

    public static void handle(ShakePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                // Guarded rather than called directly: the class it reaches is client-only, and on a
                // dedicated server loading it at all would be a crash on the first quake.
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientShake.add(
                                p.intensity(), p.ticks())));
        ctx.get().setPacketHandled(true);
    }
}
