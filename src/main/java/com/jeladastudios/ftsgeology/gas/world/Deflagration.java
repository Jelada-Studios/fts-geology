package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.GasConfig;
import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.registry.GasDamageTypes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A flame front travelling through connected flammable cells. Each tick it advances a number of
 * cells that depends on the fuel's burning velocity (hydrogen is ~4\u00D7 faster than methane), burns
 * the gas in place (leaving CO\u2082/H\u2082O/CO/SO\u2082 "afterdamp") and turns the released heat into
 * explosions.
 * <p>
 * Heat is collected in 4\u00D74\u00D74 blast regions aligned on the ignition point. A region detonates once
 * the flame front has passed through it, so a gas-filled room gives one big blast rather than many
 * small ones, while a long gallery explodes section by section as the flame races along it.
 */
public class Deflagration {
    private static final int MAX_CELLS = 40000;
    private static final int BIN = 4;

    private final GasManager mgr;
    private final ServerLevel level;
    @Nullable
    private final Entity cause;
    private final BlockPos origin;
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private LongArrayList frontier = new LongArrayList();
    private final Long2ObjectOpenHashMap<Bin> bins = new Long2ObjectOpenHashMap<>();
    private final int cellsPerStep;
    private int burned;
    private int age;

    Deflagration(GasManager mgr, BlockPos start, @Nullable Entity cause) {
        this.mgr = mgr;
        this.level = mgr.level;
        this.cause = cause;
        this.origin = start;
        long l = start.asLong();
        visited.add(l);
        frontier.add(l);
        GasMix c = mgr.getCell(start);
        double speed = c == null ? 1 : Combustion.flameSpeed(c);
        this.cellsPerStep = Math.max(1, Math.min(4, (int) Math.round(speed)));
        level.playSound(null, start, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.5f, 0.6f + mgr.random.nextFloat() * 0.3f);
    }

    boolean contains(long pos) {
        return visited.contains(pos);
    }

