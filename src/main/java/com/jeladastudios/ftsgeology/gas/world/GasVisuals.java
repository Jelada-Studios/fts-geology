package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.Combustion;
import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasMix;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * Makes invisible gas visible to one player (optical gas imaging camera / debug view) by sending
 * coloured dust particles only to them.
 */
public final class GasVisuals {
    private static final int MAX_PARTICLES = 700;

    private GasVisuals() {
    }

    public static void show(ServerPlayer player, GasManager mgr, int radius) {
        RandomSource r = player.getRandom();
        BlockPos center = BlockPos.containing(player.getEyePosition());
        int[] sent = {0};
        mgr.forEachCellNear(center, radius, (p, g) -> {
            if (sent[0] >= MAX_PARTICLES) return;
            Gas gas = g.dominantAnomaly();
            if (gas == null) return;
            double intensity = intensity(g, gas);
            if (intensity < 0.02) return;
            if (r.nextDouble() > 0.25 + intensity) return;
            int c = gas.color;
            float size = (float) (0.5 + Math.min(1.0, intensity) * 1.6);
            DustParticleOptions dust = new DustParticleOptions(new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f), size);
            player.serverLevel().sendParticles(player, dust, true,
                    p.getX() + r.nextDouble(), p.getY() + r.nextDouble(), p.getZ() + r.nextDouble(), 1, 0, 0, 0, 0);
            sent[0]++;
        });
    }

    /** 0..1+ how strongly a cell should show, relative to the danger level of that gas. */
    static double intensity(GasMix g, Gas gas) {
        double x = g.fraction(gas);
        return switch (gas) {
            case METHANE, HYDROGEN -> Combustion.percentLel(g) / 100.0;
            case CARBON_MONOXIDE -> x * 1e6 / 400.0;
            case HYDROGEN_SULFIDE -> x * 1e6 / 100.0;
            case SULFUR_DIOXIDE -> x * 1e6 / 50.0;
            case CARBON_DIOXIDE -> x / 0.05;
            case OXYGEN -> (Gas.AIR_O2 - x) / 0.08;
            case WATER_VAPOR -> x / 0.1;
            default -> 0;
        };
    }
}
