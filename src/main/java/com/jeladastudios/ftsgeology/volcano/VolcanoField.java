package com.jeladastudios.ftsgeology.volcano;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.mix;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the large volcanoes stand.
 *
 * <p>Not a structure: a structure start reaches only eight chunks, and a large volcano's foot runs
 * 200 to 400 blocks out. So, like the hotspots, sites come from the world seed over a coarse grid,
 * and any chunk on any thread gets the same answer.</p>
 *
 * <p>A cell becomes a shield or caldera over a plume, a stratovolcano or now and then a caldera on a
 * subduction arc, and a fissure or a shield on a rift. It is refused on water, on ground too broken for it, and where a surface
 * structure is due, since that structure would be built inside the mountain. A shield or caldera turned down
 * for water or a structure is tried again on the ground around it, which the plume or arc covers as well.</p>
 */
public final class VolcanoField {

    private VolcanoField() {}

    /**
     * A planned large volcano.
     *
     * @param summitY      where the summit stands once built
     * @param reach        how far out it writes anything, apron included
     * @param edificeReach how far out the mountain itself goes; structures and later volcanoes keep
     *                     clear of this
     * @param roll         0..1, compared against the configured share each time it is asked, so a
     *                     change to the setting is not stuck behind the cache
     */
    public record Site(int x, int z, int baseY, int summitY, VolcanoType type, int magnitude,
                       long seed, int reach, int edificeReach, double roll, VolcanoSetting setting, double age) {
        public boolean chosen() {
            return GeyserConfig.LARGE_VOLCANOES.get() && roll < GeyserConfig.LARGE_VOLCANO_CHANCE.get()
                    && (!setting.ocean() || GeyserConfig.OCEAN_VOLCANOES.get());
        }

        /** How alive it is, from its seed and place alone; see {@link VolcanoActivity#of}. */
        public VolcanoActivity activity() {
            return VolcanoActivity.of(seed, x, z, type, setting);
        }

        /** The language key naming what this is: a stratovolcano, a shield island, an atoll. */
        public String kindKey() {
            String t = type.name().toLowerCase(java.util.Locale.ROOT);
            return switch (setting) {
                case LAND -> t;
                case ISLAND -> t + "_island";
                default -> setting.name().toLowerCase(java.util.Locale.ROOT);
            };
        }
    }

    /**
     * A search result: the nearest chosen site or null, how many chosen sites the search passed, how many
     * of those were of each type, by {@link VolcanoType} ordinal, and how many candidates were turned
     * down, at type ordinal times {@link #REASONS} plus the reason; how many were in each setting; and how alive those
     * on land and live islands were.
     */
    public record Found(Site site, int count, int[] byType, int[] refused, int[] bySetting, int[] byActivity) {}

    /** Why a candidate was turned down: water under it, broken ground, a structure due, anything else. */
    public static final int WATER = 0, RELIEF = 1, STRUCTURE = 2, OTHER = 3, REASONS = 4;

    /** A worked-out cell: its volcano, if any, and the candidates turned down on the way. */
    private record Cell(Site site, int[] refused) {}

    /** One structure set's possible start chunk, whose answer is kept while a cell is worked out. */
    private record StructureKey(int set, long chunk) {}

    /** Edge of a grid cell. Each cell holds at most one volcano. */
    static final int CELL = 2560;
    /**
     * How far a centre keeps from its cell's edge. Twice this is more than the widest footprint, so two
     * large volcanoes can never overlap and a chunk only has to ask the cells around its own.
     */
    private static final int MARGIN = 620;
    /** Points tried per cell on a plate boundary, which is a narrow thing to land on. */
    private static final int TRIES = 12;
    /** How active the boundary has to be. The arc and the rift proper, not their faint outer edge. */
    private static final double MIN_STRESS = 0.4;
    /** How far a plume's centre may be pulled to fit its cell: the dome is still strong there. */
    private static final int PLUME_PULL = 420;
    /** Ground tried around a refused shield or caldera: four bearings at half its foot, at the foot, and half as far again. */
    private static final int SHIFTS = 12;

    private static final Map<Long, CompletableFuture<Cell>> CACHE = new ConcurrentHashMap<>();

