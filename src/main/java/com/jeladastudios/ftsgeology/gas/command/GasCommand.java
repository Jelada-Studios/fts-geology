package com.jeladastudios.ftsgeology.gas.command;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.item.GasDetectorItem;
import com.jeladastudios.ftsgeology.gas.world.GasManager;
import com.jeladastudios.ftsgeology.gas.world.GasVisuals;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * /gas info [pos]                       composition at a position
 * /gas add &lt;gas&gt; &lt;moles&gt; [pos]           add gas to one cell
 * /gas fill &lt;gas&gt; &lt;percent&gt; &lt;radius&gt;     flood the connected space around you
 * /gas clear [radius]                   remove gas around you
 * /gas ignite [pos]                     make a spark
 * /gas view                             toggle gas visualisation
 * /gas stats                            simulation statistics
 * </pre>
 */
public final class GasCommand {
    private static final DynamicCommandExceptionType UNKNOWN_GAS =
            new DynamicCommandExceptionType(o -> Component.translatable("commands.fts_geology.gas.unknown_gas", o));
    private static final SuggestionProvider<CommandSourceStack> GASES = (ctx, b) ->
            SharedSuggestionProvider.suggest(Arrays.stream(Gas.VALUES).map(g -> g.id), b);
    private static final Set<UUID> VIEWERS = new HashSet<>();
    private static boolean tickerRegistered;

