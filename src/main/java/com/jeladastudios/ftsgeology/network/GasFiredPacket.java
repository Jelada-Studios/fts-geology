package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A furnace, smoker or blast furnace that has started or stopped burning gas (see {@code gas.GasFiredFurnaces}), so the
 * client draws no coal smoke out of it while it does.
 *
 * @param pos the furnace, as {@code BlockPos.asLong}
 * @param on  whether it burns gas now
 */
public record GasFiredPacket(long pos, boolean on) {

    public static void encode(GasFiredPacket p, FriendlyByteBuf buf) {
        buf.writeLong(p.pos);
        buf.writeBoolean(p.on);
    }

    public static GasFiredPacket decode(FriendlyByteBuf buf) {
        return new GasFiredPacket(buf.readLong(), buf.readBoolean());
    }

    public static void handle(GasFiredPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientGasFired.set(p.pos, p.on)));
        ctx.get().setPacketHandled(true);
    }
}
