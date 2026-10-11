package com.jeladastudios.ftsgeology.command;

import com.jeladastudios.ftsgeology.quake.PlayerBuilt;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /geology built mark|clear|count <from> <to>}: what the mod counts as built by a player in a box. Blocks a
 * player places are recorded as they are placed; a build pasted in with a schematic or WorldEdit, or set with
 * {@code /fill}, is not, and so quakes and landslides treat it as natural ground. Marking it makes it a build: its walls
 * shake as walls, it is left alone by the ground moving under a fault, and a wall of it holds a slope.
 */
final class BuiltCommands {

    private BuiltCommands() {}

    /** The most blocks one command goes through. */
    private static final int MOST = 1 << 18;

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("built")
                .then(box("mark", Mode.MARK))
                .then(box("clear", Mode.CLEAR))
                .then(box("count", Mode.COUNT));
    }

    private enum Mode { MARK, CLEAR, COUNT }

    private static LiteralArgumentBuilder<CommandSourceStack> box(String name, Mode mode) {
        return Commands.literal(name).then(Commands.argument("from", BlockPosArgument.blockPos())
                .then(Commands.argument("to", BlockPosArgument.blockPos()).executes(ctx -> run(ctx, mode))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Mode mode) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos a = BlockPosArgument.getLoadedBlockPos(ctx, "from"), b = BlockPosArgument.getLoadedBlockPos(ctx, "to");
        long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1)
                * (Math.abs(a.getZ() - b.getZ()) + 1);
        if (volume > MOST) {
            ctx.getSource().sendFailure(Component.literal("Too many blocks (" + volume + "); at most " + MOST));
            return 0;
        }
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (level.getBlockState(p).isAir()) continue;
            switch (mode) {
                case MARK -> {
                    if (!PlayerBuilt.contains(level, p)) {
                        PlayerBuilt.markBuilt(level, p.immutable());
                        n++;
                    }
                }
                case CLEAR -> {
                    if (PlayerBuilt.contains(level, p)) {
                        PlayerBuilt.unmark(level, p.immutable());
                        n++;
                    }
                }
                case COUNT -> {
                    if (PlayerBuilt.contains(level, p)) n++;
                }
            }
        }
        final int count = n;
        String what = switch (mode) {
            case MARK -> "blocks marked as built";
            case CLEAR -> "blocks no longer counted as built";
            case COUNT -> "blocks counted as built";
        };
        ctx.getSource().sendSuccess(() -> Component.literal(count + " " + what), true);
        return count;
    }
}
