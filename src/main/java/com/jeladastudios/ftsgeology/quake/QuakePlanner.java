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
 * Works out what an earthquake does to the ground.
 *
 * <h2>Three stages</h2>
 * <ol>
 *   <li>{@link #traceFault} walks the plate boundary out from the epicentre, re-reading the local
 *       strike as it goes, so the rupture follows the real curve of the fault and stops where the
 *       boundary ends or changes character - at a triple junction, for instance. Pure maths over
 *       the seed; touches no blocks.</li>
 *   <li>{@link #snapshot} then copies only the CORRIDOR along that trace. A realistic M7 rupture is
 *       hundreds of blocks long, and a square box around it would be a million columns.</li>
 *   <li>{@link #plan} turns the snapshot into an immutable edit list on a worker thread, and
 *       {@link Earthquake} applies it on the server thread a slice per tick.</li>
 * </ol>
 *
 * <h2>The corridor is walked on the block lattice, not parametrically</h2>
 * Both stages used to sweep the corridor by stepping along the fault and then sideways across it,
 * rounding the result to a block. That works perfectly when the fault runs north-south or east-west
 * and <b>silently loses a fifth of the ground</b> when it does not: two rounded parametric axes
 * cannot land on every lattice point. Measured over a 312-block rupture with a 24-block half-width,
 * the columns never visited were
 * <pre>
 *   axis-aligned    0 of 14711    (100.0% covered)
 *   22.5 degrees  526 of 14351     (96.3%)
 *   45 degrees   3094 of 14365     (78.5%)
 *   60 degrees   1926 of 14354     (86.6%)
 * </pre>
 * Untouched columns keep their original height while their neighbours are cut or raised, which is
 * what left rift grabens looking combed through with thin pillars and turned a collision belt's
 * continuous ridges into a scatter of disconnected bumps.
 *
 * <p>{@link #forEachCorridorColumn} therefore enumerates the integer columns of each trace segment
 * directly and asks each one where it falls relative to the fault. Coverage is complete by
 * construction, and because a column is visited exactly once it gets a single coherent treatment
 * rather than whichever partial answer happened to claim it first.</p>
 *
 * <h2>Deformation</h2>
 * <ul>
 *   <li><b>Divergent</b> - a graben drops between two shoulder faults with a fissure down its axis.
 *       Thingvellir in Iceland.</li>
 *   <li><b>Subduction</b> - deeply asymmetric: a trench hard against the boundary that shallows out
 *       over sixty-odd blocks, against a broad arc high on the overriding plate.</li>
 *   <li><b>Collision</b> - symmetric crumpling: a wide belt of parallel ridges and valleys, which is
 *       what a fold-and-thrust mountain range actually is.</li>
 *   <li><b>Transform</b> - the landscape itself is carried along the strike, so anything crossing
 *       the fault is cut and offset.</li>
 * </ul>
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
        // The backward half was traced away from the epicentre, so reversing it puts the whole
        // rupture in order. Its first entry is the epicentre itself, which the forward half also
        // carries, so it is dropped rather than duplicated into a zero-length segment.
        for (int i = backward.size() - 1; i >= 1; i--) all.add(backward.get(i));
        all.addAll(forward);

        // Re-orient every point's strike so it points at the NEXT point in the list. The two halves
        // were traced in opposite directions and the corridor is laid out FORWARD from each point,
        // so a segment still carrying the direction it was traced in would tile the line backwards
        // and interpolate its slip the wrong way.
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
            // A natural rupture stops where the boundary stops being this kind of boundary - that is
            // what makes it end at a triple junction. A quake whose type was FORCED (the command,
            // used to demonstrate a style anywhere) must not: standing one block off the fault used
            // to break the trace on its very first step.
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
     * <h2>Membership and coordinate are different questions</h2>
     * <b>Membership</b> is a capsule: a column belongs to this segment's corridor if it is within
     * {@code band} of the SEGMENT, ends included. Consecutive capsules share the disc at their joint,
     * so their union covers everything within {@code band} of the polyline - which is what closed the
     * coverage hole that left grabens combed through with pillars.
     *
     * <p>The <b>coordinate</b> handed to the deformation is a different thing entirely: the
     * perpendicular offset from the fault line. Reusing the capsule distance for it - which is what
     * shipped last round - was badly wrong, because past the end of a segment that distance is
     * measured to the endpoint rather than across the fault. With a 94-block band and an 8-block
     * segment, <em>95% of each capsule is end cap</em>, so 88.6% of columns were handed a distance
     * that was not their distance from the fault, wrong by up to the full width of the band. Every
     * cross-section became a bullseye centred on its trace point instead of a band along the fault:
     * the segment at the epicentre claimed a 94-block disc and everything further along got an
     * inflated distance and therefore no deformation at all. That is why a rupture appeared as a
     * mound at the epicentre rather than something running along the boundary.</p>
     *
     * @param bodyOnly first pass: claim only the columns squarely alongside this segment, so each one
     *                 gets its own segment's slip. The second pass sweeps up the joint discs.
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
         * True when this column stands inside something the world generated - a village, a temple,
         * an outpost - rather than something a player built.
         *
         * <h2>Why it is recorded here and not asked for later</h2>
         * The two are indistinguishable by block: a village is planks and cobblestone, so the rule
         * that protects builds protects villages too, which is why they stood untouched in the
         * middle of a rupture. Telling them apart needs the structure manager, and that needs world
         * access - which the planner does not have, because it runs on a worker thread. So the
         * question is asked once per column while the snapshot is being taken on the server thread,
         * and the answer travels with the column.
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

    /**
     * How many blocks of a column's history this style needs at this distance across the fault.
     *
     * <p>Capturing to the deepest possible carve everywhere would be the simple thing to do and also
     * by far the most expensive part of starting a quake. Only the strip that actually gets dug out
     * needs the full stack; the broad uplifted flanks need three blocks to pick a believable fill
     * material and nothing more.</p>
     */
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
     * Turns a trace plus snapshot into an ordered edit list. Pure computation; safe on a worker
     * thread.
     *
     * <h2>Why the order matters</h2>
     * The trace runs end to end, but the segments are visited walking OUTWARD from the epicentre.
     * That is what actually happens - a rupture nucleates at the hypocentre and tears outward - and
     * it also means each tick of the apply loop touches one or two chunks instead of scattering
     * across the whole rupture, which is what used to freeze the client. When the edit cap is
     * reached the work dropped is always the far ENDS, so a capped quake is a shorter one rather
     * than one full of holes.
     *
     * <h2>One column, one treatment</h2>
     * Each column is claimed by the first segment that reaches it and is then deformed once, as a
     * whole. Edits used to be merged block by block across overlapping cross-sections, so a column
     * could end up half carved by one slice and half stacked by another.
     */
    public static Plan plan(Snapshot snap, List<TracePoint> trace, BlockPos epicentre, FaultType type,
                            double magnitude, double depthMetres, RandomGenerator rng,
                            boolean mayBreakBuilds) {
        int cap = GeyserConfig.QUAKE_MAX_EDITS.get();
        int band = deformationHalfWidth(type, magnitude);

        // Phase one: decide what every column does. Nothing is emitted yet, because the ORDER the
        // edits go out in is what decides whether the fault appears to move all at once or to be
        // swept along by a wave.
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
        // Two passes over the trace. The first claims only the columns squarely alongside each
        // segment, so a column is deformed with the slip of the piece of fault it actually sits
        // against rather than whichever segment happened to reach it first - the segment at the
        // epicentre otherwise swallows a disc the full width of the corridor. The second pass fills
        // in the joint discs, which is what keeps coverage complete on a bend.
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
     * Emits one layer of movement, spread across the whole rupture at once.
     *
     * <h2>Why not simply sweep</h2>
     * Plates do not tear open at one spot and unzip; they pull apart, or drive together, everywhere
     * along the boundary at the same time. Emitting a layer end to end would still read as a wave
     * travelling down the fault.
     *
     * <p>But writing to the entire rupture inside a single tick is exactly what used to freeze the
     * client - fifty to a hundred chunk meshes rebuilt every tick. So the layer is cut into
     * {@link #ACTIVE_FRONTS} fronts spread along the fault and they take turns in short slices: each
     * tick touches a handful of chunks, while every part of the boundary creeps forward together.</p>
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
        // Sea-floor spreading. Where a rift opens under water the gap does not stay a hole: mantle
        // rises into it, melts from the drop in pressure alone and freezes as new crust. That is the
        // entire mechanism of a spreading ridge, and it is why the floor of one is bare young basalt
        // with the sediment lying only to either side. On land a fissure just stays a fissure.
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

    /**
     * Young ocean floor: the rock that freezes in the gap the moment the plates part.
     *
     * <p>Basalt family only, and deliberately no magma block. Magma under water opens a downward
     * bubble column that drags a swimmer to the bottom, which would turn the most interesting place
     * in the mod into a drowning trap. The glow belongs on the black smokers, where it is sealed.</p>
     */
    private static BlockState freshCrust(RandomGenerator rng) {
        return switch (rng.nextInt(6)) {
            case 0 -> Blocks.BLACKSTONE.defaultBlockState();
            case 1 -> Blocks.SMOOTH_BASALT.defaultBlockState();
            case 2 -> Blocks.TUFF.defaultBlockState();
            default -> Blocks.BASALT.defaultBlockState();
        };
    }


    // === Shape parameters, shared with deformationHalfWidth =================

    /**
     * Half-width of the dropped graben floor at a rift.
     *
     * <p>Was capped at 20, which made a rift 40 blocks across while a subduction margin ran past a
     * hundred and a collision belt seventy - the narrowest thing the model built, when in the
     * ground a rift valley is one of the widest. The East African Rift is thirty to a hundred
     * kilometres from shoulder to shoulder.</p>
     */
    private static int grabenHalfFloor(double magnitude, double slip) {
        return Mth.clamp((int) Math.round(slip * (6 + magnitude * 5.0)), 3, 46);
    }

    /**
     * How far the flexural shoulder reaches beyond the graben floor.
     *
     * <p>Stretching crust does not only drop the middle: unloading it lets the flanks rebound, so a
     * real rift is a valley between two RAISED shoulders. That is the escarpment you stand on to
     * look down into the Great Rift Valley, and the model had none of it.</p>
     */
    private static int riftShoulderReach(int halfFloor) {
        return Math.max(6, (int) Math.round(halfFloor * 0.9));
    }

    /** Shoulder height as a fraction of the graben's own drop. Subordinate on purpose. */
    private static final double RIFT_SHOULDER_FRACTION = 0.35;

    /**
     * How far inland the overriding plate is heaved up behind a trench.
     *
     * <p>Scaled off the same flexural length as the trench, so the two sides of the margin stay in
     * proportion as magnitude grows. It is deliberately the <b>wider</b> of the two: the seaward
     * side ends at {@code trenchReach * OUTER_RISE_END}, and this is about a quarter as far again.
     * That is the right way round. A subduction margin is lopsided inland - the trench sits a
     * hundred-odd kilometres offshore and the outer rise dies not far beyond it, while on the other
     * side the Altiplano is hundreds of kilometres wide and the deformation front runs on past it
     * into the Sub-Andean belt. It was previously capped at 44 blocks against a seaward reach of
     * 112, which is not just too narrow but lopsided the wrong way.</p>
     */
    private static int arcHalfWidth(int core) {
        return Mth.clamp((int) Math.round(flexuralLength(core) * 7.8), 12, 145);
    }

    /** Where the volcanic arc crest stands, measured inland from the boundary. */
    private static int arcCrest(int core) {
        return Math.max(3, (int) Math.round(flexuralLength(core) * 1.6));
    }

    /**
     * How high the back-arc plateau stands as a fraction of the arc crest. The high ground behind a
     * volcanic arc does not fall straight off the back of it; it holds up as a plateau and only then
     * declines into the foreland.
     */
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

    /**
     * How much of the belt sits on the underthrust side, as a fraction of the whole half-width.
     *
     * <p>Below a half, so the range front on that side is steeper and closer in than the plateau
     * behind the suture - the Himalayan front against the Tibetan plateau. Everything past it is
     * foreland basin.</p>
     */
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

    /**
     * Normal faulting. Stretching crust does not just crack - it drops a whole block of ground
     * between two faults, which is why a rift is a VALLEY rather than a fissure. At Thingvellir you
     * can walk along the floor of one with a plate on either side.
     *
     * <p>So this is a graben: a subsided floor easing up to the shoulders, with a deep open fissure
     * down the axis where the crust actually parted.</p>
     */
    private static int riftDelta(double across, double slip, double magnitude, RandomGenerator rng) {
        if (slip <= 0.02) return 0;
        int halfFloor = grabenHalfFloor(magnitude, slip);
        int shoulder = riftShoulderReach(halfFloor);
        double d = Math.abs(across);
        if (d > halfFloor + shoulder) return 0;

        int drop = Mth.clamp((int) Math.round(slip * magnitudeAmplitude(magnitude, 9.0) * 1.4), 1, 8);

        if (d > halfFloor) {
            // The raised shoulder. A half sine: zero where it meets the valley rim and zero again
            // where it dies out, so it swells up between the two and joins the floor without a
            // step. Flexural uplift really does crest a little way back from the border fault
            // rather than right on it, which is what this shape says.
            double u = (d - halfFloor) / (double) shoulder;
            int lift = (int) Math.round(drop * RIFT_SHOULDER_FRACTION * Math.sin(Math.PI * u));
            return lift;
        }

        // The floor. Smoothstep rather than a straight line, and NOT floored at one block: the old
        // profile kept a minimum cut of 1 all the way out, so the valley ended in a one-block step
        // instead of running out into the countryside.
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
     * A subduction margin: the most asymmetric thing plate tectonics builds.
     *
     * <p>The down-going side is dragged into a trench that is <b>deepest hard against the
     * boundary</b> and then shallows out over sixty-odd blocks along a cosine-squared curve, so the
     * ground climbs back to normal without a single step in it - fifteen or so blocks down close in,
     * a dozen by twenty blocks out, and on to nothing. The inner wall is eased in over a handful of
     * blocks with a smoothstep, so even the steepest part of the margin is a slope rather than a
     * cliff. Far beyond the basin the bent plate springs back into a low <b>outer rise</b>, kept
     * deliberately subordinate so it reads as a swell rather than competing with the trench.</p>
     *
     * <p>The overriding plate carries its high ground inland at the volcanic arc, not at the water's
     * edge, which is what keeps the boundary itself a smooth inflection instead of a wall.</p>
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
                // Boundary to arc: climbs from nothing, so the margin itself stays an inflection
                // rather than a wall standing at the water's edge.
                shape = 0.5 * (1.0 - Math.cos(Math.PI * across / crest));
            } else {
                // Arc to foreland. Two terms: a quick drop off the back of the crest, and a broad
                // plateau under it that only gives way near the far edge. Their sum falls from the
                // crest to the plateau within the first fifth of the tail and then declines slowly
                // across the rest of it, which is what makes the uplift a REGION rather than a
                // ridge - the whole point of the change.
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
            // One continuous curve rather than a wall term times a tail.
            //
            // It used to be smoothstep(d / trenchWall) * cos^2(d / reach): two independent factors,
            // and their product piles most of the descent into the first few blocks off the
            // boundary. That reads as an edge you fall off rather than a basin you walk down into,
            // which is what testing meant by asking for the down-going side to be smoother within
            // itself. A single smootherstep over the whole reach has zero slope at BOTH ends, so
            // the ground eases away from the boundary and eases back to level at the far side, with
            // the steepest part in the middle where a real forearc basin has it.
            double u = Mth.clamp(d / (double) reach, 0.0, 1.0);
            return -(int) Math.round(maxDepth * (1.0 - smootherstep(u)));
        }
        // The outer rise: a low, broad swell where the bent plate has sprung back up.
        double u = (d - reach) / (reach * (OUTER_RISE_END - 1.0));
        if (u > 1.0) return 0;
        return (int) Math.round(maxDepth * 0.14 * Math.sin(Math.PI * u));
    }

    /**
     * Continental collision. Two buoyant plates cannot subduct, so the crust simply crumples - and it
     * crumples into <b>one mountain range</b>, not a corduroy of equal ridges.
     *
     * <h2>Why this is one range and not five</h2>
     * A fold-and-thrust belt really does have parallel ridges, but they are subordinate wrinkles on a
     * single enormous swell: the Himalaya is one wall with the Siwaliks pleated along its foot, not
     * five Himalayas standing in a row. The old profile handed the fold train 55% of the amplitude
     * and let it run clean across the belt, so it produced 2*folds+1 separate summits - five to nine
     * of them - and on a small rupture, where the wavelength fell to about six blocks, a visible
     * comb. The fold train is now 8% of the signal: it breaks the silhouette without ever becoming a
     * mountain of its own.
     *
     * <h2>Why it is lopsided</h2>
     * Collision is one-sided. One plate rides over the other and carries a broad high plateau away
     * from the suture - Tibet - while the underthrust side gets a steep range front and then a
     * <b>foreland basin</b>, crust pressed down by the sheer weight of the mountains beside it, which
     * is the Ganges plain. A symmetric cosine cannot say any of that. This asymmetry is what makes
     * the landform read as a collision instead of as a generic hill.
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
     * Strike-slip faulting. Neither side rises or falls; one slides along the other, and the moving
     * side carries the <b>shape of the landscape</b> with it. A stream bed or a ridge crossing the
     * fault is genuinely cut and offset, which is the one image everybody has of the San Andreas.
     *
     * <p>Height changes are clamped and tapered across the band so steep country can never be turned
     * into a tower or a pit, and the trace itself gets the shallow broken trough - the mole track -
     * that marks a strike-slip rupture at the surface.</p>
     */
    private static ColumnPlan strikeSlipPlan(Snapshot snap, int x, int z, double across,
                                             double sx, double sz, double slip, double magnitude,
                                             RandomGenerator rng, boolean mayBreakBuilds) {
        if (slip <= 0.02) return null;
        int top = snap.groundAt(x, z);

        // The mole track: the shallow, broken trough that marks the trace of a strike-slip rupture
        // on the surface, with the occasional sag pond where the fault steps.
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
     * Amplitude for a magnitude, on a curve rather than a straight line. Earthquake energy rises by
     * about 32x per magnitude step, so a linear response makes every large event feel the same;
     * squaring the normalised magnitude keeps small quakes modest while letting the rare giants
     * genuinely reshape the ground.
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

    /**
     * Like {@link #smoothstep} but with zero curvature at both ends as well as zero slope.
     *
     * <p>Used for the trench floor. Smoothstep leaves a visible crease where it meets level ground,
     * because its second derivative jumps; over sixty blocks of subsiding plate that crease reads as
     * a terrace. This one leaves none.</p>
     */
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

    /**
     * Is this column standing in a structure the world generated?
     *
     * <p>Server thread only - see {@link Snapshot#generatedAt}. Any structure counts, not just
     * villages: a temple, an outpost or a fortress is no more a player's work than a village is,
     * and a quake that levels the village next door while leaving the pillager tower standing looks
     * like a bug rather than a decision.</p>
     */
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
     * The mod's own working parts: cores, chambers, igniters and the deep end of a hot spring.
     *
     * <h2>Why these are protected outright</h2>
     * They are machinery rather than landscape, and half of a machine is worse than none - an
     * orphaned chamber with no core, or a spring source with its conduit cut away, is a broken
     * world rather than a damaged one. The existing code said as much in a comment and relied on
     * these blocks failing the natural-terrain test to get it, which held only while
     * {@code quakeMayBreakBuilds} was off: with that switched on, {@code mayBreakBuilds} let a quake
     * carve a working geyser in half after all.
     *
     * <p>Depth alone is not a substitute. A spring source is seated below anything a quake reaches,
     * but a quake can lower the ground, and the one after it measures from the new surface.</p>
     */
    private static boolean machinery(BlockState s) {
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
        // "generated" means the column stands in a village or other world-made structure. Those are
        // scenery the world put there, not somebody's work, so an earthquake moves them like any
        // other ground - see Snapshot.generatedAt.
        return mayBreakBuilds || generated || !EruptionHandler.isPlayerPlaced(s);
    }

    /** May the quake pick this block up and move or stack it? Same rules, plus it must be solid. */
    private static boolean liftable(BlockState s, boolean mayBreakBuilds, boolean generated) {
        if (s == null || s.isAir() || s.is(Blocks.BEDROCK)) return false;
        if (!s.getFluidState().isEmpty()) return false;
        if (TerrainProbe.isVegetation(s)) return false;   // nothing to carry; it is just ground cover
        if (machinery(s)) return false;
        // "generated" means the column stands in a village or other world-made structure. Those are
        // scenery the world put there, not somebody's work, so an earthquake moves them like any
        // other ground - see Snapshot.generatedAt.
        return mayBreakBuilds || generated || !EruptionHandler.isPlayerPlaced(s);
    }
}
