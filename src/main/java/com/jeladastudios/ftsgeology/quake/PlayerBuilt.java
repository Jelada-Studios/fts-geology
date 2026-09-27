package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where players have placed blocks, kept with each chunk. A house of sand or of cobblestone is made of the very blocks
 * the ground is, and nothing in the block itself tells the two apart; where it was put by a hand does. Read by the
 * shaking (see {@link ShakingDamage}), which loosens builds and never the landscape.
 *
 * <p>A position is dropped when a player breaks the block there, and when the chunk is saved with air in it. A block
 * a player placed before this was kept is not known here: builds of worked materials -- planks, glass, bricks -- are
 * still told by what they are made of.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class PlayerBuilt {

    private PlayerBuilt() {}

    public static final String TAG = "fts_player_placed";

    /** Placed positions by dimension-qualified chunk key; each set is guarded by itself. */
    private static final Map<String, LongSet> PLACED = new ConcurrentHashMap<>();

    private static String key(LevelAccessor level, int cx, int cz) {
        String dim = level instanceof net.minecraft.world.level.Level l ? l.dimension().location().toString() : "unknown";
        return dim + "|" + ChunkPos.asLong(cx, cz);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getLevel() instanceof ServerLevel level)) return;
        mark(level, event.getPos());
    }

    /** Records a block as placed by a player. */
    static void mark(ServerLevel level, BlockPos p) {
        LongSet set = PLACED.computeIfAbsent(key(level, p.getX() >> 4, p.getZ() >> 4), k -> new LongOpenHashSet());
        synchronized (set) {
            set.add(p.asLong());
        }
        // Saved with the chunk, so the chunk has to be saved: a chunk written out since its last change kept none.
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (chunk != null) chunk.setUnsaved(true);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos p = event.getPos();
        LongSet set = PLACED.get(key(level, p.getX() >> 4, p.getZ() >> 4));
        if (set == null) return;
        synchronized (set) {
            set.remove(p.asLong());
        }
    }

    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        ChunkAccess chunk = event.getChunk();
        LongSet set = PLACED.get(key(event.getLevel(), chunk.getPos().x, chunk.getPos().z));
        if (set == null) return;
        long[] kept;
        synchronized (set) {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            set.removeIf((long p) -> chunk.getBlockState(m.set(p)).isAir());
            kept = set.toLongArray();
        }
        if (kept.length > 0) event.getData().putLongArray(TAG, kept);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkDataEvent.Load event) {
        if (!event.getData().contains(TAG)) return;
        long[] stored = event.getData().getLongArray(TAG);
        if (stored.length == 0) return;
        ChunkPos cp = event.getChunk().getPos();
        LongSet set = PLACED.computeIfAbsent(key(event.getLevel(), cp.x, cp.z), k -> new LongOpenHashSet());
        synchronized (set) {
            for (long p : stored) set.add(p);
        }
    }

    /** A copy of what players placed in a chunk, empty where nothing is known. */
    public static LongSet inChunk(ServerLevel level, int cx, int cz) {
        LongSet set = PLACED.get(key(level, cx, cz));
        if (set == null) return new LongOpenHashSet();
        synchronized (set) {
            return new LongOpenHashSet(set);
        }
    }

    /** The server is going down, and the next may be another world with the same dimension names. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLACED.clear();
    }
}
