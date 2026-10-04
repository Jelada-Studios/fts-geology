package com.jeladastudios.ftsgeology.worldgen.lithology;

import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.worldgen.terrain.ColumnClimate;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.jeladastudios.ftsgeology.worldgen.terrain.WorldgenRevision;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * A surface rule that runs another only where a column passes a test, {@code fts_geology:column_test} in the mod's
 * noise settings: {@code {"test": "...", "then_run": rule}}. Vanilla's own conditions see the block and its biome but
 * not the place's climate, its rivers or its volcanoes, nor which version a world was made with.
 *
 * <p>The tests:</p>
 * <ul>
 *   <li>{@code greener}, {@code older}: the world is made since {@link WorldgenRevision#GREENER_GROUND}, or before.
 *   The noise settings keep the old rules under {@code older}, so an older world's new chunks meet its old ones
 *   without a seam.</li>
 *   <li>{@code volcanic_bare}: volcanic ground stays bare ash and rock -- where the climate is dry, over the tree line,
 *   or on a large volcano's own body. Elsewhere ash weathers fast to andosol, among the richest soils there are: Java,
 *   Japan, the Azores, the Cascades are green to the foot of their cones.</li>
 *   <li>{@code dry}: the climate is dry, near a desert's.</li>
 *   <li>{@code near_river}: a river's channel or the strip beside it its floods lay sand and gravel on.</li>
 *   <li>{@code basin_floor}: the floor of a geothermal basin, which its springs crust over with sinter.</li>
 * </ul>
 */
public record ColumnTestRule(Test test, SurfaceRules.RuleSource thenRun) implements SurfaceRules.RuleSource {

    public enum Test implements StringRepresentable {
        GREENER("greener"), OLDER("older"), VOLCANIC_BARE("volcanic_bare"), DRY("dry"), NEAR_RIVER("near_river"),
        BASIN_FLOOR("basin_floor");

        public static final Codec<Test> CODEC = StringRepresentable.fromEnum(Test::values);

        private final String name;

        Test(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final KeyDispatchDataCodec<ColumnTestRule> CODEC = KeyDispatchDataCodec.of(
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Test.CODEC.fieldOf("test").forGetter(ColumnTestRule::test),
                    SurfaceRules.RuleSource.CODEC.fieldOf("then_run").forGetter(ColumnTestRule::thenRun)
            ).apply(i, ColumnTestRule::new)));

    /** The climate's humidity under which volcanic ground stays bare, and under which a column counts as dry. */
    private static final double VOLCANIC_DRY = -0.25, DRY = -0.35;
    /** How far up a large volcano's body, as a share of its reach, its ground stays bare: a little past its foot. */
    private static final double ON_BODY = 1.1;
    /** How far past a channel's edge its floods leave sand and gravel, in blocks of the normal world. */
    private static final double FLOOD_STRIP = 6.0;
    private static final int SEA = 63;

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        SurfaceRules.SurfaceRule inner = thenRun.apply(context);
        return switch (test) {
            case GREENER -> WorldgenRevision.has(WorldgenRevision.GREENER_GROUND) ? inner : (x, y, z) -> null;
            case OLDER -> WorldgenRevision.has(WorldgenRevision.GREENER_GROUND) ? (x, y, z) -> null : inner;
            default -> new Pass(test, inner, context.chunk);
        };
    }

    /** One chunk: each column tested the first time one of its blocks is asked about. */
    private static final class Pass implements SurfaceRules.SurfaceRule {
        private final Test test;
        private final SurfaceRules.SurfaceRule inner;
        private final ChunkAccess chunk;
        /** 0 not yet tested, 1 failed, 2 passed. */
        private final byte[] passed = new byte[256];
        private ColumnClimate.At[] climate;
        private boolean climateTried;

        Pass(Test test, SurfaceRules.SurfaceRule inner, ChunkAccess chunk) {
            this.test = test;
            this.inner = inner;
            this.chunk = chunk;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            int i = ((x & 15) << 4) | (z & 15);
            if (passed[i] == 0) passed[i] = (byte) (test(x, z) ? 2 : 1);
            return passed[i] == 2 ? inner.tryApply(x, y, z) : null;
        }

        private boolean test(int x, int z) {
            return switch (test) {
                case VOLCANIC_BARE -> volcanicBare(x, z);
                case DRY -> {
                    ColumnClimate.At c = climate(x, z);
                    yield c != null && c.humidity() < DRY;
                }
                case NEAR_RIVER -> RiverNetwork.at(x, z).within(FLOOD_STRIP * TerrainContext.params().horizontal());
                case BASIN_FLOOR -> {
                    ServerLevel level = overworld();
                    yield level != null && com.jeladastudios.ftsgeology.worldgen.GeothermalBasin.onFloor(level, x, z);
                }
                default -> false;
            };
        }

        private boolean volcanicBare(int x, int z) {
            ColumnClimate.At c = climate(x, z);
            if (c != null) {
                if (c.humidity() < VOLCANIC_DRY) return true;
                int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
                if (ground >= c.treeLine(x, z, SEA)) return true;
            }
            ServerLevel level = overworld();
            return level != null && com.jeladastudios.ftsgeology.volcano.VolcanoField.bodyShare(level, x, z) < ON_BODY;
        }

        private ColumnClimate.At climate(int x, int z) {
            if (!climateTried) {
                climateTried = true;
                climate = ColumnClimate.corners(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
            }
            if (climate == null) return null;
            return ColumnClimate.blend(climate, x & 15, z & 15);
        }

        private static ServerLevel overworld() {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.overworld();
        }
    }
}
