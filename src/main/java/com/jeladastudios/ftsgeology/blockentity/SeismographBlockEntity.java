package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.instrument.SeismicNetwork;
import com.jeladastudios.ftsgeology.instrument.SeismicWave;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModSounds;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A recording station. It is told nothing: it reads {@link SeismicNetwork}, works out what its own
 * drum would have drawn, and keeps that measurement, with distance and magnitude derived from it.
 * One station cannot give a direction; three fix the epicentre.
 *
 * <p>After an event it holds a redstone signal scaled by how hard the ground shook here, from about 3
 * for a distant tremor to 15.</p>
 */
public class SeismographBlockEntity extends BlockEntity {

    /** How many measurements the drum keeps before the oldest scrolls off the paper. */
    private static final int LOG_SIZE = 8;

    /** Ticks the needle keeps twitching, and the redstone signal stays up, after an arrival. */
    private static final int SHAKE_TICKS = 100;

    /** Redstone the station holds through the warning window: full, so a bell rings without wiring. */
    private static final int WARNING_SIGNAL = 15;

    /**
     * One line of the station's own paper trace. Measured values only: with its three components, the way the ground
     * first moved gives which way the waves came from ({@code azimuth}, degrees from north to the source, -1 not read)
     * give or take {@code err} degrees, the more the noisier the drum was against the arrival.
     */
    public record Reading(long eventId, double spSeconds, double amplitudeMm, long gameTime, double azimuth, double err) {

        /** Distance to the hypocentre, in metres, as read off the S-P gap. */
        public double distanceMetres() {
            return SeismicWave.distanceMetres(spSeconds);
        }

        /** Magnitude, from the swing corrected for that distance. */
        public double magnitude() {
            return SeismicWave.magnitude(amplitudeMm, distanceMetres());
        }

        /** True when the pen ran off the paper, so the magnitude is only a lower bound. */
        public boolean clipped() {
            return amplitudeMm >= SeismicWave.CLIP_MM;
        }
    }

    private final List<Reading> readings = new ArrayList<>();
    /** The station's long record, day by day, and its watch on a restless volcano near it; printed on paper. */
    private final com.jeladastudios.ftsgeology.instrument.Seismogram.Record record = new com.jeladastudios.ftsgeology.instrument.Seismogram.Record();

    /**
     * One small quake of a volcano's swarm as this drum drew it. Kept apart from the readings: a swarm is dozens of
     * them, and would scroll every earthquake off the paper.
     */
    private record Tremor(long gameTime, double magnitude, double distanceMetres) {}

    /** How long a swarm's tremors are kept, and the window a warning counts them in, in ticks. */
    private static final long SWARM_KEPT = 12000L, SWARM_COUNTED = 1200L;
    /** Tremors in the counting window that sound the swarm warning, and the least time between two warnings. */
    private static final int SWARM_ALARM = 6;
    private static final long SWARM_ALARM_AGAIN = 6000L;
    private static final int SWARM_SIZE = 48;

    private final List<Tremor> swarm = new ArrayList<>();
    private long swarmAlarm = Long.MIN_VALUE;
    /** Newest network event this station has already worked through. */
    private long seen = -1L;
    /** Ticks left of shaking. */
    private int shake;
    /** Redstone output while shaking. */
    private int signal;
    /** Game time the ground will start moving, while the station is in its warning phase; 0 otherwise. */
    private long warnUntil;
    /** The arrival's redstone strength, held over from detection until the shaking actually lands. */
    private int pendingSignal;
    /** Ticks into a small quake's chime, or -1: a few notes, one every {@link #CHIME_STEP} ticks. Not saved. */
    private int chime = -1;
    private static final int CHIME_STEP = 4;
    /** The chime's notes, as note block pitches: a rising triad. */
    private static final float[] CHIME = {0.94f, 1.19f, 1.41f};

    /**
     * One arrival on the drum's live paper: when the P and S waves reach here, how big the swing, the unit vector from
     * the source to the station (east, north) the ground first moves along, what it was (0 a quake, 1 a volcano's tremor,
     * 2 an explosion) and how long its shaking rings on, in ticks. Not kept: only the last minutes are drawn.
     */
    private record Arrival(long pAt, long sAt, double ampMm, double magnitude, double rE, double rN, int kind, int lasts) {}

