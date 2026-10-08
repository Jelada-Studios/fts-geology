package com.jeladastudios.ftsgeology.client;


import net.neoforged.fml.common.EventBusSubscriber;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.item.GeologyMapItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.SubscribeEvent;
/** The key to a geological map's colours, under its name. */
@EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class GeologyMapTooltip {

    private GeologyMapTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        if (!stack.is(Items.FILLED_MAP) || stack.getTag() == null || !stack.getTag().getBoolean(GeologyMapItem.TAG)) return;
        event.getToolTip().add(Component.translatable("item.fts_geology.geology_map.key_rocks").withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable("item.fts_geology.geology_map.key_more").withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable("item.fts_geology.geology_map.key_faults").withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable("item.fts_geology.geology_map.key_marks").withStyle(ChatFormatting.GRAY));
    }
}
