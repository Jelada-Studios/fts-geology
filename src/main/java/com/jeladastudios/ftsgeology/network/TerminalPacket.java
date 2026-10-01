package com.jeladastudios.ftsgeology.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A weather station terminal's screen contents, or a seismograph's (see {@code WeatherTerminalBlockEntity#data} and
 * {@code SeismographBlockEntity#data}), from the server: sent when it is opened, and again while its screen is up.
 *
 * @param open whether to open the screen, or only to bring an open one up to date
 */
public record TerminalPacket(BlockPos pos, CompoundTag data, boolean open) {

    public static void encode(TerminalPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeNbt(p.data);
        buf.writeBoolean(p.open);
    }

    public static TerminalPacket decode(FriendlyByteBuf buf) {
        return new TerminalPacket(buf.readBlockPos(), buf.readNbt(), buf.readBoolean());
    }

    public static void handle(TerminalPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> {
                            if (p.data.getBoolean("Gas")) com.jeladastudios.ftsgeology.client.GasPanelScreen.receive(p);
                            else if (p.data.getBoolean("Seismo")) com.jeladastudios.ftsgeology.client.SeismographScreen.receive(p);
                            else com.jeladastudios.ftsgeology.client.WeatherTerminalScreen.receive(p);
                        }));
        ctx.get().setPacketHandled(true);
    }
}
