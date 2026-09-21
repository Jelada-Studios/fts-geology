package com.jeladastudios.ftsgeology.command;

import com.mojang.brigadier.context.CommandContext;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateKind;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static com.jeladastudios.ftsgeology.command.TectonicCommands.*;
import static com.jeladastudios.ftsgeology.command.FindCommands.*;

import static com.jeladastudios.ftsgeology.command.InspectCommands.*;

/** /geology terrain: the ground the generator makes, measured without loading a chunk, and its climate and rivers. */
public final class TerrainCommands {

    private TerrainCommands() {}

    /**
     * The generator's ground along a line from here, every eight blocks, without loading a chunk: for measuring
     * the terrain the plates make. Prints the heights as a row and a summary.
     */
    static int terrain(CommandContext<CommandSourceStack> ctx, int dx, int dz, int length) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        if (dx == 0 && dz == 0) dx = 1;
        BlockPos at = BlockPos.containing(source.getPosition());
        var generator = level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        int step = 8, n = length / step;
        StringBuilder row = new StringBuilder();
        int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE, sum = 0, high = 0, sea = 0, steepest = 0, walls = 0;
        int sealevel = level.getSeaLevel(), previous = Integer.MIN_VALUE;
        for (int i = 0; i <= n; i++) {
            int x = at.getX() + dx * i * step, z = at.getZ() + dz * i * step;
            int y = generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, state);
            if (i > 0) row.append(' ');
            row.append(y);
            max = Math.max(max, y); min = Math.min(min, y); sum += y;
            if (y >= 120) high++;
            if (y < sealevel) sea++;
            if (previous != Integer.MIN_VALUE) {
                int rise = Math.abs(y - previous);
                steepest = Math.max(steepest, rise);
                if (rise >= 2 * step) walls++;
            }
            previous = y;
        }
        final int count = n + 1, fmax = max, fmin = min, fhigh = high, fsea = sea, fdx = dx, fdz = dz;
        final double mean = (double) sum / count;
        // A wall: two samples eight blocks apart whose ground differs by sixteen or more.
        String line = String.format(Locale.ROOT, "terrain from %d,%d along %+d,%+d for %d: max %d, min %d, mean %.1f, above 120: %d blocks, under sea: %d blocks, steepest step %d, walls %d",
                at.getX(), at.getZ(), fdx, fdz, length, fmax, fmin, mean, fhigh * step, fsea * step, steepest, walls);
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(row.toString()).withStyle(ChatFormatting.GRAY), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("terrain heights: {}", row);
        return 1;
    }

    /**
     * The router's density down one column, for telling a two-dimensional spike from a three-dimensional one.
     *
     * <p>A cliff in the offset shows here as a column that is solid all the way from its top; a lump of the
     * blended noise standing on its own shows as solid, then air, then solid again. The slope of the density
     * between two heights is {@code -4 * factor / 128}, so the factor the column is actually being built with
     * can be read off it, and that is the number that decides how far the noise may move the surface.</p>
     */
    static int terrainColumn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        record Pos(int blockX, int blockY, int blockZ) implements net.minecraft.world.level.levelgen.DensityFunction.FunctionContext {}
        var router = level.getChunkSource().randomState().router();
        // The offset, read off the depth: depth = a gradient in y plus the offset, and the gradient at 64 is
        // known from the preset's own slope of one over a hundred and twenty-eight.
        double dep = router.depth().compute(new Pos(at.getX(), 64, at.getZ()));
        double span = level.getMaxBuildHeight() - level.getMinBuildHeight();
        double grad = 1.5 - (64.0 - level.getMinBuildHeight()) * (8.0 / span);
        int flat = (int) Math.round(128.0 + 128.0 * (dep - grad));
        StringBuilder row = new StringBuilder();
        int noCave = Integer.MIN_VALUE, withCave = Integer.MIN_VALUE, gaps = 0, eaten = 0;
        boolean wasSolid = false;
        for (int y = Math.min(level.getMaxBuildHeight() - 8, flat + 24); y >= Math.max(level.getMinBuildHeight() + 8, flat - 120); y -= 4) {
            double i0 = router.initialDensityWithoutJaggedness().compute(new Pos(at.getX(), y, at.getZ()));
            double d = router.finalDensity().compute(new Pos(at.getX(), y, at.getZ()));
            if (i0 > 0 && noCave == Integer.MIN_VALUE) noCave = y;
            if (d > 0 && withCave == Integer.MIN_VALUE) withCave = y;
            if (i0 > 0 && d <= 0) eaten++;
            if (d <= 0 && wasSolid) gaps++;
            wasSolid = d > 0;
            if (row.length() < 760) {
                row.append(y).append(':').append(String.format(Locale.ROOT, "%.2f/%.2f", i0, d)).append(' ');
            }
        }
        String line = String.format(Locale.ROOT,
                "terrain column at %d,%d: offset ground %d, first solid without caves %d, with them %d, samples caves ate %d, gaps %d",
                at.getX(), at.getZ(), flat, noCave, withCave, eaten, gaps);
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(row.toString()).withStyle(ChatFormatting.GRAY), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("terrain density: {}", row);
        return 1;
    }

    /**
     * Whether a column's ground is a cave mouth rather than a face, for telling one from the other in a grid.
     *
     * <p>The generator's height counts an open cave as ground, so a cheese cave that reaches a hillside reads as a
     * step as deep as the cave. A seventy-four block step measured in the tall world's badlands was one of these:
     * one column wide, with the two columns either side of it within a block of each other, and the ground above
     * it whole. Without this the grid says "tallest step 74" and a round goes looking for a cliff that is not
     * there.</p>
     */
    private static String cavesAt(ServerLevel level, int x, int z) {
        record Pos(int blockX, int blockY, int blockZ) implements net.minecraft.world.level.levelgen.DensityFunction.FunctionContext {}
        var router = level.getChunkSource().randomState().router();
        double dep = router.depth().compute(new Pos(x, 64, z));
        double span = level.getMaxBuildHeight() - level.getMinBuildHeight();
        double grad = 1.5 - (64.0 - level.getMinBuildHeight()) * (8.0 / span);
        int flat = (int) Math.round(128.0 + 128.0 * (dep - grad));
        int noCave = Integer.MIN_VALUE, withCave = Integer.MIN_VALUE;
        for (int y = Math.min(level.getMaxBuildHeight() - 8, flat + 24);
             y >= Math.max(level.getMinBuildHeight() + 8, flat - 160); y -= 2) {
            if (noCave == Integer.MIN_VALUE
                    && router.initialDensityWithoutJaggedness().compute(new Pos(x, y, z)) > 0) noCave = y;
            if (withCave == Integer.MIN_VALUE
                    && router.finalDensity().compute(new Pos(x, y, z)) > 0) withCave = y;
            if (noCave != Integer.MIN_VALUE && withCave != Integer.MIN_VALUE) break;
        }
        if (noCave == Integer.MIN_VALUE || withCave == Integer.MIN_VALUE) return "no reading";
        return String.format(Locale.ROOT, "solid from %d, open to %d", noCave, withCave);
    }

    /** What the river network says about a column, for telling a channel's own wall from the hillside's. */
    private static String channelAt(int x, int z) {
        com.jeladastudios.ftsgeology.hydrology.RiverNetwork.At a =
                com.jeladastudios.ftsgeology.hydrology.RiverNetwork.at(x, z);
        if (a.distance() == Double.MAX_VALUE) return "no channel";
        return String.format(Locale.ROOT, "channel %.1f out of a %.1f bed, water %.1f, floor %.1f",
                a.distance(), a.halfWidth(), a.water(), a.floor());
    }

    /**
     * The generator's ground on a square grid round here, for the shape of a mountain or a slope: the highest and
     * lowest ground, how much of the square lies within three blocks of the top (a flat-topped mountain has a lot),
     * how the high ground spreads, how many neighbouring samples stand at exactly the same height (ground stepped
     * into contour terraces has a lot, and shows them as lines up a hillside), and how many differ by more than
     * twice their spacing.
     */
    static int terrainGrid(CommandContext<CommandSourceStack> ctx, int half, int step) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        var generator = level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        int n = 2 * (half / step) + 1;
        int[][] h = new int[n][n];
        int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int x = at.getX() + (i - n / 2) * step, z = at.getZ() + (j - n / 2) * step;
                int y = generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, state);
                h[i][j] = y;
                max = Math.max(max, y);
                min = Math.min(min, y);
                sum += y;
            }
        }
        // The biggest rise between two neighbouring samples, and how many of them are cliffs: a shaved hillside
        // that stops dead shows up here and nowhere else in this line, because a single sheer face is a handful
        // of pairs out of tens of thousands and vanishes into every share.
        int tallest = 0, faces = 0, tallX = 0, tallZ = 0;
        int nearTop = 0, steep = 0, walls = 0, pairs = 0, flat = 0;
        int[] bands = new int[8];   // 160-179, 180-199, ... 300-319
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (h[i][j] >= max - 3) nearTop++;
                if (h[i][j] >= 160) bands[Math.min(7, (h[i][j] - 160) / 20)]++;
                for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
                    int a = i + d[0], b = j + d[1];
                    if (a >= n || b >= n) continue;
                    int rise = Math.abs(h[i][j] - h[a][b]);
                    pairs++;
                    if (rise == 0) flat++;
                    if (rise >= step) steep++;
                    if (rise >= 2 * step) walls++;
                    if (rise > tallest) {
                        tallest = rise;
                        tallX = at.getX() + (i - n / 2) * step;
                        tallZ = at.getZ() + (j - n / 2) * step;
                    }
                    if (rise >= 8) faces++;
                }
            }
        }
        StringBuilder spread = new StringBuilder();
        for (int b = 0; b < bands.length; b++) {
            if (bands[b] > 0) spread.append(' ').append(160 + 20 * b).append('+').append(':').append(bands[b]);
        }
        String line = String.format(Locale.ROOT, "terrain grid at %d,%d, half %d every %d: %d samples, max %d, min %d, mean %.1f, within 3 of the top %d, level pairs %.1f%%, slope 1+ %.1f%%, slope 2+ %.1f%%, tallest step %d at %d,%d (%s), 8+ steps %d, high ground%s",
                at.getX(), at.getZ(), half, step, n * n, max, min, (double) sum / (n * n), nearTop, 100.0 * flat / Math.max(1, pairs),
                100.0 * steep / Math.max(1, pairs), 100.0 * walls / Math.max(1, pairs), tallest, tallX, tallZ,
                channelAt(tallX, tallZ) + "; " + cavesAt(level, tallX, tallZ),
                faces, spread.length() == 0 ? " none" : spread);
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        return 1;
    }

    /**
     * The biomes the generator would pick on a square grid round here, asked above the ground so no cave biome
     * answers: each biome's share of the samples, and how far a biome runs along the grid's rows and columns before
     * the next one starts, on average, which is how large the patches are. Loads no chunk.
     */
    static int terrainBiomes(CommandContext<CommandSourceStack> ctx, int half, int step) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        int n = 2 * (half / step) + 1;
        if ((long) n * n > 250_000) {
            source.sendFailure(Component.literal("Too many samples: " + (long) n * n + ", keep it under 250000"));
            return 0;
        }
        var biomes = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        int qy = net.minecraft.core.QuartPos.fromBlock(level.getMaxBuildHeight() - 16);
        String[][] name = new String[n][n];
        boolean[][] land = new boolean[n][n];
        int[][] bands = new int[n][n];
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int x = at.getX() + (i - n / 2) * step, z = at.getZ() + (j - n / 2) * step;
                int qx = net.minecraft.core.QuartPos.fromBlock(x), qz = net.minecraft.core.QuartPos.fromBlock(z);
                var biome = biomes.getNoiseBiome(qx, qy, qz, sampler);
                bands[i][j] = bands(sampler.sample(qx, qy, qz));
                String b = biome.unwrapKey().map(k -> k.location().toString().replace("minecraft:", "")).orElse("?");
                name[i][j] = b;
                land[i][j] = !(biome.is(net.minecraft.tags.BiomeTags.IS_OCEAN) || biome.is(net.minecraft.tags.BiomeTags.IS_DEEP_OCEAN)
                        || biome.is(net.minecraft.tags.BiomeTags.IS_RIVER) || biome.is(net.minecraft.tags.BiomeTags.IS_BEACH));
                counts.merge(b, 1, Integer::sum);
            }
        }
        int changes = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j + 1 < n; j++) {
                if (!name[i][j].equals(name[i][j + 1])) changes++;
                if (!name[j][i].equals(name[j + 1][i])) changes++;
            }
        }
        // Every sample lies on one row and one column; each line starts one run and every change starts another.
        double run = (double) step * 2 * n * n / (2 * n + changes);
        // The same over the land alone, stepping across rivers, beaches and the sea, which cut every line every few
        // hundred blocks whatever the size of the patches either side.
        long landSamples = 0, landRuns = 0, landChanges = 0, landSteps = 0, noBand = 0, ours = 0;
        long[] byParameter = new long[BAND_NAMES.length], everyStep = new long[BAND_NAMES.length];
        for (int line = 0; line < 2 * n; line++) {
            String previous = null;
            int previousBands = 0;
            for (int k = 0; k < n; k++) {
                int i = line < n ? line : k, j = line < n ? k : line - n;
                if (!land[i][j]) continue;
                landSamples++;
                // How often each parameter crosses a band on any step over the land, biome change or not: a
                // parameter that crosses on most steps anyway is not what makes the biomes change.
                if (previous != null) {
                    landSteps++;
                    for (int p = 0; p < BAND_NAMES.length; p++) {
                        if (crossed(previousBands, bands[i][j], p)) everyStep[p]++;
                    }
                }
                if (!name[i][j].equals(previous)) {
                    landRuns++;
                    if (previous != null) {
                        // Which climate parameters crossed a band of the biome table between the two samples.
                        landChanges++;
                        boolean any = false;
                        for (int p = 0; p < BAND_NAMES.length; p++) {
                            if (crossed(previousBands, bands[i][j], p)) {
                                byParameter[p]++;
                                any = true;
                            }
                        }
                        if (!any) noBand++;
                        if (previous.startsWith("fts_geology:") || name[i][j].startsWith("fts_geology:")) ours++;
                    }
                }
                previous = name[i][j];
                previousBands = bands[i][j];
            }
        }
        double landRun = landRuns == 0 ? 0.0 : (double) step * landSamples / landRuns;
        StringBuilder why = new StringBuilder();
        for (int p = 0; p < BAND_NAMES.length; p++) {
            why.append(String.format(Locale.ROOT, " %s %.0f/%.0f%%,", BAND_NAMES[p], 100.0 * byParameter[p] / Math.max(1, landChanges),
                    100.0 * everyStep[p] / Math.max(1, landSteps)));
        }
        StringBuilder top = new StringBuilder();
        final int total = n * n;
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(16).forEach(e ->
                top.append(String.format(Locale.ROOT, "%s %s %.1f%%", top.length() == 0 ? "" : ",", e.getKey(),
                        100.0 * e.getValue() / total)));
        String line = String.format(Locale.ROOT, "terrain biomes at %d,%d, half %d every %d: %d samples, %d biomes, mean run %.0f blocks, on land %.0f; land changes %d of %d steps, band crossed (at a change/on any step):%s none %.0f%%, one of ours %.0f%%;%s",
                at.getX(), at.getZ(), half, step, total, counts.size(), run, landRun, landChanges, landSteps, why,
                100.0 * noBand / Math.max(1, landChanges), 100.0 * ours / Math.max(1, landChanges), top);
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        return 1;
    }

    /** The parameters of the overworld biome table, and the edges of its bands: a biome only changes across one. */
    private static final String[] BAND_NAMES = {"temperature", "humidity", "continentalness", "erosion", "weirdness"};
    private static final float[][] BAND_EDGES = {
            {-0.45F, -0.15F, 0.2F, 0.55F},
            {-0.35F, -0.1F, 0.1F, 0.3F},
            {-1.05F, -0.455F, -0.19F, -0.11F, 0.03F, 0.3F},
            {-0.78F, -0.375F, -0.2225F, 0.05F, 0.45F, 0.55F},
            {-0.93333334F, -0.7666667F, -0.56666666F, -0.4F, -0.26666668F, -0.05F, 0.05F, 0.26666668F, 0.4F,
                    0.56666666F, 0.7666667F, 0.93333334F}};

    /** Whether two samples lie in different bands of parameter {@code p}. */
    private static boolean crossed(int a, int b, int p) {
        return ((a ^ b) >> (3 * p) & (p == 4 ? 15 : 7)) != 0;
    }

    /** A sample's band in each parameter, three bits apiece and four for weirdness's thirteen slices. */
    private static int bands(net.minecraft.world.level.biome.Climate.TargetPoint t) {
        long[] v = {t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.weirdness()};
        int packed = 0;
        for (int p = 0; p < v.length; p++) {
            float value = net.minecraft.world.level.biome.Climate.unquantizeCoord(v[p]);
            int band = 0;
            for (float edge : BAND_EDGES[p]) if (value >= edge) band++;
            packed |= band << (3 * p);
        }
        return packed;
    }

    /** The plate density fields at this column, as the terrain generator sees them, and the seeds in play. */
    static int terrainHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        record Pos(int blockX, int blockY, int blockZ) implements net.minecraft.world.level.levelgen.DensityFunction.FunctionContext {}
        Pos pos = new Pos(at.getX(), 64, at.getZ());
        StringBuilder sb = new StringBuilder();
        for (String f : new String[] {"continents", "erosion", "ridges", "relief", "spline", "grip", "dem", "range", "landmark", "valley", "crest", "graben"}) {
            sb.append(f).append(' ').append(String.format(Locale.ROOT, "%.3f", com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field.valueOf(f.toUpperCase(Locale.ROOT)), com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(), com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), at.getX(), at.getZ()))).append("; ");
        }
        var generator = level.getChunkSource().getGenerator();
        int base = generator.getBaseHeight(at.getX(), at.getZ(), net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
        sb.append(String.format(Locale.ROOT, "apron %.3f; belt %.3f; ",
                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.apronAt(
                        com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                        com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), at.getX(), at.getZ()),
                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(
                        com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field.BELT,
                        com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                        com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), at.getX(), at.getZ())));
        var plate = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.sampleAt(com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(), com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), at.getX(), at.getZ());
        String line = String.format(Locale.ROOT, "terrain at %d,%d: %sbase %d, role %s%s; level seed %d, terrain seed %d, own %s",
                at.getX(), at.getZ(), sb, base,
                com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles.roleAt(at.getX(), at.getZ()),
                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.worn(plate, com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed()) ? " (worn belt)" : "",
                level.getSeed(), com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level));
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        String edges = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.debugAt(
                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), at.getX(), at.getZ());
        source.sendSuccess(() -> Component.literal(edges).withStyle(ChatFormatting.GRAY), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("edges: {}", edges);
        return 1;
    }

    /**
     * How much the generator's rivers wind, on a square grid round here asked of the biome source above the ground:
     * the river cells joined eight ways into rivers, and for each river the longest path through it (the farthest
     * cell from the farthest cell, diagonals at root two) against the straight distance between that path's ends.
     * A straight river is 1. Rivers shorter than {@code minPath} cells are left out of the median. Loads no chunk.
     */
    static int terrainRivers(CommandContext<CommandSourceStack> ctx, int half, int step, int minPath) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        int n = 2 * (half / step) + 1;
        if ((long) n * n > 250_000) {
            source.sendFailure(Component.literal("Too many samples: " + (long) n * n + ", keep it under 250000"));
            return 0;
        }
        var biomes = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        int qy = net.minecraft.core.QuartPos.fromBlock(level.getMaxBuildHeight() - 16);
        boolean[] river = new boolean[n * n];
        int rivers = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int x = at.getX() + (i - n / 2) * step, z = at.getZ() + (j - n / 2) * step;
                var biome = biomes.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), qy, net.minecraft.core.QuartPos.fromBlock(z), sampler);
                if (biome.is(net.minecraft.tags.BiomeTags.IS_RIVER)) { river[j * n + i] = true; rivers++; }
            }
        }
        int[] comp = new int[n * n], queue = new int[n * n];
        double[] dist = new double[n * n];
        java.util.List<Double> ratios = new java.util.ArrayList<>();
        java.util.List<String> longest = new java.util.ArrayList<>();
        int components = 0;
        for (int start = 0; start < n * n; start++) {
            if (!river[start] || comp[start] != 0) continue;
            components++;
            int head = 0, tail = 0;
            queue[tail++] = start; comp[start] = components;
            while (head < tail) {
                int c = queue[head++];
                int cx = c % n, cz = c / n;
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx, nz = cz + dz;
                    if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
                    int m = nz * n + nx;
                    if (river[m] && comp[m] == 0) { comp[m] = components; queue[tail++] = m; }
                }
            }
            int[] cells = java.util.Arrays.copyOf(queue, tail);
            int far = riverFarthest(river, n, cells, start, dist);
            int end = riverFarthest(river, n, cells, far, dist);
            double along = dist[end], straight = Math.hypot(end % n - far % n, end / n - far / n);
            if (along < minPath) continue;
            double ratio = straight < 1 ? 1.0 : along / straight;
            ratios.add(ratio);
            longest.add(String.format(Locale.ROOT, "%d cells, path %.0f, straight %.0f, sinuosity %.2f at %d,%d",
                    tail, along * step, straight * step, ratio,
                    at.getX() + (far % n - n / 2) * step, at.getZ() + (far / n - n / 2) * step));
        }
        java.util.Collections.sort(ratios);
        double median = ratios.isEmpty() ? 0 : ratios.get(ratios.size() / 2);
        double mean = ratios.stream().mapToDouble(d -> d).average().orElse(0);
        String line = String.format(Locale.ROOT, "rivers within %d of %d,%d every %d: %d samples, %d river (%.2f%%), %d rivers, %d of %d+ cells: sinuosity median %.2f, mean %.2f",
                half, at.getX(), at.getZ(), step, n * n, rivers, 100.0 * rivers / (n * n), components, ratios.size(), minPath, median, mean);
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        for (String l : longest) com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("   {}", l);
        return 1;
    }

    /** The river cell farthest along the river from {@code from}, by a search with root-two diagonals; fills dist. */
    private static int riverFarthest(boolean[] river, int n, int[] cells, int from, double[] dist) {
        for (int c : cells) dist[c] = Double.MAX_VALUE;
        java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        dist[from] = 0; pq.add(new double[]{0, from});
        int best = from;
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int c = (int) top[1];
            if (top[0] > dist[c]) continue;
            if (dist[c] > dist[best]) best = c;
            int cx = c % n, cz = c / n;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                int nx = cx + dx, nz = cz + dz;
                if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
                int m = nz * n + nx;
                if (!river[m]) continue;
                double nd = dist[c] + (dx != 0 && dz != 0 ? Math.sqrt(2) : 1);
                if (nd < dist[m]) { dist[m] = nd; pq.add(new double[]{nd, m}); }
            }
        }
        return best;
    }

    /** The share of the generator's ground under the sea within a radius, on a 64-block grid. */
    static int terrainOcean(CommandContext<CommandSourceStack> ctx, int radius) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        var generator = level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        int sealevel = level.getSeaLevel();
        int total = 0, sea = 0, mountain = 0;
        for (int x = at.getX() - radius; x <= at.getX() + radius; x += 64) {
            for (int z = at.getZ() - radius; z <= at.getZ() + radius; z += 64) {
                int y = generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, state);
                total++;
                if (y < sealevel) sea++;
                if (y >= 150) mountain++;
            }
        }
        String line = String.format(Locale.ROOT, "terrain within %d of %d,%d: %d samples, %.1f%% under sea, %.1f%% at or over 150",
                radius, at.getX(), at.getZ(), total, 100.0 * sea / total, 100.0 * mountain / total);
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        return 1;
    }

    /**
     * How many blocks of the mod's river water round here would take a naturally spawned salmon, by vanilla's rule
     * and by the one the mod widens it with. Needs the chunks loaded, so it is a measurement tool, not a player's.
     */
    public static int terrainFish(CommandContext<CommandSourceStack> ctx, int half) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        net.minecraft.world.level.block.Block river = ModBlocks.RIVER_WATER.get();
        int wet = 0, plain = 0, ours = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = at.getX() - half; x <= at.getX() + half; x++) {
            for (int z = at.getZ() - half; z <= at.getZ() + half; z++) {
                if (!level.hasChunkAt(x, z)) continue;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                for (int y = Math.max(level.getMinBuildHeight() + 1, top - 24); y <= top; y++) {
                    p.set(x, y, z);
                    if (!level.getBlockState(p).is(river)) continue;
                    wet++;
                    if (net.minecraft.world.entity.animal.WaterAnimal.checkSurfaceWaterAnimalSpawnRules(
                            net.minecraft.world.entity.EntityType.SALMON, level,
                            net.minecraft.world.entity.MobSpawnType.NATURAL, p, level.random)) {
                        plain++;
                    }
                    if (net.minecraft.world.entity.SpawnPlacements.checkSpawnRules(
                            net.minecraft.world.entity.EntityType.SALMON, level,
                            net.minecraft.world.entity.MobSpawnType.NATURAL, p, level.random)) {
                        ours++;
                    }
                }
            }
        }
        final String out = String.format(Locale.ROOT,
                "fish at %d,%d, half %d: %d river water blocks, %d take a salmon by vanilla's rule, %d by ours",
                at.getX(), at.getZ(), half, wet, plain, ours);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info(out);
        source.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /** The traced river nearest this column: its channel, the pool over it and the rock bar below it. */
    public static int terrainTrace(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        if (!com.jeladastudios.ftsgeology.hydrology.RiverNetwork.ready()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No river network: this world type traces none."), false);
            return 0;
        }
        var a = com.jeladastudios.ftsgeology.hydrology.RiverNetwork.at(at.getX(), at.getZ());
        String line;
        if (a.distance() == Double.MAX_VALUE) {
            line = String.format(Locale.ROOT, "no channel within reach of %d,%d (%d traces cut)",
                    at.getX(), at.getZ(), com.jeladastudios.ftsgeology.hydrology.RiverNetwork.tracesCut());
        } else {
            line = String.format(Locale.ROOT,
                    "channel %.1f blocks away, half width %.1f, floor %.1f, water %.1f; ground here %d (%d traces cut)",
                    a.distance(), a.halfWidth(), a.bed(), a.water(),
                    level.getChunkSource().getGenerator().getBaseHeight(at.getX(), at.getZ(),
                            net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                            level, level.getChunkSource().randomState()),
                    com.jeladastudios.ftsgeology.hydrology.RiverNetwork.tracesCut());
        }
        final String out = line;
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info(out);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /** The rivers over a square round here: how much ground they hold, how they step down and how much they wind. */
    public static int terrainTraceGrid(CommandContext<CommandSourceStack> ctx, int half, int step) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        if (!com.jeladastudios.ftsgeology.hydrology.RiverNetwork.ready()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No river network: this world type traces none."), false);
            return 0;
        }
        final String out = com.jeladastudios.ftsgeology.hydrology.RiverNetwork.report(at.getX(), at.getZ(), half, step);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info(out);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /** A fingerprint of the rivers round here, to check that the same world gives the same rivers every time. */
    public static int terrainRiversHash(CommandContext<CommandSourceStack> ctx, int half) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        if (!com.jeladastudios.ftsgeology.hydrology.RiverNetwork.ready()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No river network: this world type traces none."), false);
            return 0;
        }
        final String out = com.jeladastudios.ftsgeology.hydrology.RiverNetwork.hash(at.getX(), at.getZ(), half);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info(out);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }
}
