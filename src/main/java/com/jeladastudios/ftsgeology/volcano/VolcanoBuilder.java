package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.blockentity.VolcanoCoreBlockEntity;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.HashMap;
import java.util.Map;
import static com.jeladastudios.ftsgeology.volcano.VolcanoPlan.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoEdifice.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoSummit.*;

/**
 * Carves a whole volcano and its geothermal field.
 *
 * <p>The edifice is a height field: every column works out the height the finished mountain reaches
 * there and fills up from its own ground, so a floating block cannot be expressed. Profile, summit,
 * rock and vent layout come from {@link VolcanoType}, so the four types are different landforms, and
 * a caldera digs. Small and medium volcanoes are emitted as a {@link VolcanoJob}, a slice per tick;
 * large ones are written during world generation.</p>
 */
public final class VolcanoBuilder {

    private VolcanoBuilder() {}

    /** How far below the local ground a lava vein must stay so it can never break out of a slope. */
    static final int LAVA_SURFACE_CLEARANCE = 6;

    /** How far from its centre a large volcano's live finishing reaches: vents, chimneys and ponds. */
    static final int LARGE_LIVE_REACH = 64;

    /** How far either side of its centre a big fissure keeps level ground for its ponds. */
    static final int POND_SEGMENT = 40;

    /** Builds the volcano with its base at the given position. Returns false if the site was refused. */
    public static boolean build(ServerLevel level, BlockPos summit, int magnitude) {
        return build(level, summit, magnitude, VolcanoSize.SMALL);
    }

    /** Builds a volcano of the given size, its shape chosen by the setting. */
    public static boolean build(ServerLevel level, BlockPos summit, int magnitude, VolcanoSize size) {
        VolcanoType type = VolcanoType.forLocation(level, summit.getX(), summit.getZ(),
                magnitude, level.random);
        return build(level, summit, magnitude, type, size);
    }

    /**
     * Raises the mountain again over a core that survived an earthquake. Runs the shaping steps only,
     * never the reservoir, conduit, vents or core: a fresh core would rebuild again in a loop, and a
     * second reservoir would cut through the edifice. The height is pinned to the original mountain,
     * since the cone only adds where the ground is below target.
     *
     * @return true if the rebuild was queued
     */
    public static boolean rebuildEdifice(ServerLevel level, BlockPos base, int magnitude,
                                         VolcanoType type, int summitY, VolcanoSize size) {
        if (size == VolcanoSize.LARGE) return false;
        Ctx c = layout(level, base, magnitude, type, size);
        if (c == null) return false;

        // Pin the profile to the original mountain instead of the fresh roll layout just made.
        if (summitY > c.baseY) {
            c.summitY = summitY;
            c.coneHeight = summitY - c.baseY;
            c.coneBaseR = c.coneHeight > 0
                    ? (int) Math.round(c.craterR + c.coneHeight * c.coneSlope)
                    : c.craterR;
            if (type == VolcanoType.FISSURE) c.coneBaseR = c.fissureHalf;
        }

        VolcanoJob job = new VolcanoJob(level, "rebuild " + type + " @ " + c.x + "," + c.z);
        // No ramparts: they are added on top of whatever stands, so a second pass would stack them.
        queueEdifice(job, c, false);
        addSummit(job, c);
        addCraterClearing(job, c);
        job.add(lvl -> sealExposedLava(lvl, c));
        job.add(lvl -> verifyContainment(lvl, c));
        return VolcanoJob.enqueue(job);
    }

    public static boolean build(ServerLevel level, BlockPos base, int magnitude, VolcanoType type) {
        return build(level, base, magnitude, type, VolcanoSize.SMALL);
    }

    /**
     * Plans a volcano of an explicit shape and size and queues it for construction.
     *
     * <p>Refuses {@link VolcanoSize#LARGE}. Those are raised only while new terrain generates; see
     * {@link VolcanoField} and {@link #generateFieldChunk}.</p>
     */
    public static boolean build(ServerLevel level, BlockPos base, int magnitude, VolcanoType type,
                                VolcanoSize size) {
        if (size == VolcanoSize.LARGE) return false;
        Ctx c = layout(level, base, magnitude, type, size);
        if (c == null) return false;

        VolcanoJob job = new VolcanoJob(level, size + " " + type + " @ " + c.x + "," + c.z);

        // 1-4. Canopy, edifice, caldera and apron.
        queueEdifice(job, c, true);

        // 5. The summit: each type finishes differently.
        addSummit(job, c);
        addCraterClearing(job, c);

        // 6. Plumbing, once the vent position is known.
        addLavaDisc(job, c);
        job.add(lvl -> plantCore(lvl, c));
        job.add(lvl -> carveConduit(lvl, c));
        job.add(lvl -> growLavaBranches(lvl, c));

        // 7. Flank outlets, spaced apart and laid out in this type's own pattern.
        job.add(lvl -> chooseVents(lvl, c));
        for (int i = 0; i < c.ventCount; i++) {
            final int idx = i;
            job.add(lvl -> cutVent(lvl, c, idx));
        }
        job.add(lvl -> cutFumaroles(lvl, c));
        job.add(lvl -> recordVents(lvl, c));

        // 8. The geothermal field around it, then the safety sweep.
        job.add(lvl -> placeField(lvl, c));
        job.add(lvl -> sealExposedLava(lvl, c));
        job.add(lvl -> verifyContainment(lvl, c));

        if (!VolcanoJob.enqueue(job)) return false;
        GeysersMod.LOGGER.debug("Volcano {} (magnitude {}, cone {}) queued at {}, {}, {}",
                type, magnitude, c.coneHeight, c.x, c.baseY, c.z);
        return true;
    }

