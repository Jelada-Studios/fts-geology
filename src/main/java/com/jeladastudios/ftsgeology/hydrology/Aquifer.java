package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * The groundwater that wells draw on. Every well -- a pump on a well casing, or an open pipe end buried in the saturated
 * ground -- is kept with the story of how fast it has drawn, and the water table anywhere is lowered by the sum of their
 * cones: the Theis solution, the textbook answer for a well in a wide aquifer, laid over itself for each change of rate
 * (a pump switched off is the same well recharging at the rate it drew). So a cone spreads for as long as a well draws,
 * steep in tight rock and hardly there in gravel, and fills back in once it stops; many wells together lower a whole
 * plain.
 *
 * <p>The aquifer at a well is read off the rock round its intake: gravel and sand pass water freely, limestone through
 * its cracks, basalt through its joints, granite and slate hardly at all, clay not at all. Times are the ground's
 * ({@link SoilWater#HOURS_PER_TICK}): a game day is a week of pumping.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Aquifer {

    private Aquifer() {}

    /** How far from a well its cone is reckoned; beyond, it is taken to be nothing. */
    static final double REACH = 512;
    /** The radius of a well's bore, in blocks: the drawdown at the well itself. */
    static final double BORE = 0.5;
    /** Ticks over which a well's rate is measured before a change is written into its story. */
    static final int WINDOW = 100;
    /** Changes of rate kept for a well; older ones are folded into the oldest kept. */
    static final int STORY = 48;
    /** A well stopped this long, in ground hours, has refilled for all a player could tell, and is let go. */
    static final double FORGET_HOURS = 24 * 7 * 12;

    /** What the rock round an intake passes: transmissivity in square metres an hour, and the share of it that drains. */
    public record Rock(String name, double transmissivity, double yield) {}

    public static final Rock GRAVEL = new Rock("gravel", 60, 0.25), SAND = new Rock("sand", 30, 0.25),
            KARST = new Rock("limestone", 20, 0.05), BASALT = new Rock("basalt", 8, 0.05),
            SANDSTONE = new Rock("sandstone", 4, 0.15), CRYSTALLINE = new Rock("hard_rock", 0.5, 0.01),
            CLAY = new Rock("clay", 0.05, 0.03);

    /** The aquifer the rock round an intake makes: its most open block among the six beside it and the one under. */
    public static Rock rockAt(Level level, BlockPos intake) {
        Rock best = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int[] d : new int[][]{{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 0, 0}}) {
            Rock r = rockOf(level.getBlockState(m.setWithOffset(intake, d[0], d[1], d[2])));
            if (r != null && (best == null || r.transmissivity > best.transmissivity)) best = r;
        }
        return best == null ? CRYSTALLINE : best;
    }

    public static Rock rockOf(BlockState s) {
        if (s.is(Tags.Blocks.GRAVEL)) return GRAVEL;
        if (s.is(BlockTags.SAND)) return SAND;
        if (s.is(Tags.Blocks.SANDSTONE)) return SANDSTONE;
        if (s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(BlockTags.TERRACOTTA)) return CLAY;
        if (s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.TUFF)) return BASALT;
        if (s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)) return KARST;
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.contains("limestone") || path.contains("chalk") || path.contains("dolomite") || path.contains("marble")) return KARST;
        if (path.contains("basalt") || path.contains("scoria") || path.contains("tuff")) return BASALT;
        if (path.contains("sandstone") || path.contains("conglomerate")) return SANDSTONE;
        if (path.contains("shale") || path.contains("mudstone") || path.contains("claystone")) return CLAY;
        if (s.is(BlockTags.DIRT)) return CLAY;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Tags.Blocks.STONE)) return CRYSTALLINE;
        return null;
    }

    /** One well: where it draws, the rock it draws from, and each change in how fast, in cubic metres an hour. */
    static final class Well {
        final int x, z;
        Rock rock;
        /** Ground hours of each change, and the change in rate from it on. */
        final List<double[]> story = new ArrayList<>();
        /** Cubic metres drawn in the window now running, and the rate written last. */
        double drawn, rate;
        long windowStart = Long.MIN_VALUE;

        Well(int x, int z, Rock rock) {
            this.x = x;
            this.z = z;
            this.rock = rock;
        }

        /** Blocks the water stands lower at {@code r} blocks from this well, {@code hours} into the ground's time. */
        double drawdown(double r, double hours) {
            double t2 = 4 * rock.transmissivity;
            double s = 0;
            double rr = Math.max(r, BORE);
            for (double[] c : story) {
                double dt = hours - c[0];
                if (dt <= 0) continue;
                double u = rr * rr * rock.yield / (t2 * dt);
                s += c[1] / (4 * Math.PI * rock.transmissivity) * wellFunction(u);
            }
            // A recharge (a negative rate) raises the water back; the sum over all wells is kept from going over its level.
            return s;
        }
    }

    /** The Theis well function, the exponential integral E1(u) (Abramowitz and Stegun 5.1.53 and 5.1.56). */
    static double wellFunction(double u) {
        if (u <= 0) return 0;
        if (u <= 1) {
            return -Math.log(u) - 0.57721566 + u * (0.99999193 + u * (-0.24991055 + u * (0.05519968
                    + u * (-0.00976004 + u * 0.00107857))));
        }
        if (u > 50) return 0;
        double num = u * u + 2.334733 * u + 0.250621, den = u * u + 3.330657 * u + 1.681534;
        return Math.exp(-u) / u * num / den;
    }

    /** The wells of a level, kept with it. */
    static final class Wells extends SavedData {
        final Long2ObjectOpenHashMap<Well> byPos = new Long2ObjectOpenHashMap<>();

        static Wells load(CompoundTag tag) {
            Wells w = new Wells();
            for (Tag t : tag.getList("wells", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Rock rock = rock(c.getString("rock"));
                Well well = new Well(c.getInt("x"), c.getInt("z"), rock);
                long[] times = c.getLongArray("t");
                long[] rates = c.getLongArray("q");
                for (int i = 0; i < Math.min(times.length, rates.length); i++) {
                    well.story.add(new double[]{Double.longBitsToDouble(times[i]), Double.longBitsToDouble(rates[i])});
                }
                well.rate = c.getDouble("rate");
                w.byPos.put(BlockPos.asLong(well.x, c.getInt("y"), well.z), well);
            }
            return w;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (var e : byPos.long2ObjectEntrySet()) {
                Well w = e.getValue();
                CompoundTag c = new CompoundTag();
                c.putInt("x", w.x);
                c.putInt("y", BlockPos.getY(e.getLongKey()));
                c.putInt("z", w.z);
                c.putString("rock", w.rock.name);
                long[] t = new long[w.story.size()], q = new long[w.story.size()];
                for (int i = 0; i < t.length; i++) {
                    t[i] = Double.doubleToLongBits(w.story.get(i)[0]);
                    q[i] = Double.doubleToLongBits(w.story.get(i)[1]);
                }
                c.putLongArray("t", t);
                c.putLongArray("q", q);
                c.putDouble("rate", w.rate);
                list.add(c);
            }
            tag.put("wells", list);
            return tag;
        }
    }

    static Rock rock(String name) {
        for (Rock r : new Rock[]{GRAVEL, SAND, KARST, BASALT, SANDSTONE, CRYSTALLINE, CLAY}) if (r.name.equals(name)) return r;
        return CRYSTALLINE;
    }

    private static Wells wells(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Wells::load, Wells::new, "fts_geology_wells");
    }

    static double hours(ServerLevel level) {
        return level.getGameTime() * SoilWater.HOURS_PER_TICK;
    }

    // === Drawing ==========================================================

    /**
     * Water a well at {@code intake} takes now, in cubic metres; the well is kept from its first draw on. How fast it
     * draws is measured over a few seconds, and each real change is written into its story.
     */
    public static void draw(ServerLevel level, BlockPos intake, double cubicMetres) {
        if (!GeyserConfig.SOIL_WATER.get() || !Level.OVERWORLD.equals(level.dimension())) return;
        Wells ws = wells(level);
        Well w = ws.byPos.get(intake.asLong());
        if (w == null) {
            w = new Well(intake.getX(), intake.getZ(), rockAt(level, intake));
            ws.byPos.put(intake.asLong(), w);
            ws.setDirty();
        }
        if (w.windowStart == Long.MIN_VALUE) w.windowStart = level.getGameTime();
        w.drawn += cubicMetres;
    }

    /**
     * Water soaking down into the ground at a column, in cubic metres, that reaches the aquifer a well nearby is drawing
     * on: it goes back in as that well's water taken out does, a well run backwards at the spot (one to a sixteen-block
     * square, at the depth the well draws from), and the cone fills in by as much. Where no well draws within reach the
     * ground's own water ({@link SoilWater}) is all there is to it.
     */
    public static void recharge(ServerLevel level, int x, int z, double cubicMetres) {
        if (!GeyserConfig.SOIL_WATER.get() || !Level.OVERWORLD.equals(level.dimension()) || cubicMetres <= 0) return;
        Wells ws = level.getDataStorage().get(Wells::load, "fts_geology_wells");
        if (ws == null || ws.byPos.isEmpty()) return;
        Well near = null;
        long nearKey = 0;
        double best = REACH * REACH;
        for (var e : ws.byPos.long2ObjectEntrySet()) {
            Well w = e.getValue();
            if (w.rate <= 0) continue;
            double dx = w.x - x, dz = w.z - z, r2 = dx * dx + dz * dz;
            if (r2 < best) {
                best = r2;
                near = w;
                nearKey = e.getLongKey();
            }
        }
        if (near == null) return;
        long at = BlockPos.asLong((x & ~15) + 8, BlockPos.getY(nearKey), (z & ~15) + 8);
        Well r = ws.byPos.get(at);
        if (r == null) {
            r = new Well((x & ~15) + 8, (z & ~15) + 8, near.rock);
            ws.byPos.put(at, r);
            ws.setDirty();
        }
        if (r.windowStart == Long.MIN_VALUE) r.windowStart = level.getGameTime();
        r.drawn -= cubicMetres;
    }

    /** Each window, a well's measured rate goes into its story where it has changed; wells long stopped are let go. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || level.getGameTime() % 20 != 0) return;
        Wells ws = level.getDataStorage().get(Wells::load, "fts_geology_wells");
        if (ws == null || ws.byPos.isEmpty()) return;
        long now = level.getGameTime();
        double h = hours(level);
        for (var it = ws.byPos.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Well w = it.next().getValue();
            if (w.windowStart == Long.MIN_VALUE) {
                // Stopped, and not drawn on since: once refilled, forgotten.
                if (w.rate == 0 && (w.story.isEmpty() || h - w.story.get(w.story.size() - 1)[0] > FORGET_HOURS)) {
                    it.remove();
                    ws.setDirty();
                }
                continue;
            }
            if (now - w.windowStart < WINDOW) continue;
            double rate = w.drawn / ((now - w.windowStart) * SoilWater.HOURS_PER_TICK);
            w.drawn = 0;
            w.windowStart = rate != 0 ? now : Long.MIN_VALUE;
            if (Math.abs(rate - w.rate) > 0.1 * Math.max(rate, w.rate) + 1e-6) {
                change(w, h, rate - w.rate);
                w.rate = rate;
                ws.setDirty();
            }
        }
    }

    /** A well found not drawing: its rate goes to nothing from now, as a stopped pump's does. */
    public static void stopped(ServerLevel level, BlockPos intake) {
        Wells ws = level.getDataStorage().get(Wells::load, "fts_geology_wells");
        if (ws == null) return;
        Well w = ws.byPos.get(intake.asLong());
        if (w == null || w.rate == 0) return;
        change(w, hours(level), -w.rate);
        w.rate = 0;
        w.drawn = 0;
        w.windowStart = Long.MIN_VALUE;
        ws.setDirty();
    }

    private static void change(Well w, double h, double dq) {
        w.story.add(new double[]{h, dq});
        // Too long a story: the two oldest changes become one, at the later time, which the far past hardly minds.
        while (w.story.size() > STORY) {
            double[] a = w.story.remove(0), b = w.story.get(0);
            b[1] += a[1];
        }
    }

    // === Reading ==========================================================

    /** Blocks the wells round a column have lowered its water table by, now. */
    public static double drawdown(ServerLevel level, double x, double z) {
        Wells ws = level.getDataStorage().get(Wells::load, "fts_geology_wells");
        if (ws == null || ws.byPos.isEmpty()) return 0;
        double h = hours(level), s = 0;
        for (Well w : ws.byPos.values()) {
            double dx = w.x + 0.5 - x, dz = w.z + 0.5 - z, r2 = dx * dx + dz * dz;
            if (r2 > REACH * REACH || w.story.isEmpty()) continue;
            s += w.drawdown(Math.sqrt(r2), h);
        }
        return Math.max(0, s);
    }

    /** How fast a well draws now, in cubic metres an hour of the ground's time, as last measured; 0 if not kept. */
    public static double rate(ServerLevel level, BlockPos intake) {
        Wells ws = level.getDataStorage().get(Wells::load, "fts_geology_wells");
        if (ws == null) return 0;
        Well w = ws.byPos.get(intake.asLong());
        return w == null ? 0 : w.rate;
    }

    /**
     * The most a well at {@code intake} can draw, cubic metres an hour, with {@code head} blocks of water standing over
     * it: Dupuit's steady flow to a well, the rock's transmissivity over the water column, spread by the log of how far
     * the cone reaches.
     */
    public static double capacity(Rock rock, double head) {
        if (head <= 0) return 0;
        return 2 * Math.PI * rock.transmissivity * head / Math.log(REACH / BORE);
    }

    /** Cubic metres an hour of the ground's time as millibuckets a tick. */
    public static double millibucketsPerTick(double cubicMetresAnHour) {
        return cubicMetresAnHour * 1000.0 * SoilWater.HOURS_PER_TICK;
    }

    /** Where the water stands at a column: its table's own level, what the rain has done to it, less the wells' cones. */
    public static double waterY(ServerLevel level, int x, int z) {
        double y = WaterTable.tableYBefore(level, x, z, Long.MAX_VALUE);
        SoilWater.Reading r = SoilWater.at(level, x, z);
        if (r != null) y += r.table();
        return y - drawdown(level, x + 0.5, z + 0.5);
    }

    public static int count(ServerLevel level) {
        Wells ws = level.getDataStorage().get(Wells::load, "fts_geology_wells");
        return ws == null ? 0 : ws.byPos.size();
    }
}
