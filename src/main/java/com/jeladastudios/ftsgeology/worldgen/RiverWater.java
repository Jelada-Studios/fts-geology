package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyChunkGenerator;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The water standing in the rivers above the sea. {@link RiverNetwork} traces them down the raw ground and the
 * offset cuts their channels; this fills what the channel holds.
 *
 * <p>The water is {@code fts_geology:river_water}, which never moves. That is what lets the surface come down the
 * valley continuously instead of in flat pools: a column takes water wherever the channel's floor lies under the
 * surface the trace gives it, and nothing has to be proved about its neighbours.</p>
 */
public final class RiverWater {

    private RiverWater() {}

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * How far over the traced surface the real floor of a channel may come out before the column is given up on.
     *
     * <p>The trace reads a two-dimensional ground -- the offset with no three-dimensional noise on it -- and the
     * chunk is then built with that noise added, so the floor the generator actually lays wanders a block or two
     * either side of what the trace predicted. Where it came out over the water the old rule simply dropped the
     * column, and a dry island was left standing in the river. Those islands are what grew the grass, the flowers
     * and the melons a player found in the middle of a river: vanilla planted them at the vegetation step, quite
     * correctly, on ground we had left dry. Inside the channel the floor is ours to set, so it is shaved instead.
     */
    private static final int LEVEL_SHAVE = 4;

    /**
     * How deep a spring's bore runs under the bed it rises through, and how much of it may be cut short.
     *
     * <p>The bore still stops at the first block under it that is not solid, so a spring never opens into a cave;
     * asking for more only means it gets as much of what it asked for as the rock under it will give.</p>
     */
    private static final int SPRING_DEEP = 40, SPRING_VARY = 24, SPRING_LEAST = 5;
    /** How deep a hollow under a channel's floor is stopped up. */
    private static final int PLUG_DEEP = 12;
    /** The highest step down whose face is hung with falling water, in blocks. */
    private static final int CURTAIN_MAX = 16;
    /** The blocks at the top of the sea in a river's mouth that are the river's water. */
    private static final int MOUTH_TOP = 3;
    /**
     * How far a lake's shore may be taken down to its water, in blocks at the normal world's layout. The lake's
     * extent is worked out on the raw ground; the chunk is built with the noise on top, which left the edge of a
     * lake a block or two over its water in a dry strip -- the last two blocks between a stream and the lake it runs
     * into.
     */
    private static final int LAKE_LEVEL = 2;
    /** How far a river beside a lake may run below the lake's water before no falling water is hung from the lake down to it. */
    private static final int LAKE_WALL = 2;

    private static final LongAdder CANDIDATES = new LongAdder(), KEPT = new LongAdder(), BLOCKS = new LongAdder(),
            DROPPED = new LongAdder(), LEVELLED = new LongAdder(), SPRINGS = new LongAdder(), WET = new LongAdder(),
            BANKED = new LongAdder(), CLIFFS = new LongAdder(), ICED = new LongAdder(), GLACIERS = new LongAdder(), PLUGGED = new LongAdder(), CURTAINS = new LongAdder(), SHORED = new LongAdder(), GAPS = new LongAdder(), REEDS = new LongAdder();

    /**
     * The tongue of ice a mountain river comes out from under, where it rises high enough to snow: how far it reaches
     * up the valley, how wide and how thick it is, at the normal world's layout and grown with a wider one.
     */
    private static final double TONGUE_LONG = 10.0, TONGUE_WIDE = 5.0, TONGUE_THICK = 3.0;