    /**
     * The shaping passes, a row of columns per step: canopy, cone, a caldera's excavation, the apron,
     * and a big fissure's ramparts. Shared by a build and a rebuild.
     */
    static void queueEdifice(VolcanoJob job, Ctx c, boolean ramparts) {
        // Strip the canopy off the whole footprint before anything is raised.
        forEachRow(job, c.clearReach, dx -> lvl -> clearSiteRow(lvl, c, dx));
        // The edifice, in rows reaching as far as the lobed foot can swing.
        if (c.coneHeight > 0) {
            forEachRow(job, coneReach(c), dx -> lvl -> buildConeRow(lvl, c, dx));
        }
        // A caldera does the opposite: it excavates its floor and throws up a ring scarp.
        if (c.type.excavates()) {
            forEachRow(job, calderaRingReach(c), dx -> lvl -> carveCalderaRow(lvl, c, dx));
        }
        // The debris apron that blends whatever we built into the countryside.
        forEachRow(job, c.apronReach, dx -> lvl -> buildApronRow(lvl, c, dx));
        if (ramparts && hasRamparts(c)) {
            int reach = c.fissureHalf + 4;
            forEachRow(job, reach, dx -> lvl -> {
                for (int dz = -reach; dz <= reach; dz++) {
                    fissureRampartColumn(lvl, c, c.x + dx, c.z + dz, false);
                }
            });
        }
    }

    /** Queues one step per row of a square footprint, so no single step is a stall. */
    static void forEachRow(VolcanoJob job, int reach,
                                   java.util.function.IntFunction<VolcanoJob.Step> rowStep) {
        for (int dx = -reach; dx <= reach; dx++) {
            job.add(rowStep.apply(dx));
        }
    }

    // === Large volcanoes ====================================================

    /** Markers whose summit is queued, with the tick it was queued at. Server thread only. */
    static final Map<Long, Long> FINISHING = new HashMap<>();
    /** A queued summit that has not replaced its marker after this long is tried again. */
    static final long FINISH_RETRY_TICKS = 6000L;

    public static void clearFinishing() {
        FINISHING.clear();
    }

    /** Plans a large volcano from its field seed. The same site gives the same mountain on any thread. */
    static Ctx fieldCtx(ServerLevel level, VolcanoField.Site site) {
        return plan(level, site.x(), site.baseY(), site.z(), site.magnitude(), site.type(),
                VolcanoSize.LARGE, RandomSource.create(site.seed()),
                TectonicMap.sampleCached(level, site.x(), site.z()));
    }

    /**
     * How far a large volcano planned from this seed would write anything, how far its mountain goes,
     * where its summit would stand and its crater radius; null if it cannot stand on this base at all.
     */
    public static int[] largeFootprint(ServerLevel level, int x, int baseY, int z, int magnitude,
                                       VolcanoType type, long seed) {
        Ctx c = plan(level, x, baseY, z, magnitude, type, VolcanoSize.LARGE, RandomSource.create(seed),
                TectonicMap.sampleCached(level, x, z));
        if (c == null) return null;
        int edifice = switch (type) {
            case CALDERA -> calderaRingReach(c);
            case FISSURE -> c.fissureHalf + 4;
            default -> coneReach(c);
        };
        return new int[] {c.clearReach, edifice, c.summitY, c.craterR};
    }

