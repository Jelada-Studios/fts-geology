package com.jeladastudios.ftsgeology.worldgen;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise3D;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.util.Diagnostics;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext;
import com.jeladastudios.ftsgeology.worldgen.terrain.WorldgenRevision;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ore bodies big enough to be worth going after, that reach the ground and show where it is bare rock (0.11.3).
 *
 * <p>The deposits {@link OreGenesis} lays are many and small and lie deep, and none of them is seen from the surface.
 * Here a few large ones are built from the ground down instead, the way a prospector meets them:</p>
 * <ul>
 *   <li><b>Porphyry copper</b> on an arc over a pluton: a stock tens of blocks across, its core porphyry peppered with
 *       chalcopyrite, a pyrite shell round it, quartz veinlets through it. Its top has rotted to a rusty gossan in
 *       browns, oranges and yellows, the copper in it gone to green malachite and blue azurite: the colour a prospector
 *       sees across a valley.</li>
 *   <li><b>A gold-bearing quartz reef</b> in a collision belt: a white sheet two to four blocks thick, standing almost
 *       upright along the belt for a hundred blocks and more, its rich shoots gold-bearing quartz, the gneiss or schist
 *       beside them specked with gold.</li>
 *   <li><b>Float and placer</b>: the streams that drain a body carry pieces of it down for a few hundred blocks, so a
 *       walker finds them on the banks and bars, fewer the farther down, and gold in the gravel of the beds below a
 *       reef. Following them upstream leads to the body.</li>
 * </ul>
 *
 * <p>A body only takes the place of natural rock, so under soil and grass it stays hidden and is seen only on cliffs,
 * crags and in gullies. The mod's own world type, worlds made with {@link WorldgenRevision#ORE_BODIES} or later.</p>
 */
public final class OreOutcrops {

    private OreOutcrops() {}

    public enum Kind { PORPHYRY, REEF }

    /**
     * One body, worked out once from its cell: where its anchor is, the generator's ground there, and its shape. A
     * porphyry is a stock of {@code radius} round a centre at {@code cy}; a reef a sheet {@code half} each way along
     * ({@code vx}, {@code vz}), {@code thick} across, leaning by {@code lean} for every block up.
     */
    public record Body(Kind kind, int x, int z, int ground, long h, double radius, int cy, int half, double vx,
                       double vz, double thick, double lean) {
        /** How far from its anchor the body reaches across the ground. */
        public int reach() {
            return kind == Kind.PORPHYRY ? (int) Math.ceil(radius * 1.35) + 1
                    : half + (int) Math.ceil(Math.abs(lean) * 80 + thick) + 6;
        }

        /** Its deepest block, about. */
        public int bottom() {
            return kind == Kind.PORPHYRY ? (int) Math.floor(cy - radius * STRETCH * 1.05) : ground - 80;
        }
    }

    /** A length of a stream below a body: where it runs, how far down from the body it starts and ends, how wide it is. */
    public record Seg(float x, float z, float ex, float ez, float d0, float d1, float half, float water) {}

    /** A grid cell holds at most one body; the share of cells that may hold one where the rock allows it. */
    private static final int CELL = 256;
    private static final double CHANCE = 0.6;
    /** The farthest a body reaches across from its anchor. */
    private static final int BODY_REACH = 140;
    /** How far down its streams pieces of a body are carried. */
    public static final int FLOAT_REACH = 600;
    /** How far from a body's edge the streams that take its pieces may run. */
    private static final int SHED = 160;
    /** How far round a body's edge pieces of it lie on the slopes, and the chance of one at the edge. */
    public static final int SLOPE = 100;
    private static final double SLOPE_CHANCE = 0.006;
    /** A porphyry stock is this much taller than wide. */
    private static final double STRETCH = 1.5;
    /** The chance a bank column right below a body holds float, and a bed column below a reef gold-bearing gravel. */
    private static final double FLOAT_CHANCE = 0.05, PLACER_CHANCE = 0.08;

