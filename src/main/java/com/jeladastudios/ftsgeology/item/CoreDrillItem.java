package com.jeladastudios.ftsgeology.item;

import com.jeladastudios.ftsgeology.advancement.GeologyTrigger;
import com.jeladastudios.ftsgeology.command.InspectCommands;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A hand core drill: bores straight down from the block it is set on and brings up the core, a cylinder of every bed
 * it went through, as a {@link CoreSampleItem} to keep. The column the op command prints, in survival: the beds with
 * their real thickness, any deposit it went through named for the process that laid it, and where the water table
 * stands. A cave on the way is logged as a void and the drill goes on under it.
 */
public class CoreDrillItem extends Item {

    /** Ticks before the drill can be set again: a core takes a while to bring up. */
    private static final int COOLDOWN = 60;
    /** The most beds and deposits a core keeps. */
    private static final int BEDS = 24, DEPOSITS = 12;

    public CoreDrillItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(ctx.getLevel() instanceof ServerLevel level) || !(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        BlockPos top = ctx.getClickedPos();
        ItemStack core = drill(level, top);
        CoreSampleItem.describe(core, level, player::sendSystemMessage);
        if (!player.getInventory().add(core)) player.drop(core, false);

        level.playSound(null, top, SoundEvents.GRINDSTONE_USE, SoundSource.PLAYERS, 1.0f, 0.7f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(top)),
                top.getX() + 0.5, top.getY() + 1.0, top.getZ() + 0.5, 20, 0.2, 0.3, 0.2, 0.1);
        player.getCooldowns().addCooldown(this, COOLDOWN);
        ctx.getItemInHand().hurtAndBreak(1, player, p -> p.broadcastBreakEvent(ctx.getHand()));
        GeologyTrigger.award(player, "core");
        if (CoreSampleItem.hasDeposit(core)) GeologyTrigger.award(player, "core_deposit");
        return InteractionResult.CONSUME;
    }

    /** Bores down from {@code top} and returns the core. */
    public static ItemStack drill(ServerLevel level, BlockPos top) {
        int depth = GeyserConfig.CORE_DRILL_DEPTH.get();
        int floor = Math.max(level.getMinBuildHeight(), top.getY() - depth + 1);
        ListTag beds = new ListTag(), deposits = new ListTag();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        String run = null;
        int count = 0, drilled = 0;
        String lastDeposit = null;
        for (int y = top.getY(); y >= floor; y--) {
            BlockState s = level.getBlockState(m.set(top.getX(), y, top.getZ()));
            if (s.is(Blocks.BEDROCK)) break;
            drilled++;
            String name = s.isAir() || !s.getFluidState().isEmpty() ? "void"
                    : BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if (name.equals(run)) {
                count++;
            } else {
                if (run != null && beds.size() < BEDS) beds.add(bed(run, count));
                run = name;
                count = 1;
            }
            boolean deposit = RockTypes.classify(s) == RockTypes.Rock.ORE;
            if (deposit && !name.equals(lastDeposit) && deposits.size() < DEPOSITS) {
                CompoundTag d = new CompoundTag();
                d.putString("Block", name);
                String genesis = InspectCommands.oreGenesisKey(s);
                if (genesis != null) d.putString("Genesis", genesis);
                d.putInt("Depth", top.getY() - y);
                deposits.add(d);
            }
            lastDeposit = deposit ? name : null;
        }
        if (run != null && beds.size() < BEDS) beds.add(bed(run, count));

        ItemStack core = new ItemStack(ModItems.CORE_SAMPLE.get());
        CompoundTag tag = core.getOrCreateTagElement("Core");
        tag.putInt("X", top.getX());
        tag.putInt("Z", top.getZ());
        tag.putInt("Y", top.getY());
        tag.putInt("Drilled", drilled);
        tag.put("Beds", beds);
        tag.put("Deposits", deposits);
        tag.putInt("Water", com.jeladastudios.ftsgeology.hydrology.WaterTable.tableY(level, top.getX(), top.getZ()));
        return core;
    }

    private static CompoundTag bed(String block, int blocks) {
        CompoundTag t = new CompoundTag();
        t.putString("Block", block);
        t.putInt("Blocks", blocks);
        return t;
    }

    /** What the drill says of itself in the inventory. */
    @Override
    public void appendHoverText(ItemStack stack, @javax.annotation.Nullable net.minecraft.world.level.Level level,
                                java.util.List<Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
        int depth = 64;
        try {
            depth = GeyserConfig.CORE_DRILL_DEPTH.get();
        } catch (IllegalStateException e) {
            // before a world's settings are loaded
        }
        tooltip.add(Component.translatable("item.fts_geology.core_drill.tip", depth).withStyle(ChatFormatting.GRAY));
    }
}
