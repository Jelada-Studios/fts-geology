package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity;
import com.jeladastudios.ftsgeology.blockentity.WeatherTerminalBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The stations of a world as one network: every weather terminal and seismograph, each with a name, who set it up,
 * and what its screen last showed. From any terminal a player sees them all, listed and on a little map, and opens any
 * of them: live where its chunk is loaded, else as it last read, and when. Kept with the world, per dimension.
 */
public final class StationNetwork {

    private StationNetwork() {}

    public enum Kind { TERMINAL, SEISMOGRAPH }

    /** The most stations listed on a screen, the nearest first. */
    private static final int LISTED = 64;
    /** The longest name. */
    public static final int NAME = 32;

    /** One station: what it is, its name ("" for none yet), who set it up, and what its screen last showed and when. */
    static final class Entry {
        Kind kind;
        String name = "";
        UUID owner;
        CompoundTag shown;
        long shownAt;
    }

    static final class Store extends SavedData {
        final Long2ObjectOpenHashMap<Entry> all = new Long2ObjectOpenHashMap<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            for (Tag t : tag.getList("Stations", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Entry e = new Entry();
                try {
                    e.kind = Kind.valueOf(c.getString("Kind"));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                e.name = c.getString("Name");
                if (c.hasUUID("Owner")) e.owner = c.getUUID("Owner");
                if (c.contains("Shown")) e.shown = c.getCompound("Shown");
                e.shownAt = c.getLong("ShownAt");
                s.all.put(c.getLong("Pos"), e);
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (var it = all.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var en = it.next();
                Entry e = en.getValue();
                CompoundTag c = new CompoundTag();
                c.putLong("Pos", en.getLongKey());
                c.putString("Kind", e.kind.name());
                c.putString("Name", e.name);
                if (e.owner != null) c.putUUID("Owner", e.owner);
                if (e.shown != null) c.put("Shown", e.shown);
                c.putLong("ShownAt", e.shownAt);
                list.add(c);
            }
            tag.put("Stations", list);
            return tag;
        }
    }

    private static Store of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store::load, Store::new, "fts_geology_stations");
    }

    /** A station set up: by whom, and named as the item it was set up from was, if it was named on an anvil. */
    public static void placed(ServerLevel level, BlockPos pos, Kind kind, String name, UUID owner) {
        Store s = of(level);
        Entry e = new Entry();
        e.kind = kind;
        e.name = clean(name);
        e.owner = owner;
        s.all.put(pos.asLong(), e);
        s.setDirty();
    }

    /** A station is there (loaded): one set up before the network was kept is taken in, unnamed and nobody's. */
    public static void present(ServerLevel level, BlockPos pos, Kind kind) {
        Store s = of(level);
        Entry e = s.all.get(pos.asLong());
        if (e != null && e.kind == kind) return;
        e = new Entry();
        e.kind = kind;
        s.all.put(pos.asLong(), e);
        s.setDirty();
    }

    public static void removed(ServerLevel level, BlockPos pos) {
        Store s = of(level);
        if (s.all.remove(pos.asLong()) != null) s.setDirty();
    }

    /** What a station's screen shows now, kept for when its chunk is not loaded. */
    public static void shown(ServerLevel level, BlockPos pos, CompoundTag data) {
        Store s = of(level);
        Entry e = s.all.get(pos.asLong());
        if (e == null) return;
        e.shown = data.copy();
        e.shownAt = level.getGameTime();
        s.setDirty();
    }

    /** A station's name, "" where it has none. */
    public static String name(ServerLevel level, BlockPos pos) {
        Entry e = of(level).all.get(pos.asLong());
        return e == null ? "" : e.name;
    }

    /** Renames a station: whoever set it up may, an operator may, and anyone may name one nobody owns. */
    public static boolean rename(ServerLevel level, ServerPlayer player, BlockPos pos, String name) {
        Store s = of(level);
        Entry e = s.all.get(pos.asLong());
        if (e == null) return false;
        if (e.owner != null && !e.owner.equals(player.getUUID()) && !player.hasPermissions(2)) return false;
        e.name = clean(name);
        s.setDirty();
        return true;
    }

    /** Renames a station whoever set it up: for the console. */
    public static boolean renameAny(ServerLevel level, BlockPos pos, String name) {
        Store s = of(level);
        Entry e = s.all.get(pos.asLong());
        if (e == null) return false;
        e.name = clean(name);
        s.setDirty();
        return true;
    }

