package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.Tags;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Whether a building stands up to the shaking: a rough structural reading of what players and villages built, block
 * by block, against how hard the ground shakes where it stands.
 *
 * <ul>
 *   <li><b>Load.</b> Each block of a building carries every block of it above it in its column. What it can carry
 *   depends on what it is -- sand and gravel carry nothing once shaken, glass and wool little, wood a fair load,
 *   stone and brick a great deal, obsidian and metal all of it -- and drops as the shaking grows: a fifty-block stone
 *   tower stands through a moderate quake and not a violent one, and one with a sand course in its column falls at
 *   the first strong shaking. What fails comes down with everything over it.</li>
 *   <li><b>Shaking.</b> Past an intensity of its own a wall comes down whatever it carries, the weaker its material
 *   the sooner, by the classes of EMS-98 ({@link Fabric}): sand at the onset, glass and wool soon after, rubble and mud
 *   brick about Mercalli IX, stone and brick about X, concrete about XI, timber later still; obsidian and metal never.
 *   The odds grow over a degree or two either side, as EMS-98's "few", "many" and "most" do. A tall building goes a
 *   little sooner. Near the fault of a great quake, what is not built strong is flattened.</li>
 *   <li><b>Overhangs.</b> A block over open air holds only as far out from the nearest supported block as its
 *   material spans, less in strong shaking.</li>
 *   <li><b>Slender towers.</b> A column standing many times higher than what is round it sways over and falls
 *   sideways in strong shaking.</li>
 * </ul>
 */
public final class Structural {

    private Structural() {}

    /** The least intensity at which a building is read for load, overhangs and slender towers. */
    static final double ONSET = 5.0;
    /**
     * Loose sand and gravel hold together only by lying still: walls of them are read from this weaker shaking, a
     * magnitude 5 close to its fault, and nothing else is.
     */
    static final double LOOSE_ONSET = 4.2;
    /** How high over its ground a building is read. */
    private static final int HEIGHT = 96, SKY = 6;
    /** Across how far a support for an overhang is looked for. */
    private static final int SPAN_LOOK = 12;

    private static final LongAdder FAILED = new LongAdder(), OVERHANG = new LongAdder(), TOPPLED = new LongAdder(),
            SHAKEN = new LongAdder();

    /** How much of a block's strength is left at this intensity: all of it up to 5.5, a third at 9. */
    static double left(double intensity) {
        double t = Math.max(0.0, Math.min(1.0, (intensity - 5.5) / 3.5));
        return (1.0 - 0.65 * t) / Math.sqrt(Math.max(0.1, GeyserConfig.SHAKING_DAMAGE.get()));
    }