    /**
     * Writes this chunk's share of a large volcano while the chunk is generated: the cone, a caldera's
     * floor and scarp, the apron and a fissure's ramparts. Nothing outside the chunk is read or written.
     *
     * @return how many columns of the volcano fell in this chunk
     */
    public static int generateFieldChunk(WorldGenLevel level, ChunkPos cp, VolcanoField.Site site) {
        Ctx c = fieldCtx(level.getLevel(), site);
        if (c == null) return 0;
        RandomSource rng = RandomSource.create(0L);
        long reach2 = (long) (c.clearReach + 1) * (c.clearReach + 1);
        int columns = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int gx = cp.getMinBlockX() + lx, gz = cp.getMinBlockZ() + lz;
                long dx = gx - c.x, dz = gz - c.z;
                if (dx * dx + dz * dz > reach2) continue;
                // Seeded by the column alone, so its dice fall the same whichever chunk came first.
                rng.setSeed(com.jeladastudios.ftsgeology.util.SeedHash.columnSeed(site.seed(), gx, gz));
                if (c.coneHeight > 0) coneColumn(level, c, gx, gz, rng, true);
                if (c.type.excavates()) calderaColumn(level, c, gx, gz, rng, true);
                apronColumn(level, c, gx, gz, rng, true);
                if (hasRamparts(c)) fissureRampartColumn(level, c, gx, gz, true);
                columns++;
            }
        }
        if (cp.x == SectionPos.blockToSectionCoord(c.x) && cp.z == SectionPos.blockToSectionCoord(c.z)) {
            placeMarker(level, c);
        }
        return columns;
    }

    /**
     * Leaves a core flagged as unfinished in the magma chamber. The crater, conduit, vents and core
     * need a live world, so once its chunk ticks the marker queues them; see {@link #finishFieldVolcano}.
     */
    static void placeMarker(WorldGenLevel level, Ctx c) {
        net.minecraft.resources.ResourceLocation id =
                BlockEntityType.getKey(ModBlockEntities.VOLCANO_CORE.get());
        if (id == null) return;
        BlockPos p = new BlockPos(c.x, c.reservoirY, c.z);
        level.setBlock(p, ModBlocks.VOLCANO_CORE.get().defaultBlockState(), 2);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putInt("x", p.getX());
        tag.putInt("y", p.getY());
        tag.putInt("z", p.getZ());
        tag.putBoolean(VolcanoCoreBlockEntity.FIELD_PENDING, true);
        // Replaces the placeholder the region put down for the new block, so the core loads flagged.
        level.getChunk(p).setBlockEntityNbt(tag);
    }

    /**
     * Finishes a large volcano's summit from its marker: crater, core, conduit, vents and chimneys.
     * Called by the marker once a second until the chamber's lava has replaced it.
     */
    public static void finishFieldVolcano(ServerLevel level, BlockPos marker) {
        long key = marker.asLong();
        Long queued = FINISHING.get(key);
        if (queued != null && level.getGameTime() - queued < FINISH_RETRY_TICKS) return;

        VolcanoField.Site site = VolcanoField.siteAt(level, marker.getX(), marker.getZ());
        Ctx c = site == null ? null : fieldCtx(level, site);
        if (c == null || c.reservoirY != marker.getY()) {
            // Nothing in the field accounts for this core, so the field no longer agrees with the world.
            // Rock is better than a core with no mountain over it.
            GeysersMod.LOGGER.warn("Large volcano marker at {} matches no planned site; removed", marker);
            level.setBlock(marker, Blocks.BASALT.defaultBlockState(), 3);
            return;
        }
        int area = Math.max(c.liveReach, (int) Math.ceil(c.lakeOuter)) + 8;
        if (!loaded(level, c.x, c.z, area)) return;
        if (com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(level, marker)) return;

        VolcanoJob job = new VolcanoJob(level, "large " + c.type + " summit @ " + c.x + "," + c.z);
        if (c.type.excavates()) job.add(lvl -> collectCalderaLake(lvl, c));
        addSummit(job, c);
        addCraterClearing(job, c);
        addLavaDisc(job, c);
        job.add(lvl -> plantCore(lvl, c));
        job.add(lvl -> carveConduit(lvl, c));
        job.add(lvl -> growLavaBranches(lvl, c));
        job.add(lvl -> chooseVents(lvl, c));
        for (int i = 0; i < c.ventCount; i++) {
            final int idx = i;
            job.add(lvl -> cutVent(lvl, c, idx));
        }
        job.add(lvl -> cutFumaroles(lvl, c));
        job.add(lvl -> recordVents(lvl, c));
        job.add(lvl -> sealExposedLava(lvl, c));
        job.add(lvl -> verifyContainment(lvl, c));
        job.add(lvl -> FINISHING.remove(key));
        if (VolcanoJob.enqueue(job)) {
            FINISHING.put(key, level.getGameTime());
            GeysersMod.LOGGER.info("Large {} at {}, {}: finishing its summit (base Y {}, summit Y {})",
                    c.type, c.x, c.z, c.baseY, c.summitY);
        }
    }

    /** Lists a large caldera's lake for its core, since generation laid it with no live pass to record it. */
    static void collectCalderaLake(ServerLevel level, Ctx c) {
        int r = (int) Math.ceil(c.lakeR) + 1;
        int lx = (int) Math.round(c.lakeX), lz = (int) Math.round(c.lakeZ);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!inLake(c, lx + dx, lz + dz)) continue;
                BlockPos p = new BlockPos(lx + dx, c.calderaFloorY - 1, lz + dz);
                if (level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) c.molten.add(p);
            }
        }
    }

    /** True when every chunk within {@code radius} blocks is loaded. */
    static boolean loaded(ServerLevel level, int x, int z, int radius) {
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }
}
