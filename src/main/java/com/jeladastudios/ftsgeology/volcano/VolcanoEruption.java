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
        level.sendParticles(ParticleTypes.LAVA, x, summit.getY() + 1.0, z, 6, 0.5, 0.3, 0.5, 0.0);
        level.sendParticles(ParticleTypes.FLAME, x, summit.getY() + 1.5, z, 8, 0.5, 0.7, 0.5, 0.05);

        // The base of the column, and it has to be BLACK.
        //
        // This was five smoke particles in a metre-wide puff, which testing called far too light -
        // and rightly, because the ash column above it had meanwhile been built to reach the world
        // ceiling. A plume that fans out enormously overhead and comes out of a wisp at the crater
        // reads as two unrelated effects. So the throat is now dense and dark, and the column takes
        // over from something already thick rather than from nothing.
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, summit.getY() + 2.0, z,
                18, 1.4, 1.0, 1.4, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, summit.getY() + 5.0, z,
                14, 2.0, 1.8, 2.0, 0.04);
        // A little of the paler ash mixed through it, so the black is not a flat silhouette.
        level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, summit.getY() + 4.0, z,
                4, 1.2, 1.2, 1.2, 0.02);

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

        // The part you can see from the far side of the valley.
        ashColumn(level, summit, magnitude, eruptionTicks);
        ashfall(level, summit, magnitude);
        trailBombs(level);
        if (eruptionTicks % 4 == 0) ashInTheAir(level, summit, magnitude);
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
     * The eruption column: ash going up for hundreds of blocks and leaning off downwind.
     *
     * <h2>Why nothing above was ever visible from a distance</h2>
     * Every particle in this class is emitted within a block or two of the vent, and the tallest of
     * them - the fountain smoke in {@link #tickEruption} - reaches {@code summit + 4}. So a volcano
     * in full eruption said nothing at all to anybody who was not standing on it, which for the
     * single largest event in the mod is the wrong way round: in life an eruption column is the
     * thing you see first and from furthest away, and it is how you know to go and look.
     *
     * <h2>The trap: ordinary sendParticles reaches 32 blocks</h2>
     * {@code ServerLevel.sendParticles(type, x, y, z, ...)} only sends to players within <b>32
     * blocks</b>. A column visible from three hundred is therefore impossible that way no matter how
     * many particles are asked for - they are simply never sent, and the fix looks like it failed.
     * The per-player overload with {@code longDistance = true} raises that to 512, so this walks the
     * player list itself and sends to each one directly.
     *
     * <p>That also makes the cost controllable, because the budget can then depend on how far away
     * the viewer is: somebody on the far ridge gets the silhouette, somebody on the slope gets the
     * full thing.</p>
     */
    private static void ashColumn(ServerLevel level, BlockPos summit, int magnitude, int eruptionTicks) {
        if (eruptionTicks % 2 != 0) return;      // twice a tick per player is fog, not a plume

        // How high the column stands, bounded by the world rather than by taste: a big eruption
        // should genuinely reach the ceiling, because that is what makes it read as enormous.
        int height = Math.min(30 + magnitude * 20, level.getMaxBuildHeight() - summit.getY() - 2);
        if (height < 12) return;

        double[] wind = wind(summit);
        double reach = 512.0;

        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            double dx = p.getX() - summit.getX(), dz = p.getZ() - summit.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > reach) continue;

            // Six segments up the column. Near the vent it is dense and dark; higher up it thins
            // and spreads into the drifting cloud, which is the shape that reads as an ash plume
            // rather than as a chimney.
            int segments = 6;
            int near = dist < 64 ? 4 : dist < 200 ? 3 : 2;
            for (int i = 0; i < segments; i++) {
                double t = (i + 0.5) / segments;
                double y = summit.getY() + 1.0 + t * height;
                // Leans downwind, further the higher it goes.
                double lean = t * t * height * 0.45;
                double x = summit.getX() + 0.5 + wind[0] * lean;
                double z = summit.getZ() + 0.5 + wind[1] * lean;
                // And widens, so the top is a cloud and the bottom is a stalk.
                double spread = 1.0 + t * height * 0.10;

                level.sendParticles(p, ParticleTypes.LARGE_SMOKE, true,
                        x, y, z, near, spread, height / (double) segments * 0.4, spread, 0.01);
                // The pale upper cloud, where the ash is fine enough to catch the light. Only for
                // people close enough to tell two greys apart: past a couple of hundred blocks it is
                // a silhouette either way, and this is a third of the packets the column sends.
                if (t > 0.45 && dist < 256) {
                    level.sendParticles(p, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                            x, y, z, Math.max(1, near - 1), spread * 1.3, spread * 0.5, spread * 1.3, 0.005);
                }
            }
        }
    }

    /**
     * Ash settling out of the column onto the ground downwind.
     *
     * <p>What lands is a layer of {@link com.jeladastudios.ftsgeology.block.VolcanicAshBlock}, which
     * accumulates rather than replacing what it falls on - see {@link #settle} for why that turned
     * out to be the whole design and not a detail.</p>
     *
     * <p>Off-centre on purpose. A ring of ash around a volcano would be wrong: the column goes where
     * the wind takes it, so the deposit is a lobe on one side, which is also what makes it readable
     * on the ground - you can tell which way the wind was blowing when it went off.</p>
     */
    private static void ashfall(ServerLevel level, BlockPos summit, int magnitude) {
        if (!GeyserConfig.VOLCANIC_ASHFALL.get()) return;

        double[] wind = wind(summit);
        int reach = Math.min(40 + magnitude * 8, 160);

        // Enough columns per call that a minute-long eruption visibly greys the country downwind.
        //
        // The first version sampled six a call, which measured out at three percent of the lobe over
        // a whole eruption - arithmetically an ash fall, and on screen nothing whatever. Forty was
        // the correction and overshot: testing liked the look and said there was too much of it, so
        // this is halved again. Coverage does not halve with it, because a good share of the samples
        // were landing on ground that was already ashed.
        for (int n = 0; n < 20; n++) {
            // Distance is square-root biased so the samples spread evenly over the disc rather than
            // piling up at the middle; the thinning below is what puts the weight near the vent.
            double d = reach * Math.sqrt(level.random.nextDouble());

            // A direction anywhere on the compass, then weighted by how well it lines up with the
            // wind. Taking the angle from a fixed cone instead - which is what this did first - put
            // a hundred percent of the ash in one half and zero in the other, and a lobe with a hard
            // angular edge reads as a pie slice rather than as weather. Real fall is heaviest
            // downwind and merely light elsewhere, so every bearing gets some.
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

            // The crater is not a place ash settles - it is where the column is coming OUT of.
            // Without this the fall quietly fills the lava pool it was launched from.
            double sx = x - summit.getX(), sz = z - summit.getZ();
            if (sx * sx + sz * sz < 12 * 12) continue;

            int g = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z);
            if (g == Integer.MIN_VALUE) continue;
            if (com.jeladastudios.ftsgeology.worldgen.TerrainProbe.hasFluidAbove(level, x, z)) continue;

            BlockPos ground = new BlockPos(x, g, z);
            BlockState under = level.getBlockState(ground);
            if (under.is(Blocks.BEDROCK) || !under.getFluidState().isEmpty()) continue;
            // Not onto a live flow. It would be buried by the next lava anyway, and a grey crust
            // over molten rock is the one thing here that would read as a bug.
            if (under.is(Blocks.LAVA) || under.is(Blocks.MAGMA_BLOCK)) continue;

            settle(level, ground.above());
        }
    }

    /**
     * Adds one layer of ash to a column, up to a full block.
     *
     * <h2>It settles ON the ground; it does not become the ground</h2>
     * This replaced the surface block at first - grass, gravel, whatever was there became tuff or
     * coarse dirt - and testing found the hole in that immediately: the volcano is inside its own
     * fall radius, its basalt reads as natural terrain rather than as somebody's build, and so the
     * mountain was turned into dirt and gravel by its own eruption.
     *
     * <p>The fix was not a bigger exclusion zone around the cone. It was that an ash fall does not
     * replace anything, and everything else follows from saying so: the grass lives, the cone stays
     * basalt and merely greys over, a second eruption deepens the deposit instead of re-stamping it,
     * and a player can dig it off.</p>
     */
    private static void settle(ServerLevel level, BlockPos at) {
        BlockState here = level.getBlockState(at);
        BlockState ash = com.jeladastudios.ftsgeology.registry.ModBlocks.VOLCANIC_ASH.get()
                .defaultBlockState();

        if (here.is(ash.getBlock())) {
            int layers = here.getValue(
                    com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS);
            if (layers >= 8) return;                       // as deep as it goes
            level.setBlock(at, here.setValue(
                    com.jeladastudios.ftsgeology.block.VolcanicAshBlock.LAYERS, layers + 1), 2);
            return;
        }
        // Ash falls through a tuft of grass and buries it; it does not stack on top of it.
        if (!here.isAir() && !com.jeladastudios.ftsgeology.worldgen.TerrainProbe.isVegetation(here)) return;
        if (!ash.canSurvive(level, at)) return;
        level.setBlock(at, ash, 2);
    }

    /**
     * This mountain's prevailing wind, as a unit vector.
     *
     * <p>Derived from its own position, so it is the same every eruption and after every reload
     * without storing anything: a volcano that ashes the eastern valley goes on ashing the eastern
     * valley, and the deposit on the ground stays consistent with the column in the sky.</p>
     */
    private static double[] wind(BlockPos summit) {
        long h = summit.getX() * 0x9E3779B97F4A7C15L ^ summit.getZ() * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        double a = ((h >>> 11) / (double) (1L << 53)) * Math.PI * 2.0;
        return new double[]{ Math.cos(a), Math.sin(a) };
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
        IN_FLIGHT.add(bomb);

        // Impact: a single scorched block at the landing spot.
        impact(level, target, summit.getY());
    }

    /**
     * Bombs still in the air, so a trail can be drawn behind them.
     *
     * <p>{@link FallingBlockEntity} ticks itself and tells nobody, so there is no hook on the entity
     * to hang this from; holding the handful in flight and drawing from the eruption's own tick is
     * both simpler than an entity mixin and self-limiting, since the list only ever contains the
     * bombs one volcano has thrown in the last few seconds.</p>
     */
    private static final java.util.List<FallingBlockEntity> IN_FLIGHT = new java.util.ArrayList<>();

    /**
     * Smoke and fire off the back of a bomb on its way over.
     *
     * <p>Without it a bomb is a block of basalt gliding through the air, which reads as a glitch
     * rather than as something thrown out of a volcano. The trail is what tells you it is hot.</p>
     */
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
     * Ash drifting down through the air, as opposed to ash already on the ground.
     *
     * <p>The deposit was there and the fall itself was not, so the ground quietly turned grey around
     * a player who never saw anything come down. Vanilla has the particle for it already -
     * {@code ParticleTypes.ASH}, from the basalt deltas - so this needs no sprite of its own.</p>
     */
    private static void ashInTheAir(ServerLevel level, BlockPos summit, int magnitude) {
        if (!GeyserConfig.VOLCANIC_ASHFALL.get()) return;

        double[] wind = wind(summit);
        int reach = Math.min(40 + magnitude * 8, 160);
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            double dx = p.getX() - summit.getX(), dz = p.getZ() - summit.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > reach + 32) continue;
            // Falling harder downwind, the same lobe the deposit follows, so what is in the air
            // agrees with what is on the ground.
            double align = dist < 1 ? 1.0 : (dx / dist) * wind[0] + (dz / dist) * wind[1];
            int count = (int) Math.round(6 * (0.25 + 0.75 * (align + 1.0) * 0.5));

            level.sendParticles(p, ParticleTypes.ASH, true,
                    p.getX() + (level.random.nextDouble() - 0.5) * 24,
                    p.getY() + 6 + level.random.nextDouble() * 8,
                    p.getZ() + (level.random.nextDouble() - 0.5) * 24,
                    count, 10.0, 4.0, 10.0, 0.0);
        }
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
                                         long[] keepVents, long[] molten) {
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
            // Cells the volcano was BUILT with as lava - the summit pool, a caldera's crescent
            // lake, a fissure's ponds. The radius check above cannot express those shapes: a
            // caldera's lake reaches 0.85 of the crater radius while its keep radius was a third of
            // it, so after one eruption most of the lake had been turned to basalt and formCrater
            // never refilled it. That is the "hardly any lava in the crater" report.
            if (isKept(molten, p)) continue;
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
    public static void formCrater(ServerLevel level, BlockPos summit, int craterR, long[] molten) {
        // Refill every cell the volcano was built with as lava, wherever it is. The radius loop
        // below only reaches the summit pool; a caldera's lake and a fissure's ponds sit outside it
        // and would stay as whatever the eruption left them.
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
