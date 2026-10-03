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
 * Buttons under them set what the machine has to set (the valve's opening, the separator's gas, the sensor's mode);
 * the readings come fresh from the server every second while it is open.
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
        boolean power = data.contains("EnergyMax"), refused = data.contains("Refused");
        ListTag ports = data.getList("Ports", Tag.TAG_COMPOUND);
        // A machine with its openings listed shows each of them; one without, its one tank as before.
        boolean tank = ports.isEmpty() && data.contains("Atm");
        List<FormattedCharSequence> idle = data.contains("Idle")
                ? font.split(Component.translatable("gui.fts_geology.gas_panel.idle." + data.getString("Idle")), W - 16) : List.of();
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Tag t : data.getList("Status", Tag.TAG_STRING)) {
            Component c = Component.Serializer.fromJson(t.getAsString());
            if (c != null) lines.addAll(font.split(c, W - 16));
        }
        ListTag controls = data.getList("Controls", Tag.TAG_COMPOUND);
        int portsH = 0;
        for (int i = 0; i < ports.size(); i++) portsH += portHeight(ports.getCompound(i));
        int h = 22 + idle.size() * 10 + (idle.isEmpty() ? 0 : 4) + (power ? 24 : 0) + (refused ? 12 : 0) + (tank ? 65 : 0)
                + (ports.isEmpty() ? 0 : 12 + portsH) + lines.size() * 10 + 8 + (controls.isEmpty() ? 0 : 22);
        int left = (width - W) / 2, top = Math.max(4, (height - h) / 2);
        g.fill(left, top, left + W, top + h, BG);
        frame(g, left, top, W, h);
        g.fill(left + 1, top + 1, left + W - 1, top + 17, HEADER);
        g.drawString(font, title, left + 8, top + 5, TEXT, false);
        int x = left + 8, w = W - 16, y = top + 22;

        for (FormattedCharSequence line : idle) {
            g.drawString(font, line, x, y, WARN, false);
            y += 10;
        }
        if (!idle.isEmpty()) y += 4;
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
        if (!ports.isEmpty()) {
            g.drawString(font, Component.translatable("gui.fts_geology.gas_panel.ports"), x, y, DIM, false);
            y += 12;
            for (int i = 0; i < ports.size(); i++) {
                CompoundTag p = ports.getCompound(i);
                port(g, p, x, y, w);
                y += portHeight(p);
            }
        }
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, y, TEXT, false);
            y += 10;
        }
        buttons(g, controls, x, y + 4, w, mx, my);
        super.render(g, mx, my, partial);
    }

    /** Where the buttons were drawn last, for the click. */
    private record Hit(int x0, int y0, int x1, int y1, String key) {}

    private final List<Hit> hits = new ArrayList<>();

    /** The machine's buttons in one row: a one-character button narrow, the rest sharing the width left. */
    private void buttons(GuiGraphics g, ListTag controls, int x, int y, int w, int mx, int my) {
        hits.clear();
        if (controls.isEmpty()) return;
        int n = controls.size(), gap = 4, narrow = 20, wide = 0;
        List<Component> labels = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Component c = Component.Serializer.fromJson(controls.getCompound(i).getString("Text"));
            labels.add(c == null ? Component.empty() : c);
            if (labels.get(i).getString().length() > 1) wide++;
        }
        int wideW = wide == 0 ? narrow : (w - gap * (n - 1) - narrow * (n - wide)) / wide;
        int at = x;
        for (int i = 0; i < n; i++) {
            int bw = labels.get(i).getString().length() > 1 ? wideW : narrow;
            boolean over = mx >= at && mx < at + bw && my >= y && my < y + 16;
            g.fill(at, y, at + bw, y + 16, over ? BORDER : HEADER);
            frame(g, at, y, bw, 16);
            Component label = labels.get(i);
            g.drawString(font, label, at + (bw - font.width(label)) / 2, y + 4, TEXT, false);
            hits.add(new Hit(at, y, at + bw, y + 16, controls.getCompound(i).getString("Key")));
            at += bw + gap;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Hit h : hits) {
                if (mx >= h.x0() && mx < h.x1() && my >= h.y0() && my < h.y1()) {
                    Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                            .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    ModNetwork.CHANNEL.sendToServer(new com.jeladastudios.ftsgeology.network.GasControlPacket(pos, h.key()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private static final int IN = 0xFF7BD389, OUT = 0xFFF4A261;

    /**
     * A port's faces, each with its compass direction where it has one and what it meets, as many to a line as fit; a
     * face is never broken over two lines.
     */
    private List<FormattedCharSequence> faces(CompoundTag p, int width) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        net.minecraft.network.chat.MutableComponent line = null;
        ListTag faces = p.getList("Faces", Tag.TAG_COMPOUND);
        for (int i = 0; i < faces.size(); i++) {
            CompoundTag f = faces.getCompound(i);
            String rel = f.getString("Rel"), dir = f.getString("Dir");
            net.minecraft.network.chat.MutableComponent face = Component.translatable("gui.fts_geology.gas_panel.face." + rel);
            if (!dir.equals("up") && !dir.equals("down")) {
                face.append(" (").append(Component.translatable("gui.fts_geology.gas_panel.dir." + dir)).append(")");
            }
            face.append(": ").append(Component.translatable("gui.fts_geology.gas_panel.meets." + f.getString("Meets")));
            if (line != null && font.width(line) + font.width("   ") + font.width(face) <= width) {
                line.append("   ").append(face);
                continue;
            }
            if (line != null) lines.addAll(font.split(line, width));
            line = face;
        }
        if (line != null) lines.addAll(font.split(line, width));
        return lines;
    }

    private int portHeight(CompoundTag p) {
        int faceLines = faces(p, W - 24).size();
        boolean mix = !p.getList("Mix", Tag.TAG_COMPOUND).isEmpty();
        return 10 + faceLines * 10 + (mix ? 10 : 0) + 4;
    }

    /**
     * One opening: an arrow in (green) or out (orange) and its name, the pressure behind it, then its faces and what each
     * meets, then the gases behind it.
     */
    private void port(GuiGraphics g, CompoundTag p, int x, int y, int w) {
        boolean in = p.getBoolean("In"), both = p.getString("Key").equals("stored");
        String arrow = both ? "\u21C4 " : in ? "\u2192 " : "\u2190 ";
        Component name = p.contains("Arg")
                ? Component.translatable("gui.fts_geology.gas_panel.port." + p.getString("Key"), p.getString("Arg"))
                : Component.translatable("gui.fts_geology.gas_panel.port." + p.getString("Key"));
        g.drawString(font, arrow, x, y, both ? PRESSURE : in ? IN : OUT, false);
        g.drawString(font, name, x + font.width(arrow), y, TEXT, false);
        if (p.contains("Atm")) {
            String v = String.format(Locale.ROOT, "%.2f / %.0f atm", p.getDouble("Atm"), Math.max(0.1, p.getDouble("MaxAtm")));
            g.drawString(font, v, x + w - font.width(v), y, TEXT, false);
        }
        y += 10;
        for (FormattedCharSequence line : faces(p, w - 8)) {
            g.drawString(font, line, x + 8, y, DIM, false);
            y += 10;
        }
        ListTag mix = p.getList("Mix", Tag.TAG_COMPOUND);
        if (mix.isEmpty()) return;
        record Part(Gas gas, double share) {}
        List<Part> parts = new ArrayList<>();
        for (Tag t : mix) {
            CompoundTag c = (CompoundTag) t;
            Gas gas = Gas.byId(c.getString("Id"));
            if (gas != null) parts.add(new Part(gas, c.getDouble("F")));
        }
        parts.sort((a, b) -> Double.compare(b.share(), a.share()));
        int lx = x + 8;
        for (int i = 0; i < Math.min(3, parts.size()); i++) {
            Part part = parts.get(i);
            String share = part.share() >= 0.001 ? String.format(Locale.ROOT, " %.0f%%", part.share() * 100)
                    : String.format(Locale.ROOT, " %.0f ppm", part.share() * 1e6);
            Component label = Component.literal(part.gas().formula).append(share);
            int lw = 8 + font.width(label) + 10;
            if (lx + lw > x + w) break;
            g.fill(lx, y + 1, lx + 6, y + 7, 0xFF000000 | part.gas().color);
            g.drawString(font, label, lx + 8, y, TEXT, false);
            lx += lw;
        }
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