    /** The chosen large volcanoes whose footprint reaches into this chunk. */
    public static List<Site> sitesTouching(ServerLevel level, ChunkPos cp) {
        List<Site> out = new ArrayList<>(1);
        int cx0 = Math.floorDiv(cp.getMiddleBlockX(), CELL), cz0 = Math.floorDiv(cp.getMiddleBlockZ(), CELL);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen()) continue;
                // The chunk's nearest point to the centre, so a chunk the rim only clips still counts.
                long dx = Mth.clamp(s.x(), cp.getMinBlockX(), cp.getMaxBlockX()) - s.x();
                long dz = Mth.clamp(s.z(), cp.getMinBlockZ(), cp.getMaxBlockZ()) - s.z();
                if (dx * dx + dz * dz <= (long) (s.reach() + 1) * (s.reach() + 1)) out.add(s);
            }
        }
        return out;
    }

    /** The chosen large volcano nearest this column among the cells round it, or null. */
    public static Site nearestLarge(ServerLevel level, int x, int z) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        Site best = null;
        double bestD = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen()) continue;
                double d = Math.hypot(x - s.x(), z - s.z());
                if (d < bestD) { bestD = d; best = s; }
            }
        }
        return best;
    }

    /** Radius of a foot spring cluster: the springs of one group lie within this of its centre. */
    public static final int FOOT_CLUSTER_R = 30;

    /**
     * How far this column is from the nearest spring cluster at the foot of a chosen large land volcano, in blocks;
     * huge where there is none. A big mountain's springs come up in a few groups on the fan below its slopes
     * (Beppu, Hakone, Kusatsu): three centres a third of a circle apart, set from the site's seed on the apron,
     * between a bit over half its reach and nine tenths of it. The chunk pass fills each group as the ground loads.
     */
    public static double footCluster(ServerLevel level, int x, int z) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        double best = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen() || s.setting() != VolcanoSetting.LAND || s.type() == VolcanoType.CALDERA) continue;
                double a0 = rand01(mix(s.seed() ^ 0x5F00D5L)) * Math.PI * 2;
                for (int k = 0; k < 3; k++) {
                    long h = mix(s.seed() ^ (0xC1A5L + k * 0x9E37L));
                    double a = a0 + k * Math.PI * 2 / 3 + (rand01(h) - 0.5) * Math.PI / 3;
                    // On the apron itself, the fan below the slopes, not out in the country past it.
                    double r = s.reach() * 0.55 + rand01(mix(h)) * (s.reach() * 0.35);
                    best = Math.min(best, Math.hypot(x - (s.x() + Math.cos(a) * r), z - (s.z() + Math.sin(a) * r)));
                }
            }
        }
        return best;
    }

    /** True when a chosen large volcano's mountain stands within {@code margin} blocks of here. */
    public static boolean nearLarge(ServerLevel level, int x, int z, int margin) {
        return largeMargin(level, x, z) <= margin;
    }

    /**
     * How far outside the nearest chosen large volcano's mountain this column lies, in blocks: negative inside it,
     * huge where there is none. Cheap enough to ask for every column, so ground painted round a volcano can fade
     * out along its circle instead of stopping on a chunk edge.
     */
    public static double largeMargin(ServerLevel level, int x, int z) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        double best = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen()) continue;
                best = Math.min(best, Math.hypot(x - s.x(), z - s.z()) - s.reach());
            }
        }
        return best;
    }

    /**
     * How far outside the nearest chosen large volcano's body this column lies, apron not counted: negative on
     * the mountain itself, huge where there is none. Springs are kept off the body and let onto the apron.
     */
    public static double bodyMargin(ServerLevel level, int x, int z) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        double best = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen()) continue;
                best = Math.min(best, Math.hypot(x - s.x(), z - s.z()) - s.edificeReach());
            }
        }
        return best;
    }

    /**
     * How far up the nearest chosen large volcano's body this column lies, as a share of the body's reach: 0 at the
     * centre, 1 at its foot, more outside it, huge where there is none.
     */
    public static double bodyShare(ServerLevel level, int x, int z) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        double best = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen() || s.edificeReach() <= 0) continue;
                best = Math.min(best, Math.hypot(x - s.x(), z - s.z()) / s.edificeReach());
            }
        }
        return best;
    }

    /** The chosen large volcanoes whose mountain reaches into this box of blocks. */
    public static List<Site> sitesInBox(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        List<Site> out = new ArrayList<>(1);
        for (int cx = Math.floorDiv(minX, CELL) - 1; cx <= Math.floorDiv(maxX, CELL) + 1; cx++) {
            for (int cz = Math.floorDiv(minZ, CELL) - 1; cz <= Math.floorDiv(maxZ, CELL) + 1; cz++) {
                Site s = site(level, cx, cz);
                if (s == null || !s.chosen()) continue;
                int r = s.edificeReach();
                if (s.x() + r < minX || s.x() - r > maxX || s.z() + r < minZ || s.z() - r > maxZ) continue;
                out.add(s);
            }
        }
        return out;
    }

    /** True on the floor of a chosen large caldera, where hot ground and springs belong. */
    public static boolean onCalderaFloor(ServerLevel level, int x, int z) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen() || s.type() != VolcanoType.CALDERA) continue;
                // Well inside the ring fault, clear of the inner wall at any bearing.
                if (Math.hypot(x - s.x(), z - s.z()) < s.edificeReach() * 0.3) return true;
            }
        }
        return false;
    }

    /**
     * The site centred exactly on this column, or null. Chosen or not: a volcano whose body was
     * generated before the setting changed still deserves its summit.
     */
    public static Site siteAt(ServerLevel level, int x, int z) {
        Site s = site(level, Math.floorDiv(x, CELL), Math.floorDiv(z, CELL));
        return s != null && s.x() == x && s.z() == z ? s : null;
    }

    /**
     * The chosen site nearest to a point within {@code rings} cells, of one type or of any when
     * {@code only} is null. Safe off the server thread.
     */
    public static Found nearest(ServerLevel level, int x, int z, int rings, VolcanoType only) {
        return nearest(level, x, z, rings, only, null);
    }

    /** {@link #nearest}, also limited to one setting when {@code onlySetting} is not null. */
    public static Found nearest(ServerLevel level, int x, int z, int rings, VolcanoType only,
                                VolcanoSetting onlySetting) {
        return nearest(level, x, z, rings, only, onlySetting, null);
    }

    /**
     * {@link #nearest}, also limited to one activity when {@code onlyActivity} is not null. An activity is asked of
     * volcanoes on land and live islands, where it shows; an old island is extinct by what it is.
     */
    public static Found nearest(ServerLevel level, int x, int z, int rings, VolcanoType only,
                                VolcanoSetting onlySetting, VolcanoActivity onlyActivity) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        Site best = null;
        double bestD = Double.MAX_VALUE;
        int count = 0;
        int[] byType = new int[VolcanoType.values().length];
        int[] bySetting = new int[VolcanoSetting.values().length];
        int[] byActivity = new int[VolcanoActivity.values().length];
        int[] refused = new int[VolcanoType.values().length * REASONS];
        int side = 2 * rings + 1;
        Cell[] cells = new Cell[side * side];
        // A cell is slow to work out the first time and needs no other, so off the server thread a search spreads
        // them over a few workers. The answer is put together in the same order either way.
        int workers = level.getServer().isSameThread() ? 1
                : Mth.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 8);
        CompletableFuture<?>[] parts = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            int first = w;
            Runnable part = () -> {
                for (int i = first; i < cells.length; i += workers) {
                    cells[i] = cell(level, cx0 - rings + i / side, cz0 - rings + i % side);
                }
            };
            if (workers == 1) {
                part.run();
                parts[w] = CompletableFuture.completedFuture(null);
            } else {
                parts[w] = CompletableFuture.runAsync(part, net.minecraft.Util.backgroundExecutor());
            }
        }
        CompletableFuture.allOf(parts).join();
        for (int ox = -rings; ox <= rings; ox++) {
            for (int oz = -rings; oz <= rings; oz++) {
                Cell cell = cells[(ox + rings) * side + (oz + rings)];
                for (int i = 0; i < refused.length; i++) refused[i] += cell.refused()[i];
                Site s = cell.site();
                if (s == null || !s.chosen()) continue;
                count++;
                byType[s.type().ordinal()]++;
                bySetting[s.setting().ordinal()]++;
                if (s.setting() == VolcanoSetting.LAND || s.setting() == VolcanoSetting.ISLAND) byActivity[s.activity().ordinal()]++;
                if (only != null && s.type() != only) continue;
                if (onlySetting != null && s.setting() != onlySetting) continue;
                if (onlyActivity != null && (s.activity() != onlyActivity
                        || (s.setting() != VolcanoSetting.LAND && s.setting() != VolcanoSetting.ISLAND))) continue;
                double d = Math.hypot(x - s.x(), z - s.z());
                if (d < bestD) { bestD = d; best = s; }
            }
        }
        return new Found(best, count, byType, refused, bySetting, byActivity);
    }

    public static void clearCache() {
        CACHE.clear();
        COAST_LAND.clear();
    }

    /** Per island, {@link OceanEdifice.Isle#coastLand}: the generator is asked once, not once a chunk. */
    private static final Map<Long, Long> COAST_LAND = new java.util.concurrent.ConcurrentHashMap<>();

    /** Where the generator's own land meets an island's coast, a bit a bearing; see {@link OceanEdifice#landAtCoast}. */
    public static long coastLand(ServerLevel level, VolcanoPlan.Ctx c) {
        if (c.isle == null || c.isle.setting == VolcanoSetting.GUYOT) return 0L;
        long key = ((long) c.x << 32) ^ (c.z & 0xFFFFFFFFL);
        Long hit = COAST_LAND.get(key);
        if (hit != null) return hit;
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();
        long bits = 0L;
        for (int i = 0; i < OceanEdifice.COAST_BEARINGS; i++) {
            double a = Math.PI * 2 * i / OceanEdifice.COAST_BEARINGS;
            double r = OceanEdifice.coastAt(c, a) + 4;
            int px = c.x + (int) Math.round(Math.cos(a) * r), pz = c.z + (int) Math.round(Math.sin(a) * r);
            int surface = gen.getBaseHeight(px, pz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
            int floor = gen.getBaseHeight(px, pz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            if (surface <= floor || floor >= sea) bits |= 1L << i;
        }
        COAST_LAND.put(key, bits);
        return bits;
    }

    private static Site site(ServerLevel level, int cx, int cz) {
        return cell(level, cx, cz).site();
    }

    private static Cell cell(ServerLevel level, int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        CompletableFuture<Cell> hit = CACHE.get(key);
        if (hit == null) {
            if (CACHE.size() > 50_000) CACHE.clear();
            CompletableFuture<Cell> mine = new CompletableFuture<>();
            hit = CACHE.putIfAbsent(key, mine);
            if (hit == null) {
                // This thread works the cell out. A cell that tries the ground round a refused site is slow,
                // so any other thread asking meanwhile waits for this answer instead of repeating the work.
                long started = System.nanoTime();
                try {
                    mine.complete(evaluate(level, cx, cz));
                } catch (RuntimeException | Error e) {
                    CACHE.remove(key, mine);
                    mine.completeExceptionally(e);
                    throw e;
                }
                long took = System.nanoTime() - started;
                com.jeladastudios.ftsgeology.worldgen.GenCost.cell(took);
                com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Large volcano cell {},{} worked out in {} ms",
                        cx, cz, took / 1_000_000);
                hit = mine;
            }
        }
        // The server thread never waits on another thread: it works a cell still in progress out itself.
        // Waiting would stall the game, and hang it for good if that thread ever needed the server.
        if (!hit.isDone() && level.getServer().isSameThread()) return evaluate(level, cx, cz);
        return hit.join();
    }

    private static Cell evaluate(ServerLevel level, int cx, int cz) {
        long seed = hash(level.getSeed(), cx, cz, 0x5EED1L);
        int[] refused = new int[VolcanoType.values().length * REASONS];
        int span = CELL - 2 * MARGIN;
        int minX = cx * CELL + MARGIN, minZ = cz * CELL + MARGIN;
        int maxX = minX + span, maxZ = minZ + span;
        // Nearby candidates ask after the same structure starts over and over; each is worked out once.
        Map<StructureKey, Boolean> structures = new HashMap<>();

        boolean ocean = GeyserConfig.OCEAN_VOLCANOES.get();
        // The land caldera is out: its 300-block columns stalled the generator's workers under a terrain mod. The
        // code stays; the sites that would have been calderas are shields over plumes and stratovolcanoes on arcs.
        boolean calderas = false;
        // A plume first: fewer of them, and the grander sight. A centre just outside the usable part
        // of the cell is pulled in.
        for (int[] p : HotspotMap.plumeCentres(level, minX - PLUME_PULL, minZ - PLUME_PULL,
                maxX + PLUME_PULL, maxZ + PLUME_PULL)) {
            int x = Mth.clamp(p[0], minX, maxX), z = Mth.clamp(p[1], minZ, maxZ);
            if (HotspotMap.plumeStrength(level, x, z) < 0.4) continue;
            // Under the open sea a plume builds an island up from the sea floor, as at Hawaii.
            if (ocean && oceanDepth(level, x, z) >= ISLAND_DEPTH) {
                Site s = checkOcean(level, x, z, VolcanoType.SHIELD, VolcanoSetting.ISLAND, 0.0, seed, refused,
                        structures);
                // An island needs open sea round it as well; the plume's dome is wide, so the sea round it is tried.
                int[] uncounted = new int[refused.length];
                for (int i = 0; s == null && i < 8; i++) {
                    double a = rand01(hash(seed, x, z, 0x15A1L)) * Math.PI * 2 + Math.PI * 0.25 * i;
                    double r = 180.0 * (1 + i % 2);
                    int sx = x + (int) Math.round(Math.cos(a) * r), sz = z + (int) Math.round(Math.sin(a) * r);
                    if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) continue;
                    if (HotspotMap.plumeStrength(level, sx, sz) < 0.4 || oceanDepth(level, sx, sz) < ISLAND_DEPTH) continue;
                    s = checkOcean(level, sx, sz, VolcanoType.SHIELD, VolcanoSetting.ISLAND, 0.0, seed, uncounted,
                            structures);
                }
                if (s != null) return new Cell(s, refused);
            }
            // A really large hotspot volcano has often emptied its chamber and fallen in.
            VolcanoType type = calderas && rand01(hash(seed, 0, 0, 0xCA1DL)) < 0.34
                    ? VolcanoType.CALDERA : VolcanoType.SHIELD;
            Site s = checkNear(level, x, z, type, seed, refused, null, minX, minZ, maxX, maxZ, structures);
            if (s != null) return new Cell(s, refused);
        }

        // Then the islands a plume left behind as the plate carried them off it.
        if (ocean) {
            Site s = trailSite(level, seed, refused, minX, minZ, maxX, maxZ, structures);
            if (s != null) return new Cell(s, refused);
        }

        for (int i = 0; i < TRIES; i++) {
            int x = minX + (int) (rand01(hash(seed, i, 1, 0x71A1L)) * span);
            int z = minZ + (int) (rand01(hash(seed, i, 2, 0x71A2L)) * span);
            PlateSample s = TectonicMap.sampleCached(level, x, z);
            if (s.stress() < MIN_STRESS) continue;
            // An arc stands on the plate that rides over, back from the trench; the plate going under has none.
            if (s.faultType() == FaultType.CONVERGENT_SUBDUCTION && !s.onArc(GeyserConfig.FAULT_WIDTH.get())) continue;
            VolcanoType type = switch (s.faultType()) {
                // A quarter of the arc volcanoes have blown their tops off, as at Crater Lake or Aso.
                case CONVERGENT_SUBDUCTION -> calderas && rand01(hash(seed, i, 4, 0xCA2DL)) < 0.25
                        ? VolcanoType.CALDERA : VolcanoType.STRATOVOLCANO;
                // A third of the rift volcanoes have built a broad basalt shield, as many in Iceland have.
                case DIVERGENT -> rand01(hash(seed, i, 5, 0x5E1DL)) < 0.35
                        ? VolcanoType.SHIELD : VolcanoType.FISSURE;
                default -> null;
            };
            if (type == null) continue;
            // Rifts run long and largely over land, so left alone they out-numbered the arcs four to
            // one in testing. A flood-basalt fissure this size is the rarer sight in the real world.
            if (type == VolcanoType.FISSURE && rand01(hash(seed, i, 3, 0xF155L)) < 0.6) continue;
            // An arc out in the sea is a string of volcanic islands, as the Aleutians are; the cones that died long
            // ago are worn down to old islands, valleyed and cliffed, with a reef in a warm sea.
            if (ocean && type != VolcanoType.FISSURE && type != VolcanoType.SHIELD
                    && oceanDepth(level, x, z) >= ISLAND_DEPTH) {
                boolean dead = type == VolcanoType.STRATOVOLCANO
                        && VolcanoActivity.of(seed, x, z, type, VolcanoSetting.ISLAND) == VolcanoActivity.EXTINCT;
                double ageRoll = rand01(hash(seed, x, z, 0xA6EDL));
                // Younger than a plume's track: an old arc island is worn and cliffed but still stands well out of the sea.
                double age = 0.1 + 0.3 * ageRoll;
                // The oldest, in a warm sea, have sunk under their reef: Darwin's atoll, on an arc as on a plume.
                VolcanoSetting old = ageRoll >= 0.55 && seaTemperature(level, x, z) > OceanEdifice.ATOLL_TEMPERATURE
                        ? VolcanoSetting.ATOLL : VolcanoSetting.ERODED;
                Site island = dead
                        ? checkOcean(level, x, z, type, old, old == VolcanoSetting.ATOLL ? 0.4 + age : age, seed,
                                refused, structures)
                        : checkOcean(level, x, z, type, VolcanoSetting.ISLAND, 0.0, seed, refused, structures);
                // A sea too shallow for an atoll still holds the old island itself.
                if (island == null && dead && old == VolcanoSetting.ATOLL) {
                    island = checkOcean(level, x, z, type, VolcanoSetting.ERODED, age, seed, new int[refused.length],
                            structures);
                }
                if (island != null) return new Cell(island, refused);
            }
            Site site = checkNear(level, x, z, type, seed, refused, s.faultType(), minX, minZ, maxX, maxZ,
                    structures);
            if (site != null) return new Cell(site, refused);
        }
        return new Cell(null, refused);
    }

    /**
     * {@link #check}, and for a cone or caldera turned down for water or a structure, the same check on the
     * ground around it. Those need hundreds of blocks of dry ground with no village on it, which one point
     * seldom has, while the plume or arc under it spreads well past that point. Only the first candidate is
     * counted as refused, so the counts stay a count of candidates. A fissure runs along its rift and is not moved.
     *
     * @param fault the boundary a moved site has to stay on, or null for a plume
     */
    private static Site checkNear(ServerLevel level, int x, int z, VolcanoType type, long seed, int[] refused,
                                  FaultType fault, int minX, int minZ, int maxX, int maxZ,
                                  Map<StructureKey, Boolean> structures) {
        int b = type.ordinal() * REASONS;
        int water = refused[b + WATER], structure = refused[b + STRUCTURE];
        Site site = check(level, x, z, type, seed, refused, structures);
        if (site != null || type == VolcanoType.FISSURE) return site;
        // Broken ground or no plan at all: moving over would not help.
        if (refused[b + WATER] == water && refused[b + STRUCTURE] == structure) return null;

        int foot = footAt(level, x, z, type, seed);
        if (foot <= 0) return null;
        double spin = rand01(hash(seed, x, z, 0x5F1L)) * Math.PI * 2;
        int[] uncounted = new int[refused.length];
        for (int i = 0; i < SHIFTS; i++) {
            // Ring by ring outward, each turned half way between the bearings of the one inside it.
            int ring = i / 4;
            double a = spin + Math.PI * 0.5 * (i % 4) + (ring % 2 == 1 ? Math.PI * 0.25 : 0.0);
            double r = foot * (0.5 + 0.5 * ring);
            int sx = x + (int) Math.round(Math.cos(a) * r), sz = z + (int) Math.round(Math.sin(a) * r);
            // Inside the usable part of the cell, or two volcanoes could overlap.
            if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) continue;
            if (fault == null) {
                if (HotspotMap.plumeStrength(level, sx, sz) < 0.4) continue;
            } else {
                PlateSample p = TectonicMap.sampleCached(level, sx, sz);
                if (p.stress() < MIN_STRESS || p.faultType() != fault) continue;
                if (fault == FaultType.CONVERGENT_SUBDUCTION && !p.onArc(GeyserConfig.FAULT_WIDTH.get())) continue;
            }
            // Two height samples rule most of the sea out before a full check is paid for.
            if (centreWet(level, sx, sz)) continue;
            Site moved = check(level, sx, sz, type, seed, uncounted, structures);
            if (moved != null) return moved;
        }
        return null;
    }

    /** Water over the sea floor a live island needs at its centre, and a sunken one. */
    private static final int ISLAND_DEPTH = 8, SUNKEN_DEPTH = 12;
    /** How far along a plume's track its old islands begin; nearer the plume the live island stands. */
    private static final double TRAIL_START = 1000.0;

    /**
     * Where a plume's track through the sea crosses this cell: by how far along it is, an old island, then an atoll
     * in warm water or a guyot in cold.
     */
    private static Site trailSite(ServerLevel level, long seed, int[] refused, int minX, int minZ, int maxX, int maxZ,
                                  Map<StructureKey, Boolean> structures) {
        double length = GeyserConfig.OCEAN_TRAIL_LENGTH.get();
        for (HotspotMap.Trail t : HotspotMap.oceanTrails(level, minX, minZ, maxX, maxZ, length)) {
            double[] span = clip(t, minX, minZ, maxX, maxZ, length);
            if (span == null) continue;
            // A few points along the stretch in the cell, from a seeded start: the track crosses islands and shoals,
            // and the first open sea along it takes the old volcano. Only the sea is asked about: the world's plates are
            // far smaller than the Pacific, and their crust type is read from a handful of biome probes. The plume
            // itself may be under land: a track that runs out to sea from the coast still leaves islands, as the
            // Cameroon line does.
            double start = rand01(hash(seed, (int) t.x(), (int) t.z(), 0x7A11L));
            int[] uncounted = new int[refused.length];
            for (int i = 0; i < 8; i++) {
                double along = span[0] + (span[1] - span[0]) * ((start + i * 0.125) % 1.0);
                if (along < TRAIL_START) continue;
                int x = (int) Math.round(t.x() + t.dirX() * along), z = (int) Math.round(t.z() + t.dirZ() * along);
                double age = along / length;
                VolcanoSetting setting = age < 0.5 ? VolcanoSetting.ERODED
                        : seaTemperature(level, x, z) > OceanEdifice.ATOLL_TEMPERATURE ? VolcanoSetting.ATOLL
                        : VolcanoSetting.GUYOT;
                int depth = oceanDepth(level, x, z);
                if (depth < (setting == VolcanoSetting.ERODED ? ISLAND_DEPTH : SUNKEN_DEPTH)) {
                    com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Plume track at {},{} ({}, age {}) has {} of sea",
                            x, z, setting, String.format(java.util.Locale.ROOT, "%.2f", age), depth);
                    continue;
                }
                // Only the first try is counted, so the counts stay a count of candidates.
                Site s = checkOcean(level, x, z, VolcanoType.SHIELD, setting, age, seed, i == 0 ? refused : uncounted,
                        structures);
                if (s != null) return s;
            }
        }
        return null;
    }

    /** The stretch of a track inside a box, as distances along it from the plume; null where it misses the box. */
    static double[] clip(HotspotMap.Trail t, int minX, int minZ, int maxX, int maxZ, double length) {
        double lo = 0.0, hi = length;
        double[][] axes = {{t.x(), t.dirX(), minX, maxX}, {t.z(), t.dirZ(), minZ, maxZ}};
        for (double[] a : axes) {
            if (Math.abs(a[1]) < 1.0e-9) {
                if (a[0] < a[2] || a[0] > a[3]) return null;
                continue;
            }
            double t1 = (a[2] - a[0]) / a[1], t2 = (a[3] - a[0]) / a[1];
            lo = Math.max(lo, Math.min(t1, t2));
            hi = Math.min(hi, Math.max(t1, t2));
        }
        return lo <= hi ? new double[] {lo, hi} : null;
    }

    /** Water over the generator's sea floor at a column, or -1 where the column is dry. */
    public static int oceanDepth(ServerLevel level, int x, int z) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int surface = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
        return surface > floor ? gen.getSeaLevel() - floor : -1;
    }

    /**
     * The climate temperature at a column at sea level: the parameter that picks vanilla's frozen, cold, temperate,
     * lukewarm and warm seas, and the same under any biome mod that keeps the climate noise.
     */
    public static double seaTemperature(ServerLevel level, int x, int z) {
        net.minecraft.world.level.biome.Climate.TargetPoint p = level.getChunkSource().randomState().sampler()
                .sample(QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(z));
        return net.minecraft.world.level.biome.Climate.unquantizeCoord(p.temperature());
    }

    /**
     * Whether a volcano can rise from the sea floor here, from the generator's own terrain: open sea over the centre,
     * little land under the body, a floor without a cliff in it, and no ocean monument in the way.
     */
    private static Site checkOcean(ServerLevel level, int x, int z, VolcanoType type, VolcanoSetting setting, double age,
                                   long seed, int[] refused, Map<StructureKey, Boolean> structures) {
        int magnitude = magnitudeAt(seed, x, z);
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();
        boolean sunken = setting == VolcanoSetting.ATOLL || setting == VolcanoSetting.GUYOT;
        int depth = sunken ? SUNKEN_DEPTH : ISLAND_DEPTH;
        double warmth = seaTemperature(level, x, z);
        // Planned once on a provisional floor, only to learn how wide a ring to sample.
        int[] probe = VolcanoBuilder.largeFootprint(level, x, sea - 30, z, magnitude, type, seed, setting, age, warmth);
        if (probe == null) return refuse(refused, type, OTHER, x, z, "no island plan");
        int foot = probe[4];

        int[] floors = new int[17];
        int dryMid = 0, dryFoot = 0, n = 0;
        for (int ring = 0; ring <= 2; ring++) {
            int count = ring == 0 ? 1 : 8;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2 * i / 8 + ring * 0.39;
                double r = foot * ring / 2.0;
                int px = x + (int) Math.round(Math.cos(a) * r);
                int pz = z + (int) Math.round(Math.sin(a) * r);
                int surface = gen.getBaseHeight(px, pz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                int floor = gen.getBaseHeight(px, pz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
                boolean wet = surface > floor && floor < sea;
                if (ring == 0 && (!wet || sea - floor < depth)) {
                    return refuse(refused, type, WATER, x, z, "centre not over open sea");
                }
                if (!wet) {
                    if (ring == 1) dryMid++;
                    else dryFoot++;
                }
                floors[n++] = floor - 1;
            }
        }
        // An island may reach a shore at its foot. One whose body stands on land is a volcano on land.
        if (dryMid > (sunken ? 1 : 2) || dryFoot > (sunken ? 3 : 5)) {
            return refuse(refused, type, WATER, x, z, "land under the island, " + dryMid + " mid " + dryFoot + " foot");
        }
        int[] sorted = floors.clone();
        Arrays.sort(sorted);
        if (sorted[12] - sorted[4] > 40) {
            return refuse(refused, type, RELIEF, x, z, "sea floor relief " + (sorted[12] - sorted[4]));
        }
        // It stands on the deeper part of the floor under it.
        int baseY = sorted[4];
        if (sea - 1 - baseY < depth) return refuse(refused, type, WATER, x, z, "sea too shallow");
        VolcanoPlan.Ctx plan = VolcanoBuilder.largePlan(level, x, baseY, z, magnitude, type, seed, setting, age, warmth);
        if (plan == null || plan.isle == null) return refuse(refused, type, OTHER, x, z, "no island plan on floor " + baseY);
        if (plan.summitY <= baseY + 4) return refuse(refused, type, WATER, x, z, "sea too shallow for its top");
        // Open sea just past the coast: land all round would join the island to the shore. A live island may lie off
        // a coast on one side, as arc islands do; an old island or an atoll stands in the open ocean.
        int landOff = landOffCoast(level, gen, rs, plan, x, z, sea);
        if (landOff > (setting == VolcanoSetting.ISLAND ? 3 : 1)) {
            return refuse(refused, type, WATER, x, z, "land off the coast, " + landOff + " of 16");
        }
        // Only a monument is in the way: a wreck or a ruin is built first and ends up inside the island.
        if (structureInTheWay(level, gen, rs, x, z, plan.isle.edifice + 16, structures, true)) {
            return refuse(refused, type, STRUCTURE, x, z, "monument within " + (plan.isle.edifice + 16));
        }
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Ocean {} {} at {},{}: floor {}, top {}, reach {}, age {}, sea {}",
                setting, type, x, z, baseY, plan.summitY, plan.clearReach, String.format(java.util.Locale.ROOT, "%.2f", age),
                String.format(java.util.Locale.ROOT, "%.2f", warmth));
        return new Site(x, z, baseY, plan.summitY, type, magnitude, seed, plan.clearReach, plan.isle.edifice,
                rand01(hash(seed, x, z, 0xC40L)), setting, age);
    }

    /** How far past an island's coast the sea has to stay open, in blocks. */
    private static final int COAST_CLEARANCE = 48;

    /** How many of sixteen points just off an island's coast the generator makes land. A guyot has no coast. */
    private static int landOffCoast(ServerLevel level, ChunkGenerator gen, RandomState rs, VolcanoPlan.Ctx plan,
                                    int x, int z, int sea) {
        if (plan.isle.setting == VolcanoSetting.GUYOT) return 0;
        int dry = 0;
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16 + 0.2;
            double r = OceanEdifice.coastAt(plan, a) + COAST_CLEARANCE;
            int px = x + (int) Math.round(Math.cos(a) * r), pz = z + (int) Math.round(Math.sin(a) * r);
            int surface = gen.getBaseHeight(px, pz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
            int floor = gen.getBaseHeight(px, pz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            if (surface <= floor || floor >= sea) dry++;
        }
        return dry;
    }

    /** The magnitude of a large volcano planned at this point. */
    private static int magnitudeAt(long seed, int x, int z) {
        return VolcanoSize.LARGE.magnitude(rand01(hash(seed, x, z, 0x3A6L)));
    }

    /** How far out a large volcano of this type planned here reaches, or 0 where it cannot be planned. */
    private static int footAt(ServerLevel level, int x, int z, VolcanoType type, long seed) {
        int sea = level.getChunkSource().getGenerator().getSeaLevel();
        // Planned once on a provisional base, only to learn how wide a ring to sample.
        int[] probe = VolcanoBuilder.largeFootprint(level, x, sea + 8, z, magnitudeAt(seed, x, z), type, seed);
        return probe == null ? 0 : probe[1];
    }

    /** True where the generator puts water, or ground at the sea, on this column. */
    private static boolean centreWet(ServerLevel level, int x, int z) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int surface = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
        return surface - floor >= DEEP_WATER;
    }

    /** Water this deep under a probe is a lake or the sea; anything shallower is a river the mountain buries. */
    private static final int DEEP_WATER = 4;

    /**
     * Whether a large volcano of this type can stand here, from the generator's own terrain rather than
     * the world, which for most of these cells does not exist yet.
     */
    private static Site check(ServerLevel level, int x, int z, VolcanoType type, long seed, int[] refused,
                              Map<StructureKey, Boolean> structures) {
        int magnitude = magnitudeAt(seed, x, z);
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();

        int foot = footAt(level, x, z, type, seed);
        if (foot <= 0) return refuse(refused, type, OTHER, x, z, "no plan");

        // The centre, then eight points half way out and eight at the foot.
        int[] ground = new int[17];
        int[] wet = new int[3];
        int n = 0;
        int wetMid = type == VolcanoType.SHIELD ? 4 : 2;
        for (int ring = 0; ring <= 2; ring++) {
            int count = ring == 0 ? 1 : 8;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2 * i / 8 + ring * 0.39;
                double r = foot * ring / 2.0;
                int px = x + (int) Math.round(Math.cos(a) * r);
                int pz = z + (int) Math.round(Math.sin(a) * r);
                int surface = gen.getBaseHeight(px, pz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                int floor = gen.getBaseHeight(px, pz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
                // Only deep water counts: a river or a shallow lake under the body is built over, as real
                // volcanoes stand by rivers. In a world full of rivers, counting every one left no site at all.
                if (surface - floor >= DEEP_WATER) {
                    if (ring == 0) return refuse(refused, type, WATER, x, z, "centre in water");
                    wet[ring]++;
                }
                ground[n++] = floor - 1;
            }
            // Water under the body of the mountain refuses it; a lake or a shore out at the foot does not,
            // since the apron carries on under water as a thin skin. A volcano half in the sea is a job for
            // the ocean volcanoes, not this. A body already too wet is refused before its foot is sampled.
            if (ring == 1 && wet[1] > wetMid) return refuse(refused, type, WATER, x, z, "wet " + wet[1] + " mid");
        }
        if (wet[2] > 6) return refuse(refused, type, WATER, x, z, "wet " + wet[2] + " foot");

        // Without the highest and lowest of the nine inner points, so one peak or gorge under the body
        // does not turn a whole mountain down. A caldera cuts a floor and needs ground that allows it; a
        // shield is low and spreads over broken country; a cone grows out of whatever is there.
        int[] inner = Arrays.copyOf(ground, 9);
        Arrays.sort(inner);
        int relief = inner[7] - inner[1];
        int allowed = switch (type) {
            case SHIELD -> 200;
            case CALDERA -> 96;
            default -> 140;
        };
        if (relief > allowed) return refuse(refused, type, RELIEF, x, z, "relief " + relief);
        int[] sorted = ground.clone();
        Arrays.sort(sorted);
        int baseY = sorted[8];

        int[] plan = VolcanoBuilder.largeFootprint(level, x, baseY, z, magnitude, type, seed);
        if (plan == null) return refuse(refused, type, OTHER, x, z, "no plan at base " + baseY);
        // A cone's summit has to stand clear of the land round it, or its lake is cut into a hill and runs out on
        // the low side. Where the ground near the centre rises past the planned summit, the mountain is raised.
        if (type == VolcanoType.SHIELD || type == VolcanoType.STRATOVOLCANO) {
            double around = Math.max(8.0, plan[3] * 1.6);
            int top = Integer.MIN_VALUE;
            for (int i = 0; i <= 8; i++) {
                int px = x + (i == 0 ? 0 : (int) Math.round(Math.cos(Math.PI * i / 4) * around));
                int pz = z + (i == 0 ? 0 : (int) Math.round(Math.sin(Math.PI * i / 4) * around));
                top = Math.max(top, gen.getBaseHeight(px, pz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1);
            }
            int lift = top + 2 - plan[2];
            if (lift > 40) return refuse(refused, type, RELIEF, x, z, "hills " + lift + " over the summit");
            if (lift > 0) {
                baseY += lift;
                plan = VolcanoBuilder.largeFootprint(level, x, baseY, z, magnitude, type, seed);
                if (plan == null) return refuse(refused, type, OTHER, x, z, "no plan at raised base " + baseY);
            }
        }
        // Structures are placed before the mountain. One under the summit would be cut by the crater, so the
        // crater zone is kept clear; one on the flank stays where it is, its blocks never written over, and
        // the mountain rises round it, as Pompeii lies under Vesuvius. Asking for the whole body left no
        // site in a world with structure mods.
        int keepClear = Math.max(plan[3] + 16, (int) Math.round(plan[1] * 0.35));
        if (structureInTheWay(level, gen, rs, x, z, keepClear, structures, false)) {
            return refuse(refused, type, STRUCTURE, x, z, "structure within " + keepClear);
        }
        return new Site(x, z, baseY, plan[2], type, magnitude, seed, plan[0], plan[1],
                rand01(hash(seed, x, z, 0xC40L)), VolcanoSetting.LAND, 0.0);
    }

    /** Counts and logs why a candidate site was turned down, at debug level, and refuses it. */
    private static Site refuse(int[] refused, VolcanoType type, int reason, int x, int z, String why) {
        refused[type.ordinal() * REASONS + reason]++;
        com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Large {} site at {},{} refused: {}", type, x, z, why);
        return null;
    }

    /**
     * True when a surface structure is due within {@code radius} blocks, asked of the structure
     * placement itself so it works for ungenerated chunks. Errs towards refusing; ruined portals are
     * ignored.
     *
     * @param due          answers already worked out for this cell, by structure set and start chunk
     * @param monumentOnly only an ocean monument counts, for an island rising round what the sea floor holds
     */
    private static boolean structureInTheWay(ServerLevel level, ChunkGenerator gen, RandomState rs,
                                             int x, int z, int radius, Map<StructureKey, Boolean> due,
                                             boolean monumentOnly) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        long seed = state.getLevelSeed();
        int minCX = (x - radius) >> 4, maxCX = (x + radius) >> 4;
        int minCZ = (z - radius) >> 4, maxCZ = (z + radius) >> 4;
        long r2 = (long) radius * radius;
        int index = -1;
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            index++;
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement spread)) continue;
            List<Structure> surface = new ArrayList<>();
            for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                Structure s = e.structure().value();
                boolean counts = monumentOnly ? s.type() == StructureType.OCEAN_MONUMENT
                        : s.step() == GenerationStep.Decoration.SURFACE_STRUCTURES
                                && s.type() != StructureType.RUINED_PORTAL;
                if (counts) surface.add(s);
            }
            if (surface.isEmpty()) continue;
            int spacing = spread.spacing();
            for (int rx = Math.floorDiv(minCX, spacing); rx <= Math.floorDiv(maxCX, spacing); rx++) {
                for (int rz = Math.floorDiv(minCZ, spacing); rz <= Math.floorDiv(maxCZ, spacing); rz++) {
                    ChunkPos cand = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    int bx = cand.getMiddleBlockX(), bz = cand.getMiddleBlockZ();
                    long dx = bx - x, dz = bz - z;
                    if (dx * dx + dz * dz > r2) continue;
                    StructureKey key = new StructureKey(index, cand.toLong());
                    Boolean hit = due.get(key);
                    if (hit == null) {
                        hit = structureDue(level, gen, rs, state, spread, cand, surface);
                        due.put(key, hit);
                    }
                    if (hit) return true;
                }
            }
        }
        return false;
    }

    /** Whether one of these surface structures really starts in this candidate chunk. */
    private static boolean structureDue(ServerLevel level, ChunkGenerator gen, RandomState rs,
                                        ChunkGeneratorStructureState state, RandomSpreadStructurePlacement spread,
                                        ChunkPos cand, List<Structure> surface) {
        if (!spread.isStructureChunk(state, cand.x, cand.z)) return false;
        int bx = cand.getMiddleBlockX(), bz = cand.getMiddleBlockZ();
        int y = gen.getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(bx),
                QuartPos.fromBlock(y), QuartPos.fromBlock(bz), rs.sampler());
        for (Structure s : surface) {
            if (s.biomes().contains(biome)) return true;
        }
        return false;
    }

}
