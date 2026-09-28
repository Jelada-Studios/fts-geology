package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carbon dioxide seeping out of a restless or erupting volcano. Heavier than air, it runs downhill and lies in the
 * crater and in hollows on the flanks, unseen and without a smell, as it did at Dieng in 1979 and still does round
 * Mammoth Mountain, where it has killed the trees and the odd skier who fell into a snow hollow. Whatever puts its head
 * in it is short of breath, then dizzy, and only what stays in it is harmed; flames go out in it.
 *
 * <p>A live volcano breathes it all the time: between eruptions it lies in the crater and round the vents on the
 * flanks, where the plants in it die off over the days, as the trees have round Mammoth Mountain; while the volcano
 * is restless or erupting it pours out and fills the hollows on its flanks too. A volcano reports its gas once a
 * second; its pockets are found then and kept for five minutes, and the gas lingers a couple of minutes after it
 * stops. Nothing is saved.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class VolcanicGas {

    private VolcanicGas() {}

    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            new ResourceLocation(GeysersMod.MODID, "volcanic_gas"));

    /** How long the gas lingers after the volcano last reported it, and how long its pockets are kept, in ticks. */
    private static final long LINGER = 2400L, KEEP = 6000L;
    /** Most pockets on one volcano's flanks, and the step the flanks are searched at. */
    private static final int MOST = 24, STEP = 4;
    /** How much higher all round a hollow's rim must stand for gas to lie in it, and the most it fills. */
    private static final int HOLLOW = 2, DEEPEST = 4;

    /** Gas lying in a hollow: over {@code r} of a column, from {@code floor} up to {@code top}. */
    public record Pocket(int x, int z, int floor, int top, int r) {
        boolean holds(double px, double py, double pz) {
            double dx = px - (x + 0.5), dz = pz - (z + 0.5);
            return dx * dx + dz * dz <= (r + 0.5) * (r + 0.5) && py >= floor && py < top + 1;
        }
    }

    private static final class Field {
        final ResourceKey<Level> dimension;
        final List<Pocket> pockets;
        final long found;
        /** Only the crater and the vents: the gas a volcano breathes between eruptions. */
        final boolean quiet;
        long until;

        Field(ResourceKey<Level> dimension, List<Pocket> pockets, long found, boolean quiet) {
            this.dimension = dimension;
            this.pockets = pockets;
            this.found = found;
            this.quiet = quiet;
        }
    }

    private static final Map<Long, Field> FIELDS = new HashMap<>();
    private static int sickened, flames;

    /** Once a second from a restless or erupting volcano: its gas keeps coming, and lies where it can. */
    public static void seep(ServerLevel level, BlockPos summit, int craterR, int magnitude, long[] vents) {
        seep(level, summit, craterR, magnitude, vents, false);
    }

    /** Once a second from a live volcano between eruptions: gas in its crater and round its vents. */
    public static void breatheQuietly(ServerLevel level, BlockPos summit, int craterR, int magnitude, long[] vents) {
        seep(level, summit, craterR, magnitude, vents, true);
    }

    private static void seep(ServerLevel level, BlockPos summit, int craterR, int magnitude, long[] vents, boolean quiet) {
        if (!GeyserConfig.VOLCANIC_GAS.get()) return;
        long now = level.getGameTime();
        Field f = FIELDS.get(summit.asLong());
        if (f == null || now - f.found > KEEP || f.quiet && !quiet) {
            f = new Field(level.dimension(), find(level, summit, craterR, magnitude, vents, quiet), now, quiet);
            FIELDS.put(summit.asLong(), f);
            com.jeladastudios.ftsgeology.util.Diagnostics.info("Volcano at {} is venting gas: {} pockets{}", summit, f.pockets.size(),
                    f.pockets.stream().limit(4).map(p -> String.format(java.util.Locale.ROOT, "; %d %d %d-%d r%d", p.x(), p.z(), p.floor(), p.top(), p.r()))
                            .reduce("", String::concat));
        }
        f.until = now + LINGER;
    }

    /**
     * Where the gas lies: the crater, filled to its lowest notch, and the hollows on the flanks and round the vents, each
     * filled to the lowest point of its rim, no more than {@link #DEEPEST} deep.
     */
    private static List<Pocket> find(ServerLevel level, BlockPos summit, int craterR, int magnitude, long[] vents,
                                     boolean quiet) {
        List<Pocket> out = new ArrayList<>();
        // The crater, over its lava.
        int rim = Integer.MAX_VALUE;
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            int x = summit.getX() + (int) Math.round(Math.cos(a) * (craterR + 2));
            int z = summit.getZ() + (int) Math.round(Math.sin(a) * (craterR + 2));
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(x, 0, z))) continue;
            int g = TerrainProbe.groundY(level, x, z);
            if (g != Integer.MIN_VALUE) rim = Math.min(rim, g);
        }
        if (rim != Integer.MAX_VALUE && rim > summit.getY()) {
            out.add(new Pocket(summit.getX(), summit.getZ(), summit.getY(), Math.min(rim, summit.getY() + 6), craterR + 1));
        }
        List<Pocket> hollows = new ArrayList<>();
        int reach = quiet ? 0 : Math.min(64, craterR + 16 + magnitude);
        for (int dx = -reach; dx <= reach && reach > 0; dx += STEP) {
            for (int dz = -reach; dz <= reach; dz += STEP) {
                if (dx * dx + dz * dz > reach * reach) continue;
                hollow(level, summit.getX() + dx, summit.getZ() + dz, hollows);
            }
        }
        for (long v : vents) {
            BlockPos p = BlockPos.of(v);
            for (int dx = -9; dx <= 9; dx += 3) {
                for (int dz = -9; dz <= 9; dz += 3) hollow(level, p.getX() + dx, p.getZ() + dz, hollows);
            }
        }
        hollows.sort((a, b) -> Integer.compare(b.top() - b.floor(), a.top() - a.floor()));
        for (Pocket h : hollows) {
            if (out.size() >= MOST) break;
            boolean near = false;
            for (Pocket o : out) {
                if (Math.abs(o.x() - h.x()) <= o.r() + h.r() && Math.abs(o.z() - h.z()) <= o.r() + h.r()) {
                    near = true;
                    break;
                }
            }
            if (!near) out.add(h);
        }
        return out;
    }

    /** A hollow at a column, if its ground lies at least {@link #HOLLOW} under every point of a ring round it. */
    private static void hollow(ServerLevel level, int x, int z, List<Pocket> out) {
        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(x, 0, z))) return;
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE || TerrainProbe.hasFluidAbove(level, x, z)) return;
        // Most columns are on a slope, and one step across tells: the cheap test first.
        int spill = Integer.MAX_VALUE;
        for (int i = 0; i < 8 && spill >= g + HOLLOW; i++) {
            double a = i * Math.PI / 4;
            int rx = x + (int) Math.round(Math.cos(a) * 3), rz = z + (int) Math.round(Math.sin(a) * 3);
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(rx, 0, rz))) return;
            int r = TerrainProbe.groundY(level, rx, rz);
            if (r == Integer.MIN_VALUE) return;
            spill = Math.min(spill, r);
        }
        if (spill < g + HOLLOW) return;
        out.add(new Pocket(x, z, g + 1, g + Math.min(spill - g, DEEPEST), 2));
    }

    /** The pocket a point is in, anywhere, or null: for the probe. */
    public static Pocket at(Level level, double x, double y, double z) {
        long now = level.getGameTime();
        for (Field f : FIELDS.values()) {
            if (!f.dimension.equals(level.dimension()) || f.until < now) continue;
            for (Pocket p : f.pockets) if (p.holds(x, y, z)) return p;
        }
        return null;
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || FIELDS.isEmpty() || !(event.level instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % 20L != 0L) return;
        boolean douse = now % 100L == 0L;
        if (douse) EXPOSED.values().removeIf(x -> now - x[1] > RECOVER);
        for (java.util.Iterator<Field> it = FIELDS.values().iterator(); it.hasNext(); ) {
            Field f = it.next();
            if (!f.dimension.equals(level.dimension())) continue;
            if (f.until < now) {
                it.remove();
                continue;
            }
            for (Pocket p : f.pockets) {
                if (!PyroclasticFlow.ticking(level, p.x(), p.z())) continue;
                breathe(level, p);
                if (douse) {
                    douse(level, p);
                    wither(level, p);
                }
            }
        }
    }

    /**
     * How many seconds running something has breathed the gas, and when it last did: a few breaths only make it short of
     * breath, and only staying in it does harm. A breath out of the gas for {@link #RECOVER} ticks and it is over.
     */
    private static final Map<java.util.UUID, long[]> EXPOSED = new HashMap<>();
    /** Seconds with the head in the gas before it tires, before the head swims, before it harms, and how often then. */
    private static final int TIRES = 3, SWIMS = 10, HARMS = 20, HARM_EVERY = 4;
    private static final long RECOVER = 200L;

    /**
     * Whatever breathes in a pocket, with its head in it (the gas lies low; standing in it to the knees does nothing):
     * short of breath and slow at first, dizzy after a while, and only one that stays half a minute in it is harmed, a
     * little at a time. The harm goes past armour and wears none of it, as a lack of air would.
     */
    private static void breathe(ServerLevel level, Pocket p) {
        AABB box = new AABB(p.x() - p.r(), p.floor(), p.z() - p.r(), p.x() + p.r() + 1, p.top() + 1, p.z() + p.r() + 1);
        DamageSource source = null;
        long now = level.getGameTime();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e instanceof Player pl && (pl.isCreative() || pl.isSpectator())) continue;
            if (e.getMobType() == MobType.UNDEAD) continue;                  // the dead do not breathe
            if (!p.holds(e.getX(), e.getEyeY(), e.getZ())) continue;
            long[] x = EXPOSED.computeIfAbsent(e.getUUID(), u -> new long[2]);
            if (x[1] == now) continue;                                       // in two pockets at once: one breath
            if (now - x[1] > RECOVER) x[0] = 0;
            x[0]++;
            x[1] = now;
            if (x[0] < TIRES) continue;
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 50, 0, false, false));
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 0, false, false));
            if (x[0] >= SWIMS) e.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0, false, false));
            if (x[0] < HARMS || (x[0] - HARMS) % HARM_EVERY != 0) continue;
            if (source == null) {
                source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DAMAGE));
            }
            if (e.hurt(source, 1.0f)) sickened++;
        }
    }

    /** Flames in a pocket go out: torches are knocked out and dropped, candles and campfires snuffed, fire put out. */
    private static void douse(ServerLevel level, Pocket p) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -p.r(); dx <= p.r(); dx++) {
            for (int dz = -p.r(); dz <= p.r(); dz++) {
                for (int y = p.floor(); y <= p.top(); y++) {
                    m.set(p.x() + dx, y, p.z() + dz);
                    BlockState s = level.getBlockState(m);
                    if (s.isAir()) continue;
                    boolean out = false;
                    if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.SOUL_TORCH) || s.is(Blocks.SOUL_WALL_TORCH)) {
                        level.destroyBlock(m.immutable(), true);
                        out = true;
                    } else if (s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) {
                        level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        out = true;
                    } else if (s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT)
                            && (s.getBlock() instanceof CampfireBlock
                            || s.getBlock() instanceof net.minecraft.world.level.block.AbstractCandleBlock)) {
                        if (s.getBlock() instanceof CampfireBlock) CampfireBlock.dowse(null, level, m, s);
                        level.setBlock(m, s.setValue(BlockStateProperties.LIT, false), Block.UPDATE_ALL);
                        out = true;
                    }
                    if (out) {
                        flames++;
                        level.sendParticles(ParticleTypes.SMOKE, m.getX() + 0.5, m.getY() + 0.6, m.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
                        level.playSound(null, m, SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.8f);
                    }
                }
            }
        }
    }

    /** How often, one in so many looks, a plant in the gas dies, or the grass under it. */
    private static final int WITHER = 12;

    /**
     * The plants in a pocket die off: grass, flowers and saplings go, leaves drop, and the grass under them turns to
     * bare soil -- the dead ground a gas pocket shows by, since the gas itself is never seen.
     */
    private static void wither(ServerLevel level, Pocket p) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -p.r(); dx <= p.r(); dx++) {
            for (int dz = -p.r(); dz <= p.r(); dz++) {
                if (level.random.nextInt(WITHER) != 0) continue;
                for (int y = p.top(); y >= p.floor() - 1; y--) {
                    m.set(p.x() + dx, y, p.z() + dz);
                    BlockState s = level.getBlockState(m);
                    if (s.isAir()) continue;
                    if (TerrainProbe.isVegetation(s) && !s.is(Blocks.SNOW) || s.is(net.minecraft.tags.BlockTags.LEAVES)) {
                        level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        withered++;
                        break;
                    }
                    if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)) {
                        level.setBlock(m, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
                        withered++;
                    }
                    break;
                }
            }
        }
    }

    private static int withered;

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "gas: %d breaths of it, %d flames out, %d plants withered",
                sickened, flames, withered);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FIELDS.clear();
        EXPOSED.clear();
    }
}
