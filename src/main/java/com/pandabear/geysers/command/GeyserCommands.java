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
            source.sendFailure(Component.literal(
                    "No geyser core found in the column here (scanned "
                            + level.getMinBuildHeight() + " to " + (at.getY() + 8)
                            + "). Stand right on a geyser vent."));
            return 0;
        }
        final BlockPos fp = found;
        final GeyserCoreBlockEntity c = core;
        source.sendSuccess(() -> Component.literal(String.format(
                "Geyser @ %d,%d,%d | phase=%s temp=%.0f°C P=%.0f (thr %.0f) water=%.1f room=%.1f heat=%.2f mag=%d ventMouthY=%s eruptTicks=%d",
                fp.getX(), fp.getY(), fp.getZ(), c.getPhase(), c.getTemperatureC(), c.getPressure(),
                GeyserConfig.PRESSURE_ERUPTION_THRESHOLD.get(),
                c.getWaterVolume(), c.getRoomVolume(), c.getHeatWeight(), c.getMagnitude(),
                c.getVentMouthYRaw() == Integer.MIN_VALUE ? "unset" : String.valueOf(c.getVentMouthYRaw()),
                c.getEruptionTicks())), false);
        // Second line: the structural truth around the core — is it actually ticking, and is the
        // magma bed / chamber water really there? This tells us bug vs. broken-structure at a glance.
        String below = level.getBlockState(fp.below()).getBlock().getName().getString();
        String above = level.getBlockState(fp.above()).getBlock().getName().getString();
        source.sendSuccess(() -> Component.literal(String.format(
                "  ticks=%d ventTopY=%s | below(core-1)=%s above(core+1)=%s",
                c.getTickCount(),
                c.getVentTopY() == Integer.MIN_VALUE ? "unset" : String.valueOf(c.getVentTopY()),
                below, above)), false);
        return 1;
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
                source.sendSuccess(() -> Component.literal(
                        "Geyser (magnitude " + mag + ") placed at " + corePos.getX() + ", " + coreY + ", "
                                + corePos.getZ() + " — it will heat up and surface shortly."), true);
                return 1;
            }
            source.sendFailure(Component.literal(
                    "Couldn't place a geyser here: the deep column below you isn't clear natural rock "
                            + "(a cave, fluid, or build is in the way). Try flat ground away from structures."));
            return 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Geyser spawn failed: " + e));
            return 0;
        }
    }
}
