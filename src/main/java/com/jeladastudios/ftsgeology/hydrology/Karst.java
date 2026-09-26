package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.util.SeedHash;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;

/**
 * Karst: ground that water dissolves.
 *
 * <p>Where marble or calcite lies close under the surface of a platform or a foreland -- the flat-lying limestone of a
 * plateau, not the folded rock of a mountain belt -- rain soaked through the soil carries carbon dioxide down into the
 * rock and eats it out along its joints. The surface is pocked with sinkholes, some fallen through into the hollows
 * under them, and now and then a river crossing onto the rock sinks at a swallow hole, runs on in a cave under its
 * valley -- left dry over it -- and comes out again further down, into the river it feeds. The Reka sinks at Škocjan
 * and comes up in the Timavo.</p>
 *
 * <p>The cave slopes down with the river and never lower than where it comes out, so the water runs downhill all the
 * way and leaves the cave at the level of the river below: over its last stretch the cave's roof has thinned away and it
 * runs in a slot open to the sky.</p>
 *
 * <p>Not all such ground is karst: a region either has it or has not, as the karst of the Dinarides, the Causses or
 * the Yucatán is a region. The rest of it drains at the surface as anywhere else.</p>
 */
public final class Karst {

    private Karst() {}

    /** The largest river that sinks, by the cells it drains: a big river's water is more than its bed can take. */
    static final int SINK_AREA_MAX = 512;
    /**
     * How far a river has to fall across a cell to sink there, in blocks at the normal world's layout: the cave slopes
     * down with it no lower than where it comes out, and over a smaller fall it would have no roof.
     */
    static final double CAVE_DROP = 5.0;
    /** The fewest lengths of river a cave runs for: shorter, it would be a hole through a bank. */
    static final int TUNNEL_MIN = 2;

    /** The share of the ground with soluble beds under it that is karst country. */
    private static final double COUNTRY = 0.55;
    /** How far across a karst country runs, in blocks at the normal world's layout. */
    private static final double COUNTRY_CELL = 1200.0;
    /** How far under the surface a bed of marble or calcite still takes the ground's water. */
    private static final int REACH = 30;
    /** Karst stands clear of the sea: at the coast the ground's water meets the sea's. */
    private static final double ABOVE_SEA = 66.0;

    /** Whether the ground here takes its water underground, for a river to sink or the ground to open in sinkholes. */
    public static boolean soluble(int x, int z, double ground) {
        if (!GeyserConfig.KARST.get() || ground < ABOVE_SEA) return false;
        long seed = TerrainContext.seed();
        if (country(seed, x, z, RiverNetwork.horizontal()) > COUNTRY) return false;
        Lithology.Column c = Lithology.column(seed, TerrainContext.params(), x, z);
        // A plateau's beds: in a mountain belt the rock is folded, faulted and mostly not limestone, and the rivers
        // there are mountain rivers.
        if (c.setting() != Lithology.Setting.PLATFORM && c.setting() != Lithology.Setting.FORELAND) return false;
        int top = (int) Math.floor(ground);
        for (int d = 1; d <= REACH; d += 2) {
            Lithology.Rock r = Lithology.rockAt(seed, c, x, top - d, z, top);
            if (r == Lithology.Rock.MARBLE || r == Lithology.Rock.CALCITE) return true;
        }
        return false;
    }

    /** Smooth value noise over the karst countries, in [0, 1]: under {@link #COUNTRY} is karst. */
    static double country(long seed, int x, int z, double h) {
        double cell = COUNTRY_CELL * h;
        double fx = x / cell, fz = z / cell;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = corner(seed, x0, z0), b = corner(seed, x0 + 1, z0), c = corner(seed, x0, z0 + 1), d = corner(seed, x0 + 1, z0 + 1);
        double top = a + (b - a) * tx, bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private static double corner(long seed, int i, int j) {
        return SeedHash.rand01(SeedHash.hash(seed, i, j, 0x4A57L));
    }
}
