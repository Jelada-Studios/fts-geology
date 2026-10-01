package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.block.WeatherInstrumentBlock;
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
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Locale;

/**
 * A weather station terminal's screen, drawn in code: four pages -- the readings now, the day's record in charts, the
 * forecast for the days ahead, and the advice for the fields. It asks the server for the station's latest every two
 * seconds while it is open (see {@link TerminalRequestPacket}).
 */
public class WeatherTerminalScreen extends Screen {

    private static final int W = 320, H = 214;
    private static final int BG = 0xF0141D23, PANEL = 0xFF1C2A33, HEADER = 0xFF223A46, BORDER = 0xFF3A6070, TEXT = 0xFFE6F1F5,
            DIM = 0xFF87A2AE, FAINT = 0xFF4A5F69, PRESSURE = 0xFF8ECAE6, TEMP = 0xFFF4A261, HUMID = 0xFF90BE6D, WIND = 0xFFBDB2FF,
            RAIN = 0xFF4EA8DE, WARN = 0xFFE76F51, GOOD = 0xFF2A9D8F, SUN = 0xFFFFD166, CLOUD = 0xFFC9D6DC, DARK_CLOUD = 0xFF6C7A82;
    /** The degree sign and the long dash, from their code points: the sources are compiled as ASCII. */
    private static final String DEG = String.valueOf((char) 0xB0), DASH = String.valueOf((char) 0x2014);
    private static final String[] TABS = {"now", "charts", "forecast", "fields", "stations"};
    private static final int TAB_W = 58, TAB_STEP = 61;
    /** The page the network's stations are on, and the height of a row of their list. */
    private static final int STATIONS = 4, ROW = 11;

    private final BlockPos pos;
    private CompoundTag data;
    private int tab, left, top, ticks, scroll;
    private net.minecraft.client.gui.components.EditBox nameBox;
    private net.minecraft.client.gui.components.Button back;

    private WeatherTerminalScreen(BlockPos pos, CompoundTag data, int tab) {
        super(Component.translatable("gui.fts_geology.terminal.title"));
        this.pos = pos;
        this.data = data;
        this.tab = tab;
    }

