package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    public static void rumble(ServerLevel level, BlockPos summit, long time) {
        if (time % 40L == 0L) {
            level.playSound(null, summit, SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), SoundSource.BLOCKS,
                    1.5f, 0.4f);
        }
    }

    /** Per-tick eruption spectacle: fire fountain particles, the odd bomb, ambient roar. */
    public static void tickEruption(ServerLevel level, BlockPos summit, int magnitude, int craterR, int eruptionTicks) {
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
            throwBomb(level, summit, magnitude, craterR);
        }
        if (eruptionTicks % 30 == 0) {
            level.playSound(null, summit, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 1.2f, 0.5f);
        }

        ashfall(level, summit, magnitude);
        if (eruptionTicks % 100 == 0) com.jeladastudios.ftsgeology.advancement.GeologyTrigger.awardNear(level, summit.getX(), summit.getZ(), 200, "eruption");
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
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(x, level.getSeaLevel(), z))) continue;
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
    private static boolean settle(ServerLevel level, BlockPos at) {
        BlockState here = level.getBlockState(at);
        BlockState ash = com.jeladastudios.ftsgeology.registry.ModBlocks.VOLCANIC_ASH.get()
                .defaultBlockState();
        BlockState below = level.getBlockState(at.below());
        boolean field = below.getBlock() instanceof net.minecraft.world.level.block.FarmBlock;
        if (field && !GeyserConfig.ASHFALL_BURIES_CROPS.get()) return false;

        if (here.is(ash.getBlock())) {
            int layers = here.getValue(
                    com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS);
            if (layers >= 8) return false;                 // as deep as it goes
            level.setBlock(at, TfcCompat.translate(level, at, here.setValue(
                    com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS, layers + 1)), 2);
            if (field && layers + 1 >= FIELD_LOST_LAYERS) {
                net.minecraft.world.level.block.FarmBlock.turnToDirt(null, below, level, at.below());
            }
            AshLoad.layered(level, at, layers + 1);
            return true;
        }
        // Ash falls through a tuft of grass, or a crop, and buries it; it does not stack on top of it.
        if (!here.isAir() && !com.jeladastudios.ftsgeology.worldgen.TerrainProbe.isVegetation(here)) return false;
        if (!ash.canSurvive(level, at)) return false;
        level.setBlock(at, TfcCompat.translate(level, at, ash), 2);
        AshLoad.layered(level, at, 1);
        return true;
    }

    /**
     * Lays {@code layers} layers of ash on a column whose ground is at {@code groundY}: onto ash already lying there,
     * and on up over a full block of it. Returns how many went down.
     */
    public static int layAsh(ServerLevel level, int x, int groundY, int z, int layers) {
        net.minecraft.world.level.block.Block block = com.jeladastudios.ftsgeology.registry.ModBlocks.VOLCANIC_ASH.get();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, groundY, z);
        int laid = 0;
        for (int i = 0; i < layers && m.getY() < level.getMaxBuildHeight() - 2; i++) {
            BlockState g = level.getBlockState(m);
            while (g.is(block) && g.getValue(com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS) >= 8) {
                m.move(0, 1, 0);
                g = level.getBlockState(m);
            }
            boolean onto = g.is(block);
            BlockPos at = onto ? m.immutable() : m.above();
            if (!settle(level, at)) break;
            laid++;
            if (!onto) m.move(0, 1, 0);
        }
        return laid;
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
        level.setBlock(summit, TfcCompat.translate(level, summit, Blocks.LAVA.defaultBlockState()), 3);
        level.scheduleTick(summit, Fluids.LAVA, 5);
        return true;
    }

    /** How far past the crater's rim bombs reach, per unit of magnitude beyond six, at the default reach. */
    private static final double BOMB_REACH = 2.5;
    /** The most ticks a bomb is flown for when its throw is worked out; a falling block gives up at 600. */
    private static final int BOMB_FLIGHT = 300;

    /**
     * Hurls a volcanic bomb: one block of basalt thrown out over the crater's rim, to come down on the flanks and lie
     * where it lands. Most fall within a crater's width or two of the rim, the odd one far down the mountain.
     */
    public static void throwBomb(ServerLevel level, BlockPos summit, int magnitude, int craterR) {
        double inner = craterR + 4;
        double outer = inner + BOMB_REACH * (6 + magnitude) * GeyserConfig.VOLCANO_BOMB_REACH.get();
        double d = inner + (outer - inner) * Math.pow(level.random.nextDouble(), 1.5);
        double a = level.random.nextDouble() * Math.PI * 2;
        int tx = summit.getX() + (int) Math.round(Math.cos(a) * d);
        int tz = summit.getZ() + (int) Math.round(Math.sin(a) * d);
        if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, new BlockPos(tx, summit.getY(), tz))) return;
        int tg = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, tx, tz);
        if (tg == Integer.MIN_VALUE) return;

        // Thrown so as to come down on the target: the rise decides how long it flies, and the push out is what covers
        // the distance over that time, as a falling block flies -- pulled down, then slowed by the air, every tick.
        // Thrown high enough to clear the crater's rim on its way: from a vent at the foot of a funnel, a low throw hit the
        // funnel's wall and fell back in.
        BlockPos from = summit.above(2);
        double dx = Math.cos(a), dz = Math.sin(a);
        int rim = Integer.MIN_VALUE;
        for (int r = Math.max(1, craterR - 4); r <= craterR + 6; r++) {
            rim = Math.max(rim, com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level,
                    summit.getX() + (int) Math.round(dx * r), summit.getZ() + (int) Math.round(dz * r)));
        }
        for (double rise = 0.9 + level.random.nextDouble() * 0.6; rise <= BOMB_RISE; rise += 0.1) {
            int ticks = flight(from.getY(), tg + 1.0, rise);
            if (ticks < 0) continue;
            double push = d / ((1 - Math.pow(0.98, ticks)) / 0.02);
            if (!clears(from.getY(), rise, push, Math.max(1, craterR - 4), craterR + 6, rim + 2)) continue;
            FallingBlockEntity bomb = FallingBlockEntity.fall(level, from, Blocks.BASALT.defaultBlockState());
            bomb.setDeltaMovement(new Vec3(dx * push, rise, dz * push));
            bomb.setHurtsEntities(3.0f, 12);
            bomb.hurtMarked = true;
            IN_FLIGHT.put(bomb, summit);
            THROWN++;
            return;
        }
    }

    /** The hardest a bomb is thrown up, in blocks a tick. */
    private static final double BOMB_RISE = 3.0;

    /**
     * Ticks a bomb thrown up at {@code rise} from {@code y} takes to come back down to {@code ground}, or -1 if it never
     * gets that high.
     */
    private static int flight(double y, double ground, double rise) {
        double v = rise;
        for (int t = 1; t <= BOMB_FLIGHT; t++) {
            v -= 0.04;
            y += v;
            v *= 0.98;
            if (v < 0 && y <= ground) return t;
        }
        return -1;
    }

    /** Whether a bomb thrown up at {@code rise} and out at {@code push} is over {@code rimY} from {@code in} out to {@code out}. */
    private static boolean clears(double y, double rise, double push, double in, double out, int rimY) {
        double v = rise, h = 0;
        for (int t = 1; t <= BOMB_FLIGHT && h < out; t++) {
            v -= 0.04;
            y += v;
            h += push;
            v *= 0.98;
            push *= 0.98;
            if (h >= in && y < rimY) return false;
        }
        return h >= out;
    }

    /** Bombs in the air and the vents they came from, held here to draw their trail: FallingBlockEntity offers no hook. */
    private static final java.util.Map<FallingBlockEntity, BlockPos> IN_FLIGHT = new java.util.LinkedHashMap<>();

    /**
     * Every tick while bombs are in the air, eruption or not: smoke and fire off the back of each, so it reads as thrown
     * rock and not a gliding block; a bomb about to come down in lava sinks into it instead of setting there as a block,
     * or a crater lake filled up with its own bombs; and the thud and dust of one landing.
     */
    public static void tickBombs(ServerLevel level) {
        if (IN_FLIGHT.isEmpty()) return;
        // Every volcano core in the world calls this each tick; once is enough.
        if (level.getGameTime() == bombTick) return;
        bombTick = level.getGameTime();
        boolean melt = GeyserConfig.VOLCANO_BOMBS_MELT_IN_LAVA.get();
        for (java.util.Iterator<java.util.Map.Entry<FallingBlockEntity, BlockPos>> it = IN_FLIGHT.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<FallingBlockEntity, BlockPos> e = it.next();
            FallingBlockEntity b = e.getKey();
            if (b.level() != level) continue;
            if (!b.isAlive() || b.isRemoved()) {
                it.remove();
                landed(level, b.position());
                LANDED++;
                LANDED_AT += Math.hypot(b.getX() - e.getValue().getX() - 0.5, b.getZ() - e.getValue().getZ() - 0.5);
                continue;
            }
            if (melt && headsIntoLava(level, b)) {
                level.sendParticles(ParticleTypes.LAVA, b.getX(), b.getY(), b.getZ(), 10, 0.4, 0.2, 0.4, 0.1);
                level.playSound(null, b.blockPosition(), SoundEvents.LAVA_POP, SoundSource.BLOCKS, 1.5f, 0.7f);
                b.discard();
                it.remove();
                MELTED++;
                continue;
            }
            level.sendParticles(ParticleTypes.LARGE_SMOKE, b.getX(), b.getY() + 0.2, b.getZ(),
                    2, 0.12, 0.12, 0.12, 0.01);
            level.sendParticles(ParticleTypes.FLAME, b.getX(), b.getY() + 0.2, b.getZ(),
                    1, 0.1, 0.1, 0.1, 0.005);
        }
    }

    private static long bombTick = -1;

    /** Bombs thrown, sunk in lava and landed, and how far from their vent all the landed ones came down, for the log. */
    private static long THROWN, MELTED, LANDED;
    private static double LANDED_AT;

    public static String bombSummary() {
        return String.format(java.util.Locale.ROOT, "bombs: %d thrown, %d sank in lava, %d landed %.0f blocks out on average",
                THROWN, MELTED, LANDED, LANDED == 0 ? 0.0 : LANDED_AT / LANDED);
    }

    /**
     * Whether a bomb passes through lava before its next tick is over. Looked for along the whole way it is about to
     * move and a block under it, since a fast bomb goes through a shallow lake and sets on its floor in a single tick.
     */
    private static boolean headsIntoLava(ServerLevel level, FallingBlockEntity b) {
        Vec3 p = b.position(), v = b.getDeltaMovement().add(0, -0.04, 0);
        int steps = 1 + (int) Math.ceil(v.length() * 2);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            at.set(p.x + v.x * f, p.y + v.y * f, p.z + v.z * f);
            if (level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        at.set(p.x + v.x, p.y + v.y - 1, p.z + v.z);
        return v.y < 0 && level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA);
    }

    /** The thud and the dust of a bomb coming down. */
    private static void landed(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.LAVA, at.x, at.y + 0.3, at.z, 8, 0.4, 0.2, 0.4, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.8, at.z, 6, 0.3, 0.2, 0.3, 0.02);
        level.playSound(null, BlockPos.containing(at), SoundEvents.BASALT_BREAK, SoundSource.BLOCKS, 1.5f, 0.6f);
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
            level.setBlock(vent, TfcCompat.translate(level, vent, Blocks.LAVA.defaultBlockState()), 3);
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
            level.setBlock(vent, TfcCompat.translate(level, vent, Blocks.AIR.defaultBlockState()), 3);
        }
    }

    /**
     * After an eruption, hardens spilled lava outside the crater while the crater lake stays molten. A flow becomes
     * a skin on the ground it ran over: the volcano never grows, lava above the original summit is drained, and
     * lava over air is left alone. Bounded scan.
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
                    level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 2);
                }
                continue;
            }
            if (!fs.is(net.minecraft.tags.FluidTags.LAVA)) continue;
            // Quench steam wherever the flow has reached water. Sampled rather than fired on every
            // cell of every sweep, so a long shoreline hisses instead of turning white.
            if (level.random.nextInt(3) == 0) steamIfWet(level, p);
            BlockState below = level.getBlockState(p.below());
            if (below.isAir() || !below.getFluidState().isEmpty()) continue;   // must rest on solid ground
            if (p.getY() > summit.getY()) {
                level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 2); // drain it: never stack upward
                continue;
            }
            // Lava that pooled in a hollow freezes where it lies.
            if (walledSides(level, p) >= 3) {
                level.setBlock(p, TfcCompat.translate(level, p, (level.random.nextInt(3) == 0
                        ? Blocks.TUFF : Blocks.BASALT).defaultBlockState()), 2);
                continue;
            }
            // A stream over the surface leaves a skin, never a course laid on top: cooling in place combed a flank
            // with ridges one block high along every finger the flow split into. Only a sheet of lava leaves one:
            // a thread one block wide, which is what a flow splits into on the way down, scorches the ground and
            // is gone, or every eruption combed the flank again in tuff. The ground under a sheet turns to rock,
            // mostly tuff so the trace reads as baked ground rather than a black stripe; the volcano's own rock
            // takes a fresh crust.
            level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 2);
            if (flowSides(level, p) < 2) continue;
            if (below.is(Blocks.BEDROCK) || below.hasBlockEntity()
                    || com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(below)) continue;
            BlockState skin = ownRock(below)
                    ? com.jeladastudios.ftsgeology.registry.ModBlocks.COOLING_LAVA_CRUST.get().defaultBlockState()
                    : (level.random.nextInt(100) < 65 ? Blocks.TUFF : Blocks.BASALT).defaultBlockState();
            level.setBlock(p.below(), TfcCompat.translate(level, p.below(), skin), 2);
        }
    }

    /** How many of the four horizontal neighbours are solid: three or more and the cell is a hollow. */
    private static int walledSides(ServerLevel level, BlockPos p) {
        int n = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockState s = level.getBlockState(p.relative(d));
            if (!s.isAir() && s.getFluidState().isEmpty()) n++;
        }
        return n;
    }

    /**
     * How many of the four horizontal neighbours are part of the flow: lava, or a skin already left this sweep. Two
     * or more and the cell is inside a sheet; fewer and it is a thread.
     */
    private static int flowSides(ServerLevel level, BlockPos p) {
        int n = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos q = p.relative(d);
            if (level.getBlockState(q).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                    || ownRock(level.getBlockState(q.below()))) n++;
        }
        return n;
    }

    /** Rock a volcano lays down itself, which a fresh flow crusts over rather than turning to more of it. */
    private static boolean ownRock(BlockState s) {
        return s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BLACKSTONE) || s.is(Blocks.TUFF)
                || s.is(Blocks.MAGMA_BLOCK)
                || s.is(com.jeladastudios.ftsgeology.registry.ModBlocks.COOLING_LAVA_CRUST.get());
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
     * At eruption end, re-lines the summit crater at the summit's own Y: the cells built as lava refilled, and the
     * lava's open edges walled. A volcano saved before it kept that list gets the disc it always had instead: molten
     * inside the carved crater radius, cooled rock on the rim.
     */
    public static void formCrater(ServerLevel level, BlockPos summit, int craterR, long[] molten) {
        // Refill every cell built as lava, including a caldera's lake and a fissure's ponds. Basalt standing in one is a
        // bomb that set there before bombs sank, and melts back into the lake.
        boolean melt = GeyserConfig.VOLCANO_BOMBS_MELT_IN_LAVA.get();
        if (molten != null) {
            for (long key : molten) {
                BlockPos p = BlockPos.of(key);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || (melt && s.is(Blocks.BASALT))) {
                    level.setBlock(p, TfcCompat.translate(level, p, Blocks.LAVA.defaultBlockState()), 3);
                }
            }
        }
        // With the list, the list is the crater. A round disc over a shield's lobed lake flooded the ground between
        // its lobes, rimless, and the lava ran down the flank after every eruption.
        if (molten == null || molten.length == 0) fillCraterDisc(level, summit, craterR);
        sealMoltenEdges(level, molten, summit, craterR + 1);
        // What ran off the summit and is no longer fed: a lake walled only now left its old overflow standing on the
        // flank, and running lava nothing feeds and nothing ticks stays where it is.
        int reach = craterR;
        if (molten != null) {
            for (long key : molten) {
                BlockPos p = BlockPos.of(key);
                reach = Math.max(reach, (int) Math.ceil(Math.hypot(p.getX() - summit.getX(), p.getZ() - summit.getZ())));
            }
        }
        drainRunningLava(level, summit, reach + DRAIN_PAST_LAKE, DRAIN_DEPTH);
    }

    /** How far past the summit's lava, and how far under it, running lava is drained at the end of an eruption. */
    private static final int DRAIN_PAST_LAKE = 24, DRAIN_DEPTH = 48;

    /**
     * Takes the running lava round a summit, within {@code radius} of it and from {@code depth} under it to a few blocks
     * over it. Only the chunk sections whose palette holds running lava are looked through.
     */
    private static void drainRunningLava(ServerLevel level, BlockPos summit, int radius, int depth) {
        int y0 = summit.getY() - depth, y1 = summit.getY() + 4;
        long r2 = (long) radius * radius;
        java.util.function.Predicate<BlockState> running = s -> s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                && !s.getFluidState().isSource();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int cx = (summit.getX() - radius) >> 4; cx <= (summit.getX() + radius) >> 4; cx++) {
            for (int cz = (summit.getZ() - radius) >> 4; cz <= (summit.getZ() + radius) >> 4; cz++) {
                if (!com.jeladastudios.ftsgeology.util.Loaded.chunk(level, cx, cz)) continue;
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int index = chunk.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= chunk.getSections().length) continue;
                    net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(index);
                    if (section.hasOnlyAir() || !section.getStates().maybeHas(running)) continue;
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int x = (cx << 4) + lx, z = (cz << 4) + lz;
                            long dx = x - summit.getX(), dz = z - summit.getZ();
                            if (dx * dx + dz * dz > r2) continue;
                            for (int ly = 0; ly < 16; ly++) {
                                int y = (sy << 4) + ly;
                                if (y < y0 || y > y1) continue;
                                if (!running.test(section.getBlockState(lx, ly, lz))) continue;
                                p.set(x, y, z);
                                level.setBlock(p, TfcCompat.translate(level, p, Blocks.AIR.defaultBlockState()), 3);
                            }
                        }
                    }
                }
            }
        }
    }

    /** The crater of a volcano saved without its list of lava cells: molten inside the radius, cooled rock on the rim. */
    private static void fillCraterDisc(ServerLevel level, BlockPos summit, int craterR) {
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
                    if (fs.isEmpty()) level.setBlock(p, TfcCompat.translate(level, p, Blocks.LAVA.defaultBlockState()), 3);
                } else {
                    // rim: cooled volcanic rock, occasionally still smouldering
                    if (!s.isAir() && fs.isEmpty()) {
                        level.setBlock(p, TfcCompat.translate(level, p, (level.random.nextInt(3) == 0
                                ? Blocks.MAGMA_BLOCK : Blocks.BASALT).defaultBlockState()), 3);
                    }
                }
            }
        }
    }

    /**
     * Walls the lava of a volcano's summit where it stands open: beside each cell of it, a cell of air or plants, or
     * lava running out of it, that is not itself lava standing still becomes basalt. The lava is the cells it was built
     * with and, within {@code radius} of {@code summit} at the summit's own height, any other still lava there, which is
     * what the round crater disc of earlier eruptions left between a shield's lobes. A shield's lake lobed past the
     * square its summit was carved in was left without a rim there on the tall world's big shields, and the lake ran
     * down the flank; walled at the end of every eruption, one built that way holds after its next. Costs a look at
     * four neighbours a cell.
     */
    public static void sealMoltenEdges(ServerLevel level, long[] molten, BlockPos summit, int radius) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet cells = molten == null
                ? new it.unimi.dsi.fastutil.longs.LongOpenHashSet()
                : new it.unimi.dsi.fastutil.longs.LongOpenHashSet(molten);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos p = summit.offset(dx, 0, dz);
                FluidState fs = level.getFluidState(p);
                if (fs.is(net.minecraft.tags.FluidTags.LAVA) && fs.isSource()) cells.add(p.asLong());
            }
        }
        if (cells.isEmpty()) return;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (long key : cells.toLongArray()) {
            BlockPos p = BlockPos.of(key);
            if (!level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) continue;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                n.setWithOffset(p, d);
                if (cells.contains(n.asLong())) continue;
                BlockState s = level.getBlockState(n);
                if (s.is(Blocks.BEDROCK) || com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(s)) continue;
                FluidState fs = s.getFluidState();
                boolean runningOut = fs.is(net.minecraft.tags.FluidTags.LAVA) && !fs.isSource();
                if (s.isAir() || com.jeladastudios.ftsgeology.worldgen.TerrainProbe.isVegetation(s) || runningOut) {
                    level.setBlock(n, TfcCompat.translate(level, n, Blocks.BASALT.defaultBlockState()), 3);
                }
            }
        }
    }
}
