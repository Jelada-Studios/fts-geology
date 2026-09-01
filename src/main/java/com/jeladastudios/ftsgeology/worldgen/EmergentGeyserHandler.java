package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.GeyserCoreBlockEntity;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Turns <em>player-built</em> water-over-rock-over-lava setups into live geysers.
 *
 * <p>Opt-in (config {@code emergentEnabled}, default off). While enabled, each loaded player's
 * surroundings are periodically scanned for the pattern: a body of water sitting on a solid rock
 * layer, with lava a few blocks under that rock. When found, the rock cell is replaced with a
 * (hidden) {@code GeyserCore} flagged {@code emergent} — the normal thermodynamic engine then
 * heats the enclosed water, pressurises, and erupts. With {@code emergentDestructive} on, that
 * eruption breaks blocks: the "boil water over lava in your basement and it blows up" scenario.</p>
 *
 * <p>Cost is near-zero when disabled (guard short-circuits). When enabled it runs a bounded scan
 * once every {@code emergentScanIntervalTicks}; users who turn it on accept that cost.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class EmergentGeyserHandler {

    private EmergentGeyserHandler() {}

    /** Upper bound on the water-pocket flood-fill (we only need a rough size). */
    private static final int WATER_COUNT_CAP = 64;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!GeyserConfig.EMERGENT_ENABLED.get()) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % GeyserConfig.EMERGENT_SCAN_INTERVAL_TICKS.get() != 0L) return;

        for (ServerPlayer player : level.players()) {
            scanAround(level, player.blockPosition());
        }
    }

    /** Scans a bounded box around a point for one ignitable setup; ignites the first found. */
    private static void scanAround(ServerLevel level, BlockPos centre) {
        int r = GeyserConfig.EMERGENT_SCAN_RADIUS.get();
        int lavaDepth = GeyserConfig.EMERGENT_LAVA_DEPTH.get();
        int minWater = GeyserConfig.EMERGENT_MIN_WATER.get();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    m.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState s = level.getBlockState(m);

                    // Candidate separating layer: a solid, non-fluid rock cell...
                    if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                    if (!s.isSolidRender(level, m)) continue;

                    BlockPos rock = m.immutable();
                    // ...with water directly above...
                    if (!level.getBlockState(rock.above()).getFluidState().is(FluidTags.WATER)) continue;
                    // ...and lava within lavaDepth below.
                    if (!hasLavaBelow(level, rock, lavaDepth)) continue;
                    // ...and enough connected water to matter.
                    int water = countWaterPocket(level, rock.above(), minWater);
                    if (water < minWater) continue;
                    // ...and not right next to an existing core (avoid duplicates).
                    if (coreNearby(level, rock)) continue;

                    ignite(level, rock, water);
                    return; // one ignition per scan keeps it calm
                }
            }
        }
    }

    private static boolean hasLavaBelow(ServerLevel level, BlockPos rock, int depth) {
        for (int i = 1; i <= depth; i++) {
            if (level.getBlockState(rock.below(i)).getFluidState().is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** Counts connected water cells (capped). Returns as soon as the cap is hit. */
    private static int countWaterPocket(ServerLevel level, BlockPos start, int need) {
        if (!level.getBlockState(start).getFluidState().is(FluidTags.WATER)) return 0;
        Set<Long> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.asLong());
        int count = 0;
        while (!queue.isEmpty() && count < WATER_COUNT_CAP) {
            BlockPos p = queue.poll();
            if (!level.getBlockState(p).getFluidState().is(FluidTags.WATER)) continue;
            count++;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (seen.add(n.asLong())) queue.add(n);
            }
        }
        return count;
    }

    private static boolean coreNearby(ServerLevel level, BlockPos rock) {
        int r = GeyserConfig.EMERGENT_MIN_SPACING.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (level.getBlockState(rock.offset(dx, dy, dz))
                            .is(ModBlocks.GEYSER_CORE.get())) return true;
                }
            }
        }
        return false;
    }

    private static void ignite(ServerLevel level, BlockPos rock, int waterCount) {
        level.setBlock(rock, ModBlocks.GEYSER_CORE.get().defaultBlockState(), 2);
        if (level.getBlockEntity(rock) instanceof GeyserCoreBlockEntity core) {
            core.setEmergent(true);
            int magnitude = Mth.clamp(4 + waterCount / 8,
                    GeyserCoreBlockEntity.MIN_MAGNITUDE, GeyserCoreBlockEntity.MAX_MAGNITUDE);
            core.setMagnitude(magnitude);
        }
        GeysersMod.LOGGER.debug("Emergent geyser ignited at {} (water={})", rock, waterCount);
    }
}
