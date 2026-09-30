package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dams: a river walled across fills up behind the wall, and a wall too weak for the water it holds gives way.
 *
 * <p>The rivers' water stands still (see {@link RiverWaterFluid}), laid at the level the river network gives each
 * column, so a wall across a channel holds nothing back by itself. A block a player sets in a river's water is looked
 * at a few seconds later: if the blocks there now cut the river in two at its surface -- the water on one side no
 * longer reaching the other -- the side upstream (against the way the water runs) becomes a reservoir. It fills a
 * layer at a time, as fast as the river brings water (a brook a few blocks a second, a broad river tens), up to the
 * top of the wall, or to where the valley's side is low enough to let it out. The water is the river's, fresh: fields
 * beside it are watered, and it is drinkable.</p>
 *
 * <p>A wall holds back a push that grows with the water's height behind it. A gravity dam must be about as thick as it
 * is high where built of stone, more than half of that of concrete, three times of earth; an earth bank overtopped
 * washes out. A dam too thin for its water starts to leak and creak, and before long breaks: a gap is torn through
 * it, the reservoir runs out a layer at a time, and the river finds its way through the gap again (the rivers' upkeep
 * lays it back). While it fills, and once full, the dam is looked at again every few seconds.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Reservoirs {

    private Reservoirs() {}

    /** Ticks after a block is set in a river before the wall there is read: time to finish building it. */
    private static final int READ_AFTER = 100;
    /** Most water surface one side of a dam may have within reach, and how far from the dam, to count as a river. */
    private static final int SIDE_MOST = 4096, REACH = 160;
    /** Most columns a reservoir's layer may spread over before it is taken as spilling over the valley's side. */
    private static final int LAYER_MOST = 8000;
    /** Ticks between looks at each reservoir. */
    private static final int EVERY = 40;

    enum State { FILLING, FULL, DRAINING }

    static final class Reservoir {
        BlockPos dam;
        /** The river's own surface, the reservoir's top water block now, and the most it may reach. */
        int base, level, crest;
        /** The water's surface below the dam. */
        int below;
        /** Which way the river runs at the dam, unit steps along x and z. */
        int fx, fz;
        /** A block of the reservoir's water at its base, to find the rest from. */
        BlockPos seed;
        /** Blocks of water the river has brought and not yet laid. */
        double owed;
        /** Blocks of water a second the river brings. */
        double inflow;
        State state = State.FILLING;
        long lastWarn;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Dam", dam.asLong());
            t.putLong("Seed", seed.asLong());
            t.putInt("Base", base);
            t.putInt("Level", level);
            t.putInt("Crest", crest);
            t.putInt("Below", below);
            t.putInt("Fx", fx);
            t.putInt("Fz", fz);
            t.putDouble("Owed", owed);
            t.putDouble("Inflow", inflow);
            t.putString("State", state.name());
            return t;
        }

        static Reservoir load(CompoundTag t) {
            Reservoir r = new Reservoir();
            r.dam = BlockPos.of(t.getLong("Dam"));
            r.seed = BlockPos.of(t.getLong("Seed"));
            r.base = t.getInt("Base");
            r.level = t.getInt("Level");
            r.crest = t.getInt("Crest");
            r.below = t.getInt("Below");
            r.fx = t.getInt("Fx");
            r.fz = t.getInt("Fz");
            r.owed = t.getDouble("Owed");
            r.inflow = t.getDouble("Inflow");
            try {
                r.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException e) {
                r.state = State.FULL;
            }
            return r;
        }
    }

    static final class Store extends SavedData {
        final List<Reservoir> all = new ArrayList<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            for (Tag t : tag.getList("Reservoirs", Tag.TAG_COMPOUND)) s.all.add(Reservoir.load((CompoundTag) t));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Reservoir r : all) list.add(r.save());
            tag.put("Reservoirs", list);
            return tag;
        }
    }

    private static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store::load, Store::new, "fts_geology_reservoirs");
    }

    /** Blocks set in rivers, to the tick the wall there is read. */
    private static final Long2LongOpenHashMap PENDING = new Long2LongOpenHashMap();
    private static long formed, filled, broke;

    public static void clear() {
        PENDING.clear();
        formed = filled = broke = 0;
    }

    private static boolean on(Level level) {
        return GeyserConfig.DAMS.get() && Level.OVERWORLD.equals(level.dimension())
                && !com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.active();
    }

    private static boolean riverWater(FluidState f) {
        return f.getType() instanceof RiverWaterFluid;
    }

    // === A block set in a river ===============================================

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !on(level) || !(event.getEntity() instanceof Player)) return;
        BlockState was = event.getBlockSnapshot().getReplacedBlock();
        BlockPos pos = event.getPos();
        boolean inRiver = riverWater(was.getFluidState());
        if (!inRiver) {
            for (Direction d : Direction.Plane.HORIZONTAL) inRiver |= riverWater(level.getFluidState(pos.relative(d)));
        }
        if (inRiver) PENDING.put(pos.asLong(), level.getGameTime() + READ_AFTER);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || !on(level)) return;
        long now = level.getGameTime();
        if (!PENDING.isEmpty() && now % 20 == 0) {
            LongArrayList due = new LongArrayList();
            for (var e : PENDING.long2LongEntrySet()) if (now >= e.getLongValue()) due.add(e.getLongKey());
            for (long k : due) {
                PENDING.remove(k);
                BlockPos p = BlockPos.of(k);
                if (com.jeladastudios.ftsgeology.util.Loaded.at(level, p) && !claimed(level, p)) read(level, p);
            }
        }
        if (now % EVERY != 0) return;
        Store s = level.getDataStorage().get(Store::load, "fts_geology_reservoirs");
        if (s == null || s.all.isEmpty()) return;
        s.all.removeIf(r -> {
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, r.dam) || !com.jeladastudios.ftsgeology.util.Loaded.at(level, r.seed)) return false;
            boolean gone = step(level, r);
            s.setDirty();
            return gone;
        });
    }

    /** A dam already known near this block: a wall is read once, not again for every block of it. */
    private static boolean claimed(ServerLevel level, BlockPos p) {
        Store s = level.getDataStorage().get(Store::load, "fts_geology_reservoirs");
        if (s == null) return false;
        for (Reservoir r : s.all) if (r.dam.distManhattan(p) <= 24) return true;
        return false;
    }

    // === Reading a wall ======================================================

    /**
     * Whether what a player built round {@code at} cuts the river in two; if it does, the side upstream becomes a
     * reservoir. Only built blocks make the wall -- the banks it is set against are ground, and as low as they may be,
     * that is for the filling to find -- and the river's surface is looked for on each side at its own height, since a
     * river running downhill stands higher above a dam than below it.
     */
    private static void read(ServerLevel level, BlockPos at) {
        // The river's surface here: the highest river water beside the wall, and the way it runs.
        int surface = Integer.MIN_VALUE;
        Vec3 run = Vec3.ZERO;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    FluidState f = level.getFluidState(p);
                    if (!riverWater(f) || !f.isSource()) continue;
                    if (p.getY() > surface) surface = p.getY();
                    if (f.hasProperty(RiverWaterFluid.FLOW)) run = run.add(RiverWaterFluid.way(f.getValue(RiverWaterFluid.FLOW)));
                }
            }
        }
        if (surface == Integer.MIN_VALUE || run.lengthSqr() < 1e-6) return;
        // A dam of earth or rock is of natural blocks: what a player set there is known by the chunk's record of it.
        LongOpenHashSet built = new LongOpenHashSet();
        for (int cx = (at.getX() - 24) >> 4; cx <= (at.getX() + 24) >> 4; cx++) {
            for (int cz = (at.getZ() - 24) >> 4; cz <= (at.getZ() + 24) >> 4; cz++) {
                built.addAll(com.jeladastudios.ftsgeology.quake.PlayerBuilt.inChunk(level, cx, cz));
            }
        }
        // The wall at the surface: the built blocks there joined to the one set.
        LongOpenHashSet wall = new LongOpenHashSet();
        LongArrayFIFOQueue todo = new LongArrayFIFOQueue();
        BlockPos start = new BlockPos(at.getX(), surface, at.getZ());
        if (!wallBlock(level, start, built)) return;
        todo.enqueue(start.asLong());
        wall.add(start.asLong());
        List<BlockPos> touching = new ArrayList<>();
        LongOpenHashSet looked = new LongOpenHashSet();
        while (!todo.isEmpty() && wall.size() < 512) {
            BlockPos p = BlockPos.of(todo.dequeueLong());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) return;
                if (wall.contains(n.asLong())) continue;
                if (wallBlock(level, n, built)) {
                    if (n.distManhattan(start) <= 32) {
                        wall.add(n.asLong());
                        todo.enqueue(n.asLong());
                    }
                    continue;
                }
                // River water beside the wall, at the surface or, where the river stands lower on this side, under it.
                if (!looked.add(n.asLong())) continue;
                for (int y = n.getY(); y >= n.getY() - 3; y--) {
                    BlockPos q = new BlockPos(n.getX(), y, n.getZ());
                    BlockState s = level.getBlockState(q);
                    if (riverWater(s.getFluidState())) {
                        touching.add(q);
                        break;
                    }
                    if (solid(s)) break;
                }
            }
        }
        if (touching.isEmpty()) return;
        // The water on each side, followed at the lower side's level, where the wall has to cut the river too.
        int low = Integer.MAX_VALUE;
        for (BlockPos t : touching) low = Math.min(low, t.getY());
        List<LongOpenHashSet> sides = new ArrayList<>();
        for (BlockPos t : touching) {
            BlockPos l = new BlockPos(t.getX(), low, t.getZ());
            if (!riverWater(level.getFluidState(l))) continue;
            boolean known = false;
            for (LongOpenHashSet side : sides) known |= side.contains(l.asLong());
            if (!known) sides.add(surfaceFrom(level, l, start));
        }
        if (sides.size() < 2) return;              // the water goes round: no dam
        // Upstream is the side the water comes from: against the way it runs.
        Vec3 way = run.normalize();
        LongOpenHashSet up = null, down = null;
        double upDot = Double.MAX_VALUE, downDot = -Double.MAX_VALUE;
        for (LongOpenHashSet side : sides) {
            double sx = 0, sz = 0;
            for (long k : side) {
                sx += BlockPos.getX(k) - start.getX();
                sz += BlockPos.getZ(k) - start.getZ();
            }
            double dot = (sx * way.x + sz * way.z) / side.size();
            if (dot < upDot) {
                upDot = dot;
                up = side;
            }
            if (dot > downDot) {
                downDot = dot;
                down = side;
            }
        }
        if (up == null || down == null || up == down) return;
        // Each side's surface: the top of its water along the wall.
        int upTop = Integer.MIN_VALUE, downTop = Integer.MIN_VALUE, width = 0;
        BlockPos seed = null;
        for (BlockPos t : touching) {
            long l = BlockPos.asLong(t.getX(), low, t.getZ());
            BlockPos top = t;
            while (top.getY() < t.getY() + 32 && riverWater(level.getFluidState(top.above()))) top = top.above();
            if (up.contains(l)) {
                width++;
                if (top.getY() > upTop) {
                    upTop = top.getY();
                    seed = top;
                }
            } else if (down.contains(l)) {
                downTop = Math.max(downTop, top.getY());
            }
        }
        if (seed == null || downTop == Integer.MIN_VALUE) return;
        Reservoir r = new Reservoir();
        r.dam = start;
        r.seed = seed;
        r.base = r.level = upTop;
        r.below = Math.min(downTop, upTop);
        r.fx = (int) Math.signum(Math.round(way.x));
        r.fz = (int) Math.signum(Math.round(way.z));
        if (r.fx == 0 && r.fz == 0) r.fx = 1;
        r.crest = crest(level, wall, surface);
        if (r.crest <= r.base) return;             // no higher than the water: nothing to hold
        r.inflow = 2.0 + 1.5 * Math.max(1, width);
        store(level).all.add(r);
        store(level).setDirty();
        formed++;
        tell(level, r.dam, Component.translatable("message.fts_geology.dam.formed", r.crest - r.base).withStyle(ChatFormatting.AQUA));
        com.jeladastudios.ftsgeology.util.Diagnostics.info("dam at {}: a reservoir forms upstream, surface {} ({} below), crest {}, "
                + "{} blocks of wall, {} blocks of water a second", r.dam.toShortString(), r.base, r.below, r.crest, wall.size(),
                String.format(Locale.ROOT, "%.1f", r.inflow));
    }

    /** A block of a player's wall: solid, and set by a player (or a block no world makes). */
    private static boolean wallBlock(ServerLevel level, BlockPos p, LongOpenHashSet built) {
        BlockState s = level.getBlockState(p);
        return solid(s) && (built.contains(p.asLong()) || com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s));
    }

    /** The river's surface from a block of it, followed at its level, or null past the reach it is taken to be a river. */
    private static LongOpenHashSet surfaceFrom(ServerLevel level, BlockPos from, BlockPos dam) {
        LongOpenHashSet out = new LongOpenHashSet();
        LongArrayFIFOQueue todo = new LongArrayFIFOQueue();
        out.add(from.asLong());
        todo.enqueue(from.asLong());
        while (!todo.isEmpty() && out.size() < SIDE_MOST) {
            BlockPos p = BlockPos.of(todo.dequeueLong());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (Math.abs(n.getX() - dam.getX()) > REACH || Math.abs(n.getZ() - dam.getZ()) > REACH) continue;
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) continue;
                if (riverWater(level.getFluidState(n)) && out.add(n.asLong())) todo.enqueue(n.asLong());
            }
        }
        return out;
    }

    /** The lowest top of the wall across the river: the water can stand no higher. */
    private static int crest(ServerLevel level, LongOpenHashSet wall, int surface) {
        int crest = Integer.MAX_VALUE;
        for (long k : wall) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            int y = surface;
            while (y < surface + 32 && solid(level.getBlockState(new BlockPos(x, y + 1, z)))) y++;
            crest = Math.min(crest, y);
        }
        return crest == Integer.MAX_VALUE ? surface : crest;
    }

    private static boolean solid(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced();
    }

    // === Filling, holding, breaking ===========================================

    /** One look at a reservoir; true once it is gone. */
    private static boolean step(ServerLevel level, Reservoir r) {
        if (r.state == State.DRAINING) return drain(level, r);
        if (!solid(level.getBlockState(r.dam))) {
            // The wall is gone from under it: it runs out.
            r.state = State.DRAINING;
            return false;
        }
        if (holds(level, r)) {
            if (r.state == State.FILLING) fill(level, r);
            return false;
        }
        breach(level, r);
        return false;
    }

    /** A layer at a time, as fast as the river brings water, to the top of the wall or the lowest gap in the valley. */
    private static void fill(ServerLevel level, Reservoir r) {
        r.owed = Math.min(LAYER_MOST, r.owed + r.inflow * EVERY / 20.0 * (level.isRaining() ? 1.5 : 1.0));
        int y = r.level + 1;
        if (y > r.crest) {
            full(level, r, "crest");
            return;
        }
        LongOpenHashSet layer = layer(level, r, y);
        if (layer == UNLOADED) return;                 // part of the valley not loaded: wait for it
        if (layer == null) {
            full(level, r, "spill");
            return;
        }
        if (r.owed < layer.size()) return;
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState();
        for (long k : layer) level.setBlock(BlockPos.of(k), water, Block.UPDATE_CLIENTS);
        r.owed -= layer.size();
        r.level = y;
    }

    private static void full(ServerLevel level, Reservoir r, String why) {
        r.state = State.FULL;
        r.owed = 0;
        filled++;
        tell(level, r.dam, Component.translatable("message.fts_geology.dam.full", r.level - r.base).withStyle(ChatFormatting.AQUA));
        com.jeladastudios.ftsgeology.util.Diagnostics.info("dam at {}: reservoir full at {} ({} over the river, {})", r.dam.toShortString(),
                r.level, r.level - r.base, why);
    }

    /**
     * The next layer of the reservoir, at {@code y}: open cells over the water or over ground, joined to the cells over
     * the reservoir's water, as far as the valley holds them. Null where the water would get out: over a drop, too far,
     * or past the dam. {@link #UNLOADED} where it reaches ground not loaded.
     */
    private static LongOpenHashSet layer(ServerLevel level, Reservoir r, int y) {
        LongOpenHashSet out = new LongOpenHashSet();
        LongArrayFIFOQueue todo = new LongArrayFIFOQueue();
        // From the water under the new layer: the reservoir's top, found from its seed at its level.
        BlockPos seedTop = new BlockPos(r.seed.getX(), y - 1, r.seed.getZ());
        LongOpenHashSet top = new LongOpenHashSet();
        LongArrayFIFOQueue find = new LongArrayFIFOQueue();
        if (!riverWater(level.getFluidState(seedTop))) {
            // The seed column's water is not at the top: look for any reservoir water at the level round the seed.
            boolean found = false;
            for (int dx = -8; dx <= 8 && !found; dx++) {
                for (int dz = -8; dz <= 8 && !found; dz++) {
                    BlockPos p = seedTop.offset(dx, 0, dz);
                    if (riverWater(level.getFluidState(p))) {
                        seedTop = p;
                        found = true;
                    }
                }
            }
            if (!found) return null;
        }
        top.add(seedTop.asLong());
        find.enqueue(seedTop.asLong());
        while (!find.isEmpty() && top.size() < LAYER_MOST) {
            BlockPos p = BlockPos.of(find.dequeueLong());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) return UNLOADED;
                if (riverWater(level.getFluidState(n)) && top.add(n.asLong())) find.enqueue(n.asLong());
            }
        }
        for (long k : top) {
            BlockPos over = BlockPos.of(k).above();
            if (open(level.getBlockState(over)) && out.add(over.asLong())) todo.enqueue(over.asLong());
        }
        while (!todo.isEmpty()) {
            if (out.size() > LAYER_MOST) return null;
            BlockPos p = BlockPos.of(todo.dequeueLong());
            if (Math.abs(p.getX() - r.dam.getX()) > REACH || Math.abs(p.getZ() - r.dam.getZ()) > REACH) return null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (out.contains(n.asLong())) continue;
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) return UNLOADED;
                BlockState s = level.getBlockState(n);
                if (!open(s)) continue;
                // Water over a drop runs away: the valley does not hold it here.
                BlockState under = level.getBlockState(n.below());
                if (!solid(under) && !riverWater(under.getFluidState())) return null;
                out.add(n.asLong());
                todo.enqueue(n.asLong());
            }
        }
        return out;
    }

    /** A layer that reaches ground not loaded: looked at again once it is. */
    private static final LongOpenHashSet UNLOADED = new LongOpenHashSet();

    private static boolean open(BlockState s) {
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }

    /**
     * Whether the dam holds the water behind it: thick enough, for what it is made of, for the height of the water, and
     * an earth bank not overtopped. A dam that does not hold is not broken at once: each look it may go, the likelier
     * the further short it falls; meanwhile it seeps and creaks.
     */
    private static boolean holds(ServerLevel level, Reservoir r) {
        int head = r.level - r.below;
        if (head <= 0) return true;
        int mid = r.below + Math.max(1, head / 2);
        BlockPos at = new BlockPos(r.dam.getX(), mid, r.dam.getZ());
        // Through the dam along the way the river runs, from the face the reservoir's water stands against (up the river
        // from here, past any gap cut in the wall) to the first opening below it.
        BlockPos face = at;
        for (int i = 0; i < 64; i++) {
            BlockPos next = face.offset(-r.fx, 0, -r.fz);
            if (riverWater(level.getFluidState(next))) break;
            face = next;
        }
        if (!riverWater(level.getFluidState(face.offset(-r.fx, 0, -r.fz)))) {
            face = at;
            for (int i = 0; i < 64 && solid(level.getBlockState(face.offset(-r.fx, 0, -r.fz))); i++) face = face.offset(-r.fx, 0, -r.fz);
        }
        while (!solid(level.getBlockState(face)) && face.distManhattan(at) > 0) face = face.offset(r.fx, 0, r.fz);
        int thick = 0;
        for (BlockPos q = face; thick < 64 && solid(level.getBlockState(q)); q = q.offset(r.fx, 0, r.fz)) thick++;
        double need = strength(level.getBlockState(at)) * head;
        boolean earth = strength(level.getBlockState(at)) >= EARTH;
        boolean overtopped = earth && r.level >= r.crest;
        if (thick >= need && !overtopped) return true;
        double shortfall = Math.max(overtopped ? 0.5 : 0.0, (need - thick) / need);
        long now = level.getGameTime();
        if (now - r.lastWarn > 400) {
            r.lastWarn = now;
            tell(level, r.dam, Component.translatable("message.fts_geology.dam.strain", r.level - r.below, thick,
                    (int) Math.ceil(need)).withStyle(ChatFormatting.GOLD));
            com.jeladastudios.ftsgeology.util.Diagnostics.info("dam at {}: straining, {} blocks of water on {} of wall where {} would hold",
                    r.dam.toShortString(), r.level - r.below, thick, (int) Math.ceil(need));
        }
        level.sendParticles(ParticleTypes.FALLING_WATER, r.dam.getX() + 0.5 + r.fx * (thick / 2.0 + 0.6), mid + 0.5,
                r.dam.getZ() + 0.5 + r.fz * (thick / 2.0 + 0.6), 6, 0.6, 0.6, 0.6, 0.0);
        if (level.random.nextInt(3) == 0) level.playSound(null, at, SoundEvents.GRAVEL_HIT, SoundSource.BLOCKS, 1.5F, 0.5F);
        return level.random.nextDouble() >= 0.08 * shortfall;
    }

    /** How thick a dam of this must be, times the water's height. */
    private static final double EARTH = 3.0;

    private static double strength(BlockState s) {
        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (name.contains("concrete") && !name.contains("powder") || s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN)
                || name.contains("reinforced") || s.is(Blocks.IRON_BLOCK)) {
            return 0.6;
        }
        if (s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.MUD)
                || s.is(Blocks.PACKED_MUD) || s.is(Blocks.SNOW_BLOCK)) {
            return EARTH;
        }
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOOL)) return 1.8;
        return 1.0;                                   // stone, brick, cobble and the rest: masonry
    }

    /** A gap torn through the dam, three blocks wide, from its top to the river below; then it runs out. */
    private static void breach(ServerLevel level, Reservoir r) {
        int ax = r.fz != 0 ? 1 : 0, az = r.fx != 0 ? 1 : 0;       // across the river
        // What a player built there, whatever it is made of: a dam of earth or rock is of natural blocks.
        LongOpenHashSet built = new LongOpenHashSet(com.jeladastudios.ftsgeology.quake.PlayerBuilt.inChunk(level, r.dam.getX() >> 4, r.dam.getZ() >> 4));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = r.dam.relative(d, 16);
            built.addAll(com.jeladastudios.ftsgeology.quake.PlayerBuilt.inChunk(level, n.getX() >> 4, n.getZ() >> 4));
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int a = -1; a <= 1; a++) {
            for (int y = r.below; y <= r.crest; y++) {
                // Through the whole thickness along the river.
                for (int t = -8; t <= 8; t++) {
                    m.set(r.dam.getX() + a * ax + t * r.fx, y, r.dam.getZ() + a * az + t * r.fz);
                    BlockState s = level.getBlockState(m);
                    if (!solid(s) || s.is(Blocks.BEDROCK) || !com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s)
                            && !built.contains(m.asLong())) {
                        continue;
                    }
                    level.destroyBlock(m, level.random.nextInt(3) == 0);
                }
            }
        }
        level.sendParticles(ParticleTypes.CLOUD, r.dam.getX() + 0.5, r.level, r.dam.getZ() + 0.5, 40, 1.5, 1.0, 1.5, 0.05);
        level.playSound(null, r.dam, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 2.0F, 0.6F);
        tell(level, r.dam, Component.translatable("message.fts_geology.dam.broke").withStyle(ChatFormatting.RED));
        com.jeladastudios.ftsgeology.util.Diagnostics.info("dam at {}: broke with {} blocks of water behind it", r.dam.toShortString(), r.level - r.below);
        broke++;
        r.state = State.DRAINING;
    }

    /** The reservoir runs out through the gap, its top layer at a time, down to the river it was. */
    private static boolean drain(ServerLevel level, Reservoir r) {
        if (r.level <= r.base) return true;
        LongOpenHashSet layer = new LongOpenHashSet();
        LongArrayFIFOQueue todo = new LongArrayFIFOQueue();
        // Any of its water at the top level near the seed or the dam will do to start from.
        for (BlockPos from : new BlockPos[]{r.seed, r.dam.offset(-r.fx * 2, 0, -r.fz * 2)}) {
            for (int dx = -8; dx <= 8 && layer.isEmpty(); dx++) {
                for (int dz = -8; dz <= 8 && layer.isEmpty(); dz++) {
                    BlockPos p = new BlockPos(from.getX() + dx, r.level, from.getZ() + dz);
                    if (com.jeladastudios.ftsgeology.util.Loaded.at(level, p) && riverWater(level.getFluidState(p))) {
                        layer.add(p.asLong());
                        todo.enqueue(p.asLong());
                    }
                }
            }
        }
        while (!todo.isEmpty() && layer.size() < LAYER_MOST) {
            BlockPos p = BlockPos.of(todo.dequeueLong());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) continue;
                if (riverWater(level.getFluidState(n)) && layer.add(n.asLong())) todo.enqueue(n.asLong());
            }
        }
        for (long k : layer) level.setBlock(BlockPos.of(k), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.sendParticles(ParticleTypes.SPLASH, r.dam.getX() + 0.5 + r.fx * 3, r.below + 1, r.dam.getZ() + 0.5 + r.fz * 3,
                60, 2.0, 0.5, 2.0, 0.2);
        r.level--;
        return r.level <= r.base;
    }

    private static void tell(ServerLevel level, BlockPos at, Component msg) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(at) <= 96 * 96) p.sendSystemMessage(msg);
        }
    }

    public static String summary() {
        return String.format(Locale.ROOT, "reservoirs: %d formed, %d filled, %d dams broke", formed, filled, broke);
    }

    public static boolean any() {
        return formed + filled + broke > 0;
    }
}
