package com.pandabear.geysers.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pandabear.geysers.GeysersMod;
import com.pandabear.geysers.config.GeyserConfig;
import com.pandabear.geysers.quake.Earthquake;
import com.pandabear.geysers.tectonics.DepthScale;
import com.pandabear.geysers.tectonics.FaultType;
import com.pandabear.geysers.tectonics.GeothermalSuitability;
import com.pandabear.geysers.tectonics.HotspotMap;
import com.pandabear.geysers.tectonics.PlateKind;
import com.pandabear.geysers.tectonics.PlateSample;
import com.pandabear.geysers.tectonics.TectonicMap;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import com.pandabear.geysers.volcano.VolcanoBuilder;
import com.pandabear.geysers.volcano.VolcanoType;
import com.pandabear.geysers.worldgen.RetrogenHandler;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Inspection commands for the tectonic model.
 *
 * <ul>
 *   <li>{@code /geology plate} - full readout for the column you are standing in: which plate, what
 *       crust, which way it drifts, and what the nearest fault is doing.</li>
 *   <li>{@code /geology map [blocksPerCell]} - a chat map of the plates and fault lines around you,
 *       so you can actually see where the boundaries run.</li>
 * </ul>
 *
 * The model computes everything from the world seed and never edits the world, so these are pure
 * read-only queries and safe to run anywhere.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class TectonicCommands {

    private TectonicCommands() {}

    /** Side of the chat map, in cells. Odd so the player sits exactly in the middle. */
    private static final int MAP_SIZE = 25;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("geology")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("plate").executes(TectonicCommands::plate))
                        .then(Commands.literal("suitability").executes(TectonicCommands::suitability))
                        .then(Commands.literal("column").executes(TectonicCommands::column))
                        .then(Commands.literal("deepgen")
                                .executes(ctx -> deepgen(ctx, 0))
                                .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 8))
                                        .executes(ctx -> deepgen(ctx,
                                                IntegerArgumentType.getInteger(ctx, "chunkRadius")))))
                        .then(Commands.literal("map")
                                .executes(ctx -> mapItem(ctx, 0))
                                .then(Commands.literal("chat")
                                        .executes(ctx -> map(ctx, 0))
                                        .then(Commands.argument("blocksPerCell", IntegerArgumentType.integer(16, 20000))
                                                .executes(ctx -> map(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "blocksPerCell")))))
                                .then(Commands.argument("blocksPerPixel", IntegerArgumentType.integer(1, 20000))
                                        .executes(ctx -> mapItem(ctx,
                                                IntegerArgumentType.getInteger(ctx, "blocksPerPixel")))))
                        .then(Commands.literal("find")
                                .then(Commands.argument("setting", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(SETTINGS, b))
                                        .executes(ctx -> find(ctx,
                                                StringArgumentType.getString(ctx, "setting"), false))
                                        .then(Commands.literal("tp").executes(ctx -> find(ctx,
                                                StringArgumentType.getString(ctx, "setting"), true)))))
                        .then(Commands.literal("quake")
                                .executes(ctx -> quake(ctx, null, 0))
                                .then(Commands.literal("cancel").executes(ctx -> {
                                    int n = com.pandabear.geysers.quake.Earthquake.cancelAll();
                                    int v = com.pandabear.geysers.volcano.VolcanoJob.clear();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Cancelled " + n + " running earthquake(s) and " + v + " volcano build(s).")
                                            .withStyle(ChatFormatting.YELLOW), true);
                                    return 1;
                                }))
                                .then(Commands.argument("faultType", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(FAULTS, b))
                                        .executes(ctx -> quake(ctx,
                                                StringArgumentType.getString(ctx, "faultType"), 0))
                                        .then(Commands.argument("magnitude", DoubleArgumentType.doubleArg(1.0, 9.5))
                                                .executes(ctx -> quake(ctx,
                                                        StringArgumentType.getString(ctx, "faultType"),
                                                        DoubleArgumentType.getDouble(ctx, "magnitude"))))))
                        .then(Commands.literal("place")
                                .then(Commands.argument("feature", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(FEATURES, b))
                                        .executes(ctx -> place(ctx,
                                                StringArgumentType.getString(ctx, "feature"))))));
    }

    private static final String[] SETTINGS = {"subduction", "rift", "collision", "transform", "hotspot"};
    private static final String[] FAULTS = {"subduction", "rift", "collision", "transform"};
    private static final String[] FEATURES = {"geyser", "hotspring", "volcano",
            "shield", "strato", "fissure", "caldera"};

    private static int plate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!GeyserConfig.TECTONICS_ENABLED.get()) {
            source.sendFailure(Component.literal("Tectonics are disabled in the config."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());

        source.sendSuccess(() -> Component.literal("== Tectonics @ " + at.getX() + ", " + at.getZ() + " ==")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Plate %s (%s), drifting %.0f deg at %.2f",
                TectonicMap.plateCode(s.plateId()), s.plateKind(), s.plateBearing(), s.plateSpeed())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Nearest boundary: %s, %.0f blocks away", s.faultType(), s.faultDistance()))
                .withStyle(colorOf(s.faultType())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Against plate %s (%s) | convergence %+.2f, shear %.2f",
                TectonicMap.plateCode(s.neighbourId()), s.neighbourKind(),
                s.convergence(), s.shear())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Stress %.2f  %s", s.stress(), bar(s.stress()))), false);
        source.sendSuccess(() -> Component.literal("  " + describe(s)).withStyle(ChatFormatting.GRAY), false);

        // Hotspot state: the intraplate story a boundary map alone cannot tell.
        HotspotMap.Hotspot hot = HotspotMap.sample(level, at.getX(), at.getZ());
        if (hot.strength() > 0) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "  Mantle hotspot directly below, strength %.2f %s",
                    hot.strength(), bar(hot.strength()))).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        } else if (hot.onTrail()) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "  On a hotspot trail, %.0f%% of the way to extinct",
                    hot.trailAge() * 100)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }

        // Depth reported in real units via the scale, not in blocks.
        int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                at.getX(), at.getZ());
        source.sendSuccess(() -> Component.literal(String.format(
                "  Scaled crust here: %s thick | typical quake focus: %s",
                DepthScale.format(DepthScale.crustBaseMetres(level, surfaceY)),
                s.faultType() == FaultType.INTERIOR ? "n/a"
                        : DepthScale.format(s.faultType().typicalQuakeDepth() * 1000.0)))
                .withStyle(ChatFormatting.DARK_AQUA), false);

        GeothermalSuitability.Suitability fit = GeothermalSuitability.at(level, at.getX(), at.getZ());
        source.sendSuccess(() -> Component.literal(String.format(
                "  Suitability - volcano %.2f, geyser %.2f, hot spring %.2f",
                fit.volcano(), fit.geyser(), fit.hotSpring())), false);
        return 1;
    }

    private static int map(CommandContext<CommandSourceStack> ctx, int requestedStep) {
        CommandSourceStack source = ctx.getSource();
        if (!GeyserConfig.TECTONICS_ENABLED.get()) {
            source.sendFailure(Component.literal("Tectonics are disabled in the config."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        // Default zoom shows roughly two plate widths across, so at least one boundary is in view.
        int step = requestedStep > 0
                ? requestedStep
                : Math.max(16, (int) (GeyserConfig.PLATE_SCALE.get() * 2.0 / MAP_SIZE));
        int half = MAP_SIZE / 2;

        source.sendSuccess(() -> Component.literal(
                "== Plate map, " + step + " blocks per cell ==").withStyle(ChatFormatting.GOLD), false);

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
                    source.sendSuccess(() -> Component.literal(
                            "  ^ collision  V subduction  ~ rift  = transform  * hotspot  . interior")
                            .withStyle(ChatFormatting.GRAY), false);
                }, level.getServer())
                .exceptionally(t -> {
                    source.sendFailure(Component.literal("Map failed: " + t));
                    return null;
                });
        return 1;
    }

    /** Builds the map rows. Pure computation over the seed - safe to run off the server thread. */
    private static List<MutableComponent> renderGrid(ServerLevel level, BlockPos at, int step, int half) {
        List<MutableComponent> rows = new ArrayList<>();
        for (int row = -half; row <= half; row++) {
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
    private static char glyph(PlateSample s) {
        return switch (s.faultType()) {
            case CONVERGENT_COLLISION -> '^';
            case CONVERGENT_SUBDUCTION -> 'V';
            case DIVERGENT -> '~';
            case TRANSFORM -> '=';
            case INTERIOR -> s.plateKind() == PlateKind.OCEANIC ? ',' : '.';
        };
    }

    private static ChatFormatting colorOf(FaultType type) {
        return switch (type) {
            case CONVERGENT_COLLISION -> ChatFormatting.GOLD;
            case CONVERGENT_SUBDUCTION -> ChatFormatting.RED;
            case DIVERGENT -> ChatFormatting.AQUA;
            case TRANSFORM -> ChatFormatting.YELLOW;
            case INTERIOR -> ChatFormatting.DARK_GRAY;
        };
    }

    /** Plain-language note on what this boundary would build in the real world. */
    private static String describe(PlateSample s) {
        return switch (s.faultType()) {
            case CONVERGENT_COLLISION ->
                    "Two continents crumpling: a high mountain belt, big shallow quakes, no volcanoes.";
            case CONVERGENT_SUBDUCTION ->
                    "Oceanic crust diving under: a volcanic arc, deep trenches and the strongest quakes.";
            case DIVERGENT ->
                    "Crust pulling apart: a rift valley or spreading ridge, with hot springs and geysers.";
            case TRANSFORM ->
                    "Plates grinding past each other: a strike-slip fault with sharp, shallow quakes.";
            case INTERIOR ->
                    "Stable plate interior. The nearest boundary is too far to matter here.";
        };
    }

    private static String bar(double v) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, v)) * 10);
        return "[" + "#".repeat(filled) + "-".repeat(10 - filled) + "]";
    }


    // === /geology map (item) ================================================

    /**
     * Hands the player a real filled map painted with the fault network. This is the only way to see
     * how boundaries actually run - where they curve, and where three of them meet at a junction.
     */
    private static int mapItem(CommandContext<CommandSourceStack> ctx, int requestedPixel) {
        CommandSourceStack source = ctx.getSource();
        if (!GeyserConfig.TECTONICS_ENABLED.get()) {
            source.sendFailure(Component.literal("Tectonics are disabled in the config."));
            return 0;
        }
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can be handed a map."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        // Default zoom fits roughly two plate widths across the 128-pixel image.
        int perPixel = requestedPixel > 0
                ? requestedPixel
                : Math.max(1, (int) (GeyserConfig.PLATE_SCALE.get() * 2.0 / 128));
        final int fx = at.getX(), fz = at.getZ(), fpp = perPixel;

        source.sendSuccess(() -> Component.literal("Surveying the plates...")
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
                    source.sendSuccess(() -> Component.literal(
                            "Fault map drawn at " + fpp + " blocks per pixel - "
                                    + (fpp * 128) + " blocks across.").withStyle(ChatFormatting.GREEN), false);
                }, level.getServer())
                .exceptionally(t -> {
                    source.sendFailure(Component.literal("Map failed: " + t));
                    return null;
                });
        return 1;
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
    private static int column(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());

        int top = com.pandabear.geysers.worldgen.TerrainProbe.groundY(level, at.getX(), at.getZ());
        if (top == Integer.MIN_VALUE) top = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
        int bottom = level.getMinBuildHeight();

        source.sendSuccess(() -> Component.literal(String.format(
                "== Section at %d, %d  |  %s, stress %.2f ==",
                at.getX(), at.getZ(), s.faultType(), s.stress())).withStyle(ChatFormatting.GOLD), false);

        // Walk down, collapsing runs of the same block so a 380-block column reads as a dozen lines.
        List<String> lines = new ArrayList<>();
        String runName = null;
        int runTop = top;
        int shown = 0;
        for (int y = top; y >= bottom && shown < 26; y--) {
            net.minecraft.world.level.block.state.BlockState st =
                    level.getBlockState(new BlockPos(at.getX(), y, at.getZ()));
            String name = st.isAir() ? "air"
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(st.getBlock()).getPath();
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
        if (runName != null && shown < 26) {
            lines.add(String.format("  Y %4d..%4d  %s", bottom, runTop, runName));
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }

        // Name what the boundary should have left here, so the section can be read against it.
        String expected = switch (s.faultType()) {
            case CONVERGENT_COLLISION -> "folded calcite / tuff / diorite bands (metamorphic root)";
            case CONVERGENT_SUBDUCTION -> "a basalt-blackstone slab deep down, granite/diorite higher";
            case DIVERGENT -> "near-vertical basalt dykes, open fractures near the axis";
            case TRANSFORM -> "a narrow band of cobbled deepslate, gravel and tuff";
            case INTERIOR -> "nothing - this is plate interior";
        };
        source.sendSuccess(() -> Component.literal("  Expected here: " + expected)
                .withStyle(ChatFormatting.DARK_AQUA), false);
        if (s.stress() < 0.25 && s.faultType() != FaultType.INTERIOR) {
            source.sendSuccess(() -> Component.literal(
                    "  Stress is below 0.25, so this column is outside the active part of the zone.")
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
    private static int deepgen(CommandContext<CommandSourceStack> ctx, int chunkRadius) {
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
                com.pandabear.geysers.worldgen.DeepStructure.Report r =
                        new com.pandabear.geysers.worldgen.DeepStructure.Report();
                com.pandabear.geysers.worldgen.DeepStructure.generate(level, cp, rng, r);
                blocks += r.blocks;
                if (r.note != null) note = r.note;
                com.pandabear.geysers.worldgen.OceanicRidge.generate(level, cp, rng);
                done++;
            }
        }

        final int count = done;
        final int placed = blocks;
        final String why = note;
        source.sendSuccess(() -> Component.literal(String.format(
                "Deep structure regenerated for %d chunk(s) around %d, %d (%s, stress %.2f). "
                        + "%d blocks placed. Use /geology column to read the section.",
                count, cp0(centre), cp1(centre), s.faultType(), s.stress(),
                placed)).withStyle(ChatFormatting.GREEN), false);
        if (placed == 0 && why != null) {
            final String w = why;
            source.sendSuccess(() -> Component.literal("  Nothing placed: " + w)
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        if (s.stress() < 0.25) {
            source.sendSuccess(() -> Component.literal(
                    "  Note: stress here is too low for deep structure. Use /geology find <type> tp first.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int cp0(net.minecraft.world.level.ChunkPos cp) { return cp.x; }

    private static int cp1(net.minecraft.world.level.ChunkPos cp) { return cp.z; }
    // === /geology find ======================================================

    /**
     * Walks outward from the player in a coarse spiral until it finds the requested setting. This
     * is the command that makes the rest testable: hunting for a continental collision zone by
     * wandering could take thousands of blocks, and this answers in a moment because the whole model
     * is pure maths over the seed.
     */
    private static int find(CommandContext<CommandSourceStack> ctx, String what, boolean teleport) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());

        source.sendSuccess(() -> Component.literal("Searching for the nearest " + what + "...")
                .withStyle(ChatFormatting.GRAY), false);

        // Searching outward can mean a hundred thousand plate samples before a rare setting turns
        // up, and doing that on the server thread stalled the game for seconds. It is pure maths
        // over the seed, so it runs on a worker and only the answer comes back.
        CompletableFuture
                .supplyAsync(() -> search(level, at, what), Util.backgroundExecutor())
                .thenAcceptAsync(hit -> {
                    if (hit == null) {
                        source.sendFailure(Component.literal("No " + what
                                + " found within about 21000 blocks. Try another setting or world."));
                        return;
                    }
                    // Only now, on the server thread: generate the one destination chunk so the
                    // column has a real surface instead of answering with the bottom of the world.
                    level.getChunk(hit.x() >> 4, hit.z() >> 4);
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                            hit.x(), hit.z());
                    source.sendSuccess(() -> Component.literal(String.format(
                            "Nearest %s: %d, %d, %d  (about %d blocks away)",
                            what, hit.x(), y, hit.z(), hit.distance())).withStyle(ChatFormatting.GREEN), false);
                    if (teleport && source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                        p.teleportTo(level, hit.x() + 0.5, y + 1, hit.z() + 0.5, p.getYRot(), p.getXRot());
                    }
                }, level.getServer())
                .exceptionally(t -> {
                    source.sendFailure(Component.literal("Search failed: " + t));
                    return null;
                });
        return 1;
    }

    /** A located setting: where it is and roughly how far away. */
    private record Hit(int x, int z, int distance) {}

    /** Spirals outward looking for the requested setting. Pure maths - runs off the server thread. */
    private static Hit search(ServerLevel level, BlockPos at, String what) {
        int step = 96;
        int maxRings = 220;                 // reaches out about 21k blocks
        for (int ring = 1; ring <= maxRings; ring++) {
            int r = ring * step;
            for (int i = 0; i < ring * 8; i++) {
                double ang = (Math.PI * 2 * i) / (ring * 8);
                int x = at.getX() + (int) Math.round(Math.cos(ang) * r);
                int z = at.getZ() + (int) Math.round(Math.sin(ang) * r);
                if (matches(level, x, z, what)) return new Hit(x, z, r);
            }
        }
        return null;
    }

    private static boolean matches(ServerLevel level, int x, int z, String what) {
        if (what.equals("hotspot")) {
            return HotspotMap.sample(level, x, z).strength() > 0.35;
        }
        PlateSample s = TectonicMap.sampleCached(level, x, z);
        // Require decent stress so we land somewhere the setting is actually expressed, not on the
        // faint outer edge of the fault zone.
        if (s.stress() < 0.25) return false;
        return switch (what) {
            case "subduction" -> s.faultType() == FaultType.CONVERGENT_SUBDUCTION;
            case "collision" -> s.faultType() == FaultType.CONVERGENT_COLLISION;
            case "rift" -> s.faultType() == FaultType.DIVERGENT;
            case "transform" -> s.faultType() == FaultType.TRANSFORM;
            default -> false;
        };
    }

    // === /geology suitability ===============================================

    /** Explains, for this exact column, why a geyser or volcano can or cannot form here. */
    private static int suitability(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        GeothermalSuitability.Suitability fit = GeothermalSuitability.at(level, at.getX(), at.getZ());

        source.sendSuccess(() -> Component.literal("== Geothermal suitability here ==")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Volcano %.2f %s", fit.volcano(), bar(fit.volcano()))), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Geyser  %.2f %s", fit.geyser(), bar(fit.geyser()))), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  Springs %.2f %s", fit.hotSpring(), bar(fit.hotSpring()))), false);
        String painted = com.pandabear.geysers.tectonics.ThermalBiomes.label(level, at.getX(), at.getZ());
        if (!painted.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  The world generator already put " + painted + " here, so the model treats "
                            + "this as a mantle hotspot.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
        HotspotMap.Hotspot spot = HotspotMap.sample(level, at.getX(), at.getZ());
        if (spot.strength() > 0) {
            double basin = HotspotMap.basinStrength(level, at.getX(), at.getZ());
            source.sendSuccess(() -> Component.literal(String.format(
                    "  Geyser basin %.2f %s  %s", basin, bar(basin),
                    basin > 0.15 ? "(dense thermal ground)" : "(quiet ground between basins)"))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
        source.sendSuccess(() -> Component.literal("  " + fit.reason())
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // === /geology quake =====================================================

    /**
     * Triggers an earthquake. With no type it uses the real local fault; with an explicit type it
     * forces that style anywhere, which is how you compare all three deformations side by side on
     * flat ground - and how you demonstrate them in a classroom.
     */
    private static int quake(CommandContext<CommandSourceStack> ctx, String forcedType, double magnitude) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());

        if (forcedType == null) {
            if (!Earthquake.triggerHere(level, at, magnitude)) {
                source.sendFailure(Component.literal(
                        "No fault here - plate interiors do not rupture. Use /geology find <type> tp, "
                                + "or force one with /geology quake <type>."));
                return 0;
            }
            return 1;
        }

        FaultType type = switch (forcedType) {
            case "subduction" -> FaultType.CONVERGENT_SUBDUCTION;
            case "collision" -> FaultType.CONVERGENT_COLLISION;
            case "rift" -> FaultType.DIVERGENT;
            case "transform" -> FaultType.TRANSFORM;
            default -> null;
        };
        if (type == null) {
            source.sendFailure(Component.literal("Unknown fault type: " + forcedType));
            return 0;
        }
        // Use the real local strike when there is one, so a forced quake still lines up with the
        // landscape; otherwise fall back to a fixed direction.
        PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());
        double sx = s.onFault() ? s.faultStrikeX() : 1.0;
        double sz = s.onFault() ? s.faultStrikeZ() : 0.0;
        double mag = magnitude > 0 ? magnitude
                : Earthquake.rollMagnitude(type, Math.max(0.6, s.stress()), level.random);
        // Say plainly that the type was forced, so a rift appearing on a collision boundary does not
        // read as a bug. Plain /geology quake with no type uses the real local fault.
        final FaultType real = s.faultType();
        if (real != type) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "Forcing a %s rupture here - the real boundary at this spot is %s. "
                            + "Use /geology quake with no type to rupture the actual fault.",
                    forcedType, real)).withStyle(ChatFormatting.YELLOW), false);
        }
        Earthquake.trigger(level, at, type, mag, sx, sz, true);   // command picked the type
        return 1;
    }

    // === /geology place =====================================================

    /** Force-places a feature here, bypassing the suitability gate, to test the structure alone. */
    private static int place(CommandContext<CommandSourceStack> ctx, String what) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        boolean ok;
        switch (what) {
            case "hotspring" -> ok = RetrogenHandler.placeHotSpringAt(level, at.getX(), at.getZ());
            case "volcano", "shield", "strato", "fissure", "caldera" -> {
                // Ground, not canopy - see TerrainProbe.
                int y = com.pandabear.geysers.worldgen.TerrainProbe.groundY(level, at.getX(), at.getZ());
                if (y == Integer.MIN_VALUE) { ok = false; break; }
                BlockPos base = new BlockPos(at.getX(), y, at.getZ());
                int mag = 8 + level.random.nextInt(12);
                // Naming a shape forces it, so all four can be compared side by side.
                VolcanoType forced = switch (what) {
                    case "shield" -> VolcanoType.SHIELD;
                    case "strato" -> VolcanoType.STRATOVOLCANO;
                    case "fissure" -> VolcanoType.FISSURE;
                    case "caldera" -> VolcanoType.CALDERA;
                    default -> null;
                };
                ok = forced == null
                        ? VolcanoBuilder.build(level, base, mag)
                        : VolcanoBuilder.build(level, base, mag, forced);
            }
            case "geyser" -> {
                int deepest = level.getMinBuildHeight() + 2;
                int highest = GeyserConfig.RETROGEN_MAX_Y.get()
                        - GeyserConfig.CHAMBER_TARGET_HEIGHT.get() - 3;
                int coreY = net.minecraft.util.Mth.clamp(
                        GeyserConfig.RETROGEN_MIN_Y.get() + 1, deepest, highest);
                ok = RetrogenHandler.forcePlace(level,
                        new BlockPos(at.getX(), coreY, at.getZ()), 15, level.random);
            }
            default -> ok = false;
        }
        final boolean done = ok;
        source.sendSuccess(() -> Component.literal(done
                ? "Placed " + what + " here (suitability gate bypassed). Large volcanoes build over a few seconds."
                : "Could not place " + what + " here."), false);
        return done ? 1 : 0;
    }
}
