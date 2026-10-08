package com.jeladastudios.ftsgeology.registry;
import net.neoforged.neoforge.registries.DeferredHolder;


import net.minecraft.core.registries.BuiltInRegistries;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.fluid.RiverWaterFluid;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** The mod's fluids: the standing water of a traced river, and sea water carried off the sea in a bucket. */
public final class ModFluids {

    private ModFluids() {}

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(BuiltInRegistries.FLUID, GeysersMod.MODID);

    public static final DeferredHolder<Fluid, Fluid> RIVER_WATER =
            FLUIDS.register("river_water", () -> new RiverWaterFluid.Source(properties()));
    public static final DeferredHolder<Fluid, Fluid> FLOWING_RIVER_WATER =
            FLUIDS.register("flowing_river_water", () -> new RiverWaterFluid.Flowing(properties()));

    /**
     * Sea water, carried off the sea in a bucket: water in every way (water's own type, for the reasons below), and it
     * flows and spreads as water does, but it waters no field; see {@link com.jeladastudios.ftsgeology.hydrology.SeaWater}.
     */
    public static final DeferredHolder<Fluid, Fluid> SEA_WATER =
            FLUIDS.register("sea_water", () -> new BaseFlowingFluid.Source(seaProperties()));
    public static final DeferredHolder<Fluid, Fluid> FLOWING_SEA_WATER =
            FLUIDS.register("flowing_sea_water", () -> new BaseFlowingFluid.Flowing(seaProperties()));

    public static final DeferredRegister<net.neoforged.neoforge.fluids.FluidType> FLUID_TYPES =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.FLUID_TYPES, GeysersMod.MODID);

    /**
     * Crude oil from a well head on an oil field: heavy, slow, black, burning rather than putting a fire out. In the
     * {@code forge:crude_oil} tag, so another mod's refinery takes it (Create Diesel Generators' distillation does).
     * Drawn with water's own textures, darkened: no texture of its own.
     */
    public static final DeferredHolder<net.neoforged.neoforge.fluids.FluidType, net.neoforged.neoforge.fluids.FluidType> CRUDE_OIL_TYPE = FLUID_TYPES.register("crude_oil",
            () -> new net.neoforged.neoforge.fluids.FluidType(net.neoforged.neoforge.fluids.FluidType.Properties.create()
                    .descriptionId("fluid_type.fts_geology.crude_oil")
                    .density(870).viscosity(8000).temperature(300)
                    .motionScale(0.002).canSwim(false).canExtinguish(false).canHydrate(false).supportsBoating(false)
                    .fallDistanceModifier(0.5F)
                    .sound(net.neoforged.neoforge.common.SoundActions.BUCKET_FILL, net.minecraft.sounds.SoundEvents.BUCKET_FILL)
                    .sound(net.neoforged.neoforge.common.SoundActions.BUCKET_EMPTY, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY)) {
                @Override
                public void initializeClient(java.util.function.Consumer<net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions> consumer) {
                    consumer.accept(new net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions() {
                        private static final net.minecraft.resources.ResourceLocation STILL =
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_still");
                        private static final net.minecraft.resources.ResourceLocation FLOW =
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_flow");

                        @Override
                        public net.minecraft.resources.ResourceLocation getStillTexture() {
                            return STILL;
                        }

                        @Override
                        public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                            return FLOW;
                        }

                        @Override
                        public int getTintColor() {
                            return 0xFF2B1D12;
                        }
                    });
                }
            });

    public static final DeferredHolder<Fluid, Fluid> CRUDE_OIL =
            FLUIDS.register("crude_oil", () -> new BaseFlowingFluid.Source(oilProperties()));
    public static final DeferredHolder<Fluid, Fluid> FLOWING_CRUDE_OIL =
            FLUIDS.register("flowing_crude_oil", () -> new BaseFlowingFluid.Flowing(oilProperties()));

    private static BaseFlowingFluid.Properties oilProperties() {
        return new BaseFlowingFluid.Properties(CRUDE_OIL_TYPE, CRUDE_OIL, FLOWING_CRUDE_OIL)
                .block(() -> (net.minecraft.world.level.block.LiquidBlock) ModBlocks.CRUDE_OIL.get())
                .bucket(() -> ModItems.CRUDE_OIL_BUCKET.get())
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(30).explosionResistance(100.0F);
    }

    private static BaseFlowingFluid.Properties seaProperties() {
        return new BaseFlowingFluid.Properties(NeoForgeMod.WATER_TYPE::value, SEA_WATER, FLOWING_SEA_WATER)
                .block(() -> (net.minecraft.world.level.block.LiquidBlock) ModBlocks.SEA_WATER.get())
                .bucket(() -> ModItems.SEA_WATER_BUCKET.get())
                .slopeFindDistance(4).levelDecreasePerBlock(1).tickRate(5).explosionResistance(100.0F);
    }

    /**
     * The fluid is <em>water's own</em> {@link ForgeMod#WATER_TYPE}, not a type of its own, and that one argument is
     * the whole of what makes a river habitable.
     *
     * <p>Forge routes almost everything a player can feel about a fluid through the fluid type -- swimming, boats,
     * putting a fire out, watering a field -- and a type of our own answered all of those. But
     * {@code Entity.wasTouchingWater}, which is what {@code isInWater()} reads, is set by comparing the type against
     * {@code NeoForgeMod.WATER_TYPE} <em>by identity</em>; the {@code #minecraft:water} tag has no part in it. So with a
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
    private static BaseFlowingFluid.Properties properties() {
        return new BaseFlowingFluid.Properties(NeoForgeMod.WATER_TYPE::value, RIVER_WATER, FLOWING_RIVER_WATER)
                .block(() -> (net.minecraft.world.level.block.LiquidBlock) ModBlocks.RIVER_WATER.get())
                .bucket(() -> Items.WATER_BUCKET);
    }
}
