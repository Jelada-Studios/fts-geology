package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.blockentity.VolcanoCoreBlockEntity;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.worldgen.MagmaSealing;
import com.jeladastudios.ftsgeology.worldgen.RetrogenHandler;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Carves a whole volcano and its geothermal field.
 *
 * <h2>The edifice is a height field, not a stack of rings</h2>
 * The cone used to be built as horizontal discs starting at the summit column's ground level and
 * filling whatever air they found. On any slope the outer discs therefore hung in space with nothing
 * beneath them, which is why volcanoes appeared with parts floating and met the landscape at a sheer
 * step. Now every column in the footprint works out the height the finished mountain should reach
 * there and fills up from <b>its own</b> ground to meet it. A column can only ever be filled from the
 * ground upward, so a floating block is not merely unlikely - it is impossible to express.
 *
 * <h2>Each type is genuinely a different mountain</h2>
 * Profile exponent, summit treatment, rock recipe and where the flank vents sit all come from
 * {@link VolcanoType}, so a stratocone, a shield, a fissure swarm and a caldera read as four
 * different landforms rather than one mound at four sizes. A caldera in particular now <b>digs</b>:
 * it is a collapse structure, and building it upward as a low wide cone was why it looked like a
 * basalt dinner plate.
 *
 * <p>All of it is emitted as a {@link VolcanoJob} and applied a slice per tick.</p>
 */
public final class VolcanoBuilder {

    private VolcanoBuilder() {}

    /** How far below the local ground a lava vein must stay so it can never break out of a slope. */
    private static final int LAVA_SURFACE_CLEARANCE = 6;

    /** Builds the volcano with its base at the given position. Returns false if the site was refused. */
    public static boolean build(ServerLevel level, BlockPos summit, int magnitude) {
        VolcanoType type = VolcanoType.forLocation(level, summit.getX(), summit.getZ(),
                magnitude, level.random);
        return build(level, summit, magnitude, type);
    }

    /**
     * Plans a volcano of an explicit shape and queues it for construction. The setting normally
     * chooses the shape (see {@link VolcanoType}); this overload exists so the inspection command can
     * demonstrate each one.
     *
     * @return true if the site was accepted and the build was queued
     */
    public static boolean build(ServerLevel level, BlockPos base, int magnitude, VolcanoType type) {
        Ctx c = layout(level, base, magnitude, type);
        if (c == null) return false;

        VolcanoJob job = new VolcanoJob(level, type + " @ " + c.x + "," + c.z);

        // 1. Strip the canopy off the whole footprint before anything is raised.
        forEachRow(job, c.clearReach, dx -> lvl -> clearSiteRow(lvl, c, dx));

        // 2. The edifice itself, one row of columns at a time.
        if (c.coneHeight > 0) {
            forEachRow(job, c.coneBaseR + 2, dx -> lvl -> buildConeRow(lvl, c, dx));
        }

        // 3. A caldera does the opposite: it excavates its floor and throws up a ring scarp.
        if (c.type.excavates()) {
            forEachRow(job, calderaRingReach(c), dx -> lvl -> carveCalderaRow(lvl, c, dx));
        }

        // 4. The debris apron that blends whatever we built into the countryside.
        forEachRow(job, c.apronReach, dx -> lvl -> buildApronRow(lvl, c, dx));

        // 5. The summit: each type finishes differently.
        job.add(lvl -> buildSummit(lvl, c));

        // 6. Plumbing, once the vent position is known.
        job.add(lvl -> fillLavaDisc(lvl, c.x, c.reservoirY, c.z, c.reservoirR, 3));
        job.add(lvl -> plantCore(lvl, c));
        job.add(lvl -> carveConduit(lvl, c));
        job.add(lvl -> growLavaBranches(lvl, c));

        // 7. Flank outlets, spaced apart and laid out in this type's own pattern.
        job.add(lvl -> chooseVents(lvl, c));
        for (int i = 0; i < c.ventCount; i++) {
            final int idx = i;
            job.add(lvl -> cutVent(lvl, c, idx));
        }
        job.add(lvl -> recordVents(lvl, c));

        // 8. The geothermal field around it, then the safety sweep.
        job.add(lvl -> placeField(lvl, c));
        job.add(lvl -> sealExposedLava(lvl, c));
        job.add(lvl -> verifyContainment(lvl, c));

        if (!VolcanoJob.enqueue(job)) return false;
        GeysersMod.LOGGER.debug("Volcano {} (magnitude {}, cone {}) queued at {}, {}, {}",
                type, magnitude, c.coneHeight, c.x, c.baseY, c.z);
        return true;
    }

    // === Layout =============================================================

    /** Everything the steps need to know, computed once up front and then shared by all of them. */
    private static final class Ctx {
        VolcanoType type;
        int magnitude;
        int x, z, baseY;
        int coneHeight, summitY, craterR, coneBaseR;
        int reservoirY, reservoirR;
        int clearReach, apronReach;
        int calderaFloorY, domeR, domeH;
        double lakeAngle, lakeWidth;
        double phaseA, phaseB, phaseC;
        int bandSeed;
        double strikeX = 1, strikeZ = 0;
        int ventCount;
        /** Filled in by the summit step: the lava cell the core sits under. */
        BlockPos vent;
        int coreCraterR = 3;
        final List<BlockPos> ventSites = new ArrayList<>();
        final List<BlockPos> vents = new ArrayList<>();
        /**
         * Cells that are MEANT to be lava between eruptions - the crater pool, a caldera's lake
         * crescent, every pond of a fissure swarm.
         *
         * <p>{@code coolScatteredLava} used to protect them with a radius, {@code coreCraterR}, and
         * a radius is the wrong shape for two of the four summit styles. A caldera's lake is a
         * crescent reaching {@code craterR * 0.85} while its keep radius was {@code craterR / 3}, and
         * a fissure's ponds are strung along a line while its keep radius was 2 - so after the first
         * eruption most of the lava a volcano was built with had been turned to basalt and never
         * refilled. That is the "hardly any lava in the crater" report. Listing the actual cells
         * makes the protection exactly the shape of the thing it is protecting.</p>
         */
        final List<BlockPos> molten = new ArrayList<>();
    }

    private static Ctx layout(ServerLevel level, BlockPos base, int magnitude, VolcanoType type) {
        Ctx c = new Ctx();
        c.type = type;
        c.magnitude = magnitude;
        c.x = base.getX();
        c.z = base.getZ();
        c.baseY = base.getY();

        c.craterR = Math.max(2, (int) Math.round(
                (GeyserConfig.VOLCANO_CRATER_RADIUS.get() + magnitude / 3.0 + 1)
                        * type.craterScale() * (0.85 + level.random.nextDouble() * 0.3)));
        c.coneHeight = type.coneHeight(magnitude, level.random);
        c.summitY = c.baseY + c.coneHeight;
        if (c.summitY >= level.getMaxBuildHeight() - 12) return null;

        c.coneBaseR = c.coneHeight > 0
                ? (int) Math.round(c.craterR + c.coneHeight * type.coneSlope())
                : c.craterR;
        if (type == VolcanoType.FISSURE) {
            // A fissure has no cone, but it is not a point either: the swarm runs for tens of blocks
            // along the strike and floods the ground around it, so the footprint is the LINE.
            c.coneBaseR = 6 + magnitude;
        }
        c.apronReach = c.coneBaseR + (int) Math.round(c.coneBaseR * type.apronReach()) + 6;
        // Everything the volcano will lay rock on, not just the edifice. The clearing used to stop
        // at the cone while the apron ran a third further out, so the outer band of debris was
        // spread UNDER a standing forest - buildApronRow only calls clearVegetation, which by
        // design leaves logs and leaves alone. That is the forest growing out of the basalt in the
        // test shots. The fringe of snags in clearSiteRow scales with the radius, so widening it
        // frays the edge further rather than mowing a bigger circle.
        c.clearReach = Math.max(Math.max(c.coneBaseR, c.craterR), c.apronReach) + 6;

        // The site check scales with what we are actually about to occupy.
        if (!siteIsSuitable(level, c)) return null;

        c.reservoirR = GeyserConfig.VOLCANO_RESERVOIR_RADIUS.get()
                + level.random.nextInt(1 + magnitude / 3);
        c.reservoirY = Math.max(level.getMinBuildHeight() + 5,
                c.baseY - 45 - level.random.nextInt(25));
        if (c.baseY - c.reservoirY < 12) return null;

        c.phaseA = level.random.nextDouble() * Math.PI * 2;
        c.phaseB = level.random.nextDouble() * Math.PI * 2;
        c.phaseC = level.random.nextDouble() * Math.PI * 2;
        c.bandSeed = level.random.nextInt(64);

        c.calderaFloorY = c.baseY - (3 + level.random.nextInt(5));
        c.domeR = Math.max(3, c.craterR / 3);
        c.domeH = 3 + level.random.nextInt(4);
        c.lakeAngle = level.random.nextDouble() * Math.PI * 2;
        c.lakeWidth = Math.PI * (0.45 + level.random.nextDouble() * 0.35);

        PlateSample s = TectonicMap.sample(level, c.x, c.z);
        if (s.onFault()) {
            double len = Math.hypot(s.faultStrikeX(), s.faultStrikeZ());
            if (len > 1.0e-6) {
                c.strikeX = s.faultStrikeX() / len;
                c.strikeZ = s.faultStrikeZ() / len;
            }
        }

        c.ventCount = Math.max(3, (int) Math.round(
                Mth.clamp(magnitude, 8, 22) * type.ventScale()));
        return c;
    }

