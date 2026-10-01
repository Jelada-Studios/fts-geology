package com.jeladastudios.ftsgeology.gas.item;

import com.jeladastudios.ftsgeology.gas.block.entity.GasTankBlockEntity;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import com.jeladastudios.ftsgeology.gas.GasText;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Gas tank item; keeps its contents when broken and picked up. */
public class GasTankItem extends BlockItem {
    public GasTankItem(Block block, Properties props) {
        super(block, props);
    }

    public static GasMix contents(ItemStack stack) {
        CompoundTag be = stack.getTagElement("BlockEntityTag");
        return be == null ? new GasMix() : GasMix.load(be.getCompound("gas"));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        GasMix g = contents(stack);
        if (g.isEmpty()) {
            tip.add(Component.translatable("item.fts_geology.gas_tank.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        tip.add(Component.literal(GasText.atm(g.pressure(GasTankBlockEntity.VOLUME)) + ", " + GasText.mol(g.total())).withStyle(ChatFormatting.WHITE));
        GasText.appendComposition(g, tip::add);
    }

    @Override
    public Component getName(ItemStack stack) {
        GasMix g = contents(stack);
        if (g.isEmpty()) return super.getName(stack);
        double o2 = g.fraction(Gas.O2);
        if (o2 > 0.19 && o2 < 0.23 && g.fraction(Gas.N2) > 0.7) {
            return Component.translatable("item.fts_geology.gas_tank.filled", Component.translatable("gas.fts_geology.air"));
        }
        Gas main = Gas.NITROGEN;
        for (Gas gas : Gas.VALUES) {
            if (g.get(gas) > g.get(main)) main = gas;
        }
        return Component.translatable("item.fts_geology.gas_tank.filled", main.displayName());
    }
}
