package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.eruption.VentPathfinder;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.MagmaSealing;
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

/**
 * Works a buried hot spring back up to daylight.
 *
 * <h2>What it is doing</h2>
 * Heated water under pressure does not give up when its outlet is blocked; it works through the
 * blockage, and while it does it drops the minerals it is carrying. That is what a travertine mound
 * IS - Pamukkale is a spring that has been building its own hill out of its own deposit for
 * thousands of years. So this bores a conduit upward a few blocks at a time, notes the rock it
 * passes through, and when it breaks surface it opens a single wet block and then grows a pool
 * outward from it by <b>laying material down</b>.
 *
 * <h2>It never takes a block away</h2>
 * The first version of this asked the world generator to cut it a pool instead, and that was a
 * mistake with teeth. Cutting a pool lowers the ground; the next cut is sited from the ground; so a
 * source that rebuilt on a timer walked its own pool one block downhill every couple of seconds.
 * Measured in one session: 53 sources, 316 pools, an entire spring field sunk into a pit. Building
 * by deposition cannot do that, because every step only ever adds.
 *
 * <h2>Why it reuses the geyser's pathfinder</h2>
 * {@link VentPathfinder} already does exactly this job for geysers: climb gradually, prefer an
 * opening that already exists over breaking fresh rock, wall off a cave it breaks into, stop at a
 * ceiling. It also refuses to touch anything player-built unless it is given pressure to force
 * with - and a spring is given none, so a build over a spring stops it dead. That safety is
 * inherited rather than re-implemented.
 *
 * <h2>Why the outlet is allowed to move</h2>
 * The conduit climbs from the source, not from where the pool used to be, so if a quake has moved
 * the ground the water surfaces wherever the new ground lets it. That is the behaviour the 1959
 * Hebgen Lake earthquake produced at Yellowstone: outlets shifted, deposits were left behind at the
 * old ones, and the systems themselves carried on.
 */
public class SpringSourceBlockEntity extends BlockEntity {

    /** How often the source looks up, in ticks. Slow: this is geology, not machinery. */
    private static final int CHECK_INTERVAL = 40;

    /** Give up on a climb that has made no progress this many attempts in a row. */
    private static final int STALL_LIMIT = 20;

    /** Highest the conduit may ever climb above the ground it was built under. */
    private static final int CEILING_ALLOWANCE = 3;

    /** Where the pool this source last built has its warm bed, or MIN_VALUE when it has none. */
    private int outletX;
    private int outletZ;
    private int outletY = Integer.MIN_VALUE;

    /** Top of the conduit so far. Starts at the source and climbs. */
    private int mouthY = Integer.MIN_VALUE;

    /** Radius of the pool to cut when the conduit surfaces. */
    private int poolRadius = 4;

    /** Consecutive attempts that gained no height. */
    private int stalled;

    /** How far the pool has grown out from the vent so far. */
    private int grown;

    /** Set when the outlet came out at or under the waterline, where a pool cannot be held. */
    private boolean dormant;

    /** Rock the conduit has bored through, which decides what the spring precipitates. */
    private int carbonate;
    private int volcanic;