    /** A terminal's data from the server: a new screen, or the open one brought up to date. */
    public static void receive(TerminalPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.open()) {
            // Moving between the stations of the network keeps the page that lists them open.
            int tab = p.data().contains("Tab") ? p.data().getInt("Tab")
                    : mc.screen instanceof WeatherTerminalScreen s && s.tab == STATIONS || mc.screen instanceof SeismographScreen ? STATIONS : 0;
            mc.setScreen(new WeatherTerminalScreen(p.pos(), p.data(), tab));
        } else if (mc.screen instanceof WeatherTerminalScreen s && s.pos.equals(p.pos())) {
            s.data = p.data();
        }
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        // On the page of stations: the box to rename this one at the station itself, or the way back from afar.
        String typed = nameBox != null ? nameBox.getValue() : null;
        int by = top + H - 22;
        nameBox = StationUi.nameBox(font, left + 14 + font.width(Component.translatable("gui.fts_geology.station.name")), by, 120,
                pos, data, this::addRenderableWidget);
        if (nameBox != null && typed != null) nameBox.setValue(typed);
        BlockPos via = StationUi.via(pos, data);
        back = via == null ? null : addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                Component.translatable("gui.fts_geology.station.back"), b -> StationUi.open(via, via))
                .bounds(left + 12, by - 1, font.width(Component.translatable("gui.fts_geology.station.back")) + 12, 14).build());
        showWidgets();
    }

    /** The name box and the way back are on the page of stations only. */
    private void showWidgets() {
        if (nameBox != null) nameBox.visible = tab == STATIONS;
        for (var w : children()) {
            if (w instanceof net.minecraft.client.gui.components.Button b) b.visible = tab == STATIONS;
        }
    }

    @Override
    public void tick() {
        if (++ticks % 40 == 0) StationUi.request(pos, data);
        if (nameBox != null) nameBox.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The pages from the keyboard too: 1 to 5, or the arrow keys; Enter in the name box renames the station. */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (nameBox != null && nameBox.isFocused() && tab == STATIONS) {
            if (key == 257 || key == 335) {
                StationUi.rename(pos, nameBox);
                return true;
            }
            if (key != 256) return nameBox.keyPressed(key, scan, modifiers);
        }
        if (key >= 49 && key <= 53) {
            tab = key - 49;
            showWidgets();
            return true;
        }
        if (key == 262 || key == 258) {
            tab = (tab + 1) % TABS.length;
            showWidgets();
            return true;
        }
        if (key == 263) {
            tab = (tab + TABS.length - 1) % TABS.length;
            showWidgets();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = 0; i < TABS.length; i++) {
            int x = left + 8 + i * TAB_STEP;
            if (mx >= x && mx < x + TAB_W && my >= top + 20 && my < top + 34) {
                tab = i;
                showWidgets();
                click();
                return true;
            }
        }
        if (tab == STATIONS) {
            BlockPos station = stationAt(mx, my);
            if (station != null) {
                click();
                StationUi.open(StationUi.terminal(pos, data), station);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tab == STATIONS) {
            int rows = data.getList("Stations", Tag.TAG_COMPOUND).size();
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, rows - listRows()));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(left, top, left + W, top + H, BG);
        frame(g, left, top, W, H, BORDER);
        g.fill(left + 1, top + 1, left + W - 1, top + 17, HEADER);
        String clock = clock(data.getLong("DayTime"));
        Component heading = data.getString("Name").isEmpty() && StationUi.via(pos, data) == null ? title
                : StationUi.title(data, "gui.fts_geology.station.terminal", pos);
        g.drawString(font, font.plainSubstrByWidth(heading.getString(), W - 24 - font.width(clock)), left + 8, top + 5, TEXT, false);
        g.drawString(font, clock, left + W - 8 - font.width(clock), top + 5, DIM, false);
        for (int i = 0; i < TABS.length; i++) {
            int x = left + 8 + i * TAB_STEP;
            boolean on = i == tab, hover = mx >= x && mx < x + TAB_W && my >= top + 20 && my < top + 34;
            g.fill(x, top + 20, x + TAB_W, top + 34, on ? BORDER : hover ? 0xFF2B4652 : PANEL);
            Component label = Component.translatable("gui.fts_geology.terminal.tab." + TABS[i]);
            g.drawString(font, label, x + (TAB_W - font.width(label)) / 2, top + 23, on ? TEXT : DIM, false);
        }
        int cx = left + 8, cy = top + 40, cw = W - 16, ch = H - 48;
        g.fill(cx, cy, cx + cw, cy + ch, PANEL);
        if (data.getBoolean("Empty") && tab != STATIONS) {
            StationUi.centred(g, font, Component.translatable("gui.fts_geology.station.empty"), cx + cw / 2, cy + ch / 2, cw - 24, DIM);
        } else {
            switch (tab) {
                case 0 -> now(g, cx, cy, cw, ch);
                case 1 -> charts(g, cx, cy, cw, ch);
                case 2 -> forecast(g, cx, cy, cw, ch);
                case 3 -> fields(g, cx, cy, cw, ch);
                default -> stations(g, cx, cy, cw, ch, mx, my);
            }
        }
        super.render(g, mx, my, partial);
    }

    // === The network's stations ===============================================

    /** Rows the list shows at once. */
    private int listRows() {
        return (H - 48 - 30) / ROW;
    }

    /**
     * The stations of the network round the terminal it is seen from, the nearest first: each its kind, its name, how far
     * and which way, and whether it is live or how long since it read; beside them, a little map with north up. A click
     * opens one. Under them, the box to rename this station, or the way back from afar.
     */
    private void stations(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        ListTag list = data.getList("Stations", Tag.TAG_COMPOUND);
        BlockPos from = StationUi.terminal(pos, data);
        int lw = w / 2 + 30, rows = listRows();
        if (list.isEmpty()) {
            g.drawString(font, Component.translatable("gui.fts_geology.station.none"), x + 6, y + 6, DIM, false);
        }
        for (int i = 0; i < rows && scroll + i < list.size(); i++) {
            CompoundTag c = list.getCompound(scroll + i);
            BlockPos p = new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z"));
            int ry = y + 4 + i * ROW;
            boolean here = p.equals(pos), hover = mx >= x + 2 && mx < x + lw && my >= ry - 1 && my < ry + ROW - 1;
            if (here || hover) g.fill(x + 2, ry - 1, x + lw, ry + ROW - 1, here ? 0xFF2B4652 : 0xFF243844);
            boolean seismo = "SEISMOGRAPH".equals(c.getString("Kind"));
            g.drawString(font, seismo ? "S" : "M", x + 5, ry + 1, seismo ? WARN : PRESSURE, false);
            String kindKey = seismo ? "gui.fts_geology.station.seismograph" : "gui.fts_geology.station.terminal";
            String name = StationUi.name(c, kindKey, p).getString();
            int dx = p.getX() - from.getX(), dz = p.getZ() - from.getZ();
            int far = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
            String where = p.equals(from) ? Component.translatable("gui.fts_geology.station.this").getString()
                    : far + " " + Component.translatable("prospect.fts_geology.dir."
                    + com.jeladastudios.ftsgeology.instrument.Prospecting.bearingOf(dx, dz)).getString();
            long age = c.getLong("Age");
            boolean live = c.getBoolean("Live");
            int whereW = font.width(where);
            g.drawString(font, font.plainSubstrByWidth(name, lw - 30 - whereW), x + 14, ry + 1, TEXT, false);
            g.drawString(font, where, x + lw - 12 - whereW, ry + 1, DIM, false);
            g.fill(x + lw - 7, ry + 3, x + lw - 3, ry + 7, live ? GOOD : age < 0 ? FAINT : 0xFF8C7A3A);
        }
        if (list.size() > rows) {
            int track = rows * ROW, knob = Math.max(8, track * rows / list.size());
            int ky = y + 3 + (track - knob) * scroll / Math.max(1, list.size() - rows);
            g.fill(x + lw + 1, ky, x + lw + 3, ky + knob, FAINT);
        }
        // The map: the terminal in the middle, north up, the farthest listed at its edge.
        int mx0 = x + lw + 8, size = Math.min(w - lw - 12, h - 30), my0 = y + 4;
        g.fill(mx0, my0, mx0 + size, my0 + size, 0xFF16222A);
        frame(g, mx0, my0, size, size, FAINT);
        int half = size / 2;
        double reach = 64;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            reach = Math.max(reach, Math.hypot(c.getInt("X") - from.getX(), c.getInt("Z") - from.getZ()) * 1.1);
        }
        g.fill(mx0 + half, my0 + 3, mx0 + half + 1, my0 + size - 3, 0xFF22333D);
        g.fill(mx0 + 3, my0 + half, mx0 + size - 3, my0 + half + 1, 0xFF22333D);
        g.drawString(font, "N", mx0 + half - 2, my0 + 2, FAINT, false);
        Component hoverName = null;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            int px = mx0 + half + (int) Math.round((c.getInt("X") - from.getX()) / reach * (half - 4));
            int pz = my0 + half + (int) Math.round((c.getInt("Z") - from.getZ()) / reach * (half - 4));
            boolean seismo = "SEISMOGRAPH".equals(c.getString("Kind"));
            BlockPos p = new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z"));
            if (p.equals(pos)) g.fill(px - 3, pz - 3, px + 4, pz + 4, TEXT);
            g.fill(px - 2, pz - 2, px + 3, pz + 3, seismo ? WARN : PRESSURE);
            if (Math.abs(mx - px) <= 3 && Math.abs(my - pz) <= 3) {
                hoverName = StationUi.name(c, seismo ? "gui.fts_geology.station.seismograph" : "gui.fts_geology.station.terminal", p);
            }
        }
        if (hoverName != null) g.renderTooltip(font, hoverName, mx, my);
        if (nameBox != null) {
            g.drawString(font, Component.translatable("gui.fts_geology.station.name"), x + 4, top + H - 20, DIM, false);
        }
    }

    /** The station under the mouse in the list or on the map, or null. */
    private BlockPos stationAt(double mx, double my) {
        ListTag list = data.getList("Stations", Tag.TAG_COMPOUND);
        int x = left + 8, y = top + 40, w = W - 16, h = H - 48;
        int lw = w / 2 + 30, rows = listRows();
        for (int i = 0; i < rows && scroll + i < list.size(); i++) {
            int ry = y + 4 + i * ROW;
            if (mx >= x + 2 && mx < x + lw && my >= ry - 1 && my < ry + ROW - 1) {
                CompoundTag c = list.getCompound(scroll + i);
                return new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z"));
            }
        }
        BlockPos from = StationUi.terminal(pos, data);
        int mx0 = x + lw + 8, size = Math.min(w - lw - 12, h - 30), my0 = y + 4, half = size / 2;
        double reach = 64;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            reach = Math.max(reach, Math.hypot(c.getInt("X") - from.getX(), c.getInt("Z") - from.getZ()) * 1.1);
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            int px = mx0 + half + (int) Math.round((c.getInt("X") - from.getX()) / reach * (half - 4));
            int pz = my0 + half + (int) Math.round((c.getInt("Z") - from.getZ()) / reach * (half - 4));
            if (Math.abs(mx - px) <= 3 && Math.abs(my - pz) <= 3) return new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z"));
        }
        return null;
    }

    // === The readings now =====================================================

    private void now(GuiGraphics g, int x, int y, int w, int h) {
        CompoundTag now = data.getCompound("Now");
        int tw = (w - 16) / 3, th = (h - 12) / 2;
        WeatherInstrumentBlock.Instrument[] order = {WeatherInstrumentBlock.Instrument.BAROMETER, WeatherInstrumentBlock.Instrument.THERMOMETER,
                WeatherInstrumentBlock.Instrument.HYGROMETER, WeatherInstrumentBlock.Instrument.ANEMOMETER,
                WeatherInstrumentBlock.Instrument.RAIN_GAUGE, WeatherInstrumentBlock.Instrument.SOIL_PROBE};
        for (int i = 0; i < 6; i++) {
            int tx = x + 4 + (i % 3) * (tw + 4), ty = y + 4 + (i / 3) * (th + 4);
            WeatherInstrumentBlock.Instrument in = order[i];
            boolean has = (data.getInt("Has") & (1 << in.ordinal())) != 0;
            g.fill(tx, ty, tx + tw, ty + th, 0xFF223440);
            frame(g, tx, ty, tw, th, has ? colorOf(in) & 0x80FFFFFF : FAINT);
            g.drawString(font, Component.translatable("block.fts_geology." + in.key()), tx + 5, ty + 4, has ? DIM : FAINT, false);
            if (!has) {
                g.drawString(font, Component.translatable("gui.fts_geology.terminal.missing"), tx + 5, ty + th - 13, FAINT, false);
                continue;
            }
            String big, unit;
            Component sub;
            switch (in) {
                case BAROMETER -> {
                    big = String.format(Locale.ROOT, "%.1f", now.getFloat("Pressure"));
                    unit = "hPa";
                    float t = now.getFloat("Tendency");
                    sub = Component.translatable("gui.fts_geology.terminal.tendency", String.format(Locale.ROOT, "%+.1f", t),
                            Component.translatable("message.fts_geology.trend." + trend(t)));
                }
                case THERMOMETER -> {
                    big = String.format(Locale.ROOT, "%.1f", now.getFloat("Temperature"));
                    unit = DEG + "C";
                    sub = Component.empty();
                }
                case HYGROMETER -> {
                    big = String.format(Locale.ROOT, "%.0f", now.getFloat("Humidity"));
                    unit = "%";
                    sub = Component.translatable("gui.fts_geology.terminal.relative");
                }
                case ANEMOMETER -> {
                    big = String.format(Locale.ROOT, "%.0f", now.getFloat("Wind"));
                    unit = "kn";
                    sub = Component.translatable("gui.fts_geology.terminal.from",
                            Component.translatable("message.fts_geology.compass." + WeatherInstrumentBlock.compass(now.getFloat("WindFrom"))),
                            String.format(Locale.ROOT, "%.0f", now.getFloat("WindFrom")));
                }
                case RAIN_GAUGE -> {
                    big = String.format(Locale.ROOT, "%.1f", now.getFloat("RainRate"));
                    unit = "mm/h";
                    sub = Component.translatable("gui.fts_geology.terminal.today", String.format(Locale.ROOT, "%.1f", now.getFloat("RainToday")));
                }
                default -> {
                    if (!now.contains("SoilWater")) {
                        big = DASH;
                        unit = "";
                        sub = Component.translatable("message.fts_geology.instrument.soil_probe.none");
                    } else {
                        big = String.format(Locale.ROOT, "%.0f", now.getFloat("SoilWater") * 100);
                        unit = "%";
                        sub = Component.translatable("message.fts_geology.soil." + now.getString("Soil"));
                    }
                }
            }
            g.pose().pushPose();
            g.pose().translate(tx + 5, ty + 17, 0);
            g.pose().scale(2f, 2f, 1f);
            g.drawString(font, big, 0, 0, colorOf(in), false);
            g.pose().popPose();
            g.drawString(font, unit, tx + 7 + font.width(big) * 2, ty + 24, DIM, false);
            text(g, sub, tx + 5, ty + th - 23, tw - 8, DIM);
        }
    }

    // === The day's record ====================================================

    private void charts(GuiGraphics g, int x, int y, int w, int h) {
        CompoundTag hist = data.getCompound("History");
        String[] series = {"PRESSURE", "TEMPERATURE", "HUMIDITY", "WIND", "RAIN"};
        int[] colors = {PRESSURE, TEMP, HUMID, WIND, RAIN};
        String[] units = {"hPa", DEG + "C", "%", "kn", "mm"};
        int rowH = (h - 8) / 5;
        for (int s = 0; s < 5; s++) {
            int ry = y + 4 + s * rowH;
            ListTag l = hist.getList(series[s], Tag.TAG_FLOAT);
            float[] v = new float[l.size()];
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE, last = Float.NaN;
            for (int i = 0; i < v.length; i++) {
                v[i] = l.getFloat(i);
                if (Float.isNaN(v[i])) continue;
                lo = Math.min(lo, v[i]);
                hi = Math.max(hi, v[i]);
                last = v[i];
            }
            g.drawString(font, Component.translatable("gui.fts_geology.terminal.series." + series[s].toLowerCase(Locale.ROOT)), x + 5, ry + 2, colors[s], false);
            String now = Float.isNaN(last) ? DASH : String.format(Locale.ROOT, s == 0 || s == 1 ? "%.1f %s" : "%.0f %s", last, units[s]);
            if (s == 4 && !Float.isNaN(last)) now = String.format(Locale.ROOT, "%.1f %s", last, units[s]);
            g.drawString(font, now, x + 5, ry + 12, TEXT, false);
            int px = x + 70, pw = w - 110, ph = rowH - 6;
            g.fill(px, ry + 2, px + pw, ry + 2 + ph, 0xFF18252D);
            if (lo > hi) {
                g.drawString(font, Component.translatable("gui.fts_geology.terminal.no_record"), px + 6, ry + ph / 2 - 2, FAINT, false);
                continue;
            }
            if (s == 4) {
                lo = 0;
                hi = Math.max(hi, 1f);
            } else if (hi - lo < (s == 0 ? 4 : 2)) {
                float mid = (hi + lo) / 2, span = s == 0 ? 2 : 1;
                lo = mid - span;
                hi = mid + span;
            }
            g.drawString(font, fmt(hi), px + pw + 4, ry + 2, DIM, false);
            g.drawString(font, fmt(lo), px + pw + 4, ry + ph - 6, DIM, false);
            float step = pw / (float) Math.max(1, v.length - 1);
            float prevX = Float.NaN, prevY = Float.NaN;
            for (int i = 0; i < v.length; i++) {
                if (Float.isNaN(v[i])) {
                    prevX = Float.NaN;
                    continue;
                }
                float vx = px + i * step, vy = ry + 2 + ph - 1 - (v[i] - lo) / (hi - lo) * (ph - 2);
                if (s == 4) {
                    g.fill((int) vx, (int) vy, (int) (vx + Math.max(1, step - 1)), ry + 2 + ph, colors[s]);
                } else if (!Float.isNaN(prevX)) {
                    line(g, prevX, prevY, vx, vy, colors[s]);
                }
                prevX = vx;
                prevY = vy;
            }
        }
        g.drawString(font, Component.translatable("gui.fts_geology.terminal.last_day"), x + 70, y + h - 11, FAINT, false);
    }

    // === The days ahead ======================================================

    private void forecast(GuiGraphics g, int x, int y, int w, int h) {
        if (!data.contains("Forecast")) {
            text(g, Component.translatable("gui.fts_geology.terminal.need_barometer"), x + 8, y + 10, w - 16, DIM);
            return;
        }
        ListTag fc = data.getList("Forecast", Tag.TAG_COMPOUND);
        boolean temps = data.getBoolean("Temps"), winds = data.getBoolean("Winds");
        int n = fc.size(), colW = (w - 8) / 12;
        long dayTime = data.getLong("DayTime");
        int hourNow = (int) ((dayTime / 1000 + 6) % 24);
        long dayNow = Math.floorDiv(dayTime + 6000, 24000);
        int rowTop = y + 6;
        for (int i = 0; i < n; i++) {
            CompoundTag p = fc.getCompound(i);
            int colX = x + 4 + i * colW;
            int from = p.getInt("From");
            int hour = (hourNow + from) % 24;
            long day = Math.floorDiv(dayTime + 6000 + from * 1000L, 24000) - dayNow;
            if (i == 0 || hour < 6) {
                Component d = Component.translatable("gui.fts_geology.terminal.day." + Math.min(day, 3));
                g.drawString(font, d, colX + 1, rowTop, TEXT, false);
            }
            if (i % 2 == 0) g.fill(colX, rowTop + 10, colX + colW, y + h - 34, 0x10FFFFFF);
            g.drawString(font, String.format(Locale.ROOT, "%02d", hour), colX + (colW - 10) / 2, rowTop + 12, DIM, false);
            float rain = p.getFloat("Rain"), heavy = p.getFloat("Heavy"), thunder = p.getFloat("Thunder");
            boolean night = hour >= 20 || hour < 5;
            icon(g, colX + (colW - 16) / 2, rowTop + 24, rain, heavy, thunder, night);
            String pct = String.format(Locale.ROOT, "%d%%", Math.round(rain * 100));
            g.drawString(font, pct, colX + (colW - font.width(pct)) / 2, rowTop + 42, rain >= 0.5f ? RAIN : DIM, false);
            if (temps) {
                String t = String.valueOf(Math.round(p.getFloat("Warm")));
                g.drawString(font, t, colX + (colW - font.width(t)) / 2, rowTop + 54, TEMP, false);
                String c = String.valueOf(Math.round(p.getFloat("Cold")));
                g.drawString(font, c, colX + (colW - font.width(c)) / 2, rowTop + 64, 0xFF8FB8DE, false);
            }
            if (winds) {
                String k = String.format(Locale.ROOT, "%d", Math.round(p.getFloat("Wind")));
                g.drawString(font, k, colX + (colW - font.width(k)) / 2, rowTop + 76, WIND, false);
            }
        }
        int ly = rowTop + 88;
        g.drawString(font, Component.translatable("gui.fts_geology.terminal.legend", temps ? DEG + "C" : DASH, winds ? "kn" : DASH), x + 6, ly, FAINT, false);
        text(g, summary(fc), x + 6, y + h - 32, w - 12, TEXT);
    }

    /** What the next day holds, in a sentence or two. */
    private Component summary(ListTag fc) {
        double dry12 = 1, dry24 = 1, heavy = 0, thunder = 0;
        int firstWet = -1;
        for (int i = 0; i < fc.size(); i++) {
            CompoundTag p = fc.getCompound(i);
            int from = p.getInt("From");
            float r = p.getFloat("Rain");
            if (from < 12) dry12 *= 1 - r;
            if (from < 24) {
                dry24 *= 1 - r;
                heavy = Math.max(heavy, p.getFloat("Heavy"));
                thunder = Math.max(thunder, p.getFloat("Thunder"));
                if (firstWet < 0 && r >= 0.5f) firstWet = from;
            }
        }
        float tendency = data.getCompound("Now").getFloat("Tendency");
        String pressure = tendency < -1.5 ? "falling_fast" : tendency < -0.5 ? "falling" : tendency > 1.5 ? "rising_fast" : tendency > 0.5 ? "rising" : "steady";
        Component air = Component.translatable("gui.fts_geology.terminal.summary.pressure." + pressure);
        String rain12 = String.format(Locale.ROOT, "%d", Math.round((1 - dry12) * 100)), rain24 = String.format(Locale.ROOT, "%d", Math.round((1 - dry24) * 100));
        Component when = firstWet < 0 ? Component.translatable("gui.fts_geology.terminal.summary.dry")
                : firstWet == 0 ? Component.translatable("gui.fts_geology.terminal.summary.wet_now")
                : Component.translatable("gui.fts_geology.terminal.summary.wet_in", firstWet);
        Component extra = thunder > 0.35 ? Component.translatable("gui.fts_geology.terminal.summary.thunder")
                : heavy > 0.4 ? Component.translatable("gui.fts_geology.terminal.summary.heavy") : Component.empty();
        return Component.translatable("gui.fts_geology.terminal.summary", air, rain12, rain24, when, extra);
    }

    // === The fields ==========================================================

    private void fields(GuiGraphics g, int x, int y, int w, int h) {
        CompoundTag now = data.getCompound("Now");
        int ly = y + 8;
        if (now.contains("SoilWater")) {
            float water = now.getFloat("SoilWater");
            g.drawString(font, Component.translatable("gui.fts_geology.terminal.soil_water"), x + 8, ly, DIM, false);
            int bx = x + 8, bw = w - 16;
            g.fill(bx, ly + 11, bx + bw, ly + 21, 0xFF18252D);
            int col = water < 0.35f ? WARN : water > 0.9f ? RAIN : GOOD;
            g.fill(bx, ly + 11, bx + (int) (bw * Mth.clamp(water, 0, 1)), ly + 21, col);
            String pct = String.format(Locale.ROOT, "%d%%", Math.round(water * 100));
            g.drawString(font, pct, bx + bw - font.width(pct) - 3, ly + 12, TEXT, false);
            g.drawString(font, Component.translatable("gui.fts_geology.terminal.soil_line",
                    Component.translatable("message.fts_geology.soil." + now.getString("Soil")), now.getInt("Table")), x + 8, ly + 25, DIM, false);
            ly += 40;
        }
        ListTag advice = data.getList("Advice", Tag.TAG_STRING);
        for (int i = 0; i < advice.size(); i++) {
            String key = advice.getString(i);
            int col = switch (key) {
                case "irrigate", "drought", "frost", "heat", "storm", "waterlogged" -> WARN;
                case "wait_for_rain", "soil_fine", "dry_window" -> GOOD;
                default -> DIM;
            };
            g.fill(x + 8, ly + 2, x + 12, ly + 6, col);
            ly += text(g, Component.translatable("gui.fts_geology.terminal.advice." + key), x + 16, ly, w - 26, TEXT) + 4;
            if (ly > y + h - 10) break;
        }
    }

    // === Drawing ===========================================================

    /** A small picture of a stretch's weather: sun or moon, cloud, rain, a downpour, thunder. */
    private void icon(GuiGraphics g, int x, int y, float rain, float heavy, float thunder, boolean night) {
        if (rain < 0.5f) {
            int c = night ? 0xFFDCE3F0 : SUN;
            g.fill(x + 3, y + 1, x + 11, y + 9, c);
            g.fill(x + 2, y + 2, x + 12, y + 8, c);
            if (!night) {
                g.fill(x + 6, y - 2, x + 8, y, c);
                g.fill(x + 6, y + 10, x + 8, y + 12, c);
                g.fill(x - 1, y + 4, x + 1, y + 6, c);
                g.fill(x + 13, y + 4, x + 15, y + 6, c);
            } else {
                g.fill(x + 6, y + 1, x + 12, y + 7, 0xFF1C2A33);
            }
            if (rain < 0.25f) return;
        }
        int cloud = thunder > 0.35f || heavy > 0.4f ? DARK_CLOUD : CLOUD;
        g.fill(x + 3, y + 5, x + 15, y + 11, cloud);
        g.fill(x + 5, y + 2, x + 11, y + 6, cloud);
        g.fill(x + 1, y + 7, x + 4, y + 11, cloud);
        if (rain < 0.5f) return;
        int drops = heavy > 0.4f ? 5 : 3;
        for (int i = 0; i < drops; i++) g.fill(x + 3 + i * 3, y + 12, x + 4 + i * 3, y + 15, RAIN);
        if (thunder > 0.35f) {
            g.fill(x + 8, y + 10, x + 10, y + 13, SUN);
            g.fill(x + 6, y + 13, x + 9, y + 14, SUN);
            g.fill(x + 6, y + 14, x + 8, y + 17, SUN);
        }
    }

    private static void line(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        int steps = (int) Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int px = Math.round(Mth.lerp(t, x0, x1)), py = Math.round(Mth.lerp(t, y0, y1));
            g.fill(px, py, px + 1, py + 2, color);
        }
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Text wrapped to a width; returns the height it took. */
    private int text(GuiGraphics g, Component c, int x, int y, int w, int color) {
        List<FormattedCharSequence> lines = font.split(c, w);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, y + i * 10, color, false);
        return lines.size() * 10;
    }

    private static int colorOf(WeatherInstrumentBlock.Instrument in) {
        return switch (in) {
            case BAROMETER -> PRESSURE;
            case THERMOMETER -> TEMP;
            case HYGROMETER -> HUMID;
            case ANEMOMETER -> WIND;
            case RAIN_GAUGE -> RAIN;
            case SOIL_PROBE -> GOOD;
            case TILTMETER, GPS, GAS_METER -> DIM;
        };
    }

    private static String trend(float t) {
        return t < -1.5 ? "falling_fast" : t < -0.5 ? "falling" : t > 1.5 ? "rising_fast" : t > 0.5 ? "rising" : "steady";
    }

    private static String fmt(float v) {
        return Math.abs(v) >= 100 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** The game's time of day as a clock: tick 0 is six in the morning. */
    private static String clock(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        int hour = (int) ((t / 1000 + 6) % 24), minute = (int) (t % 1000 * 60 / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }
}
