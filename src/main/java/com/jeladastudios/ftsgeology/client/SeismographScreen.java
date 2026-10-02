package com.jeladastudios.ftsgeology.client;

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
 * A seismograph's screen, drawn in code: the drum's last minute of paper on its three components -- the ground's up and
 * down, and its swing north-south and east-west -- the station's site and the noise it hears over, and its log, the
 * newest last. It asks the server for the drum's latest twice a second while it is open.
 */
public class SeismographScreen extends Screen {

    private static final int W = 320, H = 240;
    private static final int BG = 0xF0161A1E, PANEL = 0xFF1F262C, HEADER = 0xFF2A3239, BORDER = 0xFF56606A, TEXT = 0xFFEDEFF1,
            DIM = 0xFF9AA5AE, PAPER = 0xFFF2ECDA, GRID = 0xFFDCD3BC, MARK = 0xFFC2B89E, INK_Z = 0xFF1E2A5A, INK_N = 0xFF1F4D2E,
            INK_E = 0xFF6A1F1F, WARN = 0xFFE76F51, BUTTON = 0xFF334049, BUTTON_ON = 0xFF46586A;
    private static final String[] COMPONENTS = {"Z", "N", "E"};
    private static final int[] INKS = {INK_Z, INK_N, INK_E};

    private final BlockPos pos;
    private CompoundTag data;
    private int left, top, ticks;
    private net.minecraft.client.gui.components.EditBox nameBox;

    private SeismographScreen(BlockPos pos, CompoundTag data) {
        super(Component.translatable("gui.fts_geology.seismograph.title"));
        this.pos = pos;
        this.data = data;
    }

