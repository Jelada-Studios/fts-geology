package com.jeladastudios.ftsgeology.volcano;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.mix;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
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
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.apache.commons.lang3.mutable.MutableObject;
import java.util.function.Predicate;
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
    record StructureKey(int set, long chunk) {}

    /** Edge of a grid cell. Each cell holds at most one volcano. */
    static final int CELL_BASE = 2560;
    /**
     * How far a centre keeps from its cell's edge. Twice this is more than the widest footprint, so two
     * large volcanoes can never overlap and a chunk only has to ask the cells around its own.
     */
    private static final int MARGIN_BASE = 620;
    /** Points tried per cell on a plate boundary, which is a narrow thing to land on. */
    static final int TRIES = 12;
    /** How active the boundary has to be. The arc and the rift proper, not their faint outer edge. */
    static final double MIN_STRESS = 0.4;
    /** How far a plume's centre may be pulled to fit its cell: the dome is still strong there. */
    private static final int PLUME_PULL_BASE = 420;
    /** Ground tried around a refused shield or caldera: four bearings at half its foot, at the foot, and half as far again. */
    static final int SHIFTS = 12;

    /**
     * The cell, its margin and the plume pull in this world: the base sizes times the horizontal scale, since a
     * large volcano is built that much bigger ({@link VolcanoPlan#largeScale}) and the margin has to keep clear of it.
     */
    static int cell() { return (int) Math.round(CELL_BASE * com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().horizontal()); }
    static int margin() { return (int) Math.round(MARGIN_BASE * com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().horizontal()); }
    static int plumePull() { return (int) Math.round(PLUME_PULL_BASE * com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().horizontal()); }

    private static final Map<Long, CompletableFuture<Cell>> CACHE = new ConcurrentHashMap<>();

    /** The chosen large volcanoes whose footprint reaches into this chunk. */
    public static List<Site> sitesTouching(ServerLevel level, ChunkPos cp) {
        List<Site> out = new ArrayList<>(1);
        int cx0 = Math.floorDiv(cp.getMiddleBlockX(), cell()), cz0 = Math.floorDiv(cp.getMiddleBlockZ(), cell());
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
        int cx0 = Math.floorDiv(x, cell()), cz0 = Math.floorDiv(z, cell());
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
        int cx0 = Math.floorDiv(x, cell()), cz0 = Math.floorDiv(z, cell());
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
        int cx0 = Math.floorDiv(x, cell()), cz0 = Math.floorDiv(z, cell());
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
        int cx0 = Math.floorDiv(x, cell()), cz0 = Math.floorDiv(z, cell());
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
        int cx0 = Math.floorDiv(x, cell()), cz0 = Math.floorDiv(z, cell());
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
        for (int cx = Math.floorDiv(minX, cell()) - 1; cx <= Math.floorDiv(maxX, cell()) + 1; cx++) {
            for (int cz = Math.floorDiv(minZ, cell()) - 1; cz <= Math.floorDiv(maxZ, cell()) + 1; cz++) {
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
        int cx0 = Math.floorDiv(x, cell()), cz0 = Math.floorDiv(z, cell());
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
        Site s = site(level, Math.floorDiv(x, cell()), Math.floorDiv(z, cell()));
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
        int cx0 = Math.floorDiv(x, cell()), cz0 = Math.floorDiv(z, cell());
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
            int[] hs = heights(gen, px, pz, level, rs);
            int surface = hs[0], floor = hs[1];
            if (surface <= floor || floor >= sea) bits |= 1L << i;
        }
        COAST_LAND.put(key, bits);
        return bits;
    }

    private static Site site(ServerLevel level, int cx, int cz) {
        return cell(level, cx, cz).site();
    }

    /**
     * The generator's surface and sea floor at a column in one pass, {@code {surface, floor}}: the same numbers
     * {@code getBaseHeight} gives for WORLD_SURFACE_WG and OCEAN_FLOOR_WG. Each of those walks the noise column from
     * the top and stops at its own block, so asked separately the column is walked twice; here it is walked once, to
     * the sea floor, and the surface is picked out of what was walked. The noise generator's walk is opened by
     * META-INF/accesstransformer.cfg; any other generator is asked twice, as before.
     */
    static int[] heights(ChunkGenerator gen, int x, int z, LevelHeightAccessor level, RandomState rs) {
        if (gen instanceof NoiseBasedChunkGenerator noise) {
            MutableObject<NoiseColumn> column = new MutableObject<>();
            int floor = noise.iterateNoiseColumn(level, rs, x, z, column, Heightmap.Types.OCEAN_FLOOR_WG.isOpaque())
                    .orElse(level.getMinBuildHeight());
            int surface = level.getMinBuildHeight();
            NoiseColumn col = column.getValue();
            if (col != null) {
                Predicate<net.minecraft.world.level.block.state.BlockState> notAir = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
                // Filled from the top down to the block the walk stopped at; nothing below it was read.
                for (int y = level.getMaxBuildHeight() - 1; y >= floor - 1 && y >= level.getMinBuildHeight(); y--) {
                    if (notAir.test(col.getBlock(y))) {
                        surface = y + 1;
                        break;
                    }
                }
            }
            return new int[] {surface, floor};
        }
        return new int[] {gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs),
                gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs)};
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
        int span = cell() - 2 * margin();
        int minX = cx * cell() + margin(), minZ = cz * cell() + margin();
        int maxX = minX + span, maxZ = minZ + span;
        // Nearby candidates ask after the same structure starts over and over; each is worked out once.
        Map<StructureKey, Boolean> structures = new HashMap<>();

        boolean ocean = GeyserConfig.OCEAN_VOLCANOES.get();
        // The land caldera is out: its 300-block columns stalled the generator's workers under a terrain mod. The
        // code stays; the sites that would have been calderas are shields over plumes and stratovolcanoes on arcs.
        boolean calderas = false;
        // A plume first: fewer of them, and the grander sight. A centre just outside the usable part
        // of the cell is pulled in.
        for (int[] p : HotspotMap.plumeCentres(level, minX - plumePull(), minZ - plumePull(),
                maxX + plumePull(), maxZ + plumePull())) {
            int x = Mth.clamp(p[0], minX, maxX), z = Mth.clamp(p[1], minZ, maxZ);
            if (HotspotMap.plumeStrength(level, x, z) < 0.4) continue;
            // Under the open sea a plume builds an island up from the sea floor, as at Hawaii.
            if (ocean && oceanDepth(level, x, z) >= SiteCheck.ISLAND_DEPTH) {
                Site s = SiteCheck.checkOcean(level, x, z, VolcanoType.SHIELD, VolcanoSetting.ISLAND, 0.0, seed, refused,
                        structures);
                // An island needs open sea round it as well; the plume's dome is wide, so the sea round it is tried.
                int[] uncounted = new int[refused.length];
                for (int i = 0; s == null && i < 8; i++) {
                    double a = rand01(hash(seed, x, z, 0x15A1L)) * Math.PI * 2 + Math.PI * 0.25 * i;
                    double r = 180.0 * (1 + i % 2);
                    int sx = x + (int) Math.round(Math.cos(a) * r), sz = z + (int) Math.round(Math.sin(a) * r);
                    if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) continue;
                    if (HotspotMap.plumeStrength(level, sx, sz) < 0.4 || oceanDepth(level, sx, sz) < SiteCheck.ISLAND_DEPTH) continue;
                    s = SiteCheck.checkOcean(level, sx, sz, VolcanoType.SHIELD, VolcanoSetting.ISLAND, 0.0, seed, uncounted,
                            structures);
                }
                if (s != null) return new Cell(s, refused);
            }
            // A really large hotspot volcano has often emptied its chamber and fallen in.
            VolcanoType type = calderas && rand01(hash(seed, 0, 0, 0xCA1DL)) < 0.34
                    ? VolcanoType.CALDERA : VolcanoType.SHIELD;
            Site s = SiteCheck.checkNear(level, x, z, type, seed, refused, null, minX, minZ, maxX, maxZ, structures);
            if (s != null) return new Cell(s, refused);
        }

        // Then the islands a plume left behind as the plate carried them off it.
        if (ocean) {
            Site s = SiteCheck.trailSite(level, seed, refused, minX, minZ, maxX, maxZ, structures);
            if (s != null) return new Cell(s, refused);
        }

        for (int i = 0; i < TRIES; i++) {
            int x = minX + (int) (rand01(hash(seed, i, 1, 0x71A1L)) * span);
            int z = minZ + (int) (rand01(hash(seed, i, 2, 0x71A2L)) * span);
            PlateSample s = TectonicMap.sampleCached(level, x, z);
            if (s.stress() < MIN_STRESS) continue;
            // An arc stands on the plate that rides over, back from the trench; the plate going under has none.
            if (s.faultType() == FaultType.CONVERGENT_SUBDUCTION && !s.onArc(com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().faultWidth())) continue;
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
                    && oceanDepth(level, x, z) >= SiteCheck.ISLAND_DEPTH) {
                boolean dead = type == VolcanoType.STRATOVOLCANO
                        && VolcanoActivity.of(seed, x, z, type, VolcanoSetting.ISLAND) == VolcanoActivity.EXTINCT;
                double ageRoll = rand01(hash(seed, x, z, 0xA6EDL));
                // Younger than a plume's track: an old arc island is worn and cliffed but still stands well out of the sea.
                double age = 0.1 + 0.3 * ageRoll;
                // The oldest, in a warm sea, have sunk under their reef: Darwin's atoll, on an arc as on a plume.
                VolcanoSetting old = ageRoll >= 0.55 && seaTemperature(level, x, z) > OceanEdifice.ATOLL_TEMPERATURE
                        ? VolcanoSetting.ATOLL : VolcanoSetting.ERODED;
                Site island = dead
                        ? SiteCheck.checkOcean(level, x, z, type, old, old == VolcanoSetting.ATOLL ? 0.4 + age : age, seed,
                                refused, structures)
                        : SiteCheck.checkOcean(level, x, z, type, VolcanoSetting.ISLAND, 0.0, seed, refused, structures);
                // A sea too shallow for an atoll still holds the old island itself.
                if (island == null && dead && old == VolcanoSetting.ATOLL) {
                    island = SiteCheck.checkOcean(level, x, z, type, VolcanoSetting.ERODED, age, seed, new int[refused.length],
                            structures);
                }
                if (island != null) return new Cell(island, refused);
            }
            Site site = SiteCheck.checkNear(level, x, z, type, seed, refused, s.faultType(), minX, minZ, maxX, maxZ,
                    structures);
            if (site != null) return new Cell(site, refused);
        }
        return new Cell(null, refused);
    }

    /** Water over the generator's sea floor at a column, or -1 where the column is dry. */
    public static int oceanDepth(ServerLevel level, int x, int z) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int[] hs = heights(gen, x, z, level, rs);
        int surface = hs[0], floor = hs[1];
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

}
