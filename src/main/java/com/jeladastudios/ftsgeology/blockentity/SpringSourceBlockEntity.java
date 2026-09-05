package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.eruption.VentPathfinder;
import com.jeladastudios.ftsgeology.quake.QuakeQuiet;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.HotSpringShape;
import com.jeladastudios.ftsgeology.worldgen.RetrogenHandler;
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
 * <h2>The line, not the pool, is the spring</h2>
 * A pool is surface furniture: an earthquake large enough to move the ground takes it away and there
 * is nothing left to recover. So the thing that persists sits well below the depth a quake reaches
 * ({@code MAX_CAPTURE_DEPTH} is 24; this is seated at 28) and holds the only facts that matter -
 * that water wants to come up here, and what it is carrying. The pool above it is disposable, and is
 * meant to be: when a quake destroys one, nothing tries to save it. The line simply grows another.
 *
 * <h2>Growth happens in stages, over days</h2>
 * A spring does not appear finished. It opens as a single wet block, and over in-game days it puts
 * down carbonate, dams itself a wider basin, breaks out past its own old rim, and only then carries
 * the microbial colours that need a large stable warm pool to exist at all. Each stage is a step
 * outward and a step up, which is exactly how a travertine terrace grows.
 *
 * <h2>Two rules that keep it out of trouble</h2>
 * <ol>
 *   <li><b>It may remove its own deposit, never native ground.</b> That is what lets a spring break
 *       out past the rim it built at an earlier stage, while making it structurally impossible for
 *       the ground under a spring to fall. An earlier version cut its pool out of the terrain, and
 *       because cutting lowers the ground and the next pool was sited from the ground, a source on
 *       a timer walked its own pool 49 blocks downhill.</li>
 *   <li><b>The pool is one connected region, worked out before anything is placed.</b> The version
 *       before this tested each cell on its own and skipped the ones that failed, permanently - so a
 *       spring whose pool had been filled in came back as a scatter of separate water holes in a
 *       field of calcite rather than a pool.</li>
 * </ol>
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

    /**
     * The most a single earthquake may move a spring's reference level, in blocks.
     *
     * <p>Generous enough that a serious quake visibly lowers a pool, tight enough that no run of
     * quakes can walk a spring into the ground the way repeated re-measuring did before.</p>
     */
    private static final int MAX_DATUM_SHIFT = 3;




    // --- state -------------------------------------------------------------

    /** The vent: where water issues. The warm bed sits one under it. */
    private int outletX;
    private int outletY = Integer.MIN_VALUE;
    private int outletZ;

    /** Top of the conduit so far. Climbs from the source towards daylight. */
    private int mouthY = Integer.MIN_VALUE;

    /**
     * The oldest this spring is allowed to get, and therefore how wide it ends up.
     *
     * <h2>What this replaced</h2>
     * There was a {@code targetRadius} here that was written, saved to NBT, loaded back - and never
     * read by anything. Pool size comes from {@link HotSpringShape#radiusFor} alone. That mattered
     * because {@code RetrogenHandler.placeHotSpringAt} sized the gap between the pools of a terrace
     * chain from the radius it passed to that dead setter: a stride of 6 to 20 blocks between pools
     * that all grew to stage 4, which is 21 blocks across. Every pair in every chain overlapped, and
     * since each pool sits a block lower than the one above it and each build clears the three
     * blocks over its own cells, neighbouring springs deleted each other's water for ever. That is
     * the water appearing and vanishing on a loop with the spring itself never changing.
     *
     * <p>A cap the growth loop actually reads is what that field should always have been. Springs in
     * a chain stop at stage 3, so the terraces stay close together and stepped, which is what
     * a travertine terrace looks like; a spring alone on the flat still grows to stage 4.</p>
     */
    private int maxStage = FINAL_STAGE;

    /** 0 = a bare vent, up to {@link #FINAL_STAGE}. */
    private int stage;

    /** Game time the current stage started, so the next one knows when it is due. */
    private long stageSince;

    /** Consecutive climb attempts that gained no height. */
    private int stalled;

    /** Set when the outlet came out at the waterline, where no pool can be held. */
    private boolean dormant;

    /**
     * The pool this spring last built, packed by column.
     *
     * <p>Kept because it is the only honest denominator for {@link HotSpringShape#health}. Asking
     * how wet a disc of {@code radiusFor(stage)} is counts the pool's own rim against it, and a
     * healthy spring then never scores well enough to be left alone.</p>
     */
    private long[] poolCells = new long[0];

    /** Set once the conduit has reached daylight. A surfaced spring never re-opens its own vent. */
    private boolean surfaced;

    /** Rebuilds since the last time the pool was found intact, and when the last one happened. */
    private int rebuilds;
    private long lastRebuild = Long.MIN_VALUE;

    /**
     * Release time of the last quake zone this spring has already re-sited for.
     *
     * <p>The one-shot stamp that stops {@link #resiteAfterQuake} from re-measuring the datum more
     * than once per earthquake. Without it that method would run on every check and become the
     * downhill ratchet it is carefully written not to be.</p>
     */
    private long resitedFor = Long.MIN_VALUE;

    /**
     * The original ground level at the outlet, measured once and then kept.
     *
     * <p>Every pool this line ever builds is sited from this, not from a reading of the ground as
     * it is now. Re-measuring is what made a covered spring rebuild lower each time until it had
     * sunk into a pit: after stage 4 the land within its own basin IS its own excavated floor, and
     * a stage 1 rebuild samples exactly there.</p>
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
     * Opens the vent and runs it straight to a finished spring, with no waiting.
     *
     * <p>Used at world generation. A brand new world should look like a world with old springs in
     * it, not one where every spring is a day old - the staging is there to show a spring recovering,
     * not to make a new player wait a week for the scenery. Everything below is the same code the
     * slow path uses, so the two can never drift apart.</p>
     *
     * @return true if a spring was built here
     */
    public boolean growToMaturity(ServerLevel level) {
        return growTo(level, FINAL_STAGE);
    }

    /**
     * Runs the spring up to a given age at once.
     *
     * <p>The stages are run in sequence rather than jumping straight to the target, because that is
     * what a growing spring does and the two must not be different code paths. Building only the
     * final stage on untouched ground is what hid the bug where a sequence could not widen the pool
     * at all - it looked perfect from a command and was broken everywhere else.</p>
     */
    public boolean growTo(ServerLevel level, int target) {
        if (outletY == Integer.MIN_VALUE) return false;
        int reached = 0;
        for (int s = 1; s <= Math.max(1, Math.min(maxStage, target)); s++) {
            // Clears the canopy at every step, and this is the path world generation takes.
            //
            // It passed false, and openVent - the only caller that passed true - is never reached
            // from generation at all: openSpring seats the source and calls straight into here. So
            // a naturally generated spring cleared no trees whatsoever, at any stage, and the tree
            // standing over a pool in testing was simply never cut. The canopy is taken at each
            // step rather than once at the end because each stage is wider than the last.
            if (!applyStage(level, s, true)) break;
            reached = s;
        }
        if (reached == 0) return false;
        stage = reached;
        stageSince = level.getGameTime();
        // Water is standing in daylight here, which is the whole meaning of the flag.
        //
        // Only openVent set it, and generation never reaches openVent - a fact written three lines
        // above this one, in a comment I put there while fixing the canopy, without noticing it
        // applied to surfaced as well. So every naturally generated spring in every world was
        // flagged as never having surfaced. The consequence is not cosmetic: when such a pool is
        // obstructed, serverTick skips the surfaced branch and takes the old path instead -
        // stage = 0, then climb() bores up through the pool's own floor, then openVent rebuilds it
        // as a stage 1 puddle. That is the demotion this class was rewritten to stop, still live
        // for every spring the world made rather than the ones a quake made.
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
        // The ground here is still moving, or still shedding what the quake shook loose. Rebuilding
        // into it produces the field of wreckage testing found after a quake: a spring that spent
        // the whole event repairing a pool that was about to be torn up again.
        if (QuakeQuiet.isQuiet(server, be.siteX(), be.siteZ())) return;

        // Before the dormancy check, deliberately. An earthquake is the one thing that can make a
        // spring which had given up worth trying again: the ground it could not hold a pool on is
        // not the ground that is there now. Dormancy was set in two places and cleared in none, so
        // without this a single bad patch put a spring to sleep for the life of the world.
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
            // The pool is gone - a quake, a landslide, a player filling it in.
            //
            // A spring that has already reached daylight does NOT go back to climbing. It used to,
            // and since climb() re-opens the vent unconditionally the moment the conduit stands at
            // or above the ground - which it does forever, once it has surfaced - every failed
            // check rebuilt the spring from stage 1. That is the 2-second cycle testing saw, the
            // 450 "broke surface" lines from six sources, and the stage 1 puddle sitting in the
            // middle of its own stage 4 basin.
            //
            // What a blocked spring actually does is find the next weakness a few metres to one
            // side. So it stalls towards that instead.
            if (be.surfaced) {
                // Counted BEFORE the rebuild barrier, not after.
                //
                // It was after, and that quietly killed the escape route this branch exists for:
                // rebuildBarred goes true once the spring has given up, so stalled stopped rising
                // the moment it mattered, never reached STALL_LIMIT, and stepAside - a silted vent
                // finding a new way out, the whole physical idea - has never once run.
                // Relocating is off: see stepAsideDisabled. A blocked spring stays put and keeps
                // trying to clear its own outlet, which is what it did before this branch existed.
                be.stalled++;
                be.setChanged();
                if (be.rebuildBarred(server)) return;
                return;
            }

            if (be.stage != 0) {
                be.stage = 0;
                be.stageSince = server.getGameTime();
                be.setChanged();
            }
            // A source that cannot find daylight stops hammering at it. Relocating is off (see
            // stepAsideDisabled), so this is simply a cap on the work: it keeps its place and its
            // record, and an earthquake overhead is what gives it another go.
            if (be.stalled >= STALL_LIMIT) return;
            be.climb(server, pos);
            return;
        }

        // Its own cap, not the global one: a spring in a terrace chain is spaced for a stage 2 pool
        // and grows into its neighbour if it is allowed past that.
        if (be.stage >= be.maxStage) return;                 // finished; nothing left to do
        if (server.getGameTime() - be.stageSince < be.stageLength()) return;

        // A wider stage needs a wider clearing, so growth takes the canopy; the same-stage flush
        // above does not. Growth happens three times in a spring's life rather than every check,
        // which is what keeps this from becoming the ring of dead trunks again.
        if (be.applyStage(server, be.stage + 1, true)) {
            be.stage++;
            be.stageSince = server.getGameTime();
            GeysersMod.LOGGER.info("Spring at {} reached stage {}/{} ({} blocks across)",
                    // Its own ceiling, not the global one. A chained spring caps at stage 2 and
                    // reporting "2/4" read as unfinished when it was in fact done.
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
     * Is there still water at the vent?
     *
     * <p>Asked of the whole basin. Testing one column meant filling in any part of a pool except the
     * block over the bed did nothing at all, which is exactly what testing reported.</p>
     *
     * <h2>The fallback has to look wider than one block</h2>
     * Before a pool has been recorded there is nothing to measure, so this falls back to the vent
     * column. One block is far too brittle a thing to hang {@link #stepAside} on: freeze it, or set
     * a single block on it, and a spring whose basin is otherwise perfect abandons the lot, kills
     * its mats and drills somewhere else. So the fallback sweeps the stage's own radius and asks
     * whether there is any water left in the basin at all.
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
     * <h2>This deliberately re-measures the datum, which is the dangerous thing to do</h2>
     * {@link #datumY} is normally measured once and kept forever, and that rule exists because
     * re-measuring is precisely what buried springs three separate times: a rebuild samples the
     * basin the spring itself dug, sites the next pool lower, and the spring walks into the ground.
     * The measured drift at its worst was 49 blocks.
     *
     * <p>A quake is the one case where the datum is genuinely stale rather than self-inflicted. The
     * land really did move, the old figure describes ground that no longer exists, and keeping it
     * would leave the pool hanging in the air or buried - which is the wreckage testing reported.
     * The deep line is untouched under all of it, so there is something to rebuild from.</p>
     *
     * <h2>The three guards that keep it from ratcheting</h2>
     * <ol>
     *   <li>Only when a quake zone actually covered this column - not on a timer, not on a failed
     *       health check, not because a player filled the pool in.</li>
     *   <li>Once per zone. The release time is stamped on the block entity, so a second tick after
     *       the same quake does nothing.</li>
     *   <li>Only after the zone has released, which means the debris has already landed.</li>
     * </ol>
     * Together those make the number of re-measurements equal to the number of earthquakes over this
     * spring, rather than a function of how long the chunk stays loaded.
     *
     * @return true if this tick was spent re-siting
     */
    private boolean resiteAfterQuake(ServerLevel level) {
        long quake = QuakeQuiet.released(level, siteX(), siteZ());
        if (quake == 0L) return false;                     // no finished quake over this ground
        if (quake <= resitedFor) return false;             // already answered for this one

        if (!surfaced || stage <= 0) {                     // never had a pool; let it climb
            resitedFor = quake;
            setChanged();
            return false;
        }
        if (datumY == Integer.MIN_VALUE) {
            resitedFor = quake;
            setChanged();
            return false;
        }

        // Read the ground BEFORE touching the pool.
        //
        // This used to stamp resitedFor, then drain the pool, and only then read the ring - so if
        // the ring could not be read (an unloaded chunk at radius 16, a cliff) the method returned
        // with the water deleted, no pool rebuilt, and the quake already marked as answered. The
        // spring sat in a dry basin for the rest of the world's life. Nothing is stamped and
        // nothing is removed until there is a pool to put back.
        int oldWater = datumY - 1;
        int ring = HotSpringShape.waterLineAt(level, siteX(), siteZ(), stage);
        if (ring == Integer.MIN_VALUE) return false;       // cannot read the ground; try again later

        resitedFor = quake;
        // The ground moved, so a spring that had given up is worth another try.
        dormant = false;
        rebuilds = 0;
        lastRebuild = Long.MIN_VALUE;
        // Anything left of the old pool goes before the new one is built, so a rebuilt spring does
        // not stand in a ring of the water its predecessor spilled.
        drainPool(level);

        // Has the ground the pool sat on actually moved? Testing the RING, not the basin.
        //
        // The basin is the spring's own excavation, so measuring against that is the downhill
        // ratchet this project has had three times over. The ring is outside anything the pool can
        // reach (see HotSpringShape.waterLine), so it is the one honest witness to what the quake
        // did to this place.
        if (Math.abs(ring - oldWater) <= 1) {
            // It did not. Keep the datum and rebuild exactly where the spring already was - which
            // is what testing asked for: a spring whose ground is intact has no business sinking.
            if (!applyStage(level, stage)) {
                surfaced = false;
                setChanged();
                return false;
            }
            GeysersMod.LOGGER.info("Spring at {},{} rebuilt after a quake, same level",
                    siteX(), siteZ());
            setChanged();
            return true;
        }

        // It did move. Follow it - but only so far.
        //
        // The cap is the whole safety margin. A quake dropping the ground two or three blocks is a
        // real thing that should be visible; a spring walking 49 blocks into the earth over a run of
        // rebuilds is the ratchet. Limiting the shift per quake makes the first possible and the
        // second arithmetically impossible, however many quakes pass over it.
        int wanted = ring + 1;
        int capped = Mth.clamp(wanted, datumY - MAX_DATUM_SHIFT, datumY + MAX_DATUM_SHIFT);
        int moved = capped - datumY;
        datumY = capped;
        if (!applyStage(level, stage)) {
            surfaced = false;                               // no pool fits here now; go looking
            setChanged();
            return false;
        }
        GeysersMod.LOGGER.info("Spring at {},{} re-sited after a quake: ground moved {} blocks",
                siteX(), siteZ(), moved);
        setChanged();
        return true;
    }

    /** Takes the water out of the pool this spring last built, and nothing else. */
    private void drainPool(ServerLevel level) {
        if (poolCells.length == 0 || datumY == Integer.MIN_VALUE) return;
        int waterY = datumY - 1;
        int cleared = 0;
        for (long c : poolCells) {
            BlockPos p = new BlockPos(HotSpringShape.unpackX(c), waterY, HotSpringShape.unpackZ(c));
            if (level.getBlockState(p).getFluidState().isEmpty()) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            cleared++;
        }
        if (cleared > 0) {
            GeysersMod.LOGGER.debug("Spring at {},{} drained {} cells before rebuilding",
                    siteX(), siteZ(), cleared);
        }
    }

    /**
     * Brings a spring saved by an older version up to date, once.
     *
     * <h2>Why this is needed at all</h2>
     * The pool cell list is what {@link #poolHealth} measures against, and a world saved before it
     * existed has no {@code PoolCells} tag - so the list loads empty and {@code poolHealth} answers
     * BLOCKED for ever. That has three consequences, none of them recoverable on their own: the
     * spring can never read FINE, so its rebuild counter never clears; it can never read FOULED, so
     * dropping dirt in an old pool does nothing at all; and a spring already at its final stage
     * returns from {@link #serverTick} before anything would repopulate the list. It stays broken
     * for the life of the world.
     *
     * <p>So it is rebuilt once at the age it had. The trees are left alone - this ground was cleared
     * when the spring first arrived, possibly years of play ago.</p>
     *
     * @return true if this tick was spent on the migration
     */
    private boolean adoptOldSave(ServerLevel level) {
        if (poolCells.length > 0) return false;
        if (!surfaced || stage <= 0 || datumY == Integer.MIN_VALUE) return false;
        if (!applyStage(level, stage)) {
            // Nothing can be built here any more - the ground has changed out from under it. Let
            // the ordinary blocked-outlet path deal with it rather than retrying every check.
            surfaced = false;
            setChanged();
            return false;
        }
        GeysersMod.LOGGER.debug("Spring at {},{} adopted an older save ({} cells)",
                outletX, outletZ, poolCells.length);
        return true;
    }

    /**
     * The column this spring belongs to, for asking questions about the ground.
     *
     * <h2>Why this is not just the outlet</h2>
     * {@code outletX}/{@code outletZ} are only filled in by {@code setVent}, which runs inside
     * {@link #openVent} - <b>after</b> the water reaches daylight. A source planted by an earthquake
     * goes {@code SpringSeeding.afterQuake -> seedSourceAt -> place} and never touches setVent, so
     * until it surfaces those fields are both <b>0</b>.
     *
     * <p>That made every quiet-zone question about a source still boring upward a question about
     * the world origin, usually thousands of blocks from the quake. The sources that most needed
     * protecting - freshly seeded ones, inside the rupture, mid-climb - were the exact ones the
     * quiet zone could not see. Falling back to the block entity's own column fixes it, and the two
     * agree once a spring has surfaced because the vent is bored straight up from here.</p>
     */
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
     * Is this spring rebuilding too often to be believed?
     *
     * <p>Two guards, because the failure they catch is the same one twice. A spring that cannot
     * hold its pool used to rebuild every {@link #CHECK_INTERVAL} for as long as the chunk stayed
     * loaded, and each rebuild is a real edit to the world - measured at 177 on one source in a
     * single session. The cooldown makes that at worst one edit every 30 seconds, and after
     * {@link #REBUILD_LIMIT} in a row the spring stops and says so once, rather than filling the
     * log and grinding the site down.</p>
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

    /**
     * One step of the climb: bore a little further, and open the vent on arrival.
     *
     * <h2>The ground, not the canopy</h2>
     * The target used to come from a raw {@link Heightmap.Types#WORLD_SURFACE} lookup, which counts
     * leaves. Under a tree that reads several blocks high, so the conduit kept climbing towards the
     * canopy and surfaced above the real ground - the 90 to 93 climb in the logs. TerrainProbe's own
     * javadoc warns about exactly this, and the warning was there before this method was written.
     */
    private void climb(ServerLevel level, BlockPos pos) {
        int probed = TerrainProbe.groundY(level, pos.getX(), pos.getZ());
        int ground = probed != Integer.MIN_VALUE
                ? probed
                : level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        int ceiling = ground + CEILING_ALLOWANCE;
        if (mouthY == Integer.MIN_VALUE) mouthY = pos.getY() + 1;

        // The ground can move down as well as up - a quake that drops the surface strands the old
        // mouth above the new ceiling, and the trace then has nowhere to climb to, reports no
        // progress, and the source counts itself stalled while standing at an open outlet.
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
     * Lines the newly bored section, and notes what it was bored through.
     *
     * <p>The noting is the point: this is the water picking up its mineral load. A conduit that came
     * up through carbonate rock delivers water that lays down travertine; one through volcanic rock
     * delivers silica, and the spring above deposits sinter. Yellowstone's basins are siliceous
     * because they sit on rhyolite; Pamukkale is carbonate because it sits on limestone.</p>
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
                level.setBlock(p, deposit(), 2);
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

        // Retire any older bed left in this column. Without this the new bed goes straight on top of
        // the old one and the buried bed reports itself dry for the life of the world - the log line
        // "block above is fts_geology:hot_spring".
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos p = vent.below(dy);
            if (level.getBlockState(p).is(ModBlocks.HOT_SPRING.get())) {
                level.setBlock(p, deposit(), 2);
            }
        }

        setVent(vent);
        stalled = 0;
        // Water reaching daylight IS a spring, immediately - a small one. The stages after this are
        // it getting older, not it appearing.
        //
        // This is also the only place the canopy comes off: the trees go because a spring arrived
        // here, not because it is still here. Rebuilds pass false and leave the wood alone.
        if (!applyStage(level, 1, true)) {
            stalled++;
            setChanged();
            return;
        }
        stage = 1;
        surfaced = true;
        rebuilds = 0;
        stageSince = level.getGameTime();
        GeysersMod.LOGGER.info("Spring line at {} broke surface at Y {}, carrying {}",
                pos, ground, deposit().getBlock().getName().getString());
        setChanged();
    }

    // === Growth =============================================================

    /**
     * Builds the spring at a stage. All the shaping lives in {@link HotSpringShape}.
     *
     * <p>This class used to shape the pool itself, and that is where every bad spring came from: a
     * version that re-cut on a timer walked downhill, one that grew cell by cell came out full of
     * holes, and one that raised the water a block per stage ended up standing on a calcite
     * pedestal above the treetops. Shape is now a pure function of place and stage, testable on its
     * own through {@code /geology place hotspring}, and the line's only job is to decide which
     * arguments it gets.</p>
     */
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
        // Remembered so health() can ask about the pool that exists rather than the disc it might
        // have filled. See HotSpringShape.health.
        poolCells = HotSpringShape.pack(pool);
        setChanged();
        return true;
    }

    /**
     * The outlet is capped and cannot be cleared: the water goes looking for another way out.
     *
     * <p>This is the behaviour a blocked spring actually has. Pressure does not disappear because a
     * vent silts up; it finds the next weakness, which is usually a few metres away rather than
     * directly above. The old pool is left as a dead travertine terrace - crust standing, water
     * gone, colours faded, because the mats do not outlive the spring that fed them.</p>
     *
     * @return true if a new outlet was chosen
     */
    /**
     * Disabled: it cannot actually move a spring, and pretending it can is worse than not trying.
     *
     * <h2>What is broken about it</h2>
     * It picks a new outlet a few blocks away and writes it into {@code outletX}/{@code outletZ}.
     * But the conduit is bored by {@link #climb}, which goes straight up from the block entity's own
     * column, and {@link #openVent} then writes the vent back as
     * {@code (pos.getX(), ground, pos.getZ())} - overwriting the position this method chose. The
     * spring surfaces exactly where it already was, having thrown away its pool and its mats on the
     * way. So the mechanism that had never once run (the stall counter could not reach its limit)
     * would not have worked even when it did.
     *
     * <p>Fixing it properly means either moving the source block itself or cutting a horizontal
     * gallery from the core to the new outlet, and that is a piece of work in its own right. Until
     * then a blocked spring stays where it is and keeps trying to clear itself, which is the
     * behaviour testing has actually been happy with.</p>
     */
    @SuppressWarnings("unused")
    private boolean stepAsideDisabled(ServerLevel level, BlockPos pos) {
        BlockPos best = null;
        int bestGround = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = level.random.nextDouble() * Math.PI * 2;
            int r = 2 + level.random.nextInt(5);
            // Around this spring's own column - see siteX(). An unsurfaced source has no outlet
            // recorded, and searching from (0, 0) found nothing but unloaded chunks.
            int x = siteX() + (int) Math.round(Math.cos(ang) * r);
            int z = siteZ() + (int) Math.round(Math.sin(ang) * r);
            if (!level.isLoaded(new BlockPos(x, level.getSeaLevel(), z))) continue;

            int g = TerrainProbe.groundY(level, x, z);
            if (g == Integer.MIN_VALUE || g <= level.getSeaLevel() + 2) continue;
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(new BlockPos(x, g, z)))) continue;
            if (TerrainProbe.hasFluidAbove(level, x, z)) continue;
            // Water takes the lowest way out it can find.
            if (g < bestGround) {
                bestGround = g;
                best = new BlockPos(x, g, z);
            }
        }
        if (best == null) return false;

        HotSpringShape.abandon(level, outletX, outletZ, Math.max(1, stage));
        GeysersMod.LOGGER.info("Spring line at {} moved its outlet from {},{} to {},{}",
                pos, outletX, outletZ, best.getX(), best.getZ());
        outletX = best.getX();
        outletZ = best.getZ();
        datumY = Integer.MIN_VALUE;   // a new outlet has its own ground level
        outletY = best.getY();
        mouthY = Integer.MIN_VALUE;
        stage = 0;
        stageSince = level.getGameTime();
        stalled = 0;
        // A new outlet has not surfaced yet, and its pool does not exist. Both have to be cleared
        // or the spring would keep answering questions about the basin it just walked away from.
        surfaced = false;
        poolCells = new long[0];
        rebuilds = 0;
        lastRebuild = Long.MIN_VALUE;
        // A spring that has found somewhere else to come out is not a spring that has given up.
        // Without this the dormancy set on the way in would survive the move and the new outlet
        // would never be built - dormant was set in two places and cleared in none.
        dormant = false;
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
        // Springs saved before the pool was recorded have surfaced if they have an outlet: without
        // this they would go back to climbing and re-open a vent that is already open.
        //
        // The stage is deliberately NOT part of this test. A spring caught mid-way through the old
        // two-second loop was saved with stage 0 - the loop set it on every failed check - so
        // requiring stage > 0 would load exactly those springs as unsurfaced and drop them straight
        // back into the demotion this was written to stop. An assigned outlet means the conduit
        // reached daylight, whatever the stage says.
        surfaced = tag.contains("Surfaced")
                ? tag.getBoolean("Surfaced")
                : outletY != Integer.MIN_VALUE;
        rebuilds = tag.getInt("Rebuilds");
        lastRebuild = tag.contains("LastRebuild") ? tag.getLong("LastRebuild") : Long.MIN_VALUE;
        resitedFor = tag.contains("ResitedFor") ? tag.getLong("ResitedFor") : Long.MIN_VALUE;
    }
}
