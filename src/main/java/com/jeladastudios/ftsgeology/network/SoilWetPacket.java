package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * How wet the ground of a chunk is at the top, cell by cell, so a mod drawing puddles on the client (Puddles &amp;
 * Floods) can put them where water would stand: see {@code ClientSoilWet}.
 *
 * @param wet sixteen cells, {@code (lz / 4) * 4 + lx / 4}: bits 0-3 how wet the top of the soil is (0 dry, 15
 *            soaked), bits 4-5 the soil (0 none, 1 sand, 2 loam, 3 clay), bit 6 water standing on it
 */
public record SoilWetPacket(int chunkX, int chunkZ, byte[] wet) {

    public static void encode(SoilWetPacket p, FriendlyByteBuf buf) {
        buf.writeInt(p.chunkX);
        buf.writeInt(p.chunkZ);
        buf.writeByteArray(p.wet);
    }

    public static SoilWetPacket decode(FriendlyByteBuf buf) {
        return new SoilWetPacket(buf.readInt(), buf.readInt(), buf.readByteArray(16));
    }

    public static void handle(SoilWetPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientSoilWet.set(p)));
        ctx.get().setPacketHandled(true);
    }
}
