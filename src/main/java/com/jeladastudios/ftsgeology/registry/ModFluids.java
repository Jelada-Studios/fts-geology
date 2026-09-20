package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import com.jeladastudios.ftsgeology.fluid.RiverWaterType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The one fluid the mod adds: the standing water of a traced river. */
public final class ModFluids {

    private ModFluids() {}

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, GeysersMod.MODID);
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(ForgeRegistries.FLUIDS, GeysersMod.MODID);

    public static final RegistryObject<FluidType> RIVER_WATER_TYPE =
            FLUID_TYPES.register("river_water", RiverWaterType::new);

    public static final RegistryObject<Fluid> RIVER_WATER =
            FLUIDS.register("river_water", () -> new RiverWaterFluid.Source(properties()));
    public static final RegistryObject<Fluid> FLOWING_RIVER_WATER =
            FLUIDS.register("flowing_river_water", () -> new RiverWaterFluid.Flowing(properties()));

    /**
     * A bucket dipped in a river comes up holding ordinary water, and what the player pours back is ordinary water
     * too. There is no river-water bucket: the fluid is a thing the generator lays, not a thing to carry about.
     */
    private static ForgeFlowingFluid.Properties properties() {
        return new ForgeFlowingFluid.Properties(RIVER_WATER_TYPE, RIVER_WATER, FLOWING_RIVER_WATER)
                .block(() -> (net.minecraft.world.level.block.LiquidBlock) ModBlocks.RIVER_WATER.get())
                .bucket(() -> Items.WATER_BUCKET);
    }
}
