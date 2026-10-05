package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.api.DroughtEvent;
import com.jeladastudios.ftsgeology.api.FloodEvent;
import com.jeladastudios.ftsgeology.api.Hydraulics;
import com.jeladastudios.ftsgeology.api.RiverBlocksChangedEvent;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod's side of a {@link Hydraulics}: who runs the rivers' water in a level, what the mod's own river rules leave
 * alone while one does, and the news it gives of its own doings -- water it still moves with the ground, the floods and
 * droughts it reckons.
 *
 * <p>With one registered in a level the rivers' upkeep, their spilling out through a bank, their floods and seasonal
 * high water, the reservoirs' filling, the streams drying over a well's cone, the lakes falling in a drought and a
 * fissure's graben filling under water all stand down there; a dam still breaks where it cannot hold its water (the
 * hydraulics' water), and no rain pond is laid in a chunk it owns.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class HydraulicsHooks {

    private HydraulicsHooks() {}

    private static final Map<ResourceKey<Level>, Hydraulics> BY_LEVEL = new ConcurrentHashMap<>();

    public static void register(ServerLevel level, Hydraulics h) {
        Hydraulics was = BY_LEVEL.put(level.dimension(), h);
        if (was != h) {
            GeysersMod.LOGGER.info("FT's Geology: {} runs the rivers' water in {}; the mod's own river rules stand down there",
                    h.getClass().getName(), level.dimension().location());
        }
    }

    public static void unregister(ServerLevel level) {
        if (BY_LEVEL.remove(level.dimension()) != null) {
            GeysersMod.LOGGER.info("FT's Geology: the rivers' water in {} is the mod's own again", level.dimension().location());
        }
    }

    /** The hydraulics registered in a level, or null. */
    public static Hydraulics of(Level level) {
        return level == null || BY_LEVEL.isEmpty() ? null : BY_LEVEL.get(level.dimension());
    }

    public static boolean active(Level level) {
        return of(level) != null;
    }

    /** Whether the registered hydraulics runs a chunk's water; false with none registered, or where it throws. */
    public static boolean owns(Level level, int cx, int cz) {
        Hydraulics h = of(level);
        if (!(h != null && level instanceof ServerLevel sl)) return false;
        try {
            return h.owns(sl, cx, cz);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // === The client's own hooks =============================================

    private static volatile com.jeladastudios.ftsgeology.api.ClientFlow clientFlow;
    private static volatile com.jeladastudios.ftsgeology.api.SurfaceHeight clientSurface;

    public static void clientFlow(com.jeladastudios.ftsgeology.api.ClientFlow f) {
        clientFlow = f;
    }

    /**
     * The lowest a hook may draw a river block's water: vanilla's renderer takes a thousandth off the top of a face it
     * draws and builds a shape that high, and a shape under nothing throws, taking the game down.
     */
    private static final float LOWEST_SURFACE = 0.01f;

    public static void clientSurface(com.jeladastudios.ftsgeology.api.SurfaceHeight s) {
        clientSurface = s;
    }

    /**
     * The flow a registered hydraulics gives a river block, blocks a second, or null to keep the block's own: on the
     * server thread of a level it runs, or on a client with its hook.
     */
    public static net.minecraft.world.phys.Vec3 flow(net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos,
                                                     net.minecraft.world.level.material.FluidState state) {
        try {
            if (level instanceof ServerLevel sl) {
                Hydraulics h = of(sl);
                if (h == null || !sl.getServer().isSameThread()) return null;
                return h.flow(sl, pos);
            }
            com.jeladastudios.ftsgeology.api.ClientFlow f = clientFlow;
            if (f == null) return null;
            if (level instanceof Level l && !l.isClientSide()) return null;
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (!(level instanceof Level) && server != null && server.isSameThread()) return null;
            return f.flow(level, pos, state);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The height a client's hook draws a river block's water at, or NaN for the mod's own. */
    public static float surface(net.minecraft.world.level.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                                net.minecraft.world.level.material.FluidState state) {
        com.jeladastudios.ftsgeology.api.SurfaceHeight s = clientSurface;
        if (s == null || !(state.getType() instanceof RiverWaterFluid)) return Float.NaN;
        try {
            float h = s.height(level, pos, state);
            return Float.isFinite(h) ? Mth.clamp(h, LOWEST_SURFACE, 1f) : Float.NaN;
        } catch (RuntimeException e) {
            return Float.NaN;
        }
    }

    // === Water the ground still moves =======================================

    private static final Map<RiverBlocksChangedEvent.Cause, LongOpenHashSet> MOVED = new EnumMap<>(RiverBlocksChangedEvent.Cause.class);
    private static final Map<RiverBlocksChangedEvent.Cause, Long> LAST = new EnumMap<>(RiverBlocksChangedEvent.Cause.class);
    /** Ticks without a write of a cause before its chunks are told. */
    private static final int QUIET = 20;

    private static boolean river(BlockState s) {
        return s != null && s.getFluidState().getType() instanceof RiverWaterFluid;
    }

    /** A block the mod wrote with the ground's movement: noted if river water came or went in a registered level. */
    public static void moved(ServerLevel level, int x, int z, BlockState was, BlockState now, RiverBlocksChangedEvent.Cause cause) {
        if (!active(level) || !river(was) && !river(now)) return;
        moved(level, x >> 4, z >> 4, cause);
    }

    /** A chunk whose river water the mod moved, in a registered level. */
    public static void moved(ServerLevel level, int cx, int cz, RiverBlocksChangedEvent.Cause cause) {
        if (!active(level) || !level.getServer().isSameThread()) return;
        MOVED.computeIfAbsent(cause, c -> new LongOpenHashSet()).add(ChunkPos.asLong(cx, cz));
        LAST.put(cause, level.getGameTime());
    }

    // === Floods and droughts, as news =======================================

    /** Flooded chunks and the game time each flood holds to. */
    private static final Long2LongOpenHashMap FLOODED = new Long2LongOpenHashMap();
    private static final Long2IntOpenHashMap RISE = new Long2IntOpenHashMap();
    private static final int FLOOD_HOLD = 1200;
    /** Regions in a drought now. */
    private static final LongOpenHashSet DRY = new LongOpenHashSet();
    private static final int REGION = 4096;
    private static final double DROUGHT_IN = 0.2, DROUGHT_OUT = 0.3;

    /** The mod reckons a river over its banks in a chunk now ({@code rise} blocks), or that it stays so. */
    public static void flood(ServerLevel level, int cx, int cz, int rise) {
        long k = ChunkPos.asLong(cx, cz);
        boolean fresh = !FLOODED.containsKey(k);
        FLOODED.put(k, level.getGameTime() + FLOOD_HOLD);
        if (fresh) {
            RISE.put(k, Math.max(1, rise));
            MinecraftForge.EVENT_BUS.post(new FloodEvent(level, new ChunkPos(cx, cz), true, Math.max(1, rise)));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        long now = level.getGameTime();
        // The ground's writes of a cause, told once they have stopped for a second.
        for (RiverBlocksChangedEvent.Cause c : RiverBlocksChangedEvent.Cause.values()) {
            LongOpenHashSet set = MOVED.get(c);
            if (set == null || set.isEmpty() || now - LAST.getOrDefault(c, now) < QUIET) continue;
            List<ChunkPos> chunks = new ArrayList<>(set.size());
            for (long k : set) chunks.add(new ChunkPos(k));
            set.clear();
            MinecraftForge.EVENT_BUS.post(new RiverBlocksChangedEvent(level, chunks, c));
        }
        if (!FLOODED.isEmpty() && now % 20 == 0) {
            LongArrayList over = new LongArrayList();
            for (var e : FLOODED.long2LongEntrySet()) if (now > e.getLongValue()) over.add(e.getLongKey());
            for (long k : over) {
                FLOODED.remove(k);
                RISE.remove(k);
                MinecraftForge.EVENT_BUS.post(new FloodEvent(level, new ChunkPos(k), false, 0));
            }
        }
        if (now % 200 == 0 && !level.players().isEmpty()) droughts(level);
    }

    private static void droughts(ServerLevel level) {
        LongOpenHashSet seen = new LongOpenHashSet();
        for (ServerPlayer p : level.players()) {
            int rx = Mth.floor(p.getX() / REGION), rz = Mth.floor(p.getZ() / REGION);
            long k = ChunkPos.asLong(rx, rz);
            if (!seen.add(k)) continue;
            double wet = com.jeladastudios.ftsgeology.weather.Storms.spell(level, (rx + 0.5) * REGION, (rz + 0.5) * REGION);
            boolean dry = DRY.contains(k);
            if (!dry && wet < DROUGHT_IN) {
                DRY.add(k);
                MinecraftForge.EVENT_BUS.post(new DroughtEvent(level, rx * REGION, rz * REGION, rx * REGION + REGION - 1, rz * REGION + REGION - 1, true));
            } else if (dry && wet > DROUGHT_OUT) {
                DRY.remove(k);
                MinecraftForge.EVENT_BUS.post(new DroughtEvent(level, rx * REGION, rz * REGION, rx * REGION + REGION - 1, rz * REGION + REGION - 1, false));
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BY_LEVEL.clear();
        MOVED.clear();
        LAST.clear();
        FLOODED.clear();
        RISE.clear();
        DRY.clear();
    }
}
