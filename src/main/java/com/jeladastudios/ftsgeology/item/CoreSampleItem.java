package com.jeladastudios.ftsgeology.item;

import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A drilled core: the beds under one spot, top to bottom, as the drill brought them up. Reads the same in the hand as
 * in the chat the moment it was drilled, so a set of cores from across a valley can be laid out and compared.
 */
public class CoreSampleItem extends Item {

    public CoreSampleItem(Properties props) {
        super(props);
    }

    /** Looked at again: the core read out in the chat, as when it came up. */
    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                                   net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && stack.getTagElement("Core") != null) describe(stack, level, player::sendSystemMessage);
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag core = stack.getTagElement("Core");
        if (core == null) return super.getName(stack);
        return Component.translatable("item.fts_geology.core_sample.from", core.getInt("X"), core.getInt("Z"));
    }

    @Override
    public void appendHoverText(ItemStack stack, @javax.annotation.Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        if (stack.getTagElement("Core") == null) {
            tooltip.add(Component.translatable("item.fts_geology.core_sample.blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        try {
            describe(stack, level, tooltip::add, TOOLTIP_BEDS);
        } catch (IllegalStateException e) {
            // The server's settings are not here yet, as in a menu before a world is joined.
            tooltip.add(Component.translatable("item.fts_geology.core_sample.blank").withStyle(ChatFormatting.GRAY));
        }
    }

    static boolean hasDeposit(ItemStack stack) {
        CompoundTag core = stack.getTagElement("Core");
        if (core == null) return false;
        for (Tag t : core.getList("Deposits", Tag.TAG_COMPOUND)) {
            if (((CompoundTag) t).contains("Genesis")) return true;
        }
        return false;
    }

    /** The core read out: header, beds with their thickness, deposits with what laid them, the water table. */
    /** Beds shown under the name; the chat, when it is drilled, has them all. */
    private static final int TOOLTIP_BEDS = 12;

    public static void describe(ItemStack stack, @javax.annotation.Nullable Level level, Consumer<Component> out) {
        describe(stack, level, out, Integer.MAX_VALUE);
    }

    static void describe(ItemStack stack, @javax.annotation.Nullable Level level, Consumer<Component> out, int most) {
        CompoundTag core = stack.getTagElement("Core");
        if (core == null) return;
        int top = core.getInt("Y");
        out.accept(Component.translatable("item.fts_geology.core_sample.header", core.getInt("X"), core.getInt("Z"),
                DepthScale.format(core.getInt("Drilled") * DepthScale.metresPerBlock()), core.getInt("Drilled"))
                .withStyle(ChatFormatting.GOLD));
        int depth = 0, shown = 0;
        ListTag beds = core.getList("Beds", Tag.TAG_COMPOUND);
        for (Tag t : beds) {
            if (shown++ >= most) {
                out.accept(Component.translatable("item.fts_geology.core_sample.more", beds.size() - most)
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            CompoundTag bed = (CompoundTag) t;
            int blocks = bed.getInt("Blocks");
            String id = bed.getString("Block");
            String from = String.format(Locale.ROOT, "%-7s", DepthScale.format(depth * DepthScale.metresPerBlock()));
            if (id.equals("void")) {
                out.accept(Component.translatable("item.fts_geology.core_sample.void", from,
                        DepthScale.format(blocks * DepthScale.metresPerBlock())).withStyle(ChatFormatting.DARK_GRAY));
            } else {
                Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(id));
                RockTypes.Rock rock = RockTypes.classify(b.defaultBlockState());
                out.accept(Component.translatable("item.fts_geology.core_sample.bed", from,
                        DepthScale.format(blocks * DepthScale.metresPerBlock()), b.getName(),
                        Component.translatable(rock.nameKey())).withStyle(rock == RockTypes.Rock.ORE
                        ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
            }
            depth += blocks;
        }
        ListTag deposits = core.getList("Deposits", Tag.TAG_COMPOUND);
        if (!deposits.isEmpty()) {
            out.accept(Component.translatable("item.fts_geology.core_sample.deposits").withStyle(ChatFormatting.GOLD));
            for (Tag t : deposits) {
                CompoundTag d = (CompoundTag) t;
                Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(d.getString("Block")));
                String at = DepthScale.format(d.getInt("Depth") * DepthScale.metresPerBlock());
                out.accept((d.contains("Genesis")
                        ? Component.translatable("item.fts_geology.core_sample.deposit", at, b.getName(),
                                Component.translatable(d.getString("Genesis")))
                        : Component.translatable("item.fts_geology.core_sample.deposit_plain", at, b.getName()))
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
        int water = top - core.getInt("Water");
        out.accept((water <= 0
                ? Component.translatable("item.fts_geology.core_sample.water_at_surface")
                : Component.translatable("item.fts_geology.core_sample.water",
                        DepthScale.format(water * DepthScale.metresPerBlock()), water)).withStyle(ChatFormatting.AQUA));
    }
}