    private GasCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        // Under /geology, beside the mod's other commands; Brigadier merges the two roots.
        d.register(Commands.literal("geology").then(Commands.literal("gas").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info")
                        .executes(c -> info(c, BlockPos.containing(c.getSource().getPosition())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(c -> info(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))
                .then(Commands.literal("add")
                        .then(Commands.argument("gas", StringArgumentType.word()).suggests(GASES)
                                .then(Commands.argument("moles", DoubleArgumentType.doubleArg(0, 100000))
                                        .executes(c -> add(c, BlockPos.containing(c.getSource().getPosition())))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(c -> add(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))))
                .then(Commands.literal("fill")
                        .then(Commands.argument("gas", StringArgumentType.word()).suggests(GASES)
                                .then(Commands.argument("percent", DoubleArgumentType.doubleArg(0, 100))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 48))
                                                .executes(GasCommand::fill)))))
                .then(Commands.literal("clear")
                        .executes(c -> clear(c, 16))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .executes(c -> clear(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("ignite")
                        .executes(c -> ignite(c, BlockPos.containing(c.getSource().getPosition())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(c -> ignite(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))
                .then(Commands.literal("panel").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> panel(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))
                .then(Commands.literal("tap").executes(GasCommand::tap))
                .then(Commands.literal("sum")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .executes(c -> sum(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("view").executes(GasCommand::view))
                .then(Commands.literal("flames").executes(GasCommand::flames))
                .then(Commands.literal("stats").executes(GasCommand::stats))));
    }

    private static Gas gas(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String id = StringArgumentType.getString(c, "gas");
        Gas g = Gas.byId(id);
        if (g == null) throw UNKNOWN_GAS.create(id);
        return g;
    }

    private static int info(CommandContext<CommandSourceStack> c, BlockPos pos) {
        GasManager mgr = GasManager.get(c.getSource().getLevel());
        GasMix g = mgr.sample(pos);
        CommandSourceStack s = c.getSource();
        s.sendSuccess(() -> Component.translatable("item.fts_geology.gas_detector.report", pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GOLD), false);
        s.sendSuccess(() -> Component.translatable("fts_geology.gas.pressure", GasText.atm(g.pressure(1.0))).withStyle(ChatFormatting.GRAY), false);
        GasText.appendComposition(g, line -> s.sendSuccess(() -> line, false));
        s.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  LEL %.0f%%  ", Combustion.percentLel(g))).append(GasText.flammability(g)), false);
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> c, BlockPos pos) throws CommandSyntaxException {
        Gas g = gas(c);
        double moles = DoubleArgumentType.getDouble(c, "moles");
        GasMix mix = new GasMix();
        mix.add(g, moles);
        boolean ok = GasManager.get(c.getSource().getLevel()).release(pos, mix);
        if (ok) c.getSource().sendSuccess(() -> Component.translatable("commands.fts_geology.gas.add", GasText.mol(moles), g.displayName(), pos.toShortString()), true);
        else c.getSource().sendFailure(Component.translatable("commands.fts_geology.gas.blocked"));
        return ok ? 1 : 0;
    }

    private static int fill(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Gas g = gas(c);
        double f = DoubleArgumentType.getDouble(c, "percent") / 100.0;
        int radius = IntegerArgumentType.getInteger(c, "radius");
        ServerLevel level = c.getSource().getLevel();
        GasManager mgr = GasManager.get(level);
        BlockPos start = BlockPos.containing(c.getSource().getPosition());
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start.asLong());
        seen.add(start.asLong());
        int filled = 0;
        int r2 = radius * radius;
        while (!queue.isEmpty() && filled < 60000) {
            BlockPos p = BlockPos.of(queue.dequeueLong());
            GasMix cell = mgr.getOrCreateCell(p);
            if (cell == null) continue;
            if (g == Gas.OXYGEN || g == Gas.NITROGEN) {
                double o2 = g == Gas.OXYGEN ? f : 1 - f;
                cell.clear();
                cell.add(Gas.OXYGEN, GasManager.N0 * o2);
                cell.add(Gas.NITROGEN, GasManager.N0 * (1 - o2));
            } else {
                cell.setAir(GasManager.N0 * (1 - f));
                cell.add(g, GasManager.N0 * f);
            }
            mgr.markDirty(p);
            filled++;
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (q.distSqr(start) > r2 || seen.contains(q.asLong())) continue;
                seen.add(q.asLong());
                if (mgr.canFlow(p, d)) queue.enqueue(q.asLong());
            }
        }
        int n = filled;
        c.getSource().sendSuccess(() -> Component.translatable("commands.fts_geology.gas.fill", n, g.displayName(), GasText.pct(f)), true);
        return n;
    }

    private static int clear(CommandContext<CommandSourceStack> c, int radius) {
        int n = GasManager.get(c.getSource().getLevel()).clearNear(BlockPos.containing(c.getSource().getPosition()), radius);
        c.getSource().sendSuccess(() -> Component.translatable("commands.fts_geology.gas.clear", n), true);
        return n;
    }

    /**
     * A shaft from the ground down into the gas cap of an oil field under this column, the cap broken into at its
     * bottom: its gas comes up the shaft (see GasFields). For trying a field out; a shaft is what a drill would leave.
     */
    private static int tap(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // The nearest column, out to 48 blocks, whose gas cap is whole rock where it tops out (a cave can cut a cap).
        int x = 0, z = 0, cap = Integer.MIN_VALUE, ground = 0;
        search:
        for (int r = 0; r <= 48; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = at.getX() + dx, cz = at.getZ() + dz;
                    if (!level.hasChunkAt(m.set(cx, 0, cz))) continue;
                    int g = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
                    for (int y = g; y > level.getMinBuildHeight(); y--) {
                        var f = com.jeladastudios.ftsgeology.worldgen.PetroleumFields.at(level, m.set(cx, y, cz));
                        if (f == null || f.zone() != com.jeladastudios.ftsgeology.worldgen.PetroleumFields.Zone.GAS) continue;
                        if (!level.getBlockState(m).is(net.minecraft.world.level.block.Blocks.SANDSTONE)) break;
                        // Whole rock round it and over it, so the shaft is the gas's one way out.
                        boolean whole = true;
                        for (int ox = -1; ox <= 1 && whole; ox++) {
                            for (int oz = -1; oz <= 1 && whole; oz++) {
                                for (int oy = -1; oy <= 3 && whole; oy++) {
                                    if (ox == 0 && oz == 0 && oy >= 0) continue;
                                    BlockPos q = new BlockPos(cx + ox, y + oy, cz + oz);
                                    if (!level.getBlockState(q).isCollisionShapeFullBlock(level, q)) whole = false;
                                }
                            }
                        }
                        if (!whole) break;
                        x = cx;
                        z = cz;
                        cap = y;
                        ground = g;
                        break search;
                    }
                }
            }
        }
        if (cap == Integer.MIN_VALUE) {
            c.getSource().sendFailure(Component.literal("No gas cap under these columns."));
            return 0;
        }
        for (int y = cap + 1; y <= ground; y++) level.setBlock(m.set(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        com.jeladastudios.ftsgeology.gas.world.GasFields.dig(level, new BlockPos(x, cap, z));
        int top = cap, tx = x, tz = z, shaft = ground;
        // Whoever tapped it stands by the wellhead.
        if (c.getSource().getEntity() != null) c.getSource().getEntity().teleportTo(x + 0.5, ground + 1, z + 1.5);
        c.getSource().sendSuccess(() -> Component.literal("Tapped the gas cap at " + tx + " " + top + " " + tz
                + ", a shaft up to " + shaft + "."), true);
        return 1;
    }

    /** Opens a gas machine's gauge panel for the player running it, as a right-click would. */
    private static int panel(CommandContext<CommandSourceStack> c, BlockPos pos) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = c.getSource().getPlayerOrException();
        if (!(c.getSource().getLevel().getBlockEntity(pos) instanceof com.jeladastudios.ftsgeology.gas.block.entity.GasMachineBlockEntity m)) {
            c.getSource().sendFailure(Component.literal("No gas machine there."));
            return 0;
        }
        com.jeladastudios.ftsgeology.network.ModNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new com.jeladastudios.ftsgeology.network.TerminalPacket(pos, m.panelData(), true));
        return 1;
    }

    /** Every gas in the cells within a radius, summed: what a space holds, beyond its air. */
    private static int sum(CommandContext<CommandSourceStack> c, int radius) {
        GasManager mgr = GasManager.get(c.getSource().getLevel());
        double[] total = new double[Gas.COUNT];
        int[] cells = new int[1];
        mgr.forEachCellNear(BlockPos.containing(c.getSource().getPosition()), radius, (p, g) -> {
            cells[0]++;
            for (int i = 0; i < Gas.COUNT; i++) total[i] += g.m[i];
        });
        StringBuilder sb = new StringBuilder();
        for (Gas g : Gas.VALUES) {
            if (g == Gas.OXYGEN || g == Gas.NITROGEN || total[g.ordinal()] < 1e-6) continue;
            sb.append(String.format(Locale.ROOT, " %s %.4f mol;", Gas.asciiFormula(g), total[g.ordinal()]));
        }
        String o2 = String.format(Locale.ROOT, " O2 %.1f%% of the cells' gas", cells[0] == 0 ? 20.95
                : 100.0 * total[Gas.O2] / java.util.Arrays.stream(total).sum());
        String line = "gas sum within " + radius + ": " + cells[0] + " cells," + sb + o2;
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return cells[0];
    }

    private static int flames(CommandContext<CommandSourceStack> c) {
        for (String line : GasManager.get(c.getSource().getLevel()).flames().describe()) {
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int ignite(CommandContext<CommandSourceStack> c, BlockPos pos) {
        boolean ok = GasManager.get(c.getSource().getLevel()).igniteAround(pos, 1, c.getSource().getEntity());
        // Gas too rich to burn by the air: a standing flame where they meet.
        if (!ok) ok = GasManager.get(c.getSource().getLevel()).spark(pos, 1, c.getSource().getEntity(), new double[2]) == GasManager.Spark.LIT;
        if (ok) c.getSource().sendSuccess(() -> Component.translatable("commands.fts_geology.gas.ignite"), true);
        else c.getSource().sendFailure(Component.translatable("commands.fts_geology.gas.not_flammable"));
        return ok ? 1 : 0;
    }

    private static int view(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ensureTicker();
        boolean on;
        if (VIEWERS.remove(player.getUUID())) on = false;
        else {
            VIEWERS.add(player.getUUID());
            on = true;
        }
        c.getSource().sendSuccess(() -> Component.translatable(on ? "commands.fts_geology.gas.view.on" : "commands.fts_geology.gas.view.off"), false);
        return 1;
    }

    private static void ensureTicker() {
        if (tickerRegistered) return;
        tickerRegistered = true;
        MinecraftForge.EVENT_BUS.addListener((TickEvent.PlayerTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer sp)) return;
            if (!VIEWERS.contains(sp.getUUID()) || sp.level().getGameTime() % 4 != 0) return;
            GasVisuals.show(sp, GasManager.get(sp.serverLevel()), 20);
        });
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        GasManager mgr = GasManager.get(c.getSource().getLevel());
        c.getSource().sendSuccess(() -> Component.translatable("commands.fts_geology.gas.stats",
                mgr.activeCellCount(), mgr.lastSweepSize, mgr.loadedChunkCount(), mgr.deflagrationCount(),
                String.format(Locale.ROOT, "%.2f", mgr.lastTickMs), mgr.sweepCount), false);
        return 1;
    }
}