    /** How many blocks over it a block can carry, unshaken. */
    public static double strength(BlockState s) {
        if (s.getBlock() instanceof FallingBlock) return 0;                      // sand, gravel, concrete powder
        if (strong(s)) return 800;
        if (s.getSoundType() == SoundType.GLASS || s.is(BlockTags.ICE)) return 6;
        if (s.is(BlockTags.WOOL) || s.is(BlockTags.LEAVES) || s.is(Blocks.HAY_BLOCK) || s.is(BlockTags.DIRT)) return 4;
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.getSoundType() == SoundType.WOOD) return 48;
        float hard = s.getBlock().defaultDestroyTime();
        if (hard < 0) return 2000;
        // Stone, cobble, brick, concrete, deepslate: hardness 1.5-3.5.
        return Math.min(240, hard * 40 + 20);
    }

    private static boolean strong(BlockState s) {
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN) || s.is(Blocks.REINFORCED_DEEPSLATE)
                || s.is(Tags.Blocks.STORAGE_BLOCKS) || s.is(Blocks.NETHERITE_BLOCK);
    }

    /** How far out over open air a block of this can reach from a support, unshaken. */
    static int span(BlockState s) {
        double st = strength(s);
        if (st >= 800) return 12;
        if (st >= 60) return 5;
        if (st >= 40) return 6;         // wood beams span further than their load would say
        if (st >= 4) return 1;
        return 0;
    }

    /**
     * What a wall is built of, as the European Macroseismic Scale (EMS-98) sorts buildings by how they stand shaking:
     * {@code A} rubble, earth and mud brick; {@code B} plain stone and brick masonry; {@code C} concrete; {@code D}
     * timber, whose frames bend and stand; {@code E} metal; {@code F} what never comes down. Loose sand and gravel, and
     * glass, wool and hay, are weaker than any building and kept apart.
     */
    enum Fabric { LOOSE, FRAIL, A, B, C, D, E, F }

    static Fabric fabric(BlockState s) {
        if (s.getBlock() instanceof FallingBlock) return Fabric.LOOSE;
        float hard = s.getBlock().defaultDestroyTime();
        if (strong(s) || hard < 0) return Fabric.F;
        SoundType sound = s.getSoundType();
        if (sound == SoundType.GLASS || s.is(BlockTags.ICE) || s.is(BlockTags.WOOL) || s.is(BlockTags.LEAVES)
                || s.is(Blocks.HAY_BLOCK)) return Fabric.FRAIL;
        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (s.is(BlockTags.DIRT) || s.is(Blocks.CLAY) || s.is(Tags.Blocks.COBBLESTONE) || name.contains("cobble")
                || name.contains("mud") || name.contains("rubble") || name.contains("adobe")) return Fabric.A;
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || sound == SoundType.WOOD || sound == SoundType.BAMBOO_WOOD
                || sound == SoundType.CHERRY_WOOD || sound == SoundType.NETHER_WOOD) return Fabric.D;
        if (name.contains("concrete")) return Fabric.C;
        if (sound == SoundType.METAL || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.COPPER
                || sound == SoundType.ANVIL || sound == SoundType.CHAIN || hard >= 5.0f) return Fabric.E;
        return Fabric.B;
    }

    /**
     * How many degrees of Mercalli later than rubble a class comes to the same damage: EMS-98's table moves a degree
     * from one class to the next.
     */
    static double shift(Fabric f) {
        return switch (f) {
            case A -> 0.0;
            case B -> 1.0;
            case C -> 2.0;
            case D -> 3.0;
            case E -> 4.0;
            default -> 99.0;
        };
    }

    /** The intensity of this mod's shaking law at a degree of the Mercalli scale (see {@link FeltShaking#mercalli}). */
    static double atMercalli(double mercalli) {
        return (mercalli + 1.0) / 1.35;
    }

    /**
     * The intensity a wall of this is shaken down at, whatever it carries: the middle of EMS-98's grade 5 (destruction)
     * for its class -- rubble about Mercalli IX and a half, stone and brick a degree later, concrete two, timber three.
     * Rubble goes before dressed stone although it is the harder block: it is loose stones in mortar. Wood goes last of
     * the walls, as timber frames do; a tall stone house on a weak storey goes first of all, by its load.
     */
    static double standsTo(BlockState s) {
        Fabric f = fabric(s);
        return switch (f) {
            case LOOSE -> 4.4;
            case FRAIL -> 6.0;
            case F -> 99.0;
            default -> atMercalli(9.4 + shift(f));
        };
    }

    /** Whether a block makes a footing: stone, brick, concrete or metal, set in the ground; not rubble, earth or wood. */
    static boolean footing(BlockState s) {
        Fabric f = fabric(s);
        return f == Fabric.B || f == Fabric.C || f == Fabric.E || f == Fabric.F;
    }

    /** How much sooner a tall building is shaken down, per block over six. */
    private static final double TALL = 0.02;
    /**
     * Over how much intensity the odds of a column coming down go from none to all, centred on what its walls stand to:
     * about three degrees of Mercalli, a tenth of a class's buildings down a degree before its middle, nine in ten a degree
     * after, as EMS-98 has "few", "many" and "most".
     */
    private static final double SPREAD = 2.0;

    /**
     * Reads the buildings in one chunk shaken at {@code intensity}; what fails is brought down at a moment of the
     * shaking by {@link Collapse}.
     */
    static void chunk(ServerLevel level, LevelChunk chunk, double intensity, LongSet placed, List<BoundingBox> pieces,
                      LongSet made, long from, int spread) {
        if (intensity < LOOSE_ONSET || !GeyserConfig.SHAKING_LOOSENS_BUILDS.get()) return;
        ChunkPos cp = chunk.getPos();
        double left = left(intensity);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LongSet failing = new LongOpenHashSet();
        // The columns with anything built in them.
        LongSet columns = new LongOpenHashSet();
        for (long p : placed) columns.add(BlockPos.asLong(BlockPos.getX(p), 0, BlockPos.getZ(p)));
        // And those with blocks of a building no one was seen placing, as the shaking found them.
        columns.addAll(made);
        if (!pieces.isEmpty()) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                    if (ShakingDamage.inside(pieces, x, top, z)) columns.add(BlockPos.asLong(x, 0, z));
                }
            }
        }
        for (long c : columns) {
            int x = BlockPos.getX(c), z = BlockPos.getZ(c);
            int[] ground = QuakePlanner.naturalGround(chunk, x, z, placed, pieces, m);
            if (ground == null || ground[1] == Integer.MIN_VALUE) continue;
            column(level, chunk, x, z, ground[0], left, intensity, placed, pieces, failing, m, from, spread);
        }
    }

    private static void column(ServerLevel level, LevelChunk chunk, int x, int z, int ground, double left, double intensity,
                               LongSet placed, List<BoundingBox> pieces, LongSet failing, BlockPos.MutableBlockPos m,
                               long from, int spread) {
        // What was done under and into the building: a deep footing, an isolating course, bracing. It shakes the
        // building as if the quake were that much weaker.
        double work = groundwork(level, chunk, x, z, ground, placed, pieces, m);
        if (work != 0) {
            intensity += work;
            left = left(intensity);
            if (intensity < LOOSE_ONSET) return;
        }
        // The column's built blocks, bottom up, to the sky.
        int top = ground, air = 0;
        for (int y = ground + 1; y <= ground + HEIGHT && air < SKY && y < level.getMaxBuildHeight(); y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir()) {
                air++;
                continue;
            }
            air = 0;
            top = y;
        }
        if (top <= ground) return;
        int[] above = new int[top - ground + 1];
        int count = 0;
        for (int y = top; y > ground; y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            above[y - ground] = count;
            if (!s.isAir() && Collapse.built(level, s, m, placed, pieces)) count++;
        }
        boolean looseOnly = intensity < ONSET;
        // Load: the lowest block that cannot carry what is over it brings itself and all of that down.
        for (int y = ground + 1; y <= top && !looseOnly; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir() || !Collapse.built(level, s, m, placed, pieces)) continue;
            int load = above[y - ground];
            if (load == 0) break;
            if (load > strength(s) * left) {
                bringDown(level, x, y, top, z, from, spread, 0, 0, failing, m, placed, pieces);
                FAILED.increment();
                return;
            }
        }
        // Shaken down: past what its walls stand, the column comes down, the likelier the harder it shakes.
        int lowest = Integer.MIN_VALUE, n = 0;
        double[] stands = new double[top - ground];
        for (int y = ground + 1; y <= top; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir() || !Collapse.built(level, s, m, placed, pieces)) continue;
            if (lowest == Integer.MIN_VALUE) lowest = y;
            stands[n++] = standsTo(s);
        }
        if (n > 0) {
            java.util.Arrays.sort(stands, 0, n);
            if (looseOnly && stands[n / 2] >= ONSET) return;
            double at = stands[n / 2] - TALL * Math.max(0, top - ground - 6);
            double chance = Math.max(0.0, Math.min(1.0, 0.5 + (intensity - at) / SPREAD));
            if (chance > 0 && level.random.nextDouble() < chance) {
                bringDown(level, x, lowest, top, z, from, spread, 0, 0, failing, m, placed, pieces);
                SHAKEN.increment();
                return;
            }
        }
        if (looseOnly) return;
        // Overhangs: a block over air needs a supported block of the same floor within its span.
        for (int y = ground + 2; y <= top; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir() || !Collapse.built(level, s, m, placed, pieces)) continue;
            if (!chunk.getBlockState(m.set(x, y - 1, z)).isAir()) continue;
            int reach = (int) Math.floor(span(s) * left);
            if (!supported(level, x, y, z, reach, placed, pieces)) {
                bringDown(level, x, y, top, z, from, spread, 0, 0, failing, m, placed, pieces);
                OVERHANG.increment();
                return;
            }
        }
        // Slender towers: a column over three times the height of everything built round it, above twelve blocks.
        int height = top - ground;
        if (height >= 12 && intensity >= 6.5) {
            int round = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int k = 1; k <= 2; k++) {
                    int nx = x + d.getStepX() * k, nz = z + d.getStepZ() * k;
                    if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(nx, ground, nz))) continue;
                    int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz) - 1 - ground;
                    round = Math.max(round, h);
                }
            }
            if (height > 3 * Math.max(1, round) && level.random.nextDouble() < Math.min(0.9, (intensity - 6.0) / 3.0)) {
                Direction way = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
                bringDown(level, x, ground + Math.max(2, height / 3), top, z, from, spread, way.getStepX(), way.getStepZ(),
                        failing, m, placed, pieces);
                TOPPLED.increment();
            }
        }
    }

    /** Strong blocks of a footing, and the most of them that count. */
    private static final int FOOTING_MOST = 8;
    /**
     * Intensity taken off per block of footing, more for concrete, for a footing down to rock, for an isolating course,
     * per brace.
     */
    private static final double PER_FOOTING = 0.15, CONCRETE = 1.25, ON_ROCK = 0.5, ISOLATED = 1.0, PER_BRACE = 0.3,
            BRACES_MOST = 1.0;
    /** Intensity added for walls set straight on the ground with no footing: on soil, and on sand, gravel or mud. */
    private static final double BARE_SOIL = 0.5, BARE_LOOSE = 1.0;
    /** The most a column's ground ever adds to its shaking. */
    static final double MOST_WORSE = BARE_LOOSE;

    /**
     * How much harder or less hard a building's column is shaken for its foundation and the work done in its ground, in
     * units of intensity:
     * <ul>
     *   <li><b>No footing</b>: walls set straight on the ground. On rock they stand as they are; on soil they settle
     *   unevenly and crack, half a degree the worse; on sand, gravel or mud, which give way and slide under them, a whole
     *   degree. Houses of rubble and mud brick on bare ground are the first to fall in every great earthquake.</li>
     *   <li><b>A footing of rubble, earth or wood</b> set in the ground: it spreads the load and is no worse than rock.</li>
     *   <li><b>A footing</b> of stone, brick, concrete or metal standing in the ground, the ground on all four sides of
     *   it: the deeper the better, concrete best, and better again where it reaches rock. Where the walls have one and
     *   where they have not is told column by column, so a house with a footing under part of its walls is spared there
     *   only.</li>
     *   <li><b>An isolating course</b>: slime or honey under the walls, which give and spring back and let the ground move
     *   under the building without shaking it as hard, as a modern building's rubber bearings do.</li>
     *   <li><b>Bracing</b>: iron bars and chains in the column, tying it across.</li>
     * </ul>
     * That soft ground shakes harder whatever stands on it is the ground's own (see {@link SiteResponse}).
     */
    static double groundwork(ServerLevel level, LevelChunk chunk, int x, int z, int ground, LongSet placed,
                             List<BoundingBox> pieces, BlockPos.MutableBlockPos m) {
        double work = 0;
        int footing = 0, weak = 0, y = ground + 1;
        boolean concrete = false;
        for (; y <= ground + FOOTING_MOST * 2; y++) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (s.isAir() || !Collapse.built(level, s, m, placed, pieces) || !buried(level, x, y, z, placed, pieces)) break;
            if (footing(s)) {
                footing++;
                if (fabric(s) == Fabric.C) concrete = true;
            } else {
                weak++;
            }
        }
        BlockState under = chunk.getBlockState(m.set(x, ground, z));
        boolean rock = under.is(BlockTags.BASE_STONE_OVERWORLD) || under.is(Tags.Blocks.STONE);
        if (footing > 0) {
            work -= PER_FOOTING * Math.min(footing, FOOTING_MOST) * (concrete ? CONCRETE : 1.0);
            if (rock) work -= ON_ROCK;
        } else if (weak == 0 && !rock) {
            work += loose(under) ? BARE_LOOSE : BARE_SOIL;
        }
        // The course: slime or honey among the lowest few blocks over the footing.
        for (int k = y; k <= y + 2; k++) {
            BlockState s = chunk.getBlockState(m.set(x, k, z));
            if (s.is(Blocks.SLIME_BLOCK) || s.is(Blocks.HONEY_BLOCK)) {
                work -= ISOLATED;
                break;
            }
        }
        int braces = 0;
        for (int k = y; k <= y + HEIGHT && k < level.getMaxBuildHeight(); k++) {
            BlockState s = chunk.getBlockState(m.set(x, k, z));
            if (s.is(Blocks.IRON_BARS) || s.is(Blocks.CHAIN)) braces++;
            if (s.isAir() && k > y + 8) break;
        }
        work -= Math.min(BRACES_MOST, PER_BRACE * braces);
        return work;
    }

    /** Ground that gives way under a wall: sand, gravel, mud, clay, snow, ash. */
    private static boolean loose(BlockState s) {
        return s.getBlock() instanceof FallingBlock || s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVEL) || s.is(Blocks.MUD)
                || s.is(Blocks.CLAY) || s.is(BlockTags.SNOW) || s.is(Blocks.SOUL_SAND) || s.is(Blocks.SOUL_SOIL)
                || s.is(com.jeladastudios.ftsgeology.registry.ModBlocks.VOLCANIC_ASH.get());
    }

    /** Whether a built block stands in the ground: natural ground on all four sides of it. */
    private static boolean buried(ServerLevel level, int x, int y, int z, LongSet placed, List<BoundingBox> pieces) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            n.set(x + d.getStepX(), y, z + d.getStepZ());
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) return false;
            BlockState s = level.getBlockState(n);
            // A neighbour of the footing itself counts: a slab of footing is buried at its edges only.
            if (s.isAir() || !s.getFluidState().isEmpty()) return false;
            if (Collapse.built(level, s, n, placed, pieces) && !footing(s)) return false;
        }
        return true;
    }

    /** Whether a supported block of the same floor lies within {@code reach} along the building from this one. */
    private static boolean supported(ServerLevel level, int x, int y, int z, int reach, LongSet placed,
                                     List<BoundingBox> pieces) {
        if (reach <= 0) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
        LongSet seen = new LongOpenHashSet();
        queue.add(new long[]{BlockPos.asLong(x, y, z), 0});
        seen.add(BlockPos.asLong(x, y, z));
        while (!queue.isEmpty()) {
            long[] e = queue.poll();
            int px = BlockPos.getX(e[0]), pz = BlockPos.getZ(e[0]);
            if (e[1] > 0) {
                BlockState under = level.getBlockState(m.set(px, y - 1, pz));
                if (!under.isAir() && under.getFluidState().isEmpty()) return true;
            }
            if (e[1] >= Math.min(reach, SPAN_LOOK)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = px + d.getStepX(), nz = pz + d.getStepZ();
                long k = BlockPos.asLong(nx, y, nz);
                if (seen.contains(k) || !com.jeladastudios.ftsgeology.util.Loaded.at(level, m.set(nx, y, nz))) continue;
                BlockState n = level.getBlockState(m);
                if (n.isAir() || !n.getFluidState().isEmpty()) continue;
                seen.add(k);
                queue.add(new long[]{k, e[1] + 1});
            }
        }
        return false;
    }

    /** Everything built in a column from {@code y} up to {@code top} comes down over the shaking. */
    private static void bringDown(ServerLevel level, int x, int y, int top, int z, long from, int spread, double px, double pz,
                                  LongSet failing, BlockPos.MutableBlockPos m, LongSet placed, List<BoundingBox> pieces) {
        long at = from + level.random.nextInt(Math.max(1, spread));
        for (int yy = top; yy >= y; yy--) {
            m.set(x, yy, z);
            if (!failing.add(m.asLong())) continue;
            BlockState s = level.getBlockState(m);
            if (s.isAir() || !Collapse.built(level, s, m, placed, pieces)) continue;
            // The top goes first, a moment apart, so a falling column reads as one coming down.
            Collapse.fail(level, m.immutable(), at + (top - yy) / 3, px, pz);
        }
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT,
                "structure: %d columns failed under load, %d shaken down, %d overhangs fell, %d towers toppled",
                FAILED.sum(), SHAKEN.sum(), OVERHANG.sum(), TOPPLED.sum());
    }
}
