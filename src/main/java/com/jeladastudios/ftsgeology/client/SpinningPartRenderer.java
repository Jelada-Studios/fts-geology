package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Turns a machine's moving part while it runs: a ventilation fan's blades, a gas engine's flywheel. The rest of the
 * machine is its block's own model, drawn with the chunk; the part is a model of its own, set as the block faces and
 * turned about its axle here each frame. It spins up over a second when the machine starts and runs down when it stops.
 */
public class SpinningPartRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    public static final ResourceLocation FAN_BLADES = new ResourceLocation(GeysersMod.MODID, "block/ventilation_fan_blades");
    public static final ResourceLocation FLYWHEEL = new ResourceLocation(GeysersMod.MODID, "block/gas_engine_flywheel");

    private final ResourceLocation model;
    private final Axis axis;
    private final float px, py, pz, degreesPerTick;
    /** Each machine's part: its angle, its speed and when it was last drawn. */
    private final Long2ObjectOpenHashMap<float[]> turning = new Long2ObjectOpenHashMap<>();

    private SpinningPartRenderer(ResourceLocation model, Axis axis, float px, float py, float pz, float degreesPerTick) {
        this.model = model;
        this.axis = axis;
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.degreesPerTick = degreesPerTick;
    }

    /** The fan's blades, about the duct's long axis through its middle. */
    public static <T extends BlockEntity> BlockEntityRendererProvider<T> fan() {
        return ctx -> new SpinningPartRenderer<>(FAN_BLADES, Axis.ZP, 0.5f, 0.5f, 0.5f, 36f);
    }

    /** The engine's flywheel, about its axle on the engine's side. */
    public static <T extends BlockEntity> BlockEntityRendererProvider<T> flywheel() {
        return ctx -> new SpinningPartRenderer<>(FLYWHEEL, Axis.XP, 15f / 16f, 7.5f / 16f, 9.5f / 16f, 24f);
    }

    @Override
    public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null) return;
        BlockState s = be.getBlockState();
        boolean on = s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT);
        if (turning.size() > 512) turning.clear();
        float[] t = turning.computeIfAbsent(be.getBlockPos().asLong(), k -> new float[]{0f, 0f, -1f});
        float now = be.getLevel().getGameTime() + partialTick;
        float dt = t[2] < 0 ? 0f : Mth.clamp(now - t[2], 0f, 5f);
        t[2] = now;
        t[1] += ((on ? degreesPerTick : 0f) - t[1]) * Math.min(1f, 0.06f * dt);
        t[0] = (t[0] + t[1] * dt) % 360f;

        Direction f = s.hasProperty(BlockStateProperties.FACING) ? s.getValue(BlockStateProperties.FACING)
                : s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        // As the block state turns the model (north is the model's own front).
        int x = f == Direction.UP ? 270 : f == Direction.DOWN ? 90 : 0;
        int y = switch (f) {
            case SOUTH -> 180;
            case EAST -> 90;
            case WEST -> 270;
            default -> 0;
        };
        BakedModel part = Minecraft.getInstance().getModelManager().getModel(model);
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-y));
        pose.mulPose(Axis.XP.rotationDegrees(-x));
        pose.translate(-0.5, -0.5, -0.5);
        pose.translate(px, py, pz);
        pose.mulPose(axis.rotationDegrees(t[0]));
        pose.translate(-px, -py, -pz);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(),
                buffers.getBuffer(Sheets.solidBlockSheet()), s, part, 1.0f, 1.0f, 1.0f, light, overlay, ModelData.EMPTY,
                RenderType.solid());
        pose.popPose();
    }
}
