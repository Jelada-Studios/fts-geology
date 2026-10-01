package com.jeladastudios.ftsgeology.client;

import com.jeladastudios.ftsgeology.network.ModNetwork;
import com.jeladastudios.ftsgeology.network.StationRenamePacket;
import com.jeladastudios.ftsgeology.network.TerminalRequestPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * What the station screens share (see {@code StationNetwork}): a station's name in the header, and when it is seen from
 * afar, through which terminal and how long since it last read; the request for its latest, straight or through that
 * terminal; and the box to rename it, at the station itself.
 */
final class StationUi {

    private StationUi() {}

    /** The terminal a screen's station is seen through, or null where it is opened at the station itself. */
    static BlockPos via(BlockPos pos, CompoundTag data) {
        if (!data.contains("Via")) return null;
        BlockPos via = BlockPos.of(data.getLong("Via"));
        return via.equals(pos) ? null : via;
    }

    /** The terminal a terminal's own screen lists the network from: itself, or the one it is seen through. */
    static BlockPos terminal(BlockPos pos, CompoundTag data) {
        return data.contains("Via") ? BlockPos.of(data.getLong("Via")) : pos;
    }

    /** Asks for the station's latest: straight, or through the terminal it is seen from. */
    static void request(BlockPos pos, CompoundTag data) {
        BlockPos via = via(pos, data);
        ModNetwork.CHANNEL.sendToServer(via == null ? new TerminalRequestPacket(pos) : TerminalRequestPacket.through(via, pos, false));
    }

    /** Opens a station on the network from the terminal at {@code via}; the terminal's own screen when it is that one. */
    static void open(BlockPos via, BlockPos station) {
        ModNetwork.CHANNEL.sendToServer(TerminalRequestPacket.through(via, station, true));
    }

    /** The name to show: the station's own, or its kind and place. */
    static Component name(CompoundTag data, String kindKey, BlockPos pos) {
        String n = data.getString("Name");
        return n.isEmpty() ? Component.translatable(kindKey, pos.getX(), pos.getZ()) : Component.literal(n);
    }

    /** The header's line: the name, and from afar, which way and how long since it read. */
    static Component title(CompoundTag data, String kindKey, BlockPos pos) {
        Component n = name(data, kindKey, pos);
        if (!data.contains("Via") || BlockPos.of(data.getLong("Via")).equals(pos)) return n;
        long stale = data.contains("Stale") ? data.getLong("Stale") : -2;
        Component how = stale == -2 ? Component.translatable("gui.fts_geology.station.live")
                : stale < 0 ? Component.translatable("gui.fts_geology.station.never")
                : Component.translatable("gui.fts_geology.station.ago", ago(stale));
        return Component.translatable("gui.fts_geology.station.remote", n, how);
    }

    /** Game ticks as minutes, hours or days of play. */
    static String ago(long ticks) {
        long minutes = ticks / 1200;
        if (minutes < 1) return "<1 min";
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        return hours < 48 ? hours + " h" : hours / 24 + " d";
    }

    /** The box to rename the station at, and its button; null where the player may not. */
    static EditBox nameBox(Font font, int x, int y, int w, BlockPos pos, CompoundTag data, Consumer<net.minecraft.client.gui.components.AbstractWidget> add) {
        if (!data.getBoolean("MayRename") || via(pos, data) != null) return null;
        EditBox box = new EditBox(font, x, y, w, 12, Component.translatable("gui.fts_geology.station.name"));
        box.setMaxLength(com.jeladastudios.ftsgeology.instrument.StationNetwork.NAME);
        box.setValue(data.getString("Name"));
        box.setHint(Component.translatable("gui.fts_geology.station.name"));
        add.accept(box);
        Component save = Component.translatable("gui.fts_geology.station.save");
        add.accept(Button.builder(save, b -> rename(pos, box)).bounds(x + w + 4, y - 1, font.width(save) + 12, 14).build());
        return box;
    }

    /** A note in the middle of a panel, wrapped to its width. */
    static void centred(net.minecraft.client.gui.GuiGraphics g, Font font, Component text, int cx, int cy, int width, int color) {
        var lines = font.split(text, width);
        int y = cy - lines.size() * 5;
        for (var line : lines) {
            g.drawString(font, line, cx - font.width(line) / 2, y, color, false);
            y += 10;
        }
    }

    static void rename(BlockPos pos, EditBox box) {
        ModNetwork.CHANNEL.sendToServer(new StationRenamePacket(pos, box.getValue()));
        box.setFocused(false);
    }
}
