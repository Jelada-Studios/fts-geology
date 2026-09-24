package com.jeladastudios.ftsgeology.worldgen.lithology;

import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * The young lava down the middle of a continental rift, {@code fts_geology:rift_axis} in the mod's noise settings.
 *
 * <p>A rift pulls a continent apart along a line, and the line is where the magma comes up: the floor of the Afar or of
 * Iceland's rift zone is dark with fresh basalt down its axis, the older floor either side of it weathered and
 * covered. So along every rift between two continents a strip of basalt runs the valley's length, four to eight
 * blocks across, winding with the valley itself and with ragged edges, smooth and glassy in places and black
 * where it has barely cooled. It is the ground's top block only, laid with the ground, so nothing grows on it.</p>
 */
public record RiftAxisRule() implements SurfaceRules.RuleSource {

    public static final KeyDispatchDataCodec<RiftAxisRule> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(new RiftAxisRule()));

    /** The strip's half width, in blocks, at its narrowest and at its widest. */
    private static final double HALF_MIN = 2.0, HALF_MAX = 4.0;
    /** How much the edge frays, in blocks, on a short scale. */
    private static final double FRAY = 1.0, FRAY_SCALE = 3.0;
    /** How far past the channel a river still owns its banks, in blocks of the normal world. */
    private static final double RIVER_BANK = 3.0;

    private static final BlockState BASALT = Blocks.BASALT.defaultBlockState();
    private static final BlockState SMOOTH = Blocks.SMOOTH_BASALT.defaultBlockState();
    private static final BlockState BLACK = Blocks.BLACKSTONE.defaultBlockState();

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        return new Pass(context.chunk, TerrainContext.seed());
    }

    /** One chunk: each column decided the first time one of its blocks is asked about. */
    private static final class Pass implements SurfaceRules.SurfaceRule {
        private final ChunkAccess chunk;
        private final long seed;
        /** 0 not yet looked at, 1 off the strip, 2 basalt, 3 smooth basalt, 4 blackstone. */
        private final byte[] strip = new byte[256];

        Pass(ChunkAccess chunk, long seed) {
            this.chunk = chunk;
            this.seed = seed;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            int i = ((x & 15) << 4) | (z & 15);
            if (strip[i] == 0) strip[i] = decide(x, z);
            return switch (strip[i]) {
                case 2 -> BASALT;
                case 3 -> SMOOTH;
                case 4 -> BLACK;
                default -> null;
            };
        }

        private byte decide(int x, int z) {
            var p = TerrainContext.params();
            double d = TerrainFields.riftAxisDistance(seed, p, x, z, HALF_MAX + FRAY);
            if (d < 0) return 1;
            double half = HALF_MIN + (HALF_MAX - HALF_MIN) * (0.5 + 0.5 * ValueNoise.noise(x - 409, z + 2267, 36.0))
                    + FRAY * ValueNoise.noise(x + 57, z + 91, FRAY_SCALE);
            if (d > half) return 1;
            // A river keeps its own bed and banks: the flow does not run across a channel cut into it since.
            RiverNetwork.At a = RiverNetwork.at(x, z);
            if (a.distance() != Double.MAX_VALUE
                    && a.distance() <= a.halfWidth() + RIVER_BANK * p.horizontal()) return 1;
            double kind = ValueNoise.noise(x - 5003, z + 877, 7.0);
            return (byte) (kind > 0.45 ? 3 : kind < -0.55 ? 4 : 2);
        }
    }
}
