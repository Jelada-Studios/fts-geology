package com.jeladastudios.ftsgeology.item;

import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A rock hammer. Names what you hit, and reads the section underneath it.
 *
 * <h2>What a geologist actually does with one</h2>
 * The hammer is not for mining. It is for knocking a fresh face off a weathered outcrop so the rock
 * can be identified, and for working out what is stacked underneath - which beds, how thick, in
 * what order. That stack is a <b>section</b>, and reading one is most of field geology: the order
 * of the layers is the order of events, and the thicknesses are how long each one lasted.
 *
 * <p>So this reports two things. The rock you struck, with its class and how rock of that class
 * comes to exist. Then the column below it, collapsed into beds with a real thickness in metres
 * from {@link DepthScale} - not a block count, because the mod's world is a scaled crust and
 * counting blocks would give an answer that means nothing outside Minecraft.</p>
 *
 * <p>It never breaks anything. A hammer that mined would be a worse pickaxe and would also teach
 * the wrong lesson: the instrument is for reading the ground, not removing it.</p>
 */
public class GeologistsHammerItem extends Item {

    /** How far down one strike can read. Deeper than this and you need to dig and strike again. */
    private static final int SECTION_DEPTH = 24;

    /** Beds thinner than this are folded into the one above; a section is not a block list. */
    private static final int MIN_BED = 1;

    public GeologistsHammerItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (ctx.getPlayer() == null) return InteractionResult.PASS;

        BlockState struck = level.getBlockState(pos);
        if (!RockTypes.isRock(struck)) {
            ctx.getPlayer().sendSystemMessage(
                    Component.translatable("message.fts_geology.hammer.not_rock")
                            .withStyle(ChatFormatting.GRAY));
            return InteractionResult.CONSUME;
        }

        RockTypes.Rock rock = RockTypes.classify(struck);
        ctx.getPlayer().sendSystemMessage(Component.translatable("message.fts_geology.hammer.header",
                struck.getBlock().getName(),
                Component.translatable(rock.nameKey())).withStyle(ChatFormatting.GOLD));
        ctx.getPlayer().sendSystemMessage(Component.translatable(rock.originKey())
                .withStyle(ChatFormatting.GRAY));

        for (Component line : section(level, pos)) ctx.getPlayer().sendSystemMessage(line);

        level.playSound(null, pos, SoundEvents.STONE_HIT, SoundSource.PLAYERS, 0.9f, 1.4f);
        ctx.getItemInHand().hurtAndBreak(1, ctx.getPlayer(),
                p -> p.broadcastBreakEvent(ctx.getHand()));
        return InteractionResult.CONSUME;
    }

    /**
     * The column below the struck block, collapsed into beds.
     *
     * <p>Consecutive blocks of the same kind are one bed however many blocks they span, which is
     * what makes the output a section rather than a list. Air and fluids end the reading: you
     * cannot log a bed through a cave, and pretending otherwise would invent a contact that is not
     * there.</p>
     */
    private static List<Component> section(Level level, BlockPos top) {
        List<Component> out = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        BlockState current = null;
        int thickness = 0;
        int beds = 0;
        int floor = Math.max(level.getMinBuildHeight(), top.getY() - SECTION_DEPTH);

        for (int y = top.getY(); y >= floor; y--) {
            BlockState s = level.getBlockState(m.set(top.getX(), y, top.getZ()));
            if (s.isAir() || !s.getFluidState().isEmpty()) break;   // a void, or a cave: stop
            if (current != null && s.is(current.getBlock())) {
                thickness++;
                continue;
            }
            if (current != null) {
                beds += emit(out, current, thickness);
            }
            current = s;
            thickness = 1;
        }
        if (current != null) beds += emit(out, current, thickness);

        if (beds == 0) {
            out.add(Component.translatable("message.fts_geology.hammer.no_section")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            out.add(0, Component.translatable("message.fts_geology.hammer.section",
                    String.valueOf(beds)).withStyle(ChatFormatting.YELLOW));
        }
        return out;
    }

    /** One bed of the log, if it is thick enough to be one. */
    private static int emit(List<Component> out, BlockState s, int blocks) {
        if (blocks < MIN_BED) return 0;
        RockTypes.Rock r = RockTypes.classify(s);
        double metres = blocks * DepthScale.metresPerBlock();
        out.add(Component.translatable("message.fts_geology.hammer.bed",
                        String.format(Locale.ROOT, "%-4s", DepthScale.format(metres)),
                        s.getBlock().getName(),
                        Component.translatable(r.nameKey()))
                .withStyle(ChatFormatting.WHITE));
        return 1;
    }
}
