package com.jeladastudios.ftsgeology.client;


import net.neoforged.fml.common.EventBusSubscriber;
import com.jeladastudios.ftsgeology.GeysersMod;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
/**
 * The furnaces near the player that burn gas, as the server tells them ({@code network.GasFiredPacket}): no coal smoke
 * is drawn out of them. Forgotten with the level.
 */
@EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientGasFired {

    private ClientGasFired() {}

    private static final LongOpenHashSet FIRED = new LongOpenHashSet();

    public static void set(long pos, boolean on) {
        if (on) FIRED.add(pos);
        else FIRED.remove(pos);
    }

    public static boolean has(BlockPos pos) {
        return !FIRED.isEmpty() && FIRED.contains(pos.asLong());
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) FIRED.clear();
    }
}
