package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.weather.Storms;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rivers over their banks in a long, heavy rain.
 *
 * <p>Rain that falls on ground already soaked -- water standing on it, or the roots full (see {@link SoilWater}) -- does
 * not soak in; it runs off, and the rivers rise. Where it rains hard over a river and most of the ground round it is
 * soaked, the river comes up a block over its banks, two in the heaviest rain on the wettest ground, over whatever
 * low ground lies beside it, and stays up while the rain goes on; when it stops the water goes back down a layer at a
 * time, and where it stood on soil it leaves some mud (see {@link Floods}). In a narrow valley, where the water has
 * nowhere to spread, the same rise comes as a flash flood that carries along what stands in it.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class RiverFloods {

    private RiverFloods() {}

    /** Ticks between looks, the chunks looked at round each player, and how long a flood holds after its last renewal. */
    private static final int EVERY = 400, REACH = 3, HOLD = 1200, RECEDE = 600;

    private record Flash(AABB box, double fx, double fz, long until) {}

    private static final List<Flash> FLASHES = new ArrayList<>();
    private static long floods, flashes;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || !GeyserConfig.RIVER_FLOODS.get()) return;
        long now = level.getGameTime();
        if (!FLASHES.isEmpty()) push(level, now);
        if (now % EVERY != 0) return;
        LongOpenHashSet seen = new LongOpenHashSet();
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    int cx = at.x + dx, cz = at.z + dz;
                    if (seen.add(ChunkPos.asLong(cx, cz))) look(level, cx, cz);
                }
            }
        }
    }

    private static void look(ServerLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return;
        float rain = Storms.intensityAt(level, cx * 16 + 8, cz * 16 + 8);
        if (rain < Storms.HEAVY || !Puddles.rainsAt(chunk, cx * 16 + 8, cz * 16 + 8)) return;
        String key = "rain " + cx + "," + cz;
        // Where another mod runs the rivers' water, the flood is only reckoned and told (see HydraulicsHooks); the water
        // laid before it came is let go down.
        boolean hydraulics = HydraulicsHooks.active(level);
        // Already over its banks: it stays so while the rain goes on.
        if (!hydraulics && Floods.extend(level, key, HOLD)) {
            HydraulicsHooks.flood(level, cx, cz, 1);
            return;
        }
        double soaked = 0;
        int known = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double s = SoilWater.soaked(level, cx + dx, cz + dz);
                if (s < 0) continue;
                soaked += s;
                known++;
            }
        }
        if (known < 5) return;
        soaked /= known;
        if (soaked < 0.6) return;
        int height = rain >= 0.8f && soaked >= 0.85 ? 2 : 1;
        HydraulicsHooks.flood(level, cx, cz, height);
        if (hydraulics) return;
        // The river's running water in this chunk: the tops of its channel, and the way it runs.
        List<BlockPos> tops = new ArrayList<>();
        double fx = 0, fz = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = cx * 16 + lx, z = cz * 16 + lz;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                BlockState s = chunk.getBlockState(m.set(x, y, z));
                if (!s.is(ModBlocks.RIVER_WATER.get())) continue;
                int flow = s.getValue(RiverWaterFluid.FLOW);
                if (flow == 0) continue;
                Vec3 way = RiverWaterFluid.way(flow);
                fx += way.x;
                fz += way.z;
                tops.add(new BlockPos(x, y, z));
            }
        }
        if (tops.size() < 4) return;
        BlockPos middle = new BlockPos(cx * 16 + 8, tops.get(0).getY(), cz * 16 + 8);
        List<LongOpenHashSet> layers = Floods.spread(level, tops, height, middle, 24, 3000, k -> true);
        if (layers.isEmpty()) return;
        int laid = Floods.surge(level, layers, HOLD, RECEDE, "river over its banks at " + middle.toShortString(), key);
        if (laid == 0) return;
        floods++;
        // Little room to spread: a narrow valley, and the rise comes down it as a flash flood.
        if (height == 2 && layers.get(0).size() < tops.size() * 1.5) {
            double len = Math.hypot(fx, fz);
            if (len > 1e-6) {
                FLASHES.add(new Flash(new AABB(cx * 16 - 8, middle.getY() - 4, cz * 16 - 8, cx * 16 + 24, middle.getY() + 6, cz * 16 + 24),
                        fx / len, fz / len, level.getGameTime() + HOLD));
                flashes++;
                com.jeladastudios.ftsgeology.util.Diagnostics.info("flash flood down the valley at {}", middle.toShortString());
            }
        }
    }

    /** What stands in a flash flood's water is carried along with it. */
    private static void push(ServerLevel level, long now) {
        FLASHES.removeIf(f -> now > f.until());
        for (Flash f : FLASHES) {
            for (Entity e : level.getEntities((Entity) null, f.box(), e -> e.isInWater() && !e.isSpectator())) {
                if (!(level.getFluidState(e.blockPosition()).getType() instanceof RiverWaterFluid)) continue;
                e.setDeltaMovement(e.getDeltaMovement().add(f.fx() * 0.04, 0, f.fz() * 0.04));
                e.hurtMarked = true;
            }
        }
    }

    public static String summary() {
        return String.format(Locale.ROOT, "river floods: %d, %d of them flash floods", floods, flashes);
    }

    public static boolean any() {
        return floods > 0;
    }

    public static void clear() {
        FLASHES.clear();
        floods = flashes = 0;
    }
}
