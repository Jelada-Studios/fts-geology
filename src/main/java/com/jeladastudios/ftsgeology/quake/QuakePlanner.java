package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Works out what an earthquake does to the ground, in three stages.
 *
 * <ol>
 *   <li>{@link #traceFault} follows the plate boundary out from the epicentre, re-reading the strike
 *       as it goes, and stops where the boundary ends or changes kind. Pure maths over the seed.</li>
 *   <li>{@link #snapshot} copies only the corridor along that trace, on the server thread.</li>
 *   <li>{@link #plan} turns the snapshot into an edit list on a worker thread; {@link Earthquake}
 *       applies it a slice per tick.</li>
 * </ol>
 *
 * <p>The corridor is enumerated on the integer block lattice ({@link #forEachCorridorColumn}).
 * Stepping along and across the fault and rounding misses up to a fifth of the columns on a
 * diagonal fault, and the missed columns stand up as pillars.</p>
 *
 * <p>By boundary: a graben with an axial fissure at a rift, an asymmetric trench and arc at
 * subduction, one lopsided range with a foreland basin at collision, and ground carried along the
 * strike at a transform.</p>
 */
public final class QuakePlanner {

    private QuakePlanner() {}

    /** One block change queued by a quake. */
    public record Edit(BlockPos pos, BlockState state) {}

    /** A point on the rupture, carrying the local fault direction and how much it slipped. */
    public record TracePoint(int x, int z, double strikeX, double strikeZ, double slip) {}

    /** A fully planned earthquake, ready to be applied on the server thread. */
    public record Plan(BlockPos epicentre, FaultType type, double magnitude,
                       double depthMetres, int ruptureLength, List<Edit> edits) {}

    /** How far apart the fault is re-sampled while tracing; between these the strike is lerped. */
    private static final int TRACE_STEP = 8;

    /** Ceiling on how many columns one quake may copy, so a giant event cannot stall the tick. */
    private static final int MAX_SNAPSHOT_COLUMNS = 400_000;

    /** Deepest stack any column ever captures. Also the deepest anything may be carved. */
    private static final int MAX_CAPTURE_DEPTH = 29;

    // === Stage 1: follow the fault ==========================================

    /**
     * Rupture length from magnitude, using the standard surface-rupture scaling
     * {@code log10(L km) = 0.69 M - 3.22}.
     *
     * <p>Kilometres become blocks through the <b>horizontal</b> depth scale, not the vertical one:
     * the world is squashed vertically but not horizontally.</p>
     */
    public static int ruptureLengthBlocks(double magnitude) {
        double km = Math.pow(10.0, 0.69 * magnitude - 3.22);
        int blocks = (int) Math.round(km * 1000.0 / DepthScale.metresPerBlockHorizontal());
        return Mth.clamp(blocks, 24, GeyserConfig.QUAKE_MAX_RUPTURE.get());
    }

    /**
     * The core slipped band right at the fault. This is the seed the wider landforms are scaled
     * from; it is NOT how far the quake reaches - see {@link #deformationHalfWidth}.
     */
    public static int ruptureHalfWidth(double magnitude) {
        return Mth.clamp((int) Math.round(1 + magnitude * 0.9), 2, 14);
    }

    /**
     * How far either side of the fault this style of quake actually moves ground. Every deformation
     * profile below is scaled from the same numbers this uses, and {@link #snapshot} sizes the
     * corridor from it, so the corridor can never be narrower than the landform being built.
     */
    public static int deformationHalfWidth(FaultType type, double magnitude) {
        int core = ruptureHalfWidth(magnitude);
        return switch (type) {
            case DIVERGENT -> {
                int floor = grabenHalfFloor(magnitude, 1.0);
                yield floor + riftShoulderReach(floor) + 4;
            }
            case TRANSFORM -> strikeSlipHalfWidth(magnitude) + strikeSlipOffset(magnitude, 1.0) + 4;
            case CONVERGENT_SUBDUCTION ->
                    Math.max(arcHalfWidth(core), (int) Math.round(trenchReach(core) * OUTER_RISE_END)) + 4;
            case CONVERGENT_COLLISION -> beltHalfWidth(core) + 4;
            case INTERIOR -> 0;
        };
    }

    /**
     * Walks the boundary out from the epicentre in both directions, following its curve. Stops early
     * where the fault stops being the same kind of fault, or fades out - so a rupture naturally ends
     * at a triple junction instead of ploughing on across a neighbouring plate.
     */
    public static List<TracePoint> traceFault(ServerLevel level, BlockPos epicentre,
                                              FaultType type, double magnitude,
                                              double seedStrikeX, double seedStrikeZ,
                                              boolean forced) {
        int halfLength = Math.max(8, ruptureLengthBlocks(magnitude) / 2);
        List<TracePoint> forward = walk(level, epicentre, type, seedStrikeX, seedStrikeZ, halfLength, 1, forced);
        List<TracePoint> backward = walk(level, epicentre, type, seedStrikeX, seedStrikeZ, halfLength, -1, forced);

        List<TracePoint> all = new ArrayList<>(backward.size() + forward.size());
        // The backward half runs away from the epicentre: reverse it, and drop its first point,
        // which the forward half also has.
        for (int i = backward.size() - 1; i >= 1; i--) all.add(backward.get(i));
        all.addAll(forward);

        // Point each strike at the next point: the corridor is laid out forward from each point.
        for (int i = 0; i < all.size() - 1; i++) {
            TracePoint a = all.get(i), b = all.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double l = Math.sqrt(dx * dx + dz * dz);
            if (l < 1.0e-6) continue;
            all.set(i, new TracePoint(a.x(), a.z(), dx / l, dz / l, a.slip()));
        }
        return all;
    }

    /**
     * One direction of the walk. {@code sign} is +1 along the strike or -1 against it.
     *
     * <p>The strike stored on each point is the direction the segment actually RUNS, sign included,
     * because {@link #forEachCorridorColumn} lays its corridor out forward from the point.</p>
     */
    private static List<TracePoint> walk(ServerLevel level, BlockPos start, FaultType type,
                                         double strikeX, double strikeZ, int halfLength, int sign,
                                         boolean forced) {
        List<TracePoint> pts = new ArrayList<>();
        double x = start.getX(), z = start.getZ();
        double sx = strikeX, sz = strikeZ;
        double len = Math.sqrt(sx * sx + sz * sz);
        if (len < 1.0e-6) { sx = 1; sz = 0; } else { sx /= len; sz /= len; }

        for (int travelled = 0; travelled <= halfLength; travelled += TRACE_STEP) {
            // Slip tapers to nothing at the ends of the rupture, as real slip does.
            double slip = 1.0 - (travelled / (double) (halfLength + 1));
            pts.add(new TracePoint((int) Math.round(x), (int) Math.round(z),
                    sx * sign, sz * sign, Math.max(0.0, slip)));

            // Step forward, then re-read the fault so the next segment follows its curve.
            x += sx * TRACE_STEP * sign;
            z += sz * TRACE_STEP * sign;
            PlateSample s = TectonicMap.sample(level, (int) Math.round(x), (int) Math.round(z));
            // A natural rupture ends where the boundary changes kind. A forced one (the command)
            // keeps going so a style can be shown anywhere.
            if (!forced && (s.faultType() != type || s.stress() <= 0.02)) break;
            if (forced && s.faultType() != type) continue;   // keep the current strike and carry on

            double nsx = s.faultStrikeX(), nsz = s.faultStrikeZ();
            // The strike is a line, not an arrow: flip it if it points back the way we came.
            if (nsx * sx + nsz * sz < 0) { nsx = -nsx; nsz = -nsz; }
            double nlen = Math.sqrt(nsx * nsx + nsz * nsz);
            if (nlen > 1.0e-6) { sx = nsx / nlen; sz = nsz / nlen; }
        }
        return pts;
    }

    // === The lattice walk ===================================================

    /** Called once for every integer column of the corridor. */
    @FunctionalInterface
    private interface ColumnVisitor {
        /**
         * @param x       column X
         * @param z       column Z
         * @param across  signed perpendicular offset from the fault line, in blocks
         * @param strikeX local strike direction, X part
         * @param strikeZ local strike direction, Z part
         * @param slip    local slip, 0 at the ends of the rupture and 1 at the epicentre
         */
        void visit(int x, int z, double across, double strikeX, double strikeZ, double slip);
    }

    /**
     * Visits every integer column of one trace segment's corridor exactly once.
     *
     * <p>Membership is a capsule around the segment, so consecutive segments leave no gaps on a bend.
     * The coordinate handed on is the perpendicular offset from the fault line, never the capsule
     * distance: past a segment's end that distance points at the endpoint and turns the rupture into
     * a bullseye around each trace point.</p>
     *
     * @param bodyOnly first pass: only columns squarely alongside this segment, so each gets its own
     *                 segment's slip. The second pass fills the joints.
     */
    private static void forEachCorridorColumn(TracePoint tp, TracePoint next, int band,
                                              boolean bodyOnly, ColumnVisitor v) {
        double sx = tp.strikeX(), sz = tp.strikeZ();
        double len = Math.sqrt(sx * sx + sz * sz);
        if (len < 1.0e-6) return;
        sx /= len; sz /= len;
        double nx = -sz, nz = sx;

        double ax = tp.x(), az = tp.z();
        double bx = ax + sx * TRACE_STEP, bz = az + sz * TRACE_STEP;

        int loX = (int) Math.floor(Math.min(ax, bx)) - band - 2;
        int hiX = (int) Math.ceil(Math.max(ax, bx)) + band + 2;
        int loZ = (int) Math.floor(Math.min(az, bz)) - band - 2;
        int hiZ = (int) Math.ceil(Math.max(az, bz)) + band + 2;

        for (int x = loX; x <= hiX; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                double dx = x - ax, dz = z - az;
                double along = dx * sx + dz * sz;
                if (bodyOnly && (along < 0.0 || along >= TRACE_STEP)) continue;

                // Membership: distance to the segment, ends included.
                double clamped = Mth.clamp(along, 0.0, TRACE_STEP);
                double px = ax + sx * clamped, pz = az + sz * clamped;
                double d2 = (x - px) * (x - px) + (z - pz) * (z - pz);
                if (d2 > (double) band * band) continue;

                // Coordinate: the perpendicular offset. Measured against the segment's own line, so
                // it is the real distance across the fault wherever the column happens to sit.
                double across = dx * nx + dz * nz;
                if (Math.abs(across) > band) continue;

                double f = Mth.clamp(along / TRACE_STEP, 0.0, 1.0);
                double slip = tp.slip();
                double lsx = sx, lsz = sz;
                if (next != null) {
                    slip = Mth.lerp(f, tp.slip(), next.slip());
                    lsx = Mth.lerp(f, tp.strikeX(), next.strikeX());
                    lsz = Mth.lerp(f, tp.strikeZ(), next.strikeZ());
                    double l = Math.sqrt(lsx * lsx + lsz * lsz);
                    if (l > 1.0e-6) { lsx /= l; lsz /= l; } else { lsx = sx; lsz = sz; }
                }
                v.visit(x, z, across, lsx, lsz, slip);
            }
        }
    }

    // === Stage 2: snapshot the corridor =====================================

    /** The slice of world the planner may look at, captured on the server thread. */
    public static final class Snapshot {
        /**
         * One column. The stack is sized per column rather than globally: only the strip that will
         * actually be dug deep - a trench floor, a rift fissure - needs twenty blocks of history.
         */
        private record Column(int groundY, boolean submerged, boolean generated, BlockState[] stack) {}

        // fastutil (already shipped with Minecraft) so the millions of lookups a large rupture
        // makes do not each allocate a boxed Long.
        private final Long2ObjectMap<Column> columns = new Long2ObjectOpenHashMap<>();

        private static long key(int x, int z) {
            return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
        }

        public boolean has(int x, int z) { return columns.containsKey(key(x, z)); }

        /** How many columns were actually captured - logged so a slow snapshot is visible. */
        public int size() { return columns.size(); }

        /** Y of the topmost real ground block, or {@link Integer#MIN_VALUE} outside the corridor. */
        public int groundAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c == null ? Integer.MIN_VALUE : c.groundY();
        }

        /** Block {@code d} steps below the ground of this column, or null if outside the capture. */
        public BlockState stateAt(int x, int z, int d) {
            Column c = columns.get(key(x, z));
            if (c == null || d < 0 || d >= c.stack().length) return null;
            return c.stack()[d];
        }

        /** True when open water stood over this column before the quake touched it. */
        public boolean submergedAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c != null && c.submerged();
        }

        /**
         * True when this column stands inside a structure the world generated, such as a village.
         * Blocks cannot tell a village from a build and the structure manager is not available on
         * the worker thread, so the answer is recorded while the snapshot is taken.
         */
        public boolean generatedAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c != null && c.generated();
        }

        /** How deep this column was captured; a deformation may not carve past it. */
        public int depthAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c == null ? 0 : c.stack().length;
        }
    }

    /**
     * Copies only the columns the rupture can actually touch. Unloaded columns are simply left out,
     * so a quake never forces chunk loading and never edits terrain nobody has generated.
     */
    public static Snapshot snapshot(ServerLevel level, List<TracePoint> trace, FaultType type,
                                    double magnitude) {
        return snapshot(level, trace, type, magnitude, null);
    }

    /**
     * As above, but optionally clipped to a single chunk. Replaying a parked rupture when one chunk
     * loads only needs that chunk plus a small margin.
     */
    public static Snapshot snapshot(ServerLevel level, List<TracePoint> trace, FaultType type,
                                    double magnitude, ChunkPos clip) {
        Snapshot snap = new Snapshot();
        int band = deformationHalfWidth(type, magnitude);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int i = 0; i < trace.size(); i++) {
            if (snap.size() >= MAX_SNAPSHOT_COLUMNS) break;
            TracePoint tp = trace.get(i);
            TracePoint next = i + 1 < trace.size() ? trace.get(i + 1) : null;
            forEachCorridorColumn(tp, next, band, false, (cx, cz, across, lsx, lsz, slip) -> {
                if (clip != null && (cx < clip.getMinBlockX() - 10 || cx > clip.getMaxBlockX() + 10
                        || cz < clip.getMinBlockZ() - 10 || cz > clip.getMaxBlockZ() + 10)) return;
                if (snap.has(cx, cz)) return;
                if (!level.hasChunkAt(new BlockPos(cx, 0, cz))) return;

                int g = TerrainProbe.groundY(level, cx, cz);
                if (g == Integer.MIN_VALUE) return;
                // Recorded before anything is dug: a rift that opens under the sea builds new crust on
                // its floor, and afterwards there is no way to tell it was ever under water.
                boolean wet = !level.getBlockState(m.set(cx, g + 1, cz)).getFluidState().isEmpty();
                int need = captureDepth(type, magnitude, across);
                BlockState[] stack = new BlockState[need];
                for (int d = 0; d < need; d++) {
                    int y = g - d;
                    m.set(cx, y, cz);
                    stack[d] = y < level.getMinBuildHeight()
                            ? Blocks.BEDROCK.defaultBlockState()
                            : level.getBlockState(m);
                }
                // Asked only where it can matter: a column of plain rock is never in a village, and
                // the structure lookup is far more expensive than the block reads above it.
                boolean generated = GeyserConfig.QUAKES_BREAK_STRUCTURES.get()
                        && EruptionHandler.isPlayerPlaced(stack[0])
                        && insideGeneratedStructure(level, cx, g, cz);
                snap.columns.put(Snapshot.key(cx, cz), new Snapshot.Column(g, wet, generated, stack));
            });
        }
        return snap;
    }

    /** How many blocks of history a column needs: the full stack only where this style digs deep. */
    private static int captureDepth(FaultType type, double magnitude, double across) {
        int core = ruptureHalfWidth(magnitude);
        int shallow = 4;
        return switch (type) {
            case DIVERGENT -> Math.abs(across) <= grabenHalfFloor(magnitude, 1.0) + 1
                    ? Math.min(MAX_CAPTURE_DEPTH, GeyserConfig.QUAKE_MAX_FISSURE_DEPTH.get() + 3)
                    : shallow;
            // Only the down-going side is excavated, and only inside the trench basin.
            case CONVERGENT_SUBDUCTION -> (across < 0 && -across <= trenchReach(core))
                    ? Math.min(MAX_CAPTURE_DEPTH, maxTrenchDepth(magnitude) + 3)
                    : shallow;
            // Fold valleys are modest; the ridges only ever stack upward.
            case CONVERGENT_COLLISION -> 7;
            // Six blocks of carried height difference plus the mole track.
            case TRANSFORM -> 9;
            case INTERIOR -> shallow;
        };
    }

    // === Stage 3: plan the edits ============================================

    /**
     * Turns a trace and snapshot into an ordered edit list. Pure computation, safe on a worker thread.
     *
     * <p>Segments are visited outward from the epicentre, so the apply loop touches a few chunks at a
     * time and a quake cut short by the edit cap loses its ends rather than gaining holes. Each column
     * is claimed once and deformed as a whole.</p>
     */
    public static Plan plan(Snapshot snap, List<TracePoint> trace, BlockPos epicentre, FaultType type,
                            double magnitude, double depthMetres, RandomGenerator rng,
                            boolean mayBreakBuilds) {
        int cap = GeyserConfig.QUAKE_MAX_EDITS.get();
        int band = deformationHalfWidth(type, magnitude);

        // Phase one: decide what every column does. The order edits go out in is decided below.
        List<ColumnPlan> columns = new ArrayList<>();
        LongOpenHashSet claimed = new LongOpenHashSet();

        // Where along the trace the epicentre sits; the walk radiates from here.
        int mid = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < trace.size(); i++) {
            double dx = trace.get(i).x() - epicentre.getX();
            double dz = trace.get(i).z() - epicentre.getZ();
            double d = dx * dx + dz * dz;
            if (d < best) { best = d; mid = i; }
        }

        int length = 0;
        // Two passes: first the columns alongside each segment, so each gets its own slip, then the
        // joint discs on bends.
        for (int pass = 0; pass < 2; pass++) {
            boolean bodyOnly = pass == 0;
            for (int step = 0; step < trace.size(); step++) {
                // mid, mid-1, mid+1, mid-2, mid+2, ... so both halves are laid out together.
                int offset = (step + 1) / 2;
                int i = (step % 2 == 0) ? mid - offset : mid + offset;
                if (i < 0 || i >= trace.size()) continue;

                TracePoint tp = trace.get(i);
                TracePoint next = i + 1 < trace.size() ? trace.get(i + 1) : null;
                if (bodyOnly) length += TRACE_STEP;

                forEachCorridorColumn(tp, next, band, bodyOnly, (x, z, across, lsx, lsz, slip) -> {
                    if (!claimed.add(Snapshot.key(x, z))) return;
                    if (!snap.has(x, z)) return;
                    ColumnPlan cp = columnPlan(snap, type, x, z, across, lsx, lsz, slip,
                            magnitude, rng, mayBreakBuilds);
                    if (cp != null) columns.add(cp);
                });
            }
        }

        // Phase two: turn those into edits, one BLOCK OF MOVEMENT at a time across the whole fault.
        List<Edit> ordered = new ArrayList<>();
        int maxSteps = 0;
        for (ColumnPlan c : columns) maxSteps = Math.max(maxSteps, c.steps());

        if (GeyserConfig.QUAKE_LAYERED.get()) {
            for (int layer = 1; layer <= maxSteps && ordered.size() < cap; layer++) {
                interleave(columns, layer, ordered, cap);
            }
        } else {
            for (ColumnPlan c : columns) {
                if (ordered.size() >= cap) break;
                for (int layer = 1; layer <= c.steps(); layer++) emitLayer(c, layer, ordered);
            }
        }
        return new Plan(epicentre, type, magnitude, depthMetres, length, List.copyOf(ordered));
    }

    /** What one column of ground is going to do, held until the edits are emitted. */
    private record ColumnPlan(int x, int z, int top, int delta, BlockState cap, BlockState fill) {
        /** How many one-block steps of movement this column goes through. */
        int steps() { return Math.max(1, Math.abs(delta)); }
    }

    /** How many places along the fault are visibly moving at the same time. */
    private static final int ACTIVE_FRONTS = 48;

    /** How many columns one front advances before the turn passes to the next. */
    private static final int FRONT_SLICE = 24;

    /**
     * Emits one layer of movement across the whole rupture at once, in {@link #ACTIVE_FRONTS} fronts
     * taking short turns. The fault moves together along its length, while each tick still rebuilds
     * only a handful of chunk meshes.
     */
    private static void interleave(List<ColumnPlan> columns, int layer, List<Edit> out, int cap) {
        int n = columns.size();
        if (n == 0) return;
        int fronts = Mth.clamp(n / 500, 1, ACTIVE_FRONTS);
        int per = (n + fronts - 1) / fronts;
        for (int offset = 0; offset < per && out.size() < cap; offset += FRONT_SLICE) {
            for (int g = 0; g < fronts && out.size() < cap; g++) {
                int start = g * per + offset;
                int end = Math.min(start + FRONT_SLICE, Math.min((g + 1) * per, n));
                for (int i = start; i < end && out.size() < cap; i++) {
                    emitLayer(columns.get(i), layer, out);
                }
            }
        }
    }

    /** One block of movement for one column. */
    private static void emitLayer(ColumnPlan c, int layer, List<Edit> out) {
        if (c.delta() > 0) {
            if (layer > c.delta()) return;
            out.add(new Edit(new BlockPos(c.x(), c.top() + layer, c.z()),
                    layer == c.delta() ? c.cap() : c.fill()));
        } else if (c.delta() < 0) {
            int cut = -c.delta();
            if (layer > cut) return;
            out.add(new Edit(new BlockPos(c.x(), c.top() - (layer - 1), c.z()),
                    Blocks.AIR.defaultBlockState()));
            if (layer == 1) {
                // The plant cover standing over the column comes off with the first slice, so
                // nothing is ever left hanging over a subsiding floor.
                out.add(new Edit(new BlockPos(c.x(), c.top() + 1, c.z()), Blocks.AIR.defaultBlockState()));
                out.add(new Edit(new BlockPos(c.x(), c.top() + 2, c.z()), Blocks.AIR.defaultBlockState()));
            }
            if (layer == cut && c.cap() != null) {
                // Strike-slip: the carried ground cover arrives on the new surface.
                out.add(new Edit(new BlockPos(c.x(), c.top() - cut, c.z()), c.cap()));
            }
        } else if (layer == 1 && c.cap() != null) {
            out.add(new Edit(new BlockPos(c.x(), c.top(), c.z()), c.cap()));
        }
    }

    /** Works out what a single column does, without touching the edit list. */
    private static ColumnPlan columnPlan(Snapshot snap, FaultType type, int x, int z, double across,
                                         double sx, double sz, double slip, double magnitude,
                                         RandomGenerator rng, boolean mayBreakBuilds) {
        if (type == FaultType.TRANSFORM) {
            return strikeSlipPlan(snap, x, z, across, sx, sz, slip, magnitude, rng, mayBreakBuilds);
        }
        int delta = switch (type) {
            case DIVERGENT -> riftDelta(across, slip, magnitude, rng);
            case CONVERGENT_SUBDUCTION -> subductionDelta(across, slip, magnitude);
            case CONVERGENT_COLLISION -> collisionDelta(across, slip, magnitude);
            default -> 0;
        };
        if (delta == 0) return null;
        int top = snap.groundAt(x, z);
        if (delta > 0) {
            BlockState surface = snap.stateAt(x, z, 0);
            if (!liftable(surface, mayBreakBuilds, snap.generatedAt(x, z))) return null;
            return new ColumnPlan(x, z, top, delta, surface, deeper(snap, x, z, rng));
        }
        int cut = carvableDepth(snap, x, z, -delta, mayBreakBuilds);
        if (cut < 1) return null;
        // Under water a rift floor gets fresh crust, which is sea-floor spreading. On land the
        // fissure stays open.
        BlockState floor = null;
        if (type == FaultType.DIVERGENT && snap.submergedAt(x, z)
                && Math.abs(across) <= FRESH_CRUST_HALF) {
            floor = freshCrust(rng);
        }
        return new ColumnPlan(x, z, top, -cut, floor, null);
    }

    /** How many blocks down this column may actually be dug before something stops it. */
    private static int carvableDepth(Snapshot snap, int x, int z, int want, boolean mayBreakBuilds) {
        int limit = Math.min(want, snap.depthAt(x, z));
        for (int d = 0; d < limit; d++) {
            if (!carvable(snap.stateAt(x, z, d), mayBreakBuilds, snap.generatedAt(x, z))) return d;
        }
        return limit;
    }

    /** How far either side of the axis a submerged rift lays down brand new crust. */
    private static final int FRESH_CRUST_HALF = 2;

    /** Young ocean floor. No magma block: under water it makes a bubble column that drags swimmers down. */
    private static BlockState freshCrust(RandomGenerator rng) {
        return switch (rng.nextInt(6)) {
            case 0 -> Blocks.BLACKSTONE.defaultBlockState();
            case 1 -> Blocks.SMOOTH_BASALT.defaultBlockState();
            case 2 -> Blocks.TUFF.defaultBlockState();
            default -> Blocks.BASALT.defaultBlockState();
        };
    }

    // === Shape parameters, shared with deformationHalfWidth =================

    /** Half-width of a rift's dropped graben floor. A rift valley is one of the widest landforms here. */
    private static int grabenHalfFloor(double magnitude, double slip) {
        return Mth.clamp((int) Math.round(slip * (6 + magnitude * 5.0)), 3, 46);
    }

    /** How far the flexural shoulder rises beyond the graben floor: a rift is a valley between raised shoulders. */
    private static int riftShoulderReach(int halfFloor) {
        return Math.max(6, (int) Math.round(halfFloor * 0.9));
    }

    /** Shoulder height as a fraction of the graben's own drop. Subordinate on purpose. */
    private static final double RIFT_SHOULDER_FRACTION = 0.35;

    /**
     * How far inland the overriding plate is raised. Scaled off the trench's flexural length and wider
     * than the seaward side, as a real margin is: the Andean plateau against a narrow outer rise.
     */
    private static int arcHalfWidth(int core) {
        return Mth.clamp((int) Math.round(flexuralLength(core) * 7.8), 12, 145);
    }

    /** Where the volcanic arc crest stands, measured inland from the boundary. */
    private static int arcCrest(int core) {
        return Math.max(3, (int) Math.round(flexuralLength(core) * 1.6));
    }

    /** Back-arc plateau height as a fraction of the arc crest. */
    private static final double BACK_ARC_PLATEAU = 0.55;

    /** The natural scale of the down-going plate's bend; everything about the trench follows it. */
    private static double flexuralLength(int core) {
        return Mth.clamp(core * 1.6, 6.0, 18.0);
    }

    /** How far out the trench basin reaches before it has climbed back to the original ground. */
    private static int trenchReach(int core) {
        return (int) Math.round(flexuralLength(core) * 4.8);
    }

    /** Where the outer rise beyond the trench finally dies out, as a multiple of the reach. */
    private static final double OUTER_RISE_END = 1.30;

    /** How steep the trench's inner wall is: full depth is reached this far from the boundary. */
    private static int trenchWall(int core) {
        return Math.max(4, (int) Math.round(flexuralLength(core) * 0.6));
    }

    private static int maxTrenchDepth(double magnitude) {
        return Mth.clamp((int) Math.round(magnitudeAmplitude(magnitude, 25.0)), 1, MAX_CAPTURE_DEPTH - 4);
    }

    /** Share of the collision belt on the underthrust side: a steep range front against a broad plateau. */
    private static final double COLLISION_FRONT_FRACTION = 0.55;

    /** Half-width of a collision fold-and-thrust belt. */
    private static int beltHalfWidth(int core) {
        return Mth.clamp(core * 6, 16, 70);
    }

    private static int strikeSlipHalfWidth(double magnitude) {
        return Mth.clamp(ruptureHalfWidth(magnitude) * 2, 6, 30);
    }

    private static int strikeSlipOffset(double magnitude, double slip) {
        return Mth.clamp((int) Math.round(slip * magnitudeAmplitude(magnitude, 14.0)), 1, 12);
    }

    // === The four deformation profiles ======================================
    //
    // Each answers the same question for a single column: how far does this piece of ground move?
    // Positive lifts it, negative digs it out, zero leaves it alone.

    /** Normal faulting: a graben floor easing up to raised shoulders, with an open fissure on the axis. */
    private static int riftDelta(double across, double slip, double magnitude, RandomGenerator rng) {
        if (slip <= 0.02) return 0;
        int halfFloor = grabenHalfFloor(magnitude, slip);
        int shoulder = riftShoulderReach(halfFloor);
        double d = Math.abs(across);
        if (d > halfFloor + shoulder) return 0;

        int drop = Mth.clamp((int) Math.round(slip * magnitudeAmplitude(magnitude, 9.0) * 1.4), 1, 8);

        if (d > halfFloor) {
            // The shoulder: a half sine, zero at the valley rim and at its outer edge.
            double u = (d - halfFloor) / (double) shoulder;
            int lift = (int) Math.round(drop * RIFT_SHOULDER_FRACTION * Math.sin(Math.PI * u));
            return lift;
        }

        // The floor eases out with a smoothstep, so the valley runs into the countryside without a step.
        double t = smoothstep(1.0 - d / (halfFloor + 1.0));
        int cut = (int) Math.round(drop * t);

        // The fissure itself: a narrow, much deeper opening right on the axis.
        if (d <= 1.2) {
            int depth = (int) Math.round(GeyserConfig.QUAKE_MAX_FISSURE_DEPTH.get() * slip
                    * (0.55 + 0.45 * rng.nextDouble()));
            cut = Math.max(cut, depth);
        }
        return cut <= 0 ? 0 : -cut;
    }

    /**
     * A subduction margin. The down-going side sinks into a trench, deepest at the boundary and easing
     * back to level along a smootherstep, with a low outer rise beyond. The overriding side climbs to
     * a volcanic arc inland and holds a back-arc plateau behind it.
     */
    private static int subductionDelta(double across, double slip, double magnitude) {
        if (slip <= 0.02) return 0;
        int core = ruptureHalfWidth(magnitude);

        if (across >= 0) {
            int arcHalf = arcHalfWidth(core);
            if (across > arcHalf) return 0;
            int crest = Math.min(arcCrest(core), arcHalf - 1);
            int maxLift = Mth.clamp((int) Math.round(slip * magnitudeAmplitude(magnitude, 22.0) * 0.9), 1, 25);

            double shape;
            if (across < crest) {
                // Boundary to arc: rises from nothing, so the margin is an inflection, not a wall.
                shape = 0.5 * (1.0 - Math.cos(Math.PI * across / crest));
            } else {
                // Arc to foreland: a quick drop off the crest onto a plateau that declines slowly.
                double u = (across - crest) / (double) (arcHalf - crest);
                double taper = 0.5 * (1.0 + Math.cos(Math.PI * u));   // 1 at the crest, 0 at the edge
                double offCrest = Math.exp(-u * 6.0);                 // the drop off the back
                shape = taper * (BACK_ARC_PLATEAU + (1.0 - BACK_ARC_PLATEAU) * offCrest);
            }
            return (int) Math.round(maxLift * shape);
        }

        double d = -across;
        int reach = trenchReach(core);
        int maxDepth = (int) Math.round(slip * maxTrenchDepth(magnitude));
        if (maxDepth < 1) return 0;

        if (d <= reach) {
            // One smootherstep over the whole reach: level at both ends, steepest in the middle.
            double u = Mth.clamp(d / (double) reach, 0.0, 1.0);
            return -(int) Math.round(maxDepth * (1.0 - smootherstep(u)));
        }
        // The outer rise: a low, broad swell where the bent plate has sprung back up.
        double u = (d - reach) / (reach * (OUTER_RISE_END - 1.0));
        if (u > 1.0) return 0;
        return (int) Math.round(maxDepth * 0.14 * Math.sin(Math.PI * u));
    }

    /**
     * Continental collision: one lopsided range. The overriding side carries a broad plateau, the
     * underthrust side a steep front and then a shallow foreland basin. Fold ridges are 8% texture on
     * the flank, never mountains of their own.
     */
    private static int collisionDelta(double across, double slip, double magnitude) {
        if (slip <= 0.02) return 0;
        int core = ruptureHalfWidth(magnitude);
        int beltHalf = beltHalfWidth(core);
        if (Math.abs(across) > beltHalf) return 0;

        int maxLift = Mth.clamp((int) Math.round(slip * magnitudeAmplitude(magnitude, 18.0)), 1, 18);
        if (maxLift < 1) return 0;

        // The two flanks are not the same width: the plateau reaches right across the overriding
        // side, while the range front is packed into a little over half that on the other.
        double frontHalf = beltHalf * COLLISION_FRONT_FRACTION;
        double d = Math.abs(across);

        if (across < 0.0 && d > frontHalf) {
            // Past the range front: the foreland basin. A broad, shallow sag under the load of the
            // mountains next to it - never a trench, which is a different boundary entirely.
            double u = (d - frontHalf) / Math.max(1.0, beltHalf - frontHalf);
            return -(int) Math.round(maxLift * 0.12 * Math.sin(Math.PI * u));
        }

        double span = across < 0.0 ? frontHalf : beltHalf;
        // One envelope, one summit. The exponent below 1 flattens the crest into a plateau and
        // steepens the shoulders, which is the shape a collisional highland actually has.
        double envelope = Math.pow(0.5 * (1.0 + Math.cos(Math.PI * d / (span + 1.0))), 0.75);

        // Subsidiary ridges: real, but only ever texture on the flank of the single range.
        double wavelength = beltHalf / 1.5;
        double fold = Math.cos(2.0 * Math.PI * across / wavelength);
        return (int) Math.round(maxLift * envelope * (0.92 + 0.08 * fold));
    }

    /**
     * Strike-slip faulting: the moving side carries the landscape along the strike, so ridges and
     * streams crossing the fault come out offset. Height changes are clamped; the trace gets a
     * shallow mole track.
     */
    private static ColumnPlan strikeSlipPlan(Snapshot snap, int x, int z, double across,
                                             double sx, double sz, double slip, double magnitude,
                                             RandomGenerator rng, boolean mayBreakBuilds) {
        if (slip <= 0.02) return null;
        int top = snap.groundAt(x, z);

        // The mole track, with the odd sag pond where the fault steps.
        if (Math.abs(across) <= 1.2) {
            int trough = Mth.clamp((int) Math.round(slip * magnitude * 0.25), 1, 3);
            if (rng.nextDouble() < 0.06) trough += 2;
            int cut = carvableDepth(snap, x, z, trough, mayBreakBuilds);
            return cut < 1 ? null : new ColumnPlan(x, z, top, -cut, null, null);
        }
        // Only the near side moves; grinding both would cancel the offset out.
        if (across < 0) return null;

        int half = strikeSlipHalfWidth(magnitude);
        if (across > half) return null;
        double falloff = 1.0 - (across - 1.0) / half;
        int slid = (int) Math.round(strikeSlipOffset(magnitude, slip) * falloff);
        if (slid < 1) return null;

        // The column this ground has arrived FROM, back along the strike.
        int fx = x - (int) Math.round(sx * slid);
        int fz = z - (int) Math.round(sz * slid);
        if (!snap.has(fx, fz)) return null;

        BlockState carried = snap.stateAt(fx, fz, 0);
        if (!liftable(carried, mayBreakBuilds, snap.generatedAt(fx, fz))) return null;
        if (!liftable(snap.stateAt(x, z, 0), mayBreakBuilds, snap.generatedAt(x, z))) return null;

        int from = snap.groundAt(fx, fz);
        // Clamped and tapered, so a cliff crossing the fault offsets rather than collapses.
        int delta = (int) Math.round(Mth.clamp(from - top, -6, 6) * falloff);

        if (delta > 0) return new ColumnPlan(x, z, top, delta, carried, deeper(snap, fx, fz, rng));
        if (delta < 0) {
            int cut = carvableDepth(snap, x, z, -delta, mayBreakBuilds);
            return cut < 1 ? null : new ColumnPlan(x, z, top, -cut, carried, null);
        }
        // Same height: the offset still shows, because the ground cover itself has moved.
        return new ColumnPlan(x, z, top, 0, carried, null);
    }

    /**
     * Amplitude on the square of the normalised magnitude, so small quakes stay modest and the rare
     * giants reshape the ground.
     *
     * @param peak the amplitude a magnitude 9 reaches, in blocks
     */
    private static double magnitudeAmplitude(double magnitude, double peak) {
        double t = Mth.clamp(magnitude / 9.0, 0.0, 1.15);
        return t * t * peak;
    }

    /** Classic smoothstep: flat at both ends, so a ramp built from it has no corner. */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** Smootherstep: zero slope and curvature at both ends, so a long ramp leaves no crease. */
    private static double smootherstep(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    // === Helpers ============================================================

    /** A block a few layers down, used as fill so uplifted ground looks like local rock. */
    private static BlockState deeper(Snapshot snap, int x, int z, RandomGenerator rng) {
        BlockState s = snap.stateAt(x, z, 1 + rng.nextInt(2));
        BlockState surface = snap.stateAt(x, z, 0);
        return s != null && !s.isAir() ? s : (surface != null ? surface : Blocks.STONE.defaultBlockState());
    }

    /** Is this column in a structure the world generated? Server thread only; see {@link Snapshot#generatedAt}. */
    private static boolean insideGeneratedStructure(ServerLevel level, int x, int y, int z) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            net.minecraft.world.level.StructureManager mgr = level.structureManager();
            for (net.minecraft.world.level.levelgen.structure.Structure st
                    : mgr.getAllStructuresAt(pos).keySet()) {
                if (mgr.getStructureWithPieceAt(pos, st).isValid()) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;      // an exotic structure source that cannot answer simply protects it
        }
    }

    /**
     * The mod's own working parts: cores, chambers, igniters and spring sources. Protected outright,
     * even when quakes may break builds, because half a geyser is a broken world, not a damaged one.
     */
    static boolean machinery(BlockState s) {
        return s.is(ModBlocks.GEYSER_CORE.get())
                || s.is(ModBlocks.GEYSER_CHAMBER.get())
                || s.is(ModBlocks.GEYSER_IGNITER.get())
                || s.is(ModBlocks.VOLCANO_CORE.get())
                || s.is(ModBlocks.VOLCANO_IGNITER.get())
                || s.is(ModBlocks.SPRING_SOURCE.get());
    }

    /** May the quake remove this block? Never bedrock; never a build unless explicitly allowed. */
    private static boolean carvable(BlockState s, boolean mayBreakBuilds, boolean generated) {
        if (s == null || s.is(Blocks.BEDROCK)) return false;
        if (machinery(s)) return false;
        // A world-made structure moves like any other ground; see Snapshot.generatedAt.
        return mayBreakBuilds || generated || !EruptionHandler.isPlayerPlaced(s);
    }

    /** May the quake pick this block up and move or stack it? Same rules, plus it must be solid. */
    private static boolean liftable(BlockState s, boolean mayBreakBuilds, boolean generated) {
        if (s == null || s.isAir() || s.is(Blocks.BEDROCK)) return false;
        if (!s.getFluidState().isEmpty()) return false;
        if (TerrainProbe.isVegetation(s)) return false;   // nothing to carry; it is just ground cover
        if (machinery(s)) return false;
        // A world-made structure moves like any other ground; see Snapshot.generatedAt.
        return mayBreakBuilds || generated || !EruptionHandler.isPlayerPlaced(s);
    }
}