    /** Queues one step per row of a square footprint, so no single step is a stall. */
    private static void forEachRow(VolcanoJob job, int reach,
                                   java.util.function.IntFunction<VolcanoJob.Step> rowStep) {
        for (int dx = -reach; dx <= reach; dx++) {
            job.add(rowStep.apply(dx));
        }
    }

    /**
     * Checks the ground can actually carry this volcano.
     *
     * <p>Far more forgiving than it used to be, because the height-field edifice copes with slopes
     * that the old ring builder could not: what it still refuses is ground that would make the
     * volcano wrong rather than merely awkward - a shoreline, a site that is mostly water, or (for a
     * caldera, which has to excavate a flat floor) seriously broken country.</p>
     */
    private static boolean siteIsSuitable(ServerLevel level, Ctx c) {
        int centre = TerrainProbe.groundY(level, c.x, c.z);
        if (centre == Integer.MIN_VALUE) return false;
        if (centre <= level.getSeaLevel() + 2) return false;      // not on a shoreline or seabed
        if (centre <= level.getMinBuildHeight() + 24) return false;

        int radius = Math.max(8, c.coneBaseR);
        int lo = centre, hi = centre, wet = 0, blank = 0, samples = 0;
        int stepSize = Math.max(2, radius / 8);
        for (int dx = -radius; dx <= radius; dx += stepSize) {
            for (int dz = -radius; dz <= radius; dz += stepSize) {
                if (dx * dx + dz * dz > radius * radius) continue;
                samples++;
                int g = TerrainProbe.groundY(level, c.x + dx, c.z + dz);
                if (g == Integer.MIN_VALUE) { blank++; continue; }
                if (TerrainProbe.hasFluidAbove(level, c.x + dx, c.z + dz)) wet++;
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        if (samples == 0) return false;
        // Water is refused hard now. A cone that reaches the shoreline leaves a sheer rampart around
        // it, and a caldera cut below a lake drains it, so a site with any real amount of water in
        // its footprint is simply not a volcano site.
        if (wet * 10 > samples) return false;
        if (blank * 8 > samples) return false;      // riddled with voids: no
        // A caldera has to cut a flat floor, so it wants reasonable ground; the others grow happily
        // out of a hillside now that they fill downward to meet it.
        // A caldera has to cut a flat floor over eighty blocks across, so it wants genuinely even
        // ground - the old tolerance turned a hillside into a quarry. The others grow happily out of
        // a slope now that the cone fills downward to meet it.
        int allowed = c.type.excavates() ? 8 + radius / 6 : 24 + radius;
        return (hi - lo) <= allowed;
    }

    // === The edifice ========================================================

    /**
    /**
     * Height the finished mountain should reach at this point, or {@link Integer#MIN_VALUE} outside
     * it. The outline is warped with two sine lobes so no volcano is a circle, and the surface itself
     * is roughened so the profile does not round into visible contour rings.
     */
    /**
     * Thickness the apron has where it meets the foot of the cone.
     *
     * <p>This single number is what stops the mountain and its skirt being two separate objects.
     * The cone's profile now bottoms out at exactly this height rather than at zero, and the apron
     * starts at exactly this height rather than at its own maximum, so the two meet without a gap
     * or a step - see {@link #coneTargetY}.</p>
     */
    private static double seamHeight(Ctx c) {
        return 1.0 + c.magnitude / 8.0;
    }

    /**
     * Height the finished mountain should reach at this point, or {@link Integer#MIN_VALUE} outside
     * it.
     *
     * <h2>Why the flank stops at the seam height and not at zero</h2>
     * It used to taper to zero at {@code coneRadius}, and that produced the "mountain, then a strip
     * of soil, then a basalt wall" the tests kept finding - for three rounds, because the apron was
     * being blamed and the apron was not the cause. Worked through:
     *
     * <p>The height is rounded to whole blocks, and {@code buildConeRow} skips a column whose target
     * is not above its own ground. So the cone silently stops placing anything once
     * {@code round(X * frac)} falls below 1, i.e. once {@code frac < 0.5 / X}. For a stratocone,
     * X is about 24 and the flank exponent 1.8, which puts that at {@code t = 0.884} - so the cone
     * ends at distance 31.6 while the apron was told to start at 35. A three to four block band of
     * untouched ground, at every bearing, on every stratocone. The shield's is 2.2.
     *
     * <p>And the apron was thickest exactly where it started ({@code t = 1} at its inner edge), so
     * what stood on the far side of that band was a four-block step rising straight out of the
     * grass. Gap plus step reads as a free-standing wall, which is precisely what it was.
     *
     * <p>So the profile is continuous by construction now: the flank falls to
     * {@link #seamHeight} at {@code coneRadius} - never to zero - and the apron picks up from that
     * same value and fades out over its own length. Neither piece can leave a hole for the other to
     * fall into, whatever the bearing or the magnitude.</p>
     */
    private static int coneTargetY(Ctx c, int gx, int gz, int localGround, double dist, double ang) {
        if (c.coneHeight <= 0) return Integer.MIN_VALUE;
        double baseR = coneRadius(c, ang);
        double innerR = c.craterR * (1.0 + 0.10 * Math.sin(2 * ang + c.phaseB));
        if (dist >= baseR) return Integer.MIN_VALUE;
        if (dist <= innerR) return c.summitY;
        double t = (dist - innerR) / Math.max(1.0, baseR - innerR);
        double frac = Math.pow(1.0 - t, c.type.flankExponent());
        // Roughness fades out at the rim so the edge still meets the apron cleanly.
        double rough = surfaceNoise(c, gx, gz) * Math.max(1.0, c.coneHeight * 0.09) * (1.0 - t);
        // Measured from THIS column's own ground, not the summit column's.
        //
        // Returning c.baseY at the outer edge meant every column inside the base radius was filled
        // up to the elevation of whoever ran the command: stand on a hill and you got a plateau at
        // your own feet rather than a volcano. Anchoring the taper to the local ground makes the
        // edifice ADD a decreasing amount of rock.
        double seam = seamHeight(c);
        double span = Math.max(0.0, c.baseY - localGround + c.coneHeight - seam);
        return localGround + (int) Math.round(span * frac + seam + rough);
    }

    /**
     * Radius of the cone's foot at one bearing.
     *
     * <p>Shared with {@link #buildConeRow} and {@link #buildApronRow} on purpose, and for exactly
     * the reason {@link #ringRadius} is shared on a caldera: this is a lobed outline, not a circle,
     * and the two places that needed it had drifted apart. The apron started at the flat
     * {@code coneBaseR} while the cone's own foot swings between {@code coneBaseR * 0.79} and
     * {@code coneBaseR * 1.21}. Wherever the lobe pulled in, a belt of untouched grass was left
     * between the mountain and its own skirt - and since the apron is at its thickest right at its
     * inner edge, what stood beyond that grass was a free-standing wall of basalt. That is the
     * "the outermost basalt ring still reads as a wall" report, and it is the same bug the caldera
     * had before {@code ringRadius} was pulled out.</p>
     */
    private static double coneRadius(Ctx c, double ang) {
        return c.coneBaseR * (1.0 + 0.14 * Math.sin(3 * ang + c.phaseA)
                + 0.07 * Math.sin(5 * ang + c.phaseB));
    }

    /** How far the cone's foot can possibly reach, lobes included. */
    private static int coneReach(Ctx c) {
        return (int) Math.ceil(c.coneBaseR * 1.21) + 2;
    }

    /**
     * A little roughness for the height field.
     *
     * <p>The profile is perfectly smooth, but rounding a smooth profile to whole blocks turns every
     * contour into a visible ring - which is why a big shield read as a stack of terraces rather than
     * as a hill. Breaking the height by a block or so <em>before</em> it is rounded scatters those
     * rings into something that looks like rock.</p>
     */
    private static double surfaceNoise(Ctx c, int gx, int gz) {
        return Math.sin(gx * 0.19 + c.phaseA) * Math.cos(gz * 0.23 + c.phaseB)
                + 0.5 * Math.sin((gx + gz) * 0.11 + c.phaseC)
                + 0.35 * Math.sin((gx - gz) * 0.31 + c.phaseA);
    }

    private static void buildConeRow(ServerLevel level, Ctx c, int dx) {
        // The lobed foot can swing a fifth further out than coneBaseR, and the old reach clipped
        // it off flat wherever it did.
        int reach = coneReach(c);
        for (int dz = -reach; dz <= reach; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            double ang = Math.atan2(dz, dx);
            int gx = c.x + dx, gz = c.z + dz;

            int ground = TerrainProbe.groundY(level, gx, gz);
            if (ground == Integer.MIN_VALUE) continue;
            // Stop at the water's edge instead of walling a lake in. A cone that marches into open
            // water leaves a sheer black rampart around the shoreline, which is what the shield
            // built beside a lake looked like.
            if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) continue;

            int target = coneTargetY(c, gx, gz, ground, dist, ang);
            if (target == Integer.MIN_VALUE) continue;
            // The land here is already higher than the mountain would be: leave it alone. That is
            // both cheaper and what makes a volcano growing out of an existing range look right.
            if (ground >= target) continue;

            TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
            for (int y = ground + 1; y <= target; y++) {
                setRock(level, new BlockPos(gx, y, gz), coneRock(level, c, y));
            }
        }
    }

    /**
     * The rock the edifice is made of.
     *
     * <p>A stratocone is <em>interbedded</em>: alternating ash falls and lava flows, which is why a
     * road cut through one is stripey. Banding by height rather than picking at random per block is
     * what makes that legible when you dig in. A shield is nearly all pahoehoe basalt, and a caldera
     * is largely welded tuff - its own ignimbrite.</p>
     */
    private static BlockState coneRock(ServerLevel level, Ctx c, int y) {
        return switch (c.type) {
            case STRATOVOLCANO -> switch (Math.floorMod((y + c.bandSeed) / 3, 4)) {
                case 0 -> Blocks.TUFF.defaultBlockState();
                case 2 -> Blocks.BLACKSTONE.defaultBlockState();
                default -> (level.random.nextInt(5) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                        .defaultBlockState();
            };
            case SHIELD -> (level.random.nextInt(3) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
            case CALDERA -> (level.random.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.TUFF)
                    .defaultBlockState();
            case FISSURE -> (level.random.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
        };
    }

    /**
     * A caldera is a <b>collapse</b> structure: the chamber empties, the roof drops in, and what is
     * left is a hole ringed by a fault scarp with a resurgent dome pushing back up through the floor.
     * Building it as a low wide cone, which is what happened before, produced a flat basalt disc
     * sitting on the landscape and nothing that read as Yellowstone at all.
     */
    /**
     * Radius of the ring fault at one bearing.
     *
     * <p>Shared with {@link #buildApronRow} and {@link #calderaRingReach} on purpose. It used to be
     * written out separately in each place, and they drifted: the carve loop ran to
     * {@code craterR + 7} while this can reach {@code craterR * 1.34}, so wherever the ring bulged
     * the scarp was simply cut off, and the apron started at a fixed radius that left bare ground
     * wherever the ring was narrow. That is the "caldera, then a strip of grass, then a basalt wall"
     * report.</p>
     */
    private static double ringRadius(Ctx c, double ang) {
        return c.craterR * (1.0 + 0.22 * Math.sin(2 * ang + c.phaseA)
                + 0.12 * Math.sin(3 * ang + c.phaseB));
    }

    /** How far out the ring fault can possibly reach, scarp included. */
    private static int calderaRingReach(Ctx c) {
        return (int) Math.ceil(c.craterR * 1.34) + 7;
    }

    private static void carveCalderaRow(ServerLevel level, Ctx c, int dx) {
        int reach = calderaRingReach(c);
        for (int dz = -reach; dz <= reach; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            double ang = Math.atan2(dz, dx);
            double rr = ringRadius(c, ang);
            int gx = c.x + dx, gz = c.z + dz;
            int ground = TerrainProbe.groundY(level, gx, gz);
            if (ground == Integer.MIN_VALUE) continue;
            // Never cut below open water: the caldera floor would simply drain the lake into itself
            // and leave a black bowl where the shoreline used to be.
            if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) continue;
            // Roughness, so the floor does not read as a perfect contour. Clamped to never go BELOW
            // the base floor level, because the lava lake sits one block under it and a floor cell
            // lower than the lake would give it somewhere to run.
            int rough = Math.max(0, (int) Math.round(surfaceNoise(c, gx, gz) * 1.2));

            if (dist <= rr) {
                boolean lake = inLakeSector(c, dist, ang);
                // The lake sits at exactly the base level, no roughness and no dome, so every cell
                // of it is at the same height.
                int target = lake ? c.calderaFloorY : c.calderaFloorY + rough;
                if (!lake && dist < c.domeR) {
                    target += (int) Math.round(c.domeH * (1.0 - dist / Math.max(1.0, c.domeR)));
                }
                TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
                for (int y = target + 1; y <= ground + 2; y++) {
                    clearNatural(level, new BlockPos(gx, y, gz));
                }
                for (int y = Math.min(ground, target); y <= target; y++) {
                    setRock(level, new BlockPos(gx, y, gz), coneRock(level, c, y));
                }
                if (lake) {
                    // Recessed by one block, exactly like the flank vents: the lake is the lowest
                    // point of its own basin and so has nowhere to flow.
                    BlockPos molten = new BlockPos(gx, target - 1, gz);
                    setRock(level, molten, Blocks.LAVA.defaultBlockState());
                    clearNatural(level, new BlockPos(gx, target, gz));
                    c.molten.add(molten);   // this cell is MEANT to stay lava; see Ctx.molten
                } else if (level.random.nextInt(6) == 0) {
                    setRock(level, new BlockPos(gx, target, gz), Blocks.TUFF.defaultBlockState());
                }
            } else if (dist <= rr + 6) {
                // The ring fault scarp.
                //
                // A perfect circle of equal height reads as a palisade, which is not what a ring
                // fault looks like: a real caldera rim varies along its length and is cut through by
                // low saddles - Crater Lake's rim has several. So the height is modulated round the
                // circle and simply stops where the modulation bottoms out, leaving breaches.
                double gate = 0.5 + 0.5 * Math.sin(3 * ang + c.phaseC)
                        + 0.25 * Math.sin(5 * ang + c.phaseA);
                if (gate < 0.22) continue;                     // a breach in the ring
                double t = 1.0 - (dist - rr) / 6.0;
                int lift = (int) Math.round((3 + c.magnitude / 5.0) * t
                        * (0.3 + 0.7 * Mth.clamp(gate, 0.0, 1.0))) + rough;
                if (lift < 1) continue;
                TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
                for (int h = 1; h <= lift; h++) {
                    setRock(level, new BlockPos(gx, ground + h, gz), coneRock(level, c, ground + h));
                }
            }
        }
    }

    /** True inside the crescent of the caldera floor that holds the lava lake. */
    private static boolean inLakeSector(Ctx c, double dist, double ang) {
        double rel = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(ang - c.lakeAngle)));
        return Math.abs(rel) <= c.lakeWidth * 0.5
                && dist > c.domeR + 1 && dist < c.craterR * 0.85;
    }

    /**

    /**
     * Skirts whatever we built with its own debris, thinning to nothing at the edge.
     *
     * <p>Without this the edifice meets the landscape at a hard step and reads as an object dropped
     * on the map. The outer third is deliberately <b>speckled</b> rather than solid, so volcanic rock
     * and native ground interleave the way a real ash fall thins out, instead of ending on a line.</p>
     */
    private static void buildApronRow(ServerLevel level, Ctx c, int dx) {
        int reach = c.apronReach;
        // Where the apron starts depends on what it is skirting. A caldera's begins outside its ring
        // scarp, or it would bury the very rim you stand on to look in. A fissure's begins at the
        // crack itself: what a rift erupts is a flood-basalt FIELD spreading out from the line, so an
        // annulus with bare ground in the middle would have been exactly backwards. The ponds are cut
        // into this field afterwards.
        // The smallest the inner edge can be, used only to bail out early; a caldera recomputes it
        // per column below, because its ring fault is not a circle.
        // The smallest the inner edge can be for THIS type, used only to bail out early; both a
        // caldera and a cone recompute it per column below, because neither outline is a circle.
        int inner = switch (c.type) {
            case CALDERA -> (int) Math.round(c.craterR * 0.66) + 6;
            case FISSURE -> 0;
            default -> (int) Math.floor(c.coneBaseR * 0.79);
        };
        if (inner >= reach) return;
        for (int dz = -reach; dz <= reach; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > reach) continue;
            double ang = Math.atan2(dz, dx);
            // The apron starts where the edifice actually ENDS at this bearing, not at a fixed
            // radius. A fixed one left a band of untouched grass wherever the outline came in
            // narrow, which read as a moat between the volcano and its own skirt - and with the
            // apron at its thickest right at its inner edge, as a wall standing on that grass.
            double localInner = switch (c.type) {
                case CALDERA -> ringRadius(c, ang) + 6;
                case FISSURE -> inner;
                default -> coneRadius(c, ang);
            };
            if (dist <= localInner) continue;
            double edge = reach * (0.84 + 0.16 * Math.sin(3 * ang + c.phaseC));
            if (dist > edge || localInner >= edge) continue;

            double t = 1.0 - (dist - localInner) / Math.max(1.0, edge - localInner);
            // Speckling: certain near the cone, sparse at the rim. This is what dissolves the hard
            // boundary the player was seeing between basalt and native terrain.
            if (level.random.nextDouble() > Mth.clamp(t * 1.7, 0.0, 1.0)) continue;

            int gx = c.x + dx, gz = c.z + dz;
            int ground = TerrainProbe.groundY(level, gx, gz);
            if (ground == Integer.MIN_VALUE) continue;
            // Checked directly rather than through hasFluidAbove, which would walk the column down
            // from the heightmap a second time. Over twenty thousand apron columns that doubling is
            // most of the build cost.
            if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) continue;

            // Picks up at exactly the height the flank came down to, and fades from there.
            //
            // It used to be `round(t * (1 + magnitude/8))`, which puts the apron at its THICKEST
            // right where it starts - a four-block step rising straight out of open ground on a
            // magnitude 12 volcano. That step, plus the band the cone was leaving short of here
            // (see coneTargetY), is the free-standing wall the tests kept reporting. Now the two
            // meet at the same height and the swell dies away over the apron's own length. The 1.5
            // power keeps it close to the mountain rather than laying an even shelf, which is also
            // how a real debris apron thins.
            double u = 1.0 - t;                       // 0 at the seam, 1 at the outer edge
            int thickness = (int) Math.round(seamHeight(c) * Math.pow(1.0 - u, 1.5));
            TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
            BlockState native0 = level.getBlockState(new BlockPos(gx, ground, gz));
            for (int h = 0; h <= Math.max(0, thickness); h++) {
                // The blend-into-the-ground pass-through only makes sense for the surface cell.
                // Handing it back at h > 0 would stack a copy of the local ground in the air - a
                // grass block floating over the apron.
                setRock(level, new BlockPos(gx, ground + h, gz),
                        apronRock(level, t, h == 0 ? native0 : null));
            }
        }
    }

    /**
     * Coarse tephra and ash at the far edge, solid lava rock closer in.
     *
     * <h2>Mixed, not banded</h2>
     * This used to switch material on two hard thresholds - gravel and tuff below 0.35, tuff and
     * basalt below 0.7, basalt above. Three discrete zones drawn on a smooth radial gradient are
     * three visible contour rings, and the brown one at the outside is what read as a second,
     * separate ring around the mountain in testing.
     *
     * <p>So the mix is continuous instead: the odds of each material slide across the whole apron
     * and the three overlap everywhere, which is how a real ash fall grades - coarse near the vent,
     * finer outward, never a line. At the very edge the local ground is part of the mix too, so the
     * apron finishes by dissolving into the countryside rather than by changing colour.</p>
     *
     * @param t 1 at the apron's inner edge, 0 at its outer edge
     * @param native0 the block already at the surface here, so the far edge can blend into it, or
     *                null when the caller is filling a cell above the surface and must not be
     *                handed a copy of the ground
     */
    private static BlockState apronRock(ServerLevel level, double t, BlockState native0) {
        double r = level.random.nextDouble();

        // Outermost cells sometimes stay as they are. Ramps in below t = 0.3 and reaches roughly a
        // third of cells at the very edge - enough to fray the boundary, not enough to leave holes.
        if (native0 != null && t < 0.3 && r > t / 0.3 * 0.7 + 0.3) return native0;

        // Basalt dominates near the cone, tephra at the rim; both are present throughout.
        double basalt = Mth.clamp(t * 1.15, 0.0, 1.0);
        if (level.random.nextDouble() < basalt) {
            return (level.random.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
        }
        return (level.random.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
    }
    /**
     * Strips trees and ground cover from the footprint so the mountain never grows through them.
     *
     * <h2>Why the edge frays</h2>
     * Clearing a clean disc left every volcano standing in a circle of mown lawn with the forest
     * resuming at a perfect radius, and nothing says "pasted in" louder than that. So the edifice
     * takes its ground outright and beyond it the clearing fades, with more and more trees left
     * alone until the living forest takes over.
     *
     * <p>The fringe used to be made of <b>stripped trunks</b> - leaves removed, trunk left standing,
     * the idea being dead timber killed by ash. In game that reads as a bug rather than as a dead
     * forest: bare logs in a row look like something half-finished. So a spared tree is now spared
     * <em>whole</em>. The probability curve is unchanged, so the edge still thins outward at the
     * same rate; what changes is that every tree is either entirely there or entirely gone.</p>
     */
    private static void clearSiteRow(ServerLevel level, Ctx c, int dx) {
        int radius = c.clearReach;
        double solid = Math.max(c.coneBaseR, c.craterR) * 0.9;
        for (int dz = -radius; dz <= radius; dz++) {
            double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
            if (dist > radius) continue;
            int g = TerrainProbe.groundY(level, c.x + dx, c.z + dz);
            if (g == Integer.MIN_VALUE) continue;

            // The further out past the edifice, the likelier a tree is left standing - whole.
            //
            // Decided from the surface noise rather than from a per-column dice roll, and that is
            // not a detail: a tree covers a dozen columns, so rolling per column would clear some
            // of them and spare others, and the result is half a canopy or a bare trunk - which is
            // the thing this change exists to stop. The noise field varies over fifteen to thirty
            // blocks, so a whole tree falls on one side of the threshold or the other, and the
            // boundary comes out as an organic edge instead of a grid.
            double out = Mth.clamp((dist - solid) / Math.max(1.0, radius - solid), 0.0, 1.0);
            double spare = (surfaceNoise(c, c.x + dx, c.z + dz) + 1.85) / 3.7;
            if (dist > solid && spare < out) continue;

            // Stop at the top of whatever actually stands in this column rather than always
            // walking a fixed 24 blocks of air. The footprint now covers the apron too, so most
            // of these columns are open ground - one lookup each instead of two dozen.
            int top = Math.min(g + 24,
                    level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                            c.x + dx, c.z + dz));
            for (int y = g + 1; y <= top; y++) {
                BlockPos p = new BlockPos(c.x + dx, y, c.z + dz);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                boolean tree = s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
                if (!tree && !TerrainProbe.isVegetation(s)) break;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    // === Summits ============================================================

    private static void buildSummit(ServerLevel level, Ctx c) {
        switch (c.type.summitStyle()) {
            case FUNNEL_PIT -> carveFunnelPit(level, c);
            case LAVA_LAKE -> carveLavaLake(level, c);
            case COLLAPSE_FLOOR -> seatCalderaVent(level, c);
            case FISSURE_PONDS -> carveFissureLine(level, c);
        }
        if (c.vent == null) {
            // Nothing seated (a fissure that found no workable ground, say): fall back to the axis
            // so the volcano still has a working core rather than being left half-built.
            int g = TerrainProbe.groundY(level, c.x, c.z);
            c.vent = new BlockPos(c.x, g == Integer.MIN_VALUE ? c.summitY : g, c.z);
        }
    }

    /**
     * A stratovolcano's crater: a funnel that steps inward as it goes down to a small lava lake, with
     * smouldering magma on the walls. Standing on the rim you look down into it, which is the whole
     * experience of a Fuji or a Vesuvius - not a lake at your feet.
     *
     * <h2>The lake has to be visible</h2>
     * This used to end in a single lava block at the bottom of a funnel up to ten blocks deep, and
     * one block down a throat that tapered to a point is invisible from the rim. That is precisely
     * the "some volcanoes have no lava in the middle" report. Real stratocone craters do hold a small
     * lake - Villarrica, Erebus, Nyiragongo - and the entire point of one is that you can lean over
     * the edge and see it glowing down there. So the throat now bottoms out on a real floor instead
     * of narrowing to nothing, and the funnel is shallower so the floor is in view from the rim.
     */
    private static void carveFunnelPit(ServerLevel level, Ctx c) {
        // 0.55, not 0.35: at a 6-block crater the old fraction gave a pool of radius 2 - thirteen
        // cells, which reads from above as a few scattered lava blocks rather than a lava lake.
        int poolR = Math.max(2, (int) Math.round(c.craterR * 0.55));
        int depth = Mth.clamp(c.craterR + 1, 3, 7);
        int floorY = c.summitY - depth;
        for (int d = 0; d <= depth; d++) {
            int y = c.summitY - d;
            // Never narrower than the lake it has to hold: a funnel with a floor, not a spike.
            double r = Math.max(poolR + 1.0, c.craterR * (1.0 - d / (double) (depth + 1)));
            int reach = (int) Math.ceil(r) + 1;
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    double ang = Math.atan2(dz, dx);
                    double rr = r * (1.0 + 0.18 * Math.sin(3 * ang + c.phaseA));
                    if (dist > rr) continue;
                    BlockPos p = new BlockPos(c.x + dx, y, c.z + dz);
                    if (dist > rr - 1.3) {
                        // The wall of the funnel, still hot in places.
                        setRock(level, p, (level.random.nextInt(6) == 0
                                ? Blocks.MAGMA_BLOCK : Blocks.BLACKSTONE).defaultBlockState());
                    } else {
                        clearNatural(level, p);
                    }
                }
            }
        }
        // The lake itself, seated on its own basalt floor with a crust of cooling magma at the shore.
        for (int dx = -poolR - 1; dx <= poolR + 1; dx++) {
            for (int dz = -poolR - 1; dz <= poolR + 1; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > poolR + 1) continue;
                BlockPos p = new BlockPos(c.x + dx, floorY, c.z + dz);
                setRock(level, p.below(), Blocks.BASALT.defaultBlockState());
                if (dist <= poolR) {
                    setRock(level, p, Blocks.LAVA.defaultBlockState());
                    clearNatural(level, p.above());
                    c.molten.add(p);
                } else {
                    setRock(level, p, (level.random.nextInt(3) == 0
                            ? Blocks.MAGMA_BLOCK : Blocks.BLACKSTONE).defaultBlockState());
                }
            }
        }
        c.vent = new BlockPos(c.x, floorY, c.z);
        c.coreCraterR = poolR;
    }

    /**
     * A shield's summit: a broad, shallow, ragged lava lake sitting one block below its own rim, so
     * it is physically incapable of spilling down the flanks.
     */
    private static void carveLavaLake(ServerLevel level, Ctx c) {
        int lakeY = c.summitY - 1;
        int reach = c.craterR + 3;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                double ang = Math.atan2(dz, dx);
                double rr = c.craterR * (1.0 + 0.38 * Math.sin(2 * ang + c.phaseA)
                        + 0.20 * Math.sin(5 * ang + c.phaseC));
                if (dist > rr) continue;
                BlockPos surf = new BlockPos(c.x + dx, lakeY, c.z + dz);
                setRock(level, surf.below(), Blocks.BASALT.defaultBlockState());
                if (dist > rr - 1.4) {
                    // Low rim, one block proud of the lake.
                    setRock(level, surf, Blocks.BASALT.defaultBlockState());
                    setRock(level, surf.above(), Blocks.BASALT.defaultBlockState());
                } else {
                    setRock(level, surf, Blocks.LAVA.defaultBlockState());
                    clearNatural(level, surf.above());
                    c.molten.add(surf);
                }
            }
        }
        c.vent = new BlockPos(c.x, lakeY, c.z);
        c.coreCraterR = Math.max(2, c.craterR);
    }

    /** Finds the caldera's crescent lake and seats the core under it. */
    private static void seatCalderaVent(ServerLevel level, Ctx c) {
        double r = c.craterR * 0.6;
        int vx = c.x + (int) Math.round(Math.cos(c.lakeAngle) * r);
        int vz = c.z + (int) Math.round(Math.sin(c.lakeAngle) * r);
        // One block below the floor, matching the recessed lake carveCalderaRow lays down, so the
        // core sits under lava rather than under the rim of it.
        BlockPos p = new BlockPos(vx, c.calderaFloorY - 1, vz);
        setRock(level, p.below(), Blocks.BASALT.defaultBlockState());
        setRock(level, p, Blocks.LAVA.defaultBlockState());
        clearNatural(level, p.above());
        c.vent = p;
        // Only the lake area stays molten between eruptions; the rest of the floor cools.
        c.coreCraterR = Math.max(2, c.craterR / 3);
    }

    /**
     * A rift volcano builds no cone: the crust parts and lava wells out along the crack. This lays a
     * line of ponds along the local fault strike, offset in <b>en-echelon</b> segments the way a real
     * fissure swarm steps sideways rather than running dead straight.
     */
    private static void carveFissureLine(ServerLevel level, Ctx c) {
        int half = 6 + c.magnitude;
        int segLen = 6 + level.random.nextInt(5);
        for (int t = -half; t <= half; t++) {
            int seg = Math.floorDiv(t + half, segLen);
            double lateral = ((seg % 2 == 0) ? 1 : -1) * (1 + seg % 3);
            int px = c.x + (int) Math.round(c.strikeX * t - c.strikeZ * lateral);
            int pz = c.z + (int) Math.round(c.strikeZ * t + c.strikeX * lateral);
            BlockPos pond = seatPondCell(level, px, pz);
            if (pond == null) continue;
            if (c.vent == null) c.vent = pond;
            // EVERY pond is recorded, not just the first.
            //
            // Only the first used to be, which meant the rest were not in the vent list either - so
            // the final safety sweep saw them as stray exposed lava and walled them in, while the
            // core went on firing its eruption particles out of ground that now looked solid. That
            // is exactly the "the lava pool has vanished but the eruption still comes from there"
            // report. Recorded here they are both protected from the sweep and given to the core, so
            // the whole fissure swarm smokes and seeps together the way a real one does.
            c.vents.add(pond);
            // And recorded as MOLTEN too, which this never did.
            //
            // Ctx.molten's own javadoc promises "every pond of a fissure swarm", and this was the
            // one shape that put nothing in it - so a fissure volcano was born with an empty molten
            // list, and the post-quake recharge that reads that list restored precisely nothing.
            // The log said it plainly and nobody had looked: "vents: 35 cut of 6 sites, 0 molten
            // cells". These ponds ARE the volcano's lava; they belong here.
            c.molten.add(pond);
        }
        c.coreCraterR = 2;
    }

    /**
     * Seats one fissure pond INTO the ground, with a spatter rampart around it.
     *
     * <h2>The diagonal wall of floating basalt</h2>
     * The old version put the rampart at <b>this</b> cell's ground height on all four neighbours
     * without ever asking what height those neighbours actually were. Across broken country - a
     * badlands edge, say - the neighbour could be twenty blocks lower, so the collar hung in mid-air;
     * strung out along a fault that produced exactly the diagonal basalt wall in the screenshot.
     * Every neighbour is now read individually, and a cell whose surroundings are not level enough to
     * hold a pond is skipped outright rather than built badly.
     *
     * @return the lava cell, or null if this spot could not hold one
     */
    private static BlockPos seatPondCell(ServerLevel level, int px, int pz) {
        int ground = TerrainProbe.groundY(level, px, pz);
        if (ground == Integer.MIN_VALUE) return null;
        if (ground <= level.getSeaLevel() + 1) return null;
        if (TerrainProbe.hasFluidAbove(level, px, pz)) return null;
        BlockPos lava = new BlockPos(px, ground, pz);
        if (level.getBlockState(lava).is(Blocks.BEDROCK)) return null;
        if (EruptionHandler.isPlayerPlaced(level.getBlockState(lava))) return null;

        // Every neighbour must be at essentially the same level, and is measured on its own.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = px + d.getStepX(), nz = pz + d.getStepZ();
            int ng = TerrainProbe.groundY(level, nx, nz);
            if (ng == Integer.MIN_VALUE || Math.abs(ng - ground) > 1) return null;
        }

        TerrainProbe.clearVegetation(level, px, ground, pz, 3);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = px + d.getStepX(), nz = pz + d.getStepZ();
            int ng = TerrainProbe.groundY(level, nx, nz);
            // The rampart is built up from the NEIGHBOUR's own ground, so it can never float.
            for (int h = 0; h <= 1; h++) {
                setRock(level, new BlockPos(nx, ng + h, nz), Blocks.BASALT.defaultBlockState());
            }
        }
        setRock(level, lava.below(), Blocks.BASALT.defaultBlockState());
        setRock(level, lava, Blocks.LAVA.defaultBlockState());
        clearNatural(level, lava.above());
        return lava;
    }

    // === Plumbing ===========================================================

    private static void plantCore(ServerLevel level, Ctx c) {
        BlockPos corePos = c.vent.below();
        level.setBlock(corePos, ModBlocks.VOLCANO_CORE.get().defaultBlockState(), 2);
        if (level.getBlockEntity(corePos) instanceof VolcanoCoreBlockEntity core) {
            core.setMagnitude(c.magnitude);
            core.setCraterRadius(c.coreCraterR);
            core.setMoltenCells(c.molten);
        }
        setRock(level, c.vent, Blocks.LAVA.defaultBlockState());
    }

    private static void carveConduit(ServerLevel level, Ctx c) {
        int topY = c.vent.getY() - 2;
        for (int y = c.reservoirY + 3; y <= topY; y++) {
            BlockPos p = new BlockPos(c.x, y, c.z);
            if (level.getBlockState(p).is(Blocks.BEDROCK)) continue;
            level.setBlock(p, Blocks.LAVA.defaultBlockState(), 2);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = p.relative(d);
                BlockState ws = level.getBlockState(w);
                if (!ws.is(Blocks.BEDROCK)
                        && (ws.isAir() || (!ws.getFluidState().isEmpty() && !ws.getFluidState().is(FluidTags.LAVA)))) {
                    level.setBlock(w, Blocks.BASALT.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void fillLavaDisc(ServerLevel level, int cx, int cy, int cz, int r, int thickness) {
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                int d2 = dx * dx + dz * dz;
                boolean inside = d2 <= r * r;
                boolean wall = !inside && d2 <= (r + 1) * (r + 1);
                if (!inside && !wall) continue;
                for (int dy = -1; dy <= thickness; dy++) {
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (level.getBlockState(p).is(Blocks.BEDROCK)) continue;
                    boolean shell = (dy == -1 || dy == thickness || wall);
                    level.setBlock(p, (shell ? Blocks.BASALT : Blocks.LAVA).defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * Grows the root-like lava veins inside the mountain. Every step checks the local ground height
     * so a vein can never wander out of a hillside and pour lava downhill, which is what used to set
     * the countryside alight.
     */
    private static void growLavaBranches(ServerLevel level, Ctx c) {
        int fromY = c.reservoirY + 4;
        int toY = c.vent.getY() - 2;
        if (toY <= fromY) return;
        int branches = Mth.clamp(c.magnitude / 2, 3, 10);
        int span = Math.max(1, toY - fromY);
        for (int i = 0; i < branches; i++) {
            BlockPos p = new BlockPos(c.x, fromY + level.random.nextInt(span), c.z);
            int len = 8 + level.random.nextInt(14);
            for (int s = 0; s < len; s++) {
                int ddx = level.random.nextInt(3) - 1;
                int ddz = level.random.nextInt(3) - 1;
                int ddy = level.random.nextInt(100) < 55 ? 1 : 0;
                if (ddx == 0 && ddz == 0 && ddy == 0) ddy = 1;
                p = p.offset(ddx, ddy, ddz);
                if (Math.abs(p.getX() - c.x) > 16 || Math.abs(p.getZ() - c.z) > 16) break;
                if (p.getY() >= toY || p.getY() <= fromY - 3) break;
                if (level.getBlockState(p).is(Blocks.BEDROCK)) break;
                int ground = TerrainProbe.groundY(level, p.getX(), p.getZ());
                if (ground == Integer.MIN_VALUE || p.getY() > ground - LAVA_SURFACE_CLEARANCE) break;
                level.setBlock(p, Blocks.LAVA.defaultBlockState(), 2);
            }
        }
    }

    // === Flank vents ========================================================

    /**
     * Picks where the flank outlets go.
     *
     * <h2>Spacing, not luck</h2>
     * Sites used to be drawn uniformly at random from a square, which clusters: half a dozen vents
     * would end up within a few blocks of each other. Candidates are now rejected unless they clear
     * every accepted vent by {@code minSpacing}, and the candidate distribution itself follows the
     * type - radial and far out for a shield's lava tubes, high on the flanks for a stratocone, along
     * the strike for a fissure swarm, and around the ring fault of a caldera.
     */
    private static void chooseVents(ServerLevel level, Ctx c) {
        int minSpacing = 8 + c.magnitude / 2;
        int min2 = minSpacing * minSpacing;
        int attempts = c.ventCount * 25;
        for (int a = 0; a < attempts && c.ventSites.size() < c.ventCount; a++) {
            int[] p = ventCandidate(level, c);
            boolean clear = true;
            for (BlockPos v : c.ventSites) {
                int dx = v.getX() - p[0], dz = v.getZ() - p[1];
                if (dx * dx + dz * dz < min2) { clear = false; break; }
            }
            if (clear) c.ventSites.add(new BlockPos(p[0], 0, p[1]));
        }
    }

    private static int[] ventCandidate(ServerLevel level, Ctx c) {
        double ang = level.random.nextDouble() * Math.PI * 2;
        double outer = Math.max(12, c.coneBaseR);
        double dist;
        switch (c.type.ventPattern()) {
            case UPPER_FLANK -> dist = c.craterR + 3 + level.random.nextDouble() * outer * 0.7;
            case RADIAL_FAR -> dist = outer * (0.5 + level.random.nextDouble() * 1.3);
            case RING_FAULT -> dist = c.craterR * (0.9 + level.random.nextDouble() * 0.5);
            case ALONG_STRIKE -> {
                // Strung out along the crack, with only a little scatter across it.
                double along = (level.random.nextDouble() * 2 - 1) * (18 + c.magnitude * 2.5);
                double across = (level.random.nextDouble() * 2 - 1) * 5;
                return new int[] {
                        c.x + (int) Math.round(c.strikeX * along - c.strikeZ * across),
                        c.z + (int) Math.round(c.strikeZ * along + c.strikeX * across) };
            }
            default -> dist = outer;
        }
        return new int[] {
                c.x + (int) Math.round(Math.cos(ang) * dist),
                c.z + (int) Math.round(Math.sin(ang) * dist) };
    }

    private static void cutVent(ServerLevel level, Ctx c, int index) {
        if (index >= c.ventSites.size()) return;
        BlockPos site = c.ventSites.get(index);
        BlockPos outlet = carveSeatedOutlet(level, site.getX(), site.getZ());
        if (outlet == null) return;
        connectVentDown(level, outlet.below(), c.x, c.z, c.reservoirY + 2);
        c.vents.add(outlet);
    }

    private static void recordVents(ServerLevel level, Ctx c) {
        if (c.vent == null) return;
        if (level.getBlockEntity(c.vent.below()) instanceof VolcanoCoreBlockEntity core) {
            core.setSurfaceVents(c.vents);
        }
        // Said out loud, because "it feels like the vents do nothing" is not something you can act
        // on. carveSeatedOutlet returns null silently when a site will not do, so a mountain with
        // no working outlets looked exactly like a mountain whose particles were broken. Now the
        // ratio is in the log and the two can be told apart.
        GeysersMod.LOGGER.info("volcano {} vents: {} cut of {} sites, {} molten cells",
                c.type, c.vents.size(), c.ventSites.size(), c.molten.size());
    }

    /**
     * Seats a lava outlet INTO the hillside instead of dropping it on top. The lava ends up recessed
     * below the rock around it with a basalt collar, so containment comes from the shape of the
     * ground rather than from a fence built afterwards.
     *
     * <h2>It cuts its own bench</h2>
     * This used to <em>demand</em> level ground - {@code findLevelSite(.., radius 2, tolerance 1)},
     * which with its guard ring means a 7x7 patch flat to within one block. A volcano flank is a
     * slope by definition, and on a stratocone it is close to 1:1, so a seven-block span drops six.
     * The test therefore almost never passed, {@code c.vents} came back empty, and since both
     * {@code seepVent} and the idle smoke are guarded on {@code surfaceVents.length > 0} the
     * mountain had no working outlets at all: no lava on the flanks, and nothing to make the
     * particles the eruption was supposed to show.
     *
     * <p>So it levels the patch itself now, down to the lowest ground in it. Taking the LOW point
     * rather than an average is what keeps the old guarantee intact - the pool still sits below
     * everything around it and still has nowhere to run. A real spatter vent builds itself the same
     * small platform.</p>
     *
     * @return the lava cell, or null if this spot was genuinely unusable
     */
    private static BlockPos carveSeatedOutlet(ServerLevel level, int vx, int vz) {
        // The lowest real ground in the 5x5 the outlet will occupy. Anything that is not ground at
        // all - a cliff edge, open air - still disqualifies the site.
        int g = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int h = TerrainProbe.groundY(level, vx + dx, vz + dz);
                if (h == Integer.MIN_VALUE) return null;
                if (TerrainProbe.hasFluidAbove(level, vx + dx, vz + dz)) return null;  // lake or sea
                g = Math.min(g, h);
                hi = Math.max(hi, h);
            }
        }
        if (g == Integer.MAX_VALUE) return null;
        // Willing to cut a bench, not to gouge a cliff. Six blocks of relief across five is a steep
        // flank and still fine; past that the notch would read as a bite taken out of the mountain,
        // and there are plenty of other bearings to try.
        if (hi - g > 6) return null;
        if (g <= level.getSeaLevel() + 1) return null;   // never at the waterline

        // Check the WHOLE site before touching any of it.
        //
        // Both loops below used to bail out with `return null` partway through, after they had
        // already removed blocks - so a rejected site was left with a half-shaved notch in it, or a
        // basalt collar with no lava inside. A site is either usable or it is left exactly as it
        // was found; there is no half-built vent.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = g; y <= g + 6; y++) {
                    BlockState s = level.getBlockState(new BlockPos(vx + dx, y, vz + dz));
                    if (s.isAir()) continue;
                    if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return null;
                }
            }
        }

        // Shave the bench down to that level. Only ever removes; nothing is stacked up, so the
        // outlet cannot end up perched on a plinth of its own making.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = vx + dx, z = vz + dz;
                TerrainProbe.clearVegetation(level, x, g, z, 3);
                for (int y = g + 1; y <= g + 6; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getBlockState(p).isAir()) continue;
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }

        BlockPos lava = new BlockPos(vx, g, vz);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos p = new BlockPos(vx + dx, g, vz + dz);
                level.setBlock(p.above(), Blocks.BASALT.defaultBlockState(), 2);
                level.setBlock(p, Blocks.BASALT.defaultBlockState(), 2);
            }
        }
        level.setBlock(lava.below(), Blocks.BASALT.defaultBlockState(), 2);
        level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 2);
        level.setBlock(lava.above(), Blocks.AIR.defaultBlockState(), 2);
        return lava;
    }

    /**
     * Carves a thin lava vein from an outlet down and inward until it meets the central conduit, so
     * the outlet really is fed by the magma system.
     */
    private static void connectVentDown(ServerLevel level, BlockPos start, int coreX, int coreZ, int floorY) {
        BlockPos p = start;
        for (int guard = 0; guard < 400; guard++) {
            if (level.getBlockState(p).is(Blocks.BEDROCK)) break;
            if (level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) break;

            // Never write lava within reach of daylight. The vein used to head inward at whatever
            // height it happened to be, so on a slope it emerged from the hillside and poured down -
            // the same failure the branch carver already guards against. Here it dives instead,
            // which leaves a few blocks of rock between the outlet pool and the vein: invisible, and
            // far better than a lava fall.
            int localGround = TerrainProbe.groundY(level, p.getX(), p.getZ());
            if (localGround == Integer.MIN_VALUE) break;
            if (p.getY() > localGround - LAVA_SURFACE_CLEARANCE) {
                p = p.below();
                if (p.getY() <= floorY - 3) break;
                continue;
            }

            level.setBlock(p, Blocks.LAVA.defaultBlockState(), 2);
            int sx = Integer.compare(coreX, p.getX());
            int sz = Integer.compare(coreZ, p.getZ());
            if (p.getY() > floorY && (p.getX() == coreX && p.getZ() == coreZ || level.random.nextInt(3) == 0)) {
                p = p.below();
            } else if (p.getX() != coreX && (p.getZ() == coreZ || level.random.nextBoolean())) {
                p = p.offset(sx, 0, 0);
            } else if (p.getZ() != coreZ) {
                p = p.offset(0, 0, sz);
            } else {
                p = p.below();
            }
            if (p.getY() <= floorY - 3) break;
        }
    }

    // === Geothermal field and the safety sweep ==============================

    /**
    /**
    /**
     * Scatters hot springs and geysers <b>around</b> the volcano.
     *
     * <h2>Around, not on</h2>
     * Sites used to be drawn from a square centred on the volcano, which includes the cone itself -
     * so a geyser could and did erupt straight out of the summit. That is not where a geothermal
     * field goes: the edifice is the plumbing, and the springs sit on the ground beside it where
     * groundwater can circulate. Sites are now drawn from a ring outside the cone, and any candidate
     * standing on rock the volcano laid down is rejected as well, so the apron stays clear too.
     *
     * <p>A caldera is the exception that proves it: its springs go ON the ring fault, because that
     * circle of deep fractures is exactly what feeds them - which is where Yellowstone's basins are.
     */
    private static void placeField(ServerLevel level, Ctx c) {
        boolean ring = c.type.excavates();
        // Inner edge of the field: outside the cone, or outside the ring-fault scarp.
        double inner = ring ? c.craterR * 1.05 : c.coneBaseR * 1.15 + 4;
        double outer = inner + 26 + c.magnitude;

        int springs = 0;
        for (int attempt = 0; attempt < 120 && springs < (ring ? 7 : 5); attempt++) {
            int[] p = ringSite(level, c, inner, outer);
            if (standsOnVolcanicRock(level, p[0], p[1])) continue;
            if (RetrogenHandler.placeHotSpringAt(level, p[0], p[1])) springs++;
        }

        int deepest = level.getMinBuildHeight() + 2;
        int highest = GeyserConfig.RETROGEN_MAX_Y.get() - GeyserConfig.CHAMBER_TARGET_HEIGHT.get() - 3;
        int coreY = Mth.clamp(GeyserConfig.RETROGEN_MIN_Y.get() + 1, deepest, highest);
        int geysers = 0;
        for (int attempt = 0; attempt < 40 && geysers < (ring ? 4 : 2); attempt++) {
            int[] p = ringSite(level, c, inner, outer);
            if (standsOnVolcanicRock(level, p[0], p[1])) continue;
            RetrogenHandler.forcePlace(level, new BlockPos(p[0], coreY, p[1]),
                    8 + level.random.nextInt(6), level.random);
            geysers++;
        }
    }

    /** A random point in the annulus around the volcano. */
    private static int[] ringSite(ServerLevel level, Ctx c, double inner, double outer) {
        double a = level.random.nextDouble() * Math.PI * 2;
        double r = inner + level.random.nextDouble() * (outer - inner);
        return new int[] {
                c.x + (int) Math.round(Math.cos(a) * r),
                c.z + (int) Math.round(Math.sin(a) * r) };
    }

    /** True where the ground is rock this volcano laid down - its cone, its apron or its flows. */
    private static boolean standsOnVolcanicRock(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return true;
        BlockState s = level.getBlockState(new BlockPos(x, g, z));
        return s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT)
                || s.is(Blocks.BLACKSTONE) || s.is(Blocks.TUFF) || s.is(Blocks.MAGMA_BLOCK);
    }

    /**
    /**
     * Covers any lava that ended up with an open face.
     *
     * <p>Belt and braces: the vein carver and the vents keep lava underground or recessed by
     * construction, but a single exposed cell is enough to set a forest alight, so the build ends by
     * checking rather than trusting. Deliberately <b>targeted</b> rather than a sweep of the whole
     * footprint - lava only ever exists at the summit and at the outlets, and scanning a shield's
     * entire ninety-block apron through fifty levels of Y would have cost more than building the
     * mountain did.</p>
     */
    private static void sealExposedLava(ServerLevel level, Ctx c) {
        if (c.vent == null) return;
        // The summit and its throat.
        // Wide enough to cover a fissure swarm strung out along the strike, capped so a huge shield
        // does not turn the check into a bigger job than the build.
        int summitR = Math.min(Math.max(c.craterR + 5, c.coneBaseR + 4), 40);
        int hiY = Math.max(c.summitY, c.vent.getY()) + 3;
        sealBox(level, c, c.x, c.z, summitR, c.vent.getY() - 6, hiY);
        // Each flank outlet.
        for (BlockPos v : c.vents) {
            sealBox(level, c, v.getX(), v.getZ(), 4, v.getY() - 4, v.getY() + 4);
        }
    }

    private static void sealBox(ServerLevel level, Ctx c, int cx, int cz, int radius, int loY, int hiY) {
        if (hiY < loY) return;
        int keepR = c.coreCraterR + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = loY; y <= hiY; y++) {
                    BlockPos p = new BlockPos(cx + dx, y, cz + dz);
                    if (!level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) continue;
                    // The summit pool, the caldera lake and the outlet mouths are meant to be open.
                    if (isIntendedPool(c, p, keepR)) continue;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = p.relative(d);
                        BlockState ns = level.getBlockState(n);
                        if (ns.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(ns)) continue;
                        if (ns.isAir() || TerrainProbe.isVegetation(ns)) {
                            level.setBlock(n, Blocks.BASALT.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }

    /** True for lava the volcano is supposed to show: its summit pool and its outlet mouths. */
    private static boolean isIntendedPool(Ctx c, BlockPos p, int keepR) {
        if (c.vent != null && Math.abs(p.getY() - c.vent.getY()) <= 1) {
            int dx = p.getX() - c.vent.getX(), dz = p.getZ() - c.vent.getZ();
            if (dx * dx + dz * dz <= keepR * keepR) return true;
        }
        for (BlockPos v : c.vents) {
            if (v.getY() == p.getY() && v.getX() == p.getX() && v.getZ() == p.getZ()) return true;
        }
        return false;
    }

    // === Small helpers ======================================================


    /**
     * Final check: any lava that could still run is removed.
     *
     * <p>{@code sealExposedLava} walls whatever neighbours it finds open, which is the right first
     * move but leaves nothing to catch a cell it missed - and one missed cell on a slope is a lava
     * fall and a burning forest. This is the verification behind it, and it is deliberately harsher:
     * if a lava cell has any horizontal neighbour it could spread into, or one it could fall off,
     * that lava simply does not get to exist. Everything the volcano is <em>supposed</em> to show -
     * the summit pool, the fissure ponds, the flank outlets, the caldera lake - is recessed into its
     * own basin by construction, so all of it passes.</p>
     */
    private static void verifyContainment(ServerLevel level, Ctx c) {
        if (c.vent == null) return;
        int radius = Math.min(Math.max(c.craterR + 6, c.coneBaseR + 4), 44);
        int hiY = Math.max(c.summitY, c.vent.getY()) + 3;
        checkBox(level, c.x, c.z, radius, c.vent.getY() - 8, hiY);
        for (BlockPos v : c.vents) {
            checkBox(level, v.getX(), v.getZ(), 5, v.getY() - 4, v.getY() + 4);
        }
    }

    private static void checkBox(ServerLevel level, int cx, int cz, int radius, int loY, int hiY) {
        if (hiY < loY) return;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = loY; y <= hiY; y++) {
                    BlockPos p = new BlockPos(cx + dx, y, cz + dz);
                    if (!level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) continue;
                    if (!canEscape(level, p)) continue;
                    level.setBlock(p, Blocks.BASALT.defaultBlockState(), 2);
                }
            }
        }
    }

    /** Could lava here spread sideways, or fall off an edge? */
    private static boolean canEscape(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || TerrainProbe.isVegetation(ns)) return true;      // spreads into it
            if (level.getBlockState(n.below()).isAir()) return true;           // falls off it
        }
        return false;
    }
    /** Writes a block unless it is bedrock or something a player made. */
    private static void setRock(ServerLevel level, BlockPos p, BlockState state) {
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return;
        level.setBlock(p, state, 2);
    }

    /** Empties a cell, but only if what is there is natural. */
    private static void clearNatural(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return;
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }
}
