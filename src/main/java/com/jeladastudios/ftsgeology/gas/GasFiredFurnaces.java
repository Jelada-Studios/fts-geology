package com.jeladastudios.ftsgeology.gas;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.GasFiredPacket;
import com.jeladastudios.ftsgeology.network.ModNetwork;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * Which furnaces are burning gas now ({@link GasFurnaces}), told to the players who can see them, so their clients draw
 * no coal smoke out of them: hydrogen burns to water and methane clean, and a blast furnace on hydrogen smoked as if
 * on coal. Told when one starts, when it has gone a couple of seconds without gas, and to a player as the chunk it is
 * in comes into their view.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class GasFiredFurnaces {

    private GasFiredFurnaces() {}

    /** Ticks without gas after which a furnace is told as burning gas no more. */
    private static final long LAPSE = 40;

    /** Per level: the furnaces burning gas, by position, with the tick they last had it. Server thread. */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> FIRED = new HashMap<>();

    /** A furnace that has its heat from gas this tick. */
    static void mark(ServerLevel level, BlockPos pos) {
        Long2LongOpenHashMap fired = FIRED.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
        long key = pos.asLong();
        boolean known = fired.containsKey(key);
        fired.put(key, level.getGameTime());
        if (!known) tell(level, key, true);
    }

    /** How many times a furnace was told as starting and as stopping on gas, for {@link #summary}. */
    private static long started, stopped;

    /** The furnaces burning gas now and how often they started and stopped, for a test. */
    public static String summary() {
        int now = 0;
        for (Long2LongOpenHashMap m : FIRED.values()) now += m.size();
        return "gas-fired furnaces: " + now + " now, " + started + " started, " + stopped + " stopped";
    }

    private static void tell(ServerLevel level, long key, boolean on) {
        if (on) started++;
        else stopped++;
        BlockPos pos = BlockPos.of(key);
        if (!level.hasChunkAt(pos)) return;
        ModNetwork.CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(pos)), new GasFiredPacket(key, on));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || FIRED.isEmpty() || event.getServer().getTickCount() % 20 != 0) return;
        for (Map.Entry<ResourceKey<Level>, Long2LongOpenHashMap> e : FIRED.entrySet()) {
            ServerLevel level = event.getServer().getLevel(e.getKey());
            if (level == null) continue;
            long now = level.getGameTime();
            var it = e.getValue().long2LongEntrySet().iterator();
            while (it.hasNext()) {
                Long2LongMap.Entry f = it.next();
                if (now - f.getLongValue() <= LAPSE) continue;
                long key = f.getLongKey();   // read before the entry is removed: after, it is no one's
                it.remove();
                tell(level, key, false);
            }
        }
    }

    @SubscribeEvent
    public static void onWatch(ChunkWatchEvent.Watch event) {
        Long2LongOpenHashMap fired = FIRED.get(event.getLevel().dimension());
        if (fired == null || fired.isEmpty()) return;
        ChunkPos cp = event.getPos();
        for (long key : fired.keySet()) {
            BlockPos pos = BlockPos.of(key);
            if ((pos.getX() >> 4) != cp.x || (pos.getZ() >> 4) != cp.z) continue;
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), new GasFiredPacket(key, true));
        }
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) FIRED.remove(level.dimension());
    }
}
