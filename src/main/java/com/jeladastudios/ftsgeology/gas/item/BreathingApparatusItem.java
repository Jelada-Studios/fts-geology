package com.jeladastudios.ftsgeology.gas.item;

import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.registry.GasCapabilities;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Self-contained breathing apparatus (SCBA). Worn on the chest; while it has oxygen it protects
 * against every gas hazard and lets you breathe under water. Refill by using it on a gas tank or
 * machine that holds oxygen (e.g. the electrolyser's O\u2082 side).
 */
public class BreathingApparatusItem extends ArmorItem {
    public static final double CAPACITY = 180.0;
    /** Mol O\u2082 per second of use (\u2248 a working adult on a 30 minute cylinder). */
    public static final double USE_PER_SECOND = 0.1;

    public BreathingApparatusItem(Properties props) {
        super(GasArmorMaterials.BREATHING_APPARATUS, Type.CHESTPLATE, props);
    }

    public static double getOxygen(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getDouble("oxygen") : 0;
    }

    public static void setOxygen(ItemStack stack, double v) {
        stack.getOrCreateTag().putDouble("oxygen", Mth.clamp(v, 0, CAPACITY));
    }

    /** @return true if it could supply a breath this second */
    public static boolean breathe(LivingEntity wearer, ItemStack stack) {
        double o2 = getOxygen(stack);
        if (o2 <= 0) return false;
        setOxygen(stack, o2 - USE_PER_SECOND);
        if (getOxygen(stack) <= 0 && wearer instanceof Player p) {
            p.displayClientMessage(Component.translatable("item.fts_geology.breathing_apparatus.empty").withStyle(ChatFormatting.RED), true);
        }
        return true;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        GasTank tank = GasCapabilities.tankAt(level, ctx.getClickedPos(), ctx.getClickedFace());
        if (tank == null) tank = GasCapabilities.tankAt(level, ctx.getClickedPos(), null);
        if (tank == null) return InteractionResult.PASS;
        if (!level.isClientSide) {
            ItemStack stack = ctx.getItemInHand();
            double need = CAPACITY - getOxygen(stack);
            double got = tank.gas.takeSpecies(Gas.OXYGEN, need);
            if (got > 0) {
                tank.changed();
                setOxygen(stack, getOxygen(stack) + got);
                level.playSound(null, ctx.getClickedPos(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5f, 1.8f);
            }
            if (ctx.getPlayer() != null) {
                ctx.getPlayer().displayClientMessage(Component.translatable("item.fts_geology.breathing_apparatus.filled",
                        GasText.mol(got), GasText.mol(getOxygen(stack))), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13.0 * getOxygen(stack) / CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x55CCFF;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        double o2 = getOxygen(stack);
        int seconds = (int) (o2 / USE_PER_SECOND);
        tip.add(Component.translatable("item.fts_geology.breathing_apparatus.oxygen", GasText.mol(o2), seconds / 60, seconds % 60)
                .withStyle(o2 > 0 ? ChatFormatting.AQUA : ChatFormatting.RED));
        tip.add(Component.translatable("item.fts_geology.breathing_apparatus.desc").withStyle(ChatFormatting.DARK_GRAY));
    }
}
