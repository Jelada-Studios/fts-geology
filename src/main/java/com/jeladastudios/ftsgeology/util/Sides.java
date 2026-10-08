package com.jeladastudios.ftsgeology.util;

import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Runs client-only work where Forge's DistExecutor used to: only on a physical client. The work is a lambda, so the
 * client classes it names are loaded only when it runs, never on a dedicated server.
 */
public final class Sides {

    private Sides() {}

    public static void client(Runnable work) {
        if (FMLEnvironment.dist.isClient()) work.run();
    }
}
