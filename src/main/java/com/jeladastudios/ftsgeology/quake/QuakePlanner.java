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
    /**
     * One block the quake writes. {@code carried} marks what stood on the ground moving with it, which is not the
     * ground being dug away: the settling afterwards reads only real digging as ground gone from under something.
     */
    public record Edit(BlockPos pos, BlockState state, boolean carried) {
        public Edit(BlockPos pos, BlockState state) {
            this(pos, state, false);
        }
    }

    /** A point on the rupture, carrying the local fault direction and how much it slipped. */
    public record TracePoint(int x, int z, double strikeX, double strikeZ, double slip) {}

    /** A fully planned earthquake, ready to be applied on the server thread. */
    public record Plan(BlockPos epicentre, FaultType type, double magnitude,
                       double depthMetres, int ruptureLength, List<Edit> edits, long[] wrecked) {}

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
            PlateSample s = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, (int) Math.round(x), (int) Math.round(z));
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

    /** How far past a chunk's edge a clipped walk still looks, so the columns on its border see their neighbours. */
    static final int CLIP_MARGIN = 10;

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
     * @param clip     when given, only one chunk and a margin: replaying a parked rupture walked the whole
     *                 corridor's lattice for every chunk it came to, two hundred thousand cells to keep a
     *                 few hundred.
     */
    private static void forEachCorridorColumn(TracePoint tp, TracePoint next, int band,
                                              boolean bodyOnly, ChunkPos clip, ColumnVisitor v) {
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
        if (clip != null) {
            loX = Math.max(loX, clip.getMinBlockX() - CLIP_MARGIN);
            hiX = Math.min(hiX, clip.getMaxBlockX() + CLIP_MARGIN);
            loZ = Math.max(loZ, clip.getMinBlockZ() - CLIP_MARGIN);
            hiZ = Math.min(hiZ, clip.getMaxBlockZ() + CLIP_MARGIN);
        }

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

                // Coordinate: the distance to the segment, on the side of the fault the column is on. Alongside the
                // segment that is the offset from its line; off its ends, the distance to the nearer end, not the
                // offset from a line the column is nowhere near.
                double offset = dx * nx + dz * nz;
                if (Math.abs(offset) > band) continue;
                double across = Math.copySign(Math.sqrt(d2), offset);

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
        private record Column(int groundY, boolean submerged, boolean generated, boolean built, boolean playerBuilt,
                              BlockState[] stack, BlockState[] above) {}

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

        /** True when something built -- a player's, or a structure's -- stands on this column's ground. */
        public boolean builtAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c != null && c.built();
        }

        /** True when what stands on this column is a player's: placed by one, or worked material outside a structure. */
        public boolean playerBuiltAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c != null && c.playerBuilt();
        }

        /** What stands on this column's ground and moves with it, from the block over it up; null if it cannot. */
        public BlockState[] aboveAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c == null ? null : c.above();
        }

        /** How deep this column was captured; a deformation may not carve past it. */
        public int depthAt(int x, int z) {
            Column c = columns.get(key(x, z));
            return c == null ? 0 : c.stack().length;
        }

        /** Large volcanoes over the corridor, looked up by the planner before it starts. */
        private final java.util.List<com.jeladastudios.ftsgeology.volcano.VolcanoField.Site> volcanoes =
                new java.util.ArrayList<>();
        /** Where to look for them: the level and the corridor's bounds, or null once looked. */
        private ServerLevel volcanoLevel;
        private int[] volcanoBox;

        /**
         * Finds the large volcanoes over the corridor. Working a volcano cell out costs a tenth of a second or more, and
         * a long rupture crosses a dozen; the planner does it off the server thread.
         */
        void findVolcanoes() {
            if (volcanoBox == null) return;
            volcanoes.addAll(com.jeladastudios.ftsgeology.volcano.VolcanoField.sitesInBox(volcanoLevel,
                    volcanoBox[0], volcanoBox[1], volcanoBox[2], volcanoBox[3]));
            volcanoBox = null;
            volcanoLevel = null;
        }

        /**
         * How much of the quake a column takes: all of it in open country, a quarter rising to all of it across
         * a large volcano's body, and none in the zone round its crater, so the mountain keeps its shape, its
         * flows and its summit lake through a rupture.
         */
        public double quakeFactorAt(int x, int z) {
            double f = 1.0;
            for (com.jeladastudios.ftsgeology.volcano.VolcanoField.Site s : volcanoes) {
                double body = s.edificeReach();
                double d = Math.hypot(x - s.x(), z - s.z());
                if (d >= body) continue;
                double zone = Math.max(40.0, body * 0.15);
                double k = d <= zone ? 0.0 : 0.25 + 0.75 * smootherstep((d - zone) / Math.max(1.0, body - zone));
                f = Math.min(f, k);
            }
            return f;
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
        SnapshotJob job = new SnapshotJob(level, trace, type, magnitude, clip, null, null);
        job.step(Long.MAX_VALUE / 4);
        return job.snapshot();
    }

    /**
     * A snapshot read a slice at a time. A great rupture's corridor is a hundred thousand columns and more, and read in
     * one go it held the server for seconds; the warning before the ground moves is time enough to read it in steps.
     * Only the chunks loaded when the quake struck are read -- the rest were parked for when they load -- and one of
     * those that has left memory since is handed to {@code gone} to be parked in its turn.
     */
    public static final class SnapshotJob {
        private final ServerLevel level;
        private final List<TracePoint> trace;
        private final FaultType type;
        private final double magnitude;
        private final ChunkPos clip;
        private final it.unimi.dsi.fastutil.longs.LongSet only;
        private final java.util.function.LongConsumer gone;
        private final Snapshot snap = new Snapshot();
        private final int band;
        private final boolean structures = GeyserConfig.QUAKES_BREAK_STRUCTURES.get();
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Per chunk, read once: what players placed there, and the pieces of the structures standing in it.
        private final it.unimi.dsi.fastutil.longs.Long2ObjectMap<it.unimi.dsi.fastutil.longs.LongSet> placed =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        private final it.unimi.dsi.fastutil.longs.Long2ObjectMap<List<net.minecraft.world.level.levelgen.structure.BoundingBox>> boxes =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        private final it.unimi.dsi.fastutil.longs.LongSet missing = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private int next, steps;
        private boolean done;
        /** Where the time goes: in all, the chunk lookups, the ground, the columns under it, what stands on it. */
        private long nanos, chunkNanos, groundNanos, stackNanos, carryNanos, volcanoNanos;

        public SnapshotJob(ServerLevel level, List<TracePoint> trace, FaultType type, double magnitude, ChunkPos clip,
                           it.unimi.dsi.fastutil.longs.LongSet only, java.util.function.LongConsumer gone) {
            this.level = level;
            this.trace = trace;
            this.type = type;
            this.magnitude = magnitude;
            this.clip = clip;
            this.only = only;
            this.gone = gone;
            this.band = deformationHalfWidth(type, magnitude);
        }

        public Snapshot snapshot() {
            return snap;
        }

        public boolean done() {
            return done;
        }

        /** Reads trace points for about {@code budgetNanos}, one at the least; true once the whole corridor is in. */
        public boolean step(long budgetNanos) {
            if (done) return true;
            long start = System.nanoTime(), deadline = start + budgetNanos;
            steps++;
            do {
                if (next >= trace.size() || snap.size() >= MAX_SNAPSHOT_COLUMNS) {
                    finish();
                    break;
                }
                take(next++);
            } while (System.nanoTime() < deadline);
            nanos += System.nanoTime() - start;
            return done;
        }

        /** One line on how long the reading took and where the time went. */
        public String timing() {
            return String.format(java.util.Locale.ROOT,
                    "%d columns in %d ms over %d ticks (chunks %d, ground %d, columns %d, cover %d, bounds %d ms)",
                    snap.size(), nanos / 1_000_000, steps, chunkNanos / 1_000_000, groundNanos / 1_000_000,
                    stackNanos / 1_000_000, carryNanos / 1_000_000, volcanoNanos / 1_000_000);
        }

        private void take(int i) {
            TracePoint tp = trace.get(i);
            TracePoint after = i + 1 < trace.size() ? trace.get(i + 1) : null;
            forEachCorridorColumn(tp, after, band, false, clip, (cx, cz, across, lsx, lsz, slip) -> {
                if (snap.has(cx, cz)) return;
                long t0 = System.nanoTime();
                long ck = ChunkPos.asLong(cx >> 4, cz >> 4);
                if (only != null && !only.contains(ck)) return;
                // Only chunks already in memory, and read straight from them: a height or a block read through the
                // level waited for a neighbour chunk to load whenever a replayed chunk's margin reached into one.
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx >> 4, cz >> 4);
                if (chunk == null) {
                    if (only != null && gone != null && missing.add(ck)) gone.accept(ck);
                    return;
                }
                it.unimi.dsi.fastutil.longs.LongSet mine = placed.computeIfAbsent(ck,
                        k -> PlayerBuilt.inChunk(level, cx >> 4, cz >> 4));
                List<net.minecraft.world.level.levelgen.structure.BoundingBox> pieces = structures
                        ? boxes.computeIfAbsent(ck, k -> ShakingDamage.structureBoxes(level, chunk, level.getMinBuildHeight()))
                        : List.of();
                long t1 = System.nanoTime();
                int[] ground = naturalGround(chunk, cx, cz, mine, pieces, m);
                long t2 = System.nanoTime();
                chunkNanos += t1 - t0;
                groundNanos += t2 - t1;
                if (ground == null) return;
                int g = ground[0];
                // Recorded before anything is dug: a rift that opens under the sea builds new crust on
                // its floor, and afterwards there is no way to tell it was ever under water.
                boolean wet = !chunk.getBlockState(m.set(cx, g + 1, cz)).getFluidState().isEmpty();
                int need = captureDepth(type, magnitude, across);
                BlockState[] stack = new BlockState[need];
                for (int d = 0; d < need; d++) {
                    int y = g - d;
                    stack[d] = y < level.getMinBuildHeight()
                            ? Blocks.BEDROCK.defaultBlockState()
                            : chunk.getBlockState(m.set(cx, y, cz));
                }
                // A rift's step is ground the world made, and moves with the rest whatever the builds setting says.
                boolean generated = ground[3] != 0 || !pieces.isEmpty() && ShakingDamage.inside(pieces, cx, g + 1, cz);
                boolean built = ground[1] != Integer.MIN_VALUE;
                long t3 = System.nanoTime();
                BlockState[] above = built || wet ? null : carried(chunk, cx, g, cz, m);
                long t4 = System.nanoTime();
                stackNanos += t3 - t2;
                carryNanos += t4 - t3;
                snap.columns.put(Snapshot.key(cx, cz), new Snapshot.Column(g, wet, generated,
                        built, ground[2] != 0, stack, above));
            });
        }

        /** The captured ground's bounds, where the planner looks for large volcanoes so it can spare their bodies. */
        private void finish() {
            done = true;
            if (snap.size() == 0) return;
            long t0 = System.nanoTime();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (long k : snap.columns.keySet()) {
                int x = (int) k, z = (int) (k >>> 32);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            snap.volcanoLevel = level;
            snap.volcanoBox = new int[]{minX, minZ, maxX, maxZ};
            volcanoNanos = System.nanoTime() - t0;
        }
    }

    /** How far down from the top of a column the ground under a building or a forest is looked for. */
    private static final int GROUND_WALK = 128;

    /**
     * The terrain of a column, under whatever stands on it: {ground Y, top of what is built on it, or
     * {@link Integer#MIN_VALUE}, 1 if a player built it, 1 if the ground is a rift's step}. Trees in a village's bounds
     * are trees, not the village. Plants, trees and water are passed over, and so is anything built -- what a player
     * placed, a structure's pieces other than its ground, and worked blocks -- so a quake moves the land, not a roof.
     * The ground used to be the first solid block from the top: a village's roof, or the lantern on a post, and an
     * uplift stacked copies of it, beds and torches in towers.
     */
    static int[] naturalGround(net.minecraft.world.level.chunk.LevelChunk chunk, int x, int z,
                               it.unimi.dsi.fastutil.longs.LongSet placed,
                               List<net.minecraft.world.level.levelgen.structure.BoundingBox> pieces,
                               BlockPos.MutableBlockPos m) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int floor = chunk.getMinBuildHeight();
        int builtTop = Integer.MIN_VALUE, player = 0;
        for (int y = top, steps = 0; y > floor && steps < GROUND_WALK; y--, steps++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            boolean mine = !placed.isEmpty() && placed.contains(m.asLong());
            // Trees and plants first, and cheaply: most of a forest's column is its crown. A log post in a structure's
            // bounds is the building's, not a tree.
            boolean inStructure = !pieces.isEmpty() && ShakingDamage.inside(pieces, x, y, z);
            if (!mine && (TerrainProbe.isVegetation(s) || TerrainProbe.isTreePart(s) && !ShakingDamage.stripped(s)
                    && !(inStructure && ShakingDamage.builtLog(chunk, s, x, y, z)))) {
                continue;
            }
            if (!inStructure && !mine && s.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                    && com.jeladastudios.ftsgeology.worldgen.RiftSteps.isStep(s, chunk.getBlockState(new BlockPos(x, y - 1, z)))) {
                return new int[]{y, builtTop, player, 1};
            }
            boolean byPlayer = mine || (!inStructure && EruptionHandler.isWorked(s));
            if (byPlayer || (inStructure && !ShakingDamage.ground(s))) {
                if (builtTop == Integer.MIN_VALUE) builtTop = y;
                if (byPlayer) player = 1;
                continue;
            }
            if (!s.getFluidState().isEmpty()) continue;
            return new int[]{y, builtTop, player, 0};
        }
        return null;
    }

    /** How tall a stand of trees and plants a moving column carries along. */
    private static final int CARRY_REACH = 40;
    private static final BlockState[] NOTHING = new BlockState[0];

    /**
     * What stands on a column's ground and moves with it when the ground moves: plants, trees, snow, from the block
     * over the ground up to the last of them, the air between included. Ground that rises lifts its trees with it and
     * ground that drops lowers them, where they used to be buried by the rising ground or left hanging over the
     * dropped floor. Null when anything else stands there -- a block that holds contents, water, rock overhead -- and
     * that column moves the old way.
     */
    static BlockState[] carried(net.minecraft.world.level.chunk.LevelChunk chunk, int x, int g, int z,
                                BlockPos.MutableBlockPos m) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        if (top <= g) return NOTHING;
        if (top - g > CARRY_REACH) return null;
        BlockState[] up = new BlockState[top - g];
        int last = -1;
        for (int y = g + 1; y <= top; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (!s.isAir()) {
                if (s.hasBlockEntity() || !(TerrainProbe.isTreePart(s) || TerrainProbe.isVegetation(s))
                        || ShakingDamage.stripped(s)) return null;
                last = y - g - 1;
            }
            up[y - g - 1] = s;
        }
        return last < 0 ? NOTHING : java.util.Arrays.copyOf(up, last + 1);
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
        return plan(snap, trace, epicentre, type, magnitude, depthMetres, rng, mayBreakBuilds, null);
    }

    /** As above, walking only the lattice round one chunk: for a parked rupture replayed there. */
    public static Plan plan(Snapshot snap, List<TracePoint> trace, BlockPos epicentre, FaultType type,
                            double magnitude, double depthMetres, RandomGenerator rng,
                            boolean mayBreakBuilds, ChunkPos clip) {
        int cap = GeyserConfig.QUAKE_MAX_EDITS.get();
        int band = deformationHalfWidth(type, magnitude);
        snap.findVolcanoes();

        // Phase one: decide what every column does. The order edits go out in is decided below.
        List<ColumnPlan> columns = new ArrayList<>();

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
        // Each column is measured from the nearest stretch of the trace, by its real distance to it. Measured from
        // whichever stretch reached it first, a column where the trace wanders took its distance across the fault from
        // a line fifty blocks off: uplift in patches, and strips of ground left standing down the middle of a range.
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<double[]> nearest = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (int step = 0; step < trace.size(); step++) {
            // mid, mid-1, mid+1, mid-2, mid+2, ... so both halves are laid out together.
            int offset = (step + 1) / 2;
            int i = (step % 2 == 0) ? mid - offset : mid + offset;
            if (i < 0 || i >= trace.size()) continue;

            TracePoint tp = trace.get(i);
            TracePoint next = i + 1 < trace.size() ? trace.get(i + 1) : null;
            length += TRACE_STEP;
            int order = step;

            forEachCorridorColumn(tp, next, band, false, clip, (x, z, across, lsx, lsz, slip) -> {
                if (!snap.has(x, z)) return;
                long key = Snapshot.key(x, z);
                double[] had = nearest.get(key);
                if (had != null && had[0] <= Math.abs(across)) return;
                nearest.put(key, new double[]{Math.abs(across), across, lsx, lsz, slip, order, x, z});
            });
        }
        // Planned in the order the stretches were laid out, outward from the epicentre.
        List<double[]> measured = new ArrayList<>(nearest.values());
        measured.sort(java.util.Comparator.comparingDouble((double[] m) -> m[5]));
        for (double[] m : measured) {
            ColumnPlan cp = columnPlan(snap, type, (int) m[6], (int) m[7], m[1], m[2], m[3], m[4],
                    magnitude, rng, mayBreakBuilds);
            if (cp != null) columns.add(cp);
        }

        // Every tree moves with the ground its trunk stands on.
        alignTrees(snap, columns);

        // What stood on ground that moved goes down with it: those columns are wrecked once the ground has moved.
        it.unimi.dsi.fastutil.longs.LongArrayList wrecked = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (ColumnPlan c : columns) {
            if (snap.builtAt(c.x(), c.z())) wrecked.add(BlockPos.asLong(c.x(), c.top(), c.z()));
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
        return new Plan(epicentre, type, magnitude, depthMetres, length, List.copyOf(ordered), wrecked.toLongArray());
    }

    /**
     * What one column of ground is going to do, held until the edits are emitted. A column whose cover moves with it
     * ({@code above} not null) carries its surface and the {@code low} blocks of low cover over it with its own ground,
     * {@code delta}, and the tree over it with the ground its tree's trunk stands on, {@code treeDelta}.
     */
    private record ColumnPlan(int x, int z, int top, int delta, BlockState cap, BlockState fill, BlockState[] above,
                              int low, int treeDelta) {
        ColumnPlan(int x, int z, int top, int delta, BlockState cap, BlockState fill, BlockState[] above) {
            this(x, z, top, delta, cap, fill, above, lowCover(above), delta);
        }

        /** How many one-block steps of movement this column goes through. */
        int steps() {
            return Math.max(1, above == null ? Math.abs(delta) : Math.max(Math.abs(delta), Math.abs(treeDelta)));
        }

        ColumnPlan withTree(int treeDelta) {
            return new ColumnPlan(x, z, top, delta, cap, fill, above, low, treeDelta);
        }
    }

    /** How much of what stands on a column is low cover and not tree: everything under the first block of a tree. */
    private static int lowCover(BlockState[] above) {
        if (above == null) return 0;
        for (int i = 0; i < above.length; i++) {
            if (TerrainProbe.isTreePart(above[i])) return i;
        }
        return above.length;
    }

    /**
     * What a column whose cover moves with it holds at {@code y} after {@code layer} layers, or null where it is left
     * as it was. Rising ground is filled in under the new surface, and the old surface stays buried; dropping ground
     * loses the rock under its surface, and the surface comes down whole -- a tree's rooted soil with it. On the
     * surface stands its low cover, and over that the tree, moved with the ground its trunk stands on. Where the two
     * meet, the ground wins, then the tree.
     */
    private static BlockState carriedAt(ColumnPlan c, int layer, int y) {
        int g = Integer.signum(c.delta()) * Math.min(layer, Math.abs(c.delta()));
        int t = Integer.signum(c.treeDelta()) * Math.min(layer, Math.abs(c.treeDelta()));
        int top = c.top(), base = top + g, low = c.low();
        BlockState[] above = c.above();
        if (c.delta() > 0) {
            if (y <= top) return null;
            if (y < base) return c.fill();
            if (y == base) return g == c.delta() ? c.cap() : c.fill();
        } else {
            if (y < base) return null;
            if (y == base) return c.cap();
        }
        if (y <= base + low) {
            BlockState s = above[y - base - 1];
            if (!s.isAir()) return s;
        }
        int i = y - (top + 1 + low + t);
        if (i >= 0 && i < above.length - low && !above[low + i].isAir()) return above[low + i];
        return Blocks.AIR.defaultBlockState();
    }

    /** One layer of a column whose cover moves with it: only the blocks that change, so a trunk costs a few. */
    private static void emitCarried(ColumnPlan c, int layer, List<Edit> out) {
        int reach = Math.max(Math.abs(c.delta()), Math.abs(c.treeDelta()));
        if (layer > reach) return;
        int from = c.top() - Math.abs(c.delta()) - 1, to = c.top() + c.above().length + reach + 2;
        for (int y = from; y <= to; y++) {
            BlockState now = carriedAt(c, layer, y);
            if (now == null) continue;
            if (now != carriedAt(c, layer - 1, y)) out.add(new Edit(new BlockPos(c.x(), y, c.z()), now, true));
        }
    }

    /** How far from its trunk a tree's crown is looked for. */
    private static final int CROWN_REACH = 6;

    /**
     * Makes every tree move as one, with the ground its trunk stands on, whatever the ground under its crown does: a
     * crown spread over columns that moved apart came out in pieces, and Dynamic Trees' trees fell apart with it.
     * Trunks side by side are one tree. A column of crown over ground that does not move at all gets a plan of its own.
     */
    private static void alignTrees(Snapshot snap, List<ColumnPlan> columns) {
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap index = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        for (int i = 0; i < columns.size(); i++) index.put(Snapshot.key(columns.get(i).x(), columns.get(i).z()), i);
        LongOpenHashSet trunks = new LongOpenHashSet();
        for (var e : snap.columns.long2ObjectEntrySet()) {
            BlockState[] a = e.getValue().above();
            if (a != null && a.length > 0 && a[0].is(net.minecraft.tags.BlockTags.LOGS)) trunks.add(e.getLongKey());
        }
        if (trunks.isEmpty()) return;
        // Each tree's move: the mean of its trunk columns'.
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap treeDelta = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long t0 : trunks) {
            if (!seen.add(t0)) continue;
            it.unimi.dsi.fastutil.longs.LongArrayList tree = new it.unimi.dsi.fastutil.longs.LongArrayList();
            tree.add(t0);
            for (int gi = 0; gi < tree.size(); gi++) {
                long k = tree.getLong(gi);
                int x = (int) k, z = (int) (k >>> 32);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long n = Snapshot.key(x + dx, z + dz);
                        if (trunks.contains(n) && seen.add(n)) tree.add(n);
                    }
                }
            }
            int sum = 0;
            for (long k : tree) {
                int i = index.get(k);
                sum += i < 0 ? 0 : columns.get(i).delta();
            }
            int d = Math.round((float) sum / tree.size());
            for (long k : tree) treeDelta.put(k, d);
        }
        // Each column with tree over it takes the move of the nearest trunk.
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap nearest = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        nearest.defaultReturnValue(Integer.MAX_VALUE);
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap moves = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap owner = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        for (long t0 : trunks) {
            int tx = (int) t0, tz = (int) (t0 >>> 32), d = treeDelta.get(t0);
            for (int dx = -CROWN_REACH; dx <= CROWN_REACH; dx++) {
                for (int dz = -CROWN_REACH; dz <= CROWN_REACH; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > CROWN_REACH * CROWN_REACH) continue;
                    long k = Snapshot.key(tx + dx, tz + dz);
                    if (k != t0 && trunks.contains(k)) continue;
                    Snapshot.Column col = snap.columns.get(k);
                    if (col == null || col.above() == null || lowCover(col.above()) >= col.above().length) continue;
                    if (d2 >= nearest.get(k)) continue;
                    nearest.put(k, d2);
                    moves.put(k, d);
                    owner.put(k, t0);
                }
            }
        }
        // Crown over ground that stays gets a plan of its own, laid out next to its trunk's.
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<ColumnPlan>> extra = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        List<ColumnPlan> orphans = new ArrayList<>();
        for (var e : moves.long2IntEntrySet()) {
            long k = e.getLongKey();
            int td = e.getIntValue(), i = index.get(k);
            if (i >= 0) {
                ColumnPlan p = columns.get(i);
                if (p.above() != null && p.treeDelta() != td) columns.set(i, p.withTree(td));
            } else if (td != 0) {
                Snapshot.Column col = snap.columns.get(k);
                if (col.stack().length == 0) continue;
                ColumnPlan p = new ColumnPlan((int) k, (int) (k >>> 32), col.groundY(), 0, col.stack()[0], null, col.above(),
                        lowCover(col.above()), td);
                long t0 = owner.get(k);
                if (index.get(t0) >= 0) extra.computeIfAbsent(t0, x -> new ArrayList<>()).add(p);
                else orphans.add(p);
            }
        }
        if (extra.isEmpty() && orphans.isEmpty()) return;
        List<ColumnPlan> merged = new ArrayList<>(columns.size() + orphans.size() + extra.size() * 8);
        for (ColumnPlan p : columns) {
            merged.add(p);
            List<ColumnPlan> more = extra.get(Snapshot.key(p.x(), p.z()));
            if (more != null) merged.addAll(more);
        }
        merged.addAll(orphans);
        columns.clear();
        columns.addAll(merged);
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
        // What stands on the ground moves with it.
        if (c.above() != null) {
            emitCarried(c, layer, out);
            return;
        }
        if (c.delta() > 0) {
            if (layer > c.delta()) return;
            out.add(new Edit(new BlockPos(c.x(), c.top() + layer, c.z()),
                    layer == c.delta() ? c.cap() : c.fill()));
        } else if (c.delta() < 0) {
            int cut = -c.delta();
            if (layer > cut) return;
            out.add(new Edit(new BlockPos(c.x(), c.top() - (layer - 1), c.z()), Blocks.AIR.defaultBlockState()));
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
        // A player's build moves, and goes down, only where quakes may break builds.
        if (!mayBreakBuilds && snap.playerBuiltAt(x, z)) return null;
        if (type == FaultType.TRANSFORM) {
            return strikeSlipPlan(snap, x, z, across, sx, sz, slip, magnitude, rng, mayBreakBuilds);
        }
        int delta = switch (type) {
            case DIVERGENT -> riftDelta(across, slip, magnitude, rng);
            case CONVERGENT_SUBDUCTION -> subductionDelta(across, slip, magnitude);
            case CONVERGENT_COLLISION -> collisionDelta(across, slip, magnitude);
            default -> 0;
        };
        // A large volcano's body takes a share of the movement and its crater zone none; see quakeFactorAt.
        delta = (int) Math.round(delta * snap.quakeFactorAt(x, z));
        if (delta == 0) return null;
        int top = snap.groundAt(x, z);
        if (delta > 0) {
            BlockState surface = snap.stateAt(x, z, 0);
            if (!liftable(surface, mayBreakBuilds, snap.generatedAt(x, z))) return null;
            return new ColumnPlan(x, z, top, delta, surface, deeper(snap, x, z, rng), snap.aboveAt(x, z));
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
        // A rift's fissure swallows what stood on it, which is felled after; the rest of the floor lowers it.
        boolean fissure = type == FaultType.DIVERGENT && Math.abs(across) <= 1.2;
        BlockState[] above = fissure ? null : snap.aboveAt(x, z);
        return new ColumnPlan(x, z, top, -cut, floor != null ? floor : above != null ? snap.stateAt(x, z, 0) : null, null,
                above);
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
        // Offsets are carried whole, not scaled, so ground a large volcano mostly shields stays where it is.
        if (snap.quakeFactorAt(x, z) < 0.5) return null;
        int top = snap.groundAt(x, z);

        // The mole track, with the odd sag pond where the fault steps.
        if (Math.abs(across) <= 1.2) {
            int trough = Mth.clamp((int) Math.round(slip * magnitude * 0.25), 1, 3);
            if (rng.nextDouble() < 0.06) trough += 2;
            int cut = carvableDepth(snap, x, z, trough, mayBreakBuilds);
            BlockState[] above = snap.aboveAt(x, z);
            return cut < 1 ? null : new ColumnPlan(x, z, top, -cut, above != null ? snap.stateAt(x, z, 0) : null, null, above);
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

        if (delta > 0) return new ColumnPlan(x, z, top, delta, carried, deeper(snap, fx, fz, rng), snap.aboveAt(x, z));
        if (delta < 0) {
            int cut = carvableDepth(snap, x, z, -delta, mayBreakBuilds);
            return cut < 1 ? null : new ColumnPlan(x, z, top, -cut, carried, null, snap.aboveAt(x, z));
        }
        // Same height: the offset still shows, because the ground cover itself has moved.
        return new ColumnPlan(x, z, top, 0, carried, null, null);
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
        if (GeyserConfig.QUAKE_PRESERVES_ORES.get() && s.is(net.minecraftforge.common.Tags.Blocks.ORES)) return false;
        // A world-made structure moves like any other ground; see Snapshot.generatedAt.
        return mayBreakBuilds || generated || !EruptionHandler.isPlayerPlaced(s);
    }

    /** May the quake pick this block up and move or stack it? Same rules, plus it must be solid. */
    private static boolean liftable(BlockState s, boolean mayBreakBuilds, boolean generated) {
        if (s == null || s.isAir() || s.is(Blocks.BEDROCK)) return false;
        if (!s.getFluidState().isEmpty()) return false;
        if (TerrainProbe.isVegetation(s)) return false;   // nothing to carry; it is just ground cover
        if (machinery(s)) return false;
        if (GeyserConfig.QUAKE_PRESERVES_ORES.get() && s.is(net.minecraftforge.common.Tags.Blocks.ORES)) return false;
        // A world-made structure moves like any other ground; see Snapshot.generatedAt.
        return mayBreakBuilds || generated || !EruptionHandler.isPlayerPlaced(s);
    }
}
