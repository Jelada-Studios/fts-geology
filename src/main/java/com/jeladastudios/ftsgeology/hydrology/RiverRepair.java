package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.quake.PlayerBuilt;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Keeps the rivers and lakes the generator laid where they belong. Their water never moves, so whatever takes it away
 * -- a quake dropping the ground under a channel, a cave roof falling in under a lake, another mod's water running off
 * into the hole, a player's bucket -- left a dry channel for good. The river network knows where every river runs and
 * the level of its water at every column, so the water can be laid again.
 *
 * <ul>
 *   <li><b>After the ground moves.</b> When a quake's ground has settled, the rivers and lakes of the chunks it moved
 *   are laid again at the network's level, as far as their banks hold them: the water stands no higher than the lowest
 *   bank beside it, and a bank a block or two low is built up, as the generator does. A channel the ground rose into by
 *   a few blocks is cut through again, the way a river keeps its course across a rising fold.</li>
 *   <li><b>A little at a time.</b> In the chunks round players, water missing from a channel comes back from the water
 *   beside it, a column a visit, so a drained stretch fills from its ends over a minute or so. A stretch walled off
 *   from the river stays dry.</li>
 * </ul>
 *
 * <p>The network is asked on a worker thread, once a chunk, and remembered; the water is laid on the server thread
 * within the tick's budget, without neighbour updates, so no other mod's water is set moving by it.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class RiverRepair {

    private RiverRepair() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS;
    /** How far a channel the ground rose into is cut down again, at most, in blocks over its water. */
    private static final int RECUT = 4;
    /** How low a bank may be built up to hold the water, in blocks. */
    private static final int BANK_FILL = 2;
    /** How often the chunks round players are looked over, and how far round them, in chunks. */
    private static final int KEEP_EVERY = 200, KEEP_REACH = 4;
    /** The most chunks remembered. */
    private static final int REMEMBERED = 4096;

    /**
     * What the network says of a chunk's columns and the ring round it, 18 by 18: the water level, or
     * {@link Integer#MIN_VALUE} where no river or lake is; which way it runs; whether it is a lake.
     */
    private record Plan(int[] water, byte[] flow, boolean[] lake, boolean[] narrow) {
        static int index(int lx, int lz) {
            return (lx + 1) * 18 + (lz + 1);
        }

        int waterAt(int lx, int lz) {
            return water[index(lx, lz)];
        }
    }

    /** A chunk with no river or lake anywhere near it. */
    private static final Plan DRY = new Plan(new int[0], new byte[0], new boolean[0], new boolean[0]);

    private static final Map<Long, Plan> PLANS = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Plan> e) {
                    return size() > REMEMBERED;
                }
            });
    private static final Map<Long, Boolean> ASKED = new ConcurrentHashMap<>();

    /** Chunks to lay again in full after the ground moved, and chunks to look over, in order. */
    private static final LongLinkedOpenHashSet MOVED = new LongLinkedOpenHashSet(), KEEP = new LongLinkedOpenHashSet();

    private static final LongAdder LAID = new LongAdder(), RECUT_COLUMNS = new LongAdder(), BANKS = new LongAdder(),
            CHUNKS = new LongAdder(), DRIED_COLUMNS = new LongAdder(), RECEDED = new LongAdder();

    /** Columns of small streams a well's cone has dried, until the water comes back under a block; and the drawdowns. */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet DRIED = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static final double DRY_AT = 2.0, WET_AT = 1.0;

    private static boolean enabled(ServerLevel level) {
        return GeyserConfig.RIVERS.get() && GeyserConfig.RIVERS_REFILL.get() && !TfcCompat.active()
                && Level.OVERWORLD.equals(level.dimension()) && GeologyWorld.isOwn(level) && RiverNetwork.ready();
    }

    /** The ground that moved under a quake: the columns it touched, as the settling keeps them ({@code x << 32 | z}). */
    public static void afterQuake(ServerLevel level, long[] columns) {
        if (!enabled(level)) return;
        for (long c : columns) {
            int x = (int) (c >> 32), z = (int) c;
            MOVED.add(ChunkPos.asLong(x >> 4, z >> 4));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null || !enabled(level)) return;
        if (server.getTickCount() % KEEP_EVERY == 0) {
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator()) continue;
                ChunkPos at = p.chunkPosition();
                for (int dx = -KEEP_REACH; dx <= KEEP_REACH; dx++) {
                    for (int dz = -KEEP_REACH; dz <= KEEP_REACH; dz++) KEEP.add(ChunkPos.asLong(at.x + dx, at.z + dz));
                }
            }
        }
        if (MOVED.isEmpty() && KEEP.isEmpty()) return;
        com.jeladastudios.ftsgeology.util.TickBudget.open(server.getTickCount());
        long deadline = System.nanoTime() + com.jeladastudios.ftsgeology.util.TickBudget.slice(0.1);
        // Not while the ground is still moving: the settling would drop the water again.
        if (com.jeladastudios.ftsgeology.quake.Earthquake.moving()) return;
        boolean had = !MOVED.isEmpty();
        drain(level, MOVED, true, deadline);
        if (had && MOVED.isEmpty()) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", summary());
        drain(level, KEEP, false, deadline);
    }

    private static void drain(ServerLevel level, LongLinkedOpenHashSet queue, boolean moved, long deadline) {
        int tries = queue.size();
        while (!queue.isEmpty() && tries-- > 0 && System.nanoTime() < deadline) {
            long key = queue.removeFirstLong();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;                              // gone: it is laid again when next looked over
            if (moved && (com.jeladastudios.ftsgeology.quake.Weathering.pendingNear(level, cx * 16 + 8, cz * 16 + 8, 24)
                    || com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(level, cx * 16 + 8, cz * 16 + 8))) {
                queue.add(key);                                       // the settling is not done here yet
                continue;
            }
            Plan plan = PLANS.get(key);
            // Round a player, a chunk with none of the rivers' water in it or beside it has nothing to keep: the network
            // is not asked about it. A drained stretch still fills, from its ends inwards.
            if (plan == null && !moved && !riverNear(level, chunk)) continue;
            if (plan == null) {
                ask(key, cx, cz, level.getSeaLevel());
                if (moved) queue.add(key);                            // back when the network has answered
                continue;
            }
            if (plan == DRY) continue;
            lay(level, chunk, plan, moved);
            CHUNKS.increment();
        }
    }

    /** Whether a chunk, or a loaded one beside it, holds any of the rivers' water. */
    private static boolean riverNear(ServerLevel level, LevelChunk chunk) {
        if (holdsRiver(chunk)) return true;
        ChunkPos p = chunk.getPos();
        for (int[] d : SIDES) {
            LevelChunk c = level.getChunkSource().getChunkNow(p.x + d[0], p.z + d[1]);
            if (c != null && holdsRiver(c)) return true;
        }
        return false;
    }

    /** Whether any of a chunk's sections has river water in its palette: a look at a few entries a section. */
    private static boolean holdsRiver(LevelChunk chunk) {
        net.minecraft.world.level.block.Block river = ModBlocks.RIVER_WATER.get();
        for (net.minecraft.world.level.chunk.LevelChunkSection s : chunk.getSections()) {
            if (s.hasOnlyAir()) continue;
            if (s.getStates().maybeHas(b -> b.getBlock() == river)) return true;
        }
        return false;
    }

    /** Asks the network about a chunk on a worker thread; the answer is remembered. */
    private static void ask(long key, int cx, int cz, int sea) {
        if (ASKED.putIfAbsent(key, Boolean.TRUE) != null) return;
        CompletableFuture.runAsync(() -> {
            try {
                PLANS.put(key, plan(cx, cz, sea));
            } finally {
                ASKED.remove(key);
            }
        }, net.minecraft.Util.backgroundExecutor());
    }

    private static Plan plan(int cx, int cz, int sea) {
        int[] water = new int[18 * 18];
        byte[] flow = new byte[18 * 18];
        boolean[] lake = new boolean[18 * 18];
        boolean[] narrow = new boolean[18 * 18];
        boolean any = false;
        for (int lx = -1; lx <= 16; lx++) {
            for (int lz = -1; lz <= 16; lz++) {
                int i = Plan.index(lx, lz);
                water[i] = Integer.MIN_VALUE;
                RiverNetwork.At a = RiverNetwork.at(cx * 16 + lx, cz * 16 + lz);
                if (a.distance() == Double.MAX_VALUE || a.sunk()) continue;
                int w = (int) Math.floor(a.water());
                // As the generator fills them: the channel's floor under its water, and above the sea.
                if (a.floor() > w - 0.5 || w <= sea) continue;
                water[i] = w;
                flow[i] = (byte) RiverWaterFluid.wayOf(a.fx(), a.fz());
                lake[i] = a.lake();
                narrow[i] = !a.lake() && a.halfWidth() <= 2.5;
                if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) any = true;
            }
        }
        return any ? new Plan(water, flow, lake, narrow) : DRY;
    }

    /**
     * Lays one chunk's river and lake water again. After a quake every column is laid; otherwise only one that has
     * water beside it at its level, so a drained stretch fills from its ends, a column a visit.
     */
    private static void lay(ServerLevel level, LevelChunk chunk, Plan plan, boolean moved) {
        ChunkPos cp = chunk.getPos();
        LongSet placed = PlayerBuilt.inChunk(level, cp.x, cp.z);
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int w = plan.waterAt(lx, lz);
                if (w == Integer.MIN_VALUE) continue;
                int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                if (Reservoirs.heldBack(x, z)) continue;       // a reservoir filling upstream takes this water
                int g = ground(chunk, x, z, m);
                if (g == Integer.MIN_VALUE) continue;
                boolean lake = plan.lake()[Plan.index(lx, lz)];
                // A lake in a long drought is down a block: its top water goes as the column is visited, and is not laid
                // again till the rains come back, when it fills from the water still in it.
                if (lake && low(level, x, z)) {
                    BlockState at = chunk.getBlockState(m.set(x, w, z));
                    if ((at.is(ModBlocks.RIVER_WATER.get()) || at.is(Blocks.ICE)) && chunk.getBlockState(m.set(x, w + 1, z)).getFluidState().isEmpty()) {
                        level.setBlock(m.set(x, w, z), Blocks.AIR.defaultBlockState(), FLAGS);
                        RECEDED.increment();
                    }
                    w--;
                }
                if (dryStream(level, chunk, plan, x, z, lx, lz, g, w, m)) continue;
                if (g >= w) {
                    // Ground the quake lifted into a river's channel is cut through again; a lake's shore is its shore.
                    if (!moved || lake || g - w >= RECUT || !recut(chunk, x, z, g, w, placed, m)) continue;
                    g = w - 1;
                    RECUT_COLUMNS.increment();
                }
                // The water stands no higher than the lowest bank that is not the river's own; a bank a block or two
                // low is built up to hold it, after a quake.
                int top = w;
                for (int[] d : SIDES) {
                    int nx = lx + d[0], nz = lz + d[1];
                    if (plan.waterAt(nx, nz) != Integer.MIN_VALUE) continue;
                    int bx = x + d[0], bz = z + d[1];
                    LevelChunk nc = (bx >> 4) == cp.x && (bz >> 4) == cp.z ? chunk
                            : level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
                    if (nc == null) continue;
                    int bank = ground(nc, bx, bz, m);
                    if (bank == Integer.MIN_VALUE) continue;
                    if (bank < w && moved && w - bank <= BANK_FILL && buildBank(level, nc, bx, bz, bank, w, m)) bank = w;
                    top = Math.min(top, bank);
                }
                if (top <= g || top - g > DEEPEST) continue;
                if (!moved && !besideWater(level, chunk, x, z, top, m)) continue;
                if (!held(level, chunk, plan, x, z, lx, lz, g, top, m)) continue;
                BlockState run = water.setValue(RiverWaterFluid.FLOW, (int) plan.flow()[Plan.index(lx, lz)]);
                int here = 0;
                for (int y = g + 1; y <= top; y++) {
                    BlockState s = chunk.getBlockState(m.set(x, y, z));
                    if (s.is(ModBlocks.RIVER_WATER.get())) continue;
                    if (placed.contains(m.asLong())) break;
                    boolean open = s.isAir() || TerrainProbe.isVegetation(s) && !s.is(Blocks.SNOW)
                            || s.getFluidState().is(FluidTags.WATER) && !s.getFluidState().isSource();
                    if (!open) {
                        if (s.getFluidState().isSource()) continue;         // the sea's water, or a lake's own
                        break;                                               // something standing in the channel
                    }
                    level.setBlock(m, run, FLAGS);
                    here++;
                }
                if (here == 0) continue;
                LAID.add(here);
                // A lake where it snows is frozen over, as the generator left it.
                if (lake && top == w && chunk.getBlockState(m.set(x, w, z)).is(ModBlocks.RIVER_WATER.get())
                        && level.getBiome(m).value().coldEnoughToSnow(m)) {
                    level.setBlock(m, Blocks.ICE.defaultBlockState(), FLAGS);
                }
            }
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** How dry a region's spell is for its lakes to go down a block (see Storms#spell). */
    private static final double DROUGHT = 0.2;

    /** Whether the lakes round a place are down a block: the region is in a long drought. */
    static boolean low(ServerLevel level, int x, int z) {
        return GeyserConfig.LAKE_LEVELS.get() && com.jeladastudios.ftsgeology.weather.Storms.spell(level, x, z) < DROUGHT;
    }

    /** The ground of a column, under its water, plants and trees; read straight from its chunk. */
    private static int ground(LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos m) {
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int floor = Math.max(chunk.getMinBuildHeight(), top - 128);
        for (int y = top; y > floor; y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty() || TerrainProbe.isVegetation(s) || TerrainProbe.isTreePart(s)
                    || s.is(Blocks.ICE)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    /** The deepest a column is filled: a hole deeper than that under a channel is a cave's, not the river's. */
    private static final int DEEPEST = 24;

    /**
     * Whether every block of water to be laid in a column has something on each side of it: rock, water, or a column of
     * the river that takes water too. A cave opening off the side of a fallen-in channel would have water standing in
     * the open in it.
     */
    private static boolean held(ServerLevel level, LevelChunk chunk, Plan plan, int x, int z, int lx, int lz, int g,
                                int top, BlockPos.MutableBlockPos m) {
        for (int[] d : SIDES) {
            if (plan.waterAt(lx + d[0], lz + d[1]) != Integer.MIN_VALUE) continue;
            int bx = x + d[0], bz = z + d[1];
            LevelChunk nc = (bx >> 4) == (x >> 4) && (bz >> 4) == (z >> 4) ? chunk
                    : level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
            if (nc == null) return false;
            for (int y = g + 1; y <= top; y++) {
                BlockState s = nc.getBlockState(m.set(bx, y, bz));
                if (s.isAir() || TerrainProbe.isVegetation(s) && s.getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    /** Whether water stands beside a column at or just under the level it is to take. */
    private static boolean besideWater(ServerLevel level, LevelChunk chunk, int x, int z, int top,
                                       BlockPos.MutableBlockPos m) {
        for (int[] d : SIDES) {
            int bx = x + d[0], bz = z + d[1];
            LevelChunk nc = (bx >> 4) == (x >> 4) && (bz >> 4) == (z >> 4) ? chunk
                    : level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
            if (nc == null) continue;
            for (int y = top; y >= top - 1; y--) {
                if (nc.getBlockState(m.set(bx, y, bz)).getFluidState().is(FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    /** Cuts a channel the ground rose into down to its water, taking only the world's own ground. */
    private static boolean recut(LevelChunk chunk, int x, int z, int g, int w, LongSet placed, BlockPos.MutableBlockPos m) {
        for (int y = w; y <= g; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (placed.contains(m.asLong()) || EruptionHandler.isPlayerPlaced(s) || s.hasBlockEntity()) return false;
        }
        ServerLevel level = (ServerLevel) chunk.getLevel();
        for (int y = g + 2; y >= w; y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (!s.isAir()) level.setBlock(m, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        return true;
    }

    /** Builds a bank up to the water beside it, from what the bank is made of: its top over the soil under it. */
    private static boolean buildBank(ServerLevel level, LevelChunk chunk, int x, int z, int bank, int w,
                                     BlockPos.MutableBlockPos m) {
        BlockState top = chunk.getBlockState(m.set(x, bank, z));
        if (EruptionHandler.isPlayerPlaced(top) || top.hasBlockEntity() || !top.getFluidState().isEmpty()) return false;
        if (PlayerBuilt.inChunk(level, x >> 4, z >> 4).contains(m.asLong())) return false;
        for (int y = bank + 1; y <= w; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (!s.isAir() && !TerrainProbe.isVegetation(s)) return false;
        }
        BlockState under = top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM)
                ? Blocks.DIRT.defaultBlockState() : top;
        level.setBlock(m.set(x, bank, z), under, FLAGS);
        for (int y = bank + 1; y <= w; y++) level.setBlock(m.set(x, y, z), y == w ? top : under, FLAGS);
        BANKS.increment();
        return true;
    }

    /**
     * A small stream over ground the wells round it have drawn down two blocks and more runs dry there: its water goes,
     * and stays gone until the drawdown is back under a block, when the column fills from its ends as any drained
     * stretch does. A river or a lake holds. True while the column is dry.
     */
    private static boolean dryStream(ServerLevel level, LevelChunk chunk, Plan plan, int x, int z, int lx, int lz, int g, int w,
                                     BlockPos.MutableBlockPos m) {
        if (!GeyserConfig.STREAMS_DRY_UP.get() || !plan.narrow()[Plan.index(lx, lz)]) return false;
        long col = ChunkPos.asLong(x, z);
        double drawn = Aquifer.drawdown(level, x + 0.5, z + 0.5);
        boolean dry = DRIED.contains(col) ? drawn >= WET_AT : drawn >= DRY_AT;
        if (!dry) {
            DRIED.remove(col);
            return false;
        }
        if (DRIED.add(col)) DRIED_COLUMNS.increment();
        for (int y = w; y > g; y--) {
            if (chunk.getBlockState(m.set(x, y, z)).is(ModBlocks.RIVER_WATER.get())) level.setBlock(m, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        return true;
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT,
                "river repair: %d chunks laid again, %d blocks of water, %d channels cut through, %d banks built up, %d stream columns run dry, %d lake columns down a block in a drought",
                CHUNKS.sum(), LAID.sum(), RECUT_COLUMNS.sum(), BANKS.sum(), DRIED_COLUMNS.sum(), RECEDED.sum());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLANS.clear();
        MOVED.clear();
        KEEP.clear();
        DRIED.clear();
    }
}
