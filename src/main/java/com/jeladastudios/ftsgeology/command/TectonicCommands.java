package com.jeladastudios.ftsgeology.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.quake.Earthquake;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import com.jeladastudios.ftsgeology.volcano.VolcanoBuilder;
import com.jeladastudios.ftsgeology.volcano.VolcanoSize;
import com.jeladastudios.ftsgeology.volcano.VolcanoType;
import com.jeladastudios.ftsgeology.worldgen.HotSpringShape;
import com.jeladastudios.ftsgeology.worldgen.SurfaceFeatures;
import com.jeladastudios.ftsgeology.worldgen.HotSpringSites;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Locale;
import static com.jeladastudios.ftsgeology.command.InspectCommands.*;
import static com.jeladastudios.ftsgeology.command.FindCommands.*;

/**
 * The {@code /geology} command tree. Inspection and search live in {@link InspectCommands} and
 * {@link FindCommands}; quakes and placement are here.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class TectonicCommands {

    private TectonicCommands() {}

    /** Side of the chat map, in cells. Odd so the player sits exactly in the middle. */
    static final int MAP_SIZE = 25;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("geology")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("plate").executes(InspectCommands::plate))
                        .then(Commands.literal("suitability").executes(InspectCommands::suitability))
                        .then(Commands.literal("column").executes(InspectCommands::column))
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
                        .then(Commands.literal("field")
                                .executes(ctx -> field(ctx, null, false))
                                .then(Commands.literal("tp").executes(ctx -> field(ctx, null, true)))
                                .then(Commands.literal("seams").executes(FindCommands::fieldSeams))
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(VOLCANO_TYPES, b))
                                        .executes(ctx -> field(ctx,
                                                StringArgumentType.getString(ctx, "type"), false))
                                        .then(Commands.literal("tp").executes(ctx -> field(ctx,
                                                StringArgumentType.getString(ctx, "type"), true)))))
                        .then(Commands.literal("quake")
                                .executes(ctx -> quake(ctx, null, 0))
                                .then(Commands.literal("cancel").executes(ctx -> {
                                    int n = com.jeladastudios.ftsgeology.quake.Earthquake.cancelAll();
                                    int v = com.jeladastudios.ftsgeology.volcano.VolcanoJob.clear();
                                    ctx.getSource().sendSuccess(() -> Component.translatable("command.fts_geology.cancelled_s_running_earthquake_s_and_s_v", n, v)
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
                                                StringArgumentType.getString(ctx, "feature"), 0))
                                        // A volcano can be asked for at medium size. Large ones only
                                        // come with new terrain; /geology field finds them.
                                        .then(Commands.literal("medium").executes(ctx -> place(ctx,
                                                StringArgumentType.getString(ctx, "feature"), 0,
                                                VolcanoSize.MEDIUM)))
                                        // A hot spring can be built at one stage, to compare stages side by side.
                                        .then(Commands.argument("stage",
                                                        IntegerArgumentType.integer(1, HotSpringShape.MAX_STAGE))
                                                .executes(ctx -> place(ctx,
                                                        StringArgumentType.getString(ctx, "feature"),
                                                        IntegerArgumentType.getInteger(ctx, "stage"))))))
                        // The terrain and the biomes the generator makes, measured without loading a chunk.
                        .then(Commands.literal("terrain")
                                // The generator's ground along a line from here, without loading a chunk.
                                .then(Commands.literal("here").executes(TerrainCommands::terrainHere))
                                // The router's density down this column: a cliff in the offset, or a lump of noise.
                                .then(Commands.literal("column").executes(TerrainCommands::terrainColumn))
                                // The traced river nearest here: where its channel runs and what its pool holds.
                                .then(Commands.literal("trace").executes(TerrainCommands::terrainTrace)
                                        .then(Commands.argument("half", IntegerArgumentType.integer(64, 8000))
                                                .then(Commands.argument("step", IntegerArgumentType.integer(2, 64))
                                                        .executes(ctx -> TerrainCommands.terrainTraceGrid(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "half"),
                                                                IntegerArgumentType.getInteger(ctx, "step"))))))
                                // Whether the river water round here would take a naturally spawned fish.
                                .then(Commands.literal("fish")
                                        .then(Commands.argument("half", IntegerArgumentType.integer(8, 256))
                                                .executes(ctx -> TerrainCommands.terrainFish(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "half")))))
                                .then(Commands.literal("grid")
                                        .then(Commands.argument("half", IntegerArgumentType.integer(8, 2000))
                                                .then(Commands.argument("step", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> TerrainCommands.terrainGrid(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "half"),
                                                                IntegerArgumentType.getInteger(ctx, "step"))))))
                                .then(Commands.literal("biomes")
                                        .then(Commands.argument("half", IntegerArgumentType.integer(64, 16000))
                                                .then(Commands.argument("step", IntegerArgumentType.integer(16, 1024))
                                                        .executes(ctx -> TerrainCommands.terrainBiomes(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "half"),
                                                                IntegerArgumentType.getInteger(ctx, "step"))))))
                                .then(Commands.literal("rivers")
                                        // A fingerprint of the rivers round here: the same world, the same number.
                                        .then(Commands.literal("hash")
                                                .then(Commands.argument("half", IntegerArgumentType.integer(64, 8000))
                                                        .executes(ctx -> TerrainCommands.terrainRiversHash(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "half")))))
                                        // A picture of the network over a region, nothing generated.
                                        .then(Commands.literal("map")
                                                .then(Commands.argument("half", IntegerArgumentType.integer(256, 16000))
                                                        .then(Commands.argument("step", IntegerArgumentType.integer(1, 64))
                                                                .executes(ctx -> TerrainCommands.terrainRiversMap(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "half"),
                                                                        IntegerArgumentType.getInteger(ctx, "step"))))))
                                        // Crossings, dead ends, water going uphill or standing over its bank.
                                        .then(Commands.literal("audit")
                                                .then(Commands.argument("half", IntegerArgumentType.integer(64, 8000))
                                                        .executes(ctx -> TerrainCommands.terrainRiversAudit(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "half")))))
                                        .then(Commands.argument("half", IntegerArgumentType.integer(64, 4000))
                                                .then(Commands.argument("step", IntegerArgumentType.integer(4, 64))
                                                        .then(Commands.argument("minPath", IntegerArgumentType.integer(0, 10000))
                                                                .executes(ctx -> TerrainCommands.terrainRivers(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "half"),
                                                                        IntegerArgumentType.getInteger(ctx, "step"),
                                                                        IntegerArgumentType.getInteger(ctx, "minPath")))))))
                                .then(Commands.literal("ocean")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(64, 20000))
                                                .executes(ctx -> TerrainCommands.terrainOcean(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                                .then(Commands.argument("dx", IntegerArgumentType.integer(-1, 1))
                                        .then(Commands.argument("dz", IntegerArgumentType.integer(-1, 1))
                                                .then(Commands.argument("length", IntegerArgumentType.integer(16, 20000))
                                                        .executes(ctx -> TerrainCommands.terrain(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "dx"),
                                                                IntegerArgumentType.getInteger(ctx, "dz"),
                                                                IntegerArgumentType.getInteger(ctx, "length")))))))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("cost").executes(TectonicCommands::cost))));
    }

    /** /geology debug cost: what world generation has cost the mod since the last time it was asked; then starts again. */
    static int cost(CommandContext<CommandSourceStack> ctx) {
        String line = com.jeladastudios.ftsgeology.worldgen.GenCost.summary();
        com.jeladastudios.ftsgeology.worldgen.GenCost.reset();
        GeysersMod.LOGGER.info("{}", line);
        ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    static final String[] SETTINGS = {"subduction", "rift", "collision", "transform", "hotspot", "tube", "valley",
            "everest", "k2", "matterhorn"};
    static final String[] FAULTS = {"subduction", "rift", "collision", "transform"};
    static final String[] VOLCANO_TYPES = {"strato", "shield", "fissure", "caldera", "island", "flooded", "eroded", "atoll",
            "guyot", "active", "dormant", "extinct"};
    static final String[] FEATURES = {"geyser", "hotspring", "volcano",
            "shield", "strato", "fissure", "caldera"};

    // === /geology quake =====================================================

    /**
     * Triggers an earthquake. With no type it uses the real local fault; with an explicit type it
     * forces that style anywhere, which is how you compare all three deformations side by side on
     * flat ground - and how you demonstrate them in a classroom.
     */
    static int quake(CommandContext<CommandSourceStack> ctx, String forcedType, double magnitude) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());

        if (forcedType == null) {
            if (!Earthquake.triggerHere(level, at, magnitude)) {
                source.sendFailure(Component.translatable("command.fts_geology.no_fault_here_plate_interiors_do_not_rup"));
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
            source.sendFailure(Component.translatable("command.fts_geology.unknown_fault_type_s", forcedType));
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
            source.sendSuccess(() -> Component.translatable("command.fts_geology.forcing_a_s_rupture_here_the_real_bounda", forcedType, real).withStyle(ChatFormatting.YELLOW), false);
        }
        Earthquake.trigger(level, at, type, mag, sx, sz, true);   // command picked the type
        return 1;
    }

    // === /geology place =====================================================

    /** Force-places a feature here, bypassing the suitability gate, to test the structure alone. */
    static int place(CommandContext<CommandSourceStack> ctx, String what, int stage) {
        return place(ctx, what, stage, VolcanoSize.SMALL);
    }

    static int place(CommandContext<CommandSourceStack> ctx, String what, int stage,
                             VolcanoSize size) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        boolean ok;
        switch (what) {
            // With a stage, one spring at that stage, plumbing and all; with none, a whole system as
            // world generation would build it.
            case "hotspring" -> ok = stage > 0
                    ? HotSpringSites.placeSingleSpringAt(level, at.getX(), at.getZ(), stage)
                    : HotSpringSites.placeHotSpringAt(level, at.getX(), at.getZ(),
                            HotSpringShape.MAX_STAGE);
            case "volcano", "shield", "strato", "fissure", "caldera" -> {
                // Ground, not canopy - see TerrainProbe.
                int y = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, at.getX(), at.getZ());
                if (y == Integer.MIN_VALUE) { ok = false; break; }
                BlockPos base = new BlockPos(at.getX(), y, at.getZ());
                int mag = size == VolcanoSize.SMALL
                        ? 8 + level.random.nextInt(12) : size.magnitude(level.random.nextDouble());
                // Naming a shape forces it, so all four can be compared side by side.
                VolcanoType forced = switch (what) {
                    case "shield" -> VolcanoType.SHIELD;
                    case "strato" -> VolcanoType.STRATOVOLCANO;
                    case "fissure" -> VolcanoType.FISSURE;
                    case "caldera" -> VolcanoType.CALDERA;
                    default -> null;
                };
                ok = forced == null
                        ? VolcanoBuilder.build(level, base, mag, size)
                        : VolcanoBuilder.build(level, base, mag, forced, size);
            }
            case "geyser" -> {
                int deepest = level.getMinBuildHeight() + 2;
                int highest = GeyserConfig.RETROGEN_MAX_Y.get()
                        - GeyserConfig.CHAMBER_TARGET_HEIGHT.get() - 3;
                int coreY = net.minecraft.util.Mth.clamp(
                        GeyserConfig.RETROGEN_MIN_Y.get() + 1, deepest, highest);
                ok = SurfaceFeatures.forcePlace(level,
                        new BlockPos(at.getX(), coreY, at.getZ()), 15, level.random);
            }
            default -> ok = false;
        }
        final boolean done = ok;
        final String named = size == VolcanoSize.SMALL
                ? what : what + " (" + size.name().toLowerCase(Locale.ROOT) + ")";
        // Two keys, one placeholder each.
        source.sendSuccess(() -> Component.translatable(
                done ? "command.fts_geology.placed_here" : "command.fts_geology.cannot_place_here",
                named), false);
        return done ? 1 : 0;
    }
}
