package com.jeladastudios.ftsgeology.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The rivers muddy after heavy rain: the water off the fields carries silt, and a river runs brown for a day or two
 * after a downpour before it clears. Kept on the client from the rain the player has had lately -- a light rain does not
 * do it, a downpour of an hour or so turns the rivers half brown -- and the river water's colour blended towards silt by
 * that much (see {@code RiverMudMixin}). The chunks round the player are drawn again when it changes a step.
 */
public final class ClientRiverMud {

    private ClientRiverMud() {}

    /** The colour of silty water, and how far towards it the muddiest river goes. */
    private static final int SILT = 0x8A6E42;
    private static final float MOST = 0.85f;
    /** Steps the muddiness is drawn in: each a rebuild of the chunks round the player. */
    private static final int STEPS = 8;
    /** How fast heavy rain muddies the rivers, and how fast they clear: by half in a game day and a half. */
    private static final float MUDDY = 0.001f, CLEAR = 0.693f / 36000f;

    private static float turbid;
    private static int step;

    /** One client tick of the rain the player stands in: only the heavier part of it washes silt in. */
    static void tick(LocalPlayer player, float rain) {
        float wash = Math.max(0f, rain - 0.25f) / 0.75f;
        turbid += MUDDY * wash * (1f - turbid) - CLEAR * turbid;
        turbid = Mth.clamp(turbid, 0f, 1f);
        int now = Math.round(turbid * STEPS);
        if (now == step) return;
        step = now;
        // The river water round the player was drawn in the old colour: drawn again.
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null || mc.level == null) return;
        int cx = player.getBlockX() >> 4, cy = player.getBlockY() >> 4, cz = player.getBlockZ() >> 4, r = 8;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int y = cy - 2; y <= cy + 1; y++) mc.levelRenderer.setSectionDirty(x, y, z);
            }
        }
    }

    /** How muddy the rivers are drawn, 0 to 1. */
    public static float muddiness() {
        return step / (float) STEPS;
    }

    /** A river's water colour, blended towards silt as muddy as the rivers are now. */
    public static int tint(int water) {
        float t = muddiness() * MOST;
        if (t <= 0f) return water;
        int r = (int) ((water >> 16 & 255) * (1 - t) + (SILT >> 16 & 255) * t);
        int g = (int) ((water >> 8 & 255) * (1 - t) + (SILT >> 8 & 255) * t);
        int b = (int) ((water & 255) * (1 - t) + (SILT & 255) * t);
        return water & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** A new world, or a new server: clear water. */
    static void reset() {
        turbid = 0f;
        step = 0;
    }

    /** For trying it out: the rivers this muddy now, 0 to 1. */
    public static void set(float muddy) {
        turbid = Mth.clamp(muddy, 0f, 1f);
    }
}
