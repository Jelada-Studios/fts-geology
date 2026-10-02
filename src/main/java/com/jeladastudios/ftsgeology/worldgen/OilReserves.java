package com.jeladastudios.ftsgeology.worldgen;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The oil an oil field gives up through its wells, kept with the world so a field runs down. What a field holds is its
 * oil column's share of the dome (the oil between the gas-oil and oil-water contacts), the reservoir's pore space, and
 * the part of that a well can bring up on the field's own pressure -- about a third, as with real primary recovery.
 */
public final class OilReserves extends SavedData {

    /** Pore space of the reservoir, and the share of its oil the field's own pressure brings up. */
    private static final double POROSITY = 0.2, RECOVERY = 0.35;

    private final Long2DoubleOpenHashMap taken = new Long2DoubleOpenHashMap();

    public static OilReserves of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(OilReserves::load, OilReserves::new, "fts_geology_oil_reserves");
    }

    /** Millibuckets a field gives up in all, from the size of its oil column. */
    public static double recoverable(PetroleumFields.Field f) {
        // The dome's top falls as the square of the distance out, so the volume down to a depth h under the crest is
        // pi a b h^2 / (2 closure): the oil lies between the depths of its two contacts.
        double hGas = Math.max(0, f.crest() - f.goc()), hOil = Math.max(hGas, Math.min(f.closure(), f.crest() - f.owc()));
        double volume = Math.PI * f.a() * f.b() * (hOil * hOil - hGas * hGas) / (2.0 * Math.max(1, f.closure()));
        return volume * POROSITY * RECOVERY * f.richness() * 1000.0;
    }

    /** The share of a field's oil still to come, 0..1. */
    public double left(PetroleumFields.Field f) {
        double all = recoverable(f);
        return all <= 0 ? 0 : Math.max(0, 1.0 - taken.get(f.id()) / all);
    }

    /** For trying it out: a field set to have this share of its oil taken. */
    public void spend(PetroleumFields.Field f, double share) {
        taken.put(f.id(), Math.max(0, Math.min(1, share)) * recoverable(f));
        setDirty();
    }

    public void take(PetroleumFields.Field f, double millibuckets) {
        taken.addTo(f.id(), millibuckets);
        setDirty();
    }

    static OilReserves load(CompoundTag tag) {
        OilReserves r = new OilReserves();
        for (Tag t : tag.getList("Fields", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            r.taken.put(c.getLong("Id"), c.getDouble("Taken"));
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var e : taken.long2DoubleEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Id", e.getLongKey());
            c.putDouble("Taken", e.getDoubleValue());
            list.add(c);
        }
        tag.put("Fields", list);
        return tag;
    }
}
