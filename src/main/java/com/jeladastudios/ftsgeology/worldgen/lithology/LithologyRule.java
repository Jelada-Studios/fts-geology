package com.jeladastudios.ftsgeology.worldgen.lithology;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Column;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Rock;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.mojang.serialization.MapCodec;
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
 */
public enum LithologyRule implements SurfaceRules.RuleSource {
    INSTANCE;

    public static final KeyDispatchDataCodec<LithologyRule> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }

    /** Called once a chunk. The context and its chunk are opened to the mod by META-INF/accesstransformer.cfg. */
    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        if (!com.jeladastudios.ftsgeology.config.GeyserConfig.LITHOLOGY.get()) return (x, y, z) -> null;
        return new Pass(context.chunk, TerrainContext.seed());
    }

    /** One chunk's pass: each column worked out the first time one of its blocks is asked about. */
    private static final class Pass implements SurfaceRules.SurfaceRule {
        private final ChunkAccess chunk;
        private final long seed;
        private final Column[] columns = new Column[256];
        private final int[] grounds = new int[256];

        Pass(ChunkAccess chunk, long seed) {
            this.chunk = chunk;
            this.seed = seed;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            int i = ((x & 15) << 4) | (z & 15);
            Column c = columns[i];
            if (c == null) {
                c = Lithology.column(seed, TerrainContext.params(), x, z);
                columns[i] = c;
                // The ground as the noise left it, under any water: the floor of the sea is the top of its rock too.
                grounds[i] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15) - 1;
            }
            Rock r = Lithology.rockAt(seed, c, x, y, z, grounds[i]);
            // Plain stone is what the block already is: answering it would only pay for writing it again.
            return r == Rock.KEEP || r == Rock.STONE ? null : States.ALL[r.ordinal()];
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
