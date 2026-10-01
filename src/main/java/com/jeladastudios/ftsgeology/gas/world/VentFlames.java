package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standing flames: gas too rich to burn, coming out into the air, lit where the two meet. The mouth of a gas-filled cave
 * or room opened at its top, a hole into a gas cap, the crater of Darvaza that has burned since 1971 -- inside, the gas
 * is too rich to burn and nothing happens there; at the mouth it mixes with the air drawn into the flame and burns, in
 * the air, for as long as gas comes up. Not a fire block: a flame drawn in the air, as tall as the heat it gives (a
 * diffusion flame's height goes as the two-fifths power of its heat, Heskestad), yellow for methane and all but
 * unseen for hydrogen.
 *
 * <p>The gas comes to the mouth as the simulation brings it, rising out of what lies below; the flame burns what reaches
 * its cell each time, with air from the open cells round it, and lets the fumes rise. When the gas behind the mouth
 * thins until air mixed into it can burn, the flame runs back into it: the room explodes (a flashback). A block over the
 * mouth smothers it, a big explosion beside it blows it out, and with no gas left it dies down and goes out. Flames are
 * kept with the world.</p>
 */
public final class VentFlames {

    /** The most flames a dimension keeps burning. */
    private static final int MOST = 48;
    /** Ticks between burns. */
    private static final int EVERY = 2;
    /** The share of the mouth cell's gas that comes into the flame each burn, and of each air cell round it. */
    private static final double TAKE = 0.6, ENTRAIN = 0.25;
    /** Oxygen an air cell must hold to feed the flame. */
    private static final double AIR_O2 = 0.10;
    /** Burns in a row with next to no heat before the flame goes out. */
    private static final int STARVE = 20;
    /** Heat a burn must give to count, kJ. */
    private static final double KEEPS = 0.5;

    private final GasManager gm;
    private final ServerLevel level;
    private final Long2ObjectLinkedOpenHashMap<Flame> flames = new Long2ObjectLinkedOpenHashMap<>();
    private Store store;
    private long started, out, flashbacks;

    /** One flame: where, how long it has gone hungry, its heat (kW, smoothed), what it burns, where its gas comes from. */
    static final class Flame {
        long pos;
        int starve;
        double kw;
        boolean hydrogen;
        long source = Long.MIN_VALUE;
        int age;
    }

    VentFlames(GasManager gm) {
        this.gm = gm;
        this.level = gm.level;
    }

    private Store store() {
        if (store == null) {
            store = level.getDataStorage().computeIfAbsent(Store::load, Store::new, "fts_geology_vent_flames");
            for (long l : store.saved) {
                Flame f = new Flame();
                f.pos = l;
                flames.put(l, f);
            }
        }
        return store;
    }

    private void save() {
        Store s = store();
        s.saved = flames.keySet().toLongArray();
        s.setDirty();
    }

    /** Whether gas this rich will not burn as it is: more fuel in it than burns with the air it has. */
    public static boolean tooRich(GasMix c) {
        double f = Combustion.fuelFraction(c);
        return f > 0.02 && f > Combustion.ufl(c);
    }

    /**
     * Lights a standing flame at {@code p}, where gas too rich to burn meets the air. Returns false where there is no
     * such gas, no air by it, or a flame already.
     */
    public boolean start(BlockPos p) {
        store();
        long l = p.asLong();
        if (flames.containsKey(l)) return false;
        GasMix c = gm.getCell(p);
        // The mouth of a bore a gas field pours out of is fed however the air thins the gas over it.
        boolean fed = GasFields.isMouth(p) && c != null && Combustion.fuelFraction(c) > 0.005;
        if (c == null || !(fed || tooRich(c)) || airBeside(p) == null || flames.size() >= MOST) {
            if (c != null && Combustion.fuelFraction(c) > 0.05) {
                com.jeladastudios.ftsgeology.util.Diagnostics.info("no standing flame at {}: fuel {} (burns {} to {}), air beside {}, {} flames",
                        p.toShortString(), String.format(Locale.ROOT, "%.3f", Combustion.fuelFraction(c)),
                        String.format(Locale.ROOT, "%.3f", Combustion.lfl(c)), String.format(Locale.ROOT, "%.3f", Combustion.ufl(c)),
                        airBeside(p), flames.size());
            }
            return false;
        }
        Flame f = new Flame();
        f.pos = l;
        flames.put(l, f);
        started++;
        light(p, true);
        level.playSound(null, p, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.5f, 0.5f);
        save();
        return true;
    }

    /** The first open cell by {@code p} with air enough to feed a flame, or null. */
    private BlockPos airBeside(BlockPos p) {
        for (Direction d : GasManager.DIRS) {
            if (!gm.canFlow(p, d)) continue;
            BlockPos q = p.relative(d);
            if (gm.sample(q).fraction(Gas.O2) >= AIR_O2) return q;
        }
        return null;
    }

    public int count() {
        return flames.size();
    }

    /** Every tick: each flame burns what reached it, where the gas is simulated now. */
    void tick() {
        Store s = store();
        if (flames.isEmpty()) return;
        long now = level.getGameTime();
        if (now % EVERY != 0) return;
        List<Flame> all = new ArrayList<>(flames.values());
        boolean changed = false;
        for (Flame f : all) {
            BlockPos p = BlockPos.of(f.pos);
            if (!gm.isLoaded(p) || !level.isLoaded(p) || !gm.simulated(p.getX(), p.getZ())) continue;
            String why = burn(f, p, now);
            if (why != null) {
                remove(f, BlockPos.of(f.pos), why);
                changed = true;
            } else if (f.pos != p.asLong()) {
                changed = true;
            }
        }
        if (changed || s.saved.length != flames.size()) save();
    }

    /** One burn. Returns why the flame went out, or null while it burns. */
    private String burn(Flame f, BlockPos p, long now) {
        f.age++;
        if (gm.isGasTight(level.getBlockState(p), p)) return "smothered";
        GasMix cell = gm.getCell(p);
        // The flame keeps to the gas: where its cell has thinned and a cell beside it holds more, it moves there.
        if (!GasFields.isMouth(p) && (cell == null || Combustion.fuelFraction(cell) < Combustion.lfl(cell))) {
            BlockPos richer = richest(p, cell == null ? 0 : Combustion.fuelFraction(cell), true);
            if (richer != null) {
                move(f, p, richer);
                p = richer;
                cell = gm.getCell(p);
            }
        }
        double heat = 0;
        BlockPos fumes = null;
        BlockPos source = richest(p, 0.02, false);
        f.source = source == null ? Long.MIN_VALUE : source.asLong();
        if (cell != null && Combustion.fuelMoles(cell) > 1e-6) {
            double fuel = Combustion.fuelMoles(cell);
            f.hydrogen = fuel > 0 && cell.get(Gas.HYDROGEN) / fuel > 0.6;
            GasMix stream = cell.take(cell.total() * TAKE);
            gm.markDirty(p);
            for (Direction d : UP_FIRST) {
                if (!gm.canFlow(p, d)) continue;
                BlockPos q = p.relative(d);
                if (q.asLong() == f.source) continue;
                GasMix air = gm.getOrCreateCell(q);
                if (air == null || air.fraction(Gas.O2) < AIR_O2) continue;
                if (fumes == null && d != Direction.DOWN) fumes = q;
                heat += Combustion.burnWithAir(stream, air, air.total() * ENTRAIN);
                gm.markDirty(q);
                if (Combustion.fuelMoles(stream) < 1e-6) break;
            }
            // The fumes and what did not burn rise from the flame.
            gm.release(fumes != null ? fumes : p, stream);
        }
        f.kw = f.kw * 0.8 + 0.2 * heat * 20.0 / EVERY;
        if (heat < KEEPS) {
            if (++f.starve >= STARVE) return "out";
        } else {
            f.starve = 0;
        }
        // Air got into the gas behind the mouth and made it burnable: the flame runs back into it.
        if (source != null) {
            GasMix behind = gm.getCell(source);
            if (behind != null && Combustion.isFlammable(behind) && gm.ignite(source, null)) {
                flashbacks++;
                return "flashback";
            }
        }
        if (f.kw > 1.0) effects(f, p, now);
        return null;
    }

    private static final Direction[] UP_FIRST = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST,
            Direction.WEST, Direction.DOWN};

    /**
     * The open cell beside {@code p} holding the most fuel, more than {@code than}, or null; above it only where
     * {@code up} (the fumes rise there, the gas comes from below and the sides).
     */
    private BlockPos richest(BlockPos p, double than, boolean up) {
        BlockPos best = null;
        double most = than;
        for (Direction d : GasManager.DIRS) {
            if (d == Direction.UP && !up) continue;
            if (!gm.canFlow(p, d)) continue;
            BlockPos q = p.relative(d);
            GasMix c = gm.getCell(q);
            if (c == null) continue;
            double f = Combustion.fuelFraction(c);
            if (f > most) {
                most = f;
                best = q;
            }
        }
        return best;
    }

    private void move(Flame f, BlockPos from, BlockPos to) {
        long l = to.asLong();
        if (flames.containsKey(l)) return;
        flames.remove(f.pos);
        light(from, false);
        f.pos = l;
        flames.put(l, f);
        light(to, true);
    }

    private void remove(Flame f, BlockPos p, String why) {
        flames.remove(f.pos);
        light(p, false);
        out++;
        if (!"flashback".equals(why)) level.playSound(null, p, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("vent flame at {} went out ({}) after {} burns", p.toShortString(), why, f.age);
    }

    /** The flame's light: a light block in its cell while the cell is air, taken away after. */
    private void light(BlockPos p, boolean on) {
        BlockState s = level.getBlockState(p);
        if (on && s.isAir()) {
            level.setBlock(p, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), Block.UPDATE_ALL);
        } else if (!on && s.is(Blocks.LIGHT)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** A diffusion flame's height over its mouth, blocks, for its heat in kW (Heskestad, a mouth a metre across). */
    public static double height(double kw) {
        return Math.max(0.4, Math.min(24.0, 0.235 * Math.pow(Math.max(0.0, kw), 0.4) - 1.02));
    }

    /** The flame drawn, its roar, and what it does to what stands in it and round it. */
    private void effects(Flame f, BlockPos p, long now) {
        double h = height(f.kw);
        double x = p.getX() + 0.5, y = p.getY() + 0.1, z = p.getZ() + 0.5;
        if ((now / EVERY) % 2 == 0) {
            int n = (int) Math.min(40, 4 + h * 5);
            if (f.hydrogen) {
                // Hydrogen burns pale blue and next to unseen: a few wisps, the heat shimmer and the roar tell of it.
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + h * 0.4, z, Math.max(1, n / 6), 0.18, h * 0.3, 0.18, 0.01);
                level.sendParticles(ParticleTypes.WHITE_ASH, x, y + h * 0.5, z, Math.max(1, n / 4), 0.3, h * 0.35, 0.3, 0.02);
            } else {
                level.sendParticles(ParticleTypes.FLAME, x, y + h * 0.35, z, n, 0.22 + h * 0.04, h * 0.3, 0.22 + h * 0.04, 0.02);
                level.sendParticles(ParticleTypes.LAVA, x, y + h * 0.2, z, n > 20 ? 1 : 0, 0.2, h * 0.2, 0.2, 0.0);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + h + 0.3, z, 1 + (int) (h / 4), 0.3, 0.3, 0.3, 0.02);
            }
        }
        if (now % 20 == 0) {
            float loud = (float) Math.min(3.0, 0.5 + f.kw / 400.0);
            level.playSound(null, p, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, loud, 0.5f + level.random.nextFloat() * 0.2f);
            if (f.kw > 300) level.playSound(null, p, SoundEvents.BLAZE_BURN, SoundSource.BLOCKS, loud * 0.5f, 0.4f);
        }
        if (now % 10 == 0) {
            AABB column = new AABB(p.getX() - 0.2, p.getY(), p.getZ() - 0.2, p.getX() + 1.2, p.getY() + h + 0.5, p.getZ() + 1.2);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, column)) {
                if (e.fireImmune()) continue;
                e.setSecondsOnFire(8);
                e.hurt(level.damageSources().inFire(), (float) Math.min(8.0, 2.0 + f.kw / 200.0));
            }
        }
        if (now % 20 == 0) heatAround(p, h);
    }

    /** Twice a second the flame's heat reaches round it: snow and ice melt, and what burns by it catches. */
    private void heatAround(BlockPos p, double h) {
        int r = 1 + (int) (h / 3);
        for (int i = 0; i < 2; i++) {
            BlockPos q = p.offset(level.random.nextInt(2 * r + 1) - r, level.random.nextInt((int) Math.ceil(h) + 2) - 1,
                    level.random.nextInt(2 * r + 1) - r);
            BlockState s = level.getBlockState(q);
            if (s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) {
                level.setBlock(q, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            } else if (s.is(BlockTags.ICE) && !level.dimensionType().ultraWarm()) {
                level.setBlock(q, s.is(Blocks.ICE) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            } else if (s.isAir() && !s.is(Blocks.LIGHT) && BaseFireBlock.canBePlacedAt(level, q, Direction.UP) && burnsBeside(q)) {
                level.setBlock(q, BaseFireBlock.getState(level, q), Block.UPDATE_ALL);
            }
        }
    }

    private boolean burnsBeside(BlockPos q) {
        for (Direction d : GasManager.DIRS) {
            BlockPos n = q.relative(d);
            if (level.getBlockState(n).isFlammable(level, n, d.getOpposite())) return true;
        }
        return false;
    }

    /** An explosion of {@code radius} at {@code centre}: the flames it reaches are blown out, as a well fire is. */
    public void blowOut(Vec3 centre, double radius) {
        store();
        if (flames.isEmpty()) return;
        boolean any = false;
        for (Flame f : new ArrayList<>(flames.values())) {
            BlockPos p = BlockPos.of(f.pos);
            if (p.getCenter().distanceTo(centre) <= radius + 1.0) {
                remove(f, p, "blown out");
                any = true;
            }
        }
        if (any) save();
    }

    /** The flame whose fire stands at {@code q} -- in its cell or in its column over it -- its heat (kW), or 0. */
    public double heatAt(BlockPos q) {
        store();
        if (flames.isEmpty()) return 0;
        for (int k = 0; k <= 24; k++) {
            Flame f = flames.get(q.below(k).asLong());
            if (f != null && k <= height(f.kw) + 0.5) return f.kw;
        }
        return 0;
    }

    /** A line on each flame, for the command. */
    public List<String> describe() {
        store();
        List<String> out = new ArrayList<>();
        for (Flame f : flames.values()) {
            BlockPos p = BlockPos.of(f.pos);
            out.add(String.format(Locale.ROOT, "%s  %.0f kW  %.1f blocks  %s  %d burns", p.toShortString(), f.kw, height(f.kw),
                    f.hydrogen ? "hydrogen" : "methane", f.age));
        }
        out.add(String.format(Locale.ROOT, "lit %d, gone out %d, flashbacks %d", started, this.out, flashbacks));
        return out;
    }

    /** The flames' places, kept with the world. */
    static final class Store extends SavedData {
        long[] saved = new long[0];

        static Store load(CompoundTag tag) {
            Store s = new Store();
            s.saved = tag.getLongArray("Flames");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.put("Flames", new LongArrayTag(saved));
            return tag;
        }
    }
}
