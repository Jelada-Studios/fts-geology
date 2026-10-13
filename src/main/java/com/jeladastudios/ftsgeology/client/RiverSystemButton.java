package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.terrain.RiverSystem;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * On the world creation screen's World tab, with Streams Reflowing installed: whose rivers the new world gets
 * ({@link RiverSystem}), Streams' by default. The choice goes with the next world this game makes; a world keeps it.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, value = Dist.CLIENT)
public final class RiverSystemButton {

    private RiverSystemButton() {}

    private static final String WORLD_TAB = "createWorld.tab.world.title";

    private static CycleButton<RiverSystem.Kind> button;
    private static RiverSystem.Kind value = RiverSystem.Kind.STREAMS;
    private static WeakReference<CreateWorldScreen> screenSeen = new WeakReference<>(null);
    private static Field tabs;
    private static boolean tabsLooked;

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen) || !RiverSystem.streamsLoaded()) return;
        // A new screen starts from the default; the same one laid out again (a resize) keeps what was picked.
        if (screenSeen.get() != screen) {
            screenSeen = new WeakReference<>(screen);
            value = RiverSystem.Kind.STREAMS;
        }
        RiverSystem.choose(value);
        int w = 210;
        button = CycleButton.<RiverSystem.Kind>builder(k -> Component.translatable(key(k)))
                .withValues(RiverSystem.Kind.STREAMS, RiverSystem.Kind.OURS)
                .withInitialValue(value)
                .withTooltip(k -> Tooltip.create(Component.translatable(key(k) + ".tooltip")))
                .create(screen.width / 2 - w / 2, screen.height - 58, w, 20, Component.translatable("fts_geology.rivers.label"),
                        (b, v) -> {
                            value = v;
                            RiverSystem.choose(v);
                        });
        button.visible = onWorldTab(screen);
        event.addListener(button);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        if (button != null && event.getScreen() instanceof CreateWorldScreen screen && screenSeen.get() == screen) {
            button.visible = onWorldTab(screen);
            if (button.visible) place(screen);
        }
    }

    /** Under the tab's last row, as wide as its rows: found among the screen's widgets, the tab bar and the buttons at
     * the foot of the screen left out. */
    private static void place(CreateWorldScreen screen) {
        int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (var child : screen.children()) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget w) || w == button || !w.visible) continue;
            if (w.getY() < 30 || w.getY() + w.getHeight() > screen.height - 40) continue;
            left = Math.min(left, w.getX());
            right = Math.max(right, w.getX() + w.getWidth());
            bottom = Math.max(bottom, w.getY() + w.getHeight());
        }
        if (bottom == Integer.MIN_VALUE || right - left < 100) return;
        button.setX(left);
        button.setY(bottom + 8);
        button.setWidth(right - left);
    }

    private static String key(RiverSystem.Kind k) {
        return "fts_geology.rivers." + k.name().toLowerCase(Locale.ROOT);
    }

    /** Whether the screen shows its World tab: by the tab's title, its tab manager found by its type. */
    private static boolean onWorldTab(CreateWorldScreen screen) {
        if (!tabsLooked) {
            tabsLooked = true;
            for (Field f : CreateWorldScreen.class.getDeclaredFields()) {
                if (f.getType() == TabManager.class) {
                    f.setAccessible(true);
                    tabs = f;
                    break;
                }
            }
        }
        if (tabs == null) return true;
        try {
            Tab tab = ((TabManager) tabs.get(screen)).getCurrentTab();
            return tab != null && tab.getTabTitle().getContents() instanceof TranslatableContents t && WORLD_TAB.equals(t.getKey());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }
}
