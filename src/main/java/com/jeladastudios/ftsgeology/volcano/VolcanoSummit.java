package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.blockentity.VolcanoCoreBlockEntity;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.worldgen.SurfaceFeatures;
import com.jeladastudios.ftsgeology.worldgen.HotSpringSites;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.LevelAccessor;
import static com.jeladastudios.ftsgeology.volcano.VolcanoBuilder.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoPlan.*;
import static com.jeladastudios.ftsgeology.volcano.VolcanoEdifice.*;

/** Finishes a volcano: summit, core, conduit, flank vents, chimneys and the lava containment sweep. */
public final class VolcanoSummit {

    private VolcanoSummit() {}

    // === Summits ============================================================

    static void buildSummit(ServerLevel level, Ctx c) {
        switch (c.type.summitStyle()) {
            case FUNNEL_PIT -> carveFunnelPit(level, c);
            case LAVA_LAKE -> carveLavaLake(level, c);
            case COLLAPSE_FLOOR -> seatCalderaVent(level, c);
            case FISSURE_PONDS -> carveFissureLine(level, c);
        }
        if (c.vent == null) {
            // Nothing seated: fall back to the axis so the volcano still gets a working core.
            int g = TerrainProbe.groundY(level, c.x, c.z);
            c.vent = new BlockPos(c.x, g == Integer.MIN_VALUE ? c.summitY : g, c.z);
        }
    }

