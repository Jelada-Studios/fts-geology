package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.volcano.VolcanoPlan.Ctx;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import static com.jeladastudios.ftsgeology.volcano.OceanEdifice.*;

/** Writes an island's columns and chooses their blocks: pillow lava, rubble, black sand, soil, reef and the trees on top. The shape comes from OceanEdifice. */
public final class OceanColumn {

    private OceanColumn() {}

    // === Writing ============================================================

    /** Writes one column of the island. Reads and writes nothing outside the column. */
    static void column(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng) {
        Isle k = c.isle;
        int dx = gx - c.x, dz = gz - c.z;
        if ((long) dx * dx + (long) dz * dz > (long) (k.reach + 1) * (k.reach + 1)) return;
        int bed = seabed(level, gx, gz);
        if (bed == Integer.MIN_VALUE) return;
        Probe p = new Probe();
        double shaped = shape(c, gx, gz, p);
        int water = k.seaY - 1;
        if (p.r > k.shoreR0 && bed < k.floorY) {
            // The flank under the sea runs down to the floor the plan was made on. Where the real floor lies
            // deeper, the flank follows it down instead of ending on a shelf with a wall under it.
            double u = Mth.clamp((p.r - k.shoreR0) / Math.max(1.0, k.footR - k.shoreR0), 0.0, 1.0);
            shaped += (bed - k.floorY) * smooth(u);
        }
        int target = (int) Math.round(shaped);
        // Pillow lava piles up in lumps on a young flank.
        if (k.setting == VolcanoSetting.ISLAND && target < water - 12 && pillow(k, gx, gz)) target++;
        if (target < bed && c.type == VolcanoType.CALDERA && (p.bits & LAGOON) != 0
                && !level.getBlockState(new BlockPos(gx, bed + 1, gz)).getFluidState().isEmpty()) {
            // The collapse took the floor down with it: a flooded caldera is dug below the sea floor round it.
            for (int y = bed; y > target; y--) {
                BlockPos pos = new BlockPos(gx, y, gz);
                BlockState s = level.getBlockState(pos);
                if (s.is(Blocks.BEDROCK) || com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s)) break;
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            VolcanoSummit.setRock(level, new BlockPos(gx, target, gz), surface(c, k, rng, gx, target, gz, p.bits, 0.0, p));
            return;
        }
        double spread = apron(k, p), rubble = debris(k, gx, gz, p);
        double laid = Math.max(spread, rubble);
        boolean onBed = bed + (int) Math.round(laid) > target;
        if (onBed) target = bed + (int) Math.round(laid);
        if (target <= bed) return;

        int surfaceTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz);
        boolean dry = bed >= k.seaY && level.getBlockState(new BlockPos(gx, bed + 1, gz)).getFluidState().isEmpty();
        double slope = onBed ? 0.0 : slope(c, gx, gz);
        int bits = onBed ? 0 : p.bits;
        // Towards its outer edge the apron keeps the sea floor's own top, so it thins into the floor instead of
        // ending on a line.
        BlockState floorTop = onBed && rubble < spread && rng.nextDouble() < apronShare(k, p) * 0.9
                ? level.getBlockState(new BlockPos(gx, bed, gz)) : null;
        for (int y = bed + 1; y <= target; y++) {
            BlockState b = y < target ? body(c, k, rng, gx, y, gz, bits)
                    : floorTop != null ? floorTop : surface(c, k, rng, gx, y, gz, bits, slope, p);
            VolcanoSummit.setRock(level, new BlockPos(gx, y, gz), b);
        }
        if ((bits & POND) != 0) {
            // The young cone's crater, on a basalt floor, where the summit step will seat the core: molten on a live
            // cone, crusted over on a sleeping one.
            VolcanoSummit.setRock(level, new BlockPos(gx, target - 1, gz), Blocks.BASALT.defaultBlockState());
            VolcanoSummit.setRock(level, new BlockPos(gx, target, gz), c.activity == VolcanoActivity.DORMANT
                    ? (rng.nextBoolean() ? Blocks.BLACKSTONE : Blocks.BASALT).defaultBlockState()
                    : Blocks.LAVA.defaultBlockState());
        }
        if ((bits & (REEF | RIM)) != 0 && target < water && rng.nextInt(4) == 0) {
            BlockPos above = new BlockPos(gx, target + 1, gz);
            if (level.getBlockState(above).is(Blocks.WATER)) level.setBlock(above, coralPlant(gx, gz, rng), 2);
        }
        clearAbove(level, gx, target + 1, gz, Math.max(surfaceTop, target + 1), k.seaY);
        if (dry) VolcanoEdifice.clearCover(level, gx, target, gz);
    }

    /** The largest height difference across a column's shaped neighbours, per block. */
    static double slope(Ctx c, int gx, int gz) {
        Probe q = new Probe();
        double east = shape(c, gx + 1, gz, q), west = shape(c, gx - 1, gz, q);
        double south = shape(c, gx, gz + 1, q), north = shape(c, gx, gz - 1, q);
        return Math.max(Math.abs(east - west), Math.abs(south - north)) * 0.5;
    }

    /** True on a pillow: lumps a few blocks apart, from a jittered grid, so neighbouring chunks agree. */
    static boolean pillow(Isle k, int gx, int gz) {
        int cx = Math.floorDiv(gx, 5), cz = Math.floorDiv(gz, 5);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                long h = SeedHash.hash(k.noise, cx + ox, cz + oz, 0x9111L);
                double px = (cx + ox) * 5 + 1 + SeedHash.rand01(h) * 3;
                double pz = (cz + oz) * 5 + 1 + SeedHash.rand01(SeedHash.mix(h)) * 3;
                double ex = gx + 0.5 - px, ez = gz + 0.5 - pz;
                if (ex * ex + ez * ez < 2.3) return true;
            }
        }
        return false;
    }

    /** The first real ground under the sea or under an iceberg, or {@link Integer#MIN_VALUE}. */
    public static int seabed(LevelAccessor level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = top; y > level.getMinBuildHeight() && y > top - 200; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty() || frozen(s) || loose(s)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean frozen(BlockState s) {
        return s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE) || s.is(Blocks.SNOW_BLOCK);
    }

    private static boolean loose(BlockState s) {
        return TerrainProbe.isVegetation(s) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS);
    }

    /** Over a column's new top: sea up to the sea level, nothing above it; ice and plants left there go. */
    static void clearAbove(LevelAccessor level, int x, int from, int z, int to, int seaY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = from; y <= Math.min(to, from + 64); y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            boolean clear = frozen(s) || loose(s);
            if (y < seaY) {
                if (s.isAir() || clear) level.setBlock(m, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                else if (s.getFluidState().isEmpty()) return;
            } else {
                if (clear) level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                else if (!s.isAir()) return;
            }
        }
    }

    // === Plants =============================================================

    /** In one column of an island's dry ground in a hundred, roughly, a tree; oftener in a warm sea. */
    private static final int TREE_ODDS_WARM = 26, TREE_ODDS = 40;

    /**
     * Trees and plants on the dry ground an island laid in this chunk. An ocean biome grows none, so the island
     * does: forest in a warm sea, oak and birch in a temperate one, spruce in a cold one, nothing on a frozen one,
     * where the biome's snow comes instead. Trunks stay three blocks inside the chunk, so no crown reaches a
     * neighbour that is built after it.
     */
    static void plant(net.minecraft.world.level.WorldGenLevel level,
                      net.minecraft.world.level.chunk.ChunkGenerator generator, Ctx c,
                      net.minecraft.world.level.ChunkPos cp, long seed) {
        Isle k = c.isle;
        if (k.setting == VolcanoSetting.GUYOT || k.seaTemp < -0.45) return;
        net.minecraft.core.Registry<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> features =
                level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        RandomSource rng = RandomSource.create(0L);
        long reach2 = (long) k.edifice * k.edifice;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int gx = cp.getMinBlockX() + lx, gz = cp.getMinBlockZ() + lz;
                long dx = gx - c.x, dz = gz - c.z;
                if (dx * dx + dz * dz > reach2) continue;
                int g = TerrainProbe.groundY(level, gx, gz);
                if (g < k.seaY + 1) continue;
                BlockPos ground = new BlockPos(gx, g, gz);
                if (!level.getBlockState(ground).is(Blocks.GRASS_BLOCK) || !level.getBlockState(ground.above()).isAir()) continue;
                rng.setSeed(SeedHash.columnSeed(seed ^ 0x7EE5L, gx, gz));
                boolean inside = lx >= 3 && lx <= 12 && lz >= 3 && lz <= 12;
                int odds = k.seaTemp > REEF_TEMPERATURE ? TREE_ODDS_WARM : TREE_ODDS;
                if (inside && rng.nextInt(odds) == 0) {
                    features.getHolder(treeFor(k, rng)).ifPresent(tree ->
                            tree.value().place(level, generator, rng, ground.above()));
                    continue;
                }
                int roll = rng.nextInt(12);
                if (roll < 2) level.setBlock(ground.above(), Blocks.GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                else if (roll == 2 && k.seaTemp > -0.15) {
                    level.setBlock(ground.above(), Blocks.FERN.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Which tree grows on an island, by how warm its sea is. */
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>>
            treeFor(Isle k, RandomSource rng) {
        int roll = rng.nextInt(10);
        if (k.seaTemp > 0.55) {
            return roll < 6 ? net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_TREE_NO_VINE
                    : net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_BUSH;
        }
        if (k.seaTemp > REEF_TEMPERATURE) {
            return roll < 5 ? net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_BUSH
                    : net.minecraft.data.worldgen.features.TreeFeatures.OAK;
        }
        if (k.seaTemp > -0.15) {
            return roll < 6 ? net.minecraft.data.worldgen.features.TreeFeatures.OAK
                    : net.minecraft.data.worldgen.features.TreeFeatures.BIRCH;
        }
        return net.minecraft.data.worldgen.features.TreeFeatures.SPRUCE;
    }

    // === Blocks =============================================================

    /** Rock inside the island, by depth: thin flows above the sea, glassy rubble round the shore, pillow lava below. */
    static BlockState body(Ctx c, Isle k, RandomSource rng, int gx, int y, int gz, int bits) {
        int water = k.seaY - 1;
        if ((bits & KAMENI) != 0) {
            // Dark young lava.
            return (rng.nextInt(3) == 0 ? Blocks.BASALT : Blocks.BLACKSTONE).defaultBlockState();
        }
        if (c.type == VolcanoType.CALDERA && y > water - 4) {
            // The ring's cliffs show the eruptions that built it in beds: lava, red scoria, grey tuff, and on top the
            // white pumice of the one that emptied it. The beds dip gently away from the vent, swell and thin, and
            // pinch out into the tuff round them.
            int cap = 1 + (int) Math.round(3.0 * (0.5 + 0.5 * ValueNoise.noise(gx + 7 * k.noise, gz, 23.0)));
            if (y > k.seaY + k.h0 - cap) {
                return (rng.nextInt(5) == 0 ? Blocks.TUFF : Blocks.CALCITE).defaultBlockState();
            }
            double out = Math.hypot(gx - c.x, gz - c.z) - k.ringR;
            double v = y + 0.12 * out + 2.5 * ValueNoise.noise(gx - k.noise, gz, 40.0)
                    + ValueNoise.noise(gx, gz + k.noise, 13.0);
            double thick = 3.0 + ValueNoise.noise(gx + 3 * k.noise, gz - 3 * k.noise, 60.0);
            int band = Math.floorMod((int) Math.floor(v / thick), 6);
            if (band != 0 && ValueNoise.noise(gx + 17 * band * k.noise, gz - 13 * band, 28.0) < -0.45) band = 0;
            int roll = rng.nextInt(10);
            Block b = switch (band) {
                case 1 -> roll < 7 ? Blocks.BLACKSTONE : Blocks.BASALT;
                case 3 -> roll < 5 ? Blocks.RED_TERRACOTTA : roll < 8 ? Blocks.BROWN_TERRACOTTA : Blocks.TUFF;
                case 4 -> roll < 6 ? Blocks.SMOOTH_BASALT : roll < 8 ? Blocks.BASALT : Blocks.TUFF;
                default -> roll < 9 ? Blocks.TUFF : Blocks.ANDESITE;
            };
            return b.defaultBlockState();
        }
        if ((k.setting == VolcanoSetting.ATOLL && y >= water - 14) || ((bits & (REEF | RIM | MOTU)) != 0 && y >= water - 8)) {
            // Reef limestone.
            return (rng.nextInt(4) == 0 ? Blocks.SAND : Blocks.CALCITE).defaultBlockState();
        }
        if ((bits & (CONE | RING)) != 0 && y > water - 2) {
            return (rng.nextInt(4) == 0 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
        }
        boolean cone = c.type == VolcanoType.STRATOVOLCANO;
        int roll = rng.nextInt(20);
        if (y > water + 1 && (bits & DELTA) == 0) {
            return cone ? VolcanoEdifice.stratoRock(rng, c, gx, y, gz)
                    : (roll < 7 ? Blocks.SMOOTH_BASALT : Blocks.BASALT).defaultBlockState();
        }
        if (y >= water - 12) {
            if (cone) return (roll < 9 ? Blocks.TUFF : roll < 14 ? Blocks.ANDESITE : roll < 18 ? Blocks.GRAVEL
                    : ModBlocks.VOLCANIC_BLACK_SAND.get()).defaultBlockState();
            return roll < 8 ? Blocks.TUFF.defaultBlockState()
                    : roll < 12 ? ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState()
                    : (roll < 15 ? Blocks.GRAVEL : Blocks.BASALT).defaultBlockState();
        }
        if (cone) return (roll < 8 ? Blocks.TUFF : roll < 15 ? Blocks.ANDESITE : roll < 18 ? Blocks.GRAVEL
                : Blocks.BASALT).defaultBlockState();
        return (roll < 10 ? Blocks.BASALT : roll < 16 ? Blocks.SMOOTH_BASALT : roll < 18 ? Blocks.BLACKSTONE
                : Blocks.TUFF).defaultBlockState();
    }

    /** A column's top block. */
    static BlockState surface(Ctx c, Isle k, RandomSource rng, int gx, int y, int gz, int bits, double slope, Probe p) {
        int water = k.seaY - 1;
        boolean young = k.setting == VolcanoSetting.ISLAND;
        if ((bits & KAMENI) != 0) {
            int roll = rng.nextInt(10);
            return roll < 6 ? Blocks.BLACKSTONE.defaultBlockState()
                    : roll < 9 ? Blocks.BASALT.defaultBlockState() : VolcanoEdifice.flowRock(c);
        }
        if (c.type == VolcanoType.CALDERA && (bits & LAGOON) != 0) {
            // The drowned caldera floor: ash, pumice gravel and black sand.
            int roll = rng.nextInt(10);
            return roll < 4 ? Blocks.TUFF.defaultBlockState()
                    : roll < 7 ? Blocks.GRAVEL.defaultBlockState() : ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState();
        }
        if ((bits & DELTA) != 0) return rng.nextInt(5) < 3 ? VolcanoEdifice.flowRock(c) : Blocks.BASALT.defaultBlockState();
        if ((bits & CONE) != 0) {
            return rng.nextInt(3) == 0 ? ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState() : Blocks.TUFF.defaultBlockState();
        }
        if ((bits & RING) != 0) {
            return (y > water + 1 && slope < 0.9 && rng.nextInt(3) > 0 ? Blocks.GRASS_BLOCK : Blocks.TUFF).defaultBlockState();
        }
        if ((bits & MOTU) != 0) return (y >= water + 2 ? Blocks.GRASS_BLOCK : Blocks.SAND).defaultBlockState();
        if ((bits & (REEF | RIM)) != 0) {
            if (y > water) return Blocks.SAND.defaultBlockState();
            if (y >= water - 1) {
                // Awash: rubble, sand and coral killed by the sun at low water. The living reef is below.
                int roll = rng.nextInt(10);
                return roll < 4 ? deadCoral(gx, gz) : (roll < 8 ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
            }
            return y < water - 14 ? deadCoral(gx, gz) : coral(gx, gz);
        }
        if ((bits & LAGOON) != 0) return (rng.nextInt(8) == 0 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState();
        if ((bits & TOP) != 0) {
            // The planed top keeps the shallow-water limestone and sand that capped it before it drowned.
            int roll = rng.nextInt(10);
            if (roll < 4) return Blocks.SAND.defaultBlockState();
            if (roll < 6) return Blocks.CALCITE.defaultBlockState();
            return roll < 9 ? deadCoral(gx, gz) : Blocks.GRAVEL.defaultBlockState();
        }
        if (y >= water + 2) {
            BlockState dry = land(c, k, rng, gx, y, gz, slope, p);
            // A young island's black sand runs well up behind the strand and grows over along a ragged line, as at
            // Reynisfjara: bare at the waves, dune grass and scrub behind.
            return young && c.type != VolcanoType.CALDERA && slope < 1.6
                    ? VolcanoEdifice.shoreSkin(k.seaY, rng, gx, y, gz, dry, 3.5, 2.0, 4.0) : dry;
        }
        // A caldera's island is mostly pumice and ash, pale down to the water and under it.
        boolean pumice = c.type == VolcanoType.CALDERA;
        if (y >= water - 1) {
            // The strand, or boulders where the shore is steep.
            if (slope >= 0.9) {
                int roll = rng.nextInt(10);
                if (pumice) return (roll < 5 ? Blocks.TUFF : roll < 8 ? Blocks.ANDESITE : Blocks.GRAVEL).defaultBlockState();
                return (roll < 4 ? Blocks.BASALT : roll < 6 ? Blocks.BLACKSTONE : roll < 8 ? Blocks.GRAVEL
                        : Blocks.SMOOTH_BASALT).defaultBlockState();
            }
            if (pumice) {
                int roll = rng.nextInt(10);
                return (roll < 4 ? Blocks.SAND : roll < 7 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
            }
            if (young) return ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState();
            // Old reefs grind down to white sand; a cold sea leaves shingle and black sand.
            if (k.warm()) return Blocks.SAND.defaultBlockState();
            return rng.nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState();
        }
        if (y >= water - 12) {
            int roll = rng.nextInt(10);
            if (pumice) return (roll < 4 ? Blocks.GRAVEL : roll < 7 ? Blocks.SAND : Blocks.TUFF).defaultBlockState();
            if (young) return roll < 5 ? ModBlocks.VOLCANIC_BLACK_SAND.get().defaultBlockState()
                    : (roll < 7 ? Blocks.TUFF : Blocks.BASALT).defaultBlockState();
            if (k.warm()) return (roll < 7 ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
            return (roll < 5 ? Blocks.GRAVEL : roll < 8 ? Blocks.SAND : Blocks.CLAY).defaultBlockState();
        }
        // Deep: fresh pillows on a live island, a drape of sediment thickening with age on an old one. A guyot's
        // gentler flanks are all under sediment; the basalt shows only where it is steep.
        if (!young && (rng.nextDouble() < 0.35 + 0.5 * k.age || (k.setting == VolcanoSetting.GUYOT && slope < 1.0))) {
            int roll = rng.nextInt(10);
            return (roll < 5 ? Blocks.SAND : roll < 8 ? Blocks.CLAY : Blocks.GRAVEL).defaultBlockState();
        }
        int roll = rng.nextInt(20);
        if (pumice) return (roll < 10 ? Blocks.TUFF : roll < 16 ? Blocks.GRAVEL : Blocks.BASALT).defaultBlockState();
        if (c.type == VolcanoType.STRATOVOLCANO) {
            return (roll < 9 ? Blocks.TUFF : roll < 15 ? Blocks.ANDESITE : Blocks.GRAVEL).defaultBlockState();
        }
        return (roll < 11 ? Blocks.BASALT : roll < 18 ? Blocks.SMOOTH_BASALT : Blocks.BLACKSTONE).defaultBlockState();
    }

    /** Dry ground: a live shield's skin and flows, a stratocone's, or an old island weathered to soil to its top. */
    static BlockState land(Ctx c, Isle k, RandomSource rng, int gx, int y, int gz, double slope, Probe p) {
        BlockState rock = body(c, k, rng, gx, y, gz, 0);
        // Cliffs and valley walls show the lava beds they were cut through; an old island is soil to a steeper pitch.
        if (slope >= (k.setting == VolcanoSetting.ISLAND ? 1.6 : 2.0)) return rock;
        if (c.type == VolcanoType.CALDERA) {
            // The ring's outer slopes: grass on weathered ash, and here and there pale pumice where the cover is thin,
            // in small ragged patches with ash and scree round them.
            if (slope >= 1.0 && rng.nextInt(3) == 0) return rock;
            double n = ValueNoise.noise(gx + 11 * k.noise, gz, 24.0) + 0.3 * ValueNoise.noise(gx - 5 * k.noise, gz, 5.0);
            if (n < -0.65) {
                int roll = rng.nextInt(10);
                return (roll < 5 ? Blocks.CALCITE : roll < 8 ? Blocks.TUFF : Blocks.WHITE_TERRACOTTA).defaultBlockState();
            }
            if (n < -0.38) return (rng.nextInt(3) == 0 ? Blocks.TUFF : Blocks.COARSE_DIRT).defaultBlockState();
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        double h = (y - (k.seaY - 1)) / (double) Math.max(1, k.h0);
        if (k.setting == VolcanoSetting.ISLAND) {
            if (c.type == VolcanoType.STRATOVOLCANO) return VolcanoEdifice.stratoSkin(rng, c, gx, gz, h, rock);
            if (VolcanoEdifice.flowAt(c, p.ang, p.r)) return VolcanoEdifice.flowRock(c);
            return VolcanoEdifice.shieldSkin(rng, c, gx, gz, h, rock);
        }
        // An old island, cone or shield, has weathered to soil to its top.
        if (slope >= 1.0 && rng.nextInt(3) == 0) return rock;
        double n = ValueNoise.noise(gx + 7 * k.noise, gz, 30.0);
        if (n < -0.55) return Blocks.COARSE_DIRT.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    /** A living reef block, one kind in patches a few blocks across. */
    static BlockState coral(int gx, int gz) {
        Block b = switch (coralKind(gx, gz)) {
            case 0 -> Blocks.TUBE_CORAL_BLOCK;
            case 1 -> Blocks.BRAIN_CORAL_BLOCK;
            case 2 -> Blocks.BUBBLE_CORAL_BLOCK;
            case 3 -> Blocks.FIRE_CORAL_BLOCK;
            default -> Blocks.HORN_CORAL_BLOCK;
        };
        return b.defaultBlockState();
    }

    static BlockState deadCoral(int gx, int gz) {
        Block b = switch (coralKind(gx, gz)) {
            case 0 -> Blocks.DEAD_TUBE_CORAL_BLOCK;
            case 1 -> Blocks.DEAD_BRAIN_CORAL_BLOCK;
            case 2 -> Blocks.DEAD_BUBBLE_CORAL_BLOCK;
            case 3 -> Blocks.DEAD_FIRE_CORAL_BLOCK;
            default -> Blocks.DEAD_HORN_CORAL_BLOCK;
        };
        return b.defaultBlockState();
    }

    static BlockState coralPlant(int gx, int gz, RandomSource rng) {
        boolean fan = rng.nextBoolean();
        Block b = switch (coralKind(gx, gz)) {
            case 0 -> fan ? Blocks.TUBE_CORAL_FAN : Blocks.TUBE_CORAL;
            case 1 -> fan ? Blocks.BRAIN_CORAL_FAN : Blocks.BRAIN_CORAL;
            case 2 -> fan ? Blocks.BUBBLE_CORAL_FAN : Blocks.BUBBLE_CORAL;
            case 3 -> fan ? Blocks.FIRE_CORAL_FAN : Blocks.FIRE_CORAL;
            default -> fan ? Blocks.HORN_CORAL_FAN : Blocks.HORN_CORAL;
        };
        return b.defaultBlockState();
    }

    private static int coralKind(int gx, int gz) {
        return (int) Math.floorMod(SeedHash.hash(0x0C0A1L, gx >> 2, gz >> 2, 0x5EAL), 5L);
    }
}
