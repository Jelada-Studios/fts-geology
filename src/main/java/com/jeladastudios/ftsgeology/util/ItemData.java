package com.jeladastudios.ftsgeology.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * The tag a stack carried on Forge, kept in 1.21's custom data component. Reads are copies; a change goes through
 * {@link #update}, which writes the component back.
 */
public final class ItemData {

    private ItemData() {}

    /** A copy of the stack's tag, or null where it has none. */
    @Nullable
    public static CompoundTag get(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? null : d.copyTag();
    }

    public static boolean has(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_DATA);
    }

    /** A copy of one compound inside the tag, or null. */
    @Nullable
    public static CompoundTag element(ItemStack stack, String key) {
        CompoundTag t = get(stack);
        return t != null && t.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND) ? t.getCompound(key) : null;
    }

    /** Changes the tag, made if there was none. */
    public static void update(ItemStack stack, Consumer<CompoundTag> change) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, change);
    }
}
