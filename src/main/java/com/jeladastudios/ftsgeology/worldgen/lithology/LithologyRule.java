package com.jeladastudios.ftsgeology.worldgen.lithology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Column;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Rock;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.worldgen.terrain.ColumnClimate;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.jeladastudios.ftsgeology.worldgen.terrain.WorldgenRevision;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * {@link Lithology} as a surface rule, {@code fts_geology:lithology} in the mod's noise settings. Placed after vanilla's
 * soil and before its deepslate, it decides every block the generator would have left as plain stone, so the rock goes
 * down with the ground while the ground is built: nothing is written twice, and the caves, the ores and the cave growth
 * that come after it cut and fill the right rock. The mod's own biomes also use it for the bare rock at their surface,
 * so what shows on a cliff is what lies behind it.
 *
 * <p>With {@code steep_only} it answers only in columns whose ground climbs three blocks within two, and then for
 * every block, stone included. Placed before the soil rules, that leaves a cliff face bare in any biome, the way a real
 * cliff sheds its soil, and the rock it shows is the rock behind it. Vanilla's own steep test looks one way along each
 * axis, so it would have stripped a cliff that faced north and left the one facing south under grass; this one looks
 * both ways. Not all of it is face: see {@link #TALUS_SHARE}.</p>
 *
 * <p>With {@code bare} it answers in every column, stone included, whatever the slope: the rule above the tree line,
 * where a mountain carries no soil at all.</p>
 *
 * <p>In worlds made since {@link WorldgenRevision#GREENER_GROUND} the climate has its say: a wet slope under the tree line
 * holds its plants steeper ({@link #steepRise}), bare and green ground meet in patches rather than on a line, the scree
 * thins out from the tree line to the snow line, and low scree in a wet climate is partly grown over.</p>
 */
public record LithologyRule(boolean steepOnly, boolean bare) implements SurfaceRules.RuleSource {

    public static final KeyDispatchDataCodec<LithologyRule> CODEC = KeyDispatchDataCodec.of(
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.BOOL.optionalFieldOf("steep_only", false).forGetter(LithologyRule::steepOnly),
                    Codec.BOOL.optionalFieldOf("bare", false).forGetter(LithologyRule::bare)
            ).apply(i, LithologyRule::new)));

    /** How much the ground must climb across two blocks for a column to count as a cliff (worlds made before GREENER_GROUND). */
    private static final int STEEP_RISE = 3;

    /**
     * In worlds made since {@link WorldgenRevision#GREENER_GROUND}, the rise a column has to beat to go bare, for its
     * climate: {@code steepRise} where it is dry or over the tree line, up to {@code humidSteepRise} where it is wet and
     * well under it. A wet climate's slopes hold their plants far steeper than a dry one's -- Madeira's, the Na Pali
     * coast's, the Faroes' -- and none hold them over the tree line.
     */
    public static double steepRise(ColumnClimate.At climate, int x, int z, int ground) {
        double dry = GeyserConfig.STEEP_RISE.get(), humid = GeyserConfig.HUMID_STEEP_RISE.get();
        return dry + (humid - dry) * green(climate, x, z, ground);
    }

    /** How far a column's climate lets plants hold steep ground and scree: 1 wet and well under the tree line, 0 dry or over it. */
    public static double green(ColumnClimate.At climate, int x, int z, int ground) {
        double wet = Mth.clamp((climate.humidity() - WET_FROM) / (WET_FULL - WET_FROM), 0.0, 1.0);
        if (wet <= 0.0) return 0.0;
        double under = Mth.clamp((climate.treeLine(x, z, SEA) - ground) / TREE_LINE_FADE, 0.0, 1.0);
        return wet * under;
    }

    /** The climate's humidity a slope starts holding its plants at, and holds them fully at. */
    private static final double WET_FROM = -0.1, WET_FULL = 0.4;
    /** Blocks under the tree line over which plants come back to steep ground. */
    private static final double TREE_LINE_FADE = 12.0;
    private static final int SEA = 63;

    /**
     * How much of a steep slope is loose debris rather than bare face, low down, and how wide those patches are.
     *
     * <p>Measured against real Alpine ground in the twenty-first round: at ten metres to the block almost every
     * column of a mountainside climbs three within two, so the cliff rule fired on all of them and the whole flank
     * came out as one grey wall. A rock face does not run unbroken down a mountain. It breaks off, and what breaks
     * off piles at the foot at the angle of repose, so a real flank is bands of bare rock with scree between them.
     * The patch is a field rather than a die a column at a time, so the bare stretches are that many blocks across
     * and some of them are whole; and it fades out with {@link Column#high}, because debris gathers low and the
     * summits stay the bare rock the twenty-fourth round asked for.</p>
     */
    private static final double TALUS_SHARE = 0.75, TALUS_SCALE = 20.0, TALUS_GRAIN = 4.0;
    /** The size of the patches a slope near its threshold goes bare in, and old scree grows over in, in blocks. */
    private static final double BARE_GRAIN = 6.0, GROWN_SCALE = 9.0;
    /** How far under its threshold, in blocks of rise, a fully green place's slope may already go bare now and then. */
    private static final double SOFT_BAND = 1.5;

    /**
     * How far past a channel's flat bed the river still owns the ground it runs on, in fault widths of the
     * preset. The cut band itself scales with the world, so this has to as well: at three flat blocks the tall
     * world's band reached six and a quarter and the outer stretch of every channel wall fell through to the
     * cliff rule and came out as bare rock. That is why the bare stripe along a river was a tall-world thing.
     */
    private static final double RIVER_BANK = 3.0;

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }

    /** Called once a chunk. The context and its chunk are opened to the mod by META-INF/accesstransformer.cfg. */
    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        if (!GeyserConfig.LITHOLOGY.get()) return (x, y, z) -> null;
        Pass pass = new Pass(context.chunk, TerrainContext.seed(), steepOnly, bare);
        if (steepOnly && WorldgenRevision.has(WorldgenRevision.GREENER_GROUND)) {
            pass.climate = ColumnClimate.corners(context.chunk.getPos().getMinBlockX(), context.chunk.getPos().getMinBlockZ());
        }
        return pass;
    }

    /** One chunk's pass: each column worked out the first time one of its blocks is asked about. */
    private static final class Pass implements SurfaceRules.SurfaceRule {
        private final ChunkAccess chunk;
        private final long seed;
        private final boolean steepOnly, bare;
        private final Column[] columns = new Column[256];
        private final int[] grounds = new int[256];
        /** 0 not yet looked at, 1 gentle, 2 a cliff. */
        private final byte[] steep = new byte[256];
        /** 0 not yet looked at, 1 dry land, 2 a river's own ground. */
        private final byte[] river = new byte[256];
        /** 0 not yet looked at, 1 bare face, 2 scree, 3 scree grown over. */
        private final byte[] talus = new byte[256];
        /** The chunk's corners' climate, in worlds made since GREENER_GROUND; null before, and the old rules hold. */
        ColumnClimate.At[] climate;

        Pass(ChunkAccess chunk, long seed, boolean steepOnly, boolean bare) {
            this.chunk = chunk;
            this.seed = seed;
            this.steepOnly = steepOnly;
            this.bare = bare;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            int i = ((x & 15) << 4) | (z & 15);
            // A river's bed and banks belong to the river. Both bare rules answer for every block they are
            // asked about, so without this the channel wall -- which climbs two blocks for every block out,
            // and so is a cliff by construction -- came out as whatever rock lay behind it, and a river
            // through arc or hotspot ground ran in a blackstone trough with no gravel bed at all.
            if ((steepOnly || bare) && riverAt(i, x, z)) return null;
            if (steepOnly && !steepAt(i, x & 15, z & 15)) return null;
            Column c = columns[i];
            if (c == null) {
                c = Lithology.column(seed, TerrainContext.params(), x, z);
                columns[i] = c;
                // The ground as the noise left it, under any water: the floor of the sea is the top of its rock too.
                grounds[i] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15) - 1;
            }
            Rock r = Lithology.rockAt(seed, c, x, y, z, grounds[i]);
            // Scree, where the face has broken off: mostly gravel, with fragments of the very rock above it. The
            // grain is a slow field rather than a die a block at a time, so it reads as rubble and not as confetti.
            if (steepOnly && talusAt(i, x, z, c, grounds[i])) {
                // Under the tree line in a wet climate, scree grows over: grass, coarse earth and moss over the rubble,
                // as old talus does, and the plants the biome grows can root in it.
                if (talus[i] == 3 && grounds[i] >= SEA - 1) {
                    if (y == grounds[i]) return overgrown(x, z);
                    if (y == grounds[i] - 1) return States.DIRT;
                }
                return ValueNoise.noise(x + 313, z - 571, TALUS_GRAIN) > 0.35
                        ? States.ALL[(r == Rock.KEEP ? Rock.STONE : r).ordinal()]
                        : States.GRAVEL;
            }
            // On a cliff or above the tree line the block asked about would have become soil, so plain stone has to be
            // said out loud. Anywhere else it is what the block already is, and answering it would only pay for
            // writing it again.
            if (steepOnly || bare) return States.ALL[(r == Rock.KEEP ? Rock.STONE : r).ordinal()];
            return r == Rock.KEEP || r == Rock.STONE ? null : States.ALL[r.ordinal()];
        }

        /**
         * Whether the ground climbs {@link #STEEP_RISE} within two blocks of this column along either axis, either
         * way.
         *
         * <p>A neighbour past the chunk's edge cannot be read, so the column there is compared with itself and the
         * rise comes out over one block instead of two. Left as it was, that made the two columns of every chunk
         * border judge themselves half as steep as their neighbours: down a long cliff face the soil survived in
         * stripes sixteen blocks apart, which is most of why the face read as columns. Doubling the one-sided rise
         * puts them on the same ruler without reaching outside the chunk.</p>
         */
        private boolean steepAt(int i, int lx, int lz) {
            if (steep[i] == 0) {
                int west = height(lx - 1, lz), east = height(lx + 1, lz);
                int north = height(lx, lz - 1), south = height(lx, lz + 1);
                int spanX = (east - west) * (lx == 0 || lx == 15 ? 2 : 1);
                int spanZ = (south - north) * (lz == 0 || lz == 15 ? 2 : 1);
                boolean cliff;
                if (climate == null) {
                    cliff = Math.abs(spanX) >= STEEP_RISE || Math.abs(spanZ) >= STEEP_RISE;
                } else {
                    // Where plants can hold the slope, no hard line at the threshold: the chance of bare rock climbs over
                    // a band under it, wider the greener the place, by a field and not a die, so bare and green ground
                    // come in patches. Dry or over the tree line the line is where it always was.
                    int x = chunk.getPos().getMinBlockX() + lx, z = chunk.getPos().getMinBlockZ() + lz;
                    int rise = Math.max(Math.abs(spanX), Math.abs(spanZ));
                    ColumnClimate.At here = ColumnClimate.blend(climate, lx, lz);
                    int ground = height(lx, lz);
                    double need = steepRise(here, x, z, ground), band = SOFT_BAND * green(here, x, z, ground);
                    if (band <= 0.0) {
                        cliff = rise >= need;
                    } else {
                        double p = Mth.clamp((rise - (need - band)) / band, 0.0, 1.0);
                        cliff = p >= 1.0 || p > 0.0 && 0.5 + 0.5 * ValueNoise.noise(x - 1931, z + 4477, BARE_GRAIN) < p;
                    }
                }
                steep[i] = (byte) (cliff ? 2 : 1);
            }
            return steep[i] == 2;
        }

        /** Grass, coarse earth and mossy stones over old scree, by a field. */
        private static BlockState overgrown(int x, int z) {
            double n = ValueNoise.noise(x + 6151, z - 2711, TALUS_GRAIN);
            return n > 0.30 ? States.MOSSY : n > 0.15 ? States.COARSE : States.GRASS;
        }

        /**
         * Whether this steep column carries scree instead of a bare face.
         *
         * <p>The share is the most a slope can be debris and it falls to nothing at the tree line, so a summit is
         * the bare rock it should be and the foot of the mountain is the rubble it should be. The patch field is a
         * pure function of the place, so no chunk boundary shows in it -- the sixteen-block striping the steep test
         * itself once had came from reading the chunk, and this reads none.</p>
         */
        private boolean talusAt(int i, int x, int z, Column c, int ground) {
            if (talus[i] == 0) {
                double fade = 1.0 - c.high(ground);
                ColumnClimate.At here = null;
                if (climate != null) {
                    // Debris thins out from the tree line up and is gone at the snow line, where the summit is rock.
                    here = ColumnClimate.blend(climate, x & 15, z & 15);
                    int tree = here.treeLine(x, z, SEA), snow = here.snowLine(x, z, SEA);
                    fade = Math.min(fade, snow <= tree ? (ground < tree ? 1.0 : 0.0)
                            : Mth.clamp((snow - ground) / (double) (snow - tree), 0.0, 1.0));
                }
                double share = TALUS_SHARE * fade;
                boolean scree = share > 0.0 && ValueNoise.noise(x + 8171, z + 2333, TALUS_SCALE) > screeCut(share);
                byte kind = (byte) (scree ? 2 : 1);
                if (scree && here != null) {
                    double grown = GeyserConfig.TALUS_GREENING.get() * green(here, x, z, ground);
                    if (grown > 0.0 && 0.5 + 0.5 * ValueNoise.noise(x - 3407, z + 6113, GROWN_SCALE) < grown) kind = 3;
                }
                talus[i] = kind;
            }
            return talus[i] >= 2;
        }

        /**
         * The value the field has to beat for a column to be scree, for a wanted share of the ground.
         *
         * <p>It is the field's own quantile, not a fraction of its range. Smoothed value noise bunches round zero:
         * counted over four million points, a fifth of the ground is above 0.33, half of it above 0.00 and three
         * fifths above -0.12, so asking for a third by halving the range gave an eighth, and the first cut of this
         * rule put scree on fourteen per cent of the steep ground where it meant to put it on a third. The curve
         * below follows the measured quantile to within three points of share, and reaches one at share zero, so
         * a summit stays whole.</p>
         */
        private static double screeCut(double share) {
            // Held at three fifths: past that the curve turns back up and would ask for less scree the more it
            // wanted, and half a slope of rubble is as far as this should go in any case.
            double s = Math.min(share, 0.60);
            return 1.0 - 3.30 * s + 2.51 * s * s;
        }

        /** Whether a traced river's channel or bank covers this column. */
        private boolean riverAt(int i, int x, int z) {
            if (river[i] == 0) {
                RiverNetwork.At a = RiverNetwork.at(x, z);
                boolean wet = a.within(RIVER_BANK * TerrainContext.params().horizontal());
                river[i] = (byte) (wet ? 2 : 1);
            }
            return river[i] == 2;
        }

        private int height(int lx, int lz) {
            return chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, Math.max(0, Math.min(15, lx)), Math.max(0, Math.min(15, lz)));
        }
    }

    /**
     * The rock the model puts at a point of a loaded world, as a block state: what a cliff there shows. Plain stone where
     * the model has nothing particular to say, or where the world is not one of the mod's own (see the API).
     */
    public static BlockState rockState(net.minecraft.server.level.ServerLevel level, int x, int y, int z) {
        if (!com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level) || !GeyserConfig.LITHOLOGY.get()) {
            return States.ALL[Rock.STONE.ordinal()];
        }
        long seed = TerrainContext.seed();
        Column c = Lithology.column(seed, TerrainContext.params(), x, z);
        int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        Rock r = Lithology.rockAt(seed, c, x, y, z, ground);
        return States.ALL[(r == Rock.KEEP ? Rock.STONE : r).ordinal()];
    }

    /** The blocks the rocks are, looked up once the blocks exist. */
    private static final class States {
        static final BlockState[] ALL = new BlockState[Rock.values().length];
        static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
        static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
        static final BlockState COARSE = Blocks.COARSE_DIRT.defaultBlockState();
        static final BlockState MOSSY = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

        static {
            put(Rock.STONE, Blocks.STONE);
            put(Rock.SANDSTONE, Blocks.SANDSTONE);
            put(Rock.SHALE, ModBlocks.SHALE.get());
            put(Rock.CALCITE, Blocks.CALCITE);
            put(Rock.RED_BEDS, Blocks.RED_TERRACOTTA);
            put(Rock.GRANITE, Blocks.GRANITE);
            put(Rock.DIORITE, Blocks.DIORITE);
            put(Rock.ANDESITE, Blocks.ANDESITE);
            put(Rock.TUFF, Blocks.TUFF);
            put(Rock.RHYOLITE, ModBlocks.RHYOLITE.get());
            put(Rock.BASALT, Blocks.BASALT);
            put(Rock.SMOOTH_BASALT, Blocks.SMOOTH_BASALT);
            put(Rock.BLACKSTONE, Blocks.BLACKSTONE);
            put(Rock.GABBRO, ModBlocks.GABBRO.get());
            put(Rock.PERIDOTITE, ModBlocks.PERIDOTITE.get());
            put(Rock.SERPENTINITE, ModBlocks.SERPENTINITE.get());
            put(Rock.CHERT, ModBlocks.CHERT.get());
            put(Rock.GNEISS, ModBlocks.GNEISS.get());
            put(Rock.SCHIST, ModBlocks.SCHIST.get());
            put(Rock.SLATE, ModBlocks.SLATE.get());
            put(Rock.MARBLE, ModBlocks.MARBLE.get());
            put(Rock.QUARTZITE, ModBlocks.QUARTZITE.get());
        }

        private static void put(Rock rock, Block block) {
            ALL[rock.ordinal()] = block.defaultBlockState();
        }
    }
}
