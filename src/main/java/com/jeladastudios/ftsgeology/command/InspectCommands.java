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

/** /geology plate, map, column, deepgen and suitability: read-only views of the model. */
public final class InspectCommands {

    private InspectCommands() {}

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
     * The generator's ground on a square grid round here, for the shape of a mountain or a slope: the highest and
     * lowest ground, how much of the square lies within three blocks of the top (a flat-topped mountain has a lot),
     * how the high ground spreads, and how many neighbouring samples differ by more than twice their spacing.
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
        int nearTop = 0, steep = 0, walls = 0, pairs = 0;
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
                    if (rise >= step) steep++;
                    if (rise >= 2 * step) walls++;
                }
            }
        }
        StringBuilder spread = new StringBuilder();
        for (int b = 0; b < bands.length; b++) {
            if (bands[b] > 0) spread.append(' ').append(160 + 20 * b).append('+').append(':').append(bands[b]);
        }
        String line = String.format(Locale.ROOT, "terrain grid at %d,%d, half %d every %d: %d samples, max %d, min %d, mean %.1f, within 3 of the top %d, slope 1+ %.1f%%, slope 2+ %.1f%%, high ground%s",
                at.getX(), at.getZ(), half, step, n * n, max, min, (double) sum / (n * n), nearTop,
                100.0 * steep / Math.max(1, pairs), 100.0 * walls / Math.max(1, pairs), spread.length() == 0 ? " none" : spread);
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
        for (String f : new String[] {"continents", "erosion", "ridges", "relief"}) {
            sb.append(f).append(' ').append(String.format(Locale.ROOT, "%.3f", com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field.valueOf(f.toUpperCase(Locale.ROOT)), com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(), com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), at.getX(), at.getZ()))).append("; ");
        }
        var generator = level.getChunkSource().getGenerator();
        int base = generator.getBaseHeight(at.getX(), at.getZ(), net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
        String line = String.format(Locale.ROOT, "terrain at %d,%d: %sbase %d, role %s; level seed %d, terrain seed %d, own %s",
                at.getX(), at.getZ(), sb, base,
                com.jeladastudios.ftsgeology.worldgen.terrain.GeologyRoles.roleAt(at.getX(), at.getZ()),
                level.getSeed(), com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level));
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info("{}", line);
        return 1;
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

    static int plate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!GeyserConfig.TECTONICS_ENABLED.get()) {
            source.sendFailure(Component.translatable("command.fts_geology.tectonics_are_disabled_in_the_config"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());

        source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics_s_s", at.getX(), at.getZ())
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics.plate", TectonicMap.plateCode(s.plateId()), s.plateKind(), dec(s.plateBearing(), 0), dec(s.plateSpeed(), 2)), false);
        source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics.boundary", s.faultType(), dec(s.faultDistance(), 0))
                .withStyle(colorOf(s.faultType())), false);
        source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics.neighbour", TectonicMap.plateCode(s.neighbourId()), s.neighbourKind(), String.format(Locale.ROOT, "%+.2f", s.convergence()), dec(s.shear(), 2)), false);
        if (s.faultType() == com.jeladastudios.ftsgeology.tectonics.FaultType.CONVERGENT_SUBDUCTION
                || s.faultType() == com.jeladastudios.ftsgeology.tectonics.FaultType.CONVERGENT_COLLISION) {
            source.sendSuccess(() -> Component.translatable(s.downGoing()
                    ? "command.fts_geology.tectonics.side_under" : "command.fts_geology.tectonics.side_over",
                    String.format(Locale.ROOT, "%.2f", s.faultNormalX()), String.format(Locale.ROOT, "%.2f", s.faultNormalZ())), false);
        }
        source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics.stress", dec(s.stress(), 2), bar(s.stress())), false);
        source.sendSuccess(() -> Component.translatable(describeKey(s)).withStyle(ChatFormatting.GRAY), false);

        // Hotspot state: the intraplate story a boundary map alone cannot tell.
        HotspotMap.Hotspot hot = HotspotMap.sample(level, at.getX(), at.getZ());
        if (hot.strength() > 0) {
            source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics.hotspot", dec(hot.strength(), 2), bar(hot.strength())).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        } else if (hot.onTrail()) {
            source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics.hotspot_trail", dec(hot.trailAge() * 100, 0)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }

        // Depth reported in real units via the scale, not in blocks.
        int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                at.getX(), at.getZ());
        source.sendSuccess(() -> Component.translatable("command.fts_geology.scaled_crust_here_s_thick_typical_quake_", DepthScale.format(DepthScale.crustBaseMetres(level, surfaceY)), s.faultType() == FaultType.INTERIOR ? "n/a"
                        : DepthScale.format(s.faultType().typicalQuakeDepth() * 1000.0))
                .withStyle(ChatFormatting.DARK_AQUA), false);

        GeothermalSuitability.Suitability fit = GeothermalSuitability.at(level, at.getX(), at.getZ());
        source.sendSuccess(() -> Component.translatable("command.fts_geology.tectonics.suitability", dec(fit.volcano(), 2), dec(fit.geyser(), 2), dec(fit.hotSpring(), 2)), false);
        return 1;
    }

    static int map(CommandContext<CommandSourceStack> ctx, int requestedStep) {
        CommandSourceStack source = ctx.getSource();
        if (!GeyserConfig.TECTONICS_ENABLED.get()) {
            source.sendFailure(Component.translatable("command.fts_geology.tectonics_are_disabled_in_the_config"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        // Default zoom shows roughly two plate widths across, so at least one boundary is in view.
        int step = requestedStep > 0
                ? requestedStep
                : Math.max(16, (int) (com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().plateScale() * 2.0 / MAP_SIZE));
        int half = MAP_SIZE / 2;

        source.sendSuccess(() -> Component.translatable("command.fts_geology.plate_map_s_blocks_per_cell", step).withStyle(ChatFormatting.GOLD), false);

        // 625 columns of pure maths: computed on a worker thread, printed on the server thread.
        final int fstep = step, fhalf = half;
        CompletableFuture
                .supplyAsync(() -> renderGrid(level, at, fstep, fhalf), Util.backgroundExecutor())
                .thenAcceptAsync(rows -> {
                    for (MutableComponent row : rows) {
                        source.sendSuccess(() -> row, false);
                    }
                    source.sendSuccess(() -> Component.translatable("command.fts_geology.collision_v_subduction_rift_transform_ho")
                            .withStyle(ChatFormatting.GRAY), false);
                }, level.getServer())
                .exceptionally(t -> {
                    source.sendFailure(Component.translatable("command.fts_geology.map_failed_s", t));
                    return null;
                });
        return 1;
    }

    /** Builds the map rows. Pure computation over the seed - safe to run off the server thread. */
    static List<MutableComponent> renderGrid(ServerLevel level, BlockPos at, int step, int half) {
        List<MutableComponent> rows = new ArrayList<>();
        for (int row = -half; row <= half; row++) {
            // Literal glyphs, not translation keys: one character wide and matched to the legend.
            MutableComponent line = Component.literal("");
            for (int col = -half; col <= half; col++) {
                int wx = at.getX() + col * step;
                int wz = at.getZ() + row * step;
                if (row == 0 && col == 0) {
                    line.append(Component.literal("@").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
                    continue;
                }
                if (HotspotMap.sample(level, wx, wz).strength() > 0.25) {
                    line.append(Component.literal("*").withStyle(ChatFormatting.LIGHT_PURPLE));
                    continue;
                }
                PlateSample s = TectonicMap.sampleCached(level, wx, wz);
                line.append(Component.literal(String.valueOf(glyph(s))).withStyle(colorOf(s.faultType())));
            }
            rows.add(line);
        }
        return rows;
    }
    /** One character for a sampled column: fault symbol on a boundary, plain ground inside a plate. */
    static char glyph(PlateSample s) {
        return switch (s.faultType()) {
            case CONVERGENT_COLLISION -> '^';
            case CONVERGENT_SUBDUCTION -> 'V';
            case DIVERGENT -> '~';
            case TRANSFORM -> '=';
            case INTERIOR -> s.plateKind() == PlateKind.OCEANIC ? ',' : '.';
        };
    }

    static ChatFormatting colorOf(FaultType type) {
        return switch (type) {
            case CONVERGENT_COLLISION -> ChatFormatting.GOLD;
            case CONVERGENT_SUBDUCTION -> ChatFormatting.RED;
            case DIVERGENT -> ChatFormatting.AQUA;
            case TRANSFORM -> ChatFormatting.YELLOW;
            case INTERIOR -> ChatFormatting.DARK_GRAY;
        };
    }

    /** Plain-language note on what this boundary would build in the real world. */
    static String describeKey(PlateSample s) {
        return switch (s.faultType()) {
            case CONVERGENT_COLLISION  -> "command.fts_geology.tectonics.setting.collision";
            case CONVERGENT_SUBDUCTION -> "command.fts_geology.tectonics.setting.subduction";
            case DIVERGENT             -> "command.fts_geology.tectonics.setting.rift";
            case TRANSFORM             -> "command.fts_geology.tectonics.setting.transform";
            case INTERIOR              -> "command.fts_geology.tectonics.setting.interior";
        };
    }

    /** What the boundary should have left in the column here, for reading a section against. */
    static String expectedKey(FaultType type) {
        return switch (type) {
            case CONVERGENT_COLLISION  -> "command.fts_geology.column.expected.collision";
            case CONVERGENT_SUBDUCTION -> "command.fts_geology.column.expected.subduction";
            case DIVERGENT             -> "command.fts_geology.column.expected.rift";
            case TRANSFORM             -> "command.fts_geology.column.expected.transform";
            case INTERIOR              -> "command.fts_geology.column.expected.interior";
        };
    }

    /**
     * Renders a decimal for display: the translation formatter throws on {@code %.2f}, and
     * {@link Locale#ROOT} keeps the separator a dot.
     */
    static String dec(double v, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", v);
    }

    static String bar(double v) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, v)) * 10);
        return "[" + "#".repeat(filled) + "-".repeat(10 - filled) + "]";
    }

    // === /geology map (item) ================================================

    /**
     * Hands the player a real filled map painted with the fault network. This is the only way to see
     * how boundaries actually run - where they curve, and where three of them meet at a junction.
     */
    static int mapItem(CommandContext<CommandSourceStack> ctx, int requestedPixel) {
        CommandSourceStack source = ctx.getSource();
        if (!GeyserConfig.TECTONICS_ENABLED.get()) {
            source.sendFailure(Component.translatable("command.fts_geology.tectonics_are_disabled_in_the_config"));
            return 0;
        }
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.translatable("command.fts_geology.only_a_player_can_be_handed_a_map"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        // Default zoom fits roughly two plate widths across the 128-pixel image.
        int perPixel = requestedPixel > 0
                ? requestedPixel
                : Math.max(1, (int) (com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().plateScale() * 2.0 / 128));
        final int fx = at.getX(), fz = at.getZ(), fpp = perPixel;

        source.sendSuccess(() -> Component.translatable("command.fts_geology.surveying_the_plates")
                .withStyle(ChatFormatting.GRAY), false);

        // 16384 pixels of Voronoi maths is far too much for a tick, and none of it touches the
        // world, so it is rendered on a worker thread and only the finished array comes back.
        CompletableFuture
                .supplyAsync(() -> FaultMap.render(level, fx, fz, fpp), Util.backgroundExecutor())
                .thenAcceptAsync(colours -> {
                    var stack = FaultMap.toItem(level, fx, fz, colours, fpp);
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                    source.sendSuccess(() -> Component.translatable("command.fts_geology.fault_map_drawn_at_s_blocks_per_pixel_s_", fpp, (fpp * 128)).withStyle(ChatFormatting.GREEN), false);
                }, level.getServer())
                .exceptionally(t -> {
                    source.sendFailure(Component.translatable("command.fts_geology.map_failed_s", t));
                    return null;
                });
        return 1;
    }

    /** One run of the same deposit in a column, for the deposits part of /geology column. */
    record OreRun(int topY, int bottomY, String blockName, String genesisKey) {}

    /** The process that left this block where it is, as a translation key, or null where that cannot be known. */
    static String oreGenesisKey(net.minecraft.world.level.block.state.BlockState st) {
        if (st.is(ModBlocks.COOLING_LAVA_CRUST.get())) return "command.fts_geology.column.horizon.magma_sill";
        if (st.is(ModBlocks.CHALCOPYRITE.get())) return "command.fts_geology.column.ore.chalcopyrite";
        if (st.is(ModBlocks.MALACHITE.get())) return "command.fts_geology.column.ore.malachite";
        if (st.is(ModBlocks.AZURITE.get())) return "command.fts_geology.column.ore.azurite";
        if (st.is(ModBlocks.QUARTZ_VEIN.get())) return "command.fts_geology.column.ore.quartz_vein";
        if (st.is(ModBlocks.PYRITE.get())) return "command.fts_geology.column.ore.pyrite";
        if (st.is(ModBlocks.CINNABAR.get())) return "command.fts_geology.column.ore.cinnabar";
        if (st.is(ModBlocks.GALENA.get())) return "command.fts_geology.column.ore.galena";
        // Vanilla ores get no story: a column cannot tell a vein this laid from one vanilla scattered.
        return null;
    }

    /** What a setting should hold, said when a column happens to miss every deposit in it. */
    static String orePotentialKey(FaultType type) {
        return switch (type) {
            case CONVERGENT_SUBDUCTION -> "command.fts_geology.column.potential.subduction";
            case CONVERGENT_COLLISION -> "command.fts_geology.column.potential.collision";
            case DIVERGENT -> "command.fts_geology.column.potential.rift";
            case TRANSFORM -> "command.fts_geology.column.potential.transform";
            case INTERIOR -> "command.fts_geology.column.potential.interior";
        };
    }

    // === /geology column ====================================================

    /**
     * Prints the vertical section under the player, bedrock to surface, so generation can be checked
     * without digging.
     */
    static int column(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());

        int top = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, at.getX(), at.getZ());
        if (top == Integer.MIN_VALUE) top = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
        int bottom = level.getMinBuildHeight();

        source.sendSuccess(() -> Component.translatable("command.fts_geology.column.header", at.getX(), at.getZ(), s.faultType(), dec(s.stress(), 2)).withStyle(ChatFormatting.GOLD), false);

        // Walk down, collapsing runs of the same block so a 380-block column reads as a dozen lines.
        List<String> lines = new ArrayList<>();
        String runName = null;
        int runTop = top;
        int shown = 0;
        // Deposits are gathered in the same walk, including below the 26-line cut-off.
        List<OreRun> ores = new ArrayList<>();
        String oreName = null, oreKey = null;
        int oreTop = 0, oreBottom = 0;
        for (int y = top; y >= bottom; y--) {
            net.minecraft.world.level.block.state.BlockState st =
                    level.getBlockState(new BlockPos(at.getX(), y, at.getZ()));
            String name = st.isAir() ? "air"
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(st.getBlock()).getPath();
            if (shown < 26) {
                if (runName == null) {
                    runName = name;
                    runTop = y;
                } else if (!runName.equals(name)) {
                    lines.add(String.format("  Y %4d..%4d  %s", y + 1, runTop, runName));
                    shown++;
                    runName = name;
                    runTop = y;
                }
            }
            boolean deposit = com.jeladastudios.ftsgeology.instrument.RockTypes.classify(st)
                    == com.jeladastudios.ftsgeology.instrument.RockTypes.Rock.ORE
                    || st.is(ModBlocks.COOLING_LAVA_CRUST.get());
            if (deposit && name.equals(oreName)) {
                oreBottom = y;
            } else {
                if (oreName != null) ores.add(new OreRun(oreTop, oreBottom, oreName, oreKey));
                oreName = deposit ? name : null;
                oreKey = deposit ? oreGenesisKey(st) : null;
                oreTop = y;
                oreBottom = y;
            }
        }
        if (oreName != null) ores.add(new OreRun(oreTop, oreBottom, oreName, oreKey));
        if (runName != null && shown < 26) {
            lines.add(String.format("  Y %4d..%4d  %s", bottom, runTop, runName));
        }
        // Literal: each line is a column-aligned "Y  64.. 71  Stone" row built by String.format, and
        // the block name inside it is already localised by Minecraft.
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }

        // And the deposits in it, named for the process. The Y range is formatted here, since
        // translations take a plain %s.
        source.sendSuccess(() -> Component.translatable("command.fts_geology.column.ores_header")
                .withStyle(ChatFormatting.GOLD), false);
        if (ores.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.fts_geology.column.no_ores_here",
                    Component.translatable(orePotentialKey(s.faultType()))).withStyle(ChatFormatting.DARK_GRAY), false);
        }
        for (OreRun r : ores) {
            String range = String.format(Locale.ROOT, "%4d..%4d", r.bottomY(), r.topY());
            MutableComponent line = r.genesisKey() == null
                    ? Component.translatable("command.fts_geology.column.ore_entry_plain", range, r.blockName())
                    : Component.translatable("command.fts_geology.column.ore_entry",
                            range, r.blockName(), Component.translatable(r.genesisKey()));
            source.sendSuccess(() -> line.withStyle(ChatFormatting.YELLOW), false);
        }

        // Name what the boundary should have left here, so the section can be read against it.
        source.sendSuccess(() -> Component.translatable("command.fts_geology.expected_here_s",
                        Component.translatable(expectedKey(s.faultType())))
                .withStyle(ChatFormatting.DARK_AQUA), false);

        // Groundwater. Printed here rather than in its own command because the question it answers
        // - why is there a spring on this ledge and not that one - is a question about the section.
        var w = com.jeladastudios.ftsgeology.hydrology.WaterTable.sample(level, at.getX(), at.getZ());

        // Depth to the ground actually here, not the generator's: they differ wherever the mod built
        // something, and on a volcano the difference is the whole cone.
        final int ground = top;
        int depth = Math.max(0, ground - w.tableY());
        if (w.head() >= ground && ground > level.getSeaLevel()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.fts_geology.column.spring_line", w.tableY(), w.head() - ground)
                    .withStyle(ChatFormatting.AQUA), false);
        } else {
            final int d = depth;
            source.sendSuccess(() -> Component.translatable(
                    "command.fts_geology.column.water_table", w.tableY(), d)
                    .withStyle(ChatFormatting.AQUA), false);
        }
        source.sendSuccess(() -> Component.translatable(
                "command.fts_geology.column.water_detail", w.base(), w.regional(), w.unsaturated())
                .withStyle(ChatFormatting.DARK_GRAY), false);
        // Say so when the ground here is not the ground the generator made, so a reading taken on a
        // volcano or in a quake scar can be understood rather than reported as a wrong number.
        if (Math.abs(ground - w.localSurface()) >= 3) {
            source.sendSuccess(() -> Component.translatable(
                    "command.fts_geology.column.ground_moved",
                    ground - w.localSurface(), w.localSurface())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        if (s.stress() < 0.25 && s.faultType() != FaultType.INTERIOR) {
            source.sendSuccess(() -> Component.translatable("command.fts_geology.stress_is_below_0_25_so_this_column_is_o")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    // === /geology deepgen ===================================================

    /**
     * Rebuilds the deep boundary geology around the player now, rather than waiting for visited
     * chunks to regenerate as you travel.
     *
     * @param chunkRadius 0 for the chunk you are in, up to 8 for a 17x17 chunk block
     */
    static int deepgen(CommandContext<CommandSourceStack> ctx, int chunkRadius) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        net.minecraft.world.level.ChunkPos centre = new net.minecraft.world.level.ChunkPos(at);
        PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());

        int done = 0, blocks = 0;
        String note = null;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                net.minecraft.world.level.ChunkPos cp =
                        new net.minecraft.world.level.ChunkPos(centre.x + dx, centre.z + dz);
                // Never force a load: a chunk that is not here simply gets its geology when it is.
                if (level.getChunkSource().getChunkNow(cp.x, cp.z) == null) continue;
                net.minecraft.util.RandomSource rng = net.minecraft.util.RandomSource.create(
                        level.getSeed() ^ (((long) cp.x) << 32 | (cp.z & 0xFFFFFFFFL)));
                com.jeladastudios.ftsgeology.worldgen.DeepStructure.Report r =
                        new com.jeladastudios.ftsgeology.worldgen.DeepStructure.Report();
                com.jeladastudios.ftsgeology.worldgen.DeepStructure.generate(level, cp, r);
                blocks += r.blocks;
                if (r.note != null) note = r.note;
                com.jeladastudios.ftsgeology.worldgen.OceanicRidge.generate(level, cp, rng);
                blocks += com.jeladastudios.ftsgeology.worldgen.OreGenesis.generate(level, cp);
                done++;
            }
        }

        final int count = done;
        final int placed = blocks;
        final String why = note;
        source.sendSuccess(() -> Component.translatable("command.fts_geology.deepgen.done", count, cp0(centre), cp1(centre), s.faultType(), dec(s.stress(), 2), placed).withStyle(ChatFormatting.GREEN), false);
        if (placed == 0 && why != null) {
            final String w = why;
            source.sendSuccess(() -> Component.translatable("command.fts_geology.nothing_placed_s", w)
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        if (s.stress() < 0.25) {
            source.sendSuccess(() -> Component.translatable("command.fts_geology.note_stress_here_is_too_low_for_deep_str")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    static int cp0(net.minecraft.world.level.ChunkPos cp) { return cp.x; }

    static int cp1(net.minecraft.world.level.ChunkPos cp) { return cp.z; }

    // === /geology suitability ===============================================

    /** Explains, for this exact column, why a geyser or volcano can or cannot form here. */
    static int suitability(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        GeothermalSuitability.Suitability fit = GeothermalSuitability.at(level, at.getX(), at.getZ());

        source.sendSuccess(() -> Component.translatable("command.fts_geology.geothermal_suitability_here")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("command.fts_geology.suitability.volcano", dec(fit.volcano(), 2), bar(fit.volcano())), false);
        source.sendSuccess(() -> Component.translatable("command.fts_geology.suitability.geyser", dec(fit.geyser(), 2), bar(fit.geyser())), false);
        source.sendSuccess(() -> Component.translatable("command.fts_geology.suitability.springs", dec(fit.hotSpring(), 2), bar(fit.hotSpring())), false);
        String painted = com.jeladastudios.ftsgeology.tectonics.ThermalBiomes.label(level, at.getX(), at.getZ());
        if (!painted.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.fts_geology.the_world_generator_already_put_s_here_s", painted).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
        HotspotMap.Hotspot spot = HotspotMap.sample(level, at.getX(), at.getZ());
        if (spot.strength() > 0) {
            double basin = HotspotMap.basinStrength(level, at.getX(), at.getZ());
            source.sendSuccess(() -> Component.translatable("command.fts_geology.suitability.basin", dec(basin, 2), bar(basin),
                    Component.translatable(basin > 0.15
                            ? "command.fts_geology.suitability.basin_dense"
                            : "command.fts_geology.suitability.basin_quiet"))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
        source.sendSuccess(() -> Component.translatable(fit.reasonKey())
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
