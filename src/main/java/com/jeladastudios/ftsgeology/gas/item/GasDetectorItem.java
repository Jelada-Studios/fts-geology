package com.jeladastudios.ftsgeology.gas.item;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.world.GasManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Portable multi-gas monitor. Held: live O\u2082 / LEL / CO / H\u2082S / CO\u2082 reading in the action bar.
 * Anywhere in the inventory: beeps when an alarm set point is exceeded. Use: full analysis.
 */
public class GasDetectorItem extends Item {
    public GasDetectorItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel sl) {
            BlockPos pos = BlockPos.containing(player.getEyePosition());
            report(player, GasManager.get(sl).sample(pos), pos);
            level.playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.4f, 1.6f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public static void report(Player player, GasMix g, BlockPos pos) {
        player.sendSystemMessage(Component.translatable("item.fts_geology.gas_detector.report", pos.getX(), pos.getY(), pos.getZ())
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.translatable("fts_geology.gas.pressure", GasText.atm(g.pressure(1.0)))
                .withStyle(ChatFormatting.GRAY));
        GasText.appendComposition(g, player::sendSystemMessage);
        player.sendSystemMessage(Component.literal("  LEL: " + String.format(Locale.ROOT, "%.0f%%", Combustion.percentLel(g)) + "  ")
                .withStyle(ChatFormatting.GRAY).append(GasText.flammability(g)));
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel sl) || !(entity instanceof ServerPlayer player)) return;
        long t = level.getGameTime();
        if (t % 10 != 0) return;
        boolean held = player.getMainHandItem() == stack || player.getOffhandItem() == stack;
        // Only the first detector in the inventory runs the alarm.
        if (!held && firstDetector(player) != stack) return;
        GasMix g = GasManager.get(sl).sample(BlockPos.containing(player.getEyePosition()));
        boolean alarm = GasText.isAlarm(g);
        if (held) player.displayClientMessage(GasText.detectorLine(g), true);
        if (alarm && t % 20 == 0) {
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.9f, 1.9f);
            if (!held) {
                player.displayClientMessage(Component.translatable("item.fts_geology.gas_detector.alarm").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
            }
        }
    }

    private static ItemStack firstDetector(Player player) {
        for (ItemStack s : player.getInventory().items) {
            if (s.getItem() instanceof GasDetectorItem) return s;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.fts_geology.gas_detector.desc").withStyle(ChatFormatting.GRAY));
    }
}
