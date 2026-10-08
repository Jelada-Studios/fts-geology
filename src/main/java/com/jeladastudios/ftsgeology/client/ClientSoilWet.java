package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.network.SoilWetPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where rain would stand in puddles, for Puddles &amp; Floods, which draws them on the client: on hard or trodden ground
 * -- stone, paths, fields, clay -- and on soil only once its top is soaked, as it is through a wet spell on loam and
 * at once on clay, but hardly ever on sand, which drinks it. The server says how wet each cell's top is
 * ({@link SoilWetPacket}); where it has said nothing, the puddles are that mod's own.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class ClientSoilWet {

    private ClientSoilWet() {}

    private static final Map<Long, byte[]> WET = new ConcurrentHashMap<>();

    public static void set(SoilWetPacket p) {
        WET.put(ChunkPos.asLong(p.chunkX(), p.chunkZ()), p.wet());
    }

    /**
     * Whether a puddle may lie at {@code pos}, over the block under it, as far as the ground's water goes; true where
     * nothing is known, so that mod decides as it would.
     */
    public static boolean allowsPuddle(Level level, BlockPos pos) {
        byte[] w = WET.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (w == null) return true;
        BlockState ground = level.getBlockState(pos.below());
        if (ground.is(Blocks.SNOW) || ground.is(Blocks.SNOW_BLOCK)) return true;
        // Sand and gravel drink the rain; soil takes it until its top is soaked; hard, trodden and tilled ground none.
        boolean drinks = ground.is(BlockTags.SAND) || ground.is(Tags.Blocks.GRAVEL);
        boolean soil = drinks || (ground.is(BlockTags.DIRT) && !ground.is(Blocks.MUD) && !ground.is(Blocks.PACKED_MUD)
                && !name(ground).startsWith("trmt:eroded"));
        if (!soil) return true;
        int b = w[((pos.getZ() & 15) >> 2) * 4 + ((pos.getX() & 15) >> 2)];
        int wet = b & 15, kind = (b >> 4) & 3;
        boolean standing = (b & 64) != 0;
        if (standing) return true;
        if (drinks || kind == 1) return false;
        return wet >= (kind == 3 ? 8 : 13);
    }

    /**
     * Whether that mod may flood the open ground at sea level at {@code pos}: only where water stands on it, the cell's
     * top soaked through; true where nothing is known, so that mod decides as it would.
     */
    public static boolean allowsFlood(Level level, BlockPos pos) {
        byte[] w = WET.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (w == null) return true;
        int b = w[((pos.getZ() & 15) >> 2) * 4 + ((pos.getX() & 15) >> 2)];
        return (b & 64) != 0;
    }

    private static String name(BlockState s) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() != null && event.getLevel().isClientSide()) WET.remove(event.getChunk().getPos().toLong());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        WET.clear();
    }
}
