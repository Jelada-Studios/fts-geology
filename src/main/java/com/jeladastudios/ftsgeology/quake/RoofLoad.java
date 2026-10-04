package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Cave roofs that give way under water, or once the water holding them up is gone.
 *
 * <p>A cave's roof is an arch, and an arch holds what it has held for ten thousand years. Change what it carries and
 * it may not: water brought in over it -- a reservoir, a pond dug and filled, a flood that stays -- weighs as much
 * as two-fifths of the rock, and soaks the joints it stands on; and in karst, where the caves were cut by the water
 * and stood full of it, a well's cone drawing the water down out of them takes away the lift that held the roof up,
 * which is how the obruks of the Konya plain open under the fields their wells water.</p>
 *
 * <p>So not everywhere: a lake the world was made with is the load the roof grew up under, and only water brought
 * later, or drawn away later, counts (the water at a place is taken as it first stood; see {@code SoilWater}) -- and
 * only water that stays: it has to stand a while before the roof is looked at, and still stand when the roof would go,
 * so a flood passing by or another mod's water finding its level brings nothing down. And
 * only a roof that was nearly failing anyway -- thin, wide, in soft or soluble rock -- goes: a thick roof, or granite,
 * holds a lake. A roof about to go warns first: water drips from it, it creaks and sheds grit, for a minute or two.
 * Then it comes down, and the water over it pours into the cave; where the roof was thin, the ground opens. A karst roof
 * the wells drew the water out from under takes days ({@code sinkholeWarningDays}): the ground over it cracks, then
 * settles a block in a ring, the signs a farmer on the Konya plain learns to read, and only then does the sinkhole
 * open. What is going is kept with the world.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class RoofLoad {

    private RoofLoad() {}

    /** Ticks a roof under water warns before it goes, at least, and the most more. */
    private static final int WARN = 1200, WARN_MORE = 1200;
    /** Ticks after a column was looked at before it is looked at again. */
    private static final long AGAIN = 24000;

    /**
     * A roof on its way down: when it goes, when it started to, the water that must still stand over it then (0 for a
     * roof going for want of water), whether it is karst the wells drew down, and how far its signs at the surface have
     * gone (1 cracks, 2 the ring sunk).
     */
    static final class Going {
        long due, start;
        int hold;
        boolean drawn;
        byte signs;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Due", due);
            t.putLong("Start", start);
            t.putInt("Hold", hold);
            t.putBoolean("Drawn", drawn);
            t.putByte("Signs", signs);
            return t;
        }

        static Going load(CompoundTag t) {
            Going g = new Going();
            g.due = t.getLong("Due");
            g.start = t.getLong("Start");
            g.hold = t.getInt("Hold");
            g.drawn = t.getBoolean("Drawn");
            g.signs = t.getByte("Signs");
            return g;
        }
    }

    /** The roofs going, by column, kept with the world: a sinkhole takes days to come. */
    static final class Store extends SavedData {
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Going> going = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            CompoundTag g = tag.getCompound("Going");
            for (String k : g.getAllKeys()) s.going.put(Long.parseLong(k), Going.load(g.getCompound(k)));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag g = new CompoundTag();
            for (var e : going.long2ObjectEntrySet()) g.put(Long.toString(e.getLongKey()), e.getValue().save());
            tag.put("Going", g);
            return tag;
        }
    }

    private static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store::load, Store::new, "fts_geology_roof_load");
    }

    /** Columns looked at, to when. */
    private static final Long2LongOpenHashMap LOOKED = new Long2LongOpenHashMap();
    private static long considered, warned, fell, held, cracked, sunk, refills;
    private static int goingNow;

    public static void clear() {
        LOOKED.clear();
        considered = warned = fell = held = cracked = sunk = refills = 0;
        goingNow = 0;
    }

    private static boolean soluble(BlockState s) {
        return s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
                || s.is(com.jeladastudios.ftsgeology.registry.ModBlocks.MARBLE.get())
                || s.is(com.jeladastudios.ftsgeology.registry.ModBlocks.TRAVERTINE.get());
    }

    /**
     * A column whose load has changed: {@code water} blocks of water standing over it more than it first had, or its
     * cave drained {@code drained} blocks below its roof by the wells round it. Its roof is looked at, once a game day
     * at most, and set to go if it cannot carry the change: under water in a minute or two, and for want of water -- the
     * karst the wells drew down -- over days, the ground cracking and then sinking in a ring first.
     */
    public static void consider(ServerLevel level, int x, int z, double water, double drained) {
        if (!GeyserConfig.WATER_LOAD_COLLAPSE.get() || TfcCompat.active()) return;
        long k = BlockPos.asLong(x, 0, z);
        long now = level.getGameTime();
        Store st = store(level);
        if (st.going.containsKey(k) || now - LOOKED.getOrDefault(k, Long.MIN_VALUE / 2) < AGAIN) return;
        LOOKED.put(k, now);
        considered++;
        CaveCollapse.Cave cave = CaveCollapse.caveUnder(level, x, z);
        if (cave == null || cave.top() - cave.floor() >= CaveCollapse.DEEP_VOID) return;
        BlockState roofRock = level.getBlockState(new BlockPos(x, cave.top() + 1, z));
        boolean karst = soluble(roofRock);
        // Drained caves are the karst's: a cave in granite was never held up by the water in it.
        if (drained > 0 && !karst) return;
        int span = CaveCollapse.span(level, x, cave.top(), z);
        double arch = Mth.clamp(span / (cave.roof() * 0.5 + 2.0), 0.0, 1.5) / 1.5;
        double weak = Math.min(1.0, RockTypes.erodibility(roofRock) + (karst ? 0.3 : 0.0));
        // Water weighs two-fifths of rock; a drained karst roof loses up to half again of its support.
        double load = 1.0 + water / (2.5 * cave.roof()) + 0.5 * Math.min(1.0, drained / 3.0);
        double stress = arch * load * (0.4 + 0.6 * weak);
        if (stress < 1.0) return;
        if (level.random.nextDouble() >= Mth.clamp((stress - 1.0) / 0.4, 0.15, 1.0)) return;
        Going g = new Going();
        g.start = now;
        g.drawn = water <= 0 && drained > 0;
        if (g.drawn) {
            long days = (long) (GeyserConfig.SINKHOLE_WARNING_DAYS.get() * 24000.0);
            g.due = now + Math.max(WARN, (long) (days * (0.75 + 0.5 * level.random.nextDouble())));
        } else {
            g.due = now + WARN + level.random.nextInt(WARN_MORE);
            // The roof goes only if the water is still there: half the rise gone again, and it holds.
            g.hold = Math.max(1, waterOver(level, x, z) - (int) Math.ceil(water / 2.0));
        }
        st.going.put(k, g);
        st.setDirty();
        warned++;
        com.jeladastudios.ftsgeology.util.Diagnostics.info("roof load: a cave roof at {} {} (roof {}, span {}, {}) is giving way under {} blocks of water{}, in {} s",
                x, z, cave.roof(), span, roofRock.getBlock().getName().getString(), String.format(java.util.Locale.ROOT, "%.1f", water),
                drained > 0 ? String.format(java.util.Locale.ROOT, ", its cave drained %.1f blocks", drained) : "", (g.due - now) / 20);
    }

    /**
     * A column over ground the wells round it have drawn down {@code lowered} blocks from its own water table at
     * {@code naturalTable}: a cave under it that stood below that table and now stands above the lowered one has lost
     * the water that held its roof up, by as much as it now stands clear.
     */
    public static void drawnDown(ServerLevel level, int x, int z, int naturalTable, double lowered) {
        if (!GeyserConfig.WATER_LOAD_COLLAPSE.get()) return;
        long k = BlockPos.asLong(x, 0, z);
        if (store(level).going.containsKey(k) || level.getGameTime() - LOOKED.getOrDefault(k, Long.MIN_VALUE / 2) < AGAIN) return;
        CaveCollapse.Cave cave = CaveCollapse.caveUnder(level, x, z);
        if (cave == null || cave.top() > naturalTable) {
            LOOKED.put(k, level.getGameTime());
            return;
        }
        double drained = Math.min(lowered, cave.top() - (naturalTable - lowered));
        if (drained <= 0) return;
        consider(level, x, z, 0, drained);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null) return;
        long now = level.getGameTime();
        if (goingNow == 0 && now % 100 != 0) return;
        Store st = level.getDataStorage().get(Store::load, "fts_geology_roof_load");
        goingNow = st == null ? 0 : st.going.size();
        if (goingNow == 0) return;
        List<Long> go = new ArrayList<>(), back = new ArrayList<>();
        for (var e : st.going.long2ObjectEntrySet()) {
            int x = BlockPos.getX(e.getLongKey()), z = BlockPos.getZ(e.getLongKey());
            if (!com.jeladastudios.ftsgeology.util.Loaded.chunk(level, x >> 4, z >> 4)) continue;
            Going g = e.getValue();
            if (now >= g.due) {
                go.add(e.getLongKey());
                continue;
            }
            if (g.drawn && now % 100 == 0 && refilled(level, x, z)) {
                // The pumps stopped in time: the water is back up in the cave, and holds the roof up again.
                back.add(e.getLongKey());
                continue;
            }
            if (g.drawn) {
                // The karst's slow way down: the ground cracks a third of the way, the ring round it sinks at two.
                double done = (now - g.start) / (double) Math.max(1, g.due - g.start);
                if (g.signs < 1 && done >= 1.0 / 3.0) {
                    g.signs = 1;
                    cracks(level, x, z);
                    st.setDirty();
                } else if (g.signs < 2 && done >= 2.0 / 3.0) {
                    g.signs = 2;
                    ring(level, x, z);
                    st.setDirty();
                }
                if (g.due - now > WARN_MORE + WARN || now % 10 != 0) continue;
            } else if (now % 10 != 0) {
                continue;
            }
            warn(level, x, z);
        }
        for (long k : back) {
            st.going.remove(k);
            st.setDirty();
            refills++;
            com.jeladastudios.ftsgeology.util.Diagnostics.info("roof load: the water has come back up under {} {} before its roof went; it holds",
                    BlockPos.getX(k), BlockPos.getZ(k));
        }
        for (long k : go) {
            Going g = st.going.remove(k);
            st.setDirty();
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            if (g.hold > 0 && waterOver(level, x, z) < g.hold) {
                held++;
                com.jeladastudios.ftsgeology.util.Diagnostics.info("roof load: the water over {} {} has gone down again; the roof holds", x, z);
                continue;
            }
            fall(level, x, z);
        }
    }

    /** Whether the water under a karst roof the wells drew down stands up in its cave again, over the cave's top. */
    private static boolean refilled(ServerLevel level, int x, int z) {
        CaveCollapse.Cave cave = CaveCollapse.caveUnder(level, x, z);
        return cave != null && com.jeladastudios.ftsgeology.hydrology.Aquifer.waterY(level, x, z) >= cave.top() + 1;
    }

    /** How wide the ground over a going roof is felt to move: a third of the cave's span, two to five blocks. */
    private static int reach(ServerLevel level, int x, int z, CaveCollapse.Cave cave) {
        return Mth.clamp(CaveCollapse.span(level, x, cave.top(), z) / 3, 2, 5);
    }

    /** Whether a block is ground nobody built: soil, sand, gravel, rock. */
    private static boolean naturalTop(ServerLevel level, BlockPos p, BlockState s) {
        if (s.isAir() || s.hasBlockEntity() || EruptionHandler.isPlayerPlaced(s) || !s.getFluidState().isEmpty()) return false;
        return !PlayerBuilt.inChunk(level, p.getX() >> 4, p.getZ() >> 4).contains(p.asLong());
    }

    /** The ground over the roof cracks: its soil goes coarse in lines, and it grinds. */
    private static void cracks(ServerLevel level, int x, int z) {
        CaveCollapse.Cave cave = CaveCollapse.caveUnder(level, x, z);
        if (cave == null) return;
        int r = reach(level, x, z, cave);
        int n = 0;
        for (int i = 0; i < 4 * r; i++) {
            double a = level.random.nextDouble() * Math.PI * 2, d = level.random.nextDouble() * (r + 1);
            int cx = x + (int) Math.round(Math.cos(a) * d), cz = z + (int) Math.round(Math.sin(a) * d);
            int g = TerrainProbe.groundY(level, cx, cz);
            if (g == Integer.MIN_VALUE) continue;
            BlockPos p = new BlockPos(cx, g, cz);
            BlockState s = level.getBlockState(p);
            if (!naturalTop(level, p, s) || !s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(Blocks.COARSE_DIRT)) continue;
            level.setBlock(p, Blocks.COARSE_DIRT.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            n++;
        }
        cracked++;
        level.playSound(null, x + 0.5, cave.ground(), z + 0.5, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.2F, 0.5F);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("roof load: the ground over {} {} cracks ({} blocks)", x, z, n);
    }

    /** The ground over the roof sinks a block in a ring round the middle, the way a sinkhole's rim settles before it opens. */
    private static void ring(ServerLevel level, int x, int z) {
        CaveCollapse.Cave cave = CaveCollapse.caveUnder(level, x, z);
        if (cave == null) return;
        int r = reach(level, x, z, cave);
        int n = 0;
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (Math.abs(d - r) > 0.7) continue;
                int cx = x + dx, cz = z + dz;
                if (!com.jeladastudios.ftsgeology.util.Loaded.chunk(level, cx >> 4, cz >> 4)) continue;
                int g = TerrainProbe.groundY(level, cx, cz);
                if (g == Integer.MIN_VALUE) continue;
                BlockPos p = new BlockPos(cx, g, cz), under = p.below();
                BlockState s = level.getBlockState(p), below = level.getBlockState(under);
                if (!naturalTop(level, p, s) || !naturalTop(level, under, below)) continue;
                BlockState over = level.getBlockState(p.above());
                if (!over.isAir() && !TerrainProbe.isVegetation(over)) continue;
                if (!over.isAir()) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.setBlock(under, s, net.minecraft.world.level.block.Block.UPDATE_ALL);
                n++;
            }
        }
        sunk++;
        level.playSound(null, x + 0.5, cave.ground(), z + 0.5, SoundEvents.GRAVEL_HIT, SoundSource.BLOCKS, 1.5F, 0.4F);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("roof load: the ground over {} {} sinks a block in a ring ({} columns)", x, z, n);
    }

    /** Water dripping from the roof, grit, and a creak now and then. */
    private static void warn(ServerLevel level, int x, int z) {
        CaveCollapse.Cave cave = CaveCollapse.caveUnder(level, x, z);
        if (cave == null) return;
        level.sendParticles(ParticleTypes.DRIPPING_WATER, x + 0.5, cave.top() + 0.9, z + 0.5, 3, 1.2, 0.0, 1.2, 0.0);
        level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST,
                        level.getBlockState(new BlockPos(x, cave.top() + 1, z))), x + 0.5, cave.top() + 0.8, z + 0.5, 2, 1.0, 0.0, 1.0, 0.0);
        if (level.random.nextInt(4) == 0) {
            level.playSound(null, x + 0.5, cave.top(), z + 0.5, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, SoundSource.BLOCKS, 1.0F, 0.8F);
        }
        if (level.random.nextInt(12) == 0) {
            level.playSound(null, x + 0.5, cave.top(), z + 0.5, SoundEvents.GRAVEL_HIT, SoundSource.BLOCKS, 1.5F, 0.5F);
        }
    }

    /**
     * The roof comes down: over a funnel as wide as a third of the span, the whole roof where it is thin, the bottom
     * of it where it is thick, heaped on the cave's floor. Water over it is let through; a build over it is not.
     */
    private static void fall(ServerLevel level, int x, int z) {
        CaveCollapse.Cave cave = CaveCollapse.caveUnder(level, x, z);
        if (cave == null) return;
        int span = CaveCollapse.span(level, x, cave.top(), z);
        boolean through = cave.roof() <= CaveCollapse.THIN_ROOF;
        int radius = Mth.clamp(span / 3, through ? 2 : 1, through ? 5 : 4);
        List<BlockState> pool = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int floor = cave.floor();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + radius) continue;
                int cx = x + dx, cz = z + dz;
                if (!com.jeladastudios.ftsgeology.util.Loaded.chunk(level, cx >> 4, cz >> 4)) continue;
                CaveCollapse.Cave c = CaveCollapse.caveUnder(level, cx, cz);
                if (c == null || Math.abs(c.top() - cave.top()) > 4) continue;
                int n = through ? (int) Math.round(c.roof() * (1.0 - Math.sqrt(dx * dx + dz * dz) / (radius + 1.0)))
                        : Math.min(2 + level.random.nextInt(3), c.roof() - 2);
                if (n <= 0) continue;
                // Rubble lands only in open air; and nothing built, no machine, no bedrock comes down.
                boolean open = true;
                for (int y = c.floor(); y <= c.top() && open; y++) open = level.getBlockState(m.set(cx, y, cz)).isAir();
                if (!open) continue;
                BlockState[] slab = new BlockState[n];
                boolean ok = true;
                for (int i = 0; i < n && ok; i++) {
                    BlockState s = level.getBlockState(m.set(cx, c.top() + 1 + i, cz));
                    ok = !s.is(Blocks.BEDROCK) && !s.hasBlockEntity() && !QuakePlanner.machinery(s)
                            && s.getFluidState().isEmpty() && !EruptionHandler.isPlayerPlaced(s);
                    slab[i] = s;
                }
                if (!ok) continue;
                if (n >= c.roof()) TerrainProbe.clearVegetation(level, cx, c.ground(), cz, 2);
                for (int i = 0; i < n; i++) {
                    m.set(cx, c.top() + 1 + i, cz);
                    level.setBlock(m, TfcCompat.translate(level, m, Blocks.AIR.defaultBlockState()),
                            net.minecraft.world.level.block.Block.UPDATE_ALL);
                    pool.add(CaveCollapse.rubble(slab[i], level.random));
                }
                floor = Math.min(floor, c.floor());
            }
        }
        if (pool.isEmpty()) return;
        fell++;
        com.jeladastudios.ftsgeology.hydrology.HydraulicsHooks.moved(level, x >> 4, z >> 4,
                com.jeladastudios.ftsgeology.api.RiverBlocksChangedEvent.Cause.COLLAPSE);
        CaveCollapse.heap(level, x, z, floor, pool);
        level.sendParticles(ParticleTypes.CLOUD, x + 0.5, cave.top(), z + 0.5, 30, radius * 0.6, 0.5, radius * 0.6, 0.02);
        level.playSound(null, x + 0.5, cave.top(), z + 0.5, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 3.0F, 0.5F);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("roof load: the roof at {} {} came down, {} blocks, {}", x, z, pool.size(),
                through ? "through to the surface" : "its bottom only");
    }

    public static boolean any() {
        return cracked + sunk + considered > 0;
    }

    /** Blocks of water standing on the ground at a column. */
    private static int waterOver(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int d = 0;
        while (d < 32 && level.getFluidState(m.set(x, g + 1 + d, z)).is(net.minecraft.tags.FluidTags.WATER)) d++;
        return d;
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "roof load: %d columns looked at, %d roofs warned, %d came down, %d held once the water went, %d going; sinkholes: %d cracked, %d rings sunk, %d called off as the water came back",
                considered, warned, fell, held, goingNow, cracked, sunk, refills);
    }
}
