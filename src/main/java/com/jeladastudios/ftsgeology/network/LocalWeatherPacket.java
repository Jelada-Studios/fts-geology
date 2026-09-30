package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * The weather where a player stands (see {@code Storms}): how hard it rains there, 0 to 1, and how much of a
 * thunderstorm it is. The client draws its sky, its rain and its sound from these instead of the world's one flag.
 *
 * @param rain    0 dry, under 0.5 light rain, 0.5 and over heavy
 * @param thunder 0 none, to 1 a thunderstorm overhead
 */
public record LocalWeatherPacket(float rain, float thunder) {

    public static void encode(LocalWeatherPacket p, FriendlyByteBuf buf) {
        buf.writeFloat(p.rain);
        buf.writeFloat(p.thunder);
    }

    public static LocalWeatherPacket decode(FriendlyByteBuf buf) {
        return new LocalWeatherPacket(buf.readFloat(), buf.readFloat());
    }

    public static void handle(LocalWeatherPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientWeather.set(p)));
        ctx.get().setPacketHandled(true);
    }
}
