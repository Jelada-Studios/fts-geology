package com.jeladastudios.ftsgeology.volcano;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the large volcanoes stand.
 *
 * <p>Not a structure: a structure start reaches only eight chunks, and a large volcano's foot runs
 * 200 to 400 blocks out. So, like the hotspots, sites come from the world seed over a coarse grid,
 * and any chunk on any thread gets the same answer.</p>
 *
 * <p>A cell becomes a shield or caldera over a plume, a stratovolcano on a subduction arc and a
 * fissure on a rift. It is refused on water, on ground too broken for it, and where a surface
 * structure is due, since that structure would be built inside the mountain.</p>
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
     * A search result: the nearest chosen site, how many chosen sites the search passed, and how many
     * of those were of each type, by {@link VolcanoType} ordinal.
     */
    public record Found(Site site, int count, int[] byType) {}

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

    private static final Map<Long, Optional<Site>> CACHE = new ConcurrentHashMap<>();

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
        for (int ox = -rings; ox <= rings; ox++) {
            for (int oz = -rings; oz <= rings; oz++) {
                Site s = site(level, cx0 + ox, cz0 + oz);
                if (s == null || !s.chosen()) continue;
                count++;
                byType[s.type().ordinal()]++;
                if (only != null && s.type() != only) continue;
                double d = Math.hypot(x - s.x(), z - s.z());
                if (d < bestD) { bestD = d; best = s; }
            }
        }
        return best == null ? null : new Found(best, count, byType);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static Site site(ServerLevel level, int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Optional<Site> hit = CACHE.get(key);
        if (hit == null) {
            if (CACHE.size() > 50_000) CACHE.clear();
            // Two threads may work the same cell out at once. The answer is the same either way.
            hit = Optional.ofNullable(evaluate(level, cx, cz));
            CACHE.putIfAbsent(key, hit);
        }
        return hit.orElse(null);
    }

    private static Site evaluate(ServerLevel level, int cx, int cz) {
        long seed = hash(level.getSeed(), cx, cz, 0x5EED1L);
        int span = CELL - 2 * MARGIN;
        int minX = cx * CELL + MARGIN, minZ = cz * CELL + MARGIN;
        int maxX = minX + span, maxZ = minZ + span;

        // A plume first: fewer of them, and the grander sight. A centre just outside the usable part
        // of the cell is pulled in.
        for (int[] p : HotspotMap.plumeCentres(level, minX - PLUME_PULL, minZ - PLUME_PULL,
                maxX + PLUME_PULL, maxZ + PLUME_PULL)) {
            int x = Mth.clamp(p[0], minX, maxX), z = Mth.clamp(p[1], minZ, maxZ);
            if (HotspotMap.plumeStrength(level, x, z) < 0.4) continue;
            // A really large hotspot volcano has often emptied its chamber and fallen in.
            VolcanoType type = rand01(hash(seed, 0, 0, 0xCA1DL)) < 0.34
                    ? VolcanoType.CALDERA : VolcanoType.SHIELD;
            Site s = check(level, x, z, type, seed);
            if (s != null) return s;
        }

        for (int i = 0; i < TRIES; i++) {
            int x = minX + (int) (rand01(hash(seed, i, 1, 0x71A1L)) * span);
            int z = minZ + (int) (rand01(hash(seed, i, 2, 0x71A2L)) * span);
            PlateSample s = TectonicMap.sampleCached(level, x, z);
            if (s.stress() < MIN_STRESS) continue;
            VolcanoType type = switch (s.faultType()) {
                case CONVERGENT_SUBDUCTION -> VolcanoType.STRATOVOLCANO;
                case DIVERGENT -> VolcanoType.FISSURE;
                default -> null;
            };
            if (type == null) continue;
            // Rifts run long and largely over land, so left alone they out-numbered the arcs four to
            // one in testing. A flood-basalt fissure this size is the rarer sight in the real world.
            if (type == VolcanoType.FISSURE && rand01(hash(seed, i, 3, 0xF155L)) < 0.6) continue;
            Site site = check(level, x, z, type, seed);
            if (site != null) return site;
        }
        return null;
    }

    /**
     * Whether a large volcano of this type can stand here, from the generator's own terrain rather than
     * the world, which for most of these cells does not exist yet.
     */
    private static Site check(ServerLevel level, int x, int z, VolcanoType type, long seed) {
        int magnitude = VolcanoSize.LARGE.magnitude(rand01(hash(seed, x, z, 0x3A6L)));
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();

        // Planned once on a provisional base, only to learn how wide a ring to sample.
        int[] probe = VolcanoBuilder.largeFootprint(level, x, sea + 8, z, magnitude, type, seed);
        if (probe == null) return null;
        int foot = probe[1];

        // The centre, then eight points half way out and eight at the foot.
        int[] ground = new int[17];
        int wet = 0, n = 0;
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
                    if (ring == 0) return null;      // never centred in water
                    wet++;
                }
                ground[n++] = floor - 1;
            }
        }
        // A river or a lake edge under the flank is fine; a volcano half in the sea is a job for the
        // ocean volcanoes, not this.
        if (wet > 2) return null;

        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < 9; i++) {
            lo = Math.min(lo, ground[i]);
            hi = Math.max(hi, ground[i]);
        }
        // A caldera cuts a floor four hundred blocks across and needs ground that allows it; a cone
        // grows out of whatever is there.
        if (hi - lo > (type.excavates() ? 48 : 140)) return null;
        int[] sorted = ground.clone();
        Arrays.sort(sorted);
        int baseY = sorted[8];

        int[] plan = VolcanoBuilder.largeFootprint(level, x, baseY, z, magnitude, type, seed);
        if (plan == null) return null;
        if (structureInTheWay(level, gen, rs, x, z, plan[1] + 48)) return null;
        return new Site(x, z, baseY, plan[2], type, magnitude, seed, plan[0], plan[1],
                rand01(hash(seed, x, z, 0xC40L)));
    }

    /**
     * True when a surface structure is due within {@code radius} blocks, asked of the structure
     * placement itself so it works for ungenerated chunks. Errs towards refusing; ruined portals are
     * ignored.
     */
    private static boolean structureInTheWay(ServerLevel level, ChunkGenerator gen, RandomState rs,
                                             int x, int z, int radius) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        long seed = state.getLevelSeed();
        int minCX = (x - radius) >> 4, maxCX = (x + radius) >> 4;
        int minCZ = (z - radius) >> 4, maxCZ = (z + radius) >> 4;
        long r2 = (long) radius * radius;
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
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
                    if (!spread.isStructureChunk(state, cand.x, cand.z)) continue;
                    int y = gen.getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                    Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(bx),
                            QuartPos.fromBlock(y), QuartPos.fromBlock(bz), rs.sampler());
                    for (Structure s : surface) {
                        if (s.biomes().contains(biome)) return true;
                    }
                }
            }
        }
        return false;
    }

}