    public SpringSourceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPRING_SOURCE.get(), pos, state);
    }

    /** Called by the generator so a fresh source knows how big a pool it feeds. */
    public void setPoolRadius(int r) {
        this.poolRadius = Math.max(2, r);
        setChanged();
    }

    /**
     * Called when the generator has already cut the pool, so the source starts up satisfied.
     *
     * <p>{@code grown} is set to the target on purpose: this pool exists already and must not have
     * rings deposited around it. Only a vent that has just broken surface starts at zero.</p>
     */
    public void setOutlet(BlockPos bed) {
        this.outletX = bed.getX();
        this.outletY = bed.getY();
        this.outletZ = bed.getZ();
        this.mouthY = bed.getY();
        this.grown = poolRadius;
        setChanged();
    }

    /** Wakes a source that has been told its pool is gone, so it re-checks on the next tick. */
    public void nudge() {
        this.stalled = 0;
        setChanged();
    }

    /**
     * Marks this source as having no outlet yet, so it starts climbing from scratch. Used when a
     * quake opens a source somewhere that has never had a spring.
     */
    public void clearOutlet() {
        this.outletY = Integer.MIN_VALUE;
        this.mouthY = Integer.MIN_VALUE;
        this.stalled = 0;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SpringSourceBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (!GeyserConfig.SPRING_RENEWAL_ENABLED.get()) return;
        if ((server.getGameTime() + pos.hashCode()) % CHECK_INTERVAL != 0) return;

        if (be.dormant) return;                       // below sea level; see surface()

        // A pool that is alive but not yet grown keeps depositing. That is the whole difference
        // between this and the version before it: the spring is never "finished and re-cut", it is
        // always either climbing or slowly building, and it never takes a block away.
        if (be.outletAlive(server)) {
            be.stalled = 0;
            if (be.grown < be.poolRadius) be.accrete(server);
            return;
        }
        // Stalling drops the source to one attempt a minute rather than stopping it for good. What
        // blocks a spring is usually temporary - rubble that has not finished settling, a player
        // structure that gets moved - and a source that has given up permanently is indistinguishable
        // from one that never worked.
        if (be.stalled >= STALL_LIMIT && server.getGameTime() % 1200L != 0L) return;
        be.climb(server, pos);
    }

    /**
     * Two block lookups: is the pool this source built still a pool?
     *
     * <p>The bed's own coordinates are remembered rather than assumed to be straight overhead. On
     * broken ground the pool cutter puts the bed wherever the basin actually formed, which need not
     * be the centre column - and a source looking in the wrong column finds no bed, concludes its
     * pool has been destroyed, and cuts a fresh one every couple of seconds for the life of the
     * world.</p>
     */
    private boolean outletAlive(ServerLevel level) {
        if (outletY == Integer.MIN_VALUE) return false;
        BlockPos bed = new BlockPos(outletX, outletY, outletZ);
        if (!level.getBlockState(bed).is(ModBlocks.HOT_SPRING.get())) return false;
        return !level.getBlockState(bed.above()).getFluidState().isEmpty();
    }

    /**
     * One step of the climb: bore a little further, line what was bored, and cut the pool if the
     * conduit has reached the surface.
     */
    private void climb(ServerLevel level, BlockPos pos) {
        int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        int ceiling = ground + CEILING_ALLOWANCE;
        if (mouthY == Integer.MIN_VALUE) mouthY = pos.getY() + 1;

        // The ground can move down as well as up - a quake that drops the surface leaves the old
        // mouth stranded above the new ceiling. Bring it back into range first, or the trace has
        // nowhere to climb to, reports no progress, and the source counts itself stalled while
        // standing at an outlet that is already open.
        mouthY = Math.min(mouthY, ceiling);
        int before = mouthY;

        // No pressure: the pathfinder will clear natural rubble and refuse anything built, which is
        // exactly the promise a spring should keep. See VentPathfinder.trace.
        BlockPos mouth = VentPathfinder.trace(level, pos, mouthY, ceiling, 0.0, true);
        mouthY = mouth.getY();

        if (mouthY > before) {
            stalled = 0;
            lineWithSinter(level, pos, before, mouthY);
            setChanged();
        }

        // Surfaced? The conduit is open to the sky once it is at or above the ground. Checked
        // whether or not it climbed this time, because arriving and making no further progress is
        // success, not a stall.
        if (mouthY >= ground) {
            surface(level, pos, ground);
            return;
        }
        if (mouthY <= before) stalled++;
    }

    /**
     * Skins the newly bored section with sinter.
     *
     * <p>This is the visible half of the mechanism and the reason a re-opened spring does not look
     * like a drilled hole: the fill the water came through is left as travertine, so what you find
     * afterwards is a pale chimney with the spring running up the middle of it.</p>
     */
    private void lineWithSinter(ServerLevel level, BlockPos pos, int fromY, int toY) {
        BlockState deposit = depositBlock();
        for (int y = fromY; y <= toY; y++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos p = new BlockPos(pos.getX(), y, pos.getZ()).relative(d);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                if (s.is(Blocks.BEDROCK) || s.is(ModBlocks.SINTER.get())) continue;
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                // Note what it came through on the way past. This is the water picking up its
                // mineral load, and it is what decides whether the spring lays down travertine or
                // silica sinter when it reaches the top.
                noteRock(s);
                if (level.random.nextInt(3) == 0) continue;     // patchy, not a tiled pipe
                level.setBlock(p, deposit, 2);
            }
        }
    }

    /** Tallies one block of wall rock towards what the water is carrying. */
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

    /**
     * The conduit has reached daylight. Open the vent - one block of water on the ground, with the
     * warm bed under it - and let {@link #accrete} grow the rest.
     */
    private void surface(ServerLevel level, BlockPos pos, int ground) {
        // A spring whose outlet lands at or under the waterline is a submarine spring. They are real
        // and we do not model them; more to the point, a basin cut there drains into the sea. The
        // source sleeps for good instead of trying twenty times and reporting failure.
        if (ground - 1 <= level.getSeaLevel() + 1) {
            dormant = true;
            GeysersMod.LOGGER.info("Spring source at {} surfaced at Y {}, at the waterline; dormant",
                    pos, ground);
            setChanged();
            return;
        }

        BlockPos vent = new BlockPos(pos.getX(), ground, pos.getZ());
        BlockPos bedPos = vent.below();
        if (EruptionHandler.isPlayerPlaced(level.getBlockState(vent))
                || EruptionHandler.isPlayerPlaced(level.getBlockState(bedPos))) {
            stalled++;
            setChanged();
            return;
        }

        level.setBlock(bedPos, ModBlocks.HOT_SPRING.get().defaultBlockState(), 2);
        level.setBlock(vent, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(bedPos.below(2), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        MagmaSealing.seal(level, bedPos.below(2), false);

        setOutlet(bedPos);
        grown = 0;
        stalled = 0;
        GeysersMod.LOGGER.info("Spring source at {} broke surface at Y {}, depositing {}",
                pos, ground, depositBlock().getBlock().getName().getString());
        setChanged();
    }

    /**
     * Grows the pool by one ring, by putting material down rather than taking it away.
     *
     * <h2>Why deposition and not excavation</h2>
     * The previous version asked the world generator to cut it a pool. That was wrong twice. It made
     * the spring a stamp rather than a process, so a repaired spring looked machined; and cutting a
     * pool lowers the ground, which lowers the surface height, which lowers where the next cut goes -
     * so a source that rebuilt on a timer walked its own pool one block downhill every two seconds
     * until the whole field had sunk into a pit. Measured: 53 sources, 316 pools, in minutes.
     *
     * <p>Deposition cannot do that. Every step only ever adds a block, so the ground under a spring
     * can rise and never fall, and the shape comes out irregular for free because the pool stops
     * wherever the land is already higher than its water.</p>
     *
     * <h2>Why it is the right geology anyway</h2>
     * This is how a travertine terrace actually forms. Water carrying dissolved carbonate reaches the
     * air, CO2 comes out of solution at the rim, the carbonate drops there, and the rim grows. The
     * pool deepens behind its own deposit. Pamukkale is a spring that has been building its own hill
     * out of its own precipitate for thousands of years.
     */
    private void accrete(ServerLevel level) {
        int waterY = outletY + 1;
        int r = ++grown;
        BlockState deposit = depositBlock();

        int added = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                int x = outletX + dx, z = outletZ + dz;

                // Wobble the edge so the pool is not a disc.
                if (d2 > (r - 1) * (r - 1) && level.random.nextInt(3) == 0) continue;

                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE) continue;
                if (g >= waterY) continue;              // the land is already higher: the pool ends here
                if (waterY - g > 4) continue;           // a drop this steep is a cliff, not a basin

                // Floor first, up to the water line, then the water itself.
                boolean blocked = false;
                for (int y = g + 1; y <= waterY; y++) {
                    if (EruptionHandler.isPlayerPlaced(level.getBlockState(new BlockPos(x, y, z)))) {
                        blocked = true;
                        break;
                    }
                }
                if (blocked) continue;

                for (int y = g + 1; y < waterY; y++) {
                    level.setBlock(new BlockPos(x, y, z), deposit, 2);
                }
                level.setBlock(new BlockPos(x, waterY, z), Blocks.WATER.defaultBlockState(), 2);
                added++;
            }
        }

        // The rim: one course of deposit around the outside, so the water is held by the spring's
        // own crust rather than by a hole in the ground.
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= r * r || d2 > (r + 1) * (r + 1)) continue;
                BlockPos p = new BlockPos(outletX + dx, waterY, outletZ + dz);
                BlockState s = level.getBlockState(p);
                if (!s.isAir() && s.getFluidState().isEmpty()) continue;   // solid already
                if (EruptionHandler.isPlayerPlaced(s)) continue;
                if (level.random.nextInt(4) == 0) continue;
                level.setBlock(p, deposit, 2);
            }
        }

        if (added == 0) grown = poolRadius;   // hemmed in; stop rather than ring the site forever
        setChanged();
    }

    /**
     * What this spring is precipitating, decided by the rock its conduit came up through.
     *
     * <p>This is the difference between sinter being a texture and sinter being a reading. Water
     * that has come up through carbonate rock deposits travertine; water that has come up through
     * volcanic rock is carrying silica and deposits sinter. Yellowstone's basins are siliceous
     * because they sit on rhyolite; Pamukkale is carbonate because it sits on limestone.</p>
     */
    private BlockState depositBlock() {
        return carbonate >= volcanic
                ? Blocks.CALCITE.defaultBlockState()
                : ModBlocks.SINTER.get().defaultBlockState();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("OutletX", outletX);
        tag.putInt("OutletY", outletY);
        tag.putInt("OutletZ", outletZ);
        tag.putInt("MouthY", mouthY);
        tag.putInt("PoolRadius", poolRadius);
        tag.putInt("Stalled", stalled);
        tag.putInt("Grown", grown);
        tag.putBoolean("Dormant", dormant);
        tag.putInt("Carbonate", carbonate);
        tag.putInt("Volcanic", volcanic);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        outletY = tag.contains("OutletY") ? tag.getInt("OutletY") : Integer.MIN_VALUE;
        // Sources written before the bed's column was recorded fall back to straight overhead,
        // which is where it is on level ground and therefore right for nearly all of them.
        outletX = tag.contains("OutletX") ? tag.getInt("OutletX") : worldPosition.getX();
        outletZ = tag.contains("OutletZ") ? tag.getInt("OutletZ") : worldPosition.getZ();
        mouthY = tag.contains("MouthY") ? tag.getInt("MouthY") : Integer.MIN_VALUE;
        poolRadius = Math.max(2, tag.getInt("PoolRadius"));
        stalled = tag.getInt("Stalled");
        grown = tag.getInt("Grown");
        dormant = tag.getBoolean("Dormant");
        carbonate = tag.getInt("Carbonate");
        volcanic = tag.getInt("Volcanic");
    }
}
