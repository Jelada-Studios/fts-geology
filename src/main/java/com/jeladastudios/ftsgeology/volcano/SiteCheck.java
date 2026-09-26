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

import static com.jeladastudios.ftsgeology.volcano.VolcanoField.*;

/**
 * Whether a large volcano can stand at a candidate point: on land ({@link #check}), in the sea ({@link #checkOcean}),
 * a little way off when water or a structure turned it down ({@link #checkNear}), and along a plume's trail
 * ({@link #trailSite}). Asked of the generator's own terrain, since most of these cells are not built yet; each
 * refusal is counted by reason for {@code /geology field}. {@link VolcanoField} keeps the cells and the answers.
 */
final class SiteCheck {

    private SiteCheck() {}

    /**
     * {@link #check}, and for a cone or caldera turned down for water or a structure, the same check on the
     * ground around it. Those need hundreds of blocks of dry ground with no village on it, which one point
     * seldom has, while the plume or arc under it spreads well past that point. Only the first candidate is
     * counted as refused, so the counts stay a count of candidates. A fissure runs along its rift and is not moved.
     *
     * @param fault the boundary a moved site has to stay on, or null for a plume
     */
    static Site checkNear(ServerLevel level, int x, int z, VolcanoType type, long seed, int[] refused,
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
                if (fault == FaultType.CONVERGENT_SUBDUCTION && !p.onArc(com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().faultWidth())) continue;
            }
            // Two height samples rule most of the sea out before a full check is paid for.
            if (centreWet(level, sx, sz)) continue;
            Site moved = check(level, sx, sz, type, seed, uncounted, structures);
            if (moved != null) return moved;
        }
        return null;
    }

    /** Water over the sea floor a live island needs at its centre, and a sunken one. */
    static final int ISLAND_DEPTH = 8, SUNKEN_DEPTH = 12;
    /** How far along a plume's track its old islands begin; nearer the plume the live island stands. */
    private static final double TRAIL_START = 1000.0;

    /**
     * Where a plume's track through the sea crosses this cell: by how far along it is, an old island, then an atoll
     * in warm water or a guyot in cold.
     */
    static Site trailSite(ServerLevel level, long seed, int[] refused, int minX, int minZ, int maxX, int maxZ,
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

    /**
     * Whether a volcano can rise from the sea floor here, from the generator's own terrain: open sea over the centre,
     * little land under the body, a floor without a cliff in it, and no ocean monument in the way.
     */
    static Site checkOcean(ServerLevel level, int x, int z, VolcanoType type, VolcanoSetting setting, double age,
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
                int[] hs = heights(gen, px, pz, level, rs);
                int surface = hs[0], floor = hs[1];
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
        if (sorted[12] - sorted[4] > 40 * com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().horizontal()) {
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
        // Only a monument is in the way: a wreck or a ruin is built first and ends up inside the island. And only
        // near the summit, where the crater would cut it; one out on the flank is built round, as on land.
        int keepClear = Math.max(plan.craterR + 16, (int) Math.round(plan.isle.edifice * 0.35));
        if (structureInTheWay(level, gen, rs, x, z, keepClear, structures, true)) {
            return refuse(refused, type, STRUCTURE, x, z, "monument within " + keepClear);
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
    static int landOffCoast(ServerLevel level, ChunkGenerator gen, RandomState rs, VolcanoPlan.Ctx plan,
                                    int x, int z, int sea) {
        if (plan.isle.setting == VolcanoSetting.GUYOT) return 0;
        int dry = 0;
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16 + 0.2;
            double r = OceanEdifice.coastAt(plan, a) + COAST_CLEARANCE;
            int px = x + (int) Math.round(Math.cos(a) * r), pz = z + (int) Math.round(Math.sin(a) * r);
            int[] hs = heights(gen, px, pz, level, rs);
            int surface = hs[0], floor = hs[1];
            if (surface <= floor || floor >= sea) dry++;
        }
        return dry;
    }

    /** The magnitude of a large volcano planned at this point. */
    static int magnitudeAt(long seed, int x, int z) {
        return VolcanoSize.LARGE.magnitude(rand01(hash(seed, x, z, 0x3A6L)));
    }

    /** How far out a large volcano of this type planned here reaches, or 0 where it cannot be planned. */
    static int footAt(ServerLevel level, int x, int z, VolcanoType type, long seed) {
        int sea = level.getChunkSource().getGenerator().getSeaLevel();
        // Planned once on a provisional base, only to learn how wide a ring to sample.
        int[] probe = VolcanoBuilder.largeFootprint(level, x, sea + 8, z, magnitudeAt(seed, x, z), type, seed);
        return probe == null ? 0 : probe[1];
    }

    /** True where the generator puts water, or ground at the sea, on this column. */
    static boolean centreWet(ServerLevel level, int x, int z) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int[] hs = heights(gen, x, z, level, rs);
        int surface = hs[0], floor = hs[1];
        return surface - floor >= DEEP_WATER;
    }

    /** Water this deep under a probe is a lake or the sea; anything shallower is a river the mountain buries. */
    private static final int DEEP_WATER = 4;

    /**
     * Whether a large volcano of this type can stand here, from the generator's own terrain rather than
     * the world, which for most of these cells does not exist yet.
     */
    static Site check(ServerLevel level, int x, int z, VolcanoType type, long seed, int[] refused,
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
        boolean[][] wetAt = new boolean[3][8];
        boolean[] riverAt = new boolean[8];
        int rivers = 0;
        int n = 0;
        int wetMid = type == VolcanoType.SHIELD ? 4 : 2;
        for (int ring = 0; ring <= 2; ring++) {
            int count = ring == 0 ? 1 : 8;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2 * i / 8 + ring * 0.39;
                double r = foot * ring / 2.0;
                int px = x + (int) Math.round(Math.cos(a) * r);
                int pz = z + (int) Math.round(Math.sin(a) * r);
                int[] hs = heights(gen, px, pz, level, rs);
                int surface = hs[0], floor = hs[1];
                // Only deep water counts here: a shallow lake under the body is built over.
                if (surface - floor >= DEEP_WATER) {
                    if (ring == 0) return refuse(refused, type, WATER, x, z, "centre in water");
                    wet[ring]++;
                    wetAt[ring][i] = true;
                }
                // A river through the body would be dammed by the mountain, and the ground it cut shows through the
                // cone as stripes. Volcanoes stand by rivers, not across them: a river at the centre, one crossing
                // from side to side, or one winding through much of the body refuses the site; a river lapping one
                // side is built round, and out at the foot the apron only laps one.
                if (ring < 2 && TfcCompat.river(gen.getBiomeSource().getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(px),
                        net.minecraft.core.QuartPos.fromBlock(sea), net.minecraft.core.QuartPos.fromBlock(pz), rs.sampler()))) {
                    if (ring == 0) return refuse(refused, type, WATER, x, z, "river under the centre");
                    riverAt[i] = true;
                    rivers++;
                }
                ground[n++] = floor - 1;
            }
            // Water under the body of the mountain refuses it; a lake or a shore out at the foot does not,
            // since the apron carries on under water as a thin skin. A volcano half in the sea is a job for
            // the ocean volcanoes, not this. A body already too wet is refused before its foot is sampled.
            if (ring == 1 && wet[1] > wetMid) return refuse(refused, type, WATER, x, z, "wet " + wet[1] + " mid");
            if (ring == 1) {
                boolean crossing = false;
                for (int b = 0; b < 4; b++) crossing |= riverAt[b] && riverAt[b + 4];
                if (crossing || rivers >= 3) return refuse(refused, type, WATER, x, z, "river through the body");
                // A few small lakes under the body are built over; one lake along a whole side is not: the flank ran into it
                // on its own profile and buried half a rift lake. Three wet probes in a row half way out is such a lake.
                int run = 0, longest = 0;
                for (int k = 0; k < 16; k++) {
                    run = wetAt[1][k % 8] ? run + 1 : 0;
                    longest = Math.max(longest, Math.min(run, 8));
                }
                if (longest >= 3) return refuse(refused, type, WATER, x, z, "lake along one side, " + longest + " mid");
            }
            // Deep water on opposite sides is a lake or a river valley the mountain would fill from shore to shore.
            for (int b = 0; ring > 0 && b < 4; b++) {
                if (wetAt[ring][b] && wetAt[ring][b + 4]) return refuse(refused, type, WATER, x, z, "water either side");
            }
        }
        if (wet[2] > 6) return refuse(refused, type, WATER, x, z, "wet " + wet[2] + " foot");

        // Without the highest and lowest of the nine inner points, so one peak or gorge under the body
        // does not turn a whole mountain down. A caldera cuts a floor and needs ground that allows it; a
        // shield is low and spreads over broken country; a cone grows out of whatever is there.
        int[] inner = Arrays.copyOf(ground, 9);
        Arrays.sort(inner);
        int relief = inner[7] - inner[1];
        // The tall world type stands its mountains as much higher as it lays them wider, so what counts as level
        // ground there is scaled with the layout.
        int allowed = (int) Math.round(switch (type) {
            case SHIELD -> 200;
            case CALDERA -> 96;
            default -> 140;
        } * com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().horizontal());
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
    static Site refuse(int[] refused, VolcanoType type, int reason, int x, int z, String why) {
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
    static boolean structureInTheWay(ServerLevel level, ChunkGenerator gen, RandomState rs,
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
    static boolean structureDue(ServerLevel level, ChunkGenerator gen, RandomState rs,
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
