package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.util.SeedHash;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Natural hydrogen, rare. Where sea water works on the mantle rock of an ophiolite, the iron in it takes the water's
 * oxygen and lets its hydrogen go (serpentinisation); where a tight bed over the rock stops it, the hydrogen gathers.
 * Mali's well at Bourakebougou, bored for water in 1987, gives almost pure hydrogen and has lit a village since 2012.
 *
 * <p>A trap is a lens of the rock some tens of blocks under the ground, on a seeded grid, one square in a dozen or so;
 * it is only a trap where the rock there is serpentinite or peridotite, so they lie in the ophiolites alone, and only
 * under land. Nothing marks it in the ground: break into the rock inside it and the hole gives hydrogen at the pressure
 * of its depth, as a gas cap gives methane (see {@link GasFields}); lit at a straight bore's mouth it burns, pale and
 * all but unseen.</p>
 */
public final class HydrogenTraps {

    private HydrogenTraps() {}

    /** The grid, and the chance a square holds a trap's lens. */
    private static final int CELL = 512;
    private static final double CHANCE = 0.08;

    /** A trap: its middle, its half-widths, how deep its top lies under the ground and how thick it is; its id. */
    public record Trap(long id, int x, int z, double a, double b, int depth, int thick) {
        /** Gas it holds, moles: its lens's volume, the cracks' share of it, at the pressure of its depth. */
        double capacity() {
            double volume = Math.PI * a * b * thick * 2.0 / 3.0;
            return volume * 0.05 * atm() * GasManager.N0;
        }

        double atm() {
            return Math.min(12.0, 1.0 + 0.1 * depth);
        }
    }

    private static Trap trapOf(long seed, int cx, int cz) {
        long h = SeedHash.hash(seed, cx, cz, 0x4A2B0L);
        if (SeedHash.rand01(h) >= CHANCE) return null;
        int x = cx * CELL + 64 + (int) (SeedHash.rand01(h ^ 0x11L) * (CELL - 128));
        int z = cz * CELL + 64 + (int) (SeedHash.rand01(h ^ 0x22L) * (CELL - 128));
        double a = 20 + 24 * SeedHash.rand01(h ^ 0x33L), b = 14 + 18 * SeedHash.rand01(h ^ 0x44L);
        int depth = 30 + (int) (30 * SeedHash.rand01(h ^ 0x55L)), thick = 5 + (int) (6 * SeedHash.rand01(h ^ 0x66L));
        return new Trap(h, x, z, a, b, depth, thick);
    }

    /** The ground over a trap's middle, as the land was made (a shaft bored into it does not move it). */
    private static int ground(ServerLevel level, int x, int z) {
        if (com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.ready()) {
            return (int) Math.floor(com.jeladastudios.ftsgeology.worldgen.terrain.RawGround.heightAt(x, z));
        }
        return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
                level.getChunkSource().randomState());
    }

    /** The rock a trap can be in. */
    public static boolean host(BlockState s) {
        return s.is(ModBlocks.SERPENTINITE.get()) || s.is(ModBlocks.PERIDOTITE.get());
    }

    /** The trap a place lies in, or null. */
    public static Trap at(ServerLevel level, BlockPos p) {
        long seed = level.getSeed();
        int cx = Math.floorDiv(p.getX(), CELL), cz = Math.floorDiv(p.getZ(), CELL);
        Trap t = trapOf(seed, cx, cz);
        if (t == null) return null;
        double dx = (p.getX() - t.x()) / t.a(), dz = (p.getZ() - t.z()) / t.b();
        double r2 = dx * dx + dz * dz;
        if (r2 > 1.0) return null;
        int surface = ground(level, t.x(), t.z());
        if (surface <= level.getSeaLevel()) return null;
        int top = surface - t.depth();
        // A lens: thickest in the middle, thinning to its rim.
        int bottom = top - (int) Math.ceil(t.thick() * Math.sqrt(1.0 - r2));
        return p.getY() <= top && p.getY() >= bottom ? t : null;
    }

    /**
     * The nearest trap within {@code radius} blocks whose rock is a host, for the find command: its middle at the
     * depth of its top, or null.
     */
    public static BlockPos nearest(ServerLevel level, int x, int z, int radius) {
        long seed = level.getSeed();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int r = radius / CELL + 1;
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
            for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
                Trap t = trapOf(seed, cx, cz);
                if (t == null) continue;
                double d = Math.hypot(t.x() - x, t.z() - z);
                if (d > radius || d >= bestD) continue;
                int surface = ground(level, t.x(), t.z()), top = surface - t.depth();
                // Under the sea no bore from the ground reaches it.
                if (surface <= level.getSeaLevel()) continue;
                // The first block down its middle that is the host rock: where a bore down it lets the gas out.
                BlockPos mid = null;
                for (int y = top; y >= top - t.thick() && mid == null; y--) {
                    BlockPos q = new BlockPos(t.x(), y, t.z());
                    if (level.hasChunkAt(q)) {
                        if (host(level.getBlockState(q))) mid = q;
                    } else if (com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)) {
                        // Not made yet: the rock the land's column puts there.
                        long s = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed();
                        var col = com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.column(s,
                                com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), t.x(), t.z());
                        var rock = com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.rockAt(s, col, t.x(), y, t.z(), surface);
                        if (rock == com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Rock.SERPENTINITE
                                || rock == com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Rock.PERIDOTITE) mid = q;
                    }
                }
                if (mid == null) continue;
                best = mid;
                bestD = d;
            }
        }
        return best;
    }
}
