package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import static com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoPlan.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoSummit.*;

/** Raises a volcano's body column by column: cone, caldera, apron, flows and fissure ramparts. */
public final class VolcanoEdifice {

    private VolcanoEdifice() {}

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
    static double seamHeight(Ctx c) {
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
    static int coneTargetY(Ctx c, int gx, int gz, int localGround, double dist, double ang) {
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
    static double coneRadius(Ctx c, double ang) {
        return c.coneBaseR * (1.0 + 0.14 * Math.sin(3 * ang + c.phaseA)
                + 0.07 * Math.sin(5 * ang + c.phaseB));
    }

    /** How far the cone's foot can possibly reach, lobes included. */
    static int coneReach(Ctx c) {
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
    static double surfaceNoise(Ctx c, int gx, int gz) {
        return Math.sin(gx * 0.19 + c.phaseA) * Math.cos(gz * 0.23 + c.phaseB)
                + 0.5 * Math.sin((gx + gz) * 0.11 + c.phaseC)
                + 0.35 * Math.sin((gx - gz) * 0.31 + c.phaseA);
    }

    static void buildConeRow(ServerLevel level, Ctx c, int dx) {
        // The lobed foot can swing a fifth further out than coneBaseR, and the old reach clipped
        // it off flat wherever it did.
        int reach = coneReach(c);
        for (int dz = -reach; dz <= reach; dz++) {
            coneColumn(level, c, c.x + dx, c.z + dz, level.random, false);
        }
    }

    /**
     * One column of the cone.
     *
     * <h2>Why every shaping step is a column</h2>
     * A column reads its own ground and writes its own blocks and nothing else, so what happens to it
     * cannot depend on what has happened to any other. That is what lets world generation raise a
     * large volcano a chunk at a time, in whatever order the chunks come, and still end with one
     * mountain rather than a patchwork with a step at every chunk border.
     *
     * @param worldgen true while the chunk is still being generated. Standing water under the cone
     *                 is then filled over rather than walled around - a river through a mountain 150
     *                 blocks tall would otherwise leave a slot canyon - and anything that grew on the
     *                 old ground is cleared off the new top of the column.
     */
    static void coneColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                   boolean worldgen) {
        int dx = gx - c.x, dz = gz - c.z;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        double ang = Math.atan2(dz, dx);

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // Stop at the water's edge instead of walling a lake in. A cone that marches into open
        // water leaves a sheer black rampart around the shoreline, which is what the shield
        // built beside a lake looked like.
        if (!worldgen && !level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) {
            return;
        }

        int target = coneTargetY(c, gx, gz, ground, dist, ang);
        if (target == Integer.MIN_VALUE) return;
        // The land here is already higher than the mountain would be: leave it alone. That is
        // both cheaper and what makes a volcano growing out of an existing range look right.
        if (ground >= target) return;

        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        // A flow is a skin over the flank, not a seam through it: only the surface course is
        // crust, and the rock underneath is the ordinary interbedded pile.
        boolean flow = flowAt(c, ang, dist);
        for (int y = ground + 1; y <= target; y++) {
            setRock(level, new BlockPos(gx, y, gz),
                    flow && y == target ? flowRock() : coneRock(rng, c, y));
        }
        if (worldgen) clearCover(level, gx, target, gz);
    }

    /**
     * Clears plants and tree parts left standing above a column's new top during generation.
     *
     * <p>This chunk's own trees are not in yet when the mountain goes up, but a neighbour's can
     * already hang over it, and without this their crowns would float over the flank.</p>
     */
    static void clearCover(LevelAccessor level, int x, int fromY, int z) {
        int top = Math.min(fromY + 40, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        for (int y = fromY + 1; y <= top; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (!(s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || TerrainProbe.isVegetation(s))) return;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
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
    /**
     * Is this column inside one of the frozen lava flows running down the flanks?
     *
     * <h2>Why the mod had a cooling-crust block and nowhere to put it</h2>
     * {@code COOLING_LAVA_CRUST} has been registered, textured, given a light level and listed as
     * natural terrain for a long time, and in all that time nothing in world generation ever placed
     * one: it existed only in the creative menu. Meanwhile a volcano's flanks were a single
     * undifferentiated skin of basalt, when the most recognisable thing about a real cone is that it
     * is <b>striped</b> - dark tongues of recent flow standing out against older, weathered rock,
     * still warm enough to glow at night.
     *
     * <h2>A function of bearing and distance, not a second pass over the world</h2>
     * The obvious way to lay a flow is to walk one downhill from the crater, and it is the wrong way
     * here: {@code VolcanoJob} builds the mountain a row at a time across many ticks and chunks, so
     * a walk would need the finished surface before the surface exists. But the cone's own outline
     * is already a pure function of bearing - {@link #coneRadius} lobes it with two sine terms - so a
     * flow can be one too, and then it simply drapes over whatever height the cone turns out to have.
     * No extra pass, no world reads, and it cannot disagree with the shape it is lying on.
     *
     * <p>The channel narrows near the vent and broadens into a lobe at the toe, which is the shape
     * an a'a flow actually leaves: a confined channel while it is moving fast, spreading out and
     * piling up where it stalls.</p>
     */
    static boolean flowAt(Ctx c, double ang, double dist) {
        if (c.flows == 0) return false;
        // The crater is molten, not crusted; its own lava is placed by the summit step.
        if (dist < c.craterR * 0.9 || dist > c.flowReach) return false;

        double t = dist / c.flowReach;                       // 0 at the vent, 1 at the toe
        for (int i = 0; i < c.flows; i++) {
            // The channel snakes as it descends rather than running down a radius like a seam.
            double centre = c.flowAim[i]
                    + 0.26 * Math.sin(dist / 9.0 + c.flowPhase[i])
                    + 0.12 * Math.sin(dist / 4.0 - c.flowPhase[i]);
            // Wrapped to -PI..PI, so a flow aimed near due west is not cut in half by the seam in
            // atan2's output.
            double delta = Math.atan2(Math.sin(ang - centre), Math.cos(ang - centre));
            double across = Math.abs(delta) * dist;          // blocks measured across the flow
            if (across <= (0.9 + 2.0 * t * t) * c.flowWidth) return true;
        }
        return false;
    }

    /** The skin of a flow: dark, and still warm enough to show at night. */
    static BlockState flowRock() {
        return com.jeladastudios.ftsgeology.registry.ModBlocks.COOLING_LAVA_CRUST.get()
                .defaultBlockState();
    }

    static BlockState coneRock(RandomSource rng, Ctx c, int y) {
        return switch (c.type) {
            case STRATOVOLCANO -> switch (Math.floorMod((y + c.bandSeed) / 3, 4)) {
                case 0 -> Blocks.TUFF.defaultBlockState();
                case 2 -> Blocks.BLACKSTONE.defaultBlockState();
                default -> (rng.nextInt(5) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                        .defaultBlockState();
            };
            case SHIELD -> (rng.nextInt(3) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
            case CALDERA -> (rng.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.TUFF)
                    .defaultBlockState();
            case FISSURE -> (rng.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
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
    static double ringRadius(Ctx c, double ang) {
        return c.craterR * (1.0 + 0.22 * Math.sin(2 * ang + c.phaseA)
                + 0.12 * Math.sin(3 * ang + c.phaseB));
    }

    /** How far out the ring fault can possibly reach, scarp included. */
    static int calderaRingReach(Ctx c) {
        return (int) Math.ceil(c.craterR * 1.34) + c.rimWidth + 1;
    }

    static void carveCalderaRow(ServerLevel level, Ctx c, int dx) {
        int reach = calderaRingReach(c);
        for (int dz = -reach; dz <= reach; dz++) {
            calderaColumn(level, c, c.x + dx, c.z + dz, level.random, false);
        }
    }

    /** One column of a caldera: its floor inside the ring fault, its scarp outside. See {@link #coneColumn}. */
    static void calderaColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                      boolean worldgen) {
        int dx = gx - c.x, dz = gz - c.z;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        double ang = Math.atan2(dz, dx);
        double rr = ringRadius(c, ang);
        if (dist > rr + c.rimWidth) return;
        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // Never cut below open water: the caldera floor would simply drain the lake into itself
        // and leave a black bowl where the shoreline used to be. A large caldera being generated is
        // floored over whatever water is in the way instead - four hundred blocks across there nearly
        // always is some - but it still raises no scarp through a lake.
        boolean wet = !level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty();
        if (wet && !(worldgen && dist <= rr)) return;
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
            int clearTop = ground + 2;
            if (worldgen) {
                // Up through any water and whatever grows here, not only the two cells above ground.
                clearTop = Math.max(clearTop, Math.min(ground + 40,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz)));
            } else {
                TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
            }
            for (int y = target + 1; y <= clearTop; y++) {
                clearNatural(level, new BlockPos(gx, y, gz));
            }
            for (int y = Math.min(ground, target); y <= target; y++) {
                setRock(level, new BlockPos(gx, y, gz), coneRock(rng, c, y));
            }
            if (lake) {
                // Recessed by one block, exactly like the flank vents: the lake is the lowest
                // point of its own basin and so has nowhere to flow.
                BlockPos molten = new BlockPos(gx, target - 1, gz);
                setRock(level, molten, Blocks.LAVA.defaultBlockState());
                clearNatural(level, new BlockPos(gx, target, gz));
                // This cell is MEANT to stay lava; see Ctx.molten. A generated lake is listed when its
                // summit is finished instead, see collectCalderaLake.
                if (!worldgen) c.molten.add(molten);
            } else if (rng.nextInt(6) == 0) {
                setRock(level, new BlockPos(gx, target, gz), Blocks.TUFF.defaultBlockState());
            }
            return;
        }

        // The ring fault scarp.
        //
        // A perfect circle of equal height reads as a palisade, which is not what a ring
        // fault looks like: a real caldera rim varies along its length and is cut through by
        // low saddles - Crater Lake's rim has several. So the height is modulated round the
        // circle and simply stops where the modulation bottoms out, leaving breaches.
        double gate = 0.5 + 0.5 * Math.sin(3 * ang + c.phaseC)
                + 0.25 * Math.sin(5 * ang + c.phaseA);
        if (gate < 0.22) return;                     // a breach in the ring
        double t = 1.0 - (dist - rr) / c.rimWidth;
        int lift = (int) Math.round(c.rimLift * t
                * (0.3 + 0.7 * Mth.clamp(gate, 0.0, 1.0))) + rough;
        if (lift < 1) return;
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        for (int h = 1; h <= lift; h++) {
            setRock(level, new BlockPos(gx, ground + h, gz), coneRock(rng, c, ground + h));
        }
        if (worldgen) clearCover(level, gx, ground + lift, gz);
    }

    /** True inside the crescent of the caldera floor that holds the lava lake. */
    static boolean inLakeSector(Ctx c, double dist, double ang) {
        double rel = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(ang - c.lakeAngle)));
        return Math.abs(rel) <= c.lakeWidth * 0.5
                && dist > c.domeR + 1 && dist < c.lakeOuter;
    }

    /**

    /**
     * Skirts whatever we built with its own debris, thinning to nothing at the edge.
     *
     * <p>Without this the edifice meets the landscape at a hard step and reads as an object dropped
     * on the map. The outer third is deliberately <b>speckled</b> rather than solid, so volcanic rock
     * and native ground interleave the way a real ash fall thins out, instead of ending on a line.</p>
     */
    static void buildApronRow(ServerLevel level, Ctx c, int dx) {
        int reach = c.apronReach;
        for (int dz = -reach; dz <= reach; dz++) {
            apronColumn(level, c, c.x + dx, c.z + dz, level.random, false);
        }
    }

    /** One column of the apron. See {@link #coneColumn}. */
    static void apronColumn(LevelAccessor level, Ctx c, int gx, int gz, RandomSource rng,
                                    boolean worldgen) {
        int dx = gx - c.x, dz = gz - c.z;
        int reach = c.apronReach;
        double dist = apronDistance(c, dx, dz);
        if (dist > reach) return;
        double ang = Math.atan2(dz, dx);
        // Where the apron starts depends on what it is skirting. A caldera's begins outside its ring
        // scarp, or it would bury the very rim you stand on to look in. A fissure's begins at the
        // crack itself: what a rift erupts is a flood-basalt FIELD spreading out from the line, so an
        // annulus with bare ground in the middle would have been exactly backwards. The ponds are cut
        // into this field afterwards.
        //
        // And it starts where the edifice actually ENDS at this bearing, not at a fixed radius. A
        // fixed one left a band of untouched grass wherever the outline came in narrow, which read as
        // a moat between the volcano and its own skirt - and with the apron at its thickest right at
        // its inner edge, as a wall standing on that grass.
        double localInner = switch (c.type) {
            case CALDERA -> ringRadius(c, ang) + c.rimWidth;
            case FISSURE -> 0;
            default -> coneRadius(c, ang);
        };
        if (dist <= localInner) return;
        double edge = reach * (0.84 + 0.16 * Math.sin(3 * ang + c.phaseC));
        if (dist > edge || localInner >= edge) return;

        double t = 1.0 - (dist - localInner) / Math.max(1.0, edge - localInner);
        // A flow tongue runs past the foot of the cone, so it has to carry on across the apron
        // or it stops dead on a contour line - which is the same "wall at a fixed radius" fault
        // this row already had to be taught not to make.
        boolean flow = flowAt(c, ang, Math.sqrt((double) dx * dx + (double) dz * dz));
        // Speckling: certain near the cone, sparse at the rim. This is what dissolves the hard
        // boundary the player was seeing between basalt and native terrain. The flow is exempt:
        // it is a continuous sheet of rock, and speckling it would perforate the tongue.
        if (!flow && rng.nextDouble() > Mth.clamp(t * 1.7, 0.0, 1.0)) return;

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        // Checked directly rather than through hasFluidAbove, which would walk the column down
        // from the heightmap a second time. Over twenty thousand apron columns that doubling is
        // most of the build cost.
        if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) return;

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
        int thickness = Math.max(0, (int) Math.round(seamHeight(c) * Math.pow(1.0 - u, 1.5)));
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
        BlockState native0 = level.getBlockState(new BlockPos(gx, ground, gz));
        for (int h = 0; h <= thickness; h++) {
            // The blend-into-the-ground pass-through only makes sense for the surface cell.
            // Handing it back at h > 0 would stack a copy of the local ground in the air - a
            // grass block floating over the apron.
            setRock(level, new BlockPos(gx, ground + h, gz),
                    flow && h == thickness
                            ? flowRock()
                            : apronRock(rng, t, h == 0 ? native0 : null));
        }
        // Only plants: the apron is a few blocks thick, and a tree beside it is still standing on
        // real ground, so clearing its crown here would only leave half a tree.
        if (worldgen) TerrainProbe.clearVegetation(level, gx, ground + thickness, gz, 2);
    }

    /**
     * Distance the apron is laid out by. A fissure above the small size floods its basalt out along
     * the crack rather than round a point, so its field is an ellipse three times longer than it is
     * wide: a line four hundred blocks long in the middle of a round sheet would read as a volcano that
     * had lost its cone.
     */
    static double apronDistance(Ctx c, int dx, int dz) {
        if (!hasRamparts(c)) return Math.sqrt((double) dx * dx + (double) dz * dz);
        double along = dx * c.strikeX + dz * c.strikeZ;
        double across = -dx * c.strikeZ + dz * c.strikeX;
        return Math.hypot(along, across * 3.0);
    }

    static boolean hasRamparts(Ctx c) {
        return c.type == VolcanoType.FISSURE && c.size != VolcanoSize.SMALL;
    }

    /**
     * The spatter ramparts along a big fissure's line, and the open crack between them.
     *
     * <p>A small fissure is short enough for its whole line to be ponds, cut once it is loaded. Four
     * hundred blocks of ponds would be thousands of lava cells and need a live world the length of
     * it, so a big one keeps its ponds to the middle and shows the rest of the line the way an older
     * stretch of a real rift looks: a crack with a low wall of spatter either side, stepping sideways
     * in segments. Worked out per column, like the rest of the edifice.</p>
     */
    static void fissureRampartColumn(LevelAccessor level, Ctx c, int gx, int gz, boolean worldgen) {
        int dx = gx - c.x, dz = gz - c.z;
        double along = dx * c.strikeX + dz * c.strikeZ;
        double out = Math.abs(along);
        if (out > c.fissureHalf || out < POND_SEGMENT) return;
        double across = -dx * c.strikeZ + dz * c.strikeX;
        // The same en-echelon offsets carveFissureLine steps its ponds through.
        int seg = Math.floorDiv((int) Math.round(along) + c.fissureHalf, c.segLen);
        double lateral = ((seg % 2 == 0) ? 1 : -1) * (1 + seg % 3);
        double off = Math.abs(across - lateral);
        if (off > 3.5) return;
        // Dies away over the last quarter towards each tip.
        double tip = Mth.clamp((1.0 - out / c.fissureHalf) * 4.0, 0.0, 1.0);
        if (tip <= 0.0) return;

        int ground = TerrainProbe.groundY(level, gx, gz);
        if (ground == Integer.MIN_VALUE) return;
        if (!level.getBlockState(new BlockPos(gx, ground + 1, gz)).getFluidState().isEmpty()) return;
        if (off < 0.75) {
            // The crack: open two blocks down and no more, so there is nowhere far to fall.
            TerrainProbe.clearVegetation(level, gx, ground, gz, 2);
            for (int y = ground; y > ground - 2; y--) clearNatural(level, new BlockPos(gx, y, gz));
            return;
        }
        int lift = (int) Math.round((off < 2.25 ? 3.0 : 1.5) * tip);
        if (lift < 1) return;
        if (!worldgen) TerrainProbe.clearVegetation(level, gx, ground, gz, 3);
        for (int h = 1; h <= lift; h++) {
            boolean dark = h == lift && Math.floorMod(gx * 31 + gz, 5) == 0;
            setRock(level, new BlockPos(gx, ground + h, gz),
                    (dark ? Blocks.BLACKSTONE : Blocks.BASALT).defaultBlockState());
        }
        if (worldgen) TerrainProbe.clearVegetation(level, gx, ground + lift, gz, 2);
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
    static BlockState apronRock(RandomSource rng, double t, BlockState native0) {
        double r = rng.nextDouble();

        // Outermost cells sometimes stay as they are. Ramps in below t = 0.3 and reaches roughly a
        // third of cells at the very edge - enough to fray the boundary, not enough to leave holes.
        if (native0 != null && t < 0.3 && r > t / 0.3 * 0.7 + 0.3) return native0;

        // Basalt dominates near the cone, tephra at the rim; both are present throughout.
        double basalt = Mth.clamp(t * 1.15, 0.0, 1.0);
        if (rng.nextDouble() < basalt) {
            return (rng.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : Blocks.BASALT)
                    .defaultBlockState();
        }
        return (rng.nextInt(3) == 0 ? Blocks.GRAVEL : Blocks.TUFF).defaultBlockState();
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
    static void clearSiteRow(ServerLevel level, Ctx c, int dx) {
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
}
