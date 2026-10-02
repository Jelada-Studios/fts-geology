package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.SereneSeasons;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.weather.RainClimate;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rivers' high water of their season, where Serene Seasons keeps the seasons. A river fed by snow runs highest as
 * the snow melts at the end of winter and the start of spring -- five or six times its summer flow -- a Mediterranean one
 * in winter, a monsoon one in summer, a tropical one in the wet season. A river's width goes as the square root of its
 * flow (hydraulic geometry), so five times the water is a river about two and a quarter times as wide: a 3-block summer
 * river runs 6-7 blocks across in the spring.
 *
 * <p>The water comes up a block, or two at the height of it, over its banks, by the same way as a flood in a long rain
 * (see {@link Floods}): on a flat plain a little rise spreads it wide, in a canyon it hardly widens. It holds while the
 * season does and goes back down after. Only round the players, a few chunks at a look; a creek of a few blocks rises one
 * block at most.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class SeasonalRivers {

    private SeasonalRivers() {}

    /** Ticks between looks, the chunks looked at round each player, and how long a rise holds after it was last renewed. */
    private static final int EVERY = 600, REACH = 3, HOLD = 2400, RECEDE = 600;

    private static long risen;

    /** Chunks still to be looked at from the last round, a few each tick within the mod's budget. */
    private static final LongArrayFIFOQueue PENDING = new LongArrayFIFOQueue();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || !GeyserConfig.SEASONAL_RIVERS.get() || !SereneSeasons.active()) return;
        long now = level.getGameTime();
        if (now % EVERY == 0 && PENDING.isEmpty()) {
            LongOpenHashSet seen = new LongOpenHashSet();
            for (ServerPlayer p : level.players()) {
                ChunkPos at = p.chunkPosition();
                for (int dx = -REACH; dx <= REACH; dx++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        long k = ChunkPos.asLong(at.x + dx, at.z + dz);
                        if (seen.add(k)) PENDING.enqueue(k);
                    }
                }
            }
        }
        if (PENDING.isEmpty()) return;
        double phase = SereneSeasons.phase(level);
        if (Double.isNaN(phase)) {
            PENDING.clear();
            return;
        }
        // A rise lays a few thousand blocks: one look at least, then only while the tick's budget lasts.
        com.jeladastudios.ftsgeology.util.TickBudget.open(event.getServer().getTickCount());
        long deadline = System.nanoTime() + com.jeladastudios.ftsgeology.util.TickBudget.slice(0.1);
        do {
            long k = PENDING.dequeueLong();
            look(level, ChunkPos.getX(k), ChunkPos.getZ(k), phase);
        } while (!PENDING.isEmpty() && System.nanoTime() < deadline);
    }

    /**
     * Blocks the rivers of a place stand over their summer level at a phase of the year: 0, 1 or 2. Snow-fed rivers (a
     * cold winter) peak with the melt; the others with their regime's wet season.
     */
    public static int stage(ServerLevel level, int x, int z, double phase) {
        RainClimate.Here h = RainClimate.at(level, x, z);
        float t = level.getBiome(new BlockPos(x, level.getSeaLevel() + 8, z)).value().getBaseTemperature();
        boolean snowFed = t < 0.5f && h.regime() != RainClimate.Regime.POLAR;
        double flow;
        if (snowFed) {
            // The melt: late winter into mid spring, the most at the turn of the year.
            flow = 1.0 + 4.5 * bump(phase, 0.0, 0.13);
        } else if (h.regime() == RainClimate.Regime.ARID || h.regime() == RainClimate.Regime.POLAR) {
            flow = 1.0;
        } else {
            flow = 1.0 + 3.0 * Math.max(0.0, RainClimate.season(level, x, z) - 1.0);
        }
        // Width goes as the square root of the flow: 2.25 times as wide at 5 times the water needs about two blocks of
        // rise on an ordinary bank; 1.5 times as wide about one.
        double wider = Math.sqrt(flow);
        return wider >= 2.0 ? 2 : wider >= 1.4 ? 1 : 0;
    }

    /** A smooth bump on the year's circle, 1 at {@code centre}, 0 past {@code half} either side. */
    private static double bump(double phase, double centre, double half) {
        double d = Math.abs(phase - centre);
        d = Math.min(d, 1.0 - d);
        if (d >= half) return 0.0;
        double c = Math.cos(d / half * Math.PI / 2);
        return c * c;
    }

    private static void look(ServerLevel level, int cx, int cz, double phase) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return;
        int stage = stage(level, cx * 16 + 8, cz * 16 + 8, phase);
        if (stage <= 0) return;
        String key = "season " + cx + "," + cz;
        // Already up: it stays so while the season does.
        if (Floods.extend(level, key, HOLD)) return;
        List<BlockPos> tops = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = cx * 16 + lx, z = cz * 16 + lz;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                BlockState s = chunk.getBlockState(m.set(x, y, z));
                if (!s.is(ModBlocks.RIVER_WATER.get()) || s.getValue(RiverWaterFluid.FLOW) == 0) continue;
                tops.add(new BlockPos(x, y, z));
            }
        }
        if (tops.size() < 4) return;
        // A creek rises a block at most.
        if (tops.size() < 24) stage = Math.min(stage, 1);
        BlockPos middle = new BlockPos(cx * 16 + 8, tops.get(0).getY(), cz * 16 + 8);
        List<LongOpenHashSet> layers = Floods.spread(level, tops, stage, middle, 24, 3000, k -> true);
        if (layers.isEmpty()) return;
        int laid = Floods.surge(level, layers, HOLD, RECEDE, String.format(Locale.ROOT, "the season's high water at %s, +%d",
                middle.toShortString(), stage), key);
        if (laid > 0) risen++;
    }

    public static String summary() {
        return String.format(Locale.ROOT, "seasonal high water: %d river stretches risen", risen);
    }
}
