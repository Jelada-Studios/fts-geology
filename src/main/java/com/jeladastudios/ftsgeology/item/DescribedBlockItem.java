package com.jeladastudios.ftsgeology.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A block item that says in its tooltip how it is used, in a few short lines ({@code <block key>.tooltip.1} and on):
 * a turbine does nothing on its own, and nothing about it says it wants a well.
 */
public class DescribedBlockItem extends BlockItem {

    private final int lines;

    public DescribedBlockItem(Block block, Properties props, int lines) {
        super(block, props);
        this.lines = lines;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        for (int i = 1; i <= lines; i++) {
            tooltip.add(Component.translatable(getDescriptionId() + ".tooltip." + i).withStyle(ChatFormatting.GRAY));
        }
    }
}
