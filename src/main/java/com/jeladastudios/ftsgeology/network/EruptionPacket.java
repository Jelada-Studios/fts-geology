package com.jeladastudios.ftsgeology.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "This volcano is doing this": the state its smoke is drawn from, rather than the smoke itself.
 *
 * <h2>Why the server stopped sending smoke</h2>
 * The eruption column used to be particles sent from the server, per player, every other tick: six
 * segments of black smoke and three of pale cloud, each a packet, around ninety a second for anyone
 * within reach - before the throat, the fumaroles and the falling ash. It also meant vanilla's campfire
 * smoke, because a server can only name particles, not decide how they move.
 *
 * <p>Now the core sends what it is doing every two seconds and the client draws the rest at its own
 * frame rate with the mod's own particles. If the heartbeat stops - the player walked away, the
 * volcano went quiet, the chunk unloaded - the smoke simply stops being made.</p>
 *
 * @param phase     0 over, 1 rumbling, 2 erupting
 * @param windX     the mountain's prevailing wind, as a unit vector
 * @param ashfall   whether ash is falling downwind, so the air can agree with the ground
 * @param fumaroles packed positions of the chimney bases on the flanks
 */
public record EruptionPacket(BlockPos summit, byte phase, float magnitude, float windX, float windZ,
                             boolean ashfall, long[] fumaroles) {

    /** Never more chimneys than this in one packet; a cone carries about ten. */
    private static final int MAX_FUMAROLES = 32;

    public static void encode(EruptionPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.summit);
        buf.writeByte(p.phase);
        buf.writeFloat(p.magnitude);
        buf.writeFloat(p.windX);
        buf.writeFloat(p.windZ);
        buf.writeBoolean(p.ashfall);
        long[] f = p.fumaroles.length > MAX_FUMAROLES ? java.util.Arrays.copyOf(p.fumaroles, MAX_FUMAROLES) : p.fumaroles;
        buf.writeLongArray(f);
    }

    public static EruptionPacket decode(FriendlyByteBuf buf) {
        return new EruptionPacket(buf.readBlockPos(), buf.readByte(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readBoolean(), buf.readLongArray());
    }

    public static void handle(EruptionPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                // Guarded for the same reason as ShakePacket: the class it reaches is client-only.
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientEruptions.update(p)));
        ctx.get().setPacketHandled(true);
    }
}
