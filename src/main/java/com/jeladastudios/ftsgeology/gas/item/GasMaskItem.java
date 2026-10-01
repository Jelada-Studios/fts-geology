package com.jeladastudios.ftsgeology.gas.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Full-face respirator with a combined activated-carbon / hopcalite cartridge. Filters CO, H\u2082S
 * and SO\u2082 \u2014 but, like a real filter mask, gives no protection against lack of oxygen or CO\u2082.
 */
public class GasMaskItem extends ArmorItem {
    public GasMaskItem(Properties props) {
        super(GasArmorMaterials.GAS_MASK, Type.HELMET, props);
    }

    public static boolean hasFilter(ItemStack stack) {
        return stack.getDamageValue() < stack.getMaxDamage() - 1;
    }

    /** Uses up one second of filter life. */
    public static void useFilter(LivingEntity wearer, ItemStack stack, int amount) {
        int left = stack.getMaxDamage() - 1 - stack.getDamageValue();
        stack.setDamageValue(stack.getDamageValue() + Math.min(amount, Math.max(0, left)));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        int left = stack.getMaxDamage() - 1 - stack.getDamageValue();
        tip.add(Component.translatable("item.fts_geology.gas_mask.filter", left / 60, left % 60)
                .withStyle(left > 0 ? ChatFormatting.GRAY : ChatFormatting.RED));
        tip.add(Component.translatable("item.fts_geology.gas_mask.desc").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean canBeDepleted() {
        return true;
    }

    @Override
    public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, T entity, java.util.function.Consumer<T> onBroken) {
        // Armour hits do not wear the filter out.
        return 0;
    }

    @Override
    public @Nullable EquipmentSlot getEquipmentSlot(ItemStack stack) {
        return EquipmentSlot.HEAD;
    }
}
