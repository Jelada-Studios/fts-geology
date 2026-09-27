package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.item.GeologyMapItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The key to a geological map's colours, under its name. */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class GeologyMapTooltip {

    private GeologyMapTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        if (!stack.is(Items.FILLED_MAP) || stack.getTag() == null || !stack.getTag().getBoolean(GeologyMapItem.TAG)) return;
        event.getToolTip().add(Component.translatable("item.fts_geology.geology_map.key_rocks").withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable("item.fts_geology.geology_map.key_more").withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable("item.fts_geology.geology_map.key_faults").withStyle(ChatFormatting.GRAY));
    }
}
