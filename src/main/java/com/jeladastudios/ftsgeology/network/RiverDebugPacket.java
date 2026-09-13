package com.jeladastudios.ftsgeology.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the river survey knows around one player, for the debug view: each river chunk's state, water level, river,
 * flow, speed and centre line, and every bend on it with how far it has got and why it stopped.
 *
 * @param chunks the river chunks within range; chunks without a river are not sent
 */
public record RiverDebugPacket(List<ChunkInfo> chunks) {

    /** States a river chunk can be in. */
    public static final byte STALE = 1, PENDING = 2, LAKE = 3, MOUTH = 4, NO_NETWORK = 5, PLANNED = 6;

    /** One bend: where, which way it shifts, how wide the channel is, progress, and the last reason it stood still. */
    public record BendInfo(int x, int z, float nx, float nz, int width, int steps, int done, float speed,
                           int nextTicks, boolean dead, String why) {}

    /**
     * One chunk. {@code centre} holds (lx, lz) pairs of the channel's centre line; {@code river} is the network the
     * chunk's water belongs to, 0 when unknown; {@code fx, fz} the flow direction, 0 when unknown.
     */
    public record ChunkInfo(int cx, int cz, int yW, byte state, long river, float fx, float fz, float speed,
                            byte[] centre, List<BendInfo> bends) {}

    public static void encode(RiverDebugPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.chunks.size());
        for (ChunkInfo c : p.chunks) {
            buf.writeInt(c.cx());
            buf.writeInt(c.cz());
            buf.writeInt(c.yW());
            buf.writeByte(c.state());
            buf.writeLong(c.river());
            buf.writeFloat(c.fx());
            buf.writeFloat(c.fz());
            buf.writeFloat(c.speed());
            buf.writeByteArray(c.centre());
            buf.writeVarInt(c.bends().size());
            for (BendInfo b : c.bends()) {
                buf.writeInt(b.x());
                buf.writeInt(b.z());
                buf.writeFloat(b.nx());
                buf.writeFloat(b.nz());
                buf.writeVarInt(b.width());
                buf.writeVarInt(b.steps());
                buf.writeVarInt(b.done());
                buf.writeFloat(b.speed());
                buf.writeVarInt(b.nextTicks());
                buf.writeBoolean(b.dead());
                buf.writeUtf(b.why(), 64);
            }
        }
    }

    public static RiverDebugPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<ChunkInfo> chunks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int cx = buf.readInt(), cz = buf.readInt(), yW = buf.readInt();
            byte state = buf.readByte();
            long river = buf.readLong();
            float fx = buf.readFloat(), fz = buf.readFloat(), speed = buf.readFloat();
            byte[] centre = buf.readByteArray();
            int m = buf.readVarInt();
            List<BendInfo> bends = new ArrayList<>(m);
            for (int j = 0; j < m; j++) {
                bends.add(new BendInfo(buf.readInt(), buf.readInt(), buf.readFloat(), buf.readFloat(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readVarInt(),
                        buf.readBoolean(), buf.readUtf(64)));
            }
            chunks.add(new ChunkInfo(cx, cz, yW, state, river, fx, fz, speed, centre, bends));
        }
        return new RiverDebugPacket(chunks);
    }

    public static void handle(RiverDebugPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                // Guarded: the class it reaches is client-only, and a dedicated server must never load it.
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.jeladastudios.ftsgeology.client.ClientRiverDebug.accept(p)));
        ctx.get().setPacketHandled(true);
    }
}
