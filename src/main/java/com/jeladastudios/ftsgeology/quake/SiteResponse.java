package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Why two places the same distance from a fault are not shaken the same.
 *
 * <ul>
 *   <li><b>The ground.</b> Waves coming up out of rock into soft ground slow down and grow: a valley of loose soil and
 *   clay over rock rings like a bowl of jelly, as Mexico City's old lake bed did in 1985, while a town on the rock beside
 *   it is shaken far less. Soaked ground, with the water table at its top, the more.</li>
 *   <li><b>Where the rupture ran.</b> A rupture that starts at one end and runs along its fault piles its waves up
 *   ahead of it, as a moving siren's pitch rises toward you: the ground the rupture ran toward is shaken harder than
 *   the ground it ran away from.</li>
 * </ul>
 *
 * <p>Both in units of intensity, added to what the distance alone gives.</p>
 */
public final class SiteResponse {

    private SiteResponse() {}

    /** How deep under a column its soft ground is looked for. */
    private static final int REACH = 12;

    /** The ground at a column against rock: up to about one unit of intensity on deep soaked soil, a little less on bare rock. */
    public static double ground(ServerLevel level, int x, int z) {
        BlockPos at = new BlockPos(x, 0, z);
        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, at)) return 0;
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int soft = 0;
        for (int y = g; y > g - REACH; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (loose(s)) soft++;
            else if (!s.isAir()) break;
        }
        double a = soft >= 8 ? 0.8 : soft >= 4 ? 0.5 : soft >= 2 ? 0.2 : -0.2;
        // Soaked: the water table up near the top of the soft ground.
        if (soft >= 2) {
            var r = com.jeladastudios.ftsgeology.hydrology.SoilWater.at(level, x, z);
            if (r != null && r.depth() >= 0 && r.depth() - r.table() <= 3) a += 0.3;
        }
        return a;
    }

    /**
     * How much more the shaking is where the rupture ran toward: nothing at the place it started, up to four tenths of a
     * unit at the far end of the stretch it ran along.
     */
    public static double directivity(BlockPos epicentre, QuakePlanner.TracePoint at, List<QuakePlanner.TracePoint> trace) {
        if (at == null || trace.size() < 2) return 0;
        double run = Math.hypot(at.x() - epicentre.getX(), at.z() - epicentre.getZ());
        double reach = 0;
        for (QuakePlanner.TracePoint t : trace) reach = Math.max(reach, Math.hypot(t.x() - epicentre.getX(), t.z() - epicentre.getZ()));
        return reach < 1 ? 0 : 0.4 * Math.min(1.0, run / reach);
    }

    private static boolean loose(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(net.minecraftforge.common.Tags.Blocks.GRAVEL)
                || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.SNOW_BLOCK)
                || s.is(com.jeladastudios.ftsgeology.hydrology.SoilBlocks.NATURAL_GROUND);
    }
}
