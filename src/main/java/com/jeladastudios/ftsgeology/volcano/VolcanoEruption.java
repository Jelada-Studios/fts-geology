package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * Physical side effects of a volcano: the black-smoke warning, the summit lava fountain, the
 * hurled volcanic bombs, and the growing crater. Stateless helpers — {@code VolcanoCoreBlockEntity}
 * owns the cycle and calls in here.
 */
public final class VolcanoEruption {

    private VolcanoEruption() {}

    /** Pre-eruption warning: a thick black smoke plume + a low rumble from the crater. */
    public static void rumble(ServerLevel level, BlockPos summit, int magnitude, long time) {
        double x = summit.getX() + 0.5, z = summit.getZ() + 0.5;
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, summit.getY() + 1.5, z,
                8, 0.6, 0.4, 0.6, 0.02);
        level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, summit.getY() + 3.0, z,
                3, 0.4, 0.6, 0.4, 0.01);
        if (time % 40L == 0L) {
            level.playSound(null, summit, SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), SoundSource.BLOCKS,
                    1.5f, 0.4f);
        }
    }

    /** Per-tick eruption spectacle: fire fountain particles, the odd bomb, ambient roar. */
    public static void tickEruption(ServerLevel level, BlockPos summit, int magnitude, int eruptionTicks) {
        double x = summit.getX() + 0.5, z = summit.getZ() + 0.5;
        // Fire fountain: lava + flame + smoke shooting up.
        level.sendParticles(ParticleTypes.LAVA, x, summit.getY() + 1.0, z, 4, 0.4, 0.2, 0.4, 0.0);
        level.sendParticles(ParticleTypes.FLAME, x, summit.getY() + 1.5, z, 6, 0.4, 0.6, 0.4, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, summit.getY() + 4.0, z, 5, 0.8, 1.2, 0.8, 0.03);

        int bombs = GeyserConfig.VOLCANO_BOMBS_PER_ERUPTION.get();
        int eruptTicks = Math.max(1, GeyserConfig.VOLCANO_ERUPT_TICKS.get());
        // Space the bombs roughly evenly across the eruption.
        int interval = Math.max(2, eruptTicks / Math.max(1, bombs));
        if (eruptionTicks % interval == 0) {
            throwBomb(level, summit, magnitude);
        }
        if (eruptionTicks % 30 == 0) {
            level.playSound(null, summit, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 1.2f, 0.5f);
        }
    }

    /** Once per second while erupting: well lava up the crater so it spills down the mountain. */
    public static boolean spillLava(ServerLevel level, BlockPos summit) {
        // Only reports true when it actually put lava out, so the core can hold it to a budget:
        // an eruption should send a tongue down the flank, not keep pouring until the mountain is
        // drowned in it.
        if (!level.getBlockState(summit).isAir()) return false;
        level.setBlock(summit, Blocks.LAVA.defaultBlockState(), 3);
        level.scheduleTick(summit, Fluids.LAVA, 5);
        return true;
    }

    /**
     * Hurls a volcanic bomb: a single lump of basalt arcs out toward a random landing spot around
     * the volcano and scorches the ground where it hits. Bigger volcanoes throw farther and hit
     * harder. Deliberately small - one block flying, one block of scorch - so a long session never
     * buries the mountainside in rubble.
     */
    public static void throwBomb(ServerLevel level, BlockPos summit, int magnitude) {
        int reach = 6 + magnitude;                          // how far bombs land
        int tx = summit.getX() + level.random.nextInt(reach * 2 + 1) - reach;
        int tz = summit.getZ() + level.random.nextInt(reach * 2 + 1) - reach;
        int tg = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, tx, tz);
        if (tg == Integer.MIN_VALUE) return;
        int ty = tg + 1;   // the air cell above real ground, so bombs never land on a treetop
        BlockPos target = new BlockPos(tx, ty, tz);

        // Visual: ONE chunk of rock flung toward the target with an upward arc.
        Vec3 dir = new Vec3(tx - summit.getX(), 0, tz - summit.getZ()).normalize();
        FallingBlockEntity bomb = FallingBlockEntity.fall(level, summit.above(2),
                Blocks.BASALT.defaultBlockState());
        double horiz = 0.35 + level.random.nextDouble() * (0.3 + magnitude / 30.0);
        bomb.setDeltaMovement(new Vec3(
                dir.x * horiz + (level.random.nextDouble() - 0.5) * 0.2,
                0.9 + level.random.nextDouble() * 0.5,
                dir.z * horiz + (level.random.nextDouble() - 0.5) * 0.2));
        bomb.setHurtsEntities(3.0f, 12);
        bomb.hurtMarked = true;

        // Impact: a single scorched block at the landing spot.
        impact(level, target, summit.getY());
    }

    /**
     * Scorches exactly ONE block where a bomb lands: the topmost solid cell of that column is
     * replaced with basalt. Because it uses the landing column own surface height and touches no
     * neighbours, it can never leave a block hanging in mid-air - the old 3x3 version reused the
     * centre column Y for all nine cells, which is what littered the slopes with floating rock.
     * Replacing rather than stacking also means zero net growth.
     */
    private static void impact(ServerLevel level, BlockPos target, int summitY) {
        BlockPos ground = target.below(); // topmost solid block of this column
        if (ground.getY() <= summitY) {   // never build above the original summit
            BlockState s = level.getBlockState(ground);
            if (!s.isAir() && !s.is(Blocks.BEDROCK) && s.getFluidState().isEmpty()) {
                level.setBlock(ground, Blocks.BASALT.defaultBlockState(), 3);
            }
        }
        level.sendParticles(ParticleTypes.LAVA, target.getX() + 0.5, target.getY() + 0.3, target.getZ() + 0.5,
                8, 0.4, 0.2, 0.4, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, target.getX() + 0.5, target.getY() + 0.8, target.getZ() + 0.5,
                6, 0.3, 0.2, 0.3, 0.02);
    }

    /** Idle black smoke from a lava pool cell or a surface vent. */
    public static void smokeAt(ServerLevel level, BlockPos p) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                p.getX() + 0.5, p.getY() + 1.1, p.getZ() + 0.5, 2, 0.25, 0.15, 0.25, 0.01);
    }

    /**
     * Seeps lava out of a surface vent during an eruption (spills, then cools to basalt later).
     *
     * <p>The outlet is given its own spatter and a low hiss. It used to place the lava and send
     * nothing at all, so a flank vent quietly filled with lava while every particle in the mod came
     * out of the summit - the "no steam or soot off the lava veins" report.</p>
     */
    public static void seepVent(ServerLevel level, BlockPos vent) {
        boolean opened = level.getBlockState(vent).isAir();
        if (opened) {
            level.setBlock(vent, Blocks.LAVA.defaultBlockState(), 3);
            level.scheduleTick(vent, Fluids.LAVA, 5);
        }
        double x = vent.getX() + 0.5, y = vent.getY() + 1.0, z = vent.getZ() + 0.5;
        level.sendParticles(ParticleTypes.LAVA, x, y, z, opened ? 4 : 1, 0.3, 0.1, 0.3, 0.0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.4, z, 3, 0.3, 0.3, 0.3, 0.02);
        if (opened) {
            level.playSound(null, vent, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.8f, 0.6f);
        }
    }

    /**
     * A puff of steam where a lava cell touches water.
     *
     * <p>This is the single most visible thing about a real flow reaching a shoreline or a stream,
     * and the mod was producing none of it: {@link #coolScatteredLava} already walks every cell the
     * eruption spilled, so the check rides along on a sweep that was happening anyway.</p>
     */
    private static void steamIfWet(ServerLevel level, BlockPos p) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!level.getBlockState(p.relative(d)).getFluidState()
                    .is(net.minecraft.tags.FluidTags.WATER)) continue;
            level.sendParticles(ParticleTypes.CLOUD,
                    p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 6, 0.4, 0.3, 0.4, 0.03);
            level.playSound(null, p, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.2f);
            return;
        }
    }

    /**
     * Clears a vent fresh lava once the eruption is over, leaving the outlet OPEN. It used to be
     * capped with basalt, which both stacked a block on top of every outlet each eruption and
     * permanently plugged it - {@link #seepVent} only fills air, so a capped outlet never seeped
     * again. Runoff that spread around the outlet still petrifies via {@link #coolScatteredLava}.
     */
    public static void dryVent(ServerLevel level, BlockPos vent) {
        if (level.getBlockState(vent).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            level.setBlock(vent, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /**
     * After an eruption, hardens the lava the volcano spilled <em>outside</em> the crater into fresh
     * basalt/tuff - the runoff on the slopes turns to rock - while the crater lava lake stays molten.
     *
     * <p><b>The volcano never grows.</b> Two rules stop a long session from stacking layer on layer
     * until the mountain swallows its own vent:</p>
     * <ul>
     *   <li>nothing may solidify <em>above the original summit</em> ({@code summit.getY()}, which is
     *       fixed because the core block never moves) - lava up there is simply drained away;</li>
     *   <li>lava resting on rock the volcano itself already laid down (basalt/tuff/magma) is drained
     *       too, so flows only petrify where they touch the ORIGINAL terrain. At most one new layer
     *       can ever form.</li>
     * </ul>
     * Lava hanging over air is still left alone, so we never freeze a mid-air stream into a spike.
     * Bounded scan for performance.
     */
    public static void coolScatteredLava(ServerLevel level, BlockPos summit, int craterR, int reach,
                                         long[] keepVents) {
        int keep2 = (craterR + 1) * (craterR + 1);
        BlockPos lo = summit.offset(-reach, -reach, -reach);
        BlockPos hi = summit.offset(reach, 6, reach);
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            int dx = p.getX() - summit.getX();
            int dz = p.getZ() - summit.getZ();
            if (dx * dx + dz * dz <= keep2) continue; // leave the crater lake molten
            // The volcano's own outlets are meant to stay molten between eruptions.
            //
            // They were not excluded before, and because each one is deliberately seated on a basalt
            // floor the "resting on rock we laid ourselves" rule read them as spilled runoff and
            // DRAINED them. So every flank vent - and, on a fissure, every pond but the first - went
            // dark after the very first eruption while the core kept firing its particles out of
            // them: the "the lava pool has vanished but the eruption still comes from there" report.
            if (isKept(keepVents, p)) continue;
            FluidState fs = level.getBlockState(p).getFluidState();
            // Any lava - full source OR a thin flowing "half" block - but only where it RESTS ON
            // SOLID GROUND, so a mid-air stream is never frozen into a floating spike.
            // Fire the flow started is put out as it cools, unless eruptionsStartFires says the
            // burn is allowed to outlive the lava - which is what actually happens when a flow
            // reaches a forest, and what fire-spread mods are there to carry on with.
            if (level.getBlockState(p).is(Blocks.FIRE)) {
                if (!GeyserConfig.ERUPTIONS_START_FIRES.get()) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
                continue;
            }
            if (!fs.is(net.minecraft.tags.FluidTags.LAVA)) continue;
            // Quench steam wherever the flow has reached water. Sampled rather than fired on every
            // cell of every sweep, so a long shoreline hisses instead of turning white.
            if (level.random.nextInt(3) == 0) steamIfWet(level, p);
            BlockState below = level.getBlockState(p.below());
            if (below.isAir() || !below.getFluidState().isEmpty()) continue;   // must rest on solid ground
            boolean aboveSummit = p.getY() > summit.getY();
            boolean onOwnRock = below.is(Blocks.BASALT) || below.is(Blocks.TUFF)
                    || below.is(Blocks.MAGMA_BLOCK);
            if (aboveSummit || onOwnRock) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2); // drain it: never stack upward
            } else {
                level.setBlock(p, (level.random.nextInt(3) == 0
                        ? Blocks.TUFF : Blocks.BASALT).defaultBlockState(), 2);
            }
        }
    }

    /** Is this one of the volcano's own recorded outlets? */
    private static boolean isKept(long[] keepVents, BlockPos p) {
        if (keepVents == null) return false;
        long key = p.asLong();
        for (long v : keepVents) {
            if (v == key) return true;
        }
        return false;
    }

    /**
     * At eruption end, re-lines the summit crater: the centre stays molten and the rim is cooled
     * volcanic rock. Everything happens at the summit own Y, so the crater is maintained rather
     * than raised. Takes the REAL carved crater radius (wider than the raw config value) so the rim
     * ring lands on the actual rim instead of inside the lava lake, where it used to slowly plug
     * the crater.
     */
    public static void formCrater(ServerLevel level, BlockPos summit, int craterR) {
        int r = Math.max(1, craterR);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                BlockPos p = summit.offset(dx, 0, dz);
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.BEDROCK)) continue;
                FluidState fs = s.getFluidState();
                if (d2 < (r - 1) * (r - 1)) {
                    // The whole floor inside the rim is refilled, not just the middle cell.
                    //
                    // This ran at the END of every eruption and only ever filled d2 <= 1, leaving
                    // the ring between there and the rim as whatever the eruption happened to
                    // leave. So the crater kept its full width while the lava in it was a blob in
                    // the centre - the "the pit gets wider but no new lava appears in the widened
                    // part, so it just looks empty" report. Only air is filled, so cooled basalt
                    // the eruption laid down is left where it is.
                    if (fs.isEmpty()) level.setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
                } else if (d2 >= (r - 1) * (r - 1)) {
                    // rim: cooled volcanic rock, occasionally still smouldering
                    if (!s.isAir() && fs.isEmpty()) {
                        level.setBlock(p, (level.random.nextInt(3) == 0
                                ? Blocks.MAGMA_BLOCK : Blocks.BASALT).defaultBlockState(), 3);
                    }
                }
            }
        }
    }
}
