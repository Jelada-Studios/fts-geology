package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.quake.QuakeQuiet;
import com.jeladastudios.ftsgeology.util.TickBudget;
import com.jeladastudios.ftsgeology.worldgen.HotSpringShape;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Moves river bends, a block at a time over weeks: the outer bank is cut into the channel and a bar of sand
 * grows on the inner one, until each bend has shifted as far as {@link RiverSurvey} planned and stops.
 *
 * <p>Three queues, all worked from the tick loop inside the mod's budget: chunks to survey when they load,
 * chunks whose bends can be planned once their neighbours are known, and the bends themselves, due one step
 * every so many ticks. A step is applied only while the cells it touches are loaded; otherwise it waits, and
 * the wait is paid off when the chunk comes back. Every write goes without neighbour updates, so the water
 * placed never runs and nothing is dropped as an item.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class MeanderScheduler {

    private MeanderScheduler() {}

    /** How every write goes: to the clients, no neighbour shape updates. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Fewest ticks between two steps of one bend, however short the configured span. */
    private static final int MIN_INTERVAL = 100;

    /** Cells written per tick at most. */
    private static final int CELLS_PER_TICK = 24;

    /** Chunks looked at per tick for surveying and planning. */
    private static final int CHUNKS_PER_TICK = 4;

    private record Key(ResourceKey<Level> dimension, long chunk) {}

    /** Chunks that loaded and want surveying; filled from the load event, drained on the tick. */
    private static final ConcurrentLinkedQueue<Key> TO_SURVEY = new ConcurrentLinkedQueue<>();

    /** Chunks surveyed with a river whose bends have not been planned yet. */
    private static final Map<ResourceKey<Level>, Deque<Long>> TO_PLAN = new HashMap<>();

    /** Chunks holding live bends, per dimension, walked round in turn. */
    private static final Map<ResourceKey<Level>, List<Long>> LIVE = new HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> CURSOR = new HashMap<>();
    private static final Map<ResourceKey<Level>, Boolean> LOADED = new HashMap<>();

    /** Ticks between two steps of a bend of this many steps. */
    static long interval(int steps) {
        double days = GeyserConfig.RIVER_MEANDER_DAYS.get();
        return Math.max(MIN_INTERVAL, Math.round(days * 24000.0 / Math.max(1, steps)));
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!GeyserConfig.RIVER_MEANDERS.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        TO_SURVEY.add(new Key(level.dimension(), chunk.getPos().toLong()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        if (!GeyserConfig.RIVER_MEANDERS.get()) return;
        MinecraftServer server = event.getServer();
        TickBudget.open(server.getTickCount());
        long deadline = System.nanoTime() + TickBudget.slice(0.1);
        try {
            surveySome(server, deadline);
            planSome(server, deadline);
            stepSome(server, deadline);
        } catch (RuntimeException e) {
            GeysersMod.LOGGER.warn("River meanders: {}", e.toString());
        }
    }

    /** Drops the in-memory queues; the survey itself lives in the world save. */
    public static void clear() {
        TO_SURVEY.clear();
        TO_PLAN.clear();
        LIVE.clear();
        CURSOR.clear();
        LOADED.clear();
    }

    /** The ground under a box changed (a quake): its chunks are surveyed again when next loaded. */
    public static void terrainChanged(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        if (!GeyserConfig.RIVER_MEANDERS.get()) return;
        RiverSurvey.of(level).invalidate(minX, minZ, maxX, maxZ);
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    TO_SURVEY.add(new Key(level.dimension(), ChunkPos.asLong(cx, cz)));
                }
            }
        }
    }

    // === Surveying and planning =============================================

    private static void surveySome(MinecraftServer server, long deadline) {
        for (int i = 0; i < CHUNKS_PER_TICK * 4 && System.nanoTime() < deadline; i++) {
            Key k = TO_SURVEY.poll();
            if (k == null) return;
            ServerLevel level = server.getLevel(k.dimension());
            if (level == null) continue;
            int cx = ChunkPos.getX(k.chunk()), cz = ChunkPos.getZ(k.chunk());
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            RiverSurvey s = RiverSurvey.of(level);
            RiverSurvey.Rec r = s.get(cx, cz);
            if (r != null && r.current()) {
                // Known already; only its bends may still want planning or running.
                if (r.river() && !r.planned) queuePlan(level, cx, cz);
                if (hasLive(r)) noteLive(level, cx, cz);
                continue;
            }
            s.survey(level, chunk);
            r = s.get(cx, cz);
            if (r != null && r.river()) queuePlan(level, cx, cz);
            // A neighbour's plan may have been waiting on this chunk.
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oz == 0) continue;
                    RiverSurvey.Rec n = s.get(cx + ox, cz + oz);
                    if (n != null && n.river() && !n.planned) queuePlan(level, cx + ox, cz + oz);
                }
            }
        }
    }

    private static void queuePlan(ServerLevel level, int cx, int cz) {
        Deque<Long> q = TO_PLAN.computeIfAbsent(level.dimension(), d -> new ArrayDeque<>());
        long key = ChunkPos.asLong(cx, cz);
        if (!q.contains(key)) q.add(key);
    }

    private static void planSome(MinecraftServer server, long deadline) {
        for (Map.Entry<ResourceKey<Level>, Deque<Long>> e : TO_PLAN.entrySet()) {
            ServerLevel level = server.getLevel(e.getKey());
            if (level == null) { e.getValue().clear(); continue; }
            Deque<Long> q = e.getValue();
            int n = q.size();
            for (int i = 0; i < n && i < CHUNKS_PER_TICK && System.nanoTime() < deadline; i++) {
                long key = q.poll();
                int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
                RiverSurvey s = RiverSurvey.of(level);
                RiverSurvey.Rec r = s.get(cx, cz);
                if (r == null || !r.river() || r.planned || !r.current()) continue;
                if (!s.neighbourhoodKnown(cx, cz)) continue;   // waits for its neighbours to load
                s.plan(level, cx, cz);
                if (hasLive(r)) {
                    noteLive(level, cx, cz);
                    GeysersMod.LOGGER.debug("river bends planned in chunk {},{}: {}", cx, cz, describe(r));
                }
            }
        }
    }

    private static String describe(RiverSurvey.Rec r) {
        StringBuilder sb = new StringBuilder();
        for (RiverSurvey.Bend b : r.bends) {
            sb.append(b.x).append(',').append(b.z).append(" w").append(b.width).append(" n").append(b.steps).append("; ");
        }
        return sb.toString();
    }

    private static boolean hasLive(RiverSurvey.Rec r) {
        for (RiverSurvey.Bend b : r.bends) if (b.live()) return true;
        return false;
    }

    private static void noteLive(ServerLevel level, int cx, int cz) {
        List<Long> live = LIVE.computeIfAbsent(level.dimension(), d -> new ArrayList<>());
        long key = ChunkPos.asLong(cx, cz);
        if (!live.contains(key)) live.add(key);
    }

    /** The first time a dimension is looked at after a start: every chunk with a live bend in the save is listed. */
    private static void gather(ServerLevel level) {
        if (LOADED.getOrDefault(level.dimension(), false)) return;
        LOADED.put(level.dimension(), true);
        RiverSurvey s = RiverSurvey.of(level);
        for (var e : s.recs.long2ObjectEntrySet()) {
            if (hasLive(e.getValue())) noteLive(level, ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey()));
        }
    }

    // === Steps ==============================================================

    private static void stepSome(MinecraftServer server, long deadline) {
        for (ServerLevel level : server.getAllLevels()) {
            gather(level);
            List<Long> live = LIVE.get(level.dimension());
            if (live == null || live.isEmpty()) continue;
            RiverSurvey s = RiverSurvey.of(level);
            long now = level.getGameTime();
            int cursor = CURSOR.getOrDefault(level.dimension(), 0);
            int written = 0;
            for (int i = 0; i < live.size() && written < CELLS_PER_TICK && System.nanoTime() < deadline; i++) {
                cursor = (cursor + 1) % live.size();
                long key = live.get(cursor);
                RiverSurvey.Rec r = s.get(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (r == null || !hasLive(r)) {
                    live.remove(cursor);
                    if (live.isEmpty()) break;
                    cursor = cursor % live.size();
                    continue;
                }
                for (RiverSurvey.Bend b : r.bends) {
                    if (!b.live() || b.next > now) continue;
                    int result = step(level, r, b);
                    if (result == STEP_WAIT) continue;
                    if (result == STEP_DONE) {
                        b.done++;
                        b.next = now + interval(b.steps);
                        written += 2 * (2 * Math.max(1, b.width / 4) + 1);
                    } else {
                        b.dead = true;
                    }
                    s.setDirty();
                }
            }
            CURSOR.put(level.dimension(), cursor);
        }
    }

    private static final int STEP_DONE = 0, STEP_WAIT = 1, STEP_DEAD = 2;

    /** Ends a bend and says why, at debug level. */
    private static int dead(RiverSurvey.Bend b, String why) {
        GeysersMod.LOGGER.debug("river bend at {},{} died: {}", b.x, b.z, why);
        return STEP_DEAD;
    }

    /**
     * One step of one bend, across a band of transects along the bank: a bend erodes along a length of bank and
     * builds a crescent of a bar, not a single column and a spit. Done when any transect moved; waiting when none
     * moved but one is off in a chunk that is not loaded; dead when nothing is left to move anywhere.
     */
    private static int step(ServerLevel level, RiverSurvey.Rec r, RiverSurvey.Bend b) {
        int m = Math.max(1, b.width / 4);
        double tx = -b.nz, tz = b.nx;
        boolean done = false, wait = false;
        for (int j = -m; j <= m; j++) {
            int res = transect(level, r, b, b.x + 0.5 + tx * j, b.z + 0.5 + tz * j);
            if (res == STEP_DONE) done = true;
            else if (res == STEP_WAIT) wait = true;
        }
        return done ? STEP_DONE : wait ? STEP_WAIT : STEP_DEAD;
    }

    /**
     * One transect of a step: the first bank cell out along the normal comes into the channel, and the last water
     * cell before the inner bank fills with sand. Both cells are found and checked first; nothing is written unless
     * both can be.
     */
    private static int transect(ServerLevel level, RiverSurvey.Rec r, RiverSurvey.Bend b, double ax, double az) {
        int yW = r.yW, depth = r.bed;
        int reach = b.width + b.steps + 3;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        // Out: the bank.
        int ox = Integer.MIN_VALUE, oz = 0;
        for (int t = 1; t <= reach; t++) {
            int x = (int) Math.floor(ax + b.nx * t), z = (int) Math.floor(az + b.nz * t);
            if (!level.hasChunkAt(m.set(x, yW, z))) return STEP_WAIT;
            if (riverWater(level.getBlockState(m.set(x, yW, z)))) continue;
            ox = x; oz = z;
            break;
        }
        if (ox == Integer.MIN_VALUE) return dead(b, "no bank within reach");

        // In: the last water before the inner bank.
        int ix = Integer.MIN_VALUE, iz = 0;
        for (int t = 1; t <= reach; t++) {
            int x = (int) Math.floor(ax - b.nx * t), z = (int) Math.floor(az - b.nz * t);
            if (!level.hasChunkAt(m.set(x, yW, z))) return STEP_WAIT;
            if (riverWater(level.getBlockState(m.set(x, yW, z)))) { ix = x; iz = z; continue; }
            break;
        }
        // The channel would be closed if the bar reached the outer bank: the bend has done all it can.
        if (ix != Integer.MIN_VALUE && Math.abs(ix - ox) + Math.abs(iz - oz) < 3) return dead(b, "channel closed");

        if (QuakeQuiet.isQuiet(level, new BlockPos(ox, yW, oz))) return STEP_WAIT;

        // The bank cell: natural, low, bare of trees, on soft ground, with solid ground behind and beside it.
        int g = TerrainProbe.groundY(level, ox, oz);
        if (g == Integer.MIN_VALUE || g < yW) return dead(b, "no ground at the bank, " + g);
        if (g - yW > RiverSurvey.MAX_BANK) return dead(b, "bank " + (g - yW) + " high");
        for (int y = yW; y <= g + 2; y++) {
            BlockState s = level.getBlockState(m.set(ox, y, oz));
            if (s.isAir()) continue;
            if (s.is(BlockTags.LOGS) || s.is(Blocks.MANGROVE_ROOTS)) return dead(b, "a tree on the bank");
            if (EruptionHandler.isPlayerPlaced(s) && !TerrainProbe.isVegetation(s)) return dead(b, "a build on the bank: " + s.getBlock());
            if (HotSpringShape.isMatBlock(s) || s.is(Blocks.BEDROCK)) return dead(b, "a spring or bedrock");
        }
        BlockState bank = level.getBlockState(m.set(ox, yW, oz));
        if (!bank.getFluidState().isEmpty() || bank.isAir()) return dead(b, "bank cell is " + bank.getBlock());
        if (RockTypes.erodibility(bank) < 0.5) return dead(b, "rock bank: " + bank.getBlock());
        if (com.jeladastudios.ftsgeology.volcano.VolcanoSummit.standsOnVolcanicRock(level, ox, oz)) return dead(b, "volcanic rock");
        // Behind and beside, at the water level, nothing the water could run off into.
        int bx = (int) Math.floor(ox + 0.5 + b.nx), bz = (int) Math.floor(oz + 0.5 + b.nz);
        if (!level.hasChunkAt(m.set(bx, yW, bz))) return STEP_WAIT;
        if (!holds(level.getBlockState(m.set(bx, yW, bz)))) return dead(b, "nothing behind the bank");
        int tx = (int) Math.round(-b.nz), tz = (int) Math.round(b.nx);
        for (int side = -1; side <= 1; side += 2) {
            int sx = ox + side * tx, sz = oz + side * tz;
            if (!level.hasChunkAt(m.set(sx, yW, sz))) return STEP_WAIT;
            if (!holds(level.getBlockState(m.set(sx, yW, sz)))) return dead(b, "nothing beside the bank");
        }

        // Cut: the bank above the water goes, the water comes in down to the bed.
        for (int y = g + 2; y > yW; y--) {
            BlockState s = level.getBlockState(m.set(ox, y, oz));
            if (!s.isAir()) level.setBlock(new BlockPos(ox, y, oz), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        for (int y = yW; y > yW - depth; y--) {
            BlockState s = level.getBlockState(m.set(ox, y, oz));
            if (s.is(Blocks.BEDROCK) || (EruptionHandler.isPlayerPlaced(s) && !s.isAir())) break;
            level.setBlock(new BlockPos(ox, y, oz), Blocks.WATER.defaultBlockState(), FLAGS);
        }

        // Fill: the bar on the inner side, from the bed up to the water line, so the sand rests on ground and
        // never falls through water it was set over.
        if (ix != Integer.MIN_VALUE) {
            int bottom = yW;
            while (bottom - 1 > yW - depth - 4 && level.getBlockState(m.set(ix, bottom - 1, iz)).getFluidState().is(FluidTags.WATER)) bottom--;
            for (int y = bottom; y <= yW; y++) {
                BlockState s = level.getBlockState(m.set(ix, y, iz));
                if (!s.getFluidState().is(FluidTags.WATER)) continue;
                level.setBlock(new BlockPos(ix, y, iz), sediment(ix, y, iz, b.width, y == yW), FLAGS);
            }
        }
        return STEP_DONE;
    }

    /** Still water at the surface: the river, not a placed bucket in a hole. */
    private static boolean riverWater(BlockState s) {
        return s.getFluidState().is(FluidTags.WATER);
    }

    /** True where a cell at the water level keeps the water in: solid ground, or more river. */
    private static boolean holds(BlockState s) {
        if (s.getFluidState().is(FluidTags.WATER)) return true;
        return !s.isAir() && s.getFluidState().isEmpty() && !TerrainProbe.isVegetation(s);
    }

    /** What a point bar is made of: mostly sand, gravel in a fast narrow channel, a little clay in a slow wide one. */
    private static BlockState sediment(int x, int y, int z, int width, boolean top) {
        long h = com.jeladastudios.ftsgeology.util.SeedHash.hash(0x5EDL ^ y, x, z, 0x5EDL);
        int roll = (int) Math.floorMod(h, 10L);
        if (top) return (roll < 8 || width < 6 ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
        if (width < 6) return (roll < 5 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState();
        if (width > 12 && roll < 2) return Blocks.CLAY.defaultBlockState();
        return (roll < 8 ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
    }
}
