package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModFluids;
import com.jeladastudios.ftsgeology.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.FarmlandWaterManager;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The sea is salt, and salt water grows nothing.
 *
 * <p>Water at sea level or below in an ocean or on a beach is the sea's, and a field beside it is not watered by it:
 * the game's own test for water near farmland is answered as if that water were not there (a mixin on its end, see
 * {@code FarmlandSaltMixin}). A bucket dipped in the sea comes up with sea water, a fluid of this mod's that is water
 * in every other way but waters no field wherever it is poured, so it cannot be carried inland to one either. The
 * fresh water -- rivers, lakes, wells, springs, rain -- waters fields as ever, and so does a pond dug by the sea above
 * its level.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class SeaWater {

    private SeaWater() {}

    /** Whether the water at {@code pos} is the sea's: this mod's sea water anywhere, or water at sea level by the sea. */
    public static boolean salty(LevelReader level, BlockPos pos, FluidState fluid) {
        if (fluid.is(ModFluids.SEA_WATER.get()) || fluid.is(ModFluids.FLOWING_SEA_WATER.get())) return true;
        if (!fluid.is(Fluids.WATER) && !fluid.is(Fluids.FLOWING_WATER)) return false;
        if (pos.getY() > level.getSeaLevel()) return false;
        Holder<Biome> biome = level.getBiome(pos);
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_BEACH);
    }

    /**
     * The game's test for water near a field, with the sea's left out: fresh water within four blocks and a level over
     * that waters it by Forge's own rule, or a watering ticket of another mod's.
     */
    public static boolean freshWaterNear(LevelReader level, BlockPos pos) {
        BlockState field = level.getBlockState(pos);
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            FluidState f = level.getFluidState(p);
            if (f.isEmpty()) continue;
            if (field.canBeHydrated(level, pos, f, p) && !salty(level, p, f)) return true;
        }
        return FarmlandWaterManager.hasBlockWaterTicket(level, pos);
    }

    /** Whether salt water is to be told from fresh at all. */
    public static boolean on() {
        return GeyserConfig.SEA_WATER_SALTY.get() && !com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.active();
    }

    /** An empty bucket dipped in the sea comes up with sea water. */
    @SubscribeEvent
    public static void onFillBucket(FillBucketEvent event) {
        if (!on() || !event.getEmptyBucket().is(Items.BUCKET)) return;
        if (!(event.getTarget() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        Level level = event.getLevel();
        BlockPos pos = hit.getBlockPos();
        FluidState fluid = level.getFluidState(pos);
        // This mod's own sea water fills its own bucket already; the sea's water is the game's. Not only a source:
        // Flowing Fluids fills a bucket from water of any level, and its own pickup below says whether there was enough.
        if (!(fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER)) || !salty(level, pos, fluid)) return;
        BlockState state = level.getBlockState(pos);
        Player player = event.getEntity();
        if (player == null || !level.mayInteract(player, pos) || !(state.getBlock() instanceof BucketPickup pickup)) return;
        ItemStack taken = pickup.pickupBlock(level, pos, state);
        if (taken.isEmpty()) return;
        pickup.getPickupSound(state).ifPresent(sound -> player.playSound(sound, 1.0F, 1.0F));
        level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        event.setFilledBucket(new ItemStack(ModItems.SEA_WATER_BUCKET.get()));
        event.setResult(Event.Result.ALLOW);
    }
}