    /**
     * How much a bank may be built up to hold the water beside it, in blocks.
     *
     * <p>The channel is cut into the raw ground, and the rim the water is held under is read off it too, but the chunk
     * is built with the three-dimensional noise on top. Where that leaves the ground beside a river a block or two
     * lower than the raw ground said, the water stood a block over its bank: a sheet of water upright in the open,
     * seven columns in a hundred along the new rivers. The column beside it is built up to the water in its own
     * ground. More than two blocks is a cliff, not a bank, and is counted and left.</p>
     */
    private static final int BANK_FILL = 2;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final AtomicLong CHUNKS = new AtomicLong();

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel server = level.getLevel();
        if (TfcCompat.active() || !GeologyWorld.isOwn(server) || !RiverNetwork.ready()) return 0;
        if (!GeyserConfig.RIVERS.get()) return 0;
        int sea = level.getSeaLevel();
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState();
        int placed = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                RiverNetwork.At a = RiverNetwork.at(x, z);
                if (!a.lake()) {
                    double gap = RiverNetwork.lakeGap(x, z);
                    if (!Double.isNaN(gap) && (a.distance() == Double.MAX_VALUE
                            || a.floor() > Math.floor(a.water()) - 0.5 || gap > a.water())) {
                        placed += fillGap(level, at, x, z, (int) Math.floor(gap), water);
                        continue;
                    }
                }
                if (a.distance() == Double.MAX_VALUE) continue;
                int w = (int) Math.floor(a.water());
                // At the mouth the ocean is the river's surface. The fill used to stop the moment the traced water
                // reached sea level, which left the last stretch of channel -- tens of blocks of it on a flat
                // shore -- with nothing in it: the ocean's own top block is one under sea level, the last river
                // column stood one over it, and the block between was air. Below the line the fill drops to the
                // ocean's own level and patches only what the generator left dry, so the two waters meet.
                // Out to where the channel's own floor climbs to the surface, and no further: past that the bank
                // stands over the water and the water would be lying on the hillside. Measured against the river's own
                // water, before the mouth drops it to the sea's: a stream's floor is a block and a half under its water,
                // so against the sea's level every narrow stream came out dry for its last few blocks, and where the
                // ground stood a block over the line it kept its grass in the middle of the river.
                if (a.floor() > w - 0.5) continue;
                boolean mouth = w <= sea;
                if (mouth) w = sea - 1;
                CANDIDATES.increment();
                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE) {
                    DROPPED.increment();
                    continue;
                }
                if (g >= w) {
                    // A lake fills its hollow and no more: ground standing well over its water is its shore. A block or
                    // two over is the noise on the raw ground the hollow was worked out on, and is taken down.
                    if (a.lake() && g - w > Math.round(LAKE_LEVEL * RiverNetwork.horizontal())) continue;
                    if (a.lake()) SHORED.increment();
                    int bedY = level(level, at, x, z, g, w, a);
                    if (bedY == Integer.MIN_VALUE) {
                        DROPPED.increment();
                        continue;
                    }
                    g = bedY;
                    LEVELLED.increment();
                }
                // Nothing is poured over a hole: the block under the water has to be solid, or a cave under the bed is
                // stopped up first.
                if (!level.getBlockState(at.set(x, g, z)).isSolidRender(level, at) && !plug(level, at, x, g, z)) {
                    DROPPED.increment();
                    continue;
                }
                // Which way the water runs here, for the surface to be drawn running and a swimmer to be carried, out
                // through the mouth into the sea. A lake stands still.
                BlockState run = a.lake() ? water
                        : water.setValue(RiverWaterFluid.FLOW, RiverWaterFluid.wayOf(a.fx(), a.fz()));
                int here = 0;
                for (int y = g + 1; y <= w; y++) {
                    BlockState was = level.getBlockState(at.set(x, y, z));
                    if (!was.isAir() && was.getFluidState().isEmpty() && !TerrainProbe.isVegetation(was)) break;
                    // The top of the sea in a river's mouth is the river's: vanilla water there freezes over in the cold,
                    // and a river's mouth does not. Seagrass and kelp reaching up into it go too: each holds vanilla water
                    // of its own, drawn as a block of a different water standing in the river.
                    if (mouth && y >= w - MOUTH_TOP + 1 && (was.is(Blocks.WATER) || seaPlant(was))) {
                        level.setBlock(at, run, FLAGS);
                        here++;
                        continue;
                    }
                    // The sea has already filled what it could, and it is the same water: only the gap is ours.
                    if (mouth && !was.isAir()) {
                        here++;
                        continue;
                    }
                    level.setBlock(at, run, FLAGS);
                    placed++;
                    here++;
                }
                // Nothing grows standing on the water: a plant the ground under it was taken from, or one a neighbour
                // chunk put there, goes.
                if (here > 0) clearPlants(level, at, x, g + here + 1, z);
                // Kelp cut short under the river's water ends in a head, not a stem with nothing on it.
                if (mouth && level.getBlockState(at.set(x, w - MOUTH_TOP, z)).is(Blocks.KELP_PLANT)) {
                    level.setBlock(at, Blocks.KELP.defaultBlockState(), FLAGS);
                }
                KEPT.increment();
                // Counted apart: a column can be kept, have its bed swapped for gravel, and still take no water
                // because the first block over its floor turned out to be solid. That is a dry gravel stripe
                // beside the river, and the old single counter called it filled.
                if (here > 0) WET.increment();
                // A lake where it snows freezes over, as vanilla's water does there; a river keeps running. The
                // river's water is its own fluid, which vanilla's freezing does not know, so the ice is laid here.
                if (a.lake() && !mouth && here > 0 && g + here >= w
                        && level.getBiome(at.set(x, w, z)).value().coldEnoughToSnow(at)
                        && level.getBlockState(at).is(ModBlocks.RIVER_WATER.get())) {
                    level.setBlock(at, Blocks.ICE.defaultBlockState(), FLAGS);
                    ICED.increment();
                }
                if (here > 0) sediment(level, at, x, z, g, w - sea, a.lake(), a.halfWidth());
                if (a.isHead()) placed += spring(level, at, x, z, g, water);
            }
        }
        placed += banks(level, cp, sea, at);
        placed += curtains(level, cp, sea, at);
        placed += glaciers(level, cp, at);
        BLOCKS.add(placed);
        if (CHUNKS.incrementAndGet() % 100 == 0) {
            GeysersMod.LOGGER.info("River water over {} chunks: {} columns in a channel, {} kept, {} of them wet, "
                            + "{} levelled, {} let go, {} springs, {} blocks, {} banks built up, {} left as cliffs, "
                            + "{} lake columns iced, {} glacier columns, {} hollows stopped up, {} steps hung with falling water, {} lake shore columns taken down, {} lake necks filled, {} plants cleared off the water; {}; {}; {}",
                    CHUNKS.get(), CANDIDATES.sum(), KEPT.sum(), WET.sum(), LEVELLED.sum(), DROPPED.sum(),
                    SPRINGS.sum(), BLOCKS.sum(), BANKED.sum(), CLIFFS.sum(), ICED.sum(), GLACIERS.sum(), PLUGGED.sum(), CURTAINS.sum(), SHORED.sum(), GAPS.sum(), REEDS.sum(),
                    RiverNetwork.summary(), GeologyChunkGenerator.summary(), SnowCover.summary());
        }
        return placed;
    }

    /**
     * Where a river rises over the snow line, the ice it comes out from under: a tongue lying up the valley from the
     * spring, thickest up the valley and thinning to its snout, packed ice round a core of blue, with a low mouth at
     * the snout where the water leaves. Worked out from the spring alone, so every chunk lays its own share of it.
     */
    private static int glaciers(WorldGenLevel level, ChunkPos cp, BlockPos.MutableBlockPos at) {
        double hz = RiverNetwork.horizontal();
        double longest = TONGUE_LONG * (1.0 + 0.4 * (hz - 1.0)) * 1.2;
        int cx = cp.getMinBlockX() + 8, cz = cp.getMinBlockZ() + 8;
        int placed = 0;
        for (RiverNetwork.Head head : RiverNetwork.headsNear(cx, cz, longest + 12.0)) {
            int hx = (int) Math.floor(head.x()), hzb = (int) Math.floor(head.z());
            if (!level.getBiome(at.set(hx, (int) Math.floor(head.water()), hzb)).value().coldEnoughToSnow(at)) continue;
            long hash = SeedHash.hash(level.getSeed(), hx, hzb, 0x61AC1L);
            double grow = 1.0 + 0.4 * (hz - 1.0);
            double len = TONGUE_LONG * grow * (0.8 + 0.4 * SeedHash.rand01(hash));
            double wide = TONGUE_WIDE * grow * (0.8 + 0.4 * SeedHash.rand01(SeedHash.mix(hash ^ 1)));
            double thick = TONGUE_THICK * (0.8 + 0.4 * SeedHash.rand01(SeedHash.mix(hash ^ 2))) + 0.4 * (hz - 1.0);
            // Up the valley is against the way the water sets off.
            double ux = -head.dx(), uz = -head.dz();
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                    double px = x + 0.5 - head.x(), pz = z + 0.5 - head.z();
                    double up = px * ux + pz * uz, side = -px * uz + pz * ux;
                    if (up < -1.0 || up > len) continue;
                    double grown = Math.min(1.0, (up + 1.0) / (0.35 * len));
                    double halfWide = wide * 0.5 * (0.55 + 0.45 * grown);
                    if (Math.abs(side) > halfWide) continue;
                    double across = side / halfWide;
                    double body = Math.min(1.0, (up + 1.0) / (0.5 * len));
                    int t = (int) Math.round((1.0 + (thick - 1.0) * body) * Math.sqrt(1.0 - across * across));
                    if (t < 1) continue;
                    int g = TerrainProbe.groundY(level, x, z);
                    if (g == Integer.MIN_VALUE) continue;
                    // The mouth: a low opening at the snout, over the spring, that the river runs out of.
                    int mouth = up < 2.0 && Math.abs(side) < 1.25 ? 2 : 0;
                    for (int y = g + 1; y <= g + t + mouth; y++) {
                        if (y <= g + mouth) continue;
                        BlockState was = level.getBlockState(at.set(x, y, z));
                        if (!was.isAir() && !was.is(Blocks.SNOW) && !TerrainProbe.isVegetation(was)) break;
                        boolean core = Math.abs(across) < 0.45 && y < g + t + mouth;
                        level.setBlock(at, core ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState(), FLAGS);
                        placed++;
                    }
                    GLACIERS.increment();
                }
            }
        }
        return placed;
    }

    /**
     * The top of the water a column is to hold, or {@link Integer#MIN_VALUE} where it holds none. Worked out from the
     * river network alone, so a column can tell what its neighbour in the next chunk will hold without reading it.
     */
    private static int waterTop(int x, int z, int sea) {
        RiverNetwork.At a = RiverNetwork.at(x, z);
        if (a.distance() == Double.MAX_VALUE) return Integer.MIN_VALUE;
        int w = (int) Math.floor(a.water());
        // At the mouth the sea is the other bank.
        if (w <= sea) return Integer.MIN_VALUE;
        if (a.floor() > w - 0.5) return Integer.MIN_VALUE;
        return a.lake() && lakeWall(x, z, w, sea) ? Integer.MIN_VALUE : w;
    }

    /**
     * Whether a lake's edge here stands over a river running well below it. The lake's hollow is worked out on the
     * raw ground and the river's gorge is cut into it, so a lake on a shelf came out with its shore right on the
     * gorge's lip: a wall of water the height of the lake standing in the open over the river, with the river's
     * falling water hung under it to the bottom -- a cliff of water all along the shore. The river now leaves the
     * ground by a lake uncut ({@link RiverNetwork#floorAt}); where an edge still stands over it, no falling water is
     * hung from the lake down to the river.
     */
    private static boolean lakeWall(int x, int z, int w, int sea) {
        long drop = Math.round(LAKE_WALL * RiverNetwork.horizontal());
        for (int[] d : SIDES) {
            RiverNetwork.At n = RiverNetwork.at(x + d[0], z + d[1]);
            if (n.distance() == Double.MAX_VALUE || n.lake()) continue;
            int nw = Math.max(sea - 1, (int) Math.floor(n.water()));
            if (n.floor() > nw - 0.5) continue;
            if (w - nw > drop) return true;
        }
        return false;
    }

    /**
     * Falling water down the face of every step in a river: where a column's neighbour holds its water higher, the
     * air over this column's water, up to the neighbour's, takes the falling form of the river's water. A still
     * river comes down a valley a block at a time, and each step used to show its bare bed on the riser; a lake's
     * edge over the river leaving it stood as a sheet of water with nothing falling from it. The neighbour's water is
     * read off the network, so the next chunk need not be built.
     */
    private static int curtains(WorldGenLevel level, ChunkPos cp, int sea, BlockPos.MutableBlockPos at) {
        BlockState falling = ModBlocks.RIVER_WATER.get().defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
        int placed = 0;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                int w = waterTop(x, z, sea);
                if (w == Integer.MIN_VALUE || !level.getBlockState(at.set(x, w, z)).is(ModBlocks.RIVER_WATER.get())) continue;
                int top = w;
                int[] from = null;
                for (int[] d : SIDES) {
                    int n = waterTop(x + d[0], z + d[1], sea);
                    if (n > top) {
                        top = n;
                        from = d;
                    }
                }
                top = Math.min(top, w + CURTAIN_MAX);
                if (top <= w) continue;
                // Falling the way the water comes over the step: from the higher water beside, down into this column. Drawn
                // the way the river runs instead, a curtain where a river met a lake higher than itself ran up the fall.
                RiverNetwork.At a = RiverNetwork.at(x, z);
                int way = from != null ? RiverWaterFluid.wayOf(-from[0], -from[1]) : RiverWaterFluid.wayOf(a.fx(), a.fz());
                BlockState fall = falling.setValue(RiverWaterFluid.FLOW, way);
                int laid = 0;
                for (int y = w + 1; y <= top; y++) {
                    if (!level.getBlockState(at.set(x, y, z)).isAir()) break;
                    level.setBlock(at, fall, FLAGS);
                    laid++;
                }
                if (laid > 0) CURTAINS.increment();
                placed += laid;
            }
        }
        return placed;
    }

    /** Builds up the columns beside the water that came out lower than it. Only this chunk's own columns. */
    private static int banks(WorldGenLevel level, ChunkPos cp, int sea, BlockPos.MutableBlockPos at) {
        int placed = 0;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                if (waterTop(x, z, sea) != Integer.MIN_VALUE) continue;
                int want = Integer.MIN_VALUE;
                for (int[] d : SIDES) want = Math.max(want, waterTop(x + d[0], z + d[1], sea));
                if (want == Integer.MIN_VALUE) continue;
                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE || g >= want) continue;
                if (want - g > BANK_FILL) {
                    CLIFFS.increment();
                    continue;
                }
                BlockState top = level.getBlockState(at.set(x, g, z));
                if (EruptionHandler.isPlayerPlaced(top) || !top.isSolidRender(level, at)) continue;
                // Grass stays on top; what is under it is the soil it grows in.
                boolean turf = top.is(BlockTags.DIRT) && !top.is(Blocks.DIRT);
                BlockState body = turf ? Blocks.DIRT.defaultBlockState() : top;
                if (turf) level.setBlock(at, body, FLAGS);
                for (int y = g + 1; y <= want; y++) {
                    BlockState was = level.getBlockState(at.set(x, y, z));
                    if (!was.isAir() && !TerrainProbe.isVegetation(was)) break;
                    level.setBlock(at, y == want ? top : body, FLAGS);
                    placed++;
                }
                BANKED.increment();
            }
        }
        return placed;
    }

    /**
     * Takes the floor of a channel down to where the trace put it, where the noise left it standing over the water.
     * Only inside the flat bed, only by a few blocks, and never through anything a player or a structure put there.
     *
     * @return the new ground, or {@link Integer#MIN_VALUE} to leave the column alone
     */
    private static int level(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int g, int w,
                             RiverNetwork.At a) {
        // The bank ring counts too. The water is laid out to where the channel's own wall reaches the surface,
        // about a block past the flat bed, but the shave used to stop at the bed -- so every column in that ring
        // whose floor the noise left high was dropped, and the river got a ragged stair down both its sides.
        // That ring is a quarter of the channel, and it is exactly where the factor boost that pins the noise
        // has decayed to nothing.
        // The tall world's noise is laid out two and a half times as high, and so is how far it lifts a floor: a
        // quarter of the bed columns on K2's rivers stood five to seven blocks over their water and were dropped,
        // which read as a river broken off and starting again further down.
        if (g - w > Math.round(LEVEL_SHAVE * RiverNetwork.horizontal())) return Integer.MIN_VALUE;
        int bedY = Math.min(w - 1, (int) Math.floor(a.floor()));
        if (bedY < level.getMinBuildHeight() + 1) return Integer.MIN_VALUE;
        // The floor of the channel has to be there to stand on -- a cave the carvers ran under the bed is stopped up,
        // where it was left as a crust across the river -- and everything over it has to be ours to take.
        if (!level.getBlockState(at.set(x, bedY, z)).isSolidRender(level, at) && !plug(level, at, x, bedY, z)) {
            return Integer.MIN_VALUE;
        }
        for (int y = bedY + 1; y <= g; y++) {
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(at.set(x, y, z)))) return Integer.MIN_VALUE;
        }
        // A plant on the ground being taken away would be left standing in the air over the water.
        clearPlants(level, at, x, g + 1, z);
        for (int y = bedY + 1; y <= g; y++) {
            level.setBlock(at.set(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        return bedY;
    }

    /**
     * Lays what a river leaves on its floor over the ground it was cut from: gravel with cobbles in the mountains,
     * sand with clay lower down, clay and sand under a lake, in patches a few blocks across. Only the natural ground a
     * channel was cut through is taken, the soil or the stone; a floor already of sediment, or anything a player put
     * there, is left. A wide river's floor is two blocks deep.
     *
     * <p>Only the soil used to be swapped. Wherever the cut went down to stone, or to one of the belt's own rocks, the
     * bed kept it: grey patches in the gravel, which read as holes in the river's floor.</p>
     */
    private static void sediment(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int g, int overSea,
                                 boolean lake, double half) {
        double n = ValueNoise.noise(x, z, 5.0);
        BlockState lay;
        if (lake) lay = n > 0.1 ? Blocks.CLAY.defaultBlockState() : overSea > 12 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        else if (overSea > 12) lay = n > 0.45 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        else lay = n > 0.4 ? Blocks.CLAY.defaultBlockState() : n < -0.55 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        int deep = half >= 4.0 ? 2 : 1;
        for (int y = g; y > g - deep; y--) {
            BlockState was = level.getBlockState(at.set(x, y, z));
            if (!was.is(BlockTags.DIRT) && !was.is(BlockTags.BASE_STONE_OVERWORLD) && !was.is(BlockTags.TERRACOTTA)
                    && !was.is(Blocks.SANDSTONE) && !was.is(Blocks.RED_SANDSTONE)) break;
            if (EruptionHandler.isPlayerPlaced(was)) break;
            // Sand and gravel fall: never over a hole.
            if (!level.getBlockState(at.set(x, y - 1, z)).isSolidRender(level, at)) break;
            level.setBlock(at.set(x, y, z), lay, FLAGS);
        }
    }

    /**
     * Stops up a hollow under a channel's floor with the rock under it, so the floor at {@code y} is solid. A cave the
     * carvers ran along under a river left the ground over it as a crust; the water was dropped there, and the crust
     * stood across the river as a bar of grass. Only down to a few blocks, and never through what a player built.
     */
    /**
     * Takes the plants out of the river after the world has grown them. The water goes in before the vegetation step,
     * and a patch of grass or flowers laid down after it could still leave a tuft standing at the water's level, in a
     * cube of water of its own, in the middle of the river. Run at the last step of generation: a plant at or under the
     * water's level in a channel is water, one standing on the water is air.
     */
    public static void clearReeds(WorldGenLevel level, ChunkPos cp) {
        ServerLevel server = level.getLevel();
        if (TfcCompat.active() || !GeologyWorld.isOwn(server) || !RiverNetwork.ready()) return;
        if (!GeyserConfig.RIVERS.get()) return;
        int sea = level.getSeaLevel();
        BlockState water = ModBlocks.RIVER_WATER.get().defaultBlockState();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                // The water's top as the fill laid it, the mouth's included: a river at the sea stands at the sea's level.
                RiverNetwork.At a = RiverNetwork.at(x, z);
                if (a.distance() == Double.MAX_VALUE) continue;
                int w = (int) Math.floor(a.water());
                if (a.floor() > w - 0.5) continue;
                if (w <= sea) w = sea - 1;
                for (int y = w - 3; y <= w + 1; y++) {
                    BlockState s = level.getBlockState(at.set(x, y, z));
                    if (!TerrainProbe.isVegetation(s) || !s.getFluidState().isEmpty()) continue;
                    BlockState under = level.getBlockState(at.set(x, y - 1, z));
                    boolean wetUnder = under.is(ModBlocks.RIVER_WATER.get());
                    at.set(x, y, z);
                    if (y <= w && (wetUnder || under.isSolidRender(level, at.below()))) {
                        level.setBlock(at, water, FLAGS);
                        REEDS.increment();
                    } else if (y > w && wetUnder) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
                        REEDS.increment();
                    }
                }
            }
        }
    }

    /**
     * Water in a neck of a lake the grid missed ({@link RiverNetwork#lakeGap}): only where the ground lies under the
     * lake's water, on solid ground, and with each side either as high as the water or water itself, so nothing is
     * poured that could not be held.
     */
    private static int fillGap(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int w, BlockState water) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE || g >= w) return 0;
        if (!level.getBlockState(at.set(x, g, z)).isSolidRender(level, at)) return 0;
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            if (TerrainProbe.groundY(level, nx, nz) >= w) continue;
            if (RiverNetwork.at(nx, nz).lake() || !Double.isNaN(RiverNetwork.lakeGap(nx, nz))) continue;
            return 0;
        }
        int laid = 0;
        for (int y = g + 1; y <= w; y++) {
            BlockState was = level.getBlockState(at.set(x, y, z));
            if (!was.isAir() && was.getFluidState().isEmpty() && !TerrainProbe.isVegetation(was)) break;
            level.setBlock(at, water, FLAGS);
            laid++;
        }
        if (laid > 0) {
            clearPlants(level, at, x, g + laid + 1, z);
            GAPS.increment();
        }
        return laid;
    }

    private static boolean plug(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z) {
        int bottom = y;
        int least = Math.max(level.getMinBuildHeight() + 1, y - PLUG_DEEP);
        while (bottom > least && !level.getBlockState(at.set(x, bottom, z)).isSolidRender(level, at)) bottom--;
        BlockState under = level.getBlockState(at.set(x, bottom, z));
        if (!under.isSolidRender(level, at) || EruptionHandler.isPlayerPlaced(under)) return false;
        for (int k = bottom + 1; k <= y; k++) {
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(at.set(x, k, z)))) return false;
        }
        BlockState fill = under.is(BlockTags.BASE_STONE_OVERWORLD) ? under : Blocks.STONE.defaultBlockState();
        for (int k = bottom + 1; k <= y; k++) level.setBlock(at.set(x, k, z), fill, FLAGS);
        PLUGGED.increment();
        return true;
    }

    /** A plant of the sea, standing in vanilla water of its own. */
    private static boolean seaPlant(BlockState s) {
        return s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT);
    }

    /** Clears plants standing from {@code y} up, a few blocks at most. */
    private static void clearPlants(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z) {
        for (int k = y; k < y + 3; k++) {
            BlockState s = level.getBlockState(at.set(x, k, z));
            if (s.isAir() || !TerrainProbe.isVegetation(s) || EruptionHandler.isPlayerPlaced(s)) return;
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    /**
     * The mouth a river rises from: one block wide, running well under the bed, so that the water is seen to come
     * out of the ground rather than to begin in a puddle. It stops above the first thing that is not solid, so a
     * spring never opens into a cave.
     */
    private static int spring(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int g,
                              BlockState water) {
        long hash = SeedHash.hash(level.getSeed(), x, z, 0x5B10L);
        int want = SPRING_DEEP + (int) (SeedHash.rand01(hash) * SPRING_VARY);
        int floor = level.getMinBuildHeight() + 2;
        // Down only while the next block is rock with rock under it and rock on all four sides. The bore used to
        // take the last solid block over a cave and stand its water on the cave's roof with nothing under it, and a
        // bore running down beside a cave had a wall of water standing open to it.
        int deepest = g;
        while (deepest > g - want && deepest - 1 > floor && sealed(level, at, x, deepest - 1, z)) {
            deepest--;
        }
        if (g - deepest < SPRING_LEAST) return 0;
        int placed = 0;
        for (int y = g; y >= deepest; y--) {
            if (EruptionHandler.isPlayerPlaced(level.getBlockState(at.set(x, y, z)))) break;
            level.setBlock(at.set(x, y, z), water, FLAGS);
            placed++;
        }
        if (placed > 0) SPRINGS.increment();
        return placed;
    }

    /** Whether a block of the bore can hold water: solid itself, and solid under it and on every side. */
    private static boolean sealed(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z) {
        if (!level.getBlockState(at.set(x, y, z)).isSolidRender(level, at)) return false;
        if (!level.getBlockState(at.set(x, y - 1, z)).isSolidRender(level, at)) return false;
        for (int[] d : SIDES) {
            if (!level.getBlockState(at.set(x + d[0], y, z + d[1])).isSolidRender(level, at)) return false;
        }
        return true;
    }
}
