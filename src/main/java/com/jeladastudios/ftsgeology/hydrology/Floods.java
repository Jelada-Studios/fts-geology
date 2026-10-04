package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Water that stands for a while where it does not belong and then goes: the wave out of a broken dam, a river over its
 * banks in a long rain.
 *
 * <p>It is laid as the rivers' own still water, so it does not run off or spread (and another mod's water is not set
 * moving by it), a layer at a time up from the water it rose from, over open ground only: into the air of a valley and
 * over its grass, never through a wall. It is taken back from the top a layer at a time, and where it stood on soil it
 * leaves some of it mud, which dries as the mud of a hollow does (with {@code soilWaterChangesGround}; see
 * {@link SoilWater#floodMud}). The ground is not moved: a flood leaves a little mud and nothing else.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Floods {

    private Floods() {}

    /** Share of the ground a flood stood on that it leaves as mud. */
    private static final double MUD_SHARE = 0.35;

    static final class Surge {
        /** The water laid, a layer each, from the bottom. */
        final List<LongArrayList> layers = new ArrayList<>();
        /** When its top layer goes, and the ticks between one layer going and the next. */
        long recedeAt;
        int every;
        String why = "";
        /** What laid it, for a flood that goes on while its cause lasts (see {@link #extend}); empty for one that does not. */
        String key = "";

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            ListTag l = new ListTag();
            for (LongArrayList layer : layers) l.add(new LongArrayTag(layer.toLongArray()));
            t.put("Layers", l);
            t.putLong("RecedeAt", recedeAt);
            t.putInt("Every", every);
            t.putString("Why", why);
            t.putString("Key", key);
            return t;
        }

        static Surge load(CompoundTag t) {
            Surge s = new Surge();
            for (Tag layer : t.getList("Layers", Tag.TAG_LONG_ARRAY)) s.layers.add(new LongArrayList(((LongArrayTag) layer).getAsLongArray()));
            s.recedeAt = t.getLong("RecedeAt");
            s.every = t.getInt("Every");
            s.why = t.getString("Why");
            s.key = t.getString("Key");
            return s;
        }
    }

    static final class Store extends SavedData {
        final List<Surge> all = new ArrayList<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            for (Tag t : tag.getList("Surges", Tag.TAG_COMPOUND)) s.all.add(Surge.load((CompoundTag) t));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Surge s : all) list.add(s.save());
            tag.put("Surges", list);
            return tag;
        }
    }

    private static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store::load, Store::new, "fts_geology_floods");
    }

    private static long laid, gone, mud, surges;

    /**
     * Whether a block is water a flood, a river's seasonal high water or a rain pond laid and will take back. Server
     * thread.
     */
    public static boolean isFloodWater(ServerLevel level, BlockPos pos) {
        if (!floodWater(level.getBlockState(pos))) return false;
        Store st = level.getDataStorage().get(Store::load, "fts_geology_floods");
        if (st == null) return false;
        long k = pos.asLong();
        for (Surge s : st.all) {
            for (LongArrayList layer : s.layers) if (layer.contains(k)) return true;
        }
        return false;
    }

    public static void clear() {
        laid = gone = mud = surges = 0;
    }

    /** Open to a flood: air, or a plant in the way. */
    static boolean open(BlockState s) {
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty() && !s.hasBlockEntity();
    }

    private static boolean floor(BlockState s) {
        return !s.getFluidState().isEmpty() || !s.isAir() && !s.canBeReplaced();
    }

    /**
     * The layers of water a flood of {@code height} blocks would stand in, over the water at {@code tops} (each the top
     * water block of a column of the river it rises from): up to {@code reach} blocks from {@code centre}, at most
     * {@code most} blocks of water, only where {@code allowed} says. Each layer spreads over open ground that its own
     * floor holds up -- ground, water, or the layer under it -- so it fills a valley's floor and stops at its sides, and
     * never runs down off a drop.
     */
    public static List<LongOpenHashSet> spread(ServerLevel level, List<BlockPos> tops, int height, BlockPos centre, int reach, int most,
                                               java.util.function.LongPredicate allowed) {
        List<LongOpenHashSet> layers = new ArrayList<>();
        LongOpenHashSet under = new LongOpenHashSet();
        int total = 0;
        for (int k = 1; k <= height && total < most; k++) {
            LongOpenHashSet layer = new LongOpenHashSet();
            LongArrayFIFOQueue todo = new LongArrayFIFOQueue();
            for (BlockPos t : tops) {
                BlockPos p = t.above(k);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, p) || !open(level.getBlockState(p)) || !allowed.test(p.asLong())) continue;
                BlockPos b = p.below();
                if (!under.contains(b.asLong()) && !floor(level.getBlockState(b))) continue;
                if (layer.add(p.asLong())) todo.enqueue(p.asLong());
            }
            while (!todo.isEmpty() && total + layer.size() < most) {
                BlockPos p = BlockPos.of(todo.dequeueLong());
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = p.relative(d);
                    long nk = n.asLong();
                    if (layer.contains(nk) || Math.max(Math.abs(n.getX() - centre.getX()), Math.abs(n.getZ() - centre.getZ())) > reach) continue;
                    if (!allowed.test(nk) || !com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) continue;
                    if (!open(level.getBlockState(n))) continue;
                    BlockPos b = n.below();
                    if (!under.contains(b.asLong()) && !floor(level.getBlockState(b))) continue;
                    layer.add(nk);
                    todo.enqueue(nk);
                }
            }
            if (layer.isEmpty()) break;
            total += layer.size();
            layers.add(layer);
            under = layer;
        }
        return layers;
    }

    /**
     * Lays a flood's layers, from the bottom, and keeps them: after {@code holdTicks} its top layer goes, then the next
     * every {@code everyTicks}. Returns the blocks of water laid.
     */
    public static int surge(ServerLevel level, List<LongOpenHashSet> layers, int holdTicks, int everyTicks, String why) {
        return surge(level, layers, holdTicks, everyTicks, why, "");
    }

    /**
     * A flood that lasts while what laid it does, by {@code key}: if one is standing, it is held on {@code holdTicks}
     * more (and nothing is laid); true if there was one.
     */
    public static boolean extend(ServerLevel level, String key, int holdTicks) {
        Store st = level.getDataStorage().get(Store::load, "fts_geology_floods");
        if (st == null) return false;
        for (Surge s : st.all) {
            if (!s.key.equals(key)) continue;
            s.recedeAt = Math.max(s.recedeAt, level.getGameTime() + holdTicks);
            st.setDirty();
            return true;
        }
        return false;
    }

    public static int surge(ServerLevel level, List<LongOpenHashSet> layers, int holdTicks, int everyTicks, String why, String key) {
        if (layers.isEmpty()) return 0;
        long t0 = System.nanoTime();
        Surge s = new Surge();
        s.key = key;
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState();
        int n = 0;
        for (LongOpenHashSet layer : layers) {
            LongArrayList put = new LongArrayList();
            for (long k : layer) {
                BlockPos p = BlockPos.of(k);
                if (!open(level.getBlockState(p))) continue;
                level.setBlock(p, water, Block.UPDATE_CLIENTS);
                put.add(k);
            }
            if (!put.isEmpty()) s.layers.add(put);
            n += put.size();
        }
        if (s.layers.isEmpty()) return 0;
        s.recedeAt = level.getGameTime() + holdTicks;
        s.every = Math.max(20, everyTicks);
        s.why = why;
        Store st = store(level);
        st.all.add(s);
        st.setDirty();
        laid += n;
        surges++;
        com.jeladastudios.ftsgeology.util.Diagnostics.info("flood ({}): {} blocks of water in {} layers, going in {} s ({} ms)", why, n, s.layers.size(), holdTicks / 20,
                (System.nanoTime() - t0) / 1_000_000);
        return n;
    }

    /** Whether a block is a flood's water still standing. */
    private static boolean floodWater(BlockState s) {
        return s.getFluidState().getType() instanceof RiverWaterFluid && s.getFluidState().isSource()
                && s.getFluidState().getValue(RiverWaterFluid.FLOW) == 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || level.getGameTime() % 20 != 0) return;
        Store st = level.getDataStorage().get(Store::load, "fts_geology_floods");
        if (st == null || st.all.isEmpty()) return;
        long now = level.getGameTime();
        st.all.removeIf(s -> {
            if (now < s.recedeAt) return false;
            recede(level, s);
            st.setDirty();
            if (s.layers.isEmpty()) {
                com.jeladastudios.ftsgeology.util.Diagnostics.info("flood ({}) gone", s.why);
                return true;
            }
            return false;
        });
    }

    /** Takes back the top layer of a surge, where its ground is loaded; the rest of it waits. */
    private static void recede(ServerLevel level, Surge s) {
        int i = s.layers.size() - 1;
        LongArrayList top = s.layers.get(i);
        boolean bottom = i == 0;
        LongArrayList left = new LongArrayList();
        for (int j = 0; j < top.size(); j++) {
            long k = top.getLong(j);
            BlockPos p = BlockPos.of(k);
            if (!com.jeladastudios.ftsgeology.util.Loaded.around(level, p)) {
                left.add(k);
                continue;
            }
            if (floodWater(level.getBlockState(p))) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                gone++;
                HydraulicsHooks.moved(level, p.getX() >> 4, p.getZ() >> 4,
                        com.jeladastudios.ftsgeology.api.RiverBlocksChangedEvent.Cause.RECEDE);
            }
            if (bottom && level.random.nextDouble() < MUD_SHARE && SoilWater.floodMud(level, p.below())) mud++;
        }
        if (left.isEmpty()) {
            s.layers.remove(i);
            s.recedeAt = level.getGameTime() + s.every;
        } else {
            s.layers.set(i, left);
        }
    }

    public static String summary() {
        return String.format(Locale.ROOT, "floods: %d surges, %d blocks of water laid, %d taken back, %d blocks of mud left", surges, laid, gone, mud);
    }

    public static boolean any() {
        return surges > 0;
    }

    /** Whether any flood stands in the world now. */
    public static boolean standing(Level level) {
        if (!(level instanceof ServerLevel sl)) return false;
        Store st = sl.getDataStorage().get(Store::load, "fts_geology_floods");
        return st != null && !st.all.isEmpty();
    }
}
