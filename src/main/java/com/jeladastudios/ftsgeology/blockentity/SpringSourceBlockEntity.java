package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.eruption.VentPathfinder;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.MagmaSealing;
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
    public static final int FINAL_STAGE = 3;

    /** Ticks in a Minecraft day. */
    private static final long DAY = 24000L;

    /** How deep a hollow the spring will floor with its own deposit rather than refuse. */
    private static final int FILL_LIMIT = 10;

    /** A pool smaller than this reads as a puddle, so the water rises to look for room. */
    private static final int MIN_POOL = 12;

    /** How far the water may rise above its stage height while looking for room. */
    private static final int MAX_SEARCH_RISE = 3;

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
        if (outletY == Integer.MIN_VALUE) return false;
        for (int s = 1; s <= FINAL_STAGE; s++) {
            if (!applyStage(level, s)) return s > 1;
        }
        stage = FINAL_STAGE;
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

        if (!be.ventOpen(server)) {
            // The pool is gone - a quake, a landslide, a player filling it in. Nothing tries to
            // rescue what was there; the line just starts again from a bare vent.
            if (be.stage != 0) {
                be.stage = 0;
                be.stageSince = server.getGameTime();
                be.setChanged();
            }
            if (be.stalled >= STALL_LIMIT && server.getGameTime() % 1200L != 0L) return;
            be.climb(server, pos);
            return;
        }

        if (be.stage >= FINAL_STAGE) return;                 // finished; nothing left to do
        if (server.getGameTime() - be.stageSince < be.stageLength()) return;

        if (be.applyStage(server, be.stage + 1)) {
            be.stage++;
            be.stageSince = server.getGameTime();
            GeysersMod.LOGGER.info("Spring at {} reached stage {}/{} ({} blocks across)",
                    be.vent(), be.stage, FINAL_STAGE, radiusFor(be.stage, be.targetRadius) * 2 + 1);
            be.setChanged();
        }
    }

    /** How long the current stage lasts, in ticks. */
    private long stageLength() {
        double days = switch (stage) {
            case 0 -> GeyserConfig.SPRING_STAGE_ONE_DAYS.get();
            case 1 -> GeyserConfig.SPRING_STAGE_TWO_DAYS.get();
            default -> GeyserConfig.SPRING_STAGE_THREE_DAYS.get();
        };
        return Math.max(20L, (long) (days * DAY));
    }

    /** Is there still water at the vent? Two lookups, and the common case. */
    private boolean ventOpen(ServerLevel level) {
        if (outletY == Integer.MIN_VALUE) return false;
        BlockPos v = new BlockPos(outletX, outletY, outletZ);
        if (level.getBlockState(v).getFluidState().isEmpty()) return false;
        return level.getBlockState(v.below()).is(ModBlocks.HOT_SPRING.get());
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
                if (s.is(Blocks.BEDROCK) || isOwnDeposit(s)) continue;
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

        level.setBlock(vent.below(), ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
        level.setBlock(vent, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(vent.below(3), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        MagmaSealing.seal(level, vent.below(3), false);

        setVent(vent);
        stage = 0;
        stageSince = level.getGameTime();
        stalled = 0;
        GeysersMod.LOGGER.info("Spring line at {} broke surface at Y {}, carrying {}",
                pos, ground, deposit().getBlock().getName().getString());
        setChanged();
    }

    // === Growth =============================================================

    /** How wide the pool is at a given stage. */
    private static int radiusFor(int stage, int target) {
        return switch (stage) {
            case 0 -> 0;
            case 1 -> Math.max(2, target / 3);
            case 2 -> Math.max(3, target * 2 / 3);
            default -> target;
        };
    }

    /**
     * Grows the spring to one stage: a wider basin, one block higher, with its old rim broken out.
     *
     * @return true if a pool was laid
     */
    private boolean applyStage(ServerLevel level, int toStage) {
        int r = radiusFor(toStage, targetRadius);
        BlockState deposit = deposit();

        // Each stage stands a block higher than the last. This is the mound growing - a spring
        // building its own hill out of its own precipitate, which is what Pamukkale is.
        //
        // And if there is no room at that height, the water rises until it finds some. A spring does
        // not dig itself a basin; it ponds, and the pond climbs until it spreads or spills. Without
        // this a spring on ground a quake had just broken had nowhere to go and stayed a single wet
        // block for good.
        int base = outletY + (toStage - 1);
        List<BlockPos> pool = List.of();
        int waterY = base;
        for (int rise = 0; rise <= MAX_SEARCH_RISE; rise++) {
            waterY = base + rise;
            pool = floodPool(level, waterY, r);
            if (pool.size() >= MIN_POOL) break;
        }
        if (pool.size() < 3) return false;

        for (BlockPos cell : pool) {
            int x = cell.getX(), z = cell.getZ();
            // Clear the column up to the waterline, but only of our own material and loose cover.
            // Native rock is never removed: that is the rule that makes the ground under a spring
            // unable to fall, and it is what the downhill-walking pool used to violate.
            for (int y = waterY; y <= waterY + 2; y++) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                if (isOwnDeposit(s) || TerrainProbe.isVegetation(s)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            // Floor: build up to the waterline wherever the ground sits below it.
            int g = TerrainProbe.groundY(level, x, z);
            int from = g == Integer.MIN_VALUE ? waterY - 1 : Math.min(waterY - 1, g);
            for (int y = from; y <= waterY - 1; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (EruptionHandler.isPlayerPlaced(level.getBlockState(p))) continue;
                level.setBlock(p, deposit, 2);
            }
            level.setBlock(cell, Blocks.WATER.defaultBlockState(), 2);
        }

        // The bed goes back under the vent, and the heat under that.
        BlockPos bed = new BlockPos(outletX, waterY - 1, outletZ);
        level.setBlock(bed, ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
        level.setBlock(bed.below(2), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        MagmaSealing.seal(level, bed.below(2), false);
        outletY = waterY;

        buildRim(level, pool, waterY, deposit);

        // The colours only arrive at the end. A microbial mat needs a large, warm, stable pool; on a
        // spring that opened a day ago there is nothing for it to live on yet.
        if (toStage >= FINAL_STAGE) {
            RetrogenHandler.paintRings(level, pool, outletX, outletZ, waterY);
        }
        return true;
    }

    /**
     * The pool as one connected region, worked out before a block is placed.
     *
     * <p>A flood fill from the vent, stopped by native ground standing above the waterline and by
     * the stage radius. Our own deposit is <b>passable</b>: that is how a spring breaks out past the
     * rim it built at an earlier stage instead of being sealed inside it forever.</p>
     *
     * <p>Doing it as a region rather than cell by cell is what stops the pool coming out full of
     * holes. The previous version tested each cell alone and dropped the failures permanently, which
     * on a spring that had been filled in left a scatter of water holes in a calcite field.</p>
     */
    private List<BlockPos> floodPool(ServerLevel level, int waterY, int r) {
        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{outletX, outletZ});
        seen.add(key(outletX, outletZ));

        while (!queue.isEmpty() && out.size() < 4096) {
            int[] c = queue.poll();
            int x = c[0], z = c[1];
            int dx = x - outletX, dz = z - outletZ;
            if (dx * dx + dz * dz > r * r) continue;

            if (!passable(level, x, z, waterY)) continue;
            out.add(new BlockPos(x, waterY, z));

            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = x + d.getStepX(), nz = z + d.getStepZ();
                if (seen.add(key(nx, nz))) queue.add(new int[]{nx, nz});
            }
        }
        return out;
    }

    /** May the pool occupy this column? Native rock above the waterline is a wall; ours is not. */
    private boolean passable(ServerLevel level, int x, int z, int waterY) {
        for (int y = waterY; y <= waterY + 1; y++) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (TerrainProbe.isVegetation(s) || isOwnDeposit(s)) continue;
            return false;                                    // native ground, or a build
        }
        // And it must not be a void the pool would pour into. The limit is what the spring is
        // willing to floor with its own deposit, so it is generous: measured on ground broken by a
        // quake, a limit of 6 admitted 22 cells where 10 admits 80. Refusing a hollow the spring
        // was about to fill in anyway is what left it with no pool at all on rubble.
        int g = TerrainProbe.groundY(level, x, z);
        return g != Integer.MIN_VALUE && waterY - g <= FILL_LIMIT;
    }

    /** One course of deposit around the pool, so the water is held by the spring's own crust. */
    private void buildRim(ServerLevel level, List<BlockPos> pool, int waterY, BlockState deposit) {
        Set<Long> inside = new HashSet<>();
        for (BlockPos p : pool) inside.add(key(p.getX(), p.getZ()));

        for (BlockPos p : pool) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int x = p.getX() + d.getStepX(), z = p.getZ() + d.getStepZ();
                if (inside.contains(key(x, z))) continue;
                BlockPos edge = new BlockPos(x, waterY, z);
                BlockState s = level.getBlockState(edge);
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                if (!s.isAir() && s.getFluidState().isEmpty()) continue;   // already solid
                level.setBlock(edge, deposit, 2);
            }
        }
    }

    /** Material this spring laid down itself, and may therefore take up again. */
    private static boolean isOwnDeposit(BlockState s) {
        return s.is(Blocks.CALCITE)
                || s.is(ModBlocks.SINTER.get())
                || s.is(ModBlocks.MICROBIAL_MAT_GREEN.get())
                || s.is(ModBlocks.MICROBIAL_MAT_YELLOW.get())
                || s.is(ModBlocks.MICROBIAL_MAT_ORANGE.get())
                || s.is(ModBlocks.MICROBIAL_MAT_BROWN.get());
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
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
        carbonate = tag.getInt("Carbonate");
        volcanic = tag.getInt("Volcanic");
    }
}
