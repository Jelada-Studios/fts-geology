package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.blockentity.GeothermalTurbineBlockEntity;
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
import net.minecraftforge.client.model.data.ModelData;

/**
 * Turns a geothermal turbine's rotor. The frame and the generator are the block's own model, drawn with the chunk; the
 * rotor is a model of its own, drawn here each frame at the angle the block entity has turned it to. It turns
 * clockwise seen from above: the way its blades are pitched, the steam coming up through them pushes it round.
 */
public class TurbineRenderer implements BlockEntityRenderer<GeothermalTurbineBlockEntity> {

    public static final ResourceLocation ROTOR = new ResourceLocation(GeysersMod.MODID, "block/geothermal_turbine_rotor");

    public TurbineRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(GeothermalTurbineBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        BakedModel rotor = Minecraft.getInstance().getModelManager().getModel(ROTOR);
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YN.rotationDegrees(be.spin(partialTick)));
        pose.translate(-0.5, 0.0, -0.5);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(),
                buffers.getBuffer(Sheets.solidBlockSheet()), be.getBlockState(), rotor, 1.0f, 1.0f, 1.0f, light, overlay,
                ModelData.EMPTY, RenderType.solid());
        pose.popPose();
    }
}
