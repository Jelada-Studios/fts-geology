package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;


/**
 * A furnace, smoker or blast furnace that has started or stopped burning gas (see {@code gas.GasFiredFurnaces}), so the
 * client draws no coal smoke out of it while it does.
 *
 * @param pos the furnace, as {@code BlockPos.asLong}
 * @param on  whether it burns gas now
 */
public record GasFiredPacket(long pos, boolean on) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

    public static final Type<GasFiredPacket> TYPE = new Type<>(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.jeladastudios.ftsgeology.GeysersMod.MODID, "gas_fired"));
    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, GasFiredPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of((buf, p) -> encode(p, buf), GasFiredPacket::decode);

    @Override
    public Type<GasFiredPacket> type() {
        return TYPE;
    }


    public static void encode(GasFiredPacket p, FriendlyByteBuf buf) {
        buf.writeLong(p.pos);
        buf.writeBoolean(p.on);
    }

    public static GasFiredPacket decode(FriendlyByteBuf buf) {
        return new GasFiredPacket(buf.readLong(), buf.readBoolean());
    }

    public static void handle(GasFiredPacket p, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.jeladastudios.ftsgeology.util.Sides.client(() -> com.jeladastudios.ftsgeology.client.ClientGasFired.set(p.pos, p.on)));
    }
}
