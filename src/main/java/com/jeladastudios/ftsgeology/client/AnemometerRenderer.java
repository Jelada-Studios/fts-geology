package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.block.WeatherInstrumentBlock;
import com.jeladastudios.ftsgeology.blockentity.InstrumentBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Turns an anemometer's cups: the mast is the block's own model, the cups a model of their own drawn here at the angle
 * the wind has spun them to (see {@link InstrumentBlockEntity#clientTick}). The other instruments draw nothing here.
 */
public class AnemometerRenderer implements BlockEntityRenderer<InstrumentBlockEntity> {

    public static final ResourceLocation CUPS = new ResourceLocation(GeysersMod.MODID, "block/anemometer_cups");

    public AnemometerRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(InstrumentBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!(be.getBlockState().getBlock() instanceof WeatherInstrumentBlock b) || b.instrument() != WeatherInstrumentBlock.Instrument.ANEMOMETER) return;
        BakedModel cups = Minecraft.getInstance().getModelManager().getModel(CUPS);
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YN.rotationDegrees(Mth.lerp(partialTick, be.spinO, be.spin)));
        pose.translate(-0.5, 0.0, -0.5);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(),
                buffers.getBuffer(Sheets.cutoutBlockSheet()), be.getBlockState(), cups, 1.0f, 1.0f, 1.0f, light, overlay,
                ModelData.EMPTY, RenderType.cutout());
        pose.popPose();
    }
}