    private final List<Arrival> arrivals = new ArrayList<>();
    /** Samples on the drum and ticks between them: a minute of paper, four marks a second. */
    public static final int SAMPLES = 240, STEP = 5;

    /** Explosions heard, apart from the quakes: a quarry's blasts would scroll every earthquake off the paper. */
    private record Blast(long gameTime, double magnitude, double distanceMetres) {}

    private final List<Blast> blasts = new ArrayList<>();
    private static final long BLASTS_KEPT = 12000L;
    private static final int BLASTS_SIZE = 32;

    /**
     * The drum's own noise at the last look, in millimetres, and what made it: {@link #NOISY_WIND} and the rest. A
     * station on rock, under ground, far from anything moving, hears the smallest quakes; one on loose soil, in the
     * open, with the wind on it and people walking round, only the larger. Not kept: worked out again every second.
     */
    private double noise = SeismicWave.NOISE_FLOOR_MM;
    private int noisy, ground, cover;
    public static final int NOISY_WIND = 1, NOISY_RAIN = 2, NOISY_MOVING = 4, NOISY_SURFACE = 8;
    /** How much stronger than the noise a swing has to be to be picked out of it. */
    private static final double PICK = 2.0;

    public SeismographBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SEISMOGRAPH.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) com.jeladastudios.ftsgeology.instrument.SeismicStations.running(level, worldPosition);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) com.jeladastudios.ftsgeology.instrument.SeismicStations.stopped(level, worldPosition);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && !level.isClientSide) com.jeladastudios.ftsgeology.instrument.SeismicStations.stopped(level, worldPosition);
    }

    /** The station's record printed on paper: a book of its quakes, day by day, and its watch on a volcano. */
    public net.minecraft.world.item.ItemStack printout(long now) {
        return com.jeladastudios.ftsgeology.instrument.Seismogram.print(record, worldPosition, now);
    }

    public int signal() {
        if (warnUntil > 0) return WARNING_SIGNAL;   // full through the alert
        return shake > 0 ? signal : 0;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SeismographBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;

        // A station placed today should not spool through everything that happened before it
        // existed; it starts its paper from now.
        if (be.seen < 0) {
            be.seen = SeismicNetwork.latestId();
            be.setChanged();
        }

        long now = level.getGameTime();

        // Warning phase: siren wailing and redstone held full until the shaking is due.
        if (be.warnUntil > 0) {
            if (now >= be.warnUntil) {
                be.warnUntil = 0;
                be.signal = be.pendingSignal;
                be.shake = SHAKE_TICKS;
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS,
                        0.9f, 0.6f);   // the ground is moving now
                level.updateNeighborsAt(pos, state.getBlock());
                be.setChanged();
            } else if (now % 8L == 0L) {
                be.siren(server, pos);
            }
        }

        if (be.shake > 0 && --be.shake == 0) {
            level.updateNeighborsAt(pos, state.getBlock());   // the signal drops
            be.setChanged();
        }
        if (be.shake > 0 && now % 4L == 0L) be.scratch(server, pos);
        if (be.chime >= 0) {
            if (be.chime % CHIME_STEP == 0) {
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.8f,
                        CHIME[be.chime / CHIME_STEP]);
            }
            if (++be.chime >= CHIME.length * CHIME_STEP) be.chime = -1;
        }

        if (now % 20L != 0L) return;   // catching up is a once-a-second job
        be.record.started(now);
        be.listen(server, pos);
        be.arrivals.removeIf(a -> now - a.sAt() > a.lasts() * 3L + (long) SAMPLES * STEP);
        // Once a game hour, a look at the restless volcano near it, if there is one: how far its ground has swelled here.
        if (now % 1000L == 0L) {
            var restless = com.jeladastudios.ftsgeology.volcano.VolcanoUnrest.nearest(level, pos.getX(), pos.getZ());
            if (restless != null) {
                be.record.watch(now, (int) Math.round(Math.hypot(pos.getX() - restless.summit().getX(), pos.getZ() - restless.summit().getZ())
                                * DepthScale.metresPerBlockHorizontal()),
                        com.jeladastudios.ftsgeology.volcano.VolcanoUnrest.swellCm(restless, pos.getX(), pos.getZ()),
                        com.jeladastudios.ftsgeology.volcano.VolcanoUnrest.near(level, pos));
            } else {
                be.record.hourQuiet();
            }
            be.setChanged();
        }
        // The faults round a station keep breaking while nobody is near, so there is something for it to draw.
        if (now % 1200L == 0L) com.jeladastudios.ftsgeology.quake.FaultClocks.station(server, pos);
        for (SeismicNetwork.Event e : SeismicNetwork.since(server.dimension(), be.seen)) {
            be.seen = Math.max(be.seen, e.id());
            be.consider(server, pos, state, e);
        }
    }

    /** Works out what this drum would have drawn for one earthquake, and files it if anything did. */
    private void consider(ServerLevel level, BlockPos pos, BlockState state,
                          SeismicNetwork.Event e) {
        double flat = Math.sqrt(pos.distSqr(new BlockPos(
                e.hypocentre().getX(), pos.getY(), e.hypocentre().getZ())));
        int range = GeyserConfig.SEISMOGRAPH_RANGE.get();
        if (flat > range) return;                       // off the end of this station's world

        double d = SeismicWave.hypocentralMetres(flat, e.depthMetres());
        double amp = Math.min(SeismicWave.CLIP_MM, SeismicWave.amplitudeMm(e.magnitude(), d));
        if (!SeismicWave.detectable(amp) || amp < PICK * noise) return;       // lost in the drum's own noise
        // The waves leave the source after the alert window for a quake, at once for a tremor or a blast.
        long leaves = e.gameTime() + (e.volcanic() || e.blast() ? 0 : GeyserConfig.QUAKE_WARNING_TICKS.get());
        double dx = pos.getX() - e.hypocentre().getX(), dz = pos.getZ() - e.hypocentre().getZ();
        double r = Math.max(1e-6, Math.hypot(dx, dz));
        arrivals.add(new Arrival(leaves + com.jeladastudios.ftsgeology.quake.FeltShaking.travelTicks(flat, e.depthMetres(), SeismicWave.VP),
                leaves + com.jeladastudios.ftsgeology.quake.FeltShaking.travelTicks(flat, e.depthMetres(), SeismicWave.VS),
                amp, e.magnitude(), dx / r, -dz / r, e.blast() ? 2 : e.volcanic() ? 1 : 0,
                e.blast() ? 8 : Math.max(20, com.jeladastudios.ftsgeology.quake.FeltShaking.durationTicks(e.magnitude(), flat) / 2)));
        if (e.blast()) {
            blasts.add(new Blast(e.gameTime(), e.magnitude(), d));
            blasts.removeIf(b -> level.getGameTime() - b.gameTime() > BLASTS_KEPT);
            while (blasts.size() > BLASTS_SIZE) blasts.remove(0);
            if (warnUntil == 0) {
                signal = Math.min(SeismicWave.signal(amp), 4);
                shake = Math.max(shake, 10);
            }
            level.updateNeighborsAt(pos, state.getBlock());
            setChanged();
            return;
        }
        if (e.volcanic()) {
            tremor(level, pos, state, e, amp, d);
            return;
        }

        // Which way the ground first moved, on the two level components: away from the source. Read against the
        // noise, so a faint arrival on a noisy drum gives its way only roughly.
        double toward = Math.toDegrees(Math.atan2(-dx, dz));
        double err = Mth.clamp(60.0 * noise / (0.3 * amp), 3.0, 90.0);
        double azimuth = ((toward + level.random.nextGaussian() * err * 0.6) % 360.0 + 360.0) % 360.0;
        // The S wave's onset picked off the paper, a little off where the drum is noisy against it.
        double sp = Math.max(0.01, SeismicWave.spSeconds(d) + level.random.nextGaussian() * (0.03 + 0.5 * noise / amp));
        readings.add(0, new Reading(e.id(), sp, amp, e.gameTime(), azimuth, err));
        com.jeladastudios.ftsgeology.instrument.SeismicStations.pick(e.id(), pos, readings.get(0).distanceMetres());
        record.quake(e.gameTime(), readings.get(0).magnitude(), readings.get(0).distanceMetres());
        com.jeladastudios.ftsgeology.advancement.GeologyTrigger.awardNear(level, pos.getX(), pos.getZ(), 32, "seismogram");
        while (readings.size() > LOG_SIZE) readings.remove(readings.size() - 1);

        int sig = SeismicWave.signal(amp);
        // The siren for a large quake only; a small one is a short chime, whether it is caught early or not.
        boolean big = readings.get(0).magnitude() >= GeyserConfig.SEISMOGRAPH_SIREN.get();
        // Caught before the ground moves: warning phase. Otherwise the arrival is treated as now.
        // The shaking here comes with the S wave, later the further the station is from the rupture.
        long groundMoves = e.gameTime() + GeyserConfig.QUAKE_WARNING_TICKS.get()
                + com.jeladastudios.ftsgeology.quake.FeltShaking.travelTicks(flat, e.depthMetres(), SeismicWave.VS);
        if (groundMoves > level.getGameTime() + 5L) {
            warnUntil = groundMoves;
            pendingSignal = sig;
            // One ten-second wail, started here and left to run for the length of the window.
            if (big) level.playSound(null, pos, ModSounds.QUAKE_SIREN.get(), SoundSource.BLOCKS, 3.0f, 1.0f);
            else chime = 0;
        } else {
            signal = sig;
            shake = SHAKE_TICKS;
            if (big) level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.9f, 0.6f);
            else chime = 0;
        }
        level.updateNeighborsAt(pos, state.getBlock());
        setChanged();
    }

    /**
     * A tremor of a volcano's swarm: the needle twitches and the drum keeps it, but the siren sounds only when the
     * tremors come thick and fast, which is the sign an eruption is near.
     */
    private void tremor(ServerLevel level, BlockPos pos, BlockState state, SeismicNetwork.Event e, double amp,
                        double metres) {
        long now = level.getGameTime();
        swarm.add(new Tremor(e.gameTime(), e.magnitude(), metres));
        record.tremor(e.gameTime(), e.magnitude());
        swarm.removeIf(t -> now - t.gameTime() > SWARM_KEPT);
        while (swarm.size() > SWARM_SIZE) swarm.remove(0);
        if (warnUntil == 0) {
            signal = Math.min(SeismicWave.signal(amp), 6);
            shake = Math.max(shake, 30);
        }
        long recent = swarm.stream().filter(t -> now - t.gameTime() <= SWARM_COUNTED).count();
        if (recent >= SWARM_ALARM && (swarmAlarm == Long.MIN_VALUE || now - swarmAlarm >= SWARM_ALARM_AGAIN)) {
            swarmAlarm = now;
            warnUntil = now + 200L;
            pendingSignal = 8;
            level.playSound(null, pos, ModSounds.QUAKE_SIREN.get(), SoundSource.BLOCKS, 3.0f, 0.7f);
            com.jeladastudios.ftsgeology.util.Diagnostics.info("Seismograph at {} sounds a swarm warning: {} tremors in a minute",
                    pos, recent);
        }
        level.updateNeighborsAt(pos, state.getBlock());
        setChanged();
    }

    /** The swarm on the paper: how many tremors in the last ten minutes, the strongest, how far, and whether it is growing. */
    private Component swarmLine(long now) {
        List<Tremor> kept = swarm.stream().filter(t -> now - t.gameTime() <= SWARM_KEPT).toList();
        if (kept.isEmpty()) return null;
        double strongest = 0, near = Double.MAX_VALUE;
        int late = 0, early = 0;
        for (Tremor t : kept) {
            strongest = Math.max(strongest, t.magnitude());
            near = Math.min(near, t.distanceMetres());
            long age = now - t.gameTime();
            if (age <= 3000L) late++;
            else if (age <= 6000L) early++;
        }
        boolean rising = late > early + 1;
        return Component.translatable(rising ? "message.fts_geology.seismograph.swarm_rising"
                        : "message.fts_geology.seismograph.swarm",
                String.valueOf(kept.size()), String.format(Locale.ROOT, "%.1f", strongest), DepthScale.format(near))
                .withStyle(rising ? ChatFormatting.RED : ChatFormatting.GOLD);
    }

    /**
     * The visible half of the alert. The siren is one ten-second clip started when the warning
     * begins; retriggering it would stack overlapping copies.
     */
    private void siren(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.NOTE,
                pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 1, 0.2, 0.0, 0.2, 0.0);
    }

    /** The needle scratching across the paper: a little dust and a tick, while it is still moving. */
    private void scratch(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.CRIT,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.2, 0.02, 0.2, 0.0);
        if (level.random.nextInt(3) == 0) {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS,
                    0.2f, 2.0f);
        }
    }

    /**
     * The paper, read out. Deliberately in the order a seismologist reads it: what was measured
     * first, what it implies second.
     */
    public List<Component> report(long now) {
        List<Component> out = new ArrayList<>();
        Component swarmed = swarmLine(now);
        if (swarmed != null) out.add(swarmed);
        if (readings.isEmpty()) {
            if (swarmed == null) {
                out.add(Component.translatable("message.fts_geology.seismograph.empty")
                        .withStyle(ChatFormatting.GRAY));
            }
            return out;
        }
        out.add(Component.translatable("message.fts_geology.seismograph.header",
                String.valueOf(readings.size())).withStyle(ChatFormatting.GOLD));
        // Oldest first, so the newest is the last line, just over where chat is typed.
        for (int i = readings.size() - 1; i >= 0; i--) {
            Reading r = readings.get(i);
            out.add(Component.translatable(r.clipped()
                            ? "message.fts_geology.seismograph.clipped"
                            : "message.fts_geology.seismograph.line",
                    String.format(Locale.ROOT, "%.1f", r.spSeconds()),
                    String.format(Locale.ROOT, "%.1f", r.amplitudeMm()),
                    DepthScale.format(r.distanceMetres()),
                    String.format(Locale.ROOT, "%.1f", r.magnitude()),
                    from(r),
                    ago(now - r.gameTime()))
                    .withStyle(r.clipped() ? ChatFormatting.RED : ChatFormatting.WHITE));
        }
        Component fixed = fixLine();
        if (fixed != null) out.add(fixed);
        Component blasted = blastLine(now);
        if (blasted != null) out.add(blasted);
        out.add(Component.translatable("message.fts_geology.seismograph.footer")
                .withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    /** Which way a reading's waves came from, as the first motion gave it: a compass point and how far it may be off. */
    public static Component from(Reading r) {
        if (r.azimuth() < 0) return Component.translatable("message.fts_geology.seismograph.from_unknown");
        return Component.translatable("message.fts_geology.seismograph.from",
                Component.translatable("message.fts_geology.compass." + com.jeladastudios.ftsgeology.block.WeatherInstrumentBlock.compass(r.azimuth())),
                String.valueOf((int) Math.round(r.err())));
    }

    /**
     * Where the network put the newest quake this drum read that two other stations listening read too: the epicentre
     * their distances cross at, how far off it may be, and how deep.
     */
    private Component fixLine() {
        for (Reading r : readings) {
            double[] f = com.jeladastudios.ftsgeology.instrument.SeismicStations.fix(r.eventId());
            if (f == null) continue;
            return Component.translatable("message.fts_geology.seismograph.fix", String.valueOf((int) f[3]),
                    String.format(Locale.ROOT, "%.1f", r.magnitude()), String.valueOf(Math.round(f[0])), String.valueOf(Math.round(f[1])),
                    String.valueOf(Math.round(f[2])), DepthScale.format(f[4])).withStyle(ChatFormatting.AQUA);
        }
        return null;
    }

    /** The explosions of the last ten minutes, in one line: how many, the largest, the nearest. */
    private Component blastLine(long now) {
        List<Blast> kept = blasts.stream().filter(b -> now - b.gameTime() <= BLASTS_KEPT).toList();
        if (kept.isEmpty()) return null;
        double largest = 0, near = Double.MAX_VALUE;
        for (Blast b : kept) {
            largest = Math.max(largest, b.magnitude());
            near = Math.min(near, b.distanceMetres());
        }
        return Component.translatable("message.fts_geology.seismograph.blasts", String.valueOf(kept.size()),
                String.format(Locale.ROOT, "%.1f", largest), DepthScale.format(near)).withStyle(ChatFormatting.GRAY);
    }

    /**
     * What the drum hears over: the ground it stands on, what is over it, the weather on it, what moves round it. The
     * ground's own hum is the floor; loose soil rings with it more than rock, the wind and the rain shake a station in
     * the open, and a walking player or a grazing cow thumps the floor near it.
     */
    private void listen(ServerLevel level, BlockPos pos) {
        BlockState under = level.getBlockState(pos.below());
        ground = rock(under) ? 0 : loose(under) ? 1 : 2;
        double coupling = ground == 0 ? 1.0 : ground == 1 ? 2.5 : 4.0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        cover = 0;
        for (int y = pos.getY() + 1; y <= pos.getY() + 16; y++) {
            BlockState s = level.getBlockState(m.set(pos.getX(), y, pos.getZ()));
            if (!s.isAir() && s.getFluidState().isEmpty()) cover++;
        }
        double buried = 0.3 + 0.7 / (1.0 + cover / 6.0);
        double env = 1.0;
        noisy = 0;
        if (level.canSeeSky(pos.above())) {
            noisy |= NOISY_SURFACE;
            double[] w = com.jeladastudios.ftsgeology.weather.Atmosphere.wind(level, pos.getX(), pos.getZ());
            double speed = Math.hypot(w[0], w[1]);
            env *= 1.0 + speed / 8.0;
            if (speed >= 4.0) noisy |= NOISY_WIND;
            double rain = com.jeladastudios.ftsgeology.weather.Storms.intensityAt(level, pos.getX(), pos.getZ());
            env *= 1.0 + 1.5 * rain;
            if (rain >= com.jeladastudios.ftsgeology.weather.Storms.WET) noisy |= NOISY_RAIN;
        }
        double activity = 0;
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(12))) {
            double d2 = e.distanceToSqr(pos.getCenter());
            activity += (0.15 + 8.0 * e.getDeltaMovement().horizontalDistance()) / (1.0 + d2 / 16.0);
        }
        if (activity >= 0.3) noisy |= NOISY_MOVING;
        noise = SeismicWave.NOISE_FLOOR_MM * coupling * buried * env * (1.0 + activity);
    }

    private static boolean rock(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraftforge.common.Tags.Blocks.STONE)
                || s.is(net.minecraft.tags.BlockTags.STONE_ORE_REPLACEABLES) || s.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                || com.jeladastudios.ftsgeology.instrument.RockTypes.isRock(s);
    }

    private static boolean loose(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.SAND)
                || s.is(net.minecraftforge.common.Tags.Blocks.GRAVEL) || s.is(net.minecraft.world.level.block.Blocks.CLAY)
                || s.is(net.minecraft.world.level.block.Blocks.MUD);
    }

    /** The smallest magnitude this drum picks out of its noise a hundred kilometres off, as it stands now. */
    public double hears() {
        return SeismicWave.magnitude(PICK * noise, 100_000.0);
    }

    /**
     * The drum's paper and its log, for its screen: the last minute on three components, the site and its noise, and the
     * readings oldest first.
     */
    public CompoundTag data(ServerLevel level) {
        long now = level.getGameTime();
        CompoundTag t = new CompoundTag();
        t.putBoolean("Seismo", true);
        String[] names = {"Z", "N", "E"};
        for (int c = 0; c < 3; c++) {
            byte[] trace = new byte[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                long at = (now / STEP - (SAMPLES - 1 - i)) * STEP;
                trace[i] = squeeze(swing(at, c));
            }
            t.putByteArray(names[c], trace);
        }
        t.putLong("Now", now);
        t.putFloat("Noise", (float) noise);
        t.putInt("Why", noisy);
        t.putInt("Ground", ground);
        t.putInt("Cover", cover);
        t.putFloat("Hears", (float) hears());
        net.minecraft.nbt.ListTag log = new net.minecraft.nbt.ListTag();
        for (int i = readings.size() - 1; i >= 0; i--) {
            Reading r = readings.get(i);
            CompoundTag c = new CompoundTag();
            c.putFloat("Sp", (float) r.spSeconds());
            c.putFloat("Amp", (float) r.amplitudeMm());
            c.putString("Dist", DepthScale.format(r.distanceMetres()));
            c.putFloat("M", (float) r.magnitude());
            c.putBoolean("Clip", r.clipped());
            c.putString("From", Component.Serializer.toJson(from(r)));
            c.putString("Ago", ago(now - r.gameTime()));
            log.add(c);
        }
        t.put("Log", log);
        net.minecraft.nbt.ListTag extra = new net.minecraft.nbt.ListTag();
        Component swarmed = swarmLine(now), blasted = blastLine(now);
        Component fixed = fixLine();
        if (fixed != null) extra.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(fixed)));
        if (swarmed != null) extra.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(swarmed)));
        if (blasted != null) extra.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(blasted)));
        t.put("Extra", extra);
        return t;
    }

    /**
     * The ground's swing here at a tick, in millimetres, on one component (0 up, 1 north, 2 east): the drum's noise and
     * every arrival's waves. The P wave comes first and small, the ground pushed away from the source and up; the S wave
     * after it, larger and across the way the waves travel; the long, slow surface waves last. An explosion is all P.
     */
    private double swing(long at, int comp) {
        long seed = worldPosition.asLong();
        double v = noise * (jitter(seed, at, 7, comp) + jitter(seed, at, 11, comp)) * 0.9;
        for (Arrival a : arrivals) {
            if (at < a.pAt()) continue;
            double dtP = at - a.pAt();
            double pAmp = (a.kind() == 2 ? 1.0 : 0.3) * a.ampMm();
            // The first swing is the push away from the source and up; then the P wave's ringing, dying away.
            double p = dtP < STEP ? pAmp : pAmp * Math.exp(-dtP / (a.kind() == 2 ? 6.0 : 8.0 + 3.0 * Math.max(0, a.magnitude())))
                    * jitter(seed ^ a.pAt(), at, 3, comp);
            double s = 0, surf = 0;
            if (a.kind() != 2 && at >= a.sAt()) {
                double dtS = at - a.sAt();
                s = a.ampMm() * Math.min(1.0, dtS / 10.0) * Math.exp(-Math.max(0, dtS - 10) / a.lasts())
                        * jitter(seed ^ a.sAt(), at, 5, comp);
                double dtR = dtS - 15;
                if (dtR > 0 && a.magnitude() >= 4.0) {
                    surf = 0.7 * a.ampMm() * Math.min(1.0, dtR / 20.0) * Math.exp(-dtR / (1.5 * a.lasts()))
                            * Math.sin(2 * Math.PI * dtR / 40.0);
                }
            }
            v += switch (comp) {
                case 0 -> 0.9 * p + 0.3 * s + surf;
                case 1 -> p * a.rN() + s * a.rE() + 0.8 * surf * a.rN();
                default -> p * a.rE() - s * a.rN() + 0.8 * surf * a.rE();
            };
        }
        return v;
    }

    /**
     * The drum's last minute printed on a map, to keep or hang in a frame: the three components on paper, marked every
     * ten seconds, the station and the time written on it. The map is locked: it is a print, not a view of the land.
     */
    public net.minecraft.world.item.ItemStack traceMap(ServerLevel level) {
        net.minecraft.world.item.ItemStack map = net.minecraft.world.item.MapItem.create(level, worldPosition.getX(), worldPosition.getZ(),
                (byte) 0, false, false);
        net.minecraft.world.item.MapItem.lockMap(level, map);
        var data = net.minecraft.world.item.MapItem.getSavedData(map, level);
        if (data == null) return map;
        byte paper = net.minecraft.world.level.material.MapColor.SAND.getPackedId(net.minecraft.world.level.material.MapColor.Brightness.HIGH);
        byte grid = net.minecraft.world.level.material.MapColor.SAND.getPackedId(net.minecraft.world.level.material.MapColor.Brightness.NORMAL);
        byte[] inks = {net.minecraft.world.level.material.MapColor.COLOR_BLUE.getPackedId(net.minecraft.world.level.material.MapColor.Brightness.LOW),
                net.minecraft.world.level.material.MapColor.COLOR_GREEN.getPackedId(net.minecraft.world.level.material.MapColor.Brightness.LOW),
                net.minecraft.world.level.material.MapColor.COLOR_RED.getPackedId(net.minecraft.world.level.material.MapColor.Brightness.LOW)};
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) data.setColor(x, y, paper);
        }
        long now = level.getGameTime();
        // Ten seconds a mark, back from now at the right edge.
        for (int i = SAMPLES - 1; i >= 0; i -= 200 / STEP) {
            int x = i * 128 / SAMPLES;
            for (int y = 2; y < 126; y += 2) data.setColor(x, y, grid);
        }
        int lane = 128 / 3;
        for (int c = 0; c < 3; c++) {
            int mid = lane * c + lane / 2 + 1, half = lane / 2 - 2, prev = mid;
            for (int x = 0; x < 128; x++) data.setColor(x, mid, grid);
            for (int x = 0; x < 128; x++) {
                int i = x * SAMPLES / 128;
                long at = (now / STEP - (SAMPLES - 1 - i)) * STEP;
                int y = mid - squeeze(swing(at, c)) * half / 127;
                int lo = x == 0 ? y : Math.min(prev, y), hi = x == 0 ? y : Math.max(prev, y);
                for (int yy = lo; yy <= hi; yy++) data.setColor(x, Mth.clamp(yy, 0, 127), inks[c]);
                prev = y;
            }
        }
        data.setDirty();
        long day = level.getDayTime() / 24000L + 1, t = (level.getDayTime() + 6000L) % 24000L;
        map.setHoverName(Component.translatable("item.fts_geology.seismogram_map", worldPosition.getX(), worldPosition.getZ(),
                day, String.format(Locale.ROOT, "%02d:%02d", t / 1000, t % 1000 * 60 / 1000)).withStyle(style -> style.withItalic(false)));
        return map;
    }

    /** A steady random wobble, -1 to 1, the same for the same station, tick, wave and component. */
    private static double jitter(long seed, long at, int wave, int comp) {
        long h = seed * 0x9E3779B97F4A7C15L + (at / STEP) * 0xC2B2AE3D27D4EB4FL + wave * 0x165667B19E3779F9L + comp * 0x27D4EB2F165667C5L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    /** A swing drawn on the paper, -127 to 127: on a log scale, so the drum's hum and a great quake both show. */
    private static byte squeeze(double mm) {
        double n0 = SeismicWave.NOISE_FLOOR_MM * 0.5;
        double v = Math.log10(1.0 + Math.abs(mm) / n0) / Math.log10(1.0 + SeismicWave.CLIP_MM / n0);
        return (byte) Math.round(Math.signum(mm) * Math.min(1.0, v) * 127.0);
    }

    /** "3m 20s ago", from a tick count. */
    private static String ago(long ticks) {
        long s = Math.max(0, ticks) / 20L;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("Seen", seen);
        tag.putInt("Shake", shake);
        tag.putInt("Signal", signal);
        tag.putLong("WarnUntil", warnUntil);
        tag.putInt("PendingSignal", pendingSignal);
        ListTag list = new ListTag();
        for (Reading r : readings) {
            CompoundTag t = new CompoundTag();
            t.putLong("Id", r.eventId());
            t.putDouble("Sp", r.spSeconds());
            t.putDouble("Amp", r.amplitudeMm());
            t.putLong("At", r.gameTime());
            t.putDouble("Az", r.azimuth());
            t.putDouble("Err", r.err());
            list.add(t);
        }
        tag.put("Readings", list);
        if (!swarm.isEmpty()) {
            ListTag tremors = new ListTag();
            for (Tremor t : swarm) {
                CompoundTag c = new CompoundTag();
                c.putLong("At", t.gameTime());
                c.putDouble("M", t.magnitude());
                c.putDouble("D", t.distanceMetres());
                tremors.add(c);
            }
            tag.put("Swarm", tremors);
        }
        tag.putLong("SwarmAlarm", swarmAlarm);
        record.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        seen = tag.contains("Seen") ? tag.getLong("Seen") : -1L;
        shake = tag.getInt("Shake");
        signal = tag.getInt("Signal");
        warnUntil = tag.getLong("WarnUntil");
        pendingSignal = tag.getInt("PendingSignal");
        readings.clear();
        for (Tag t : tag.getList("Readings", Tag.TAG_COMPOUND)) {
            // Bounded on the way in too, in case the tag was edited.
            if (readings.size() >= LOG_SIZE) break;
            CompoundTag c = (CompoundTag) t;
            readings.add(new Reading(c.getLong("Id"), c.getDouble("Sp"),
                    c.getDouble("Amp"), c.getLong("At"), c.contains("Az") ? c.getDouble("Az") : -1, c.getDouble("Err")));
        }
        swarm.clear();
        for (Tag t : tag.getList("Swarm", Tag.TAG_COMPOUND)) {
            if (swarm.size() >= SWARM_SIZE) break;
            CompoundTag c = (CompoundTag) t;
            swarm.add(new Tremor(c.getLong("At"), c.getDouble("M"), c.getDouble("D")));
        }
        swarmAlarm = tag.contains("SwarmAlarm") ? tag.getLong("SwarmAlarm") : Long.MIN_VALUE;
        record.load(tag);
    }
}
