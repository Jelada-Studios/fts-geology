package com.pandabear.geysers.command;

import com.pandabear.geysers.tectonics.HotspotMap;
import com.pandabear.geysers.tectonics.PlateKind;
import com.pandabear.geysers.tectonics.PlateSample;
import com.pandabear.geysers.tectonics.TectonicMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Paints the plate and fault network onto a real filled map.
 *
 * <p>A chat grid can tell you what is directly underfoot, but it cannot show a fault curving away
 * over the horizon or three boundaries meeting at a junction. A map item can: it is a 128x128 image
 * and the mod controls every pixel of it.</p>
 *
 * <p>The map is created with position tracking off and then locked, so vanilla never tries to
 * repaint our picture with ordinary terrain colours.</p>
 */
public final class FaultMap {

    private FaultMap() {}

    private static final int SIZE = 128;

    /** Colour palette, kept close to the chat map so the two read the same way. */
    private static byte colourFor(PlateSample s, boolean hotspot) {
        if (hotspot) return packed(MapColor.COLOR_PURPLE, MapColor.Brightness.HIGH);
        return switch (s.faultType()) {
            case CONVERGENT_COLLISION -> packed(MapColor.GOLD, MapColor.Brightness.HIGH);
            case CONVERGENT_SUBDUCTION -> packed(MapColor.FIRE, MapColor.Brightness.HIGH);
            case DIVERGENT -> packed(MapColor.WATER, MapColor.Brightness.HIGH);
            case TRANSFORM -> packed(MapColor.COLOR_YELLOW, MapColor.Brightness.NORMAL);
            case INTERIOR -> s.plateKind() == PlateKind.OCEANIC
                    ? packed(MapColor.COLOR_BLUE, MapColor.Brightness.LOW)
                    : packed(MapColor.COLOR_GRAY, MapColor.Brightness.LOW);
        };
    }

    private static byte packed(MapColor colour, MapColor.Brightness brightness) {
        return colour.getPackedId(brightness);
    }

    /**
     * Computes the 128x128 pixel grid. Pure maths over the world seed, so this is safe to run on a
     * worker thread; only the finished array needs to come back to the server thread.
     */
    public static byte[] render(ServerLevel level, int centreX, int centreZ, int blocksPerPixel) {
        byte[] colours = new byte[SIZE * SIZE];
        int half = SIZE / 2;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int wx = centreX + (px - half) * blocksPerPixel;
                int wz = centreZ + (py - half) * blocksPerPixel;
                boolean hot = HotspotMap.sample(level, wx, wz).strength() > 0.25;
                PlateSample s = TectonicMap.sampleCached(level, wx, wz);
                colours[px + py * SIZE] = colourFor(s, hot);
            }
        }
        return colours;
    }

    /**
     * Builds the finished map item from a rendered grid. Must run on the server thread, since it
     * creates the saved data the map is stored in.
     */
    public static ItemStack toItem(ServerLevel level, int centreX, int centreZ, byte[] colours,
                                   int blocksPerPixel) {
        // Tracking is off, so vanilla never repaints the picture as the holder moves - which is what
        // lets us paint at our own zoom instead of the scale a real map would use. The declared
        // scale only affects vanilla drawing, which we have taken over entirely.
        ItemStack stack = MapItem.create(level, centreX, centreZ, (byte) 4, false, false);
        MapItemSavedData data = MapItem.getSavedData(stack, level);
        if (data != null) {
            for (int py = 0; py < SIZE; py++) {
                for (int px = 0; px < SIZE; px++) {
                    // updateColor both stores the pixel and marks that column for sync.
                    data.updateColor(px, py, colours[px + py * SIZE]);
                }
            }
        }
        stack.setHoverName(net.minecraft.network.chat.Component.literal(
                "Fault Map  (" + blocksPerPixel + " blocks/pixel)"));
        return stack;
    }
}
