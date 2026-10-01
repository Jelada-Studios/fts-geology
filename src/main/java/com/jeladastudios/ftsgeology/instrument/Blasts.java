package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.GeysersMod;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Explosions, for the seismographs: TNT in a quarry, a creeper, a bed in the Nether's way. The ground takes the shock as
 * it takes a quake's, and a station near enough draws it (see {@link SeismicNetwork#recordBlast}).
 *
 * <p>How big, from how much the blast broke: a charge that shattered sixty blocks of rock is a magnitude of about one, one
 * that went off in water hardly anything. A chain of charges going off together is one blast on the paper.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class Blasts {

    private Blasts() {}

    /** Ticks and the square, in blocks, within which charges going off together are one blast. */
    private static final long TOGETHER = 10;
    private static final int AREA = 32;

    private static final Long2LongOpenHashMap LAST = new Long2LongOpenHashMap();

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos at = BlockPos.containing(event.getExplosion().getPosition());
        long area = BlockPos.asLong(Math.floorDiv(at.getX(), AREA), 0, Math.floorDiv(at.getZ(), AREA));
        long now = level.getGameTime();
        synchronized (LAST) {
            if (now - LAST.getOrDefault(area, Long.MIN_VALUE / 2) < TOGETHER) return;
            LAST.put(area, now);
            if (LAST.size() > 4096) LAST.clear();
        }
        double magnitude = 0.6 * Math.log10(1.0 + event.getAffectedBlocks().size()) - 0.2;
        SeismicNetwork.recordBlast(level, at, magnitude);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (LAST) {
            LAST.clear();
        }
    }
}
