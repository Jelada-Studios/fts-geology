package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.instrument.SeismicNetwork;
import com.jeladastudios.ftsgeology.instrument.SeismicWave;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A recording station. Turns earthquakes into measurements a player can actually work with.
 *
 * <h2>What it knows and what it does not</h2>
 * The station is never told where a quake was or how big it was. It is told nothing at all: it
 * reads {@link SeismicNetwork}, works out what its own drum would have drawn, and keeps that. So
 * every line in its log is a <b>measurement</b> - the gap between the two wave arrivals, and how far
 * the needle swung - with the distance and magnitude derived from those two numbers the way a
 * seismologist derives them.
 *
 * <p>It therefore cannot tell you which way the earthquake was, because a single seismograph
 * genuinely cannot. Distance alone puts the epicentre somewhere on a circle. Two stations narrow it
 * to two points, three fix it. Building that network is the instrument's actual gameplay, and it is
 * also exactly how the real thing is done.</p>
 *
 * <h2>Redstone</h2>
 * The station holds a signal for a few seconds after an event, scaled by how hard the ground shook
 * here - measured across the usual range that runs from about 3 for a distant tremor to 15 for
 * something that will take a hillside with it. Enough to build a warning bell, or a door that shuts
 * itself.
 */
public class SeismographBlockEntity extends BlockEntity {

    /** How many measurements the drum keeps before the oldest scrolls off the paper. */
    private static final int LOG_SIZE = 8;

    /** Ticks the needle keeps twitching, and the redstone signal stays up, after an arrival. */
    private static final int SHAKE_TICKS = 100;

    /** Redstone the station holds through the warning window: full, so a bell rings without wiring. */
    private static final int WARNING_SIGNAL = 15;

    /** One line of the station's own paper trace. Measured values only. */
    public record Reading(long eventId, double spSeconds, double amplitudeMm, long gameTime) {

        /** Distance to the hypocentre, in metres, as read off the S-P gap. */
        public double distanceMetres() {
            return SeismicWave.distanceMetres(spSeconds);
        }

        /** Magnitude, from the swing corrected for that distance. */
        public double magnitude() {
            return SeismicWave.magnitude(amplitudeMm, distanceMetres());
        }

        /**
         * True when the pen ran off the paper, so the magnitude is only a lower bound.
         *
         * <p>Worth saying out loud rather than quietly reporting a wrong number. A station sitting
         * on top of a large earthquake cannot measure it - that is a real limitation and the reason
         * a magnitude is agreed between distant stations rather than read off the nearest one.</p>
         */
        public boolean clipped() {
            return amplitudeMm >= SeismicWave.CLIP_MM;
        }
    }

    private final List<Reading> readings = new ArrayList<>();
    /** Newest network event this station has already worked through. */
    private long seen = -1L;
    /** Ticks left of shaking. */
    private int shake;
    /** Redstone output while shaking. */
    private int signal;
    /**
     * Game time the ground will start moving, while the station is in its warning phase; 0 when it
     * is not. This is the early-warning window: the quake was detected, the siren is wailing, and
     * nothing has shaken yet.
     */
    private long warnUntil;
    /** The arrival's redstone strength, held over from detection until the shaking actually lands. */
    private int pendingSignal;

    public SeismographBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SEISMOGRAPH.get(), pos, state);
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

        // Warning phase: the quake is on its way but the ground has not moved. The siren wails and
        // the redstone is held full until the moment the shaking is due, when it hands off to the
        // ordinary arrival pulse.
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
                be.siren(server, pos, now);
            }
        }

        if (be.shake > 0 && --be.shake == 0) {
            level.updateNeighborsAt(pos, state.getBlock());   // the signal drops
            be.setChanged();
        }
        if (be.shake > 0 && now % 4L == 0L) be.scratch(server, pos);

        if (now % 20L != 0L) return;   // catching up is a once-a-second job
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
        if (!SeismicWave.detectable(amp)) return;       // lost in the drum's own noise

        readings.add(0, new Reading(e.id(), SeismicWave.spSeconds(d), amp, e.gameTime()));
        while (readings.size() > LOG_SIZE) readings.remove(readings.size() - 1);

        int sig = SeismicWave.signal(amp);
        // The ground moves warningTicks after the quake was filed. If that is still ahead of us,
        // this station has caught the alert early and enters its warning phase; if the window has
        // already passed - warnings disabled, or a station reading back through old events - the
        // arrival is treated as happening now.
        long groundMoves = e.gameTime() + GeyserConfig.QUAKE_WARNING_TICKS.get();
        if (groundMoves > level.getGameTime() + 5L) {
            warnUntil = groundMoves;
            pendingSignal = sig;
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS,
                    1.0f, 1.8f);   // alarm raised
        } else {
            signal = sig;
            shake = SHAKE_TICKS;
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS,
                    0.9f, 0.6f);
        }
        level.updateNeighborsAt(pos, state.getBlock());
        setChanged();
    }

    /**
     * The warning wail: two alternating tones, a couple of blocks around the station, so it reads
     * as an alarm rather than a note. Loud enough to hear across a room, brief enough not to become
     * a nuisance over a ten-second window.
     */
    private void siren(ServerLevel level, BlockPos pos, long now) {
        float pitch = (now / 8L) % 2L == 0L ? 1.9f : 1.5f;
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS,
                0.8f, pitch);
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
        if (readings.isEmpty()) {
            out.add(Component.translatable("message.fts_geology.seismograph.empty")
                    .withStyle(ChatFormatting.GRAY));
            return out;
        }
        out.add(Component.translatable("message.fts_geology.seismograph.header",
                String.valueOf(readings.size())).withStyle(ChatFormatting.GOLD));
        for (Reading r : readings) {
            out.add(Component.translatable(r.clipped()
                            ? "message.fts_geology.seismograph.clipped"
                            : "message.fts_geology.seismograph.line",
                    String.format(Locale.ROOT, "%.1f", r.spSeconds()),
                    String.format(Locale.ROOT, "%.1f", r.amplitudeMm()),
                    DepthScale.format(r.distanceMetres()),
                    String.format(Locale.ROOT, "%.1f", r.magnitude()),
                    ago(now - r.gameTime()))
                    .withStyle(r.clipped() ? ChatFormatting.RED : ChatFormatting.WHITE));
        }
        out.add(Component.translatable("message.fts_geology.seismograph.footer")
                .withStyle(ChatFormatting.DARK_GRAY));
        return out;
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
            list.add(t);
        }
        tag.put("Readings", list);
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
            // Bounded on the way in, not just on the way out. The drum only ever writes LOG_SIZE
            // lines, so a longer list means the tag was edited or corrupted, and there is no reason
            // to let it grow the list without limit.
            if (readings.size() >= LOG_SIZE) break;
            CompoundTag c = (CompoundTag) t;
            readings.add(new Reading(c.getLong("Id"), c.getDouble("Sp"),
                    c.getDouble("Amp"), c.getLong("At")));
        }
    }
}
