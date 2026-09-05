package com.jeladastudios.ftsgeology.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/**
 * Client-only handler that instantiates and opens the field guide book screen.
 */
public class FieldGuideClient {

    public static final int PAGE_COUNT = 18;

    public static void openBook() {
        Minecraft.getInstance().setScreen(new BookViewScreen(new BookViewScreen.BookAccess() {
            @Override
            public int getPageCount() {
                return PAGE_COUNT;
            }

            @Override
            public FormattedText getPageRaw(int index) {
                return Component.translatable("book.fts_geology.page." + (index + 1));
            }
        }));
    }
}
