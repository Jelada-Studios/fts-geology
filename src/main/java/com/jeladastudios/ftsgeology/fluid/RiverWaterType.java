package com.jeladastudios.ftsgeology.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.SoundActions;
import net.minecraftforge.fluids.FluidType;

import java.util.function.Consumer;

/**
 * What a river's water is made of. Everything a player can feel about it is water's: you swim in it, drown in it,
 * float a boat on it, put a fire out with it and water a field from it, and the {@code #minecraft:water} fluid tag
 * it carries is what tells the rest of the game so.
 *
 * <p>The one thing it does not do is move. Vanilla water is a horizontal surface by definition -- two neighbouring
 * levels can only be kept apart by a solid sill -- so a river built from it has to be a staircase of flat pools with
 * a bar of rock across every step. This one holds whatever level it is given, which lets a river's surface come down
 * the mountain the way the ground does, and lets a fluid mod that moves water about leave it alone.</p>
 *
 * <p>It is also the reason the rivers no longer freeze: vanilla's freezing asks for {@code Fluids.WATER} itself, not
 * for the tag, and this is not that.</p>
 */
public class RiverWaterType extends FluidType {

    private static final ResourceLocation STILL = new ResourceLocation("block/water_still");
    private static final ResourceLocation FLOWING = new ResourceLocation("block/water_flow");
    private static final ResourceLocation OVERLAY = new ResourceLocation("block/water_overlay");

    public RiverWaterType() {
        super(Properties.create()
                .descriptionId("block.fts_geology.river_water")
                .canSwim(true)
                .canDrown(true)
                .canPushEntity(true)
                .canExtinguish(true)
                .canHydrate(true)
                .canConvertToSource(false)
                .supportsBoating(true)
                .motionScale(0.014D)
                .fallDistanceModifier(0.0F)
                .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY));
    }

    /**
     * Drawn with water's own textures and water's own biome colour, so that a river is not a second, slightly
     * different blue running through the world. Forge calls this on a client only; a dedicated server never loads
     * the class it makes.
     */
    @Override
    public void initializeClient(Consumer<net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions> consumer) {
        consumer.accept(new net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return STILL;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return FLOWING;
            }

            @Override
            public ResourceLocation getOverlayTexture() {
                return OVERLAY;
            }

            @Override
            public int getTintColor() {
                return 0xFF3F76E4;
            }

            @Override
            public int getTintColor(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
                return 0xFF000000 | net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(getter, pos);
            }
        });
    }
}
