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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
 * once every {@code emergentScanIntervalTicks}, and a section with no lava in its palette is passed
 * over without reading a cell.</p>
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
        // A rupture shears water and lava past each other constantly while it is being applied, so
        // scanning mid-quake reads those transients as a geyser waiting to happen and lights them.
        if (com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(level, centre)) return;
        int r = GeyserConfig.EMERGENT_SCAN_RADIUS.get();
        int lavaDepth = GeyserConfig.EMERGENT_LAVA_DEPTH.get();
        int minWater = GeyserConfig.EMERGENT_MIN_WATER.get();

        // The box is searched lavaDepth deeper, since a candidate rock near its floor can have its lava below it.
        int minX = centre.getX() - r, maxX = centre.getX() + r;
        int minZ = centre.getZ() - r, maxZ = centre.getZ() + r;
        int minY = Math.max(level.getMinBuildHeight(), centre.getY() - r - lavaDepth);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centre.getY() + r - 1);
        int rockCeiling = centre.getY() + r;

        // Right after a teleport, or flying fast, part of the box is not loaded yet. Reading it would load
        // it on the server thread, so the scan waits for the next round instead.
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return;
            }
        }

        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) return;
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections.length) continue;
                    LevelChunkSection section = sections[index];
                    // Lava is rare, so most sections are ruled out from their palette alone.
                    if (section.hasOnlyAir() || !section.maybeHas(s -> s.getFluidState().is(FluidTags.LAVA))) continue;

                    int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
                    int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
                    int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int y = y0; y <= y1; y++) {
                                if (!section.getBlockState(x & 15, y & 15, z & 15).getFluidState().is(FluidTags.LAVA)) continue;
                                // One ignition per scan keeps it calm.
                                if (tryIgnite(level, x, y, z, rockCeiling, lavaDepth, minWater)) return;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Looks up from one lava cell for the rock-with-water-on-it pattern, and ignites it if found. */
    private static boolean tryIgnite(ServerLevel level, int x, int lavaY, int z, int rockCeiling,
                                     int lavaDepth, int minWater) {
        for (int up = 1; up <= lavaDepth && lavaY + up <= rockCeiling; up++) {
            BlockPos rock = new BlockPos(x, lavaY + up, z);
            BlockState s = level.getBlockState(rock);
            // Candidate separating layer: a solid, non-fluid rock cell...
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (!s.isSolidRender(level, rock)) continue;
            // ...with water directly above...
            if (!level.getBlockState(rock.above()).getFluidState().is(FluidTags.WATER)) continue;
            // ...and enough connected water to matter.
            int water = countWaterPocket(level, rock.above(), minWater);
            if (water < minWater) continue;
            // ...and not right next to an existing core (avoid duplicates).
            if (coreNearby(level, rock)) continue;

            ignite(level, rock, water);
            return true;
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
            // Never follow the water into a chunk that is not loaded.
            if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
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
            for (int dz = -r; dz <= r; dz++) {
                if (!level.hasChunk((rock.getX() + dx) >> 4, (rock.getZ() + dz) >> 4)) continue;
                for (int dy = -r; dy <= r; dy++) {
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
