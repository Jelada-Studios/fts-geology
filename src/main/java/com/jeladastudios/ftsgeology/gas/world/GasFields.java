package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.PetroleumFields;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The gas of an oil field's cap, let out where its rock is broken into. A gas cap is held at the pressure of its depth --
 * a few atmospheres a few dozen blocks down -- and a hole into it is a blowout: the gas pours out for as long as the hole
 * is open and the field has gas, fast while the air at the hole is at the surface's pressure and slower as it fills.
 * Plugging the hole with any solid block stops it; a gas valve set over it lets it out through a pipe instead (the valve
 * draws from the air behind it). A sour field's gas carries hydrogen sulphide. What each field has given is kept with
 * the world, so a field runs down. Oil-soaked rock gives off a little of the gas dissolved in its oil.
 */
public final class GasFields {

    private GasFields() {}

    /** The pressure a cap holds per block of depth over the surface's (atm), and the most. */
    private static final double ATM_PER_BLOCK = 0.1, MOST_ATM = 12.0;
    /** How fast a hole evens out with the cap a second, the most moles a second, and the most holes kept. */
    private static final double DRAW = 0.25, MOST_FLOW = 20.0;
    private static final int MOST_HOLES = 64;
    /** Pore space of the reservoir rock, for how much gas a cap holds. */
    private static final double POROSITY = 0.2;

    /** An open hole into a cap: where, which field, the cap's pressure. */
    private record Hole(long pos, long field, double atm, double sour) {}

    private static final Long2ObjectLinkedOpenHashMap<Hole> HOLES = new Long2ObjectLinkedOpenHashMap<>();
    /** The mouths gas came out of at the last second's round: a flame there is fed (see VentFlames). */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet MOUTHS = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Whether gas from a field comes out at a place now, the mouth of a bore. */
    public static boolean isMouth(BlockPos p) {
        return MOUTHS.contains(p.asLong());
    }
    private static long holes, closed;
    private static double released;

