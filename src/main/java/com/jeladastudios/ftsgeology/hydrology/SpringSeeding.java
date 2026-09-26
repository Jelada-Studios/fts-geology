package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import com.jeladastudios.ftsgeology.worldgen.HotSpringSites;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * New hot springs opened by an earthquake. Shaking opens fractures and water finds a new way up, as
 * after the 1959 Hebgen Lake quake at Yellowstone.
 *
 * <p>Rare: heat, water reaching the surface and a free site all have to line up, and a quake opens
 * at most one.</p>
 */
public final class SpringSeeding {

    private SpringSeeding() {}

    /** Candidate columns tried per quake. Small: each one costs a water-table sample. */
    private static final int CANDIDATES = 40;

    /** How close an existing spring has to be for a site to be considered taken. */
    private static final int SPACING = 24;

    /**
     * How strong the geothermal reading has to be, out of 1.
     *
     * <p>Stricter than generation, which has no threshold: a quake gets one roll, so it is pointed at
     * ground that is unambiguously hot.</p>
     */
    private static final double HEAT_FLOOR = 0.55;

    /**
     * Looks for one place along a fresh rupture where water can now get out.
     *
     * @param epicentre     where the quake was centred
     * @param ruptureLength how far the fault tore, in blocks - the corridor to search
     * @param magnitude     bigger quakes disturb more plumbing, so they get a better chance
     */
    public static void afterQuake(ServerLevel level, BlockPos epicentre, int ruptureLength,
                                  double magnitude) {
        if (!GeyserConfig.QUAKES_OPEN_NEW_SPRINGS.get()) return;

        RandomSource rng = level.random;
        // A small quake rearranges very little. The scaling keeps M4 events almost never doing this
        // while a great one usually does something.
        double chance = GeyserConfig.QUAKE_SPRING_CHANCE.get() * Math.max(0.0, magnitude - 3.0);
        if (rng.nextDouble() > chance) {
            com.jeladastudios.ftsgeology.util.Diagnostics.info("quake spring: no roll (M{}, {}% chance)",
                    String.format(java.util.Locale.ROOT, "%.1f", magnitude),
                    Math.round(chance * 100));
            return;
        }

        // Counted, so a quake that opens nothing can say why.
        int unloaded = 0, cold = 0, dry = 0, badGround = 0, occupied = 0, refused = 0;

        int reach = Math.max(64, ruptureLength / 2);
        for (int i = 0; i < CANDIDATES; i++) {
            int x = epicentre.getX() + rng.nextInt(reach * 2 + 1) - reach;
            int z = epicentre.getZ() + rng.nextInt(reach * 2 + 1) - reach;
            if (!level.isLoaded(new BlockPos(x, level.getSeaLevel(), z))) { unloaded++; continue; }

            // 1. Is there heat under here at all?
            GeothermalSuitability.Suitability s = GeothermalSuitability.at(level, x, z);
            if (s.hotSpring() < HEAT_FLOOR) { cold++; continue; }

            // 2. Does the water reach the surface here?
            if (!WaterTable.isSpringLine(level, x, z)) { dry++; continue; }

            // 3. Is the ground fit to hold a pool, and is the site free?
            int ground = TerrainProbe.groundY(level, x, z);
            if (ground == Integer.MIN_VALUE || ground <= level.getSeaLevel() + 2) { badGround++; continue; }
            if (TerrainProbe.hasFluidAbove(level, x, z)) { badGround++; continue; }
            if (springNear(level, x, z)) { occupied++; continue; }

            BlockPos source = HotSpringSites.seedSourceAt(level, x, z, ground);
            if (source == null) { refused++; continue; }
            com.jeladastudios.ftsgeology.util.Diagnostics.info(
                    "Earthquake opened a new spring source at {} (heat {}, {} blocks from the epicentre)",
                    source, String.format(java.util.Locale.ROOT, "%.2f", s.hotSpring()),
                    (int) Math.sqrt(epicentre.distSqr(source)));
            return;                                  // one per quake, deliberately
        }
        com.jeladastudios.ftsgeology.util.Diagnostics.info(
                "quake spring: rolled but found nowhere in {} tries "
                        + "({} unloaded, {} not hot enough, {} no spring line, {} bad ground, "
                        + "{} too close to one, {} refused)",
                CANDIDATES, unloaded, cold, dry, badGround, occupied, refused);
    }

    /**
     * Is there already a spring near this spot?
     *
     * <p>Coarse on purpose: an 8-block grid, 25 lookups, keeps a new spring off an old one.</p>
     */
    private static boolean springNear(ServerLevel level, int x, int z) {
        for (int dx = -SPACING; dx <= SPACING; dx += 8) {
            for (int dz = -SPACING; dz <= SPACING; dz += 8) {
                int g = TerrainProbe.groundY(level, x + dx, z + dz);
                if (g == Integer.MIN_VALUE) continue;
                for (int dy = -2; dy <= 1; dy++) {
                    if (level.getBlockState(new BlockPos(x + dx, g + dy, z + dz))
                            .is(ModBlocks.HOT_SPRING.get())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