    /**
     * A stratovolcano's crater: a funnel stepping down to a lava lake on a real floor, shallow enough
     * that the lake glows in view from the rim, as at Villarrica or Nyiragongo.
     */
    static void carveFunnelPit(ServerLevel level, Ctx c) {
        // A lake about half the crater across, so it reads as a lake from above.
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
        clearAboveCrater(level, c, c.craterR * 1.18 + 1.0);
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
    static void carveLavaLake(ServerLevel level, Ctx c) {
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
        clearAboveCrater(level, c, c.craterR * 1.58 + 1.0);
        c.vent = new BlockPos(c.x, lakeY, c.z);
        c.coreCraterR = Math.max(2, c.craterR);
    }

    /**
     * Clears whatever natural stands over a crater, up to eight blocks above the summit: a chimney or
     * a tree put down before the crater was carved would otherwise be left hanging over the lava.
     */
    static void clearAboveCrater(ServerLevel level, Ctx c, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = c.summitY + 1; y <= c.summitY + 8; y++) {
                    clearNatural(level, new BlockPos(c.x + dx, y, c.z + dz));
                }
            }
        }
    }

    /** Finds the caldera's crescent lake and seats the core under it. */
    static void seatCalderaVent(ServerLevel level, Ctx c) {
        double r = c.craterR * 0.6;
        int vx = c.x + (int) Math.round(Math.cos(c.lakeAngle) * r);
        int vz = c.z + (int) Math.round(Math.sin(c.lakeAngle) * r);
        // One below the floor, matching the recessed lake, so the core sits under lava.
        BlockPos p = new BlockPos(vx, c.calderaFloorY - 1, vz);
        setRock(level, p.below(), Blocks.BASALT.defaultBlockState());
        setRock(level, p, Blocks.LAVA.defaultBlockState());
        clearNatural(level, p.above());
        c.vent = p;
        // Only the lake area stays molten between eruptions; the rest of the floor cools.
        c.coreCraterR = Math.max(2, c.craterR / 3);
    }

    /** A rift volcano: no cone, a line of ponds along the fault strike stepping sideways in en-echelon segments. */
    static void carveFissureLine(ServerLevel level, Ctx c) {
        int half = c.fissureHalf;
        int segLen = c.segLen;
        // A big fissure keeps its ponds to the middle; the rest of its line is ramparts.
        int span = hasRamparts(c) ? Math.min(half, POND_SEGMENT - 1) : half;
        for (int t = -span; t <= span; t++) {
            int seg = Math.floorDiv(t + half, segLen);
            double lateral = ((seg % 2 == 0) ? 1 : -1) * (1 + seg % 3);
            int px = c.x + (int) Math.round(c.strikeX * t - c.strikeZ * lateral);
            int pz = c.z + (int) Math.round(c.strikeZ * t + c.strikeX * lateral);
            BlockPos pond = seatPondCell(level, px, pz);
            if (pond == null) continue;
            if (c.vent == null) c.vent = pond;
            // Every pond is a vent, so the containment sweep leaves it open and the core smokes from it.
            c.vents.add(pond);
            // And molten, so the core keeps it lava after eruptions and quakes.
            c.molten.add(pond);
        }
        c.coreCraterR = 2;
    }

    /**
     * Seats one fissure pond into the ground, with a spatter rampart built up from each neighbour's own
     * ground. A cell whose neighbours are not level enough to hold a pond is skipped.
     *
     * @return the lava cell, or null if this spot could not hold one
     */
    static BlockPos seatPondCell(ServerLevel level, int px, int pz) {
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

    static void plantCore(ServerLevel level, Ctx c) {
        BlockPos corePos = c.vent.below();
        level.setBlock(corePos, ModBlocks.VOLCANO_CORE.get().defaultBlockState(), 2);
        if (level.getBlockEntity(corePos) instanceof VolcanoCoreBlockEntity core) {
            core.setMagnitude(c.magnitude);
            core.setCraterRadius(c.coreCraterR);
            core.setMoltenCells(c.molten);
            // What the mountain was, so it can be raised again after a quake: from the original base,
            // or a rebuild would stack a new cone on the ruins. A large volcano records none.
            if (c.size != VolcanoSize.LARGE) {
                core.setShape(c.type, c.size, new BlockPos(c.x, c.baseY, c.z), c.summitY);
            }
        }
        setRock(level, c.vent, Blocks.LAVA.defaultBlockState());
    }

    static void carveConduit(ServerLevel level, Ctx c) {
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

    static void fillLavaDisc(ServerLevel level, int cx, int cy, int cz, int r, int thickness) {
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

    /** Root-like lava veins inside the mountain, kept clear of the local surface so none can break out. */
    static void growLavaBranches(ServerLevel level, Ctx c) {
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
     * Picks flank outlet sites, each clear of the others by a minimum spacing and spread in the type's
     * own pattern: upper flank, far and radial, along the strike, or round the ring fault.
     */
    static void chooseVents(ServerLevel level, Ctx c) {
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

    static int[] ventCandidate(ServerLevel level, Ctx c) {
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
                if (c.liveReach > 0) along = Mth.clamp(along, -c.liveReach, c.liveReach);
                double across = (level.random.nextDouble() * 2 - 1) * 5;
                return new int[] {
                        c.x + (int) Math.round(c.strikeX * along - c.strikeZ * across),
                        c.z + (int) Math.round(c.strikeZ * along + c.strikeX * across) };
            }
            default -> dist = outer;
        }
        // A large volcano's outlets are cut live near the summit, spread over that loaded ring.
        if (c.liveReach > 0 && dist > c.liveReach) {
            dist = c.craterR + 3 + level.random.nextDouble() * Math.max(1, c.liveReach - c.craterR - 3);
        }
        return new int[] {
                c.x + (int) Math.round(Math.cos(ang) * dist),
                c.z + (int) Math.round(Math.sin(ang) * dist) };
    }

    static void cutVent(ServerLevel level, Ctx c, int index) {
        if (index >= c.ventSites.size()) return;
        BlockPos site = c.ventSites.get(index);
        BlockPos outlet = carveSeatedOutlet(level, site.getX(), site.getZ());
        if (outlet == null) return;
        connectVentDown(level, outlet.below(), c.x, c.z, c.reservoirY + 2);
        c.vents.add(outlet);
    }

    /**
     * Steam chimneys over the flanks, from the crater rim out to the near apron. They show a live cone
     * from a distance and blow black smoke during an eruption, right where the player stands.
     */
    static void cutFumaroles(ServerLevel level, Ctx c) {
        // Scaled with size, so a big mountain is not as sparse as a cinder cone.
        int wanted = 4 + c.magnitude / 2;
        int tries = wanted * 4;
        for (int i = 0; i < tries && c.fumaroles.size() < wanted; i++) {
            double ang = level.random.nextDouble() * Math.PI * 2;
            double edge = coneRadius(c, ang);
            // Clear of the crater, and out as far as the near apron.
            double d = c.craterR + 3 + level.random.nextDouble() * Math.max(6.0, edge * 1.05);
            if (c.liveReach > 0) {
                d = c.craterR + 3 + level.random.nextDouble() * Math.max(6.0, c.liveReach - c.craterR - 3);
            }
            int fx = c.x + (int) Math.round(Math.cos(ang) * d);
            int fz = c.z + (int) Math.round(Math.sin(ang) * d);
            // Never into a chunk that is not loaded: asking for its ground would load it on the spot.
            if (!loaded(level, fx, fz, 2)) continue;

            int g = TerrainProbe.groundY(level, fx, fz);
            if (g == Integer.MIN_VALUE) continue;
            BlockPos ground = new BlockPos(fx, g, fz);
            BlockState on = level.getBlockState(ground);
            if (on.is(Blocks.BEDROCK) || !on.getFluidState().isEmpty()) continue;
            if (!level.getBlockState(ground.above()).isAir()) continue;
            // Never on the mod's own machinery, and never into somebody's build.
            if (com.jeladastudios.ftsgeology.eruption.EruptionHandler.isPlayerPlaced(on)) continue;

            com.jeladastudios.ftsgeology.worldgen.HotspotSigns.chimney(level, ground, level.random);
            // Only count it if a chimney actually went in - chimney() bails on headroom of its own.
            if (level.getBlockState(ground).is(
                    com.jeladastudios.ftsgeology.registry.ModBlocks.STEAM_VENT.get())) {
                c.fumaroles.add(ground);
            }
        }
    }

    static void recordVents(ServerLevel level, Ctx c) {
        if (c.vent == null) return;
        if (level.getBlockEntity(c.vent.below()) instanceof VolcanoCoreBlockEntity core) {
            core.setSurfaceVents(c.vents);
            core.setFumaroles(c.fumaroles);
        }
        // Logged, since an outlet site that will not do is skipped silently.
        GeysersMod.LOGGER.info("volcano {} vents: {} cut of {} sites, {} molten cells",
                c.type, c.vents.size(), c.ventSites.size(), c.molten.size());
    }

    /**
     * Seats a lava outlet into the hillside: shaves a 5x5 bench down to the lowest ground in it, then
     * recesses the lava under a basalt collar so it has nowhere to run. Refuses more than six blocks of
     * relief, and checks the whole site before touching any of it.
     *
     * @return the lava cell, or null if this spot was unusable
     */
    static BlockPos carveSeatedOutlet(ServerLevel level, int vx, int vz) {
        // Nothing here may reach into an unloaded chunk; reading one loads it on the server thread.
        if (!loaded(level, vx, vz, 3)) return null;
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
        // A bench, not a cliff: past six blocks of relief the notch would read as a bite out of the mountain.
        if (hi - g > 6) return null;
        if (g <= level.getSeaLevel() + 1) return null;   // never at the waterline

        // Check the whole site first, so a rejected site is left exactly as it was.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = g; y <= g + 6; y++) {
                    BlockState s = level.getBlockState(new BlockPos(vx + dx, y, vz + dz));
                    if (s.isAir()) continue;
                    if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return null;
                }
            }
        }

        // Shave down to that level. Only removes, so the outlet never stands on a plinth.
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
    static void connectVentDown(ServerLevel level, BlockPos start, int coreX, int coreZ, int floorY) {
        BlockPos p = start;
        for (int guard = 0; guard < 400; guard++) {
            if (level.getBlockState(p).is(Blocks.BEDROCK)) break;
            if (level.getBlockState(p).getFluidState().is(FluidTags.LAVA)) break;

            // Dive rather than head inward within reach of daylight, so the vein never breaks out of a slope.
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
     * Scatters hot springs and geysers in a ring around the volcano, never on its own rock. A caldera's
     * go on its ring fault, whose fractures feed them, as in Yellowstone's basins.
     */
    static void placeField(ServerLevel level, Ctx c) {
        boolean ring = c.type.excavates();
        // Inner edge of the field: outside the cone, or outside the ring-fault scarp.
        double inner = ring ? c.craterR * 1.05 : c.coneBaseR * 1.15 + 4;
        double outer = inner + 26 + c.magnitude;

        int springs = 0;
        for (int attempt = 0; attempt < 120 && springs < (ring ? 7 : 5); attempt++) {
            int[] p = ringSite(level, c, inner, outer);
            if (standsOnVolcanicRock(level, p[0], p[1])) continue;
            if (HotSpringSites.placeHotSpringAt(level, p[0], p[1])) springs++;
        }

        int deepest = level.getMinBuildHeight() + 2;
        int highest = GeyserConfig.RETROGEN_MAX_Y.get() - GeyserConfig.CHAMBER_TARGET_HEIGHT.get() - 3;
        int coreY = Mth.clamp(GeyserConfig.RETROGEN_MIN_Y.get() + 1, deepest, highest);
        int geysers = 0;
        for (int attempt = 0; attempt < 40 && geysers < (ring ? 4 : 2); attempt++) {
            int[] p = ringSite(level, c, inner, outer);
            if (standsOnVolcanicRock(level, p[0], p[1])) continue;
            SurfaceFeatures.forcePlace(level, new BlockPos(p[0], coreY, p[1]),
                    8 + level.random.nextInt(6), level.random);
            geysers++;
        }
    }

    /** A random point in the annulus around the volcano. */
    static int[] ringSite(ServerLevel level, Ctx c, double inner, double outer) {
        double a = level.random.nextDouble() * Math.PI * 2;
        double r = inner + level.random.nextDouble() * (outer - inner);
        return new int[] {
                c.x + (int) Math.round(Math.cos(a) * r),
                c.z + (int) Math.round(Math.sin(a) * r) };
    }

    /** True where the ground is rock this volcano laid down - its cone, its apron or its flows. */
    static boolean standsOnVolcanicRock(ServerLevel level, int x, int z) {
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) return true;
        BlockState s = level.getBlockState(new BlockPos(x, g, z));
        return s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT)
                || s.is(Blocks.BLACKSTONE) || s.is(Blocks.TUFF) || s.is(Blocks.MAGMA_BLOCK);
    }

    /**
     * Walls any lava that ended up with an open face, around the summit and each outlet only: lava exists
     * nowhere else, and a full-footprint sweep would cost more than the build.
     */
    static void sealExposedLava(ServerLevel level, Ctx c) {
        if (c.vent == null) return;
        // The summit and its throat, wide enough for a fissure's ponds, capped for a huge shield.
        int summitR = Math.min(Math.max(c.craterR + 5, c.coneBaseR + 4), 40);
        int hiY = Math.max(c.summitY, c.vent.getY()) + 3;
        sealBox(level, c, c.x, c.z, summitR, c.vent.getY() - 6, hiY);
        // Each flank outlet.
        for (BlockPos v : c.vents) {
            sealBox(level, c, v.getX(), v.getZ(), 4, v.getY() - 4, v.getY() + 4);
        }
    }

    static void sealBox(ServerLevel level, Ctx c, int cx, int cz, int radius, int loY, int hiY) {
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
    static boolean isIntendedPool(Ctx c, BlockPos p, int keepR) {
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
     * Final check after the sealing: any lava cell that could still spread sideways or fall off an edge
     * is turned to basalt. Everything meant to be open is recessed, so it passes.
     */
    static void verifyContainment(ServerLevel level, Ctx c) {
        if (c.vent == null) return;
        int radius = Math.min(Math.max(c.craterR + 6, c.coneBaseR + 4), 44);
        int hiY = Math.max(c.summitY, c.vent.getY()) + 3;
        checkBox(level, c.x, c.z, radius, c.vent.getY() - 8, hiY);
        for (BlockPos v : c.vents) {
            checkBox(level, v.getX(), v.getZ(), 5, v.getY() - 4, v.getY() + 4);
        }
    }

    static void checkBox(ServerLevel level, int cx, int cz, int radius, int loY, int hiY) {
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
    static boolean canEscape(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || TerrainProbe.isVegetation(ns)) return true;      // spreads into it
            if (level.getBlockState(n.below()).isAir()) return true;           // falls off it
        }
        return false;
    }
    /** Writes a block unless it is bedrock or something a player made. */
    static void setRock(LevelAccessor level, BlockPos p, BlockState state) {
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return;
        level.setBlock(p, state, 2);
    }

    /** Empties a cell, but only if what is there is natural. */
    static void clearNatural(LevelAccessor level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.is(Blocks.BEDROCK) || EruptionHandler.isPlayerPlaced(s)) return;
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }
}
