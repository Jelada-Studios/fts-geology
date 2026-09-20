package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The one fluid the mod adds: the standing water of a traced river. */
public final class ModFluids {

    private ModFluids() {}

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(ForgeRegistries.FLUIDS, GeysersMod.MODID);

    public static final RegistryObject<Fluid> RIVER_WATER =
            FLUIDS.register("river_water", () -> new RiverWaterFluid.Source(properties()));
    public static final RegistryObject<Fluid> FLOWING_RIVER_WATER =
            FLUIDS.register("flowing_river_water", () -> new RiverWaterFluid.Flowing(properties()));

    /**
     * The fluid is <em>water's own</em> {@link ForgeMod#WATER_TYPE}, not a type of its own, and that one argument is
     * the whole of what makes a river habitable.
     *
     * <p>Forge routes almost everything a player can feel about a fluid through the fluid type -- swimming, boats,
     * putting a fire out, watering a field -- and a type of our own answered all of those. But
     * {@code Entity.wasTouchingWater}, which is what {@code isInWater()} reads, is set by comparing the type against
     * {@code ForgeMod.WATER_TYPE} <em>by identity</em>; the {@code #minecraft:water} tag has no part in it. So with a
     * type of our own a player could swim in a river and a boat could float on it while every fish in it quietly
     * suffocated, because {@code WaterAnimal.handleAirSupply} refills its air only when {@code isInWater()} is true.</p>
     *
     * <p>Water's type gives us the same numbers we had chosen ourselves -- the motion scale, the fall distance, swim,
     * drown, push, extinguish, hydrate and boating are all water's defaults -- plus the underwater screen overlay and
     * vanilla's own pathfinding, and costs nothing. The fluid tag files stay: the underwater fog, {@code isWaterAt},
     * sugar cane and the fishing rod read the tag, not the type.</p>
     *
     * <p>None of this makes a river freeze: {@code Biome.shouldFreeze} asks for {@code Fluids.WATER} itself, and this
     * is not that whatever type it carries.</p>
     *
     * <p>A bucket dipped in a river comes up holding ordinary water, and what the player pours back is ordinary water
     * too. There is no river-water bucket: the fluid is a thing the generator lays, not a thing to carry about.</p>
     */
    private static ForgeFlowingFluid.Properties properties() {
        return new ForgeFlowingFluid.Properties(ForgeMod.WATER_TYPE, RIVER_WATER, FLOWING_RIVER_WATER)
                .block(() -> (net.minecraft.world.level.block.LiquidBlock) ModBlocks.RIVER_WATER.get())
                .bucket(() -> Items.WATER_BUCKET);
    }
}
