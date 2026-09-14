package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the mod knows about the rivers: each chunk's river cells, water level and banks, read once when the
 * chunk is loaded, and the bends worked out from them. Kept per dimension in the world save, so a bend
 * goes on shifting after a restart and a chunk is never surveyed twice for the same ground.
 *
 * <p>Nothing here reads a chunk that is not loaded. A bend needs the river either side of its own chunk,
 * so it is worked out only once the neighbouring chunks have been surveyed too, from what they recorded.</p>
 */
public final class RiverSurvey extends SavedData {

    static final String NAME = "fts_geology_rivers";

    /** Widest channel handled as one river; a braided Terralith river is treated as this wide. */
    static final int MAX_WIDTH = 24;
    /**
     * Widest channel that meanders here. A river wider than this is a big river, whose bends are kilometres long
     * and outside the scale of a survey window; cut and filled at this scale it only looked bitten.
     */
    static final int BIG_RIVER = 16;
    /** Highest bank, over the water, a bend may still cut into. A canyon does not meander. */
    static final int MAX_BANK = 4;
    /** Furthest a bend may shift, as a share of the channel width. */
    static final double MAX_SHIFT = 0.6;

    /** One chunk's river, or the fact that it has none. */
    public static final class Rec {
        /** River water at the surface, one bit a column, bit {@code lz * 16 + lx}; null where there is no river. */
        long[] mask;
        /** Ground top over the water level, one byte a column, clamped; the water surface itself is 0. */
        byte[] rel;
        /** Y of the top water block, or {@link Integer#MIN_VALUE} where the chunk has no river. */
        int yW = Integer.MIN_VALUE;
        /** Blocks of water under the surface. */
        int bed = 1;
        /** An ocean biome in the chunk: a river mouth, which does not meander. */
        boolean coast;
        /** Bumped when the ground changes; the survey is stale when it no longer matches {@link #surveyed}. */
        int ver;
        int surveyed = -1;
        boolean planned;
        final List<Bend> bends = new ArrayList<>();
        /** The channel's centre line through this chunk, as (lx, lz) pairs; null until planned. For the debug view. */
        byte[] centre;

        boolean river() { return yW != Integer.MIN_VALUE; }
        boolean current() { return surveyed == ver; }
        boolean at(int lx, int lz) { return mask != null && (mask[lz >> 2] >>> ((lz & 3) * 16 + lx) & 1L) != 0; }
        int rel(int lx, int lz) { return rel == null ? Integer.MIN_VALUE : rel[lz * 16 + lx]; }
    }

    /** One bend shifting outward: its apex, the outward normal, and how far it has to go. */
    public static final class Bend {
        int x, z;
        float nx, nz;
        int width, steps, done;
        /** How fast the water runs here, 0.5 to 1.5; the bar is gravelly where it is fast. */
        float speed = 1.0f;
        long next;
        boolean dead;
        /** Why the last step could not move, for the debug view; not saved. */
        String why = "";
        /** What kind of work this is: {@link #KIND_BEND}, or {@link #KIND_DELTA} where a river enters a lake. */
        byte kind = KIND_BEND;

        boolean live() { return !dead && done < steps; }
    }

    static final byte KIND_BEND = 0, KIND_DELTA = 1;

    final Long2ObjectOpenHashMap<Rec> recs = new Long2ObjectOpenHashMap<>();

