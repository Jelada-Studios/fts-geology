package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;


/**
 * How the grass of a chunk looks, cell by cell, so the client can draw it paler where the ground has dried out and
 * deeper where it is wet.
 *
 * @param dry sixteen cells of four by four columns, {@code (lz / 4) * 4 + lx / 4}, from -15 lushest through 0 to 15 straw
 */
public record SoilTintPacket(int chunkX, int chunkZ, byte[] dry) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

    public static final Type<SoilTintPacket> TYPE = new Type<>(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.jeladastudios.ftsgeology.GeysersMod.MODID, "soil_tint"));
    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, SoilTintPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of((buf, p) -> encode(p, buf), SoilTintPacket::decode);

    @Override
    public Type<SoilTintPacket> type() {
        return TYPE;
    }


    public static void encode(SoilTintPacket p, FriendlyByteBuf buf) {
        buf.writeInt(p.chunkX);
        buf.writeInt(p.chunkZ);
        buf.writeByteArray(p.dry);
    }

    public static SoilTintPacket decode(FriendlyByteBuf buf) {
        return new SoilTintPacket(buf.readInt(), buf.readInt(), buf.readByteArray(16));
    }

    public static void handle(SoilTintPacket p, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.jeladastudios.ftsgeology.util.Sides.client(() -> com.jeladastudios.ftsgeology.client.ClientSoilTint.set(p)));
    }
}
