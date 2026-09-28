package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * How dry the grass of a chunk is, cell by cell, so the client can draw it paler where the ground has dried out.
 *
 * @param dry sixteen cells of four by four columns, {@code (lz / 4) * 4 + lx / 4}, from 0 green to 15 straw
 */
public record SoilTintPacket(int chunkX, int chunkZ, byte[] dry) {

    public static void encode(SoilTintPacket p, FriendlyByteBuf buf) {
        buf.writeInt(p.chunkX);
        buf.writeInt(p.chunkZ);
        buf.writeByteArray(p.dry);
    }

    public static SoilTintPacket decode(FriendlyByteBuf buf) {
        return new SoilTintPacket(buf.readInt(), buf.readInt(), buf.readByteArray(16));
    }

    public static void handle(SoilTintPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientSoilTint.set(p)));
        ctx.get().setPacketHandled(true);
    }
}
