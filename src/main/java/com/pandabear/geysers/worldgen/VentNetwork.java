package com.pandabear.geysers.worldgen;

import com.pandabear.geysers.config.GeyserConfig;
import com.pandabear.geysers.eruption.EruptionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Grows thin, root-like side vents branching upward from a geyser chamber. Each branch is a
 * 1-wide carved channel through natural terrain; wherever a branch breaks into a cave or the
 * open air, that breakthrough point is recorded as a <em>secondary fumarole</em> — a weaker
 * steam vent the core will puff from, so the eruption feels connected to a living underground
 * network rather than a single hole.
 *
 * <p><b>Build-safe & bounded:</b> growth only ever carves
 * {@link EruptionHandler#isNaturalTerrain} blocks and stops the instant it meets anything else
 * (a build, the chamber shell, the core); a global carve budget and a lateral drift limit cap
 * total cost.</p>
 */
public final class VentNetwork {

    private VentNetwork() {}

    /** Cap on recorded fumarole tips — keeps the core's per-second emission cheap. */
    private static final int MAX_TIPS = 16;

    /** A single growing branch head. */
    private static final class Head {
        BlockPos pos;
        int len;
        Head(BlockPos pos, int len) { this.pos = pos; this.len = len; }
    }

    /**
     * Grows the network and returns the fumarole tips (cave/air breakthroughs). May be empty
     * (feature disabled, or no branch reached open space).
     */
    public static List<BlockPos> growBranches(ServerLevel level, BlockPos core, int chamberH,
                                              int magnitude, RandomSource rng) {
        List<BlockPos> tips = new ArrayList<>();
        if (!GeyserConfig.BRANCHING_VENTS_ENABLED.get()) return tips;

        int maxHeads = Mth.clamp(magnitude / 2 + 2, 2, GeyserConfig.BRANCH_MAX_COUNT.get());
        int maxLen = GeyserConfig.BRANCH_MAX_LENGTH.get();
        int budget = GeyserConfig.BRANCH_CARVE_BUDGET.get();
        double splitChance = GeyserConfig.BRANCH_SPLIT_CHANCE.get();
        int topLimit = level.getMaxBuildHeight() - 1;
        int drift = magnitude + 4; // how far a branch may wander from the throat on X/Z

        Deque<Head> heads = new ArrayDeque<>();
        BlockPos start = core.above(chamberH + 2); // just above the rock cap, inside solid rock
        int initialHeads = maxHeads; // start bushy so the root network is actually visible
        for (int i = 0; i < initialHeads; i++) heads.add(new Head(start, 0));

        while (!heads.isEmpty() && budget > 0) {
            Head h = heads.poll();
            BlockPos next = step(h.pos, rng);

            if (next.getY() >= topLimit) continue;                       // hit the world ceiling
            if (Math.abs(next.getX() - core.getX()) > drift
                    || Math.abs(next.getZ() - core.getZ()) > drift) continue; // wandered too far

            BlockState s = level.getBlockState(next);
            if (s.isAir()) {                                             // broke into a cave/opening
                addTip(tips, next);
                continue;                                               // leave the opening intact
            }
            if (!EruptionHandler.isNaturalTerrain(s)) continue;         // build/shell/core: stop safely

            level.setBlock(next, Blocks.AIR.defaultBlockState(), 2);
            budget--;
            h.pos = next;
            h.len++;

            if (h.len >= maxLen) continue;                              // dead-ended in rock (no fumarole)
            heads.add(h);                                               // keep growing this branch
            if (rng.nextDouble() < splitChance && heads.size() < GeyserConfig.BRANCH_MAX_COUNT.get()) {
                heads.add(new Head(next, h.len));                       // split off a sibling
            }
        }
        return tips;
    }

    /** One growth step: strongly biased upward, with some lateral wander (root-like). */
    private static BlockPos step(BlockPos p, RandomSource rng) {
        int dy = rng.nextInt(100) < 72 ? 1 : 0; // ~72% climb, else spread sideways
        int dx = rng.nextInt(3) - 1;
        int dz = rng.nextInt(3) - 1;
        if (dy == 0 && dx == 0 && dz == 0) dy = 1; // never stall in place
        return p.offset(dx, dy, dz);
    }

    private static void addTip(List<BlockPos> tips, BlockPos p) {
        if (tips.size() < MAX_TIPS) tips.add(p.immutable());
    }
}
