package com.jeladastudios.ftsgeology.network;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

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

    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GeysersMod.MODID, "main"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals);

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(ShakePacket.class, id++)
                .encoder(ShakePacket::encode)
                .decoder(ShakePacket::decode)
                .consumerMainThread(ShakePacket::handle)
                .add();
    }

    /** Tells one player their view is being shaken this hard for this long. */
    public static void sendShake(ServerPlayer player, float intensity, int ticks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ShakePacket(intensity, ticks));
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
