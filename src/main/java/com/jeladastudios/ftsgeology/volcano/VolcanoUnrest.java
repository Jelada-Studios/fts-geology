package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.instrument.SeismicNetwork;
import com.jeladastudios.ftsgeology.quake.FeltShaking;
import com.jeladastudios.ftsgeology.quake.QuakePlanner;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A volcano's unrest in the minutes before it erupts, as magma rises under it: the signs a volcano observatory reads.
 *
 * <ul>
 *   <li><b>A swarm of small earthquakes</b> under the mountain, a few a minute at first and several a minute at the
 *   end, the rock cracking as the magma pushes up through it. Seismographs record them, and a swarm reads on the
 *   drum as its own warning; they are too small to be felt past the mountain's own slopes.</li>
 *   <li><b>Warming ground water</b>: hot springs round the mountain steam harder and geysers go off more often.</li>
 *   <li><b>Swelling ground</b>, which the geothermal probe measures.</li>
 *   <li><b>Gas</b>: the fumaroles smoke harder and carbon dioxide collects in hollows; see {@link VolcanicGas}.</li>
 * </ul>
 *
 * <p>A restless volcano is registered here for the rest of the world to ask about, for as long as it keeps saying so:
 * it reports itself once a second, and the entry lapses a minute after it stops.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class VolcanoUnrest {

    private VolcanoUnrest() {}

    /** How far round a restless volcano its springs warm and the probe reads it: a floor and so much per magnitude. */
    private static final double REACH = 96.0, REACH_PER_MAGNITUDE = 8.0;
    /** Swarm quakes a second at the start of the window and at its end. */
    private static final double RATE_START = 1.0 / 40.0, RATE_END = 1.0 / 3.0;
    /** How long an entry outlives the volcano's last report, in ticks. */
    private static final long LAPSE = 1200L;

    /** A volcano in unrest: how far it reaches, how far along it is (0 to 1), and until when this holds. */
    public record Restless(ResourceKey<Level> dimension, BlockPos summit, int magnitude, double reach, double progress,
                           long until) {}

    private static final Map<Long, Restless> RESTLESS = new HashMap<>();

    /** Swarm quakes filed since the server started, and the largest, for the log. */
    private static int swarmQuakes;
    private static double swarmLargest;

    /** How many ticks before an eruption a volcano becomes restless; a sleeping one wakes more slowly. */
    public static int window(boolean sealed) {
        int w = GeyserConfig.VOLCANO_UNREST_TICKS.get();
        return sealed ? w * 2 : w;
    }

    /**
     * Once a second from a restless volcano. {@code progress} runs from 0 as the unrest starts to 1 as it erupts;
     * {@code swarm} is false once it is erupting, when the eruption's own shaking takes over.
     */
    public static void tick(ServerLevel level, BlockPos summit, int magnitude, double progress, boolean swarm) {
        if (!GeyserConfig.VOLCANO_UNREST.get()) return;
        double reach = REACH + REACH_PER_MAGNITUDE * magnitude;
        RESTLESS.put(summit.asLong(), new Restless(level.dimension(), summit.immutable(), magnitude, reach,
                Math.max(0.0, Math.min(1.0, progress)), level.getGameTime() + LAPSE));
        if (!swarm) return;
        double rate = RATE_START + (RATE_END - RATE_START) * progress * progress;
        if (level.random.nextDouble() < rate) swarmQuake(level, summit, magnitude, progress);
    }

    /** Forgets a volcano's unrest, as it goes quiet. */
    public static void settle(BlockPos summit) {
        RESTLESS.remove(summit.asLong());
    }

    /**
     * One quake of the swarm: small, shallow, under the mountain, larger and more often as the eruption nears. Filed
     * for the seismographs as a volcanic event, and felt only close by.
     */
    private static void swarmQuake(ServerLevel level, BlockPos summit, int magnitude, double progress) {
        double m = 1.0 + 1.8 * progress + level.random.nextDouble() * 0.8;
        // The swarm climbs with the magma: from some six kilometres down to one or two.
        double depthM = 1000.0 + (5000.0 - 3500.0 * progress) * level.random.nextDouble();
        int spread = 4 + magnitude / 2;
        int x = summit.getX() + level.random.nextInt(2 * spread + 1) - spread;
        int z = summit.getZ() + level.random.nextInt(2 * spread + 1) - spread;
        int y = summit.getY() - (int) Math.round(depthM / DepthScale.metresPerBlock());
        BlockPos at = new BlockPos(x, Math.max(level.getMinBuildHeight(), y), z);
        SeismicNetwork.recordVolcanic(level, at, m, depthM);
        FeltShaking.start(level, at, List.of(new QuakePlanner.TracePoint(x, z, 1.0, 0.0, 0.0)), m, depthM,
                level.getGameTime());
        swarmQuakes++;
        swarmLargest = Math.max(swarmLargest, m);
    }

    /**
     * How restless the ground is at a point, 0 to 1: the unrest of the most restless volcano whose reach it is in,
     * fading out towards the edge of that reach.
     */
    public static double near(Level level, BlockPos pos) {
        if (RESTLESS.isEmpty()) return 0.0;
        long now = level.getGameTime();
        double best = 0.0;
        for (Restless r : RESTLESS.values()) {
            if (!r.dimension().equals(level.dimension()) || r.until() < now) continue;
            double d = Math.hypot(pos.getX() - r.summit().getX(), pos.getZ() - r.summit().getZ());
            if (d >= r.reach()) continue;
            best = Math.max(best, (0.35 + 0.65 * r.progress()) * (1.0 - d / r.reach()));
        }
        return best;
    }

    /** The restless volcano whose reach a point is in and nearest it, or null. */
    public static Restless nearest(Level level, int x, int z) {
        long now = level.getGameTime();
        Restless best = null;
        double bestD = Double.MAX_VALUE;
        for (Restless r : RESTLESS.values()) {
            if (!r.dimension().equals(level.dimension()) || r.until() < now) continue;
            double d = Math.hypot(x - r.summit().getX(), z - r.summit().getZ());
            if (d >= r.reach() || d >= bestD) continue;
            best = r;
            bestD = d;
        }
        return best;
    }

    /**
     * How far the ground at a point has swelled with the volcano's unrest, in centimetres: tens of centimetres on the
     * flanks as the eruption nears, much less further out.
     */
    public static int swellCm(Restless r, int x, int z) {
        double d = Math.hypot(x - r.summit().getX(), z - r.summit().getZ());
        double near = Math.max(0.0, 1.0 - d / r.reach());
        return (int) Math.round((4.0 + 60.0 * r.progress()) * near * near * (0.5 + r.magnitude() / 24.0));
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "unrest: %d swarm quakes, largest M%.1f", swarmQuakes, swarmLargest);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RESTLESS.clear();
        swarmQuakes = 0;
        swarmLargest = 0;
    }
}
