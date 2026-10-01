package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gas cells of one chunk, saved with the chunk. Only cells that differ from normal air are
 * stored; everything else is implicitly 1 atm of air.
 */
public class GasChunkData implements ICapabilitySerializable<CompoundTag> {
    public static final Capability<GasChunkData> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {
    });

    public final Int2ObjectOpenHashMap<GasMix> cells = new Int2ObjectOpenHashMap<>();
    /** The cells still settling, which the sweeps go through; the rest sleep till something stirs them. */
    public final it.unimi.dsi.fastutil.ints.IntOpenHashSet awake = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    public final LevelChunk chunk;
    public boolean seeded;
    private final LazyOptional<GasChunkData> holder = LazyOptional.of(() -> this);

    public GasChunkData(LevelChunk chunk) {
        this.chunk = chunk;
    }

    public static int key(int x, int y, int z) {
        return ((y + 2048) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public static int keyY(int key) {
        return (key >>> 8) - 2048;
    }

    public static int keyLocalX(int key) {
        return key & 15;
    }

    public static int keyLocalZ(int key) {
        return (key >> 4) & 15;
    }

    public void markDirty() {
        chunk.setUnsaved(true);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return CAPABILITY.orEmpty(cap, holder);
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("seeded", seeded);
        ListTag ids = new ListTag();
        for (Gas g : Gas.VALUES) ids.add(StringTag.valueOf(g.id));
        tag.put("gases", ids);
        int n = cells.size();
        int[] keys = new int[n];
        int[] vals = new int[n * Gas.COUNT];
        int i = 0;
        for (Int2ObjectMap.Entry<GasMix> e : cells.int2ObjectEntrySet()) {
            keys[i] = e.getIntKey();
            double[] m = e.getValue().m;
            for (int g = 0; g < Gas.COUNT; g++) vals[i * Gas.COUNT + g] = Float.floatToRawIntBits((float) m[g]);
            i++;
        }
        tag.putIntArray("keys", keys);
        tag.putIntArray("vals", vals);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        cells.clear();
        awake.clear();
        seeded = tag.getBoolean("seeded");
        ListTag ids = tag.getList("gases", Tag.TAG_STRING);
        int stored = ids.size();
        if (stored == 0) return;
        int[] map = new int[stored];
        for (int s = 0; s < stored; s++) {
            Gas g = Gas.byId(ids.getString(s));
            map[s] = g == null ? -1 : g.ordinal();
        }
        int[] keys = tag.getIntArray("keys");
        int[] vals = tag.getIntArray("vals");
        if (vals.length != keys.length * stored) return;
        for (int i = 0; i < keys.length; i++) {
            GasMix mix = new GasMix();
            for (int s = 0; s < stored; s++) {
                if (map[s] < 0) continue;
                float v = Float.intBitsToFloat(vals[i * stored + s]);
                if (v > 0 && Float.isFinite(v)) mix.m[map[s]] = v;
            }
            if (mix.total() > 1e-6) {
                cells.put(keys[i], mix);
                awake.add(keys[i]);
            }
        }
    }

    public void invalidate() {
        holder.invalidate();
    }
}
