package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.instrument.SeismicWave;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An earthquake as it is felt, wherever each player is. The waves leave the rupture as it runs along the fault and
 * travel out through the crust: first the P wave, fast and slight, a jolt and a rumble; then the S wave, slower and
 * much stronger, the shaking proper. How hard it shakes falls off with distance from the rupture, as it does in
 * {@link ShakingDamage}; how long it goes on grows with the magnitude, and with the distance, as the waves spread out.
 *
 * <p>The ground itself moves only along the fault ({@link Earthquake}); what is out here is the shaking. A great
 * earthquake on a subduction margin is felt thousands of blocks inland.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class FeltShaking {

    private FeltShaking() {}

    /** The depth a quake is taken to break at where its own is not known, in metres: a shallow crustal one. */
    public static final double TYPICAL_DEPTH = 10_000.0;
    /** The highest Mercalli intensity reached, XI: twelve is the scale's end, not something seen. */
    private static final double MOST_MERCALLI = 11.0;
    /** The least intensity anyone feels: people at rest, indoors. */
    public static final double FELT = 3.0;
    /** How fast a rupture runs along its fault, metres a second: a little under the S wave. */
    private static final double RUPTURE_SPEED = 2800.0;
    /** How often the shaking is re-sent to a player, in ticks; the client's own run-out bridges the gap. */
    private static final int RESEND = 5;
    /** The rumble clip's length in ticks, restarted until the shaking is over. */
    private static final int RUMBLE_CLIP = 420;

    // === The physics, shared ===============================================

    /**
     * Shaking intensity at {@code blocks} from the rupture of a quake of this magnitude, broken this deep, on the mod's
     * own scale ({@link #mercalli}: Mercalli = 1.35 I - 1).
     *
     * <p>The Mercalli intensity is the one measured in real earthquakes, as a function of the real distance: Allen,
     * Wald and Worden's (2012) equation for shallow crust, by the distance to the rupture, which takes in how deep its
     * top lies (from its width, Wells and Coppersmith 1994) and how big its near-field is. Distance in blocks becomes
     * kilometres through the world's horizontal scale, as everything else about a quake does. Twelve kilometres from a
     * magnitude 8 that is X, from a 7 VIII to IX, from a 6 VI to VII; a quake deep in a down-going slab is felt widely
     * and hurts little. The first shaking here fell off with distance in blocks, whatever the scale: a magnitude 8 left
     * a house twelve kilometres off with a few blocks off it.</p>
     */
    public static double intensity(double magnitude, double blocks, double depthMetres) {
        double km = Math.max(0.0, blocks) * DepthScale.metresPerBlockHorizontal() / 1000.0;
        return fromMercalli(mercalliAt(magnitude, km, depthMetres / 1000.0));
    }

    /** As {@link #intensity(double, double, double)}, for a quake of the usual shallow depth. */
    public static double intensity(double magnitude, double blocks) {
        return intensity(magnitude, blocks, TYPICAL_DEPTH);
    }

    /** The Modified Mercalli intensity, as a real number, {@code km} from the trace of a rupture this deep. */
    static double mercalliAt(double magnitude, double km, double depthKm) {
        double width = Math.pow(10.0, -1.01 + 0.32 * magnitude);
        double top = Math.max(1.0, depthKm - width / 2.0);
        double near = 1.0 + 0.078 * Math.exp(magnitude - 5.0);
        double rupture = Math.sqrt(km * km + top * top);
        double mmi = 2.085 + 1.428 * magnitude - 1.402 * Math.log(Math.sqrt(rupture * rupture + near * near));
        if (rupture > 50.0) mmi -= 0.209 * Math.log(rupture / 50.0);
        return Math.min(MOST_MERCALLI, mmi);
    }

    /** The mod's intensity scale from a Mercalli intensity. */
    public static double fromMercalli(double mmi) {
        return (mmi + 1.0) / 1.35;
    }

    /**
     * How far from the rupture, in blocks, the shaking of a quake broken this deep is still this strong; 0 where it
     * never is. A shallow quake's, where the depth is not given: the farthest.
     */
    public static double distanceFor(double magnitude, double intensity, double depthMetres) {
        if (intensity(magnitude, 0.0, depthMetres) < intensity) return 0.0;
        double lo = 0.0, hi = 1_000_000.0;
        if (intensity(magnitude, hi, depthMetres) >= intensity) return hi;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (intensity(magnitude, mid, depthMetres) >= intensity) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    public static double distanceFor(double magnitude, double intensity) {
        return distanceFor(magnitude, intensity, 3_000.0);
    }

    /**
     * The rupture a quake's shaking comes from: the trace the ground was broken along, carried on along the fault's
     * strike as far as a rupture of this magnitude really runs. The ground is moved over a few dozen kilometres at
     * most ({@code quakeMaxRupture}), but a magnitude 8 breaks two hundred, and shakes as hard all along them; a town
     * beside the far end of it is as near the rupture as one beside the near end. A single point, with its strike,
     * becomes a line of its own. Points every so many blocks; the trace's own are kept as they are.
     */
    public static List<QuakePlanner.TracePoint> rupture(List<QuakePlanner.TracePoint> trace, double magnitude) {
        if (trace.isEmpty()) return trace;
        double full = Math.pow(10.0, 0.69 * magnitude - 3.22) * 1000.0 / DepthScale.metresPerBlockHorizontal();
        QuakePlanner.TracePoint a = trace.get(0), b = trace.get(trace.size() - 1);
        double have = Math.hypot(b.x() - a.x(), b.z() - a.z());
        double each = Math.min(MOST_REACH, (full - have) / 2.0);
        if (each < RUPTURE_STEP) return trace;
        List<QuakePlanner.TracePoint> out = new ArrayList<>(trace.size() + 2 * (int) (each / RUPTURE_STEP) + 2);
        // Outward from either end: the way the trace runs there, or for a single point both ways along its strike.
        double ax, az, bx, bz;
        if (trace.size() >= 2 && have >= 1.0) {
            QuakePlanner.TracePoint a1 = trace.get(Math.min(trace.size() - 1, 4)), b1 = trace.get(Math.max(0, trace.size() - 5));
            ax = a.x() - a1.x();
            az = a.z() - a1.z();
            bx = b.x() - b1.x();
            bz = b.z() - b1.z();
        } else {
            ax = -a.strikeX();
            az = -a.strikeZ();
            bx = a.strikeX();
            bz = a.strikeZ();
        }
        double an = Math.hypot(ax, az), bn = Math.hypot(bx, bz);
        if (an < 1e-6 || bn < 1e-6) return trace;
        ax /= an;
        az /= an;
        bx /= bn;
        bz /= bn;
        int n = (int) (each / RUPTURE_STEP);
        for (int k = n; k >= 1; k--) {
            out.add(new QuakePlanner.TracePoint((int) Math.round(a.x() + ax * k * RUPTURE_STEP), (int) Math.round(a.z() + az * k * RUPTURE_STEP),
                    -ax, -az, 0.0));
        }
        out.addAll(trace);
        for (int k = 1; k <= n; k++) {
            out.add(new QuakePlanner.TracePoint((int) Math.round(b.x() + bx * k * RUPTURE_STEP), (int) Math.round(b.z() + bz * k * RUPTURE_STEP),
                    bx, bz, 0.0));
        }
        return out;
    }

    /**
     * The loaded chunks within {@code reach} blocks of a rupture, nearest the rupture first in no particular order.
     * Every chunk of the rupture's box is looked at while the box is small; a great earthquake's is hundreds of
     * kilometres across, and then only the chunks round the players (as far as they see), the forceloaded ones and the
     * spawn's are.
     */
    public static java.util.Deque<net.minecraft.world.level.ChunkPos> loadedWithin(ServerLevel level,
                                                                                    List<QuakePlanner.TracePoint> trace, double reach) {
        java.util.Deque<net.minecraft.world.level.ChunkPos> out = new java.util.ArrayDeque<>();
        if (trace.isEmpty()) return out;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (QuakePlanner.TracePoint t : trace) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
        }
        int r = (int) Math.ceil(reach);
        int cx0 = (minX - r) >> 4, cx1 = (maxX + r) >> 4, cz0 = (minZ - r) >> 4, cz1 = (maxZ + r) >> 4;
        double[] d = new double[1];
        if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) <= BOX_CHUNKS) {
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
                    nearest(trace, cx * 16 + 8, cz * 16 + 8, d);
                    if (d[0] <= reach + 12) out.add(new net.minecraft.world.level.ChunkPos(cx, cz));
                }
            }
            return out;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        it.unimi.dsi.fastutil.longs.LongArrayList candidates = new it.unimi.dsi.fastutil.longs.LongArrayList();
        int view = level.getServer().getPlayerList().getViewDistance() + 2;
        for (ServerPlayer p : level.players()) {
            int pcx = p.chunkPosition().x, pcz = p.chunkPosition().z;
            for (int dx = -view; dx <= view; dx++) for (int dz = -view; dz <= view; dz++) candidates.add(net.minecraft.world.level.ChunkPos.asLong(pcx + dx, pcz + dz));
        }
        candidates.addAll(level.getForcedChunks());
        BlockPos spawn = level.getSharedSpawnPos();
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
            candidates.add(net.minecraft.world.level.ChunkPos.asLong((spawn.getX() >> 4) + dx, (spawn.getZ() >> 4) + dz));
        }
        for (long k : candidates) {
            if (!seen.add(k)) continue;
            int cx = net.minecraft.world.level.ChunkPos.getX(k), cz = net.minecraft.world.level.ChunkPos.getZ(k);
            if (cx < cx0 || cx > cx1 || cz < cz0 || cz > cz1 || level.getChunkSource().getChunkNow(cx, cz) == null) continue;
            nearest(trace, cx * 16 + 8, cz * 16 + 8, d);
            if (d[0] <= reach + 12) out.add(new net.minecraft.world.level.ChunkPos(cx, cz));
        }
        return out;
    }

    /** The most chunks of a rupture's box gone through one by one. */
    private static final long BOX_CHUNKS = 40_000;

    /** Blocks between the points a rupture is carried on with, and the most it is carried either way. */
    private static final double RUPTURE_STEP = 32.0, MOST_REACH = 8000.0;

    /** The Modified Mercalli class this intensity reads as, I to XII. */
    public static int mercalli(double intensity) {
        return Mth.clamp((int) Math.round(1.35 * intensity - 1.0), 1, 12);
    }

    /**
     * The faintest shaking felt at all (Mercalli II, felt by a few at rest), from the config: between it and
     * {@link #FELT} a quake is only a faint tremor and a far rumble.
     */
    public static double floor() {
        return Math.min(FELT, GeyserConfig.QUAKE_FELT_FLOOR.get());
    }

    /** How far out a quake of this magnitude is felt at all, if only faintly, capped by the config. */
    public static double feltRange(double magnitude) {
        return Math.min(GeyserConfig.QUAKE_FELT_RANGE.get(), Math.max(0.0, distanceFor(magnitude, floor())));
    }

    /** Ticks for a wave at {@code speed} to reach a point this far from a hypocentre this deep. */
    public static int travelTicks(double blocks, double depthMetres, double speed) {
        return (int) Math.round(SeismicWave.hypocentralMetres(blocks, depthMetres) / speed * 20.0);
    }

    /**
     * How long the strong shaking lasts, in ticks: a few seconds for a magnitude 5, a minute for a 9, and longer the
     * further out, as the waves spread and scatter.
     */
    public static int durationTicks(double magnitude, double blocks) {
        double km = blocks * DepthScale.metresPerBlockHorizontal() / 1000.0;
        double seconds = Math.pow(10.0, 0.32 * magnitude - 1.1) + 0.05 * km;
        return Mth.clamp((int) Math.round(seconds * 20.0), 40, 1800);
    }

    /** Horizontal distance from a point to the nearest traced point of the rupture, and that point. */
    static QuakePlanner.TracePoint nearest(List<QuakePlanner.TracePoint> trace, double x, double z, double[] dist) {
        QuakePlanner.TracePoint best = null;
        double bestD = Double.MAX_VALUE;
        for (QuakePlanner.TracePoint t : trace) {
            double dx = t.x() - x, dz = t.z() - z;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        dist[0] = Math.sqrt(bestD);
        return best;
    }

    /**
     * Ticks after the rupture starts that the rupture front reaches this point of the fault and sends its waves out:
     * a great earthquake shakes the far end of its fault a minute after the near end.
     */
    static int ruptureDelay(BlockPos epicentre, QuakePlanner.TracePoint t) {
        double blocks = Math.hypot(t.x() - epicentre.getX(), t.z() - epicentre.getZ());
        return (int) Math.round(blocks * DepthScale.metresPerBlockHorizontal() / RUPTURE_SPEED * 20.0);
    }

    // === Quakes being felt ==================================================

    private static final class Felt {
        boolean jolted, told;
        /** Whether this player reads the quake's size and place off an instrument near them; asked once. */
        Boolean informed;
        /** When the rumble was last started here; never, at first. */
        long rumbled = NEVER;
    }

    private static final long NEVER = -1_000_000L;

    private static final class Quake {
        final ResourceKey<Level> dimension;
        final BlockPos epicentre;
        final List<QuakePlanner.TracePoint> trace;
        final double magnitude, depthMetres, range;
        final long startAt, endAt;
        final Map<UUID, Felt> felt = new HashMap<>();
        /** Who the network's early warning has reached, for the log. */
        final java.util.Set<UUID> warned = new java.util.HashSet<>();

        Quake(ResourceKey<Level> dimension, BlockPos epicentre, List<QuakePlanner.TracePoint> trace, double magnitude,
              double depthMetres, long startAt) {
            this.dimension = dimension;
            this.epicentre = epicentre;
            this.trace = trace;
            this.magnitude = magnitude;
            this.depthMetres = depthMetres;
            this.startAt = startAt;
            this.range = feltRange(magnitude);
            int along = 0;
            for (QuakePlanner.TracePoint t : trace) along = Math.max(along, ruptureDelay(epicentre, t));
            this.endAt = startAt + along + travelTicks(range, depthMetres, SeismicWave.VS)
                    + durationTicks(magnitude, range) + 40;
        }
    }

    private static final List<Quake> QUAKES = new ArrayList<>();
    private static long jolts, told;

    /**
     * A quake starts: its waves leave the rupture at {@code startAt}. A tremor too small to break the ground passes a
     * single point for its trace.
     */
    public static void start(ServerLevel level, BlockPos epicentre, List<QuakePlanner.TracePoint> trace, double magnitude,
                             double depthMetres, long startAt) {
        if (trace.isEmpty() || feltRange(magnitude) <= 0) return;
        QUAKES.add(new Quake(level.dimension(), epicentre.immutable(), List.copyOf(trace), magnitude, depthMetres, startAt));
    }

    /** The players close enough to feel a quake of this magnitude on this trace. */
    public static List<ServerPlayer> within(ServerLevel level, List<QuakePlanner.TracePoint> trace, double magnitude) {
        double range = feltRange(magnitude);
        double[] d = new double[1];
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            nearest(trace, p.getX(), p.getZ(), d);
            if (d[0] <= range) out.add(p);
        }
        return out;
    }

    public static void tick(MinecraftServer server) {
        if (QUAKES.isEmpty()) return;
        QUAKES.removeIf(q -> {
            ServerLevel level = server.getLevel(q.dimension);
            if (level == null) return true;
            long now = level.getGameTime();
            if (now > q.endAt) return true;
            if (now < q.startAt) return false;
            for (ServerPlayer p : level.players()) feel(level, q, p, now);
            return false;
        });
    }

    private static void feel(ServerLevel level, Quake q, ServerPlayer p, long now) {
        double[] d = new double[1];
        QuakePlanner.TracePoint at = nearest(q.trace, p.getX(), p.getZ(), d);
        if (at == null || d[0] > q.range) return;
        double intensity = intensity(q.magnitude, d[0], q.depthMetres);
        // Felt harder on soft ground and where the rupture ran toward (see SiteResponse); only where it is felt at all.
        if (intensity >= FELT - 1.2) {
            intensity += SiteResponse.ground(level, p.getBlockX(), p.getBlockZ()) + SiteResponse.directivity(q.epicentre, at, q.trace);
        }
        double floor = floor();
        if (intensity < floor) return;
        // Under a light quake's shaking (Mercalli III) only a faint tremor in the view and a far rumble: nothing told.
        boolean faint = intensity < FELT;
        long sent = q.startAt + ruptureDelay(q.epicentre, at);
        long pAt = sent + travelTicks(d[0], q.depthMetres, SeismicWave.VP);
        long sAt = sent + travelTicks(d[0], q.depthMetres, SeismicWave.VS);
        int lasts = durationTicks(q.magnitude, d[0]);
        if (now < pAt) {
            // Before the waves are here: the seismograph network's early warning, once two of its stations have caught
            // the P wave, to whoever is near enough one of them and will be shaken hard.
            if (now % 20 == 0 && intensity >= 4.0
                    && now >= com.jeladastudios.ftsgeology.instrument.SeismicStations.detects(q.dimension, q.epicentre.getX(),
                            q.epicentre.getZ(), q.depthMetres, q.startAt, GeyserConfig.SEISMOGRAPH_RANGE.get())
                    && com.jeladastudios.ftsgeology.instrument.SeismicStations.covers(q.dimension, p.getX(), p.getZ())) {
                int mmi = mercalli(intensity);
                p.displayClientMessage(Component.translatable("message.fts_geology.early_warning",
                                String.format(java.util.Locale.ROOT, "%.1f", q.magnitude),
                                Component.translatable("message.fts_geology.mercalli." + mmi), roman(mmi), (int) ((sAt - now + 19) / 20))
                        .withStyle(mmi >= 7 ? ChatFormatting.RED : ChatFormatting.GOLD), true);
                if (q.warned.add(p.getUUID())) {
                    com.jeladastudios.ftsgeology.util.Diagnostics.info("early warning: {} warned {} s before the S wave, {} s before the P wave",
                            p.getName().getString(), (sAt - now) / 20, (pAt - now) / 20);
                }
            }
            return;
        }
        if (now > sAt + lasts) return;

        Felt f = q.felt.computeIfAbsent(p.getUUID(), u -> new Felt());
        if (f.informed == null) {
            f.informed = com.jeladastudios.ftsgeology.instrument.QuakeNews.informs(level, p, q.epicentre.getX(), q.epicentre.getZ(),
                    q.magnitude, q.depthMetres);
        }
        // The view: a small, quick jolt for the P wave, then the slow heavy swaying of the S wave, dying away; a faint
        // quake only a tremor of a tenth of a degree or two, hardly seen.
        float strong = faint ? (float) (0.10 + 0.2 * (intensity - floor)) : (float) Math.max(0.0, 0.55 * (intensity - 2.5));
        boolean s = now >= sAt;
        if (!f.jolted) {
            f.jolted = true;
            jolts++;
            if (!faint) {
                // The P wave: a sharp jolt and a boom, and what it is, told; its size only off an instrument (below).
                p.playNotifySound(net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE, net.minecraft.sounds.SoundSource.BLOCKS,
                        (float) Mth.clamp((intensity - 2.0) / 6.0, 0.2, 0.8), 0.45f);
                if (!f.informed || intensity < 4.0) {
                    p.displayClientMessage(Component.translatable("message.fts_geology.p_wave_felt").withStyle(ChatFormatting.GOLD), true);
                }
            }
            com.jeladastudios.ftsgeology.util.Diagnostics.info(
                    "felt{}: {} {} blocks from the rupture, intensity {}, P at +{} ticks, S at +{}, shaking {} ticks, informed {}",
                    faint ? " faintly" : "", p.getName().getString(), Math.round(d[0]),
                    String.format(java.util.Locale.ROOT, "%.2f", intensity), pAt - q.startAt, sAt - q.startAt, lasts, f.informed);
        }
        // Between the waves, over the hotbar, once a second: the countdown to the strong shaking and how strong it will be,
        // as an early warning system says it, to a player at an instrument. Only where it will be strong enough to matter.
        if (!faint && f.informed && !s && intensity >= 4.0 && (now - pAt) % 20 == 0) {
            int warn = (int) ((sAt - now + 19) / 20);
            int mmi = mercalli(intensity);
            if (warn >= 1) {
                p.displayClientMessage(Component.translatable("message.fts_geology.p_wave_count",
                                String.format(java.util.Locale.ROOT, "%.1f", q.magnitude),
                                Component.translatable("message.fts_geology.mercalli." + mmi), roman(mmi), warn)
                        .withStyle(mmi >= 7 ? ChatFormatting.RED : ChatFormatting.GOLD), true);
            }
        }
        // The rumble starts with the P wave, starts again, louder, as the S wave arrives, and runs until the end.
        boolean sArrives = now == sAt;
        if (now - f.rumbled >= RUMBLE_CLIP || sArrives) {
            f.rumbled = now;
            // Heard where the player is, not at the epicentre: the ground under them is what roars; a faint quake's
            // rumble is low and far off.
            float volume = faint ? (float) Mth.clamp(0.12 + 0.15 * (intensity - floor), 0.1, 0.3)
                    : (float) Mth.clamp((intensity - 2.0) / (sArrives ? 3.0 : 5.0), 0.35, 1.0);
            float pitch = (float) Mth.clamp(0.75 + 0.05 * (intensity - 3.0), 0.7, 1.0);
            p.playNotifySound(com.jeladastudios.ftsgeology.registry.ModSounds.QUAKE_RUMBLE.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch);
        }
        if ((now - pAt) % RESEND == 0) {
            float shake;
            float speed;
            if (s) {
                double into = (now - sAt) / (double) lasts;
                shake = (float) (strong * (into < 0.6 ? 1.0 : 1.0 - (into - 0.6) / 0.4));
                speed = 1.0f;
            } else {
                shake = faint ? 0.06f : Math.max(0.25f, strong * 0.45f);
                speed = 3.0f;
            }
            if (shake > 0.02f) com.jeladastudios.ftsgeology.network.ModNetwork.sendShake(p, shake, 20, speed);
        }
        if (!s) return;
        if (!f.told) {
            f.told = true;
            told++;
            int mmi = mercalli(intensity);
            if (faint) {
                // Something in the room rattles, once, softly.
                p.playNotifySound(net.minecraft.sounds.SoundEvents.CHAIN_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.12f, 1.4f);
            } else {
                if (mmi >= 4) com.jeladastudios.ftsgeology.advancement.GeologyTrigger.award(p, "felt_quake");
                if (mmi >= 8) com.jeladastudios.ftsgeology.advancement.GeologyTrigger.award(p, "great_quake");
                p.displayClientMessage(Component.translatable("message.fts_geology.s_wave_felt",
                        Component.translatable("message.fts_geology.mercalli." + mmi), roman(mmi))
                        .withStyle(mmi >= 7 ? ChatFormatting.RED : mmi >= 5 ? ChatFormatting.GOLD : ChatFormatting.YELLOW), true);
            }
            com.jeladastudios.ftsgeology.util.Diagnostics.info("felt: {} S wave at +{} ticks, MMI {}",
                    p.getName().getString(), now - q.startAt, mmi);
        }
        // Knocked about, and the ground and the ceiling shedding dust, where it is strong enough to.
        double falloff = Mth.clamp((intensity - 4.0) / 4.0, 0.0, 1.0);
        if (falloff > 0) {
            double kick = 0.03 * falloff * (0.6 + q.magnitude / 12.0);
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x + (level.random.nextDouble() - 0.5) * kick,
                    v.y + (p.onGround() ? level.random.nextDouble() * kick * 0.6 : 0.0),
                    v.z + (level.random.nextDouble() - 0.5) * kick);
            p.hurtMarked = true;
            dust(level, p, falloff);
            ShakingDamage.ceilingDust(level, p, falloff);
        }
    }

    /**
     * Dust shaken off the ground around a player. Rides the player loop, so it searches nothing and writes no blocks.
     */
    private static void dust(ServerLevel level, ServerPlayer p, double falloff) {
        if (level.getGameTime() % 3L != 0L) return;
        int puffs = 1 + (int) Math.round(falloff * 4);
        for (int i = 0; i < puffs; i++) {
            int x = Mth.floor(p.getX()) + level.random.nextInt(25) - 12;
            int z = Mth.floor(p.getZ()) + level.random.nextInt(25) - 12;
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(x, level.getSeaLevel(), z))) continue;
            int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(new BlockPos(x, g - 1, z));
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(
                            net.minecraft.core.particles.ParticleTypes.BLOCK, s),
                    x + 0.5, g + 0.1, z + 0.5, 3, 0.4, 0.15, 0.4, 0.02);
        }
    }

    private static String roman(int n) {
        return new String[]{"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"}[n];
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "felt: %d players jolted, %d told the shaking", jolts, told);
    }

    public static void clear() {
        QUAKES.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
