package com.jeladastudios.ftsgeology.worldgen.lithology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Column;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Rock;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
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
 * both ways.</p>
 *
 * <p>With {@code bare} it answers in every column, stone included, whatever the slope: the rule above the tree line,
 * where a mountain carries no soil at all.</p>
 */
public record LithologyRule(boolean steepOnly, boolean bare) implements SurfaceRules.RuleSource {

    public static final KeyDispatchDataCodec<LithologyRule> CODEC = KeyDispatchDataCodec.of(
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.BOOL.optionalFieldOf("steep_only", false).forGetter(LithologyRule::steepOnly),
                    Codec.BOOL.optionalFieldOf("bare", false).forGetter(LithologyRule::bare)
            ).apply(i, LithologyRule::new)));

    /** How much the ground must climb across two blocks for a column to count as a cliff. */
    private static final int STEEP_RISE = 3;

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }

    /** Called once a chunk. The context and its chunk are opened to the mod by META-INF/accesstransformer.cfg. */
    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        if (!GeyserConfig.LITHOLOGY.get()) return (x, y, z) -> null;
        return new Pass(context.chunk, TerrainContext.seed(), steepOnly, bare);
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

        Pass(ChunkAccess chunk, long seed, boolean steepOnly, boolean bare) {
            this.chunk = chunk;
            this.seed = seed;
            this.steepOnly = steepOnly;
            this.bare = bare;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            int i = ((x & 15) << 4) | (z & 15);
            if (steepOnly && !steepAt(i, x & 15, z & 15)) return null;
            Column c = columns[i];
            if (c == null) {
                c = Lithology.column(seed, TerrainContext.params(), x, z);
                columns[i] = c;
                // The ground as the noise left it, under any water: the floor of the sea is the top of its rock too.
                grounds[i] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15) - 1;
            }
            Rock r = Lithology.rockAt(seed, c, x, y, z, grounds[i]);
            // On a cliff or above the tree line the block asked about would have become soil, so plain stone has to be
            // said out loud. Anywhere else it is what the block already is, and answering it would only pay for
            // writing it again.
            if (steepOnly || bare) return States.ALL[(r == Rock.KEEP ? Rock.STONE : r).ordinal()];
            return r == Rock.KEEP || r == Rock.STONE ? null : States.ALL[r.ordinal()];
        }

        /**
         * Whether the ground climbs {@link #STEEP_RISE} within two blocks of this column along either axis, either
         * way. Neighbours past the chunk's edge are read at the edge, as vanilla reads them, so a cliff right on a
         * border is judged a little gently.
         */
        private boolean steepAt(int i, int lx, int lz) {
            if (steep[i] == 0) {
                int west = height(lx - 1, lz), east = height(lx + 1, lz);
                int north = height(lx, lz - 1), south = height(lx, lz + 1);
                boolean cliff = Math.abs(east - west) >= STEEP_RISE || Math.abs(south - north) >= STEEP_RISE;
                steep[i] = (byte) (cliff ? 2 : 1);
            }
            return steep[i] == 2;
        }

        private int height(int lx, int lz) {
            return chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, Math.max(0, Math.min(15, lx)), Math.max(0, Math.min(15, lz)));
        }
    }

    /** The blocks the rocks are, looked up once the blocks exist. */
    private static final class States {
        static final BlockState[] ALL = new BlockState[Rock.values().length];

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
