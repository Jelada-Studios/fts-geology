package com.jeladastudios.ftsgeology.gas.block.entity;

import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasTank;
import com.jeladastudios.ftsgeology.gas.GasText;
import com.jeladastudios.ftsgeology.gas.registry.GasBlockEntities;
import com.jeladastudios.ftsgeology.gas.registry.GasTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Anaerobic digester. Bacteria break down organic matter into biogas: roughly 60 % methane,
 * 39.5 % CO\u2082 and a trace (0.5 %) of hydrogen sulfide. Gas leaves at the top; with nothing
 * attached it bubbles out into the room.
 */
public class BiogasDigesterBlockEntity extends GasMachineBlockEntity {
    public static final double MOL_PER_ITEM = 12.0;
    public static final double RATE = 0.03;
    public static final int TICKS_PER_ITEM = 400;
    public static final double MAX_PRESSURE = 5.0;

    private final ItemStackHandler items = new ItemStackHandler(9) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return stack.is(GasTags.BIOMASS);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private final LazyOptional<IItemHandler> itemCap = LazyOptional.of(() -> items);
    private final GasTank out = new GasTank(1.0, MAX_PRESSURE).onChange(this::setChanged);
    private double pending;
    private int feedTimer;

    public BiogasDigesterBlockEntity(BlockPos pos, BlockState state) {
        super(GasBlockEntities.BIOGAS_DIGESTER.get(), pos, state);
    }

    @Override
    public @Nullable GasTank tankFor(@Nullable Direction side) {
        return side == null || side == Direction.UP ? out : null;
    }

    @Override
    public InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(GasTags.BIOMASS)) {
            ItemStack rest = ItemHandlerHelper.insertItem(items, held.copy(), false);
            int used = held.getCount() - rest.getCount();
            if (used > 0) {
                if (!player.getAbilities().instabuild) held.shrink(used);
                level.playSound(null, worldPosition, SoundEvents.COMPOSTER_FILL, SoundSource.BLOCKS, 1.0f, 0.8f);
            }
            return InteractionResult.CONSUME;
        }
        return super.onUse(player, hand, hit);
    }

    private int itemCount() {
        int n = 0;
        for (int i = 0; i < items.getSlots(); i++) n += items.getStackInSlot(i).getCount();
        return n;
    }

    @Override
    public void serverTick() {
        ventIfUnconnected(out, Direction.UP, 1.15);
        if (pending < MOL_PER_ITEM && ++feedTimer >= TICKS_PER_ITEM) {
            feedTimer = 0;
            for (int i = 0; i < items.getSlots(); i++) {
                if (!items.getStackInSlot(i).isEmpty()) {
                    items.extractItem(i, 1, false);
                    pending += MOL_PER_ITEM;
                    break;
                }
            }
        }
        double mol = Math.min(RATE, Math.min(pending, out.roomUntil(MAX_PRESSURE)));
        if (mol <= 1e-5) {
            setLit(false);
            return;
        }
        out.gas.add(Gas.METHANE, mol * 0.60);
        out.gas.add(Gas.CARBON_DIOXIDE, mol * 0.395);
        out.gas.add(Gas.HYDROGEN_SULFIDE, mol * 0.005);
        out.changed();
        pending -= mol;
        setLit(true);
        if (level.getGameTime() % 20 == 0) {
            ((ServerLevel) level).sendParticles(ParticleTypes.BUBBLE_POP, worldPosition.getX() + 0.5, worldPosition.getY() + 1.02,
                    worldPosition.getZ() + 0.5, 1, 0.3, 0, 0.3, 0);
        }
    }

    @Override
    public List<Component> status() {
        List<Component> o = super.status();
        o.add(Component.translatable("block.fts_geology.biogas_digester").withStyle(ChatFormatting.GOLD));
        o.add(Component.translatable("block.fts_geology.biogas_digester.status", itemCount(), GasText.mol(pending), GasText.atm(out.pressure()))
                .withStyle(ChatFormatting.WHITE));
        o.add(Component.translatable("block.fts_geology.biogas_digester.help").withStyle(ChatFormatting.DARK_GRAY));
        return o;
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) return itemCap.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCap.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("items", items.serializeNBT());
        tag.put("gas", out.save());
        tag.putDouble("pending", pending);
        tag.putInt("feed", feedTimer);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.deserializeNBT(tag.getCompound("items"));
        out.load(tag.getCompound("gas"));
        pending = tag.getDouble("pending");
        feedTimer = tag.getInt("feed");
    }

    @Override
    public void onRemoved() {
        for (int i = 0; i < items.getSlots(); i++) {
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), items.getStackInSlot(i));
        }
        if (!out.gas.isEmpty()) gas().release(worldPosition, out.gas);
    }
}
