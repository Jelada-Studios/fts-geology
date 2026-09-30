package com.jeladastudios.ftsgeology.compat.dh;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.worldgen.SnowCover;
import com.jeladastudios.ftsgeology.worldgen.terrain.RawGround;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Distant Horizons' far terrain, drawn straight from the plate model instead of from generated chunks.
 *
 * <p>Distant Horizons fills the land past the render distance by generating real chunks there -- every noise sample,
 * every feature -- and keeping them; across the tall world's thousands of blocks that is most of what a pre-generation
 * costs, in time and in memory. Here it asks instead for the level of detail it wants, a column standing for anything
 * from one block to four thousand, and each column is answered from the ground the generator would build with no river
 * cut into it ({@link RawGround}): a few reads of the terrain function, no chunk. The sea stands over the ground under
 * its level, the rivers and lakes in the nearer detail levels, snow over the snow line, bare rock on steep ground,
 * sand on beaches and in deserts, and a canopy over the forests. When a chunk is really generated Distant Horizons
 * reads it and the approximation there gives way to the real ground.</p>
 */
final class GeologyLodGenerator implements IDhApiWorldGenerator {

    /** Detail levels asked for: 0 is a block a column, 12 four thousand and ninety six. */
    private static final byte FINEST = 0, COARSEST = 12;
    /**
     * The coarsest detail at which rivers and lakes are drawn, sixteen blocks a column: past it a column is wider than
     * all but the biggest river, and the network would be traced over the whole far distance to say so.
     */
    private static final int RIVER_DETAIL = 4;
    /**
     * The coarsest detail at which lakes are drawn, from the parts of the network already worked out only: a lake is
     * wide, and cut off where the rivers stop it left a straight edge across the water.
     */
    private static final int LAKE_DETAIL = 8;
    /** How deep the soil under the surface block is, and how steep ground must be to show bare rock. */
    private static final int SOIL = 3;
    private static final double BARE_SLOPE = 1.4;
    /** The canopy over a forest: how high over the ground it starts and how thick it is. */
    private static final int CANOPY_FROM = 4, CANOPY_THICK = 4;
    /** Full sky light. */
    private static final int SKY = 15;

    private static final LongAdder SOURCES = new LongAdder(), COLUMNS = new LongAdder(), NANOS = new LongAdder();

    private final IDhApiLevelWrapper wrapper;
    private final BiomeSource biomes;
    private final Climate.Sampler sampler;
    private final int minY, maxY, sea;
    private final Map<String, IDhApiBlockStateWrapper> blocks = new ConcurrentHashMap<>();
    private final Map<String, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();

