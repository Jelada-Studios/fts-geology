package com.jeladastudios.ftsgeology.volcano;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
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
                       long seed, int reach, int edificeReach, double roll) {
        public boolean chosen() {
            return GeyserConfig.LARGE_VOLCANOES.get() && roll < GeyserConfig.LARGE_VOLCANO_CHANCE.get();
        }
    }

    /**
     * A search result: the nearest chosen site or null, how many chosen sites the search passed, how many
     * of those were of each type, by {@link VolcanoType} ordinal, and how many candidates were turned
     * down, at type ordinal times {@link #REASONS} plus the reason.
     */
    public record Found(Site site, int count, int[] byType, int[] refused) {}

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

    /** True when a chosen large volcano's mountain stands within {@code margin} blocks of here. */
    public static boolean nearLarge(ServerLevel level, int x, int z, int margin) {
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen()) continue;
                if (Math.hypot(x - s.x(), z - s.z()) <= s.reach() + margin) return true;
            }
        }
        return false;
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
        int cx0 = Math.floorDiv(x, CELL), cz0 = Math.floorDiv(z, CELL);
        Site best = null;
        double bestD = Double.MAX_VALUE;
        int count = 0;
        int[] byType = new int[VolcanoType.values().length];
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
                if (only != null && s.type() != only) continue;
                double d = Math.hypot(x - s.x(), z - s.z());
                if (d < bestD) { bestD = d; best = s; }
            }
        }
        return new Found(best, count, byType, refused);
    }

    public static void clearCache() {
        CACHE.clear();
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
                com.jeladastudios.ftsgeology.GeysersMod.LOGGER.debug("Large volcano cell {},{} worked out in {} ms",
                        cx, cz, (System.nanoTime() - started) / 1_000_000);
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

        // A plume first: fewer of them, and the grander sight. A centre just outside the usable part
        // of the cell is pulled in.
        for (int[] p : HotspotMap.plumeCentres(level, minX - PLUME_PULL, minZ - PLUME_PULL,
                maxX + PLUME_PULL, maxZ + PLUME_PULL)) {
            int x = Mth.clamp(p[0], minX, maxX), z = Mth.clamp(p[1], minZ, maxZ);
            if (HotspotMap.plumeStrength(level, x, z) < 0.4) continue;
            // A really large hotspot volcano has often emptied its chamber and fallen in.
            VolcanoType type = rand01(hash(seed, 0, 0, 0xCA1DL)) < 0.34
                    ? VolcanoType.CALDERA : VolcanoType.SHIELD;
            Site s = checkNear(level, x, z, type, seed, refused, null, minX, minZ, maxX, maxZ, structures);
            if (s != null) return new Cell(s, refused);
        }

        for (int i = 0; i < TRIES; i++) {
            int x = minX + (int) (rand01(hash(seed, i, 1, 0x71A1L)) * span);
            int z = minZ + (int) (rand01(hash(seed, i, 2, 0x71A2L)) * span);
            PlateSample s = TectonicMap.sampleCached(level, x, z);
            if (s.stress() < MIN_STRESS) continue;
            VolcanoType type = switch (s.faultType()) {
                // A quarter of the arc volcanoes have blown their tops off, as at Crater Lake or Aso.
                case CONVERGENT_SUBDUCTION -> rand01(hash(seed, i, 4, 0xCA2DL)) < 0.25
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
            Site site = checkNear(level, x, z, type, seed, refused, s.faultType(), minX, minZ, maxX, maxZ,
                    structures);
            if (site != null) return new Cell(site, refused);
        }
        return new Cell(null, refused);
    }

    /**
     * {@link #check}, and for a shield or caldera turned down for water or a structure, the same check on the
     * ground around it. Those two need hundreds of blocks of dry ground with no village on it, which one point
     * seldom has, while the plume or arc under it spreads well past that point. Only the first candidate is
     * counted as refused, so the counts stay a count of candidates.
     *
     * @param fault the boundary a moved site has to stay on, or null for a plume
     */
    private static Site checkNear(ServerLevel level, int x, int z, VolcanoType type, long seed, int[] refused,
                                  FaultType fault, int minX, int minZ, int maxX, int maxZ,
                                  Map<StructureKey, Boolean> structures) {
        int b = type.ordinal() * REASONS;
        int water = refused[b + WATER], structure = refused[b + STRUCTURE];
        Site site = check(level, x, z, type, seed, refused, structures);
        if (site != null || (type != VolcanoType.SHIELD && type != VolcanoType.CALDERA)) return site;
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
            }
            // Two height samples rule most of the sea out before a full check is paid for.
            if (centreWet(level, sx, sz)) continue;
            Site moved = check(level, sx, sz, type, seed, uncounted, structures);
            if (moved != null) return moved;
        }
        return null;
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
        return surface > floor || floor <= gen.getSeaLevel();
    }

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
                if (surface > floor || floor <= sea) {
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
        // Structures are placed before the mountain and would end up inside it, so none may stand on the
        // edifice itself; one out on the apron keeps its buildings, which the apron will not cover.
        if (structureInTheWay(level, gen, rs, x, z, plan[1] + 8, structures)) {
            return refuse(refused, type, STRUCTURE, x, z, "structure within " + (plan[1] + 8));
        }
        return new Site(x, z, baseY, plan[2], type, magnitude, seed, plan[0], plan[1],
                rand01(hash(seed, x, z, 0xC40L)));
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
     * @param due answers already worked out for this cell, by structure set and start chunk
     */
    private static boolean structureInTheWay(ServerLevel level, ChunkGenerator gen, RandomState rs,
                                             int x, int z, int radius, Map<StructureKey, Boolean> due) {
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
                if (s.step() == GenerationStep.Decoration.SURFACE_STRUCTURES
                        && s.type() != StructureType.RUINED_PORTAL) {
                    surface.add(s);
                }
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
