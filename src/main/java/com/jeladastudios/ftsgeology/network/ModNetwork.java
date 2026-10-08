package com.jeladastudios.ftsgeology.network;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The mod's one channel to the client.
 *
 * <h2>Why there was not one until now</h2>
 * Everything the mod does happens to the world, and the world replicates itself: a quake moves
 * blocks and the client is told about the blocks. That covered every feature for thirty rounds, and
 * it is why there is no networking here and almost no client code either.
 *
 * <p>Camera shake is the first thing that is not a change to the world. The ground moving is a fact
 * about the world; <i>being shaken</i> is a fact about the viewer, and there is nothing for vanilla
 * to replicate. Hence a channel - one packet, one handler.</p>
 *
 * <h2>Why not simply push the player about, as before</h2>
 * {@code Earthquake.shake} already nudges each player's motion vector, and testing described the
 * result exactly right: it feels like standing on soap, not like an earthquake. The reason is that
 * shoving a player moves where they ARE while an earthquake moves what they SEE. Rotating the
 * camera from the client is the honest version, and it also leaves the player's aim alone - a shake
 * that turned your head would fight the mouse rather than the ground.
 */
public final class ModNetwork {

    private ModNetwork() {}

    private static final String VERSION = "11";

    /** Registers every packet, on the mod bus. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(VERSION);
        r.playToClient(ShakePacket.TYPE, ShakePacket.STREAM_CODEC, ShakePacket::handle);
        r.playToClient(EruptionPacket.TYPE, EruptionPacket.STREAM_CODEC, EruptionPacket::handle);
        r.playToClient(FlowPacket.TYPE, FlowPacket.STREAM_CODEC, FlowPacket::handle);
        r.playToClient(SoilTintPacket.TYPE, SoilTintPacket.STREAM_CODEC, SoilTintPacket::handle);
        r.playToClient(SoilWetPacket.TYPE, SoilWetPacket.STREAM_CODEC, SoilWetPacket::handle);
        r.playToClient(LocalWeatherPacket.TYPE, LocalWeatherPacket.STREAM_CODEC, LocalWeatherPacket::handle);
        r.playToClient(SkyPacket.TYPE, SkyPacket.STREAM_CODEC, SkyPacket::handle);
        r.playToClient(TerminalPacket.TYPE, TerminalPacket.STREAM_CODEC, TerminalPacket::handle);
        r.playToServer(TerminalRequestPacket.TYPE, TerminalRequestPacket.STREAM_CODEC, TerminalRequestPacket::handle);
        r.playToServer(StationRenamePacket.TYPE, StationRenamePacket.STREAM_CODEC, StationRenamePacket::handle);
        r.playToClient(GasFiredPacket.TYPE, GasFiredPacket.STREAM_CODEC, GasFiredPacket::handle);
        r.playToServer(GasControlPacket.TYPE, GasControlPacket.STREAM_CODEC, GasControlPacket::handle);
    }

    /** How far away an eruption is still worth drawing: a column hundreds of blocks tall. */
    private static final double ERUPTION_RANGE = 512.0;

    /** Tells everyone near enough to see it what a volcano is doing. */
    public static void sendEruption(ServerLevel level, EruptionPacket packet) {
        double r2 = ERUPTION_RANGE * ERUPTION_RANGE;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - packet.summit().getX(), dz = p.getZ() - packet.summit().getZ();
            if (dx * dx + dz * dz > r2) continue;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /** Tells everyone near enough to see it where a pyroclastic flow's front is. */
    public static void sendFlow(ServerLevel level, FlowPacket packet) {
        double r2 = ERUPTION_RANGE * ERUPTION_RANGE;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - packet.x(), dz = p.getZ() - packet.z();
            if (dx * dx + dz * dz > r2) continue;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /** Tells one player their view is being shaken this hard for this long. */
    public static void sendShake(ServerPlayer player, float intensity, int ticks) {
        sendShake(player, intensity, ticks, 1.0f);
    }

    /** The same, swaying at {@code speed} times the usual pace: the P wave's quick jolt. */
    public static void sendShake(ServerPlayer player, float intensity, int ticks, float speed) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new ShakePacket(intensity, ticks, speed));
    }

    /** Convenience for a source that shakes everyone near it - an eruption, say. */
    public static void shakeNear(ServerLevel level, double x, double z, double radius,
                                 float intensity, int ticks) {
        double r2 = radius * radius;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 > r2) continue;
            float falloff = (float) (1.0 - Math.sqrt(d2) / radius);
            sendShake(p, intensity * falloff, ticks);
        }
    }
}