    public static RiverSurvey of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RiverSurvey::load, RiverSurvey::new, NAME);
    }

    static long key(int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }

    Rec get(int cx, int cz) {
        return recs.get(key(cx, cz));
    }

    Rec rec(int cx, int cz) {
        return recs.computeIfAbsent(key(cx, cz), k -> new Rec());
    }

    // === Survey =============================================================

    /** Reads one loaded chunk's river: where the water is, how high it stands, how deep, and the ground round it. */
    void survey(ServerLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        Rec r = rec(cp.x, cp.z);
        long[] mask = new long[4];
        byte[] rel = new byte[256];
        int[] tops = new int[256];
        int[] levels = new int[512];
        boolean coast = false;
        int riverCells = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                // A chunk answers with the highest block itself, unlike a level, which answers one above it.
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                tops[lz * 16 + lx] = top;
                if (top < level.getMinBuildHeight()) continue;
                Holder<Biome> b = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(top), QuartPos.fromBlock(z));
                if (ocean(b)) coast = true;
                if (!river(b)) continue;
                if (!chunk.getBlockState(m.set(x, top, z)).getFluidState().is(FluidTags.WATER)) continue;
                mask[lz >> 2] |= 1L << ((lz & 3) * 16 + lx);
                riverCells++;
                levels[Mth.clamp(top + 128, 0, 511)]++;
            }
        }
        r.coast = coast;
        r.planned = false;
        r.bends.clear();
        r.surveyed = r.ver;
        if (riverCells == 0) {
            r.mask = null;
            r.rel = null;
            r.yW = Integer.MIN_VALUE;
            setDirty();
            return;
        }
        int best = 0;
        for (int i = 1; i < levels.length; i++) if (levels[i] > levels[best]) best = i;
        int yW = best - 128;
        // The bed under the water cells at that level.
        int[] depths = new int[8];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if ((mask[lz >> 2] >>> ((lz & 3) * 16 + lx) & 1L) == 0 || tops[lz * 16 + lx] != yW) continue;
                int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz);
                depths[Mth.clamp(yW - floor, 1, 7)]++;
            }
        }
        int bed = 1;
        for (int i = 2; i < depths.length; i++) if (depths[i] > depths[bed]) bed = i;
        for (int i = 0; i < 256; i++) rel[i] = (byte) Mth.clamp(tops[i] - yW, -120, 120);
        r.mask = mask;
        r.rel = rel;
        r.yW = yW;
        r.bed = bed;
        setDirty();
    }

    static boolean river(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_RIVER)) return true;
        return b.unwrapKey().map(k -> k.location().getPath().toLowerCase(Locale.ROOT).contains("river")).orElse(false);
    }

    static boolean ocean(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN)) return true;
        return b.unwrapKey().map(k -> k.location().getPath().toLowerCase(Locale.ROOT).contains("ocean")).orElse(false);
    }

    /** True once this chunk and the eight round it have all been surveyed on their current ground. */
    boolean neighbourhoodKnown(int cx, int cz) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Rec n = get(cx + ox, cz + oz);
                if (n == null || !n.current()) return false;
            }
        }
        return true;
    }

    // === Bends ==============================================================

    /** The window a chunk's bends are worked out on: the chunk and its neighbours, 48 blocks a side. */
    static final int WIN = 48;

    /**
     * Works out the bends of one chunk from its river and its neighbours': the channel's centre line is thinned
     * out of the water cells, its curvature read along that line, and where the curve is tight enough for the
     * width, a bend is planned to shift outward by {@code scale * curvature * width^2}, never more than
     * {@link #MAX_SHIFT} of the width.
     */
    boolean plan(ServerLevel level, int cx, int cz) {
        Rec r = rec(cx, cz);
        if (!r.river()) {
            r.planned = true;
            setDirty();
            return true;
        }
        // The river as a whole first: which way it flows here, how much it carries, whether this is a lake.
        // The first river cell; west of the origin the coordinate is negative, so "not found" is a sentinel, not a sign.
        int rx = Integer.MIN_VALUE, rz = Integer.MIN_VALUE;
        for (int i = 0; i < 256 && rx == Integer.MIN_VALUE; i++) {
            if (r.at(i & 15, i >> 4)) { rx = cx * 16 + (i & 15); rz = cz * 16 + (i >> 4); }
        }
        RiverNetwork.Node here = RiverNetwork.at(level, rx, rz, r.yW);
        if (here == null && !RiverNetwork.known(rx, rz)) return false;   // still being read; asked again later
        if (here == null) com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("chunk {},{}: planned without a network node at {},{}: {}", cx, cz, rx, rz, RiverNetwork.describe(rx, rz));
        r.planned = true;
        r.bends.clear();
        setDirty();
        planDelta(level, cx, cz, r);
        if (here != null && here.lake()) return true;
        boolean[][] water = new boolean[WIN][WIN];
        int[][] rel = new int[WIN][WIN];
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Rec n = get(cx + ox, cz + oz);
                if (n == null) return true;
                // A river mouth, or water at another level next door, is not a meandering reach.
                if (n.coast) return true;
                if (n.river() && Math.abs(n.yW - r.yW) > 1) return true;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int wx = (ox + 1) * 16 + lx, wz = (oz + 1) * 16 + lz;
                        water[wx][wz] = n.river() && n.at(lx, lz);
                        rel[wx][wz] = n.rel == null ? 0 : n.rel(lx, lz) + (n.river() ? n.yW - r.yW : 0);
                    }
                }
            }
        }
        int[][] dist = distance(water);
        boolean[][] skel = thin(water);
        // The centre line through this chunk, kept for the debug view.
        java.io.ByteArrayOutputStream centre = new java.io.ByteArrayOutputStream();
        for (int wz = 16; wz < 32; wz++) {
            for (int wx = 16; wx < 32; wx++) {
                if (skel[wx][wz]) { centre.write(wx - 16); centre.write(wz - 16); }
            }
        }
        r.centre = centre.toByteArray();
        double scale = GeyserConfig.RIVER_MIGRATION_SCALE.get();
        int x0 = (cx - 1) * 16, z0 = (cz - 1) * 16;
        List<double[]> found = new ArrayList<>();   // wx, wz, curvature, width, nx, nz, speed
        for (int wz = 16; wz < 32; wz++) {
            for (int wx = 16; wx < 32; wx++) {
                if (!skel[wx][wz]) continue;
                int width = Math.min(MAX_WIDTH, 2 * dist[wx][wz] - 1);
                if (width < 3) continue;
                // A lake, or the delta at the mouth: the water stands still, the banks stay.
                RiverNetwork.Node node = RiverNetwork.at(level, x0 + wx, z0 + wz, r.yW);
                if (node != null && (node.lake() || (node.directed() && node.dist() < RiverNetwork.MOUTH_ZONE))) continue;
                int h = Math.max(4, width);
                int[] back = trace(skel, wx, wz, h, -1, -1);
                if (back == null) continue;
                int[] fore = trace(skel, wx, wz, h, back[3], back[4]);
                if (fore == null || back[2] < h / 2 || fore[2] < h / 2) continue;
                double ax = back[0] - wx, az = back[1] - wz, bx = fore[0] - wx, bz = fore[1] - wz;
                double chordX = fore[0] - back[0], chordZ = fore[1] - back[1];
                double cross = ax * bz - az * bx;
                double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz), lc = Math.hypot(chordX, chordZ);
                if (la < 1 || lb < 1 || lc < 1) continue;
                double curvature = 2.0 * Math.abs(cross) / (la * lb * lc);
                // The apex bulges to the outside of the bend: the outward normal points from the chord's middle to it.
                double mx = (back[0] + fore[0]) * 0.5 - wx, mz = (back[1] + fore[1]) * 0.5 - wz;
                double lm = Math.hypot(mx, mz);
                if (lm < 0.5) continue;
                double speed = speed(node);
                // A big river's bends are kilometres long and out of this scale: it shifts, but by half.
                double shift = scale * speed * curvature * width * width * (width > BIG_RIVER ? 0.5 : 1.0);
                if (shift < 1.0) continue;
                // The bank is cut hardest a little downstream of the apex, not at it: the work is set out there.
                int px = wx, pz = wz;
                double[] flow = RiverNetwork.flow(x0 + wx, z0 + wz);
                if (flow != null) {
                    boolean foreDown = bx * flow[0] + bz * flow[1] > ax * flow[0] + az * flow[1];
                    int lag = (int) Math.round(1.5 * width);
                    int[] moved = foreDown ? trace(skel, wx, wz, lag, back[3], back[4]) : trace(skel, wx, wz, lag, fore[3], fore[4]);
                    if (moved != null) { px = moved[0]; pz = moved[1]; }
                }
                found.add(new double[] {px, pz, curvature, width, -mx / lm, -mz / lm, speed});
            }
        }
        // The tightest bends first; one bend a channel width.
        found.sort((a, b) -> Double.compare(b[2], a[2]));
        long now = level.getGameTime();
        for (double[] f : found) {
            int wx = (int) f[0], wz = (int) f[1], width = (int) f[3];
            boolean near = false;
            for (Bend b : r.bends) {
                int bx = b.x - x0, bz = b.z - z0;
                if (Math.hypot(bx - wx, bz - wz) < width) { near = true; break; }
            }
            if (near) continue;
            double nx = f[4], nz = f[5];
            // The outer bank has to be there, low enough to cut, and the inner bank within reach.
            int bank = bankAlong(water, rel, wx, wz, nx, nz, width + 4);
            if (bank == Integer.MIN_VALUE || bank > MAX_BANK) continue;
            if (bankAlong(water, rel, wx, wz, -nx, -nz, width + 4) == Integer.MIN_VALUE) continue;
            // Land with water again behind it is an islet or a braid bar, and the water either side of it a side
            // channel: a bend cut there would eat the island and never find its bank.
            if (islandAlong(water, wx, wz, nx, nz, width + 4) || islandAlong(water, wx, wz, -nx, -nz, width + 4)) continue;
            int steps = (int) Math.min(Math.floor(MAX_SHIFT * width),
                    Math.round(scale * f[6] * f[2] * width * width * (width > BIG_RIVER ? 0.5 : 1.0)));
            if (steps < 1) continue;
            Bend b = new Bend();
            b.x = x0 + wx;
            b.z = z0 + wz;
            b.nx = (float) nx;
            b.nz = (float) nz;
            b.width = width;
            b.steps = steps;
            b.speed = (float) f[6];
            b.next = now + MeanderScheduler.interval(steps);
            r.bends.add(b);
        }
        return true;
    }

    /**
     * How fast the water runs, from what it carries: a river's velocity grows slowly with its discharge. Half
     * speed on a headwater trickle, half again as fast on a river with a thousand cells behind it.
     */
    static double speed(RiverNetwork.Node node) {
        if (node == null) return 1.0;
        return Mth.clamp(0.5 + 0.5 * Math.log10(1.0 + node.upstream() / 200.0), 0.5, 1.5);
    }

    /**
     * Where the river's channel opens into a lake in this chunk, one piece of delta work: the water slows to nothing
     * there and drops what it carries in a fan. The entry is a channel cell of the lake's shore band next to a cell
     * of the open water (half a channel width and more to the bank in every direction).
     */
    private void planDelta(ServerLevel level, int cx, int cz, Rec r) {
        long now = level.getGameTime();
        for (int lz = 2; lz < 16; lz += 4) {
            for (int lx = 2; lx < 16; lx += 4) {
                if (!r.at(lx, lz)) continue;
                int x = cx * 16 + lx, z = cz * 16 + lz;
                RiverNetwork.Node here = RiverNetwork.peek(x, z);
                if (here == null || !here.lake() || here.halfWidth() >= RiverNetwork.LAKE_HALF) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        RiverNetwork.Node n = RiverNetwork.peek(x + dx * 4, z + dz * 4);
                        if (n == null || n.halfWidth() < RiverNetwork.LAKE_HALF) continue;
                        // The open water has to be there at this level: the biome runs wider than the water does.
                        BlockPos open = new BlockPos(x + dx * 4, r.yW, z + dz * 4);
                        if (!level.hasChunkAt(open) || !MeanderScheduler.riverWater(level.getBlockState(open))) continue;
                        // One delta an entry: the shore band is wide, and every chunk along it would find the same lake.
                        if (deltaNear(cx, cz, x, z, 40)) return;
                        double l = Math.hypot(dx, dz);
                        Bend b = new Bend();
                        b.kind = KIND_DELTA;
                        b.x = x;
                        b.z = z;
                        b.nx = (float) (dx / l);
                        b.nz = (float) (dz / l);
                        b.width = Math.max(4, here.halfWidth() * 8);
                        b.steps = Mth.clamp(b.width, 8, 24);
                        b.speed = (float) speed(here);
                        b.next = now + MeanderScheduler.interval(b.steps);
                        r.bends.add(b);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Every water cell the survey knows within {@code radius} chunks of a chunk, as network cell keys, and those of
     * them in chunks that touch the sea. The network is seeded from these: the survey read the chunk's own biome,
     * which is what the water follows, and the biome source can call the same cell something else.
     */
    void seedsAround(int cx, int cz, int radius, LongOpenHashSet seeds, LongOpenHashSet coast) {
        for (int ox = -radius; ox <= radius; ox++) {
            for (int oz = -radius; oz <= radius; oz++) {
                Rec r = get(cx + ox, cz + oz);
                if (r == null || !r.river()) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (!r.at(lx, lz)) continue;
                        long k = RiverNetwork.key(((cx + ox) * 16 + lx) >> 2, ((cz + oz) * 16 + lz) >> 2);
                        seeds.add(k);
                        if (r.coast) coast.add(k);
                    }
                }
            }
        }
    }

    /** Is there delta work already within {@code reach} blocks of a point, in this chunk or the ones round it? */
    private boolean deltaNear(int cx, int cz, int x, int z, int reach) {
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                Rec n = get(cx + ox, cz + oz);
                if (n == null) continue;
                for (Bend b : n.bends) {
                    if (b.kind == KIND_DELTA && Math.hypot(b.x - x, b.z - z) <= reach) return true;
                }
            }
        }
        return false;
    }

    /** Height of the first bank cell along a bearing over the water, or MIN where the window runs out first. */
    static int bankAlong(boolean[][] water, int[][] rel, int wx, int wz, double nx, double nz, int reach) {
        for (int t = 1; t <= reach; t++) {
            int x = (int) Math.round(wx + nx * t), z = (int) Math.round(wz + nz * t);
            if (x < 0 || z < 0 || x >= WIN || z >= WIN) return Integer.MIN_VALUE;
            if (!water[x][z]) return rel[x][z];
        }
        return Integer.MIN_VALUE;
    }

    /**
     * True when the first land along a bearing is a strip no wider than {@code reach} with water behind it: an
     * island or a bar, not a bank. Unknown past the window's edge, which counts as a bank.
     */
    static boolean islandAlong(boolean[][] water, int wx, int wz, double nx, double nz, int reach) {
        int land = -1;
        for (int t = 1; t <= reach * 2 + 2; t++) {
            int x = (int) Math.round(wx + nx * t), z = (int) Math.round(wz + nz * t);
            if (x < 0 || z < 0 || x >= WIN || z >= WIN) return false;
            if (land < 0) { if (!water[x][z]) land = t; continue; }
            if (t - land > reach) return false;
            if (water[x][z]) return true;
        }
        return false;
    }

    /** Chebyshev distance of each water cell to the nearest cell that is not water; 0 elsewhere. */
    static int[][] distance(boolean[][] water) {
        int[][] d = new int[WIN][WIN];
        int inf = WIN;
        for (int x = 0; x < WIN; x++) for (int z = 0; z < WIN; z++) d[x][z] = water[x][z] ? inf : 0;
        for (int x = 0; x < WIN; x++) {
            for (int z = 0; z < WIN; z++) {
                if (!water[x][z]) continue;
                int best = d[x][z];
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= WIN || nz >= WIN) continue;
                    best = Math.min(best, d[nx][nz] + 1);
                }
                d[x][z] = best;
            }
        }
        for (int x = WIN - 1; x >= 0; x--) {
            for (int z = WIN - 1; z >= 0; z--) {
                if (!water[x][z]) continue;
                int best = d[x][z];
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= WIN || nz >= WIN) continue;
                    best = Math.min(best, d[nx][nz] + 1);
                }
                d[x][z] = best;
            }
        }
        return d;
    }

    /** Zhang-Suen thinning: the water down to a one-cell centre line. */
    static boolean[][] thin(boolean[][] water) {
        boolean[][] s = new boolean[WIN][WIN];
        for (int x = 0; x < WIN; x++) s[x] = water[x].clone();
        boolean changed = true;
        List<int[]> gone = new ArrayList<>();
        while (changed) {
            changed = false;
            for (int pass = 0; pass < 2; pass++) {
                gone.clear();
                for (int x = 1; x < WIN - 1; x++) {
                    for (int z = 1; z < WIN - 1; z++) {
                        if (!s[x][z]) continue;
                        boolean p2 = s[x][z - 1], p3 = s[x + 1][z - 1], p4 = s[x + 1][z], p5 = s[x + 1][z + 1];
                        boolean p6 = s[x][z + 1], p7 = s[x - 1][z + 1], p8 = s[x - 1][z], p9 = s[x - 1][z - 1];
                        int b = (p2 ? 1 : 0) + (p3 ? 1 : 0) + (p4 ? 1 : 0) + (p5 ? 1 : 0)
                                + (p6 ? 1 : 0) + (p7 ? 1 : 0) + (p8 ? 1 : 0) + (p9 ? 1 : 0);
                        if (b < 2 || b > 6) continue;
                        int a = 0;
                        boolean[] ring = {p2, p3, p4, p5, p6, p7, p8, p9, p2};
                        for (int i = 0; i < 8; i++) if (!ring[i] && ring[i + 1]) a++;
                        if (a != 1) continue;
                        boolean c1 = pass == 0 ? !(p2 && p4 && p6) : !(p2 && p4 && p8);
                        boolean c2 = pass == 0 ? !(p4 && p6 && p8) : !(p2 && p6 && p8);
                        if (c1 && c2) gone.add(new int[] {x, z});
                    }
                }
                for (int[] g : gone) s[g[0]][g[1]] = false;
                if (!gone.isEmpty()) changed = true;
            }
        }
        return s;
    }

    /**
     * Walks the centre line from a cell for up to {@code steps} cells, not starting towards
     * {@code (avoidX, avoidZ)}, which is the first step of the walk the other way. Where the thinned line
     * offers two next cells, as it does round a corner, the one that carries straightest on is taken.
     *
     * @return the cell reached, how many steps it took, and the first step taken; null where the line ends at once
     */
    static int[] trace(boolean[][] skel, int x, int z, int steps, int avoidX, int avoidZ) {
        boolean[][] seen = new boolean[WIN][WIN];
        seen[x][z] = true;
        if (avoidX >= 0) seen[avoidX][avoidZ] = true;
        int px = x, pz = z, cx = x, cz = z, n = 0;
        int firstX = -1, firstZ = -1;
        for (int i = 0; i < steps; i++) {
            int nx = -1, nz = -1;
            double best = -1;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int qx = cx + dx, qz = cz + dz;
                    if (qx < 0 || qz < 0 || qx >= WIN || qz >= WIN || !skel[qx][qz] || seen[qx][qz]) continue;
                    double far = i == 0 ? 1 : dist2(qx, qz, px, pz);
                    if (far > best) { best = far; nx = qx; nz = qz; }
                }
            }
            if (nx < 0) break;
            if (i == 0) { firstX = nx; firstZ = nz; }
            // Everything round the cell just left is passed too, so a corner's second cell is not a fork.
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int qx = cx + dx, qz = cz + dz;
                if (qx >= 0 && qz >= 0 && qx < WIN && qz < WIN) seen[qx][qz] = true;
            }
            px = cx; pz = cz;
            cx = nx; cz = nz;
            n++;
        }
        return n == 0 ? null : new int[] {cx, cz, n, firstX, firstZ};
    }

    private static double dist2(int ax, int az, int bx, int bz) {
        return (ax - bx) * (ax - bx) + (az - bz) * (az - bz);
    }

    /** Marks the ground under a box as changed, so the chunks there are surveyed again when next loaded. */
    void invalidate(int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                Rec r = get(cx, cz);
                if (r == null) continue;
                r.ver++;
                r.planned = false;
                r.bends.clear();
            }
        }
        setDirty();
    }

    // === NBT ================================================================

    static RiverSurvey load(CompoundTag tag) {
        RiverSurvey s = new RiverSurvey();
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            Rec r = new Rec();
            r.ver = c.getInt("v");
            r.surveyed = c.getInt("s");
            r.coast = c.getBoolean("c");
            r.planned = c.getBoolean("p");
            if (c.contains("w")) {
                r.yW = c.getInt("w");
                r.bed = c.getInt("b");
                r.mask = c.getLongArray("m");
                r.rel = c.getByteArray("r");
                if (r.mask.length != 4 || r.rel.length != 256) { r.yW = Integer.MIN_VALUE; r.mask = null; r.rel = null; }
            }
            ListTag bends = c.getList("bends", Tag.TAG_COMPOUND);
            for (int j = 0; j < bends.size(); j++) {
                CompoundTag bt = bends.getCompound(j);
                Bend b = new Bend();
                b.x = bt.getInt("x");
                b.z = bt.getInt("z");
                b.nx = bt.getFloat("nx");
                b.nz = bt.getFloat("nz");
                b.width = bt.getInt("w");
                b.steps = bt.getInt("n");
                b.done = bt.getInt("d");
                b.next = bt.getLong("t");
                b.dead = bt.getBoolean("k");
                b.speed = bt.contains("v") ? bt.getFloat("v") : 1.0f;
                b.kind = bt.getByte("y");
                r.bends.add(b);
            }
            s.recs.put(c.getLong("key"), r);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var e : recs.long2ObjectEntrySet()) {
            Rec r = e.getValue();
            CompoundTag c = new CompoundTag();
            c.putLong("key", e.getLongKey());
            c.putInt("v", r.ver);
            c.putInt("s", r.surveyed);
            c.putBoolean("c", r.coast);
            c.putBoolean("p", r.planned);
            if (r.river()) {
                c.putInt("w", r.yW);
                c.putInt("b", r.bed);
                c.putLongArray("m", r.mask);
                c.putByteArray("r", r.rel);
            }
            if (!r.bends.isEmpty()) {
                ListTag bends = new ListTag();
                for (Bend b : r.bends) {
                    CompoundTag bt = new CompoundTag();
                    bt.putInt("x", b.x);
                    bt.putInt("z", b.z);
                    bt.putFloat("nx", b.nx);
                    bt.putFloat("nz", b.nz);
                    bt.putInt("w", b.width);
                    bt.putInt("n", b.steps);
                    bt.putInt("d", b.done);
                    bt.putLong("t", b.next);
                    bt.putBoolean("k", b.dead);
                    bt.putFloat("v", b.speed);
                    bt.putByte("y", b.kind);
                    bends.add(bt);
                }
                c.put("bends", bends);
            }
            list.add(c);
        }
        tag.put("chunks", list);
        return tag;
    }
}
