package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.eruption.VentPathfinder;
import com.jeladastudios.ftsgeology.quake.QuakeQuiet;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.HotSpringShape;
import com.jeladastudios.ftsgeology.worldgen.HotSpringSites;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The mineral water line under a hot spring, and the spring it grows.
 *
 * <p>The line is the spring: it sits below anything a quake reaches and holds where water comes up
 * and what it carries. The pool above it is disposable; when one is destroyed the line grows
 * another.</p>
 *
 * <p>A spring grows in stages over in-game days, from one wet block to a wide basin with microbial
 * colour bands. It may remove its own deposit but never native ground, so it can widen past its old
 * rim without ever lowering the land under it.</p>
 */
public class SpringSourceBlockEntity extends BlockEntity {

    /** How often the source looks at itself, in ticks. Slow: this is geology, not machinery. */
    private static final int CHECK_INTERVAL = 40;

    /** Give up climbing after this many attempts that gain no height. */
    private static final int STALL_LIMIT = 20;

    /** Highest the conduit may climb above the ground it was built under. */
    private static final int CEILING_ALLOWANCE = 3;

    /** The last stage. 0 is a bare vent; 3 is a finished spring with its colour bands. */
    public static final int FINAL_STAGE = HotSpringShape.MAX_STAGE;

    /** Ticks in a Minecraft day. */
    private static final long DAY = 24000L;

    /** Shortest gap between two rebuilds of the same pool, in ticks. */
    private static final int REBUILD_COOLDOWN = 600;

    /** Rebuilds in a row before the spring stops trying and says why, once. */
    private static final int REBUILD_LIMIT = 8;

    /** The most one earthquake may move a spring's datum, so no run of quakes can sink it. */
    private static final int MAX_DATUM_SHIFT = 3;
    // --- state -------------------------------------------------------------

    /** The vent: where water issues. The warm bed sits one under it. */
    private int outletX;
    private int outletY = Integer.MIN_VALUE;
    private int outletZ;

    /** Top of the conduit so far. Climbs from the source towards daylight. */
    private int mouthY = Integer.MIN_VALUE;

    /** The oldest this spring may get. A spring in a terrace chain stops at stage 3 so pools do not overlap. */
    private int maxStage = FINAL_STAGE;

    /** 0 = a bare vent, up to {@link #FINAL_STAGE}. */
    private int stage;

    /** Game time the current stage started, so the next one knows when it is due. */
    private long stageSince;

    /** Consecutive climb attempts that gained no height. */
    private int stalled;

    /** Set when the outlet came out at the waterline, where no pool can be held. */
    private boolean dormant;

    /** The pool this spring last built, packed by column: what {@link HotSpringShape#health} measures. */
    private long[] poolCells = new long[0];

    /** Set once the conduit has reached daylight. A surfaced spring never re-opens its own vent. */
    private boolean surfaced;

    /** Rebuilds since the last time the pool was found intact, and when the last one happened. */
    private int rebuilds;
    private long lastRebuild = Long.MIN_VALUE;

    /** The last released quake this spring re-sited for, so the datum is re-measured once per quake. */
    private long resitedFor = Long.MIN_VALUE;

    /**
     * The original ground level at the outlet, measured once and kept. Pools are sited from this, not
     * from the ground as it is now, which after a few stages is the spring's own excavation.
     */
    private int datumY = Integer.MIN_VALUE;

    /** Wall rock the conduit came up through; decides what the water precipitates. */
    private int carbonate;
    private int volcanic;