    /** A block of a field's rock broken: a hole into its gas cap, or a breath of the gas in its oil. */
    public static void broken(ServerLevel level, BlockPos pos, BlockState was) {
        if (!was.is(Blocks.SANDSTONE) && !was.is(ModBlocks.OIL_SANDSTONE.get())) return;
        PetroleumFields.At at = PetroleumFields.at(level, pos);
        if (at == null) return;
        GasManager gas = GasManager.get(level);
        if (at.zone() == PetroleumFields.Zone.OIL) {
            gas.releaseLater(pos, mix(1.0 + 2.0 * at.field().richness(), at.field().sour()), 1);
            return;
        }
        if (at.zone() != PetroleumFields.Zone.GAS || HOLES.containsKey(pos.asLong())) return;
        if (left(level, at.field()) <= 0) return;
        double atm = Math.min(MOST_ATM, 1.0 + ATM_PER_BLOCK * at.field().depth(level));
        HOLES.put(pos.asLong(), new Hole(pos.asLong(), at.field().id(), atm, at.field().sour()));
        while (HOLES.size() > MOST_HOLES) HOLES.removeFirst();
        holes++;
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.5f, 0.4f);
    }

    /** Breaks a block as a pick would, its field let out with it: for a test from the console, or a drill. */
    public static void dig(ServerLevel level, BlockPos pos) {
        BlockState was = level.getBlockState(pos);
        broken(level, pos, was);
        level.destroyBlock(pos, false);
    }

    /** Once a second: every open hole gives out what it can. */
    static void tick(GasManager gas) {
        if (HOLES.isEmpty()) {
            MOUTHS.clear();
            return;
        }
        MOUTHS.clear();
        ServerLevel level = gas.level;
        Fields store = Fields.of(level);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (var it = HOLES.values().iterator(); it.hasNext(); ) {
            Hole h = it.next();
            m.set(h.pos());
            if (!gas.isLoaded(m)) continue;
            if (gas.isGasTight(level.getBlockState(m), m)) {
                it.remove();
                closed++;
                continue;
            }
            double left = store.left(h.field());
            if (left <= 0) {
                it.remove();
                continue;
            }
            // Up a straight bore open to the sky the gas comes out at its mouth, as it does up a well; into anything else it
            // fills what it was let into.
            BlockPos mouth = mouth(gas, level, m);
            BlockPos out = mouth != null ? mouth : m.immutable();
            if (mouth != null) MOUTHS.add(mouth.asLong());
            GasMix air = gas.sample(out);
            // The cap's pressure falls as its gas is taken.
            double atm = 1.0 + (h.atm() - 1.0) * store.share(h.field());
            double want = (atm * GasManager.N0 - air.total()) * DRAW;
            if (want <= 0.01 || !gas.simulated(m.getX(), m.getZ())) continue;
            double moles = Math.min(Math.min(MOST_FLOW, want), left);
            m.set(out);
            if (gas.release(m, mix(moles, h.sour()))) {
                store.take(h.field(), moles);
                released += moles;
                level.sendParticles(ParticleTypes.CLOUD, m.getX() + 0.5, m.getY() + 0.5, m.getZ() + 0.5,
                        2 + (int) (moles / 4), 0.25, 0.25, 0.25, 0.04);
                if (level.getGameTime() % 60 < 20) {
                    level.playSound(null, m, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f + (float) (moles / MOST_FLOW), 0.3f);
                }
            }
        }
    }

    /**
     * The mouth of a straight bore up from a hole: going up it, the first cell level with the ground round it (the land
     * on two of its sides no higher); null where the way up is shut first.
     */
    private static BlockPos mouth(GasManager gas, ServerLevel level, BlockPos hole) {
        BlockPos p = hole;
        for (int i = 0; i < 192; i++) {
            int open = 0;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        p.getX() + d.getStepX(), p.getZ() + d.getStepZ()) <= p.getY()) open++;
            }
            if (open >= 2) return p.immutable();
            if (!gas.canFlow(p, net.minecraft.core.Direction.UP)) return null;
            p = p.above();
        }
        return null;
    }

    /** Natural gas: methane, a little CO2, and hydrogen sulphide in a sour field. */
    private static GasMix mix(double moles, double sour) {
        GasMix g = new GasMix();
        g.add(Gas.METHANE, moles * (0.97 - sour));
        g.add(Gas.CARBON_DIOXIDE, moles * 0.03);
        if (sour > 0) g.add(Gas.HYDROGEN_SULFIDE, moles * sour);
        return g;
    }

    /** The gas a field had, from the size of its cap and its pressure, less what has come out. */
    private static double left(ServerLevel level, PetroleumFields.Field f) {
        return Fields.of(level).left(f.id(), cap(level, f));
    }

    /** A cap's gas: the volume of the dome above the gas-oil contact, its pore space, at its pressure. */
    static double cap(ServerLevel level, PetroleumFields.Field f) {
        double h = Math.max(0, f.crest() - f.goc());
        double volume = Math.PI * f.a() * f.b() * h * h / (2.0 * Math.max(1, f.closure()));
        double atm = Math.min(MOST_ATM, 1.0 + ATM_PER_BLOCK * f.depth(level));
        return volume * POROSITY * atm * GasManager.N0;
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "oil field gas: %d holes broken into a cap, %d plugged, %d open, %.0f mol let out",
                holes, closed, HOLES.size(), released);
    }

    public static void clear() {
        HOLES.clear();
        MOUTHS.clear();
    }

    /** What each field has given, with the world. */
    static final class Fields extends SavedData {
        private final Long2DoubleOpenHashMap total = new Long2DoubleOpenHashMap();
        private final Long2DoubleOpenHashMap taken = new Long2DoubleOpenHashMap();

        static Fields of(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Fields::load, Fields::new, "fts_geology_gas_fields");
        }

        double left(long field, double cap) {
            total.putIfAbsent(field, cap);
            return total.get(field) - taken.get(field);
        }

        double left(long field) {
            return total.containsKey(field) ? total.get(field) - taken.get(field) : 0.0;
        }

        /** The share of a field's gas still in it, 0 to 1. */
        double share(long field) {
            double t = total.get(field);
            return t <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (t - taken.get(field)) / t));
        }

        void take(long field, double moles) {
            taken.addTo(field, moles);
            setDirty();
        }

        static Fields load(CompoundTag tag) {
            Fields f = new Fields();
            ListTag list = tag.getList("Fields", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                f.total.put(c.getLong("Id"), c.getDouble("Total"));
                f.taken.put(c.getLong("Id"), c.getDouble("Taken"));
            }
            return f;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (var e : total.long2DoubleEntrySet()) {
                CompoundTag c = new CompoundTag();
                c.putLong("Id", e.getLongKey());
                c.putDouble("Total", e.getDoubleValue());
                c.putDouble("Taken", taken.get(e.getLongKey()));
                list.add(c);
            }
            tag.put("Fields", list);
            return tag;
        }
    }
}
