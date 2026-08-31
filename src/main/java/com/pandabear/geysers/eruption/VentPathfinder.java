package com.pandabear.geysers.eruption;

import com.pandabear.geysers.config.GeyserConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the live vent mouth by tracing the actual open path from the chamber upward each time
 * it's called — so the system <em>reacts</em> to the player. Fill the throat and an eruption
 * blasts back through it; cap it with something tough and pressure builds until it forces its way
 * out (or bends sideways to a weaker spot). This is what makes the water feel like it's under
 * pressure looking for a way out, rather than teleporting to a fixed hole.
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li><b>passive</b> (heating/pressurising): find the current open exit but break nothing —
 *       if the vent is plugged, steam just seeps from wherever it's blocked.</li>
 *   <li><b>active</b> (erupting): clear soft/natural terrain in the way (small steam blasts),
 *       reroute sideways around hard obstacles, and — once pressure is high enough — force through
 *       a deliberate cap. Bedrock is never broken.</li>
 * </ul>
 */
public final class VentPathfinder {

    private VentPathfinder() {}

    private static final int MAX_TRACE = 400; // tall enough to climb a big mountain to the surface
    /** Cap on blocks breached in a single trace, so a deep vent works its way up gradually. */
    private static final int MAX_BREACH_PER_TRACE = 8;
    /**
     * Cap on how many blocks the mouth may <em>rise</em> in one active trace (per second), whether
     * by boring through rock or climbing through a cave. This is what makes the vent form deep and
     * "drill" its way up gradually — lingering at each cave it breaks into long enough to erupt and
     * wall it — instead of snapping straight to the surface. Only applied while erupting; passive
     * traces report the true current top so steam wisps come out of the right place.
     */
    private static final int MAX_RISE_PER_TRACE = 4;

    /**
     * @param core      the geyser core position
     * @param startY    Y to start climbing from (just above the core)
     * @param ceilingY  never climb above this (the designed surface opening / safety ceiling)
     * @param pressure  current chamber pressure (gates force-breaching)
     * @param active    true while erupting (may breach/reroute); false otherwise (find-only)
     * @return the current vent mouth — the highest reachable open cell of the path
     */
    public static BlockPos trace(ServerLevel level, BlockPos core, int startY, int ceilingY,
                                 double pressure, boolean active) {
        int x = core.getX(), z = core.getZ();
        BlockPos pos = new BlockPos(x, Math.min(startY, ceilingY), z);
        int steps = 0;
        int breaches = 0;
        int rise = 0;

        while (pos.getY() < ceilingY && steps++ < MAX_TRACE) {
            if (active && rise >= MAX_RISE_PER_TRACE) break; // gradual climb: only so far per second
            int yBefore = pos.getY();

            BlockPos above = pos.above();
            if (isOpen(level, above)) {          // clear path straight up
                pos = above;
                if (pos.getY() > yBefore) rise++;
                continue;
            }

            // Prefer an EXISTING opening (a gap the player left) before breaking anything —
            // the water/steam finds the hole in the roof instead of smashing fresh stone.
            BlockPos opening = openingNear(level, pos);
            if (opening != null) {
                pos = opening;
                if (pos.getY() > yBefore) rise++;
                continue;
            }

            if (!active) break;                  // passive: mouth is here, at the blockage
            if (breaches >= MAX_BREACH_PER_TRACE) break; // gradual: only so much per second

            BlockState s = level.getBlockState(above);
            if (s.is(Blocks.BEDROCK)) break;     // never break bedrock — and no side path found

            boolean easy = EruptionHandler.isNaturalTerrain(s);   // soft/natural terrain clears readily
            boolean force = GeyserConfig.VENT_BREAKS_OBSTRUCTIONS.get()
                    && pressure >= GeyserConfig.VENT_FORCE_BREACH_PRESSURE.get();
            if (easy || force) {
                breach(level, above, force && !easy); // hard caps get a small blast for feel
                breaches++;
                pos = above;
                rise++;
                continue;
            }
            break;                                // stuck: pressure keeps building, retry next second
        }
        return pos;
    }

    private static boolean isOpen(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.getFluidState().is(FluidTags.WATER);
    }

    /**
     * Finds an existing opening to move into rather than breaking a fresh path: first an
     * up-and-over diagonal (a gap in the roof one column over), then a same-level sideways
     * passage that itself continues upward. Returns null if the vent is genuinely capped.
     */
    @Nullable
    private static BlockPos openingNear(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {   // gap in the roof, one over
            BlockPos diagUp = pos.above().relative(d);
            if (isOpen(level, diagUp)) return diagUp;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {   // sideways passage that climbs
            BlockPos side = pos.relative(d);
            if (isOpen(level, side) && isOpen(level, side.above())) return side;
        }
        return null;
    }

    /** Clears one blocking cell to air, with a steam puff; a small pop when blasting a tough cap. */
    private static void breach(ServerLevel level, BlockPos p, boolean hard) {
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.05);
        level.sendParticles(ParticleTypes.CLOUD,
                p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, 3, 0.15, 0.15, 0.15, 0.03);
        if (hard) {
            // Non-destructive pop: knockback/feel only. The cell itself is already cleared.
            level.explode(null, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    1.0F, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
        }
    }
}