    public SpringSourceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPRING_SOURCE.get(), pos, state);
    }

    // === Public API =========================================================

    /** Called by the generator so a fresh line knows how old a spring it is allowed to grow. */
    public void setMaxStage(int s) {
        this.maxStage = Math.max(1, Math.min(FINAL_STAGE, s));
        setChanged();
    }

    /** The oldest this spring may get. */
    public int maxStage() {
        return maxStage;
    }

    /**
     * Runs the spring straight to maturity, for world generation: a new world should have old springs
     * in it. Same code path as slow growth.
     *
     * @return true if a spring was built here
     */
    public boolean growToMaturity(ServerLevel level) {
        return growTo(level, FINAL_STAGE);
    }

    /** Runs the spring up to a given age at once, stage by stage, exactly as slow growth does. */
    public boolean growTo(ServerLevel level, int target) {
        if (outletY == Integer.MIN_VALUE) return false;
        int reached = 0;
        for (int s = 1; s <= Math.max(1, Math.min(maxStage, target)); s++) {
            // Takes the canopy at each stage, since each stage is wider than the last.
            if (!applyStage(level, s, true)) break;
            reached = s;
        }
        if (reached == 0) return false;
        stage = reached;
        stageSince = level.getGameTime();
        // Water stands in daylight. Generation never goes through openVent, so the flag is set here.
        surfaced = true;
        setChanged();
        return true;
    }

    /** Where this line's vent is, or null if it has not reached the surface yet. */
    public BlockPos vent() {
        return outletY == Integer.MIN_VALUE ? null : new BlockPos(outletX, outletY, outletZ);
    }

    /** Records the vent the generator has just opened. */
    public void setVent(BlockPos vent) {
        this.outletX = vent.getX();
        this.outletY = vent.getY();
        this.outletZ = vent.getZ();
        this.mouthY = vent.getY();
        setChanged();
    }

    // === Ticking ============================================================

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SpringSourceBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (!GeyserConfig.SPRING_RENEWAL_ENABLED.get()) return;
        if ((server.getGameTime() + pos.hashCode()) % CHECK_INTERVAL != 0) return;
        // Never rebuild into ground a quake is still moving.
        if (QuakeQuiet.isQuiet(server, be.siteX(), be.siteZ())) return;

        // Before the dormancy check: a quake is what makes a dormant spring worth another try, since
        // the ground it failed on has changed.
        if (be.resiteAfterQuake(server)) return;

        if (be.dormant) return;
        if (be.adoptOldSave(server)) return;

        // A pool somebody has thrown a few blocks into is cleaned out and rebuilt at the age it had
        // reached. Only a pool that is mostly buried counts as a blocked outlet.
        if (be.datumY != Integer.MIN_VALUE && be.stage > 0) {
            HotSpringShape.Health h = be.poolHealth(server);
            if (h == HotSpringShape.Health.FINE) {
                be.rebuilds = 0;                     // intact: the run of rebuilds is over
            } else if (h == HotSpringShape.Health.FOULED) {
                if (be.rebuildBarred(server)) return;
                be.applyStage(server, be.stage);
                be.noteRebuild(server);
                GeysersMod.LOGGER.debug("Spring at {},{} flushed its pool", be.outletX, be.outletZ);
                return;
            }
        }

        if (!be.ventOpen(server)) {
            // The pool is gone. A spring that has reached daylight does not go back to climbing, or it
            // would rebuild from stage 1 over and over; it clears its own outlet instead.
            if (be.surfaced) {
                // Clear the outlet by rebuilding at the same stage. Moving a spring elsewhere is not
                // implemented, so this is what a blocked spring does.
                be.stalled++;
                be.setChanged();
                if (be.rebuildBarred(server)) return;
                if (be.applyStage(server, be.stage)) {
                    be.stalled = 0;
                    be.noteRebuild(server);
                    GeysersMod.LOGGER.debug("Spring at {},{} dug itself out",
                            be.siteX(), be.siteZ());
                } else if (be.stalled >= STALL_LIMIT) {
                    // Its pool will not go back here and nothing is changing: it rests until a quake moves the ground.
                    be.dormant = true;
                    GeysersMod.LOGGER.info("Spring at {},{} cannot hold a pool here; dormant until the ground moves",
                            be.siteX(), be.siteZ());
                }
                return;
            }

            if (be.stage != 0) {
                be.stage = 0;
                be.stageSince = server.getGameTime();
                be.setChanged();
            }
            // A source that cannot reach daylight stops trying; a quake overhead gives it another go.
            if (be.stalled >= STALL_LIMIT) return;
            be.climb(server, pos);
            return;
        }

        // Its own cap: a spring in a terrace chain would grow into its neighbour past it.
        if (be.stage >= be.maxStage) return;                 // finished; nothing left to do
        if (server.getGameTime() - be.stageSince < be.stageLength()) return;

        // Growth takes the canopy, since the pool widens; a same-stage flush does not.
        if (be.applyStage(server, be.stage + 1, true)) {
            be.stage++;
            be.stageSince = server.getGameTime();
            GeysersMod.LOGGER.info("Spring at {} reached stage {}/{} ({} blocks across)",
                    // Its own ceiling, not the global one.
                    be.vent(), be.stage, be.maxStage, HotSpringShape.radiusFor(be.stage) * 2 + 1);
            be.setChanged();
        }
    }

    /** How long the current stage lasts, in ticks. */
    private long stageLength() {
        double days = switch (stage) {
            case 0, 1 -> GeyserConfig.SPRING_STAGE_ONE_DAYS.get();
            case 2 -> GeyserConfig.SPRING_STAGE_TWO_DAYS.get();
            default -> GeyserConfig.SPRING_STAGE_THREE_DAYS.get();
        };
        return Math.max(20L, (long) (days * DAY));
    }

    /**
     * Is there still water at the vent? Asked of the recorded pool, or of the whole basin before one is
     * recorded; never of a single column, which one frozen block would fool.
     */
    private boolean ventOpen(ServerLevel level) {
        if (outletY == Integer.MIN_VALUE) return false;
        if (datumY == Integer.MIN_VALUE || poolCells.length == 0) return anyWaterInBasin(level);
        return poolHealth(level) != HotSpringShape.Health.BLOCKED;
    }

    /** Is there any water at all in this spring's basin? The coarse fallback test. */
    private boolean anyWaterInBasin(ServerLevel level) {
        int waterY = datumY != Integer.MIN_VALUE ? datumY - 1 : outletY;
        int radius = HotSpringShape.radiusFor(Math.max(1, stage));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos p = new BlockPos(outletX + dx, waterY, outletZ + dz);
                if (!level.getBlockState(p).getFluidState().isEmpty()) return true;
            }
        }
        return false;
    }

    /**
     * Re-sites the spring on the ground a quake left, once per quake.
     *
     * <p>This re-measures the datum, which is otherwise never done because a spring re-measuring its
     * own basin walks downhill. Guards: only when a quake zone covered this column, once per zone,
     * only after the zone has released, and at most {@link #MAX_DATUM_SHIFT} blocks per quake.</p>
     *
     * @return true if this tick was spent re-siting
     */
    private boolean resiteAfterQuake(ServerLevel level) {
        long quake = QuakeQuiet.released(level, siteX(), siteZ());
        if (quake == 0L) return false;                     // no finished quake over this ground
        if (quake <= resitedFor) return false;             // already answered for this one

        if (!surfaced || stage <= 0) {                     // never had a pool; let it climb
            resitedFor = quake;
            // A line that gave up surfacing gets another try on the moved ground.
            dormant = false;
            stalled = 0;
            setChanged();
            return false;
        }
        if (datumY == Integer.MIN_VALUE) {
            resitedFor = quake;
            setChanged();
            return false;
        }

        // Read the ground before touching anything: nothing is drained or stamped until a pool can go
        // back in.
        int oldWater = datumY - 1;
        int ring = HotSpringShape.waterLineAt(level, siteX(), siteZ(), stage);
        if (ring == Integer.MIN_VALUE) return false;       // cannot read the ground; try again later

        // The ground moved, so a spring that had given up is worth another try.
        dormant = false;
        rebuilds = 0;
        lastRebuild = Long.MIN_VALUE;
        // Clear the old pool's water first, so the rebuilt spring does not stand in its spill.
        drainPool(level);
        // resitedFor is stamped only once a pool is back in, below.

        // Has the ground moved? Measured on the ring outside the pool, never on the basin, which is the
        // spring's own excavation.
        if (Math.abs(ring - oldWater) <= 1) {
            // It has not: rebuild exactly where the spring was.
            if (!applyStage(level, stage)) return recoverPool(level, quake, ring);
            resitedFor = quake;
            GeysersMod.LOGGER.info("Spring at {},{} rebuilt after a quake, same level",
                    siteX(), siteZ());
            setChanged();
            return true;
        }

        // It has: follow it, capped per quake so no run of quakes can sink the spring.
        int wanted = ring + 1;
        int capped = Mth.clamp(wanted, datumY - MAX_DATUM_SHIFT, datumY + MAX_DATUM_SHIFT);
        int moved = capped - datumY;
        datumY = capped;
        if (!applyStage(level, stage)) {
            datumY = capped - moved;                        // put the datum back; nothing was built
            return recoverPool(level, quake, ring);
        }
        resitedFor = quake;
        GeysersMod.LOGGER.info("Spring at {},{} re-sited after a quake: ground moved {} blocks",
                siteX(), siteZ(), moved);
        setChanged();
        return true;
    }

    /**
     * For a pool that will not go back where it was because the quake broke the floor under it rather than
     * the ground around it. Its own basin is put back first, then the level the ground was left at is tried;
     * if neither holds a pool the spring gives up for this quake instead of trying again every check.
     *
     * @return true, since the check was spent either way
     */
    private boolean recoverPool(ServerLevel level, long quake, int ring) {
        resitedFor = quake;
        if (HotSpringShape.restoreBasin(level, poolCells, datumY - 1) && applyStage(level, stage)) {
            GeysersMod.LOGGER.info("Spring at {},{} restored its basin after a quake", siteX(), siteZ());
            setChanged();
            return true;
        }
        int old = datumY;
        datumY = ring + 1;
        if (datumY != old && applyStage(level, stage)) {
            GeysersMod.LOGGER.info("Spring at {},{} followed the ground after a quake: {} blocks",
                    siteX(), siteZ(), datumY - old);
            setChanged();
            return true;
        }
        datumY = old;
        dormant = true;
        GeysersMod.LOGGER.info("Spring at {},{} could not hold a pool after a quake; dormant until the ground moves again",
                siteX(), siteZ());
        setChanged();
        return true;
    }

    /**
     * Takes the water out of the pool this spring last built, and nothing else: only its own columns,
     * and only within {@link #MAX_DATUM_SHIFT} of the old water line, so a neighbouring river or a
     * lower terrace is never drained.
     */
    private void drainPool(ServerLevel level) {
        if (poolCells.length == 0 || datumY == Integer.MIN_VALUE) return;
        int waterY = datumY - 1;
        int lo = waterY - MAX_DATUM_SHIFT;
        int hi = waterY + MAX_DATUM_SHIFT;
        int cleared = 0;
        for (long c : poolCells) {
            int cx = HotSpringShape.unpackX(c), cz = HotSpringShape.unpackZ(c);
            for (int y = lo; y <= hi; y++) {
                BlockPos p = new BlockPos(cx, y, cz);
                if (level.getBlockState(p).getFluidState().isEmpty()) continue;
                if (!ourWater(level, p)) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
                cleared++;
            }
        }
        if (cleared > 0) {
            GeysersMod.LOGGER.debug("Spring at {},{} drained {} cells before rebuilding",
                    siteX(), siteZ(), cleared);
        }
    }

    /** Water on the spring's own deposit, or hanging over air, is the spring's; water on sand or soil is a lake's. */
    private boolean ourWater(ServerLevel level, BlockPos p) {
        BlockState below = level.getBlockState(p.below());
        if (below.isAir()) return true;                     // hanging: the quake left it there
        if (below.is(Blocks.CALCITE) || below.is(ModBlocks.SINTER.get())
                || below.is(ModBlocks.HOT_SPRING.get())) return true;
        return HotSpringShape.isMatBlock(below);
    }

    /**
     * Rebuilds, once, a spring saved before pool cells were recorded. Without the cell list its health
     * always reads BLOCKED and it could never recover.
     *
     * @return true if this tick was spent on the migration
     */
    private boolean adoptOldSave(ServerLevel level) {
        if (poolCells.length > 0) return false;
        if (!surfaced || stage <= 0 || datumY == Integer.MIN_VALUE) return false;
        if (!applyStage(level, stage)) {
            // Nothing can be built here now; leave it to the blocked-outlet path.
            surfaced = false;
            setChanged();
            return false;
        }
        GeysersMod.LOGGER.debug("Spring at {},{} adopted an older save ({} cells)",
                outletX, outletZ, poolCells.length);
        return true;
    }

    /** The spring's column: the outlet once it has surfaced, the block entity's own column before that. */
    private int siteX() {
        return outletY == Integer.MIN_VALUE ? worldPosition.getX() : outletX;
    }

    private int siteZ() {
        return outletY == Integer.MIN_VALUE ? worldPosition.getZ() : outletZ;
    }

    /** The state of the pool this spring last built. */
    private HotSpringShape.Health poolHealth(ServerLevel level) {
        if (datumY == Integer.MIN_VALUE || poolCells.length == 0) {
            return HotSpringShape.Health.BLOCKED;
        }
        return HotSpringShape.health(level, poolCells, datumY - 1);
    }

    /**
     * Is this spring rebuilding too often? At most one rebuild per {@link #REBUILD_COOLDOWN}; after
     * {@link #REBUILD_LIMIT} in a row it goes dormant and says so once.
     */
    private boolean rebuildBarred(ServerLevel level) {
        long now = level.getGameTime();
        if (lastRebuild != Long.MIN_VALUE && now - lastRebuild < REBUILD_COOLDOWN) return true;
        if (rebuilds >= REBUILD_LIMIT) {
            if (!dormant) {
                dormant = true;
                setChanged();
                GeysersMod.LOGGER.info(
                        "Spring at {},{} could not hold its pool after {} rebuilds; dormant",
                        outletX, outletZ, rebuilds);
            }
            return true;
        }
        return false;
    }

    private void noteRebuild(ServerLevel level) {
        lastRebuild = level.getGameTime();
        rebuilds++;
        setChanged();
    }

    // === Climbing ===========================================================

    /** One step of the climb: bore towards the real ground, not the canopy, and open the vent on arrival. */
    private void climb(ServerLevel level, BlockPos pos) {
        int probed = TerrainProbe.groundY(level, pos.getX(), pos.getZ());
        int ground = probed != Integer.MIN_VALUE
                ? probed
                : level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        int ceiling = ground + CEILING_ALLOWANCE;
        if (mouthY == Integer.MIN_VALUE) mouthY = pos.getY() + 1;

        // A quake can drop the ground under the mouth; keep the mouth under the new ceiling.
        mouthY = Math.min(mouthY, ceiling);
        int before = mouthY;

        // No pressure is passed, so VentPathfinder will clear natural rubble and refuse anything
        // player-built outright. The build safety is inherited rather than written again.
        BlockPos mouth = VentPathfinder.trace(level, pos, mouthY, ceiling, 0.0, true);
        mouthY = mouth.getY();

        if (mouthY > before) {
            stalled = 0;
            lineConduit(level, pos, before, mouthY);
            setChanged();
        }
        if (mouthY >= ground) {
            openVent(level, pos, ground);
            return;
        }
        if (mouthY <= before) stalled++;
    }

    /**
     * Lines the newly bored section and notes the rock it passed through, which decides the deposit:
     * carbonate rock gives travertine (Pamukkale), volcanic rock gives silica sinter (Yellowstone).
     */
    private void lineConduit(ServerLevel level, BlockPos pos, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos p = new BlockPos(pos.getX(), y, pos.getZ()).relative(d);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                if (s.is(Blocks.BEDROCK) || s.is(Blocks.CALCITE) || s.is(ModBlocks.SINTER.get())) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                noteRock(s);
                if (level.random.nextInt(3) == 0) continue;      // patchy, not a tiled pipe
                level.setBlock(p, deposit(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    private void noteRock(BlockState s) {
        if (s.is(Blocks.CALCITE) || s.is(Blocks.TUFF) || s.is(Blocks.DRIPSTONE_BLOCK)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.SMOOTH_SANDSTONE)) {
            carbonate++;
        } else if (s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BLACKSTONE)
                || s.is(Blocks.OBSIDIAN) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.GRANITE)
                || s.is(Blocks.DIORITE) || s.is(Blocks.ANDESITE)) {
            volcanic++;
        }
    }

    /** What this spring precipitates, from the rock its water came up through. */
    private BlockState deposit() {
        return carbonate >= volcanic
                ? Blocks.CALCITE.defaultBlockState()
                : ModBlocks.SINTER.get().defaultBlockState();
    }

    /** The conduit has reached daylight: open a single wet block and start the clock. */
    private void openVent(ServerLevel level, BlockPos pos, int ground) {
        // A spring surfacing at the waterline is a submarine spring. They are real, we do not model
        // them, and a basin there would drain into the sea. It sleeps rather than failing forever.
        if (ground - 1 <= level.getSeaLevel() + 1) {
            dormant = true;
            GeysersMod.LOGGER.info("Spring line at {} surfaced at Y {}, at the waterline; dormant",
                    pos, ground);
            setChanged();
            return;
        }

        BlockPos vent = new BlockPos(pos.getX(), ground, pos.getZ());
        if (EruptionHandler.isPlayerPlaced(level.getBlockState(vent))
                || EruptionHandler.isPlayerPlaced(level.getBlockState(vent.below()))) {
            stalled++;
            setChanged();
            return;
        }

        // Retire any older bed in this column, or the new one sits on it and the buried bed reads dry.
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos p = vent.below(dy);
            if (level.getBlockState(p).is(ModBlocks.HOT_SPRING.get())) {
                level.setBlock(p, deposit(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            }
        }

        setVent(vent);
        // Water in daylight is a spring at once, a small one. The canopy comes off only here and on
        // growth.
        if (!applyStage(level, 1, true)) {
            // Counted across tries, or a spring that surfaces where no pool fits climbs and fails for ever.
            stalled++;
            if (stalled >= STALL_LIMIT) {
                dormant = true;
                GeysersMod.LOGGER.info("Spring line at {} cannot open a pool at Y {}; dormant until the ground moves",
                        pos, ground);
            }
            setChanged();
            return;
        }
        stalled = 0;
        stage = 1;
        surfaced = true;
        rebuilds = 0;
        stageSince = level.getGameTime();
        GeysersMod.LOGGER.info("Spring line at {} broke surface at Y {}, carrying {}",
                pos, ground, deposit().getBlock().getName().getString());
        setChanged();
    }

    // === Growth =============================================================

    /** Builds the spring at a stage. All shaping lives in {@link HotSpringShape}; this picks the arguments. */
    private boolean applyStage(ServerLevel level, int toStage) {
        return applyStage(level, toStage, false);
    }

    private boolean applyStage(ServerLevel level, int toStage, boolean clearTrees) {
        if (datumY == Integer.MIN_VALUE) {
            datumY = HotSpringShape.datumFor(level, outletX, outletZ);
            if (datumY == Integer.MIN_VALUE) return false;
        }
        List<BlockPos> pool =
                HotSpringShape.build(level, outletX, outletZ, toStage, datumY, clearTrees);
        if (pool.isEmpty()) return false;
        outletY = pool.get(0).getY();
        // Remembered so health() measures the pool that exists.
        poolCells = HotSpringShape.pack(pool);
        setChanged();
        return true;
    }

    // === Persistence ========================================================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("OutletX", outletX);
        tag.putInt("OutletY", outletY);
        tag.putInt("OutletZ", outletZ);
        tag.putInt("MouthY", mouthY);
        tag.putInt("MaxStage", maxStage);
        tag.putInt("Stage", stage);
        tag.putLong("StageSince", stageSince);
        tag.putInt("Stalled", stalled);
        tag.putBoolean("Dormant", dormant);
        tag.putInt("DatumY", datumY);
        tag.putInt("Carbonate", carbonate);
        tag.putInt("Volcanic", volcanic);
        tag.putLongArray("PoolCells", poolCells);
        tag.putBoolean("Surfaced", surfaced);
        tag.putInt("Rebuilds", rebuilds);
        tag.putLong("LastRebuild", lastRebuild);
        tag.putLong("ResitedFor", resitedFor);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        outletX = tag.getInt("OutletX");
        outletZ = tag.getInt("OutletZ");
        outletY = tag.contains("OutletY") ? tag.getInt("OutletY") : Integer.MIN_VALUE;
        mouthY = tag.contains("MouthY") ? tag.getInt("MouthY") : Integer.MIN_VALUE;
        maxStage = tag.contains("MaxStage")
                ? Math.max(1, Math.min(FINAL_STAGE, tag.getInt("MaxStage")))
                : FINAL_STAGE;   // older saves grew without a cap
        stage = tag.getInt("Stage");
        stageSince = tag.getLong("StageSince");
        stalled = tag.getInt("Stalled");
        dormant = tag.getBoolean("Dormant");
        datumY = tag.contains("DatumY") ? tag.getInt("DatumY") : Integer.MIN_VALUE;
        carbonate = tag.getInt("Carbonate");
        volcanic = tag.getInt("Volcanic");
        poolCells = tag.getLongArray("PoolCells");
        // Older saves: an assigned outlet means the conduit reached daylight, whatever the stage says.
        surfaced = tag.contains("Surfaced")
                ? tag.getBoolean("Surfaced")
                : outletY != Integer.MIN_VALUE;
        rebuilds = tag.getInt("Rebuilds");
        lastRebuild = tag.contains("LastRebuild") ? tag.getLong("LastRebuild") : Long.MIN_VALUE;
        resitedFor = tag.contains("ResitedFor") ? tag.getLong("ResitedFor") : Long.MIN_VALUE;
    }
}
