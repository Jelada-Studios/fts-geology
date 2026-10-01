package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Buildings coming down: the ones standing on ground a rupture moved, and the parts of others the shaking broke
 * ({@link Structural}). What comes down falls, as falling blocks thrown a little aside, and lands as rubble where it
 * lands; past as many as the tick can afford to show, the rest breaks where it stood and drops what it is made of.
 * Anything with contents, and every ornament, is broken where it stands and drops, contents and all.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Collapse {

    private Collapse() {}

    /** How high over its ground a wrecked column is taken down, and how much open air ends it. */
    private static final int HEIGHT = 48, SKY = 6;
    /** Falling blocks shown at most per tick, and per event. */
    private static final int SHOWN_PER_TICK = 48, SHOWN = 1500;

    private static final LongAdder COLUMNS = new LongAdder(), FELL = new LongAdder(), BROKE = new LongAdder();

    /** A column to wreck from its ground up, or one block to bring down, at its moment. */
    private record Due(ResourceKey<Level> dimension, long pos, boolean column, long at, double pushX, double pushZ) {}

    private static final PriorityQueue<Due> DUE = new PriorityQueue<>(Comparator.comparingLong(Due::at));
    private static int shownThisTick, shownThisEvent;

    /** Columns something came down in since the last quiet, by dimension: swept for what it left hanging once all is down. */
    private static final java.util.Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.LongOpenHashSet> TOUCHED = new java.util.HashMap<>();
    private static long tick = Long.MIN_VALUE;

    /**
     * The columns whose ground a rupture moved, each given as its ground position: what was built on them comes down
     * over the next {@code spread} ticks.
     */
    public static void wreckColumns(ServerLevel level, long[] grounds, int spread) {
        if (grounds == null || grounds.length == 0) return;
        long now = level.getGameTime();
        shownThisEvent = 0;
        for (long g : grounds) {
            DUE.add(new Due(level.dimension(), g, true, now + level.random.nextInt(Math.max(1, spread)),
                    level.random.nextDouble() - 0.5, level.random.nextDouble() - 0.5));
        }
        COLUMNS.add(grounds.length);
    }

    /** One block to bring down at {@code at}, pushed along {@code pushX, pushZ} as it goes. */
    public static void fail(ServerLevel level, BlockPos pos, long at, double pushX, double pushZ) {
        DUE.add(new Due(level.dimension(), pos.asLong(), false, at, pushX, pushZ));
    }

    /** Whether something came down since the last summary was logged. */
    private static boolean report;

    public static void drain(MinecraftServer server, long nanos) {
        if (DUE.isEmpty()) {
            if (report) {
                report = false;
                // What the shaking brought down may have left a beam hanging where no rupture moved the ground to
                // settle it: those columns are swept once it has all come down.
                for (var e : TOUCHED.entrySet()) {
                    ServerLevel level = server.getLevel(e.getKey());
                    if (level != null) Weathering.sweep(level, e.getValue());
                }
                TOUCHED.clear();
                com.jeladastudios.ftsgeology.util.Diagnostics.info("{}; {}", summary(), Structural.summary());
            }
            return;
        }
        report = true;
        long deadline = System.nanoTime() + nanos;
        if (server.getTickCount() != tick) {
            tick = server.getTickCount();
            shownThisTick = 0;
        }
        while (!DUE.isEmpty() && System.nanoTime() < deadline) {
            Due d = DUE.peek();
            ServerLevel level = server.getLevel(d.dimension());
            if (level == null) {
                DUE.poll();
                continue;
            }
            if (d.at() > level.getGameTime()) break;
            DUE.poll();
            BlockPos p = BlockPos.of(d.pos());
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, p)) continue;
            if (d.column()) column(level, p, d);
            else down(level, p, level.getBlockState(p), d.pushX(), d.pushZ());
        }
    }

    /** Takes down everything built on one column, top first so each block falls clear of the one under it. */
    private static void column(ServerLevel level, BlockPos ground, Due d) {
        LongSet placed = PlayerBuilt.inChunk(level, ground.getX() >> 4, ground.getZ() >> 4);
        var chunk = level.getChunkAt(ground);
        List<BoundingBox> pieces = ShakingDamage.structureBoxes(level, chunk, level.getMinBuildHeight());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = ground.getY(), air = 0;
        for (int y = ground.getY() + 1; y <= ground.getY() + HEIGHT && air < SKY; y++) {
            BlockState s = level.getBlockState(m.set(ground.getX(), y, ground.getZ()));
            if (s.isAir()) {
                air++;
                continue;
            }
            air = 0;
            top = y;
        }
        for (int y = top; y > ground.getY(); y--) {
            m.set(ground.getX(), y, ground.getZ());
            BlockState s = level.getBlockState(m);
            if (s.isAir() || !built(level, s, m, placed, pieces)) continue;
            down(level, m.immutable(), s, d.pushX(), d.pushZ());
        }
    }

    /** Whether a block is part of a building: placed by a player, a structure's other than its ground, or worked. */
    public static boolean built(net.minecraft.world.level.BlockGetter level, BlockState s, BlockPos p, LongSet placed,
                         List<BoundingBox> pieces) {
        if (s.isAir() || !s.getFluidState().isEmpty() && s.getBlock() == Blocks.WATER) return false;
        if (QuakePlanner.machinery(s)) return false;
        if (placed.contains(p.asLong())) return true;
        // In a structure's bounds, all but its ground and the trees standing there: a village's log posts are its own.
        if (!pieces.isEmpty() && ShakingDamage.inside(pieces, p.getX(), p.getY(), p.getZ()) && !ShakingDamage.ground(s)
                && (!TerrainProbe.isTreePart(s) || ShakingDamage.builtLog(level, s, p.getX(), p.getY(), p.getZ()))) {
            return true;
        }
        return EruptionHandler.isWorked(s) && !TerrainProbe.isVegetation(s);
    }

    /** A block came away here some other way (shaken off): the column is swept with the others once all is down. */
    public static void touched(ServerLevel level, BlockPos p) {
        TOUCHED.computeIfAbsent(level.dimension(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet())
                .add(Weathering.column(p.getX(), p.getZ()));
        report = true;
    }

    /** A loose piece of a building comes down where it hangs, its top first. */
    public static void loose(ServerLevel level, List<BlockPos> piece) {
        piece.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
        for (BlockPos p : piece) down(level, p, level.getBlockState(p), 0, 0);
    }

    /** One block comes down: falling, or broken where it stands. */
    public static void down(ServerLevel level, BlockPos p, BlockState s, double pushX, double pushZ) {
        if (s.isAir() || QuakePlanner.machinery(s)) return;
        if (level.getServer().getTickCount() != tick) {
            tick = level.getServer().getTickCount();
            shownThisTick = 0;
        }
        TOUCHED.computeIfAbsent(level.dimension(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet())
                .add(Weathering.column(p.getX(), p.getZ()));
        boolean full = s.isCollisionShapeFullBlock(level, p);
        // What a player built comes back to them as items where it cannot come down as a block; what the world built,
        // a village's walls and roofs, is rubble. Every block of a wrecked village left lying as an item was thousands
        // of them over the ground, and taken for blocks copied.
        boolean own = PlayerBuilt.inChunk(level, p.getX() >> 4, p.getZ() >> 4).contains(p.asLong());
        if (s.hasBlockEntity() || !full || shownThisTick >= SHOWN_PER_TICK || shownThisEvent >= SHOWN) {
            // Contents spill, ornaments drop, and past what can be shown falling the block breaks where it is.
            level.destroyBlock(p, own || s.hasBlockEntity());
            BROKE.increment();
            return;
        }
        shownThisTick++;
        shownThisEvent++;
        FallingBlockEntity f = FallingBlockEntity.fall(level, p, s);
        f.setDeltaMovement(new Vec3(pushX * 0.3, 0.05, pushZ * 0.3));
        f.setHurtsEntities(2.0f, 20);
        f.dropItem = own;
        f.hurtMarked = true;
        if (level.random.nextInt(6) == 0) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), p.getX() + 0.5, p.getY() + 0.5,
                    p.getZ() + 0.5, 8, 0.4, 0.4, 0.4, 0.05);
        }
        FELL.increment();
    }

    public static boolean busy() {
        return !DUE.isEmpty();
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "collapse: %d columns wrecked, %d blocks fell, %d broke",
                COLUMNS.sum(), FELL.sum(), BROKE.sum());
    }

    public static void clear() {
        DUE.clear();
        TOUCHED.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
