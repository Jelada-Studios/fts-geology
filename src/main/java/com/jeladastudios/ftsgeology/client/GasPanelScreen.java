package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.network.ModNetwork;
import com.jeladastudios.ftsgeology.network.TerminalPacket;
import com.jeladastudios.ftsgeology.network.TerminalRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A gas machine's gauges, drawn in code: its energy, a voltage it refused lately, its tank's pressure against the most it
 * holds, the mix in the tank as a bar of the gases' colours with the largest named, and the machine's own status lines.
 * Read only; the readings come fresh from the server every second while it is open.
 */
public class GasPanelScreen extends Screen {

    private static final int W = 280;
    private static final int BG = 0xF0141D23, HEADER = 0xFF223A46, BORDER = 0xFF3A6070, TEXT = 0xFFE6F1F5, DIM = 0xFF87A2AE,
            TRACK = 0xFF0E1519, ENERGY = 0xFF2A9D8F, PRESSURE = 0xFF8ECAE6, WARN = 0xFFE76F51;

    private final BlockPos pos;
    private CompoundTag data;
    private int ticks;

    private GasPanelScreen(BlockPos pos, CompoundTag data) {
        super(Component.translatable(data.getString("Name")));
        this.pos = pos;
        this.data = data;
    }

    /** A machine's readings from the server: a new panel, or the open one brought up to date. */
    public static void receive(TerminalPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.open()) {
            mc.setScreen(new GasPanelScreen(p.pos(), p.data()));
        } else if (mc.screen instanceof GasPanelScreen s && s.pos.equals(p.pos())) {
            s.data = p.data();
        }
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0) ModNetwork.CHANNEL.sendToServer(new TerminalRequestPacket(pos));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        boolean power = data.contains("EnergyMax"), tank = data.contains("Atm"), refused = data.contains("Refused");
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Tag t : data.getList("Status", Tag.TAG_STRING)) {
            Component c = Component.Serializer.fromJson(t.getAsString());
            if (c != null) lines.addAll(font.split(c, W - 16));
        }
        int h = 22 + (power ? 24 : 0) + (refused ? 12 : 0) + (tank ? 65 : 0) + lines.size() * 10 + 8;
        int left = (width - W) / 2, top = Math.max(4, (height - h) / 2);
        g.fill(left, top, left + W, top + h, BG);
        frame(g, left, top, W, h);
        g.fill(left + 1, top + 1, left + W - 1, top + 17, HEADER);
        g.drawString(font, title, left + 8, top + 5, TEXT, false);
        int x = left + 8, w = W - 16, y = top + 22;

        if (power) {
            int e = data.getInt("Energy"), max = Math.max(1, data.getInt("EnergyMax"));
            g.drawString(font, Component.translatable("gui.fts_geology.gas_panel.energy"), x, y, DIM, false);
            String v = String.format(Locale.ROOT, "%,d / %,d FE", e, max);
            g.drawString(font, v, x + w - font.width(v), y, TEXT, false);
            bar(g, x, y + 11, w, 6, e / (double) max, ENERGY);
            y += 24;
        }
        if (refused) {
            g.drawString(font, Component.translatable("gui.fts_geology.gas_panel.refused",
                    String.format(Locale.ROOT, "%.0f", data.getDouble("Refused"))), x, y, WARN, false);
            y += 12;
        }
        if (tank) {
            double atm = data.getDouble("Atm"), most = Math.max(0.1, data.getDouble("MaxAtm"));
            g.drawString(font, Component.translatable("gui.fts_geology.gas_panel.tank"), x, y, DIM, false);
            String v = String.format(Locale.ROOT, "%.2f / %.0f atm   %.2f mol", atm, most, data.getDouble("Moles"));
            g.drawString(font, v, x + w - font.width(v), y, TEXT, false);
            bar(g, x, y + 11, w, 6, atm / most, atm / most > 0.8 ? WARN : PRESSURE);
            y += 22;
            g.drawString(font, Component.translatable("gui.fts_geology.gas_panel.mix"), x, y, DIM, false);
            y += 11;
            mix(g, x, y, w);
            y += 32;
        }
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, y, TEXT, false);
            y += 10;
        }
        super.render(g, mx, my, partial);
    }

    /** The tank's mix: one bar of the gases' shares in their colours, and the largest of them named under it. */
    private void mix(GuiGraphics g, int x, int y, int w) {
        record Part(Gas gas, double share) {}
        List<Part> parts = new ArrayList<>();
        for (Tag t : data.getList("Mix", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Gas gas = Gas.byId(c.getString("Id"));
            if (gas != null) parts.add(new Part(gas, c.getDouble("F")));
        }
        g.fill(x, y, x + w, y + 8, TRACK);
        if (parts.isEmpty()) {
            g.drawString(font, Component.translatable("gui.fts_geology.gas_panel.empty"), x, y + 11, DIM, false);
            return;
        }
        double at = 0;
        for (Part p : parts) {
            int a = x + (int) Math.round(at * w), b = x + (int) Math.round((at + p.share()) * w);
            if (b > a) g.fill(a, y, b, y + 8, 0xFF000000 | p.gas().color);
            at += p.share();
        }
        parts.sort((p, q) -> Double.compare(q.share(), p.share()));
        int lx = x, ly = y + 11;
        for (int i = 0; i < Math.min(4, parts.size()); i++) {
            Part p = parts.get(i);
            String share = p.share() >= 0.001 ? String.format(Locale.ROOT, " %.1f%%", p.share() * 100)
                    : String.format(Locale.ROOT, " %.0f ppm", p.share() * 1e6);
            Component label = p.gas().displayName().copy().append(share);
            int lw = 8 + font.width(label) + 10;
            if (lx + lw > x + w) {
                lx = x;
                ly += 10;
            }
            g.fill(lx, ly + 1, lx + 6, ly + 7, 0xFF000000 | p.gas().color);
            g.drawString(font, label, lx + 8, ly, TEXT, false);
            lx += lw;
        }
    }

    private static void bar(GuiGraphics g, int x, int y, int w, int h, double share, int colour) {
        g.fill(x, y, x + w, y + h, TRACK);
        int filled = (int) Math.round(Math.max(0, Math.min(1, share)) * w);
        if (filled > 0) g.fill(x, y, x + filled, y + h, colour);
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BORDER);
    }
}
