package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The player's own settings, from the mod list's Config button: the sky and what is drawn on the rivers, each a switch
 * that takes effect at once and is kept in config/fts_geology-client.toml. Turning the river effects off is the first
 * thing to try on a machine where the rivers cost frames.
 */
public final class ClientConfigScreen extends Screen {

    private final Screen parent;

    public ClientConfigScreen(Screen parent) {
        super(Component.translatable("screen.fts_geology.client_config"));
        this.parent = parent;
    }

    /** The mod list's Config button opens this screen. Called on the client only. */
    public static void register() {
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new ClientConfigScreen(parent)));
    }

    @Override
    protected void init() {
        int x = width / 2 - 155, y = height / 6 + 12, w = 310, h = 20, gap = 24;
        y = toggle(ClientConfig.RIVER_FOAM, "riverFoam", x, y, w, h) + gap;
        y = toggle(ClientConfig.RIVER_FOAM_SHEET, "riverFoamSheet", x, y, w, h) + gap;
        y = toggle(ClientConfig.RIVER_DEBRIS, "riverDebris", x, y, w, h) + gap + 8;
        y = toggle(ClientConfig.STORM_CLOUDS, "stormClouds", x, y, w, h) + gap;
        y = toggle(ClientConfig.SHADER_OVERCAST, "shaderOvercast", x, y, w, h) + gap + 8;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, Math.max(y, height - 28), 200, 20).build());
    }

    private int toggle(ForgeConfigSpec.BooleanValue value, String key, int x, int y, int w, int h) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(value.get())
                .withTooltip(v -> Tooltip.create(Component.translatable("screen.fts_geology.client_config." + key + ".tooltip")))
                .create(x, y, w, h, Component.translatable("screen.fts_geology.client_config." + key),
                        (b, v) -> {
                            value.set(v);
                            ClientConfig.SPEC.save();
                        });
        addRenderableWidget(button);
        return y;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
