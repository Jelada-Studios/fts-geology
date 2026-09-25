package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.hydrology.DrainageLattice;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.util.ColumnCache;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The ground the generator would build with no river cut into it, in blocks, as a plain function of two coordinates.
 *
 * <p>It is taken once a server, the first time the noise router is wired -- the world's random state, before any
 * chunk exists -- and with every cache taken out of it. It used to be taken again from every chunk the generator
 * built, which bound it to that chunk's caches: one of them keeps the last position it answered for and the answer
 * in two separate fields, which two threads can pull apart, and a finished chunk stayed alive through it.</p>
 *
 * <p>Near sea level the offset alone is too smooth to say which ground the sea covers: the world's surface stands up
 * to several blocks over it. There the network asks {@link #wet}, which reads the world's whole terrain function --
 * as flat as a river's banks hold it and with no channel cut -- across the sea's surface, and where that is open,
 * builds the column as the generator will, aquifer and all, and looks at what stands on top. A river ending there
 * ends beside it. Read without the river's flattening, a plain beside a river came out a few blocks lower than it
 * stands and under water; read off the function alone, a third of the rivers still ended on dry land, the aquifer
 * leaving open hollows under sea level dry and the generator's blend between its samples standing a block or two off
 * the function's own surface.</p>
 */
public final class RawGround {

    private RawGround() {}

    private static volatile DensityFunction offset;

    /** What {@link #wet} found, by column: a node's tile is built again whenever it has fallen out of the lattice's. */
    private static final ColumnCache<Boolean> WET = new ColumnCache<>(16);
    /** The world's terrain function, holders opened and caches taken off; bound before any chunk. */
    private static volatile DensityFunction terrain;
    /** The generator, its random state and its height, to build a column as the generator will. */
    private static volatile NoiseBasedChunkGenerator columns;
    private static volatile RandomState columnState;
    private static volatile LevelHeightAccessor columnHeight;
    /** The sea's surface: the top of its water, and the blocks over it that have to be open for it to be the sea's. */
    private static final int SEA_TOP = 62, OPEN_TO = 65;
    /** The stretch of a column built to see whether water stands on it: whole noise cells, round the sea's surface. */
    private static final int COLUMN_FROM = 48, COLUMN_HEIGHT = 32;

    /** Holders opened and cache markers taken off: what is left reads the same on any thread and keeps nothing. */
    private static final DensityFunction.Visitor BARE = f -> f instanceof DensityFunctions.HolderHolder h
            ? h.function().value()
            : f instanceof DensityFunctions.MarkerOrMarked m ? m.wrapped() : f;

    /** The ground as the river network reads it. */
    private static final DrainageLattice.Ground GROUND = new DrainageLattice.Ground() {
        @Override
        public double heightAt(int x, int z) {
            return RawGround.heightAt(x, z);
        }

        @Override
        public boolean wet(int x, int z) {
            return RawGround.wet(x, z);
        }

        @Override
        public boolean soluble(int x, int z, double ground) {
            return com.jeladastudios.ftsgeology.hydrology.Karst.soluble(x, z, ground);
        }
    };

    /** Handed the raw offset as the router is wired; only the first of a server counts. */
    static void offer(DensityFunction wired) {
        if (offset != null) return;
        synchronized (RawGround.class) {
            if (offset != null) return;
            offset = wired.mapAll(BARE);
        }
        // Switched off, the network never opens: no channel is cut into the offset, no cave is pushed away from one
        // and no water is laid. Half a river is worse than none.
        if (GeyserConfig.RIVERS.get()) {
            RiverNetwork.open(GROUND, TerrainContext.seed(), TerrainContext.params().horizontal());
        }
    }

    /**
     * Handed the world's random state as the generator sets up its structures, which is before a single chunk or biome
     * is asked for, so every question the network puts to {@link #wet} in a world is answered the same way.
     */
    static void bindTerrain(NoiseBasedChunkGenerator generator, RandomState state) {
        // Only the stretch of the column round the sea's surface: the generator builds no more of it than it is asked for,
        // and the whole of a tall world's was most of the cost.
        columnHeight = LevelHeightAccessor.create(COLUMN_FROM, COLUMN_HEIGHT);
        columnState = state;
        columns = generator;
        terrain = state.router().finalDensity().mapAll(BARE);
        WET.clear();
    }

    public static boolean ready() {
        return offset != null;
    }

    /** The ground here, in blocks: where the offset puts the surface in both world types. */
    public static double heightAt(int x, int z) {
        DensityFunction f = offset;
        return f == null ? Double.NaN : 128.0 + 128.0 * f.compute(new DensityFunction.SinglePointContext(x, 0, z));
    }

    /**
     * Whether the column here holds water over its ground once built -- the sea's, or the aquifer's at sea level over
     * a hollow -- as the ground beside a river, uncut. Without a generator bound, as the network's own checks run, it is
     * wet.
     */
    public static boolean wet(int x, int z) {
        DensityFunction f = terrain;
        NoiseBasedChunkGenerator g = columns;
        if (f == null || g == null) return true;
        long key = ColumnCache.key(x, z);
        Boolean known = WET.get(key);
        if (known != null) return known;
        boolean wet = wetColumn(f, g, x, z);
        WET.put(key, wet);
        return wet;
    }

    private static boolean wetColumn(DensityFunction f, NoiseBasedChunkGenerator g, int x, int z) {
        RiverDensity.bare(true);
        try {
            // Ground anywhere from the sea's top up past sea level stands out of the water: a few reads of the function
            // settle most of the dry ground. An opening there with ground over it would be a cave, not the sea.
            for (int y = SEA_TOP; y <= OPEN_TO; y++) {
                if (f.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0) return false;
            }
            // Open: whether the generator floods it, the column says.
            LevelHeightAccessor h = columnHeight;
            NoiseColumn column = g.getBaseColumn(x, z, h, columnState);
            for (int y = h.getMaxBuildHeight() - 1; y >= h.getMinBuildHeight(); y--) {
                BlockState b = column.getBlock(y);
                if (b.isAir()) continue;
                return !b.getFluidState().isEmpty();
            }
            return false;
        } finally {
            RiverDensity.bare(false);
        }
    }

    public static void clear() {
        offset = null;
        terrain = null;
        WET.clear();
        columns = null;
        columnState = null;
        columnHeight = null;
    }
}
