package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.util.ColumnCache;
import com.jeladastudios.ftsgeology.util.SeedHash;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Old lava tubes: the roofed-over channels a plume's shields fed their flows through, left empty when the lava
 * drained out (Kilauea, the Canaries, Iceland). They lie under the dome of a live plume and along the track of its
 * dead cones, a few blocks wide and tens to hundreds long, following the old slope a dozen blocks under the ground,
 * lined with basalt, and now and then open to the sky where the roof fell in. Laid out on a seeded grid, so every
 * chunk a tube crosses carves the same tube; written only at generation.
 *
 * <p>A tube's course is worked out once and kept. It follows the generator's ground, and asking for that is the
 * dearest thing a feature can do: walking the whole course again for every chunk it crossed made the ground over a
 * plume the slowest in the world to generate.</p>
 */
public final class LavaTubes {

    private LavaTubes() {}

    private static final int CELL = 96;
    private static final long SALT = 0x7B3EL;
    /** The longest a tube runs from its start. */
    private static final int MAX_LENGTH = 220;
    /** The share of grid cells that start a tube, where the ground allows one. */
    private static final double CELL_SHARE = 0.6;

    /** A tube's course: where each step's centre lies and whether its roof is open there, its bore, and the box it fills. */
    private record Tube(int[] xs, int[] ys, int[] zs, boolean[] sky, double rx, double ry, int shell, int shellY,
                        int minX, int minZ, int maxX, int maxZ) {
        static final Tube NONE = new Tube(new int[0], new int[0], new int[0], new boolean[0], 0, 0, 0, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /** The courses already worked out, by grid cell, for the world they were worked out in. */
    private static final ColumnCache<Tube> COURSES = new ColumnCache<>(14);
    private static volatile ServerLevel coursesFor;

    /** The start of the tube nearest a point within a few cells, or null: {x, z}. The roof is open there. */
    public static int[] nearestStart(ServerLevel model, int x, int z) {
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return null;
        long seed = model.getSeed();
        int ccx = Math.floorDiv(x, CELL), ccz = Math.floorDiv(z, CELL);
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int ox = -6; ox <= 6; ox++) {
            for (int oz = -6; oz <= 6; oz++) {
                int cx = ccx + ox, cz = ccz + oz;
                long h = SeedHash.hash(seed, cx, cz, SALT);
                if (SeedHash.rand01(h) > CELL_SHARE) continue;
                int sx = cx * CELL + (int) (SeedHash.rand01(SeedHash.mix(h)) * CELL);
                int sz = cz * CELL + (int) (SeedHash.rand01(SeedHash.mix(h ^ 0x51L)) * CELL);
                double d = Math.hypot(sx - x, sz - z);
                if (d >= bestD) continue;
                HotspotMap.Hotspot hot = HotspotMap.sample(model, sx, sz);
                if (!hot.onTrail() && hot.strength() < 0.25) continue;
                if (base(model, sx, sz) <= model.getSeaLevel() + 4) continue;
                bestD = d;
                best = new int[] {sx, sz};
            }
        }
        return best;
    }

    public static int generate(WorldGenLevel level, ChunkPos cp) {
        if (!GeyserConfig.HOTSPOTS_ENABLED.get()) return 0;
        ServerLevel model = level.getLevel();
        long seed = level.getSeed();
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        int ccx = Math.floorDiv(minX, CELL), ccz = Math.floorDiv(minZ, CELL);
        int placed = 0;
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                int cx = ccx + ox, cz = ccz + oz;
                long h = SeedHash.hash(seed, cx, cz, SALT);
                if (SeedHash.rand01(h) > CELL_SHARE) continue;
                int sx = cx * CELL + (int) (SeedHash.rand01(SeedHash.mix(h)) * CELL);
                int sz = cz * CELL + (int) (SeedHash.rand01(SeedHash.mix(h ^ 0x51L)) * CELL);
                // Too far to reach this chunk: asked before anything else, since most cells are.
                if (Math.abs(sx - (minX + 8)) > MAX_LENGTH + 20 || Math.abs(sz - (minZ + 8)) > MAX_LENGTH + 20) continue;
                Tube tube = course(model, cx, cz, sx, sz, h);
                if (tube.maxX() < minX || tube.minX() > minX + 15 || tube.maxZ() < minZ || tube.minZ() > minZ + 15) continue;
                placed += carve(level, cp, tube, h);
            }
        }
        return placed;
    }

    /** Drops the courses worked out for a world that has stopped. */
    public static void clear() {
        synchronized (COURSES) {
            COURSES.clear();
            coursesFor = null;
        }
    }

    /** The generator's ground, which every chunk agrees on. */
    private static int base(ServerLevel model, int x, int z) {
        GenCost.height();
        return model.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, model,
                model.getChunkSource().randomState());
    }

    /** The course of the tube a cell starts, worked out the first time a chunk asks; {@link Tube#NONE} where there is none. */
    private static Tube course(ServerLevel model, int cx, int cz, int sx, int sz, long h) {
        if (model != coursesFor) {
            synchronized (COURSES) {
                if (model != coursesFor) {
                    COURSES.clear();
                    coursesFor = model;
                }
            }
        }
        long key = ColumnCache.key(cx, cz);
        Tube known = COURSES.get(key);
        if (known != null) return known;
        Tube tube = plot(model, sx, sz, h);
        COURSES.put(key, tube);
        return tube;
    }

    /** Walks a tube from its start: the same dice and the same ground whichever chunk asked. */
    private static Tube plot(ServerLevel model, int sx, int sz, long h) {
        HotspotMap.Hotspot hot = HotspotMap.sample(model, sx, sz);
        if (!hot.onTrail() && hot.strength() < 0.25) return Tube.NONE;
        int start = base(model, sx, sz);
        if (start <= model.getSeaLevel() + 4) return Tube.NONE;   // a tube under the sea would be a drain
        RandomSource rng = RandomSource.create(SeedHash.mix(h ^ 0x7ABEL));
        double heading = rng.nextDouble() * Math.PI * 2;
        int length = 60 + rng.nextInt(MAX_LENGTH - 60);
        double rx = 2.0 + rng.nextDouble() * 1.5, ry = 1.6 + rng.nextDouble() * 0.9;
        int depth = 10 + rng.nextInt(8);
        int shell = (int) Math.ceil(rx) + 1, shellY = (int) Math.ceil(ry) + 1;
        int[] xs = new int[length], ys = new int[length], zs = new int[length];
        boolean[] sky = new boolean[length];
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        double x = sx + 0.5, z = sz + 0.5;
        double y = start - depth, targetY = y;
        for (int t = 0; t < length; t++) {
            if (t % 8 == 0) {
                heading += (rng.nextDouble() - 0.5) * 0.5;
                targetY = base(model, (int) Math.floor(x), (int) Math.floor(z)) - depth;
            }
            // The roof has fallen in at the start of every tube, so each one has an entrance, and here and there along it.
            sky[t] = (t % 40 == 20 && rng.nextDouble() < 0.35) || t == 1;
            y += Mth.clamp(targetY - y, -0.3, 0.3);
            x += Math.cos(heading);
            z += Math.sin(heading);
            xs[t] = (int) Math.floor(x);
            zs[t] = (int) Math.floor(z);
            ys[t] = (int) Math.round(y);
            minX = Math.min(minX, xs[t] - shell);
            maxX = Math.max(maxX, xs[t] + shell);
            minZ = Math.min(minZ, zs[t] - shell);
            maxZ = Math.max(maxZ, zs[t] + shell);
        }
        return new Tube(xs, ys, zs, sky, rx, ry, shell, shellY, minX, minZ, maxX, maxZ);
    }

    /** Writes this chunk's part of a tube. */
    private static int carve(WorldGenLevel level, ChunkPos cp, Tube tube, long h) {
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        int shell = tube.shell(), shellY = tube.shellY();
        double rx = tube.rx(), ry = tube.ry();
        int placed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int t = 0; t < tube.xs().length; t++) {
            int cx0 = tube.xs()[t], cz0 = tube.zs()[t], cy = tube.ys()[t];
            if (cx0 + shell < minX || cx0 - shell > minX + 15 || cz0 + shell < minZ || cz0 - shell > minZ + 15) continue;
            boolean sky = tube.sky()[t];

            for (int dx = -shell; dx <= shell; dx++) {
                int px = cx0 + dx;
                if (px < minX || px > minX + 15) continue;
                for (int dz = -shell; dz <= shell; dz++) {
                    int pz = cz0 + dz;
                    if (pz < minZ || pz > minZ + 15) continue;
                    int g = TerrainProbe.groundY(level, px, pz);
                    // Under a roof at least two thick, and never under standing water.
                    if (g == Integer.MIN_VALUE || cy + shellY + 1 >= g || TerrainProbe.hasFluidAbove(level, px, pz)) continue;
                    for (int dy = -shellY; dy <= shellY; dy++) {
                        int py = cy + dy;
                        double e = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) + (dz * dz) / (rx * rx);
                        if (e > 1.6) continue;
                        BlockState s = level.getBlockState(m.set(px, py, pz));
                        if (!EruptionHandler.isNaturalTerrain(s) || !s.getFluidState().isEmpty() || s.isAir()) continue;
                        double cell = SeedHash.rand01(SeedHash.hash(h, px, pz, py));
                        // The floor stays rough: a third of the floor cells keep their basalt.
                        boolean floor = dy < 0 && e > 0.55;
                        if (e <= 1.0 && !(floor && cell < 0.35)) {
                            level.setBlock(new BlockPos(px, py, pz), Blocks.AIR.defaultBlockState(), 2);
                            placed++;
                        } else {
                            level.setBlock(new BlockPos(px, py, pz),
                                    (cell < 0.3 ? Blocks.SMOOTH_BASALT : Blocks.BASALT).defaultBlockState(), 2);
                        }
                    }
                    // A skylight: the roof fell in, and the tube opens to the day.
                    if (sky && dx * dx + dz * dz <= 2) {
                        for (int py = cy; py <= g + 1; py++) {
                            BlockState s = level.getBlockState(m.set(px, py, pz));
                            if (!EruptionHandler.isNaturalTerrain(s) || !s.getFluidState().isEmpty() || s.isAir()) continue;
                            level.setBlock(new BlockPos(px, py, pz), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
        return placed;
    }
}