    /** @return true when finished */
    boolean tick() {
        age++;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int step = 0; step < cellsPerStep && !frontier.isEmpty(); step++) {
            LongArrayList next = new LongArrayList();
            for (int i = 0; i < frontier.size(); i++) {
                BlockPos p = BlockPos.of(frontier.getLong(i));
                GasMix cell = mgr.getCell(p);
                if (cell == null || !Combustion.isFlammable(cell)) {
                    // The front reaches gas too rich to burn: where that meets the air, it burns on as a standing flame.
                    if (cell != null && VentFlames.tooRich(cell)) mgr.flames().start(p);
                    continue;
                }

                // The mouth of a bore a gas field pours out of goes on burning there.
                if (GasFields.isMouth(p)) mgr.flames().start(p);
                double violence = Combustion.violence(cell);
                boolean hydrogen = cell.fraction(Gas.H2) > Combustion.fuelFraction(cell) * 0.6;
                double energy = Combustion.burn(cell);
                mgr.markDirty(p);
                burned++;
                addToBin(p, energy * violence);
                cellEffects(p, hydrogen);

                if (burned >= MAX_CELLS) {
                    next.clear();
                    break;
                }
                for (Direction d : GasManager.DIRS) {
                    q.setWithOffset(p, d);
                    long ql = q.asLong();
                    if (visited.contains(ql)) continue;
                    if (mgr.getCell(q) == null || !mgr.canFlow(p, d)) continue;
                    visited.add(ql);
                    next.add(ql);
                }
            }
            frontier = next;
        }
        boolean done = frontier.isEmpty();
        detonate(done);
        return done && bins.isEmpty();
    }

    private void addToBin(BlockPos p, double energy) {
        long key = BlockPos.asLong(Math.floorDiv(p.getX() - origin.getX() + BIN / 2, BIN),
                Math.floorDiv(p.getY() - origin.getY() + BIN / 2, BIN),
                Math.floorDiv(p.getZ() - origin.getZ() + BIN / 2, BIN));
        Bin b = bins.computeIfAbsent(key, k -> new Bin());
        b.energy += energy;
        b.sx += p.getX() + 0.5;
        b.sy += p.getY() + 0.5;
        b.sz += p.getZ() + 0.5;
        b.n++;
        b.lastBurn = age;
        if (!mgr.isSkyExposed(p)) b.enclosed++;
        b.minX = Math.min(b.minX, p.getX());
        b.minY = Math.min(b.minY, p.getY());
        b.minZ = Math.min(b.minZ, p.getZ());
        b.maxX = Math.max(b.maxX, p.getX() + 1);
        b.maxY = Math.max(b.maxY, p.getY() + 1);
        b.maxZ = Math.max(b.maxZ, p.getZ() + 1);
    }

    private void cellEffects(BlockPos p, boolean hydrogen) {
        double x = p.getX() + 0.5, y = p.getY() + 0.5, z = p.getZ() + 0.5;
        boolean heavy = burned > 1500 && (burned % 3) != 0;
        if (!heavy) {
            if (hydrogen) {
                // Hydrogen burns with an almost invisible pale flame.
                level.sendParticles(ParticleTypes.WHITE_ASH, x, y, z, 3, 0.4, 0.4, 0.4, 0.02);
            } else {
                level.sendParticles(ParticleTypes.FLAME, x, y, z, 3, 0.35, 0.35, 0.35, 0.03);
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 1, 0.3, 0.3, 0.3, 0.01);
            }
        }
        if (GasConfig.EXPLOSIONS_SPAWN_FIRE.get() && mgr.random.nextFloat() < 0.07f && level.isEmptyBlock(p)
                && BaseFireBlock.canBePlacedAt(level, p, Direction.UP)) {
            level.setBlockAndUpdate(p, BaseFireBlock.getState(level, p));
        }
    }

    /** Detonates blast regions the flame has left (or all of them once the front is out). */
    private void detonate(boolean all) {
        if (bins.isEmpty()) return;
        List<Long> ready = new ArrayList<>();
        for (Long2ObjectOpenHashMap.Entry<Bin> e : bins.long2ObjectEntrySet()) {
            if (all || e.getValue().lastBurn < age) ready.add(e.getLongKey());
        }
        ready.sort((a, b) -> Double.compare(bins.get(b).energy, bins.get(a).energy));
        int max = GasConfig.MAX_EXPLOSIONS_PER_TICK.get();
        double scale = GasConfig.EXPLOSION_SCALE.get();
        double maxPower = GasConfig.MAX_EXPLOSION_POWER.get();
        boolean breakBlocks = GasConfig.EXPLOSIONS_BREAK_BLOCKS.get();
        for (int i = 0; i < ready.size() && i < max; i++) {
            Bin b = bins.remove((long) ready.get(i));
            double cx = b.sx / b.n, cy = b.sy / b.n, cz = b.sz / b.n;
            AABB box = new AABB(b.minX - 0.5, b.minY - 0.5, b.minZ - 0.5, b.maxX + 0.5, b.maxY + 0.5, b.maxZ + 0.5);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (!e.fireImmune()) e.setSecondsOnFire(6);
            }
            // Confined gas explodes; an unconfined cloud mostly just flashes.
            double confinement = 0.35 + 0.65 * b.enclosed / b.n;
            float power = (float) Math.min(maxPower, scale * Math.cbrt(b.energy / 1000.0) * confinement);
            if (power >= 1.0f) {
                level.explode(cause, GasDamageTypes.source(level, GasDamageTypes.GAS_EXPLOSION), null,
                        cx, cy, cz, power, false,
                        breakBlocks ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE);
            } else {
                // Too little fuel for a blast: a flash fire.
                level.playSound(null, cx, cy, cz, SoundEvents.BLAZE_SHOOT, SoundSource.BLOCKS, 1.0f, 0.7f);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
                    e.hurt(level.damageSources().inFire(), 2.0f + power * 3.0f);
                }
            }
        }
    }

    private static final class Bin {
        double energy, sx, sy, sz;
        int n, enclosed, lastBurn;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    }
}