    /** Whether a player may rename this station. */
    public static boolean mayRename(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Entry e = of(level).all.get(pos.asLong());
        return e != null && (e.owner == null || e.owner.equals(player.getUUID()) || player.hasPermissions(2));
    }

    private static String clean(String name) {
        if (name == null) return "";
        String n = name.strip().replaceAll("[\\p{Cntrl}\\u00a7]", "");
        return n.length() > NAME ? n.substring(0, NAME) : n;
    }

    /**
     * The stations for a screen at {@code from}, the nearest first: each its place, kind, name, whether it is live (its
     * chunk loaded) and how long since it last showed anything.
     */
    public static ListTag list(ServerLevel level, BlockPos from) {
        List<long[]> found = new ArrayList<>();
        Store s = of(level);
        for (var it = s.all.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var en = it.next();
            BlockPos p = BlockPos.of(en.getLongKey());
            found.add(new long[]{en.getLongKey(), (long) p.distSqr(from)});
        }
        found.sort(Comparator.comparingLong(a -> a[1]));
        ListTag out = new ListTag();
        long now = level.getGameTime();
        for (int i = 0; i < Math.min(LISTED, found.size()); i++) {
            BlockPos p = BlockPos.of(found.get(i)[0]);
            Entry e = s.all.get(found.get(i)[0]);
            CompoundTag c = new CompoundTag();
            c.putInt("X", p.getX());
            c.putInt("Y", p.getY());
            c.putInt("Z", p.getZ());
            c.putString("Kind", e.kind.name());
            c.putString("Name", e.name);
            c.putBoolean("Live", com.jeladastudios.ftsgeology.util.Loaded.at(level, p));
            c.putLong("Age", e.shown == null ? -1 : Math.max(0, now - e.shownAt));
            out.add(c);
        }
        return out;
    }

    /**
     * What a screen opened by a player needs besides the station's own: its name, whether they may rename it (only at
     * the station itself), and, through a terminal ({@code via}), the network's stations round that terminal and which
     * terminal it is seen through.
     */
    public static CompoundTag decorate(ServerLevel level, ServerPlayer player, @org.jetbrains.annotations.Nullable BlockPos via,
                                       BlockPos target, CompoundTag data) {
        data.putString("Name", name(level, target));
        data.putBoolean("MayRename", (via == null || via.equals(target)) && mayRename(level, player, target));
        if (via != null) {
            data.put("Stations", list(level, via));
            data.putLong("Via", via.asLong());
        }
        return data;
    }

    /** Whether a terminal on the network stands within {@code reach} blocks of a place, and is there. */
    public static boolean terminalNear(ServerLevel level, BlockPos at, int reach) {
        double r2 = (double) reach * reach;
        for (var it = of(level).all.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getValue().kind != Kind.TERMINAL) continue;
            BlockPos p = BlockPos.of(en.getLongKey());
            if (p.distSqr(at) > r2) continue;
            if (com.jeladastudios.ftsgeology.util.Loaded.at(level, p) && level.getBlockEntity(p) instanceof WeatherTerminalBlockEntity) return true;
        }
        return false;
    }

    /**
     * A station's screen as seen from afar: live where its chunk is loaded, else as it last showed, marked with how long
     * ago ("Stale", game ticks). Null for no station there.
     */
    public static CompoundTag view(ServerLevel level, BlockPos pos) {
        Entry e = of(level).all.get(pos.asLong());
        if (e == null) return null;
        CompoundTag data = null;
        if (com.jeladastudios.ftsgeology.util.Loaded.at(level, pos)) {
            var be = level.getBlockEntity(pos);
            if (be instanceof WeatherTerminalBlockEntity t) data = t.data(level);
            else if (be instanceof SeismographBlockEntity st) data = st.data(level);
        }
        if (data == null) {
            if (e.shown == null) {
                data = new CompoundTag();
                data.putBoolean("Seismo", e.kind == Kind.SEISMOGRAPH);
                data.putBoolean("Empty", true);
            } else {
                data = e.shown.copy();
            }
            data.putLong("Stale", e.shown == null ? -1 : Math.max(0, level.getGameTime() - e.shownAt));
        }
        data.putString("Name", e.name);
        return data;
    }
}
