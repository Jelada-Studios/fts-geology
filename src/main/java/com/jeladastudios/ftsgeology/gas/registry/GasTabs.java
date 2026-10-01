package com.jeladastudios.ftsgeology.gas.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.gas.block.entity.GasTankBlockEntity;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.item.BreathingApparatusItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class GasTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, GeysersMod.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.fts_geology.gases"))
            .icon(() -> new ItemStack(GasItems.GAS_DETECTOR.get()))
            .displayItems((params, out) -> {
                for (RegistryObject<net.minecraft.world.item.Item> item : GasItems.ITEMS.getEntries()) out.accept(item.get());
                ItemStack scba = new ItemStack(GasItems.BREATHING_APPARATUS.get());
                BreathingApparatusItem.setOxygen(scba, BreathingApparatusItem.CAPACITY);
                out.accept(scba);
                for (Gas g : new Gas[]{Gas.METHANE, Gas.HYDROGEN, Gas.OXYGEN, Gas.CARBON_DIOXIDE, Gas.CARBON_MONOXIDE, Gas.HYDROGEN_SULFIDE}) {
                    out.accept(filledTank(g, 40.0));
                }
            })
            .build());

    /** A gas tank item pre-filled with a pure gas at {@code atm}. */
    public static ItemStack filledTank(Gas gas, double atm) {
        ItemStack stack = new ItemStack(GasBlocks.GAS_TANK.get());
        GasMix mix = new GasMix();
        mix.add(gas, atm * GasTankBlockEntity.VOLUME * Gas.MOL_PER_BLOCK);
        CompoundTag be = new CompoundTag();
        be.put("gas", mix.save());
        stack.getOrCreateTag().put("BlockEntityTag", be);
        return stack;
    }

    private GasTabs() {
    }
}
