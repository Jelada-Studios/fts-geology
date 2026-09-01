package com.pandabear.geysers.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pandabear.geysers.GeysersMod;
import com.pandabear.geysers.blockentity.GeyserCoreBlockEntity;
import com.pandabear.geysers.config.GeyserConfig;
import com.pandabear.geysers.worldgen.RetrogenHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.Locale;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Admin command to drop a geyser system exactly where you want one.
 *
 * <ul>
 *   <li>{@code /hydrogeyser spawn} — builds a geyser deep under your feet (random size).</li>
 *   <li>{@code /hydrogeyser spawn <magnitude>} — same, with a chosen size 5–20.</li>
 * </ul>
 *
 * The core is placed near {@code retrogenMinY} below your X/Z; the surface shaft then connects it
 * up through natural ground (build-safe — it won't carve your base). It heats, pressurises and
 * erupts on the normal cycle, so the water works its way to the surface shortly after. Requires
 * permission level 2 (op / cheats on).
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class GeyserCommands {

    private GeyserCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("hydrogeyser")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawn(ctx, -1))
                                .then(Commands.argument("magnitude", IntegerArgumentType.integer(
                                                GeyserCoreBlockEntity.MIN_MAGNITUDE,
                                                GeyserCoreBlockEntity.MAX_MAGNITUDE))
                                        .executes(ctx -> spawn(ctx,
                                                IntegerArgumentType.getInteger(ctx, "magnitude")))))
                        .then(Commands.literal("debug").executes(GeyserCommands::debug)));
    }

    /** Reports the state of the nearest geyser core within 128 blocks — for diagnosing "no water". */
    private static int debug(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        BlockPos found = null;
        GeyserCoreBlockEntity core = null;
        int best = Integer.MAX_VALUE;
        // Geyser cores sit deep (below Y-30), so scan the whole column below the player, not just
        // a box around them. A narrow horizontal band keeps it cheap since the vent is right here.
        BlockPos lo = new BlockPos(at.getX() - 12, level.getMinBuildHeight(), at.getZ() - 12);
        BlockPos hi = new BlockPos(at.getX() + 12, Math.min(at.getY() + 8, level.getMaxBuildHeight()), at.getZ() + 12);
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            if (!(level.getBlockEntity(p) instanceof GeyserCoreBlockEntity c)) continue;
            int d = Math.abs(p.getX() - at.getX()) + Math.abs(p.getZ() - at.getZ());
            if (d < best) { best = d; found = p.immutable(); core = c; }
        }
        if (core == null) {
            source.sendFailure(Component.translatable("command.fts_geology.no_geyser_core_found_in_the_column_here_", level.getMinBuildHeight(), (at.getY() + 8)));
            return 0;
        }
        final BlockPos fp = found;
        final GeyserCoreBlockEntity c = core;
        source.sendSuccess(() -> Component.translatable("command.fts_geology.geyser.debug", fp.getX(), fp.getY(), fp.getZ(), c.getPhase(), dec(c.getTemperatureC(), 0), dec(c.getPressure(), 0), dec(GeyserConfig.PRESSURE_ERUPTION_THRESHOLD.get(), 0), dec(c.getWaterVolume(), 1), dec(c.getRoomVolume(), 1), dec(c.getHeatWeight(), 2), c.getMagnitude(), c.getVentMouthYRaw() == Integer.MIN_VALUE ? "unset" : String.valueOf(c.getVentMouthYRaw()), c.getEruptionTicks()), false);
        // Second line: the structural truth around the core — is it actually ticking, and is the
        // magma bed / chamber water really there? This tells us bug vs. broken-structure at a glance.
        String below = level.getBlockState(fp.below()).getBlock().getName().getString();
        String above = level.getBlockState(fp.above()).getBlock().getName().getString();
        source.sendSuccess(() -> Component.translatable("command.fts_geology.ticks_d_venttopy_s_below_core_1_s_above_", c.getTickCount(), c.getVentTopY() == Integer.MIN_VALUE ? "unset" : String.valueOf(c.getVentTopY()), below, above), false);
        return 1;
    }

    /** See {@code TectonicCommands.dec} - a lang file cannot carry %.Nf, so decimals render here. */
    private static String dec(double v, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", v);
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, int requestedMagnitude) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerLevel level = source.getLevel();
            BlockPos at = BlockPos.containing(source.getPosition());

            // Place deep, but clamp into a valid, below-the-safety-ceiling, above-bedrock band.
            int deepest = level.getMinBuildHeight() + 2;
            int highest = GeyserConfig.RETROGEN_MAX_Y.get() - GeyserConfig.CHAMBER_TARGET_HEIGHT.get() - 3;
            int coreY = Mth.clamp(GeyserConfig.RETROGEN_MIN_Y.get() + 1, deepest, highest);
            BlockPos corePos = new BlockPos(at.getX(), coreY, at.getZ());

            int magnitude = requestedMagnitude > 0
                    ? requestedMagnitude
                    : GeyserCoreBlockEntity.MIN_MAGNITUDE + level.random.nextInt(8); // 5–12 by default

            boolean placed = RetrogenHandler.forcePlace(level, corePos, magnitude, level.random);
            if (placed) {
                final int mag = magnitude;
                source.sendSuccess(() -> Component.translatable("command.fts_geology.geyser_magnitude_s_placed_at_s_s_s_it_wi", mag, corePos.getX(), coreY, corePos.getZ()), true);
                return 1;
            }
            source.sendFailure(Component.translatable("command.fts_geology.couldn_t_place_a_geyser_here_the_deep_co"));
            return 0;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.fts_geology.geyser_spawn_failed_s", e));
            return 0;
        }
    }
}
