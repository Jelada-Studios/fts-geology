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

        @Override
        public CompoundTag save(CompoundTag tag) {
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

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % 20 != 7) return;
        Store store = level.getDataStorage().get(t -> Store.load(level, t), "fts_geology_boil_scars");
        if (store == null || store.scars.isEmpty()) return;
        long now = level.getGameTime();
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
                // Only the boil's own sand and its water go back; anything put there since stays.
                if (now2.is(Blocks.SAND) || (now2.is(Blocks.WATER) && level.getFluidState(p).isSource())) {
                    level.setBlock(p, s.was.get(i), Block.UPDATE_ALL);
                }
            }
            it.remove();
            healed++;
        }
        if (healed > 0) {
            store.setDirty();
            com.jeladastudios.ftsgeology.util.Diagnostics.info("boil scars: {} healed, {} still to heal", healed, store.scars.size());
        }
    }
}
