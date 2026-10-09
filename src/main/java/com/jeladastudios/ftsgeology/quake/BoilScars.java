package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * The sand a boil left, and the ground it covered. Most sand boils do not outlast the season: the water goes back into
 * the ground, rain and roots work the sand in, and the field is a field again. Each boil's ground is kept here, with
 * when it heals; then whatever of the boil's sand is still where it was put goes back to what was there before. A boil
 * someone dug or built over is left as it is. What is healing is kept with the world.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class BoilScars {

    private BoilScars() {}

    /** One boil: when it heals, and the blocks it changed with what they were. */
    static final class Scar {
        long due;
        final List<BlockPos> at = new ArrayList<>();
        final List<BlockState> was = new ArrayList<>();
    }

    static final class Store extends SavedData {
        final List<Scar> scars = new ArrayList<>();
        /** Boil vents still holding water, with when they dry. */
        final it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap vents = new it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap();

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag vented = new ListTag();
            for (var e : vents.long2LongEntrySet()) {
                CompoundTag v = new CompoundTag();
                v.putLong("Pos", e.getLongKey());
                v.putLong("Due", e.getLongValue());
                vented.add(v);
            }
            tag.put("Vents", vented);
            ListTag list = new ListTag();
            for (Scar s : scars) {
                CompoundTag t = new CompoundTag();
                t.putLong("Due", s.due);
                ListTag blocks = new ListTag();
                for (int i = 0; i < s.at.size(); i++) {
                    CompoundTag b = new CompoundTag();
                    b.putLong("Pos", s.at.get(i).asLong());
                    b.put("Was", NbtUtils.writeBlockState(s.was.get(i)));
                    blocks.add(b);
                }
                t.put("Blocks", blocks);
                list.add(t);
            }
            tag.put("Scars", list);
            return tag;
        }

        static Store load(ServerLevel level, CompoundTag tag) {
            Store store = new Store();
            for (Tag e : tag.getList("Vents", Tag.TAG_COMPOUND)) {
                CompoundTag v = (CompoundTag) e;
                store.vents.put(v.getLong("Pos"), v.getLong("Due"));
            }
            var blocks = level.holderLookup(Registries.BLOCK);
            for (Tag e : tag.getList("Scars", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) e;
                Scar s = new Scar();
                s.due = t.getLong("Due");
                for (Tag b : t.getList("Blocks", Tag.TAG_COMPOUND)) {
                    CompoundTag c = (CompoundTag) b;
                    s.at.add(BlockPos.of(c.getLong("Pos")));
                    s.was.add(NbtUtils.readBlockState(blocks, c.getCompound("Was")));
                }
                store.scars.add(s);
            }
            return store;
        }
    }

    static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(t -> Store.load(level, t), Store::new, "fts_geology_boil_scars");
    }

    /** Keeps a boil's ground to heal at {@code due}. */
    static void keep(ServerLevel level, List<BlockPos> at, List<BlockState> was, long due) {
        if (at.isEmpty()) return;
        Scar s = new Scar();
        s.due = due;
        s.at.addAll(at);
        s.was.addAll(was);
        Store store = store(level);
        store.scars.add(s);
        store.setDirty();
    }

    /** Keeps a boil's vent, to dry at {@code due}. */
    static void vent(ServerLevel level, BlockPos at, long due) {
        Store store = store(level);
        store.vents.put(at.asLong(), due);
        store.setDirty();
    }

    /** A vent dries: its water goes back into the ground and leaves sand, and any of it that ran out goes too. */
    static void dryVent(ServerLevel level, BlockPos at) {
        if (level.getBlockState(at).is(Blocks.WATER)) takeUp(level, at, Blocks.SAND.defaultBlockState());
        clearSheet(level, at, 2);
    }

    /**
     * Puts a block where water stands, the water taken up first. Put straight into it, a finite water mod pushes the
     * block's water out onto the ground beside it: every boil that dried left its water lying round it.
     */
    private static void takeUp(ServerLevel level, BlockPos at, BlockState with) {
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(at, with, Block.UPDATE_ALL);
    }

    /**
     * Takes up the thin water a boil let run over the ground round it: water that is not a source and not falling,
     * lying open to the sky from a block under the vent's level to a block over it. A finite water mod spread a vent
     * into such a sheet, and nothing took it back up.
     */
    private static void clearSheet(ServerLevel level, BlockPos at, int reach) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m)) continue;
                    if (!level.getBlockState(m).is(Blocks.WATER)) continue;
                    var fluid = level.getFluidState(m);
                    if (fluid.isSource() || fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) continue;
                    if (!level.getBlockState(m.above()).isAir()) continue;
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % 20 != 7) return;
        Store store = level.getDataStorage().get(t -> Store.load(level, t), "fts_geology_boil_scars");
        if (store == null || (store.scars.isEmpty() && store.vents.isEmpty())) return;
        long now = level.getGameTime();
        int dried = 0;
        for (var it = store.vents.long2LongEntrySet().iterator(); it.hasNext() && dried < 64; ) {
            var e = it.next();
            if (e.getLongValue() > now) continue;
            BlockPos p = BlockPos.of(e.getLongKey());
            // Out of the loaded world it waits for the ground to be loaded again.
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, p)) continue;
            dryVent(level, p);
            it.remove();
            dried++;
        }
        if (dried > 0) store.setDirty();
        int healed = 0;
        for (var it = store.scars.iterator(); it.hasNext() && healed < 8; ) {
            Scar s = it.next();
            if (s.due > now) continue;
            boolean loaded = true;
            for (BlockPos p : s.at) {
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, p)) {
                    loaded = false;
                    break;
                }
            }
            // Out of the loaded world it waits for the ground to be loaded again.
            if (!loaded) continue;
            for (int i = 0; i < s.at.size(); i++) {
                BlockPos p = s.at.get(i);
                BlockState now2 = level.getBlockState(p);
                // Only the boil's own sand and its water go back; anything put there since stays. The water at any level:
                // a finite water mod keeps a full block as running water, not a source.
                if (now2.is(Blocks.WATER)) takeUp(level, p, s.was.get(i));
                else if (now2.is(Blocks.SAND)) level.setBlock(p, s.was.get(i), Block.UPDATE_ALL);
            }
            // A boil from before its vent kept its water in may have left a sheet round it: that goes as well.
            clearSheet(level, s.at.get(0), 3);
            it.remove();
            healed++;
        }
        if (healed > 0) {
            store.setDirty();
            com.jeladastudios.ftsgeology.util.Diagnostics.info("boil scars: {} healed, {} still to heal", healed, store.scars.size());
        }
    }
}
