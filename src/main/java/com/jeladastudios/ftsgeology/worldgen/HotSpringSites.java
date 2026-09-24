package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.SpringSourceBlockEntity;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.List;
import static com.jeladastudios.ftsgeology.worldgen.RetrogenHandler.*;
import static com.jeladastudios.ftsgeology.worldgen.SurfaceFeatures.*;

/** Hot spring systems: siting, terraces, plumbing, and the thermal bands and halo around the pools. */
public final class HotSpringSites {

    private HotSpringSites() {}

    /** No neighbour shape updates: at the edge of the loaded area they load the next chunk on the server thread. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** How far from a pool's centre its bands, halo, canopy clearing and runoff reach. */
    private static final int POOL_REACH = 56;

    /** How far a spring keeps from open lava, across; a volcano's lake had pools on its very rim. */
    private static final int LAVA_CLEARANCE = 16;

    /** Is there lava within {@code range} across and eight up or down of here? A bounded box, since springs are rare. */
    static boolean lavaNear(ServerLevel level, int x, int y, int z, int range) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    if (!level.hasChunkAt(m.set(x + dx, y + dy, z + dz))) continue;
                    if (level.getBlockState(m).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
                }
            }
        }
        return false;
    }

    /** Builds a hot spring system here: one broad pool on the flat, a terrace chain on a slope. */
    public static boolean placeHotSpringAt(ServerLevel level, int x, int z) {
        return placeHotSpringAt(level, x, z, HotSpringShape.MAX_STAGE);
    }

    /** Over a channel or a lake of the river network, or a few blocks from one. */
    private static boolean besideRiver(int x, int z) {
        if (!RiverNetwork.ready()) return false;
        RiverNetwork.At a = RiverNetwork.at(x, z);
        return a.distance() != Double.MAX_VALUE && a.distance() <= a.halfWidth() + 4.0;
    }

    /** Says at debug level why a site was turned down, so a country with no springs can be read from the log. */
    private static boolean refused(int x, int z, String why) {
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Hot spring site at {},{} refused: {}", x, z, why);
        return false;
    }

    /** @param stage how old the springs come out; the generator always asks for a finished one. */
    public static boolean placeHotSpringAt(ServerLevel level, int x, int z, int stage) {
        // Refuse unsuitable ground outright: shorelines, open water, cliff edges.
        int centre = TerrainProbe.groundY(level, x, z);
        if (centre == Integer.MIN_VALUE) return refused(x, z, "no ground");
        if (centre <= level.getSeaLevel() + 2) return refused(x, z, "at sea level");   // no beaches, no sea floor
        if (centre <= level.getMinBuildHeight() + 8) return refused(x, z, "at the world floor");

        int scan = 8;
        int lo = centre, hi = centre;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                int g = TerrainProbe.groundY(level, x + dx, z + dz);
                if (g == Integer.MIN_VALUE) return refused(x, z, "a cliff edge or open air");
                if (TerrainProbe.hasFluidAbove(level, x + dx, z + dz)) return refused(x, z, "a lake or the sea");
                // A frozen lake reads as ground: its ice is solid, and springs came up through it on the mountains.
                if (level.getBlockState(new BlockPos(x + dx, g, z + dz)).is(BlockTags.ICE)) return refused(x, z, "ice");
                if ((dx & 3) == 0 && (dz & 3) == 0 && besideRiver(x + dx, z + dz)) return refused(x, z, "a river or a lake");
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        int relief = hi - lo;
        // Up to 12 blocks of relief is allowed; broken ground gets a terrace chain. The foot of a big volcano and
        // the fan below it are all slope and foothill, and springs sit on that in terraces (Hakone), so more is
        // allowed out to a hundred blocks past the mountain.
        int reliefCap = com.jeladastudios.ftsgeology.volcano.VolcanoField.largeMargin(level, x, z) < 100 ? 18 : 12;
        if (relief > reliefCap) return refused(x, z, "relief " + relief);
        // Not beside a lava lake or over one: the water would be steam.
        if (lavaNear(level, x, centre, z, LAVA_CLEARANCE)) return refused(x, z, "lava near");

        // Layout: one broad pool on the flat or anywhere in a geothermal basin, a chain of smaller
        // terraces on a slope (Pamukkale), each a little lower than the one above.
        double basinHere = com.jeladastudios.ftsgeology.tectonics.HotspotMap
                .basinStrength(level, x, z);
        boolean inBasin = basinHere > 0.2;
        boolean terraced = !inBasin && relief >= 6;
        int terraces = terraced ? 2 + level.random.nextInt(3) : 1;

        // Downhill as an angle from the gradient, nudged at each step so a chain follows the hill.
        int gxPlus = TerrainProbe.groundY(level, x + scan, z);
        int gxMinus = TerrainProbe.groundY(level, x - scan, z);
        int gzPlus = TerrainProbe.groundY(level, x, z + scan);
        int gzMinus = TerrainProbe.groundY(level, x, z - scan);
        double bearing = Math.atan2(gzMinus - gzPlus, gxMinus - gxPlus);
        if (gxPlus == gxMinus && gzPlus == gzMinus) {
            bearing = level.random.nextDouble() * Math.PI * 2;   // dead flat: any way will do
        }

        int placed = 0;
        int px = x, pz = z;
        int ground = centre, waterY = centre - 1;       // recessed: water sits BELOW the rim
        BlockPos firstBed = null;

        // How old a pool here may get; the chain spacing comes from the same number, so terraces never overlap.
        int chainStage = terraced ? Math.min(3, stage) : stage;
        int matureR = HotSpringShape.radiusFor(chainStage);

        String why = "no pool could be built";
        for (int i = 0; i < terraces; i++) {
            // A pool that would reach into an unloaded chunk ends the chain rather than loading it.
            if (!areaLoaded(level, (px - POOL_REACH) >> 4, (pz - POOL_REACH) >> 4,
                    (px + POOL_REACH) >> 4, (pz + POOL_REACH) >> 4)) { why = "an unloaded chunk within pool reach"; break; }
            // Every pool is a mineral water line run to maturity: the same builder as after a quake.
            if (openSpring(level, px, pz, chainStage, stage)) placed++;
            // Step downhill clear of this pool's widest wobbled edge, with bearing and stride varied so
            // the chain wanders.
            int stride = (int) Math.ceil(matureR * 1.34) * 2 + 2 + level.random.nextInt(4);
            bearing += (level.random.nextDouble() - 0.5) * 0.9;
            px += (int) Math.round(Math.cos(bearing) * stride);
            pz += (int) Math.round(Math.sin(bearing) * stride);
            int nextGround = TerrainProbe.groundY(level, px, pz);
            if (nextGround == Integer.MIN_VALUE) break;
            // Each pool sits one under its own ground; the chain stops where the hill does.
            if (nextGround >= ground) break;
            ground = nextGround;
            waterY = nextGround - 1;
            if (waterY <= lo - 6) break;
        }
        if (placed == 0) return refused(x, z, why);
        // Logged so the stage distribution can be counted.
        GeysersMod.LOGGER.info(
                "Hot spring at {},{}: {} pool(s), stage {}, relief {}, basin {}",
                x, z, placed, chainStage, relief, String.format("%.2f", basinHere));
        return true;
    }

    /** How far under the pool the source sits: well below the deepest a quake digs. */
    static final int SOURCE_DEPTH = 50;

    /** The shallowest the source may sit under the surface and still be out of a quake's reach. */
    static final int MIN_SOURCE_DEPTH = 28;

    /**
     * One spring here at exactly the stage asked for, with no chain around it: what
     * {@code /geology place hotspring <stage>} builds, so each stage can be seen on its own.
     */
    public static boolean placeSingleSpringAt(ServerLevel level, int x, int z, int stage) {
        return openSpring(level, x, z, stage, stage);
    }

    /** Seats a mineral water line and runs it up to a spring. The only way a hot spring is built. */
    static boolean openSpring(ServerLevel level, int x, int z, int maxStage, int stage) {
        int ground = TerrainProbe.groundY(level, x, z);
        if (ground == Integer.MIN_VALUE) return refused(x, z, "no ground for the pool");
        if (ground <= level.getSeaLevel() + 2) return refused(x, z, "pool at sea level");

        BlockPos source = place(level, new BlockPos(x, ground, z), ground, maxStage);
        if (source == null) return refused(x, z, "nowhere to seat the source");
        if (!(level.getBlockEntity(source) instanceof SpringSourceBlockEntity be)) return refused(x, z, "source did not seat");

        BlockPos vent = new BlockPos(x, ground, z);
        be.setVent(vent);
        boolean built = be.growTo(level, stage);
        if (built) boreConduit(level, source, ground);
        else refused(x, z, "the pool would not grow");
        return built;
    }

    /**
     * Cuts the water channel from the reservoir up to the pool, skinned against caves, with the top few
     * blocks choked with the spring's own calcite, as a real vent narrows at its mouth.
     */
    static void boreConduit(ServerLevel level, BlockPos source, int groundY) {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState choke = Blocks.CALCITE.defaultBlockState();
        int x = source.getX(), z = source.getZ();

        for (int y = source.getY() + 4; y < groundY - 1; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
            // The last few blocks under the pool floor are the throat, sealed with the spring's own
            // deposit. Everything below that is the water column itself.
            level.setBlock(p, y >= groundY - 4 ? choke : water, FLAGS);
            // Skin the wall so the column does not open into a cave it happens to pass.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = p.relative(d);
                BlockState ws = level.getBlockState(w);
                if (ws.isAir() || !ws.getFluidState().isEmpty()) {
                    if (!EruptionHandler.isPlayerPlaced(ws)) level.setBlock(w, choke, FLAGS);
                }
            }
        }
    }

    /** Seats a mineral water line with no spring yet, as a quake does; it bores up and grows a spring over days. */
    public static BlockPos seedSourceAt(ServerLevel level, int x, int z, int groundY) {
        return place(level, new BlockPos(x, groundY, z), groundY,
                5 + level.random.nextInt(3));
    }

    /**
     * Puts the deep end of a spring in, well below anything that can disturb it.
     *
     * @param at     the surface point the line belongs to
     * @param groundY surface height there, which fixes how deep the line is seated
     */
    static BlockPos place(ServerLevel level, BlockPos at, int groundY, int maxStage) {
        // Seated below the water table, since that is what feeds it: as deep as usual, or deeper under dry ground
        // where the table is far down. Pulled up where the world floor is close; refused only if it cannot sit
        // deeper than a quake digs.
        int table = com.jeladastudios.ftsgeology.hydrology.WaterTable.tableY(level, at.getX(), at.getZ());
        int y = Math.max(Math.min(groundY - SOURCE_DEPTH, table - 20), level.getMinBuildHeight() + 5);
        if (groundY - y < MIN_SOURCE_DEPTH) return null;           // too shallow a world here
        BlockPos src = new BlockPos(at.getX(), y, at.getZ());
        BlockState existing = level.getBlockState(src);
        if (existing.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(existing)) return null;

        buildReservoir(level, src, ModBlocks.SPRING_SOURCE.get().defaultBlockState(), 2, 3);
        if (level.getBlockEntity(src) instanceof SpringSourceBlockEntity be) {
            be.setMaxStage(maxStage);
        }
        return src;
    }

    /**
     * The reservoir under a geothermal feature: rock floor, solid magma heat bed, a walled body of water
     * and a natural rock cap. A spring and a geyser share it; a geyser's conduit is simply constricted.
     */
    static void buildReservoir(ServerLevel level, BlockPos core, BlockState coreBlock,
                               int rad, int chamberH) {
        int waterDepth = Math.max(1, chamberH - 1);

        // Containment floor, then a SOLID magma bed. Fluid lava would mix with the chamber water
        // into cobblestone, or drain away into a cave, and take the heat with it.
        fillLayer(level, core.below(2), rad + 1, Blocks.DEEPSLATE);
        fillLayer(level, core.below(1), rad, Blocks.MAGMA_BLOCK);
        MagmaSealing.sealSlab(level, core.below(1), rad);

        // Core level: a rock ring keeping the heat off the water, with the core in the middle.
        fillLayer(level, core, rad, Blocks.DEEPSLATE);
        level.setBlock(core, coreBlock, FLAGS);

        // The water itself, walled so it cannot leak into a cave alongside.
        for (int dy = 1; dy <= chamberH; dy++) {
            fillLayer(level, core.above(dy), rad, dy <= waterDepth ? Blocks.WATER : Blocks.AIR);
            ringWall(level, core.above(dy), rad + 1);
        }
    }

    /** Lays the microbial colour bands around a finished pool. Called by the spring line. */
    public static void paintRings(ServerLevel level, List<BlockPos> pool, int cx, int cz, int waterY, int stage) {
        paintThermalRings(level, pool, cx, cz, waterY, stage);
    }

    /**
     * Strips the canopy off a spring site. The site is taken outright near the pool; further out more
     * trees are spared, whole, decided by a smooth field so a tree is never half cleared.
     */
    public static void clearCanopy(ServerLevel level, int cx, int cz, int radius) {
        double solid = radius * 0.5;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet stripped = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet trunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (dist > radius) continue;
                int gx = cx + dx, gz = cz + dz;
                int g = TerrainProbe.groundY(level, gx, gz);
                if (g == Integer.MIN_VALUE) continue;

                // The further out, the likelier a whole tree survives.
                double out = Mth.clamp((dist - solid) / Math.max(1.0, radius - solid), 0.0, 1.0);
                if (dist > solid && spareField(gx, gz) < out) continue;
                stripped.add(columnKey(gx, gz));

                // Walk only as high as something stands here; a huge mushroom counts.
                int top = Math.min(g + 40, level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz));
                for (int y = g + 1; y <= top; y++) {
                    BlockPos p = new BlockPos(gx, y, gz);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    if (!TerrainProbe.isTreePart(s) && !TerrainProbe.isVegetation(s)) break;   // something real: stop
                    if (s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(Blocks.MUSHROOM_STEM)) trunks.add(columnKey(gx, gz));
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
        }
        // The crowns of the trees cut here, over the columns left standing round them.
        int reach = TerrainProbe.LEAF_REACH;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet looked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long t : trunks) {
            int tx = (int) (t >> 32), tz = (int) t;
            for (int ox = -reach; ox <= reach; ox++) {
                for (int oz = -reach; oz <= reach; oz++) {
                    long k = columnKey(tx + ox, tz + oz);
                    if (stripped.contains(k) || !looked.add(k)) continue;
                    TerrainProbe.dropLooseCrowns(level, tx + ox, tz + oz);
                }
            }
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * A smooth 0..1 field used to decide whether a tree is spared, coherent over roughly twenty
     * blocks so a whole tree lands on one side of the threshold.
     */
    static double spareField(int x, int z) {
        double n = Math.sin(x * 0.17) * Math.cos(z * 0.21)
                + 0.5 * Math.sin((x + z) * 0.09)
                + 0.35 * Math.sin((x - z) * 0.29);
        return Mth.clamp((n + 1.85) / 3.7, 0.0, 1.0);
    }

    /**
     * The microbial colour bands around a pool. Each temperature range has its own pigmented mats, so the
     * rings are a thermometer, as at Grand Prismatic. Widths are rolled per spring and edges wobble. The mats
     * keep to the flat apron; up a bank behind the pool the same rings show as stained, altered ground.
     */
    static void paintThermalRings(ServerLevel level, List<BlockPos> pool,
                                          int cx, int cz, int waterY, int stage) {
        if (pool.isEmpty()) return;

        // Outward from the water: sinter shelf, a narrow green fringe, yellow, orange, and brown at the dry
        // edge. Older springs have more bands; a young one has only its own deposit.
        int[] band = {
                1 + level.random.nextInt(2),   // the sinter shelf
                stage >= 2 ? 1 + level.random.nextInt(2) : 0,   // green, the shallow fringe
                stage >= 3 ? 2 + level.random.nextInt(3) : 0,   // yellow
                stage >= 4 ? 2 + level.random.nextInt(3) : 0,   // orange
                stage >= 4 ? 2 + level.random.nextInt(4) : 0,   // brown, coolest and driest
        };
        int bandReach = 0;
        for (int b : band) bandReach += b;
        // Past the last mat, a sterile halo of poisoned crust that thins into the ground.
        int halo = stage >= 4 ? 5 + level.random.nextInt(6) : 0;
        int reach = bandReach + halo;
        double phase = level.random.nextDouble() * Math.PI * 2;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : pool) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }

        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                double nearest = Double.MAX_VALUE;
                for (BlockPos p : pool) {
                    double dx = x - p.getX(), dz = z - p.getZ();
                    nearest = Math.min(nearest, dx * dx + dz * dz);
                }
                double d = Math.sqrt(nearest);
                if (d < 0.9) continue;                       // that is the pool itself

                // Irregular edges, so the rings are not a bullseye.
                double ang = Math.atan2(z - cz, x - cx);
                double wobble = 1.0 + 0.28 * Math.sin(3 * ang + phase)
                        + 0.15 * Math.sin(5 * ang - phase);
                double scaled = d / Math.max(0.4, wobble);
                Block b = bandFor(scaled, band);
                // Past the last mat: the sterile halo, thinning out into the countryside.
                boolean inHalo = false;
                if (b == null) {
                    double out = (scaled - bandReach) / halo;   // 0 at the brown edge, 1 outside
                    if (out < 0.0 || out > 1.0) continue;
                    // Thins outward, so the crust breaks up instead of drawing another ring.
                    if (level.random.nextDouble() > 1.0 - out) continue;
                    b = haloBlock(level.random);
                    inHalo = true;
                }

                int g = TerrainProbe.groundY(level, x, z);
                if (g == Integer.MIN_VALUE) continue;
                int flat = inHalo ? 4 : 2;
                if (g - waterY < -flat) continue;
                // Mats keep to the flat apron, since they live in the film of water running off it. Up a bank no
                // water runs, and steam and acid stain the ground instead: bleached white, sulfur yellow, iron
                // orange and red, as on the walls of the Grand Canyon of the Yellowstone. Thinning with height.
                boolean bank = g - waterY > flat;
                if (bank) {
                    int rise = g - waterY;
                    if (rise > BANK_REACH
                            || level.random.nextDouble() < (rise - flat) / (double) (BANK_REACH - flat + 1)) continue;
                    b = altered(b, level.random);
                }
                // Never paint the floor under standing water, such as a neighbouring pool's bed.
                if (!level.getBlockState(new BlockPos(x, g + 1, z)).getFluidState().isEmpty()) continue;
                BlockPos p = new BlockPos(x, g, z);
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) continue;
                if (!s.getFluidState().isEmpty()) continue;
                TerrainProbe.clearVegetation(level, x, g, z, 2);
                level.setBlock(p, com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, p, b.defaultBlockState()), FLAGS);
                if (bank) stainStepFace(level, x, g, z, b);
            }
        }
    }

    /**
     * How far above the water line the stained ground climbs a bank. Ten blocks of orange, red and brown terracotta
     * up every bank made a basin a patchwork of twenty kinds of block; a basin floor is white and grey with the
     * colour kept to the water's edge.
     */
    private static final int BANK_REACH = 4;

    /** A band's colour as hydrothermally altered ground instead of a mat: bleached white, a little sulfur yellow. */
    static Block altered(Block band, net.minecraft.util.RandomSource rng) {
        if (band == ModBlocks.SINTER.get()) return rng.nextBoolean() ? Blocks.CALCITE : Blocks.WHITE_TERRACOTTA;
        if (band == ModBlocks.MICROBIAL_MAT_GREEN.get() || band == ModBlocks.MICROBIAL_MAT_YELLOW.get()) {
            int r = rng.nextInt(3);
            return r == 0 ? Blocks.COARSE_DIRT : r == 1 ? Blocks.YELLOW_TERRACOTTA : ModBlocks.SINTER_CRUST.get();
        }
        if (band == ModBlocks.MICROBIAL_MAT_ORANGE.get() || band == ModBlocks.MICROBIAL_MAT_BROWN.get()) {
            int r = rng.nextInt(4);
            return r == 0 ? Blocks.COARSE_DIRT : r < 3 ? Blocks.GRAVEL : ModBlocks.SINTER_CRUST.get();
        }
        return band;
    }

    /**
     * On a bank of one-block steps the face of each step shows too. Where a neighbour stands two or more
     * lower, the block under the stained top takes the same colour, so the bank is not striped with soil.
     */
    private static void stainStepFace(ServerLevel level, int x, int g, int z, Block b) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            int n = TerrainProbe.groundY(level, x + d.getStepX(), z + d.getStepZ());
            if (n == Integer.MIN_VALUE || n > g - 2) continue;
            BlockPos under = new BlockPos(x, g - 1, z);
            BlockState s = level.getBlockState(under);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.is(Blocks.BEDROCK)
                    || EruptionHandler.isPlayerPlaced(s)) return;
            level.setBlock(under, com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.translate(level, under, b.defaultBlockState()), FLAGS);
            return;
        }
    }

    /** Halo crust: pale, dry, broken ground made of existing blocks. */
    static Block haloBlock(net.minecraft.util.RandomSource rng) {
        int r = rng.nextInt(10);
        if (r < 2) return Blocks.COARSE_DIRT;
        if (r < 5) return Blocks.GRAVEL;
        if (r < 8) return ModBlocks.SINTER.get();
        return Blocks.TUFF;
    }

    /** Which band a given distance from the water falls in, or null past the last one. */
    static Block bandFor(double d, int[] band) {
        double edge = band[0];
        if (d <= edge) return ModBlocks.SINTER.get();
        edge += band[1];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_GREEN.get();
        edge += band[2];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_YELLOW.get();
        edge += band[3];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_ORANGE.get();
        edge += band[4];
        if (d <= edge) return ModBlocks.MICROBIAL_MAT_BROWN.get();
        return null;
    }
}
