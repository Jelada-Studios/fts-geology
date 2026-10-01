package com.jeladastudios.ftsgeology.gas.item;

import com.jeladastudios.ftsgeology.gas.world.GasManager;
import com.jeladastudios.ftsgeology.gas.world.GasVisuals;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Optical gas imaging camera. Real OGI cameras make hydrocarbon leaks visible through their
 * infrared absorption; this one shows every hazardous gas while held, colour-coded.
 */
public class GasCameraItem extends Item {
    public GasCameraItem(Properties props) {
        super(props);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel sl) || !(entity instanceof ServerPlayer player)) return;
        if (level.getGameTime() % 4 != 0) return;
        if (player.getMainHandItem() != stack && player.getOffhandItem() != stack) return;
        GasVisuals.show(player, GasManager.get(sl), 14);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.fts_geology.gas_camera.desc").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("item.fts_geology.gas_camera.legend").withStyle(ChatFormatting.DARK_GRAY));
    }
}
