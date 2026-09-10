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
                : Math.max(16, (int) (GeyserConfig.PLATE_SCALE.get() * 2.0 / MAP_SIZE));
        int half = MAP_SIZE / 2;

        source.sendSuccess(() -> Component.translatable("command.fts_geology.plate_map_s_blocks_per_cell", step).withStyle(ChatFormatting.GOLD), false);

        // 625 columns of Voronoi and hotspot maths is far too much to do inside a tick, and none of
        // it touches the world, so it is computed on a worker thread and only the finished glyph
        // grid comes back to the server thread to be printed.
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
            // Literals, not translation keys: these are map symbols, not language. The legend below
            // the map is translated, but the glyphs themselves have to stay one character wide and
            // match that legend, so they are not something a translator should be able to change.
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
     * Renders a decimal for display.
     *
     * <p>Minecraft's translation formatter only understands {@code %s}, {@code %d} and positional
     * {@code %N$s} - a {@code %.2f} left in a lang file throws when the line is drawn. So decimals
     * are converted here and handed to the component as finished text. {@link Locale#ROOT} keeps
     * the separator a dot whatever locale the server JVM happened to boot in.</p>
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
                : Math.max(1, (int) (GeyserConfig.PLATE_SCALE.get() * 2.0 / 128));
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
        // Vanilla ores get no story. The game's own features scatter coal, iron, gold and lapis
        // everywhere, and a column cannot tell a vein this laid from one vanilla did - so naming a
        // process for them would mostly be telling a student something untrue.
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
     * Prints the vertical section under the player, bedrock to surface.
     *
     * <p>This exists because "I dug down and could not see anything" is not a measurement. The
     * boundary structure lives in a band that is easy to tunnel past, so testing kept turning into
     * guesswork about whether it had generated at all. Reading the column out loud settles it in one
     * command, without digging.</p>
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
        // Deposits are gathered in the same walk, which no longer stops once the section is cut off
        // at 26 lines: a vein below that point is still a vein.
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

        // And the deposits in it, each named for the process that left it there. The Y range is
        // formatted here rather than in the language file: Minecraft's translations take a plain
        // %s, and a width like %4d there breaks the whole line.
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

        // Depth is measured to the ground that is actually here, not to the one the generator would
        // have made. The model has to use the generator height - that is what lets it answer for
        // columns nowhere near a loaded chunk - but the two part company wherever the mod has since
        // built something, and a volcano is 40 blocks of exactly that. Reporting the generator
        // figure to a player standing on a summit tells them the water is a few blocks down when it
        // is under the whole cone.
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
     * Rebuilds the deep boundary geology around the player.
     *
     * <p>Deep structure is stamped with a version, so an already-visited chunk regenerates it on its
     * own as you travel. This is the impatient version: it does the area you are standing in right
     * now, so you can dig a test tunnel without first having to fly away and come back.</p>
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
