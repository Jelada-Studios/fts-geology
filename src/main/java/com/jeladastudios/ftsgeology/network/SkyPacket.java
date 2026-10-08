package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;


/**
 * The weather within sight of a player, for the clouds drawn over them (see {@code ClientSky}): the storms near them --
 * where, how wide, how hard it rains under them now, whether they carry thunder, and how they drift -- and the highs and
 * lows round them. Sent every few seconds; the client moves them on between.
 *
 * @param time    the server's game time the positions are for
 * @param storms  per storm: x, z, radius, strength now, drift x, drift z (blocks a tick), thunder (1 or 0), kind
 * @param systems per system: x, z, radius, depth now (hectopascals, below zero a low), drift x, drift z
 */
public record SkyPacket(long time, float[] storms, float[] systems) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

    public static final Type<SkyPacket> TYPE = new Type<>(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.jeladastudios.ftsgeology.GeysersMod.MODID, "sky"));
    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, SkyPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of((buf, p) -> encode(p, buf), SkyPacket::decode);

    @Override
    public Type<SkyPacket> type() {
        return TYPE;
    }


    public static final int STORM = 8, SYSTEM = 6;

    public static void encode(SkyPacket p, FriendlyByteBuf buf) {
        buf.writeLong(p.time);
        buf.writeVarInt(p.storms.length);
        for (float f : p.storms) buf.writeFloat(f);
        buf.writeVarInt(p.systems.length);
        for (float f : p.systems) buf.writeFloat(f);
    }

    public static SkyPacket decode(FriendlyByteBuf buf) {
        long time = buf.readLong();
        int n = Math.min(buf.readVarInt(), STORM * 256);
        float[] storms = new float[n];
        for (int i = 0; i < n; i++) storms[i] = buf.readFloat();
        int m = Math.min(buf.readVarInt(), SYSTEM * 256);
        float[] systems = new float[m];
        for (int i = 0; i < m; i++) systems[i] = buf.readFloat();
        return new SkyPacket(time, storms, systems);
    }

    public static void handle(SkyPacket p, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.jeladastudios.ftsgeology.util.Sides.client(() -> com.jeladastudios.ftsgeology.client.ClientSky.set(p)));
    }
}
