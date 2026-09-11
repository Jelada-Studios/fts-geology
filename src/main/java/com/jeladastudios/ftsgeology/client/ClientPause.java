package com.jeladastudios.ftsgeology.client;

import net.minecraft.client.Minecraft;

/** Whether the single player game is paused. Client only; callers check the side first. */
public final class ClientPause {

    private ClientPause() {}

    public static boolean isPaused() {
        return Minecraft.getInstance().isPaused();
    }
}
