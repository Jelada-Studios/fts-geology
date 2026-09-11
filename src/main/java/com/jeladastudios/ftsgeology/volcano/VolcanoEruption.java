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

/** Physical side effects of a volcano, as stateless helpers; {@code VolcanoCoreBlockEntity} owns the cycle. */
public final class VolcanoEruption {

    private VolcanoEruption() {}

    /**
     * Pre-eruption warning: a low rumble from the crater. The black smoke that goes with it is drawn
     * by the client from the state the core sends - see ClientEruptions.
     */
    public static void rumble(ServerLevel level, BlockPos summit, int magnitude, long time) {
        if (time % 40L == 0L) {
            level.playSound(null, summit, SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), SoundSource.BLOCKS,
                    1.5f, 0.4f);
        }
    }

    /** Per-tick eruption spectacle: fire fountain particles, the odd bomb, ambient roar. */
    public static void tickEruption(ServerLevel level, BlockPos summit, int magnitude, int eruptionTicks) {
        double x = summit.getX() + 0.5, z = summit.getZ() + 0.5;
        // Fire fountain: lava + flame + smoke shooting up.
        level.sendParticles(ParticleTypes.LAVA, x, summit.getY() + 1.0, z, 6, 0.5, 0.3, 0.5, 0.0);
        level.sendParticles(ParticleTypes.FLAME, x, summit.getY() + 1.5, z, 8, 0.5, 0.7, 0.5, 0.05);

        // The smoke column is drawn by the client from the state the core sends; see ClientEruptions.

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

        ashfall(level, summit, magnitude);
        trailBombs(level);
        // The mountain shakes while it is going off, and much less far out than a quake does: an
        // eruption is felt on its own slopes, not across a county.
        if (eruptionTicks % 5 == 0) {
            com.jeladastudios.ftsgeology.network.ModNetwork.shakeNear(level,
                    summit.getX(), summit.getZ(), 40 + magnitude * 6.0,
                    0.35f + magnitude / 22.0f, 20);
        }
    }

    // === Ash ================================================================

    /**
     * Ash settling out of the column onto the ground downwind, as layers of
     * {@link com.jeladastudios.ftsgeology.block.VolcanicAshBlock}. A lobe on the wind's side rather
     * than a ring, so the deposit shows which way the wind blew.
     */
    private static void ashfall(ServerLevel level, BlockPos summit, int magnitude) {
        if (!GeyserConfig.VOLCANIC_ASHFALL.get()) return;

        double[] wind = wind(summit);
        int reach = Math.min(40 + magnitude * 8, 160);

        // Enough columns a call that a minute-long eruption visibly greys the ground downwind.
        for (int n = 0; n < 20; n++) {
            // Distance is square-root biased so the samples spread evenly over the disc rather than
            // piling up at the middle; the thinning below is what puts the weight near the vent.
            double d = reach * Math.sqrt(level.random.nextDouble());

            // Any bearing, weighted by how well it lines up with the wind, so the lobe has no hard edge.
            double a = level.random.nextDouble() * Math.PI * 2;
            double dirX = Math.cos(a), dirZ = Math.sin(a);
            double align = dirX * wind[0] + dirZ * wind[1];             // -1 upwind, +1 downwind
            double lobe = 0.12 + 0.88 * Math.pow((align + 1.0) * 0.5, 2.2);

            int x = summit.getX() + (int) Math.round(dirX * d);
            int z = summit.getZ() + (int) Math.round(dirZ * d);

            // Thins out with distance as well: solid near the vent, patchy at the edge.
            if (level.random.nextDouble() > (1.0 - (d / reach) * 0.85) * lobe) continue;
            if (!level.hasChunkAt(new BlockPos(x, level.getSeaLevel(), z))) continue;
            if (com.jeladastudios.ftsgeology.quake.QuakeQuiet.isQuiet(level, x, z)) continue;

            // Not into the crater the column comes out of.
            double sx = x - summit.getX(), sz = z - summit.getZ();
            if (sx * sx + sz * sz < 12 * 12) continue;

            int g = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z);
            if (g == Integer.MIN_VALUE) continue;
            if (com.jeladastudios.ftsgeology.worldgen.TerrainProbe.hasFluidAbove(level, x, z)) continue;

            BlockPos ground = new BlockPos(x, g, z);
            BlockState under = level.getBlockState(ground);
            if (under.is(Blocks.BEDROCK) || !under.getFluidState().isEmpty()) continue;
            // Not onto a live flow.
            if (under.is(Blocks.LAVA) || under.is(Blocks.MAGMA_BLOCK)) continue;

            // Ash already lying here reads as the ground, so the fall deepens that layer rather than the cell
            // above it, where a thin layer cannot hold more.
            settle(level, under.is(com.jeladastudios.ftsgeology.registry.ModBlocks.VOLCANIC_ASH.get())
                    ? ground : ground.above());
        }
    }

    /**
     * Adds one layer of ash to a column, up to a full block. It settles on the ground rather than
     * replacing it, so grass lives, the cone stays basalt and repeated eruptions deepen the deposit.
     *
     * <p>A field under the fall is lost, as farms downwind of Pinatubo and Mount St. Helens were: the crop is
     * buried, and once the ash is a few layers deep the tilled soil under it goes back to dirt. Shovelled off,
     * the ground can be tilled and sown again.</p>
     */
    private static void settle(ServerLevel level, BlockPos at) {
        BlockState here = level.getBlockState(at);
        BlockState ash = com.jeladastudios.ftsgeology.registry.ModBlocks.VOLCANIC_ASH.get()
                .defaultBlockState();
        BlockState below = level.getBlockState(at.below());
        boolean field = below.getBlock() instanceof net.minecraft.world.level.block.FarmBlock;
        if (field && !GeyserConfig.ASHFALL_BURIES_CROPS.get()) return;

        if (here.is(ash.getBlock())) {
            int layers = here.getValue(
                    com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS);
            if (layers >= 8) return;                       // as deep as it goes
            level.setBlock(at, here.setValue(
                    com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS, layers + 1), 2);
            if (field && layers + 1 >= FIELD_LOST_LAYERS) {
                net.minecraft.world.level.block.FarmBlock.turnToDirt(null, below, level, at.below());
            }
            return;
        }
        // Ash falls through a tuft of grass, or a crop, and buries it; it does not stack on top of it.
        if (!here.isAir() && !com.jeladastudios.ftsgeology.worldgen.TerrainProbe.isVegetation(here)) return;
        if (!ash.canSurvive(level, at)) return;
        level.setBlock(at, ash, 2);
    }

    /** Layers of ash over a field at which its tilled soil is lost. */
    private static final int FIELD_LOST_LAYERS = 3;

    /** This mountain's prevailing wind as a unit vector, derived from its position so it never changes. */
    public static double[] wind(BlockPos summit) {
        long h = summit.getX() * 0x9E3779B97F4A7C15L ^ summit.getZ() * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        double a = ((h >>> 11) / (double) (1L << 53)) * Math.PI * 2.0;
        return new double[]{ Math.cos(a), Math.sin(a) };
    }

    /** Once per second while erupting: well lava up the crater so it spills down the mountain. */
    public static boolean spillLava(ServerLevel level, BlockPos summit) {
        // True only when lava was put out, so the core can hold the flow to a budget.
        if (!level.getBlockState(summit).isAir()) return false;
        level.setBlock(summit, Blocks.LAVA.defaultBlockState(), 3);
        level.scheduleTick(summit, Fluids.LAVA, 5);
        return true;
    }

    /** Hurls a volcanic bomb: one block of basalt arcs out and scorches one block where it lands. */
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
        IN_FLIGHT.add(bomb);

        // Impact: a single scorched block at the landing spot.
        impact(level, target, summit.getY());
    }

    /** Bombs in the air, held here to draw their trail, since FallingBlockEntity offers no hook. */
    private static final java.util.List<FallingBlockEntity> IN_FLIGHT = new java.util.ArrayList<>();

    /** Smoke and fire off the back of a bomb, so it reads as thrown rock and not a gliding block. */
    private static void trailBombs(ServerLevel level) {
        if (IN_FLIGHT.isEmpty()) return;
        IN_FLIGHT.removeIf(b -> !b.isAlive() || b.isRemoved() || b.level() != level);
        for (FallingBlockEntity b : IN_FLIGHT) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, b.getX(), b.getY() + 0.2, b.getZ(),
                    2, 0.12, 0.12, 0.12, 0.01);
            level.sendParticles(ParticleTypes.FLAME, b.getX(), b.getY() + 0.2, b.getZ(),
                    1, 0.1, 0.1, 0.1, 0.005);
        }
    }

    /**
     * Scorches one block where a bomb lands: the top solid cell of that column becomes basalt.
     * Replaces rather than stacks and touches no neighbours, so nothing is left floating.
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

    /** Seeps lava out of a surface vent during an eruption, with its own spatter and hiss. */
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

    /** A puff of steam where a lava cell touches water, riding the {@link #coolScatteredLava} sweep. */
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
     * Clears a vent's fresh lava after the eruption and leaves the outlet open, since
     * {@link #seepVent} only fills air. Runoff around it petrifies in {@link #coolScatteredLava}.
     */
    public static void dryVent(ServerLevel level, BlockPos vent) {
        if (level.getBlockState(vent).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            level.setBlock(vent, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /**
     * After an eruption, hardens spilled lava outside the crater into basalt or tuff while the crater
     * lake stays molten. The volcano never grows: lava above the original summit, or resting on rock
     * the volcano laid down, is drained instead, and lava over air is left alone. Bounded scan.
     */
    public static void coolScatteredLava(ServerLevel level, BlockPos summit, int craterR, int reach,
                                         long[] keepVents, long[] molten) {
        int keep2 = (craterR + 1) * (craterR + 1);
        BlockPos lo = summit.offset(-reach, -reach, -reach);
        BlockPos hi = summit.offset(reach, 6, reach);
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            int dx = p.getX() - summit.getX();
            int dz = p.getZ() - summit.getZ();
            if (dx * dx + dz * dz <= keep2) continue; // leave the crater lake molten
            // The volcano's own outlets stay molten between eruptions.
            if (isKept(keepVents, p)) continue;
            // Cells built as lava: summit pool, caldera lake, fissure ponds, shapes a radius cannot express.
            if (isKept(molten, p)) continue;
            FluidState fs = level.getBlockState(p).getFluidState();
            // Any lava resting on solid ground, so a mid-air stream never becomes a spike. Fire it
            // started is put out, unless eruptionsStartFires lets the burn outlive the flow.
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
     * At eruption end, re-lines the summit crater at the summit's own Y: molten inside, cooled rock
     * on the rim. Uses the real carved crater radius, so the rim lands on the rim.
     */
    public static void formCrater(ServerLevel level, BlockPos summit, int craterR, long[] molten) {
        // Refill every cell built as lava, including a caldera's lake and a fissure's ponds.
        if (molten != null) {
            for (long key : molten) {
                BlockPos p = BlockPos.of(key);
                if (level.getBlockState(p).isAir()) {
                    level.setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
                }
            }
        }
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
                    // The whole floor inside the rim, air only, so basalt the eruption laid down stays.
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