    /** A drum's data from the server: a new screen, or the open one brought up to date. */
    public static void receive(TerminalPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.open()) {
            mc.setScreen(new SeismographScreen(p.pos(), p.data()));
        } else if (mc.screen instanceof SeismographScreen s && s.pos.equals(p.pos())) {
            s.data = p.data();
        }
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        // Its name, at the station itself: a box in the header after the kind.
        String typed = nameBox != null ? nameBox.getValue() : null;
        nameBox = StationUi.nameBox(font, left + 14 + font.width(title), top + 3, 110, pos, data, this::addRenderableWidget);
        if (nameBox != null && typed != null) nameBox.setValue(typed);
    }

    @Override
    public void tick() {
        // Twice a second at the drum; once a second seen from afar through a terminal.
        if (++ticks % (remote() ? 20 : 10) == 0) StationUi.request(pos, data);
        if (nameBox != null) nameBox.tick();
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (nameBox != null && nameBox.isFocused()) {
            if (key == 257 || key == 335) {
                StationUi.rename(pos, nameBox);
                return true;
            }
            if (key != 256) return nameBox.keyPressed(key, scan, modifiers);
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** Whether this drum is seen from afar, through a terminal. */
    private boolean remote() {
        return StationUi.via(pos, data) != null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The header's button: the log to chat at the drum, or back to the terminal from afar. */
    private Component buttonLabel() {
        return Component.translatable(remote() ? "gui.fts_geology.station.back" : "gui.fts_geology.seismograph.chat");
    }

    private int buttonX() {
        return left + W - 8 - buttonW();
    }

    private int buttonW() {
        return font.width(buttonLabel()) + 12;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int bx = buttonX(), by = top + 3;
        if (mx >= bx && mx < bx + buttonW() && my >= by && my < by + 12) {
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                    .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            BlockPos via = StationUi.via(pos, data);
            if (via != null) {
                StationUi.open(via, via);
                return true;
            }
            ModNetwork.CHANNEL.sendToServer(new TerminalRequestPacket(pos, true));
            onClose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(left, top, left + W, top + H, BG);
        frame(g, left, top, W, H, BORDER);
        g.fill(left + 1, top + 1, left + W - 1, top + 17, HEADER);
        Component heading = remote() || nameBox == null ? StationUi.title(data, "gui.fts_geology.station.seismograph", pos) : title;
        int bx = buttonX(), by = top + 3;
        g.drawString(font, font.plainSubstrByWidth(heading.getString(), bx - left - 14), left + 8, top + 5, TEXT, false);
        boolean hover = mx >= bx && mx < bx + buttonW() && my >= by && my < by + 12;
        g.fill(bx, by, bx + buttonW(), by + 12, hover ? BUTTON_ON : BUTTON);
        g.drawString(font, buttonLabel(), bx + 6, by + 2, TEXT, false);
        if (data.getBoolean("Empty")) {
            StationUi.centred(g, font, Component.translatable("gui.fts_geology.station.empty"), left + W / 2, top + H / 2, W - 40, DIM);
            super.render(g, mx, my, partial);
            return;
        }

        drum(g, left + 8, top + 21, W - 16, 100);
        site(g, left + 8, top + 125, W - 16);
        log(g, left + 8, top + 159, W - 16, H - 165);
        super.render(g, mx, my, partial);
    }

    // === The drum =============================================================

    /** The paper: the three traces, each on its own line, the newest at the right, marked every ten seconds back. */
    private void drum(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PAPER);
        frame(g, x, y, w, h, MARK);
        int label = 26, px = x + label, pw = w - label - 4;
        int samples = com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity.SAMPLES;
        int step = com.jeladastudios.ftsgeology.blockentity.SeismographBlockEntity.STEP;
        // Ten seconds a mark, counted back from now.
        int per = 200 / step;
        for (int i = samples - 1, n = 0; i >= 0; i -= per, n++) {
            int mxp = px + i * pw / samples;
            g.fill(mxp, y + 2, mxp + 1, y + h - 10, n == 0 ? MARK : GRID);
            String s = n == 0 ? "0" : "-" + (n * 10);
            g.drawString(font, s, mxp - font.width(s) / 2, y + h - 9, 0xFF6B6250, false);
        }
        int lane = (h - 12) / 3;
        for (int c = 0; c < 3; c++) {
            int mid = y + 2 + lane * c + lane / 2;
            g.fill(px, mid, px + pw, mid + 1, GRID);
            Component name = Component.translatable("gui.fts_geology.seismograph.component." + COMPONENTS[c].toLowerCase(Locale.ROOT));
            g.drawString(font, name, x + 3, mid - 4, INKS[c], false);
            byte[] trace = data.getByteArray(COMPONENTS[c]);
            if (trace.length == 0) continue;
            int half = lane / 2 - 1, prev = mid;
            for (int i = 0; i < trace.length; i++) {
                int tx = px + i * pw / trace.length;
                int ty = mid - trace[i] * half / 127;
                int lo = Math.min(prev, ty), hi = Math.max(prev, ty);
                if (i == 0) lo = hi = ty;
                g.fill(tx, lo, tx + 1, hi + 1, INKS[c]);
                prev = ty;
            }
        }
    }

    // === The site =============================================================

    /** Where the drum stands and what it hears over: the site on up to two lines, then the noise. */
    private void site(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 30, PANEL);
        Component ground = Component.translatable("gui.fts_geology.seismograph.ground." + data.getInt("Ground"));
        Component line = Component.translatable("gui.fts_geology.seismograph.site", ground, data.getInt("Cover"),
                String.format(Locale.ROOT, "%.3f", data.getFloat("Noise")), String.format(Locale.ROOT, "%.1f", data.getFloat("Hears")));
        List<FormattedCharSequence> wrapped = font.split(line, w - 8);
        for (int i = 0; i < Math.min(2, wrapped.size()); i++) g.drawString(font, wrapped.get(i), x + 4, y + 2 + 10 * i, TEXT, false);
        int why = data.getInt("Why");
        List<String> from = new ArrayList<>();
        String[] names = {"wind", "rain", "moving", "surface"};
        for (int i = 0; i < names.length; i++) {
            if ((why & (1 << i)) != 0) from.add(Component.translatable("gui.fts_geology.seismograph.noise." + names[i]).getString());
        }
        Component second = from.isEmpty() ? Component.translatable("gui.fts_geology.seismograph.quiet")
                : Component.translatable("gui.fts_geology.seismograph.noisy", String.join(", ", from));
        g.drawString(font, second, x + 4, y + 21, from.isEmpty() ? DIM : WARN, false);
    }

    // === The log ==============================================================

    /** The readings, oldest first so the newest is last, as many as fit, and the swarm and blast lines under them. */
    private void log(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        ListTag log = data.getList("Log", Tag.TAG_COMPOUND);
        ListTag extra = data.getList("Extra", Tag.TAG_STRING);
        // The network's fix, the swarm and the blasts under the readings, each on up to three lines: a warning cut at two
        // lost its end ("... may be close!").
        List<FormattedCharSequence> under = new ArrayList<>();
        for (int i = 0; i < extra.size(); i++) {
            Component c = Component.Serializer.fromJson(extra.getString(i));
            if (c == null) continue;
            List<FormattedCharSequence> wrapped = font.split(c, w - 8);
            under.addAll(wrapped.subList(0, Math.min(3, wrapped.size())));
        }
        // The line saying there is nothing yet takes a row of its own: the lines under began on it, one over the other.
        int rows = (h - 14) / 10 - under.size() - (log.isEmpty() ? 1 : 0);
        // The last column, when, is set against the right edge; what is to its left is cut to fit.
        int whenX = x + w - 46;
        int[] cols = {x + 4, x + 40, x + 98, x + 150, x + 174, whenX};
        String[] heads = {"sp", "trace", "distance", "magnitude", "from", "ago"};
        for (int i = 0; i < heads.length; i++) {
            g.drawString(font, Component.translatable("gui.fts_geology.seismograph.head." + heads[i]), cols[i], y + 3, DIM, false);
        }
        g.fill(x + 2, y + 12, x + w - 2, y + 13, BORDER);
        int ry = y + 16;
        if (log.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(Component.translatable("message.fts_geology.seismograph.empty").getString(), w - 8),
                    x + 4, ry, DIM, false);
            ry += 10;
        }
        int first = Math.max(0, log.size() - Math.max(0, rows));
        for (int i = first; i < log.size(); i++) {
            CompoundTag r = log.getCompound(i);
            int color = r.getBoolean("Clip") ? WARN : TEXT;
            g.drawString(font, String.format(Locale.ROOT, "%.1f s", r.getFloat("Sp")), cols[0], ry, color, false);
            g.drawString(font, String.format(Locale.ROOT, r.getFloat("Amp") >= 1000 ? "%.0f mm" : "%.1f mm", r.getFloat("Amp")), cols[1], ry, color, false);
            g.drawString(font, r.getString("Dist"), cols[2], ry, color, false);
            g.drawString(font, String.format(Locale.ROOT, r.getBoolean("Clip") ? "%.1f+" : "%.1f", r.getFloat("M")), cols[3], ry, color, false);
            Component from = Component.Serializer.fromJson(r.getString("From"));
            if (from != null) {
                List<FormattedCharSequence> cut = font.split(from, whenX - cols[4] - 4);
                if (!cut.isEmpty()) g.drawString(font, cut.get(0), cols[4], ry, color, false);
            }
            g.drawString(font, r.getString("Ago"), cols[5], ry, DIM, false);
            ry += 10;
        }
        for (FormattedCharSequence line : under) {
            if (ry + 9 > y + h) break;
            g.drawString(font, line, x + 4, ry, DIM, false);
            ry += 10;
        }
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }
}
