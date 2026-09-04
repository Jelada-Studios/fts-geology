package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.eruption.VentPathfinder;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.HotSpringShape;
import com.jeladastudios.ftsgeology.worldgen.RetrogenHandler;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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




    // --- state -------------------------------------------------------------

    /** The vent: where water issues. The warm bed sits one under it. */
    private int outletX;
    private int outletY = Integer.MIN_VALUE;
    private int outletZ;

    /** Top of the conduit so far. Climbs from the source towards daylight. */
    private int mouthY = Integer.MIN_VALUE;

    /** How wide this spring gets when it is finished. */
    private int targetRadius = 6;

    /** 0 = a bare vent, up to {@link #FINAL_STAGE}. */
    private int stage;

    /** Game time the current stage started, so the next one knows when it is due. */
    private long stageSince;

    /** Consecutive climb attempts that gained no height. */
    private int stalled;

    /** Set when the outlet came out at the waterline, where no pool can be held. */
    private boolean dormant;

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

    /** Called by the generator so a fresh line knows how big a spring it feeds. */
    public void setTargetRadius(int r) {
        this.targetRadius = Math.max(3, r);
        setChanged();
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
        for (int s = 1; s <= Math.max(1, Math.min(FINAL_STAGE, target)); s++) {
            if (!applyStage(level, s)) break;
            reached = s;
        }
        if (reached == 0) return false;
        stage = reached;
        stageSince = level.getGameTime();
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
        if (be.dormant) return;

        // A pool somebody has thrown a few blocks into is cleaned out and rebuilt at the age it had
        // reached. Only a pool that is mostly buried counts as a blocked outlet.
        if (be.datumY != Integer.MIN_VALUE && be.stage > 0) {
            HotSpringShape.Health h = HotSpringShape.health(
                    server, be.outletX, be.outletZ, be.stage, be.datumY);
            if (h == HotSpringShape.Health.FOULED) {
                be.applyStage(server, be.stage);
                GeysersMod.LOGGER.debug("Spring at {},{} flushed its pool", be.outletX, be.outletZ);
                return;
            }
        }

        if (!be.ventOpen(server)) {
            // The pool is gone - a quake, a landslide, a player filling it in. Nothing tries to
            // rescue what was there; the line just starts again from a bare vent.
            if (be.stage != 0) {
                be.stage = 0;
                be.stageSince = server.getGameTime();
                be.setChanged();
            }
            // Capped for good: the water goes looking for another way out rather than pushing at a
            // lid forever. This is what a blocked spring does - the pressure does not go away, it
            // finds the next weakness, usually a few metres to one side.
            if (be.stalled >= STALL_LIMIT) {
                if (server.getGameTime() % 1200L != 0L) return;
                if (be.stepAside(server, pos)) return;
            }
            be.climb(server, pos);
            return;
        }

        if (be.stage >= FINAL_STAGE) return;                 // finished; nothing left to do
        if (server.getGameTime() - be.stageSince < be.stageLength()) return;

        if (be.applyStage(server, be.stage + 1)) {
            be.stage++;
            be.stageSince = server.getGameTime();
            GeysersMod.LOGGER.info("Spring at {} reached stage {}/{} ({} blocks across)",
                    be.vent(), be.stage, FINAL_STAGE, HotSpringShape.radiusFor(be.stage) * 2 + 1);
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

    /** Is there still water at the vent? Two lookups, and the common case. */
    private boolean ventOpen(ServerLevel level) {
        if (outletY == Integer.MIN_VALUE) return false;
        if (datumY == Integer.MIN_VALUE) {
            return !level.getBlockState(new BlockPos(outletX, outletY, outletZ))
                    .getFluidState().isEmpty();
        }
        // Asked of the whole basin. Testing one column meant filling in any part of a pool except
        // the block over the bed did nothing at all, which is exactly what testing reported.
        return HotSpringShape.health(level, outletX, outletZ, Math.max(1, stage), datumY)
                != HotSpringShape.Health.BLOCKED;
    }

    // === Climbing ===========================================================

    /** One step of the climb: bore a little further, and open the vent on arrival. */
    private void climb(ServerLevel level, BlockPos pos) {
        int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
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
        if (!applyStage(level, 1)) {
            stalled++;
            setChanged();
            return;
        }
        stage = 1;
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
        if (datumY == Integer.MIN_VALUE) {
            datumY = HotSpringShape.datumFor(level, outletX, outletZ);
            if (datumY == Integer.MIN_VALUE) return false;
        }
        List<BlockPos> pool = HotSpringShape.build(level, outletX, outletZ, toStage, datumY);
        if (pool.isEmpty()) return false;
        outletY = pool.get(0).getY();
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
    private boolean stepAside(ServerLevel level, BlockPos pos) {
        BlockPos best = null;
        int bestGround = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = level.random.nextDouble() * Math.PI * 2;
            int r = 2 + level.random.nextInt(5);
            int x = outletX + (int) Math.round(Math.cos(ang) * r);
            int z = outletZ + (int) Math.round(Math.sin(ang) * r);
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
        tag.putInt("TargetRadius", targetRadius);
        tag.putInt("Stage", stage);
        tag.putLong("StageSince", stageSince);
        tag.putInt("Stalled", stalled);
        tag.putBoolean("Dormant", dormant);
        tag.putInt("DatumY", datumY);
        tag.putInt("Carbonate", carbonate);
        tag.putInt("Volcanic", volcanic);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        outletX = tag.getInt("OutletX");
        outletZ = tag.getInt("OutletZ");
        outletY = tag.contains("OutletY") ? tag.getInt("OutletY") : Integer.MIN_VALUE;
        mouthY = tag.contains("MouthY") ? tag.getInt("MouthY") : Integer.MIN_VALUE;
        targetRadius = Math.max(3, tag.getInt("TargetRadius"));
        stage = tag.getInt("Stage");
        stageSince = tag.getLong("StageSince");
        stalled = tag.getInt("Stalled");
        dormant = tag.getBoolean("Dormant");
        datumY = tag.contains("DatumY") ? tag.getInt("DatumY") : Integer.MIN_VALUE;
        carbonate = tag.getInt("Carbonate");
        volcanic = tag.getInt("Volcanic");
    }
}
