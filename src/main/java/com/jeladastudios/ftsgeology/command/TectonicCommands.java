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

    /** The mod's own commands read the river network exactly, and may wait for it; see RiverNetwork.mayWait. */
    @SubscribeEvent
    public static void onCommand(net.minecraftforge.event.CommandEvent event) {
        String cmd = event.getParseResults().getReader().getString();
        if (cmd.startsWith("geology") || cmd.startsWith("/geology")) {
            com.jeladastudios.ftsgeology.hydrology.RiverNetwork.mayWaitThisTick(
                    event.getParseResults().getContext().getSource().getServer());
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("geology")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("plate").executes(InspectCommands::plate))
                        .then(Commands.literal("suitability").executes(InspectCommands::suitability))
                        .then(Commands.literal("column").executes(InspectCommands::column))
                        .then(Commands.literal("water").executes(InspectCommands::water)
                                .then(Commands.literal("weather")
                                        .then(waterWeather("dry", false))
                                        .then(waterWeather("wet", true))))
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
                                // A quake right here that moves no ground: felt, recorded and shaken, as the faults' small
                                // ones are; a strong one tries what is built round it.
                                .then(Commands.literal("tremor").then(Commands.argument("magnitude", DoubleArgumentType.doubleArg(1.0, 9.5))
                                        .executes(ctx -> {
                                            ServerLevel level = ctx.getSource().getLevel();
                                            BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
                                            PlateSample s = TectonicMap.sample(level, at.getX(), at.getZ());
                                            FaultType type = s.faultType() == FaultType.INTERIOR ? FaultType.TRANSFORM : s.faultType();
                                            double m = DoubleArgumentType.getDouble(ctx, "magnitude");
                                            Earthquake.tremor(level, at, type, m, s.onFault() ? s.faultStrikeX() : 1.0,
                                                    s.onFault() ? s.faultStrikeZ() : 0.0, false);
                                            ctx.getSource().sendSuccess(() -> Component.literal("tremor M" + m + " at " + at.toShortString())
                                                    .withStyle(ChatFormatting.YELLOW), true);
                                            return 1;
                                        })))
                                .then(Commands.literal("aftershocks")
                                        .executes(TectonicCommands::aftershocks)
                                        .then(Commands.literal("clear").executes(ctx -> {
                                            int n = com.jeladastudios.ftsgeology.quake.Aftershocks.clear(ctx.getSource().getLevel());
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "command.fts_geology.aftershocks_cleared", n).withStyle(ChatFormatting.YELLOW), true);
                                            return n;
                                        })))
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
                                        // The channels against the water the world holds: dry beds, and what the floor is.
                                        .then(Commands.literal("wet")
                                                .then(Commands.argument("half", IntegerArgumentType.integer(16, 1000))
                                                        .executes(ctx -> TerrainCommands.terrainRiversWet(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "half")))))
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
                        // The small holes a removed mod's blocks left in the ground, closed with the rock round them.
                        .then(Commands.literal("fillvoids")
                                .executes(ctx -> fillVoids(ctx, 32))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(4, 96))
                                        .executes(ctx -> fillVoids(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                        // The network of stations (see instrument.StationNetwork): a station's screen opened for the
                        // operator, at it or seen from a terminal afar, and a station renamed.
                        .then(Commands.literal("station")
                                .then(Commands.literal("open").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .executes(ctx -> openStation(ctx, null, 0))
                                        .then(Commands.argument("tab", IntegerArgumentType.integer(0, 4))
                                                .executes(ctx -> openStation(ctx, null, IntegerArgumentType.getInteger(ctx, "tab"))))))
                                .then(Commands.literal("view").then(Commands.argument("via", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                .executes(ctx -> openStation(ctx, net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "via"), 0))
                                                .then(Commands.argument("tab", IntegerArgumentType.integer(0, 4))
                                                        .executes(ctx -> openStation(ctx, net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "via"),
                                                                IntegerArgumentType.getInteger(ctx, "tab")))))))
                                .then(Commands.literal("rename").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                                            BlockPos p = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos");
                                            boolean ok = ctx.getSource().getPlayer() != null
                                                    ? com.jeladastudios.ftsgeology.instrument.StationNetwork.rename(ctx.getSource().getLevel(), ctx.getSource().getPlayerOrException(), p, StringArgumentType.getString(ctx, "name"))
                                                    : com.jeladastudios.ftsgeology.instrument.StationNetwork.renameAny(ctx.getSource().getLevel(), p, StringArgumentType.getString(ctx, "name"));
                                            ctx.getSource().sendSuccess(() -> Component.literal(ok ? "renamed" : "no station there").withStyle(ChatFormatting.YELLOW), true);
                                            return ok ? 1 : 0;
                                        })))))
                        // The storms round here, and the region's weather (see weather.Storms).
                        // A fissure eruption: started at the nearest rift axis or hot spot (or right here), moved on a stage, read, stopped.
                        .then(Commands.literal("fissure")
                                .then(Commands.literal("start").executes(ctx -> fissure(ctx, false, -1))
                                        .then(Commands.argument("unrest", com.mojang.brigadier.arguments.IntegerArgumentType.integer(200, 480000))
                                                .executes(ctx -> fissure(ctx, false, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "unrest")))))
                                .then(Commands.literal("here").executes(ctx -> fissure(ctx, true, -1))
                                        .then(Commands.argument("unrest", com.mojang.brigadier.arguments.IntegerArgumentType.integer(200, 480000))
                                                .executes(ctx -> fissure(ctx, true, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "unrest")))))
                                .then(Commands.literal("strike").executes(ctx -> {
                                    String m = com.jeladastudios.ftsgeology.volcano.FissureEruptions.strikeReport(ctx.getSource().getLevel(),
                                            BlockPos.containing(ctx.getSource().getPosition()));
                                    ctx.getSource().sendSuccess(() -> Component.literal(m), false);
                                    return 1;
                                }))
                                .then(Commands.literal("next").executes(ctx -> {
                                    String m = com.jeladastudios.ftsgeology.volcano.FissureEruptions.next(ctx.getSource().getLevel());
                                    ctx.getSource().sendSuccess(() -> Component.literal(m), true);
                                    return 1;
                                }))
                                .then(Commands.literal("tp").executes(ctx -> fissureWatch(ctx, 50, 30))
                                        .then(Commands.argument("across", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-400, 400))
                                                .then(Commands.argument("up", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-20, 200))
                                                        .executes(ctx -> fissureWatch(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "across"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "up"))))))
                                .then(Commands.literal("info").executes(ctx -> {
                                    String m = com.jeladastudios.ftsgeology.volcano.FissureEruptions.info(ctx.getSource().getLevel());
                                    ctx.getSource().sendSuccess(() -> Component.literal(m), false);
                                    return 1;
                                }))
                                .then(Commands.literal("stop").executes(ctx -> {
                                    String m = com.jeladastudios.ftsgeology.volcano.FissureEruptions.stop(ctx.getSource().getLevel());
                                    ctx.getSource().sendSuccess(() -> Component.literal(m), true);
                                    return 1;
                                })))
                        // The oil field under here set to have this share of its oil taken (for trying what a spent field does).
                        .then(Commands.literal("oil").then(Commands.literal("spend").then(Commands.argument("share", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 1.0))
                                .executes(ctx -> {
                                    var lv = ctx.getSource().getLevel();
                                    BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
                                    double share = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "share");
                                    for (int y = at.getY(); y > lv.getMinBuildHeight(); y--) {
                                        var f = com.jeladastudios.ftsgeology.worldgen.PetroleumFields.at(lv, new BlockPos(at.getX(), y, at.getZ()));
                                        if (f == null) continue;
                                        com.jeladastudios.ftsgeology.worldgen.OilReserves.of(lv).spend(f.field(), share);
                                        ctx.getSource().sendSuccess(() -> Component.literal("the oil field under here: " + share + " of its oil taken"), true);
                                        return 1;
                                    }
                                    ctx.getSource().sendFailure(Component.literal("No oil field under here."));
                                    return 0;
                                }))))
                        .then(Commands.literal("storms")
                                // The air round here given this much water, a share of what its mean warmth holds (for trying the fog).
                                .then(Commands.literal("air").then(Commands.argument("humidity", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 1.2))
                                        .executes(ctx -> {
                                            var at = BlockPos.containing(ctx.getSource().getPosition());
                                            double rh = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "humidity");
                                            int n = com.jeladastudios.ftsgeology.weather.Moisture.set(ctx.getSource().getLevel(), at, rh, 2);
                                            ctx.getSource().sendSuccess(() -> Component.literal("the air of " + n + " squares round here at " + rh + " of its mean saturation"), true);
                                            return n;
                                        })))
                                // The rain the land here has in a year, its regime, and this part of the year's share.
                                .then(Commands.literal("climate").executes(ctx -> {
                                    var at = ctx.getSource().getPosition();
                                    var lv = ctx.getSource().getLevel();
                                    double phase = com.jeladastudios.ftsgeology.compat.SereneSeasons.phase(lv);
                                    String line = com.jeladastudios.ftsgeology.weather.RainClimate.describe(lv, (int) at.x, (int) at.z)
                                            + (Double.isNaN(phase) ? "" : ", rivers +" + com.jeladastudios.ftsgeology.hydrology.SeasonalRivers.stage(lv, (int) at.x, (int) at.z, phase));
                                    ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                                    return 1;
                                }))
                                // A storm of a given strength over here, for trying the weather out.
                                .then(Commands.literal("spawn").then(Commands.argument("peak", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 1.0))
                                        .executes(ctx -> spawnStorm(ctx, 800))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(50, 4000))
                                                .executes(ctx -> spawnStorm(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))))
                                // The wind over here held for a game day: blocks a second, towards a bearing (0 east, 90 south).
                                .then(Commands.literal("wind").then(Commands.argument("speed", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 15.0))
                                        .then(Commands.argument("toward", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-360.0, 360.0))
                                                .executes(ctx -> {
                                                    double speed = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "speed");
                                                    double toward = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "toward");
                                                    var at = ctx.getSource().getPosition();
                                                    com.jeladastudios.ftsgeology.weather.Atmosphere.pinWind(ctx.getSource().getLevel(), at.x, at.z, 2000, speed, toward);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(speed > 0
                                                            ? "wind held at " + speed + " blocks/s toward " + toward + " degrees" : "wind freed")
                                                            .withStyle(ChatFormatting.AQUA), true);
                                                    return 1;
                                                }))))
                                .executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
                            for (String line : com.jeladastudios.ftsgeology.weather.Storms.describe(level, at.getX(), at.getZ())) {
                                GeysersMod.LOGGER.info("{}", line);
                                ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("cost").executes(TectonicCommands::cost))));
    }

    /** /geology station open|view: a station's screen for the operator, at it or seen through the terminal at {@code via}. */
    static int openStation(CommandContext<CommandSourceStack> ctx, BlockPos via, int tab) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos");
        net.minecraft.nbt.CompoundTag data = com.jeladastudios.ftsgeology.instrument.StationNetwork.view(level, pos);
        if (data == null) {
            ctx.getSource().sendFailure(Component.literal("no station there"));
            return 0;
        }
        if (via == null && level.getBlockEntity(pos) instanceof com.jeladastudios.ftsgeology.blockentity.WeatherTerminalBlockEntity) via = pos;
        data = com.jeladastudios.ftsgeology.instrument.StationNetwork.decorate(level, player, via, pos, data);
        data.putInt("Tab", tab);
        com.jeladastudios.ftsgeology.network.ModNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new com.jeladastudios.ftsgeology.network.TerminalPacket(pos, data, true));
        return 1;
    }

    /** /geology storms spawn &lt;peak&gt; [radius]: a storm of that strength standing over here for a game day, or none at 0. */
    static int spawnStorm(CommandContext<CommandSourceStack> ctx, int radius) {
        double peak = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "peak");
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        int n = com.jeladastudios.ftsgeology.weather.Storms.spawnHere(ctx.getSource().getLevel(), at.getX(), at.getZ(), peak, radius);
        ctx.getSource().sendSuccess(() -> Component.literal(peak > 0 ? "storm over " + at.toShortString() + ", peak " + peak + ", radius " + radius
                : "storms over " + at.toShortString() + " cleared: " + n).withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /** /geology water weather dry|wet &lt;days&gt; [chunkRadius]: what a drought or a wet spell does to the ground round here. */
    /** Puts the player where they can watch the fissure eruption under way. */
    private static int fissureWatch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, int across, int up)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String m = com.jeladastudios.ftsgeology.volcano.FissureEruptions.watch(ctx.getSource().getLevel(), ctx.getSource().getPlayerOrException(), across, up);
        ctx.getSource().sendSuccess(() -> Component.literal(m), false);
        return 1;
    }

    /** Starts a fissure eruption near the source (or right where it is), its unrest this many ticks or the configured. */
    private static int fissure(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean here, int unrest) {
        var lv = ctx.getSource().getLevel();
        long ticks = unrest > 0 ? unrest : GeyserConfig.FISSURE_UNREST_TICKS.get();
        String m = com.jeladastudios.ftsgeology.volcano.FissureEruptions.start(lv, BlockPos.containing(ctx.getSource().getPosition()), here, ticks);
        ctx.getSource().sendSuccess(() -> Component.literal(m), true);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> waterWeather(String name, boolean wet) {
        return Commands.literal(name).then(Commands.argument("days", IntegerArgumentType.integer(1, 60))
                .executes(ctx -> waterWeather(ctx, wet, 2))
                .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 8))
                        .executes(ctx -> waterWeather(ctx, wet, IntegerArgumentType.getInteger(ctx, "chunkRadius")))));
    }

    static int waterWeather(CommandContext<CommandSourceStack> ctx, boolean wet, int radius) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        int days = IntegerArgumentType.getInteger(ctx, "days");
        int n = com.jeladastudios.ftsgeology.hydrology.SoilWater.fastForward(ctx.getSource().getLevel(), at.getX(), at.getZ(),
                radius, days, wet);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.fts_geology.water.weather." + (wet ? "wet" : "dry"),
                days, n).withStyle(ChatFormatting.YELLOW), true);
        return n;
    }

    /** /geology fillvoids [radius]: closes the small holes a removed mod's blocks left in the ground round here. */
    static int fillVoids(CommandContext<CommandSourceStack> ctx, int radius) {
        com.jeladastudios.ftsgeology.compat.RemovedModBlocks.Filled f = com.jeladastudios.ftsgeology.compat.RemovedModBlocks
                .fillVoids(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), radius);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.fts_geology.fillvoids", f.pockets(), f.niches(),
                f.blocks(), radius).withStyle(ChatFormatting.YELLOW), true);
        return f.blocks();
    }

    /** /geology debug cost: what world generation has cost the mod since the last time it was asked; then starts again. */
    static int cost(CommandContext<CommandSourceStack> ctx) {
        String line = com.jeladastudios.ftsgeology.worldgen.GenCost.summary();
        com.jeladastudios.ftsgeology.worldgen.GenCost.reset();
        GeysersMod.LOGGER.info("{}", line);
        ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    /** The shocks still to come here: how many, and the next few with their size, distance and time. */
    static int aftershocks(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        java.util.List<com.jeladastudios.ftsgeology.quake.Aftershocks.Shock> due =
                com.jeladastudios.ftsgeology.quake.Aftershocks.pending(level);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.fts_geology.aftershocks_due", due.size())
                .withStyle(ChatFormatting.GOLD), false);
        net.minecraft.world.phys.Vec3 at = ctx.getSource().getPosition();
        long now = level.getGameTime();
        for (int i = 0; i < Math.min(8, due.size()); i++) {
            var s = due.get(i);
            String m = String.format(java.util.Locale.ROOT, "%.1f", s.magnitude());
            long secs = Math.max(0, (s.at() - now) / 20);
            int dist = (int) Math.round(Math.hypot(s.x() - at.x, s.z() - at.z));
            ctx.getSource().sendSuccess(() -> Component.translatable(s.main()
                            ? "command.fts_geology.aftershocks_main" : "command.fts_geology.aftershocks_line",
                    m, s.x(), s.z(), dist, secs), false);
        }
        return due.size();
    }

    static final String[] SETTINGS = {"subduction", "rift", "collision", "transform", "hotspot", "tube", "valley",
            "everest", "k2", "matterhorn", "sinkhole", "swallowhole", "oil", "fossils", "hydrogen"};
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
