package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.network.FlowPacket;
import com.jeladastudios.ftsgeology.network.ModNetwork;
import com.jeladastudios.ftsgeology.quake.Collapse;
import com.jeladastudios.ftsgeology.quake.PlayerBuilt;
import com.jeladastudios.ftsgeology.quake.QuakeQuiet;
import com.jeladastudios.ftsgeology.quake.ShakingDamage;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A pyroclastic flow: part of an explosive eruption's column falling back and pouring down the mountain as an avalanche
 * of hot ash, rock and gas, as at Mount Pelée in 1902, Mount St. Helens in 1980 and Unzen in 1991.
 *
 * <h2>How far it runs</h2>
 * By the energy line of Heim and of Malin and Sheridan: a flow falling from a height reaches whatever ground lies under
 * a line dropping from that height at a fixed slope, about one in five, as it runs out. The height of that line over the
 * ground under the front is what drives it -- fast down the steep flank, slowing as the ground levels out, and able to
 * run up over a low rise while the line is still above it. It follows the fall of the ground, keeping some of its way,
 * so it runs down the valleys; it spreads where the ground opens out.
 *
 * <h2>What it does</h2>
 * Whatever is caught in the cloud is burnt; plants are stripped from the ground and leaves from the trees, which are
 * left standing bare; glass shatters, wool and hay burn, and wooden buildings catch fire. It leaves a bed of ash along
 * its path, thickest on the valley floor and thickest of all in the lobe where it stops.
 *
 * <p>Nothing of a flow is saved: one lasts seconds, and a restart simply ends it.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class PyroclasticFlow {

    private PyroclasticFlow() {}

    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            new ResourceLocation(GeysersMod.MODID, "pyroclastic_flow"));

    /** The energy line's fall per block of run. */
    private static final double HEIM = 0.22;
    /** How far a flow may run at most, from the rim: a floor and so much per magnitude, times the config. */
    private static final double RUN = 64.0, RUN_PER_MAGNITUDE = 10.0;
    /** How high over the rim the collapsing column falls from: a floor and so much per magnitude. */
    private static final double FALL = 6.0, FALL_PER_MAGNITUDE = 0.5;
    /** Most flows moving at once, over the whole server. */
    private static final int MOST = 8;
    /** Steps over which a flow that has got less than {@link #POND_REACH} blocks anywhere has ponded in a hollow. */
    private static final int POND_STEPS = 40;
    private static final double POND_REACH = 14.0;
    /** How far over the ground under the front the cloud still burns what is in it. */
    private static final int CLOUD = 12;

    private static final class Flow {
        final ResourceKey<Level> dimension;
        final int id;
        final BlockPos summit;
        final int craterR;
        final double top, maxRun;
        double x, z, hx, hz, run, carry;
        int frontY, width = 2, ticks, steps;
        /** Where the front was over its last steps, to tell a flow that has stopped getting anywhere. */
        final double[] backX = new double[POND_STEPS], backZ = new double[POND_STEPS];
        boolean done;
        /** Why it stopped, for the log. */
        String why = "time";
        final LongOpenHashSet swept = new LongOpenHashSet();
        final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
        final Long2ObjectOpenHashMap<Built> built = new Long2ObjectOpenHashMap<>();
        final Set<UUID> caught = new HashSet<>();
        int columns, plants, leaves, glass, fires, burnt;

        Flow(ResourceKey<Level> dimension, int id, BlockPos summit, int craterR, double top, double maxRun) {
            this.dimension = dimension;
            this.id = id;
            this.summit = summit;
            this.craterR = craterR;
            this.top = top;
            this.maxRun = maxRun;
            ground.defaultReturnValue(Integer.MIN_VALUE);
        }

        double energy() {
            return top - HEIM * run;
        }
    }

    /** What is built in one chunk, read once per flow. */
    private record Built(LongSet placed, List<BoundingBox> pieces) {}

    private static final List<Flow> FLOWS = new ArrayList<>();
    private static int nextId = 1;

    /**
     * Whether a volcano's cone is steep enough to be built of sticky, gas-rich magma, for a volcano saved before its
     * type was kept: its flank falls by nearly half a block a block below the rim, where a shield's falls by a fifth.
     */
    public static boolean steepCone(ServerLevel level, BlockPos summit, int craterR) {
        double drop = 0;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int x0 = summit.getX() + (int) Math.round(Math.cos(a) * (craterR + 4));
            int z0 = summit.getZ() + (int) Math.round(Math.sin(a) * (craterR + 4));
            int x1 = summit.getX() + (int) Math.round(Math.cos(a) * (craterR + 24));
            int z1 = summit.getZ() + (int) Math.round(Math.sin(a) * (craterR + 24));
            if (!ticking(level, x0, z0) || !ticking(level, x1, z1)) continue;
            int g0 = TerrainProbe.groundY(level, x0, z0), g1 = TerrainProbe.groundY(level, x1, z1);
            if (g0 == Integer.MIN_VALUE || g1 == Integer.MIN_VALUE) continue;
            drop += g0 - g1;
            n++;
        }
        return n >= 4 && drop / n / 20.0 >= 0.45;
    }

    /**
     * Sends a flow down the mountain, over the lowest notch of its crater rim: the rim is sampled all round and one of
     * its lowest points taken, so successive flows find different valleys.
     */
    public static void start(ServerLevel level, BlockPos summit, int magnitude, int craterR) {
        if (GeyserConfig.PYROCLASTIC_FLOWS.get() <= 0 || FLOWS.size() >= MOST) return;
        if (QuakeQuiet.isQuiet(level, summit)) return;
        int r = craterR + 3;
        int bearings = 24;
        double[] a = new double[bearings];
        int[] g = new int[bearings];
        int known = 0;
        for (int i = 0; i < bearings; i++) {
            a[i] = i * 2 * Math.PI / bearings;
            int x = summit.getX() + (int) Math.round(Math.cos(a[i]) * r);
            int z = summit.getZ() + (int) Math.round(Math.sin(a[i]) * r);
            g[i] = ticking(level, x, z) ? TerrainProbe.groundY(level, x, z) : Integer.MAX_VALUE;
            if (g[i] != Integer.MAX_VALUE && g[i] != Integer.MIN_VALUE) known++;
            else g[i] = Integer.MAX_VALUE;
        }
        if (known < bearings / 2) return;
        // One of the four lowest points of the rim.
        Integer[] order = new Integer[bearings];
        for (int i = 0; i < bearings; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> g[i]));
        int pick = order[level.random.nextInt(4)];
        if (g[pick] == Integer.MAX_VALUE) return;
        double hx = Math.cos(a[pick]), hz = Math.sin(a[pick]);
        double fall = FALL + FALL_PER_MAGNITUDE * magnitude;
        double top = Math.max(summit.getY(), g[pick]) + fall;
        double maxRun = (RUN + RUN_PER_MAGNITUDE * magnitude) * GeyserConfig.PYROCLASTIC_FLOW_REACH.get();
        Flow f = new Flow(level.dimension(), nextId++, summit.immutable(), craterR, top, maxRun);
        f.x = summit.getX() + 0.5 + hx * r;
        f.z = summit.getZ() + 0.5 + hz * r;
        f.hx = hx;
        f.hz = hz;
        f.frontY = g[pick];
        FLOWS.add(f);

        level.playSound(null, summit.getX(), g[pick], summit.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                6.0f, 0.35f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, f.x, g[pick] + 2, f.z, 1, 0, 0, 0, 0);
        // Everyone within reach is told which way it is coming down, as an observatory would.
        String way = com.jeladastudios.ftsgeology.instrument.Prospecting.bearingOf((int) Math.round(hx * 100),
                (int) Math.round(hz * 100));
        Component warn = Component.translatable("message.fts_geology.pyroclastic_flow",
                Component.translatable("prospect.fts_geology.dir." + way)).withStyle(net.minecraft.ChatFormatting.RED);
        double told = maxRun + 96;
        for (ServerPlayer p : level.players()) {
            if (Math.hypot(p.getX() - summit.getX(), p.getZ() - summit.getZ()) <= told) p.displayClientMessage(warn, true);
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info("Pyroclastic flow {} from {} down the {} flank, energy line from {}, run to {}",
                f.id, summit, way, (int) Math.round(top), (int) Math.round(maxRun));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || FLOWS.isEmpty() || !(event.level instanceof ServerLevel level)) return;
        for (java.util.Iterator<Flow> it = FLOWS.iterator(); it.hasNext(); ) {
            Flow f = it.next();
            if (!f.dimension.equals(level.dimension())) continue;
            tick(level, f);
            if (f.done) {
                it.remove();
                ModNetwork.sendFlow(level, new FlowPacket(f.id, f.x, f.frontY + 1, f.z, f.width, true));
                com.jeladastudios.ftsgeology.util.Diagnostics.info(
                        "Pyroclastic flow {} stopped ({}) after {} blocks at {} {} in {} s: {} columns under ash, {} plants and {} leaves burnt, {} glass shattered, {} fires, {} caught",
                        f.id, f.why, (int) Math.round(f.run), (int) Math.round(f.x), (int) Math.round(f.z), f.ticks / 20,
                        f.columns, f.plants, f.leaves, f.glass, f.fires, f.burnt);
            }
        }
    }

    private static void tick(ServerLevel level, Flow f) {
        f.ticks++;
        double head = Math.max(0.0, f.energy() - f.frontY);
        double speed = Math.max(0.35, Math.min(1.6, 0.35 + 0.1 * Math.sqrt(head)));
        f.carry += speed;
        while (f.carry >= 1.0 && !f.done) {
            f.carry -= 1.0;
            step(level, f);
        }
        if (f.ticks > 1200) f.done = true;       // a minute is more than any flow runs
        if (f.done) {
            terminus(level, f);
            return;
        }
        engulf(level, f, head);
        if (f.ticks % 2 == 0) ModNetwork.sendFlow(level, new FlowPacket(f.id, f.x, f.frontY + 1, f.z, f.width, false));
        if (f.ticks % 30 == 0) {
            level.playSound(null, f.x, f.frontY + 2, f.z, SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), SoundSource.BLOCKS,
                    4.0f, 0.5f);
        }
        if (f.ticks % 6 == 0) {
            level.playSound(null, f.x, f.frontY + 1, f.z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 2.5f, 0.6f);
        }
    }

    /** The front moves on a block: down the fall of the ground, keeping some of its way. */
    private static void step(ServerLevel level, Flow f) {
        int ix = (int) Math.floor(f.x), iz = (int) Math.floor(f.z);
        int here = f.frontY;
        double gx = slope(level, f, ix + 2, iz, ix - 2, iz, here), gz = slope(level, f, ix, iz + 2, ix, iz - 2, here);
        double dx = -gx, dz = -gz, g = Math.hypot(dx, dz);
        if (g > 0.05) {
            // The faster it runs the more of its way it keeps: a fast flow runs over a hollow a slow one turns into.
            double fast = Math.max(0.0, f.energy() - here);
            double w = Math.min(0.6, 0.25 + 0.35 * g) / (1.0 + fast / 25.0);
            f.hx = f.hx * (1 - w) + dx / g * w;
            f.hz = f.hz * (1 - w) + dz / g * w;
        }
        // Out over the rim, not back into the crater, for the first stretch.
        if (f.run < f.craterR / 2.0 + 6) {
            double ox = f.x - f.summit.getX() - 0.5, oz = f.z - f.summit.getZ() - 0.5, o = Math.hypot(ox, oz);
            if (o > 0.1 && (f.hx * ox + f.hz * oz) / o < 0.3) {
                f.hx += ox / o;
                f.hz += oz / o;
            }
        }
        double h = Math.hypot(f.hx, f.hz);
        if (h < 1e-6) {
            f.done = true;
            return;
        }
        f.hx /= h;
        f.hz /= h;
        double nx = f.x + f.hx, nz = f.z + f.hz;
        int cx = (int) Math.floor(nx), cz = (int) Math.floor(nz);
        if (!ticking(level, cx, cz)) {
            f.why = "edge of the loaded ground";
            f.done = true;
            return;
        }
        int ny = groundAt(level, f, cx, cz);
        if (ny == Integer.MIN_VALUE) {
            f.done = true;
            return;
        }
        f.run += 1.0;
        // Over water the flow rides on its own steam and loses its heat fast.
        if (TerrainProbe.hasFluidAbove(level, cx, cz)) f.run += 2.0;
        if (ny >= f.energy() - 1.0 || f.run >= f.maxRun) {
            f.why = f.run >= f.maxRun ? "reach" : "fall spent";
            f.done = true;
            return;
        }
        f.x = nx;
        f.z = nz;
        f.frontY = ny;
        // Going round and round in a hollow, it has filled it: it stops there.
        int slot = f.steps++ % POND_STEPS;
        if (f.steps > POND_STEPS && Math.hypot(nx - f.backX[slot], nz - f.backZ[slot]) < POND_REACH) {
            f.why = "ponded";
            f.done = true;
            return;
        }
        f.backX[slot] = nx;
        f.backZ[slot] = nz;
        double head = f.energy() - ny;
        // Wider the more it carries, and wider still where the ground opens out.
        f.width = (int) Math.max(2, Math.min(7, 2 + head / 8.0 + (g < 0.15 ? 1 : 0)));
        sweep(level, f, f.width, false);
    }

    /** The fall of the ground between two columns four blocks apart, per block; level where either is unknown. */
    private static double slope(ServerLevel level, Flow f, int x1, int z1, int x0, int z0, int here) {
        int a = ticking(level, x1, z1) ? groundAt(level, f, x1, z1) : Integer.MIN_VALUE;
        int b = ticking(level, x0, z0) ? groundAt(level, f, x0, z0) : Integer.MIN_VALUE;
        if (a == Integer.MIN_VALUE) a = here;
        if (b == Integer.MIN_VALUE) b = here;
        return (a - b) / 4.0;
    }

    /**
     * Whether a column's chunk ticks blocks. Its neighbours are then loaded, so what the flow changes there never loads
     * one: at the edge of the loaded ground the neighbour updates of a change loaded, and generated, the chunks past it.
     */
    static boolean ticking(ServerLevel level, int x, int z) {
        return level.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4));
    }

    private static int groundAt(ServerLevel level, Flow f, int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        int g = f.ground.get(key);
        if (g == Integer.MIN_VALUE && !f.ground.containsKey(key)) {
            g = TerrainProbe.groundY(level, x, z);
            f.ground.put(key, g);
        }
        return g;
    }

    /** Burns and buries every column the front has not yet passed over within {@code r} of it. */
    private static void sweep(ServerLevel level, Flow f, int r, boolean end) {
        int ix = (int) Math.floor(f.x), iz = (int) Math.floor(f.z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                int x = ix + dx, z = iz + dz;
                long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                if (!end && !f.swept.add(key)) continue;
                if (!ticking(level, x, z) || QuakeQuiet.isQuiet(level, x, z)) continue;
                int g = groundAt(level, f, x, z);
                if (g == Integer.MIN_VALUE || g > f.frontY + 6) continue;
                if (end) {
                    // The lobe where it stopped: deepest in the middle, a block and a half of ash.
                    if (g > f.frontY + 2) continue;
                    int layers = (int) Math.round(12 * (1.0 - Math.sqrt(d2) / (r + 1.0)));
                    if (layers > 0) VolcanoEruption.layAsh(level, x, g, z, layers);
                    continue;
                }
                burn(level, f, x, g, z);
                if (TerrainProbe.hasFluidAbove(level, x, z)) {
                    if (level.random.nextInt(4) == 0) {
                        level.sendParticles(ParticleTypes.CLOUD, x + 0.5, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 0.5,
                                z + 0.5, 4, 0.4, 0.3, 0.4, 0.05);
                    }
                    continue;
                }
                // A bed of ash, thicker on the valley floor than up its sides.
                if (g <= f.frontY + 2) {
                    int layers = 1 + Math.max(0, Math.min(2, f.frontY + 2 - g));
                    if (VolcanoEruption.layAsh(level, x, g, z, layers) > 0) f.columns++;
                }
            }
        }
    }

    /** Strips a column the cloud passes over: plants, leaves, snow, and the weak or burnable parts of buildings. */
    private static void burn(ServerLevel level, Flow f, int x, int g, int z) {
        BiomeScars.mark(level, x, g, z);
        int top = Math.min(g + CLOUD + 16, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        boolean fires = GeyserConfig.ERUPTIONS_START_FIRES.get();
        for (int y = g + 1; y <= top; y++) {
            m.set(x, y, z);
            BlockState s = level.getBlockState(m);
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (TerrainProbe.isVegetation(s) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) {
                // With neighbour updates, so the top half of a tall plant goes with its foot.
                level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                f.plants++;
                continue;
            }
            Built b = builtIn(level, f, x >> 4, z >> 4);
            if (Collapse.built(level, s, m, b.placed(), b.pieces())) {
                if (s.getSoundType() == SoundType.GLASS) {
                    level.destroyBlock(m, false);
                    f.glass++;
                } else if (s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || s.is(Blocks.HAY_BLOCK)) {
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    f.plants++;
                } else if (fires && level.random.nextInt(4) == 0 && s.isFlammable(level, m, Direction.UP)) {
                    BlockPos above = m.above();
                    if (level.isEmptyBlock(above) && BaseFireBlock.canBePlacedAt(level, above, Direction.UP)) {
                        level.setBlock(above, BaseFireBlock.getState(level, above), Block.UPDATE_ALL_IMMEDIATE);
                        f.fires++;
                    }
                }
                continue;
            }
            if (s.is(BlockTags.LEAVES) && y <= f.frontY + CLOUD + 16) {
                level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                f.leaves++;
            }
        }
    }

    private static Built builtIn(ServerLevel level, Flow f, int cx, int cz) {
        long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        Built b = f.built.get(key);
        if (b == null) {
            b = new Built(PlayerBuilt.inChunk(level, cx, cz),
                    ShakingDamage.structureBoxes(level, level.getChunk(cx, cz), level.getMinBuildHeight()));
            f.built.put(key, b);
        }
        return b;
    }

    /** Whatever is in the cloud burns, and is carried a little along with it. */
    private static void engulf(ServerLevel level, Flow f, double head) {
        int r = f.width + 3;
        AABB box = new AABB(f.x - r, f.frontY - 2, f.z - r, f.x + r, f.frontY + CLOUD, f.z + r);
        DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DAMAGE));
        float amount = (float) Math.min(10.0, 4.0 + Math.sqrt(head));
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            if (e.hurt(source, amount)) {
                e.setSecondsOnFire(8);
                e.push(f.hx * 0.25, 0.08, f.hz * 0.25);
                e.hurtMarked = true;
                if (f.caught.add(e.getUUID())) f.burnt++;
            }
        }
    }

    /** Where it stops, what it carried settles out: a lobe of ash round the last of the front. */
    private static void terminus(ServerLevel level, Flow f) {
        sweep(level, f, f.width + 2, true);
    }

    public static boolean active() {
        return !FLOWS.isEmpty();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FLOWS.clear();
    }
}