    GeologyLodGenerator(IDhApiLevelWrapper wrapper, ServerLevel level) {
        this.wrapper = wrapper;
        this.biomes = level.getChunkSource().getGenerator().getBiomeSource();
        this.sampler = level.getChunkSource().randomState().sampler();
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
        this.sea = level.getSeaLevel();
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        return FINEST;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return COARSEST;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public boolean runApiValidation() {
        return false;
    }

    @Override
    public void preGeneratorTaskStart() {}

    @Override
    public void close() {}

    @Override
    public CompletableFuture<Void> generateLod(int chunkMinX, int chunkMinZ, int posX, int posZ, byte detail,
                                               IDhApiFullDataSource source, EDhApiDistantGeneratorMode mode,
                                               ExecutorService pool, Consumer<IDhApiFullDataSource> done) {
        return CompletableFuture.runAsync(() -> {
            if (!RawGround.ready()) throw new IllegalStateException("the terrain is not wired yet");
            fill(chunkMinX * 16, chunkMinZ * 16, detail, source);
            // Never kept past this call: Distant Horizons reuses the object.
            done.accept(source);
        }, pool);
    }

    /** Fills every column of a data source whose north-west corner is at {@code x0, z0}. */
    private void fill(int x0, int z0, byte detail, IDhApiFullDataSource source) {
        long t0 = System.nanoTime();
        int width = source.getWidthInDataColumns();
        int step = 1 << detail;
        // The ground at every column's middle, and one column round the edge for the slope.
        double[][] ground = new double[width + 2][width + 2];
        for (int i = -1; i <= width; i++) {
            for (int j = -1; j <= width; j++) {
                ground[i + 1][j + 1] = RawGround.heightAt(x0 + i * step + step / 2, z0 + j * step + step / 2);
            }
        }
        List<DhApiTerrainDataPoint> column = new ArrayList<>(8);
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                int x = x0 + i * step + step / 2, z = z0 + j * step + step / 2;
                double g = ground[i + 1][j + 1];
                double slope = (Math.abs(ground[i + 2][j + 1] - ground[i][j + 1])
                        + Math.abs(ground[i + 1][j + 2] - ground[i + 1][j])) / (4.0 * step);
                column.clear();
                column(x, z, g, slope, detail, column);
                source.setApiDataPointColumn(i, j, column);
            }
        }
        COLUMNS.add((long) width * width);
        NANOS.add(System.nanoTime() - t0);
        SOURCES.increment();
        if (SOURCES.sum() % 200 == 0) com.jeladastudios.ftsgeology.util.Diagnostics.info("{}", summary());
    }

    /** One column's blocks, bottom to top, as spans of Y relative to the bottom of the world. */
    private void column(int x, int z, double g, double slope, byte detail, List<DhApiTerrainDataPoint> out) {
        int top = clamp((int) Math.floor(g));
        int water = Integer.MIN_VALUE;           // the top of any water, exclusive
        String waterBlock = "minecraft:water";
        if (top < sea - 1) {
            water = sea;
        }
        if (detail <= LAKE_DETAIL && RiverNetwork.ready()) {
            boolean near = detail <= RIVER_DETAIL;
            RiverNetwork.At a = near ? RiverNetwork.at(x, z) : RiverNetwork.builtOnly(() -> RiverNetwork.at(x, z));
            if (a.distance() != Double.MAX_VALUE && !a.sunk() && (a.lake() || near && a.inChannel())) {
                int w = (int) Math.floor(a.water()) + 1;
                int bed = clamp((int) Math.floor(Math.min(a.bed(), a.water() - 1.0)));
                if (w > bed + 1 && w > sea) {
                    top = Math.min(top, bed);
                    water = w;
                }
            }
        }
        Holder<Biome> biome = biomes.getNoiseBiome(x >> 2, Math.max(top, sea) >> 2, z >> 2, sampler);
        IDhApiBiomeWrapper biomeWrapper = biome(biome);
        boolean wet = water > top + 1;

        String surface, soil = "minecraft:dirt";
        int snowLine = SnowCover.line();
        if (wet) {
            surface = water - top > 6 ? "minecraft:gravel" : "minecraft:sand";
            soil = surface;
        } else if (top >= snowLine || biome.value().coldEnoughToSnow(new BlockPos(x, top + 1, z))) {
            surface = slope > BARE_SLOPE * 1.5 ? "minecraft:stone" : "minecraft:snow_block";
        } else if (slope > BARE_SLOPE) {
            surface = "minecraft:stone";
            soil = "minecraft:stone";
        } else if (biome.is(BiomeTags.IS_BEACH) || biome.is(Tags.Biomes.IS_DESERT) || biome.is(Tags.Biomes.IS_SANDY)) {
            surface = biome.is(BiomeTags.IS_BADLANDS) ? "minecraft:red_sand" : "minecraft:sand";
            soil = biome.is(BiomeTags.IS_BADLANDS) ? "minecraft:terracotta" : "minecraft:sandstone";
        } else if (biome.is(BiomeTags.IS_BADLANDS)) {
            surface = "minecraft:terracotta";
            soil = "minecraft:terracotta";
        } else {
            surface = "minecraft:grass_block";
        }

        int y = minY;
        y = span(out, y, top - SOIL + 1, "minecraft:stone", biomeWrapper, 0);
        y = span(out, y, top, soil, biomeWrapper, 0);
        y = span(out, y, top + 1, surface, biomeWrapper, 0);
        if (wet) {
            y = span(out, y, clamp(water), waterBlock, biomeWrapper, SKY);
        } else if (surface.equals("minecraft:grass_block")) {
            String leaves = canopy(biome, x, z);
            if (leaves != null) {
                y = span(out, y, clamp(top + 1 + CANOPY_FROM), "minecraft:air", biomeWrapper, SKY);
                y = span(out, y, clamp(top + 1 + CANOPY_FROM + CANOPY_THICK), leaves, biomeWrapper, SKY);
            }
        }
        span(out, y, maxY, "minecraft:air", biomeWrapper, SKY);
    }

    /**
     * Adds the span from {@code from} up to {@code to} (exclusive), and returns where the next one starts. What stands
     * open to the sky is given full sky light: left dark for Distant Horizons to light, the edges between areas it had
     * not yet lit came out as black stripes across the land.
     */
    private int span(List<DhApiTerrainDataPoint> out, int from, int to, String block, IDhApiBiomeWrapper biome, int sky) {
        to = Math.min(to, maxY);
        if (to <= from) return from;
        out.add(DhApiTerrainDataPoint.create((byte) 0, 0, sky, from - minY, to - minY, block(block), biome));
        return to;
    }

    /** The leaves over a column of forest, or null where the trees stand too thin to close over it. */
    private static String canopy(Holder<Biome> biome, int x, int z) {
        double cover;
        String leaves;
        if (biome.is(BiomeTags.IS_JUNGLE)) {
            cover = 0.9;
            leaves = "minecraft:jungle_leaves";
        } else if (biome.is(BiomeTags.IS_TAIGA)) {
            cover = 0.65;
            leaves = "minecraft:spruce_leaves";
        } else if (biome.is(BiomeTags.IS_FOREST)) {
            cover = 0.75;
            leaves = "minecraft:oak_leaves";
        } else if (biome.is(BiomeTags.IS_SAVANNA)) {
            cover = 0.15;
            leaves = "minecraft:acacia_leaves";
        } else {
            return null;
        }
        return SeedHash.rand01(SeedHash.hash(0L, x >> 2, z >> 2, 0xD1ADL)) < cover ? leaves : null;
    }

    private int clamp(int y) {
        return Math.max(minY + 1, Math.min(maxY - 1, y));
    }

    private IDhApiBlockStateWrapper block(String id) {
        return blocks.computeIfAbsent(id, k -> {
            try {
                return k.equals("minecraft:air") ? DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper()
                        : DhApi.Delayed.wrapperFactory.getDefaultBlockStateWrapper(k, wrapper);
            } catch (Exception e) {
                throw new IllegalStateException("no block " + k + " for Distant Horizons", e);
            }
        });
    }

    private IDhApiBiomeWrapper biome(Holder<Biome> biome) {
        String id = biome.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
        return biomeWrappers.computeIfAbsent(id, k -> {
            try {
                return DhApi.Delayed.wrapperFactory.getBiomeWrapper(k, wrapper);
            } catch (Exception e) {
                GeysersMod.LOGGER.warn("Distant Horizons has no biome {}; drawing plains", k);
                try {
                    return DhApi.Delayed.wrapperFactory.getBiomeWrapper("minecraft:plains", wrapper);
                } catch (Exception again) {
                    throw new IllegalStateException(again);
                }
            }
        });
    }

    static String summary() {
        return String.format(java.util.Locale.ROOT, "Distant Horizons terrain: %d data sources, %d columns, %.1f ms a source",
                SOURCES.sum(), COLUMNS.sum(), SOURCES.sum() == 0 ? 0.0 : NANOS.sum() / 1.0e6 / SOURCES.sum());
    }
}
