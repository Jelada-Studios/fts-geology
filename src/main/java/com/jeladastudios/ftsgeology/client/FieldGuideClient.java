package com.jeladastudios.ftsgeology.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-only handler that instantiates and opens the field guide book screen.
 *
 * <p>Vanilla's book draws fourteen lines of a page and drops the rest, and most of the guide's pages run longer. Each
 * is broken into lines the way the book breaks them and dealt out fourteen to a page, so a long one goes on over the
 * leaf.</p>
 */
public class FieldGuideClient {

    public static final int PAGE_COUNT = 51;

    /** What vanilla's book holds: lines this wide, this many to a page. */
    private static final int WIDTH = 114, LINES = 14;

    public static void openBook() {
        List<FormattedText> pages = pages(Minecraft.getInstance().font);
        Minecraft.getInstance().setScreen(new BookViewScreen(new BookViewScreen.BookAccess() {
            @Override
            public int getPageCount() {
                return pages.size();
            }

            @Override
            public FormattedText getPageRaw(int index) {
                return pages.get(index);
            }
        }));
    }

    private static List<FormattedText> pages(Font font) {
        List<FormattedText> out = new ArrayList<>();
        for (int i = 1; i <= PAGE_COUNT; i++) {
            List<FormattedText> lines = font.getSplitter().splitLines(
                    Component.translatable("book.fts_geology.page." + i), WIDTH, Style.EMPTY);
            int from = 0;
            while (from < lines.size()) {
                // A page going on over the leaf starts at its text, not at the blank line between two paragraphs.
                if (from > 0) {
                    while (from < lines.size() && lines.get(from).getString().isBlank()) from++;
                    if (from >= lines.size()) break;
                }
                int to = Math.min(lines.size(), from + LINES);
                List<FormattedText> page = new ArrayList<>();
                for (int k = from; k < to; k++) {
                    if (k > from) page.add(FormattedText.of("\n"));
                    page.add(lines.get(k));
                }
                out.add(FormattedText.composite(page));
                from = to;
            }
        }
        return out;
    }
}