    private static final ConcurrentHashMap<Long, Optional<Body>> BODIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<Seg>> PATHS = new ConcurrentHashMap<>();
    private static volatile ServerLevel cachedFor;

    /** Whether the bodies are laid in this world. */
    public static boolean active(ServerLevel world) {
        return GeyserConfig.ORE_OUTCROPS.get() && GeologyWorld.isOwn(world)
                && WorldgenRevision.has(WorldgenRevision.ORE_BODIES);
    }

    /** Drops what was worked out for a world that has stopped. */
    public static void clear() {
        BODIES.clear();
        PATHS.clear();
        cachedFor = null;
    }

    private static void forWorld(ServerLevel world) {
        if (cachedFor != world) {
            synchronized (BODIES) {
                if (cachedFor != world) {
                    BODIES.clear();
                    PATHS.clear();
                    cachedFor = world;
                }
            }
        }
        if (BODIES.size() > 40000) BODIES.clear();
        if (PATHS.size() > 4000) PATHS.clear();
    }

    private static double die(long h, int k) {
        return rand01(hash(h, k, 0, 0x5EEDL));
    }

    /** The body of a grid cell, if it holds one. Pure arithmetic over the seed and the plate model. */
    public static Body body(ServerLevel world, int cellX, int cellZ) {
        forWorld(world);
        long key = (((long) cellX) << 32) ^ (cellZ & 0xFFFFFFFFL);
        Optional<Body> known = BODIES.get(key);
        if (known != null) return known.orElse(null);
        Body b = make(world, cellX, cellZ);
        BODIES.put(key, Optional.ofNullable(b));
        return b;
    }

    /** The places of a cell weighed for its body's anchor. */
    private static final int TRIES = 4;
    /** How far apart the heights that tell how broken the ground is are read. */
    private static final int RELIEF_STEP = 12;

    /** The kind of body the rock under a point could hold, or null. */
    private static Kind kindAt(int x, int z) {
        Lithology.Column col = Lithology.column(TerrainContext.seed(), TerrainContext.params(), x, z);
        if (col == null) return null;
        if (col.setting() == Lithology.Setting.ARC && Lithology.hasPluton(col)) return Kind.PORPHYRY;
        if (col.setting() == Lithology.Setting.FOLD_BELT) return Kind.REEF;
        return null;
    }

