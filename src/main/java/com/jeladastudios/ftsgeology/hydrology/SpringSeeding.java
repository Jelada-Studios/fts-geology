package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.GeothermalSuitability;
import com.jeladastudios.ftsgeology.worldgen.RetrogenHandler;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * New hot springs opened by an earthquake.
 *
 * <h2>Why a quake should be able to make one</h2>
 * Shaking the crust changes its permeability. Fractures open, others close, and water that had no
 * way up finds one - which is why a large earthquake near a geothermal field is followed by springs
 * appearing where there were none. The 1959 Hebgen Lake earthquake did this at Yellowstone within
 * days, and it is the same event that rearranged the outlets of the springs that were already
 * there. Both halves of that behaviour now exist in the mod:
 * {@link com.jeladastudios.ftsgeology.blockentity.SpringSourceBlockEntity} moves an existing
 * outlet, and this opens a new one.
 *
 * <h2>Why it stays rare</h2>
 * Three conditions have to line up, and they are the same three a real spring needs: heat under the
 * ground, water that reaches the surface, and somewhere not already occupied. Most of a rupture
 * corridor fails at least one. The cap of one per quake is a second, blunter guarantee - a world
 * that gets shaken often should not silently fill up with hot springs.
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
     * <p>Deliberately stricter than ordinary generation, which has no threshold at all - it rolls
     * against {@code hotSpringSpawnChance * suitability}, so faint geothermal ground still produces
     * the occasional spring given enough chunks. A quake gets one roll rather than thousands, so it
     * is pointed at ground that is unambiguously hot instead.</p>
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
        if (rng.nextDouble() > chance) return;

        int reach = Math.max(64, ruptureLength / 2);
        for (int i = 0; i < CANDIDATES; i++) {
            int x = epicentre.getX() + rng.nextInt(reach * 2 + 1) - reach;
            int z = epicentre.getZ() + rng.nextInt(reach * 2 + 1) - reach;
            if (!level.isLoaded(new BlockPos(x, level.getSeaLevel(), z))) continue;

            // 1. Is there heat under here at all?
            GeothermalSuitability.Suitability s = GeothermalSuitability.at(level, x, z);
            if (s.hotSpring() < HEAT_FLOOR) continue;

            // 2. Does the water actually reach the surface here? This is the condition that could
            //    not be asked before the water table existed, and it is the one that decides
            //    whether a spring is geology or decoration.
            if (!WaterTable.isSpringLine(level, x, z)) continue;

            // 3. Is the ground fit to hold a pool, and is the site free?
            int ground = TerrainProbe.groundY(level, x, z);
            if (ground == Integer.MIN_VALUE || ground <= level.getSeaLevel() + 2) continue;
            if (TerrainProbe.hasFluidAbove(level, x, z)) continue;
            if (springNear(level, x, z)) continue;

            BlockPos source = RetrogenHandler.seedSourceAt(level, x, z, ground);
            if (source == null) continue;
            GeysersMod.LOGGER.info(
                    "Earthquake opened a new spring source at {} (heat {}, {} blocks from the epicentre)",
                    source, String.format(java.util.Locale.ROOT, "%.2f", s.hotSpring()),
                    (int) Math.sqrt(epicentre.distSqr(source)));
            return;                                  // one per quake, deliberately
        }
    }

    /**
     * Is there already a spring near this spot?
     *
     * <p>Coarse on purpose. A dense scan would mean thousands of ground lookups for a check that
     * only has to stop a new spring landing on top of an old one; sampling the site on an 8-block
     * grid catches that and costs 25 lookups.</p>
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
