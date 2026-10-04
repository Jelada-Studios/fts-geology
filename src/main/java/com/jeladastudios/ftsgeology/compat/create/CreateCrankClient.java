package com.jeladastudios.ftsgeology.compat.create;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The crankshaft's half shaft drawn turning as Create draws its motors': by Flywheel's instancing where that runs,
 * else by a block entity renderer. The bearing round it is the block's own model.
 */
final class CreateCrankClient {

    private CreateCrankClient() {}

    static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(CreateCrank.CRANKSHAFT_BE.get(), Renderer::new));
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() ->
                SimpleBlockEntityVisualizer.builder(CreateCrank.CRANKSHAFT_BE.get())
                        .factory(OrientedRotatingVisual.of(AllPartialModels.SHAFT_HALF))
                        .skipVanillaRender(be -> true)
                        .apply()));
    }

    static final class Renderer extends KineticBlockEntityRenderer<CrankshaftBlockEntity> {

        Renderer(BlockEntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        protected SuperByteBuffer getRotatedModel(CrankshaftBlockEntity be, BlockState state) {
            return CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state);
        }
    }
}