    /** How broken the ground round a point is: the spread of the generator's heights a few blocks either way. */
    private static int relief(ServerLevel world, int x, int z, int ground) {
        int lo = ground, hi = ground;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) continue;
                int y = OreGenesis.anchorGroundAt(world, x + i * RELIEF_STEP, z + j * RELIEF_STEP);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }
        return hi - lo;
    }

    private static Body make(ServerLevel world, int cellX, int cellZ) {
        long h = hash(world.getSeed(), cellX, cellZ, 0x0C2C0L);
        if (die(h, 0) >= CHANCE) return null;
        // The cell's first place decides whether it holds a body and of what kind. Of the places in it where the same
        // rock lies, the body is set where the ground is most broken: where the land has worn deepest into it, and
        // where it shows on crags and gullies rather than under soil.
        int ax = cellX * CELL + 32 + (int) (die(h, 1) * (CELL - 64));
        int az = cellZ * CELL + 32 + (int) (die(h, 2) * (CELL - 64));
        Kind kind = kindAt(ax, az);
        if (kind == null) return null;
        int g = OreGenesis.anchorGroundAt(world, ax, az);
        if (g < world.getSeaLevel() + 12) return null;       // in the hills and mountains, not under the sea
        int best = relief(world, ax, az, g);
        for (int k = 1; k < TRIES; k++) {
            int x = cellX * CELL + 32 + (int) (die(h, 20 + 2 * k) * (CELL - 64));
            int z = cellZ * CELL + 32 + (int) (die(h, 21 + 2 * k) * (CELL - 64));
            if (kindAt(x, z) != kind) continue;
            int ground = OreGenesis.anchorGroundAt(world, x, z);
            if (ground < world.getSeaLevel() + 12) continue;
            int r = relief(world, x, z, ground);
            if (r > best) {
                best = r;
                ax = x;
                az = z;
                g = ground;
            }
        }
        if (kind == Kind.PORPHYRY) {
            double radius = 22.0 + die(h, 3) * 14.0;
            // Set so the top of the stock stands above its anchor's ground: the land worn down into it.
            int cy = g - (int) Math.round(radius * STRETCH * 0.55);
            return new Body(Kind.PORPHYRY, ax, az, g, h, radius, cy, 0, 0, 0, 0, 0);
        }
        PlateSample s = TectonicMap.sampleCached(world, ax, az);
        double sx = s.faultStrikeX(), sz = s.faultStrikeZ();
        if (Math.abs(sx) < 0.01 && Math.abs(sz) < 0.01) {
            double a = die(h, 3) * Math.PI;
            sx = Math.cos(a);
            sz = Math.sin(a);
        }
        double norm = Math.hypot(sx, sz);
        sx /= norm;
        sz /= norm;
        double turn = (die(h, 4) - 0.5) * 0.5;
        double vx = sx * Math.cos(turn) - sz * Math.sin(turn), vz = sx * Math.sin(turn) + sz * Math.cos(turn);
        int half = 60 + (int) (die(h, 5) * 50);
        double thick = 2.0 + die(h, 6) * 1.6;
        double lean = (die(h, 7) - 0.5) * 0.6;               // dips steeply, 70-90 degrees
        return new Body(Kind.REEF, ax, az, g, h, 0, 0, half, vx, vz, thick, lean);
    }

    /** The bodies whose anchors lie within {@code reach} of a point, nearest first. */
    public static List<Body> near(ServerLevel world, int x, int z, int reach) {
        List<Body> out = new ArrayList<>();
        for (int cx = Math.floorDiv(x - reach, CELL); cx <= Math.floorDiv(x + reach, CELL); cx++) {
            for (int cz = Math.floorDiv(z - reach, CELL); cz <= Math.floorDiv(z + reach, CELL); cz++) {
                Body b = body(world, cx, cz);
                if (b != null && Math.hypot(b.x - x, b.z - z) <= reach) out.add(b);
            }
        }
        out.sort((a, b) -> Double.compare(Math.hypot(a.x - x, a.z - z), Math.hypot(b.x - x, b.z - z)));
        return out;
    }

    /** The nearest body of a kind (or of either, for null), searched out to {@code reach}; null if none. */
    public static Body nearest(ServerLevel world, int x, int z, Kind kind, int reach) {
        Body best = null;
        double bestD = Double.MAX_VALUE;
        for (int ring = 0; ring * CELL <= reach + CELL; ring++) {
            int c0x = Math.floorDiv(x, CELL), c0z = Math.floorDiv(z, CELL);
            for (int cx = c0x - ring; cx <= c0x + ring; cx++) {
                for (int cz = c0z - ring; cz <= c0z + ring; cz++) {
                    if (Math.max(Math.abs(cx - c0x), Math.abs(cz - c0z)) != ring) continue;
                    Body b = body(world, cx, cz);
                    if (b == null || (kind != null && b.kind != kind)) continue;
                    double d = Math.hypot(b.x - x, b.z - z);
                    if (d < bestD && d <= reach) {
                        bestD = d;
                        best = b;
                    }
                }
            }
            // Every cell nearer than the best one found has been looked at.
            if (best != null && bestD < ring * CELL) break;
        }
        return best;
    }

    // === Generation ==========================================================

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        ServerLevel world = level.getLevel();
        if (!active(world)) return 0;
        Chunk c = new Chunk(level, cp);
        for (int cx = Math.floorDiv(c.x0 - BODY_REACH, CELL); cx <= Math.floorDiv(c.x0 + 15 + BODY_REACH, CELL); cx++) {
            for (int cz = Math.floorDiv(c.z0 - BODY_REACH, CELL); cz <= Math.floorDiv(c.z0 + 15 + BODY_REACH, CELL); cz++) {
                Body b = body(world, cx, cz);
                if (b == null || !c.reaches(b.x, b.z, b.reach())) continue;
                if (c.holds(b.x, b.z)) {
                    Diagnostics.info("Ore body: {} at {} {} {}, {}", b.kind == Kind.PORPHYRY ? "porphyry copper" : "gold quartz reef",
                            b.x, b.ground, b.z, b.kind == Kind.PORPHYRY ? (int) (b.radius * 2) + " blocks across"
                                    : b.half * 2 + " blocks long");
                }
                if (b.kind == Kind.PORPHYRY) porphyry(c, b);
                else reef(c, b);
            }
        }
        // Pieces fallen from the bodies onto the slopes round their feet.
        for (int cx = Math.floorDiv(c.x0 - BODY_REACH - SLOPE, CELL); cx <= Math.floorDiv(c.x0 + 15 + BODY_REACH + SLOPE, CELL); cx++) {
            for (int cz = Math.floorDiv(c.z0 - BODY_REACH - SLOPE, CELL); cz <= Math.floorDiv(c.z0 + 15 + BODY_REACH + SLOPE, CELL); cz++) {
                Body b = body(world, cx, cz);
                if (b == null || !c.reaches(b.x, b.z, b.reach() + SLOPE)) continue;
                slopes(c, b);
            }
        }
        int reach = FLOAT_REACH + BODY_REACH;
        for (int cx = Math.floorDiv(c.x0 - reach, CELL); cx <= Math.floorDiv(c.x0 + 15 + reach, CELL); cx++) {
            for (int cz = Math.floorDiv(c.z0 - reach, CELL); cz <= Math.floorDiv(c.z0 + 15 + reach, CELL); cz++) {
                Body b = body(world, cx, cz);
                if (b == null || !c.reaches(b.x, b.z, reach)) continue;
                carried(c, b, path(b));
            }
        }
        return c.placed;
    }

    // === Porphyry copper =======================================================

    /** Three sets of thin quartz veinlets cutting the stock, one every {@code spacing} blocks. */
    private static boolean veinlet(double[][] planes, double dx, double dy, double dz, double spacing) {
        for (double[] n : planes) {
            double d = (dx * n[0] + dy * n[1] + dz * n[2]) / spacing;
            if (Math.abs(d - Math.floor(d) - 0.5) * spacing < 0.5) return true;
        }
        return false;
    }

    private static void porphyry(Chunk c, Body b) {
        long h = b.h;
        double radius = b.radius;
        int ax = b.x, az = b.z, cy = b.cy;
        int lobes = (int) (h & 0x3FF), salt = (int) ((h >>> 20) & 0xFFFF);
        double[][] planes = new double[3][];
        for (int k = 0; k < 3; k++) {
            double a = die(h, 6 + k) * Math.PI, t = (die(h, 9 + k) - 0.5) * 1.2;
            planes[k] = new double[]{Math.cos(a) * Math.cos(t), Math.sin(t), Math.sin(a) * Math.cos(t)};
        }
        int ext = b.reach();
        for (int x = Math.max(c.x0, ax - ext); x <= Math.min(c.x0 + 15, ax + ext); x++) {
            for (int z = Math.max(c.z0, az - ext); z <= Math.min(c.z0 + 15, az + ext); z++) {
                double dx = x - ax, dz = z - az;
                // A lobed outline, not a circle.
                double a = Math.atan2(dz, dx);
                double rr = radius * (1.0 + 0.18 * Math.sin(3 * a + lobes) + 0.10 * noise(x + lobes, z, 18.0));
                double hd2 = dx * dx + dz * dz;
                if (hd2 > rr * rr * 1.1) continue;
                int top = c.ground(x, z);
                // How deep the rot of the surface goes here: five blocks, give or take.
                int capDepth = 4 + (int) Math.round(2.0 * (0.5 + 0.5 * noise(x - salt, z + salt, 11.0)));
                double yr = rr * STRETCH * 1.05;
                int y0 = Math.max(c.level.getMinBuildHeight() + 2, (int) Math.floor(cy - yr));
                int y1 = Math.min(top, (int) Math.ceil(cy + yr));
                for (int y = y0; y <= y1; y++) {
                    double dv = (y - cy) / STRETCH;
                    // Where it lies in the stock, with the zone boundaries wavy rather than shells.
                    double r = Math.sqrt(hd2 + dv * dv) / rr + 0.10 * noise3D(x + salt, y, z, 9.0, 9.0);
                    if (r > 1.0) continue;
                    double grain = grain(c.seed, x, y, z, 0x91);
                    double pod = noise3D(x - salt, y, z + salt, 4.5, 4.5);
                    // The rock itself, altered in zones by the fluids: potassic (pink) at the core, bleached
                    // quartz-sericite round it with its pyrite, greenish propylitic rock outside.
                    Block blk;
                    boolean copper = false;
                    if (r < 0.8 && veinlet(planes, dx, y - cy, dz, r < 0.45 ? 5.0 : 8.0)) {
                        boolean cpy = r < 0.45 && grain < 0.3;
                        blk = cpy ? ModBlocks.CHALCOPYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
                        copper = cpy;
                    } else if (r < 0.45) {
                        // The ore: the porphyry itself with chalcopyrite through it, richest in the middle.
                        boolean ore = pod > -0.15 + 0.35 * (r / 0.45);
                        blk = ore ? ModBlocks.COPPER_PORPHYRY.get() : Blocks.GRANITE;
                        copper = ore;
                    } else if (r < 0.78) {
                        blk = pod > 0.15 ? ModBlocks.PYRITE_ROCK.get() : grain < 0.12 ? ModBlocks.COPPER_PORPHYRY.get() : Blocks.CALCITE;
                        copper = blk == ModBlocks.COPPER_PORPHYRY.get();
                    } else {
                        blk = pod > 0.7 ? ModBlocks.PYRITE_ROCK.get() : Blocks.TUFF;
                    }
                    // Near the surface the sulfides have rotted: the iron to a rusty gossan, the copper to green malachite
                    // and blue azurite where it was, so the stain comes in patches through the rust.
                    if (top - y < capDepth && r < 0.92 && blk != ModBlocks.QUARTZ_VEIN.get()) {
                        if (copper && r < 0.6) {
                            blk = noise3D(x + 97, y, z - 97, 3.0, 3.0) > 0.0 ? ModBlocks.MALACHITE_ROCK.get() : ModBlocks.AZURITE_ROCK.get();
                        } else {
                            double n = noise3D(x - 41, y, z + 41, 5.0, 3.0);
                            blk = n < -0.2 ? ModBlocks.GOSSAN_BROWN.get() : n < 0.25 ? ModBlocks.GOSSAN_ORANGE.get() : ModBlocks.GOSSAN_YELLOW.get();
                        }
                    }
                    c.set(x, y, z, blk);
                }
            }
        }
    }

    // === Gold-bearing quartz reef ==============================================

    private static void reef(Chunk c, Body b) {
        int ax = b.x, az = b.z, g = b.ground, half = b.half;
        double vx = b.vx, vz = b.vz, thick = b.thick, lean = b.lean;
        int yTop = g + 70, yBottom = g - 80;
        int shoots = (int) (b.h & 0xFFF);
        int ext = b.reach();
        for (int x = Math.max(c.x0, ax - ext); x <= Math.min(c.x0 + 15, ax + ext); x++) {
            for (int z = Math.max(c.z0, az - ext); z <= Math.min(c.z0 + 15, az + ext); z++) {
                double along = (x - ax) * vx + (z - az) * vz;
                if (Math.abs(along) > half) continue;
                double across = -(x - ax) * vz + (z - az) * vx;
                // It pinches out towards its ends, and swells and pinches along its length.
                double taper = Math.min(1.0, (half - Math.abs(along)) / 18.0);
                double t = thick * taper * (0.8 + 0.4 * (0.5 + 0.5 * noise((int) along + shoots, 0, 14.0)));
                if (t < 0.8) continue;
                double wave = 2.5 * Math.sin(along * 0.05 + shoots);
                int top = c.ground(x, z);
                for (int y = Math.max(yBottom, c.level.getMinBuildHeight() + 2); y <= Math.min(yTop, top); y++) {
                    double off = Math.abs(across - lean * (y - g) - wave);
                    if (off > t / 2 + 1.5) continue;
                    double grain = grain(c.seed, x, y, z, 0x60);
                    // Gold in shoots plunging down the reef, and in them in clusters.
                    boolean shoot = noise((int) Math.round(along / 3) + shoots, (int) Math.round(y / 3.0), 6.0) > 0.35;
                    Block blk;
                    if (off > t / 2) {
                        // The wall rock beside it: specked with gold where a shoot passes, pale carbonate elsewhere, patchy.
                        BlockState host = c.level.getBlockState(c.m.set(x, y, z));
                        if (shoot && grain < 0.45 && host.is(ModBlocks.GNEISS.get())) blk = ModBlocks.GOLD_GNEISS.get();
                        else if (shoot && grain < 0.45 && host.is(ModBlocks.SCHIST.get())) blk = ModBlocks.GOLD_SCHIST.get();
                        else if (grain < 0.55) blk = Blocks.CALCITE;
                        else continue;
                    } else if (shoot && noise3D(x + shoots, y, z, 2.5, 2.5) > 0.05) {
                        blk = ModBlocks.GOLD_QUARTZ.get();
                    } else {
                        blk = grain > 0.93 ? ModBlocks.PYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
                    }
                    c.set(x, y, z, blk);
                }
            }
        }
    }

    // === Float and placer ======================================================

    /**
     * The streams below a body, a length at a time, as far down as its pieces are carried: those that pass by or
     * through the body, followed down the network. Worked out once a body.
     */
    public static List<Seg> path(Body b) {
        long key = (((long) b.x) << 32) ^ (b.z & 0xFFFFFFFFL);
        List<Seg> known = PATHS.get(key);
        if (known != null) return known;
        List<Seg> out = new ArrayList<>();
        if (RiverNetwork.ready()) {
            // Water off the body's slopes reaches the streams within a walk of it; pieces start their way from its edge.
            int r = b.reach() + SHED;
            int box = r + 64;
            Map<RiverNetwork.Point, float[]> best = new IdentityHashMap<>();
            for (RiverNetwork.Point s : RiverNetwork.lengthsStartingIn(b.x - box, b.z - box, b.x + box, b.z + box)) {
                double off = distToSeg(b.x, b.z, s.x(), s.z(), s.ex(), s.ez());
                if (off > r) continue;
                if (s.water() > b.ground) continue;               // the stream has to be lower than the body's top
                float d = (float) Math.max(0, off - b.reach());
                RiverNetwork.Point p = s;
                for (int step = 0; p != null && d < FLOAT_REACH && step < 200; step++) {
                    float len = (float) Math.hypot(p.ex() - p.x(), p.ez() - p.z());
                    float[] have = best.get(p);
                    if (have != null && have[0] <= d) break;      // reached by a shorter way already: the rest is known
                    best.put(p, new float[]{d});
                    d += len;
                    p = RiverNetwork.downstream(p);
                }
            }
            for (Map.Entry<RiverNetwork.Point, float[]> e : best.entrySet()) {
                RiverNetwork.Point p = e.getKey();
                float d0 = e.getValue()[0], len = (float) Math.hypot(p.ex() - p.x(), p.ez() - p.z());
                out.add(new Seg(p.x(), p.z(), p.ex(), p.ez(), d0, d0 + len, p.halfWidth(), p.water()));
            }
        }
        PATHS.put(key, out);
        return out;
    }

    /**
     * Pieces broken off a body and fallen down the slopes round it: on the ground below it, within a walk of its edge,
     * fewer the farther. The first sign of a body where no stream runs near.
     */
    private static void slopes(Chunk c, Body b) {
        int reach = b.reach();
        Block piece = b.kind == Kind.REEF ? ModBlocks.GOLD_FLOAT.get() : ModBlocks.COPPER_FLOAT.get();
        for (int x = c.x0; x <= c.x0 + 15; x++) {
            for (int z = c.z0; z <= c.z0 + 15; z++) {
                double out = edgeDistance(b, x, z);
                if (out <= 0 || out > SLOPE) continue;
                double near = 1.0 - out / SLOPE;
                if (grain(c.seed, x, 0, z, 0xF3) > SLOPE_CHANCE * near * near) continue;
                int top = c.ground(x, z);
                if (top > b.ground - 4) continue;               // below the body, where what falls off it comes to rest
                c.lay(x, top, z, piece);
            }
        }
    }

    /** How far outside a body a column lies, from its edge on the ground; 0 or less inside it. */
    public static double edgeDistance(Body b, int x, int z) {
        if (b.kind == Kind.PORPHYRY) return Math.hypot(x - b.x, z - b.z) - b.radius * 1.15;
        double along = (x - b.x) * b.vx + (z - b.z) * b.vz, across = -(x - b.x) * b.vz + (z - b.z) * b.vx;
        double beyond = Math.max(0, Math.abs(along) - b.half);
        return Math.hypot(beyond, Math.max(0, Math.abs(across) - b.thick));
    }

    /** Float on the banks and bars of the streams below a body, and gold in the gravel of their beds below a reef. */
    private static void carried(Chunk c, Body b, List<Seg> segs) {
        if (segs.isEmpty()) return;
        boolean gold = b.kind == Kind.REEF;
        for (Seg s : segs) {
            float margin = s.half + 5;
            if (Math.max(s.x, s.ex) + margin < c.x0 || Math.min(s.x, s.ex) - margin > c.x0 + 15) continue;
            if (Math.max(s.z, s.ez) + margin < c.z0 || Math.min(s.z, s.ez) - margin > c.z0 + 15) continue;
            double lx = s.ex - s.x, lz = s.ez - s.z, len2 = lx * lx + lz * lz;
            for (int x = c.x0; x <= c.x0 + 15; x++) {
                for (int z = c.z0; z <= c.z0 + 15; z++) {
                    double px = x + 0.5 - s.x, pz = z + 0.5 - s.z;
                    double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, (px * lx + pz * lz) / len2));
                    double across = Math.hypot(px - t * lx, pz - t * lz);
                    if (across > margin) continue;
                    double along = s.d0 + t * (s.d1 - s.d0);
                    if (along >= FLOAT_REACH) continue;
                    double near = 1.0 - along / FLOAT_REACH;
                    int top = c.ground(x, z);
                    BlockPos bed = new BlockPos(x, top, z);
                    boolean wet = c.level.getFluidState(bed.above()).is(FluidTags.WATER);
                    if (wet) {
                        if (!gold || across > s.half + 0.5) continue;
                        BlockState st = c.level.getBlockState(bed);
                        if (!(st.is(Blocks.GRAVEL) || st.is(BlockTags.SAND) || st.is(Blocks.CLAY) || st.is(BlockTags.DIRT)
                                || OreGenesis.isHostRock(st))) continue;
                        if (grain(c.seed, x, top, z, 0xF1) > PLACER_CHANCE * near) continue;
                        c.level.setBlock(bed, ModBlocks.GOLD_GRAVEL.get().defaultBlockState(), Block.UPDATE_CLIENTS);
                        c.placed++;
                    } else {
                        // A bank or a bar: by the water and hardly higher than it.
                        if (across < s.half - 0.5 || top > s.water + 2.5 || top < s.water - 2.0) continue;
                        if (grain(c.seed, x, top, z, 0xF2) > FLOAT_CHANCE * near * near) continue;
                        c.lay(x, top, z, gold ? ModBlocks.GOLD_FLOAT.get() : ModBlocks.COPPER_FLOAT.get());
                    }
                }
            }
        }
    }

    /** The shortest way from a point to a length of stream, and how far down the stream that nearest spot is from the body. */
    public static double[] alongPath(List<Seg> segs, double x, double z) {
        double bestAcross = Double.MAX_VALUE, bestAlong = 0;
        for (Seg s : segs) {
            double lx = s.ex - s.x, lz = s.ez - s.z, len2 = lx * lx + lz * lz;
            double px = x - s.x, pz = z - s.z;
            double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, (px * lx + pz * lz) / len2));
            double across = Math.hypot(px - t * lx, pz - t * lz);
            if (across < bestAcross) {
                bestAcross = across;
                bestAlong = s.d0 + t * (s.d1 - s.d0);
            }
        }
        return new double[]{bestAcross, bestAlong};
    }

    private static double distToSeg(double x, double z, double ax, double az, double bx, double bz) {
        double lx = bx - ax, lz = bz - az, len2 = lx * lx + lz * lz;
        double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((x - ax) * lx + (z - az) * lz) / len2));
        return Math.hypot(x - ax - t * lx, z - az - t * lz);
    }

    private static double grain(long seed, int x, int y, int z, int salt) {
        long k = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0x632BE59BD9B4E019L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ salt;
        return rand01(com.jeladastudios.ftsgeology.util.SeedHash.mix(k));
    }

    /** One chunk's writing. */
    private static final class Chunk {
        final WorldGenLevel level;
        final long seed;
        final int x0, z0;
        final int[] ground = new int[256];
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int placed;

        Chunk(WorldGenLevel level, ChunkPos cp) {
            this.level = level;
            this.seed = level.getSeed();
            this.x0 = cp.getMinBlockX();
            this.z0 = cp.getMinBlockZ();
            java.util.Arrays.fill(ground, Integer.MIN_VALUE);
        }

        boolean holds(int x, int z) {
            return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
        }

        /** Whether something reaching {@code r} round a point can touch this chunk. */
        boolean reaches(int x, int z, int r) {
            return x + r >= x0 && x - r <= x0 + 15 && z + r >= z0 && z - r <= z0 + 15;
        }

        /** The top of the ground in this chunk as it stands now (rock, soil or a stream's bed). */
        int ground(int x, int z) {
            int i = ((x - x0) << 4) | (z - z0);
            if (ground[i] == Integer.MIN_VALUE) ground[i] = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
            return ground[i];
        }

        /** A piece laid on the ground at a column: on firm dry ground, in the open or in low plants. */
        void lay(int x, int top, int z, Block piece) {
            BlockPos bed = new BlockPos(x, top, z), on = bed.above();
            BlockState there = level.getBlockState(on);
            if (!there.isAir() && !(TerrainProbe.isVegetation(there) && there.canBeReplaced())) return;
            if (!level.getFluidState(on).isEmpty() || !level.getFluidState(bed).isEmpty()) return;
            if (!level.getBlockState(bed).isFaceSturdy(level, bed, net.minecraft.core.Direction.UP)) return;
            level.setBlock(on, piece.defaultBlockState(), Block.UPDATE_CLIENTS);
            placed++;
        }

        void set(int x, int y, int z, Block b) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.hasBlockEntity() || !OreGenesis.isHostRock(s)) return;
            level.setBlock(p, TfcCompat.translate(level, p, b.defaultBlockState()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }
    }
}
