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
 *   the sooner: sand at the onset, glass and wool soon after, wood and mud brick at about IX on the Mercalli scale,
 *   rubble a little later, dressed stone and brick later still, deepslate last; obsidian and metal never. A tall
 *   building goes a little sooner. Near the fault of a great quake, what is not built strong is flattened.</li>
 *   <li><b>Overhangs.</b> A block over open air holds only as far out from the nearest supported block as its
 *   material spans, less in strong shaking.</li>
 *   <li><b>Slender towers.</b> A column standing many times higher than what is round it sways over and falls
 *   sideways in strong shaking.</li>
 * </ul>
 */
public final class Structural {

    private Structural() {}

    /** The least intensity at which anything is read at all. */
    static final double ONSET = 5.0;
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
     * The intensity a wall of this is shaken down at, whatever it carries. Rubble goes before dressed stone although
     * it is the harder block: it is loose stones in mortar.
     */
    static double standsTo(BlockState s) {
        if (s.getBlock() instanceof FallingBlock) return 5.0;
        if (strong(s) || s.getBlock().defaultDestroyTime() < 0) return 99.0;
        if (s.getSoundType() == SoundType.GLASS || s.is(BlockTags.ICE) || s.is(BlockTags.WOOL) || s.is(BlockTags.LEAVES)
                || s.is(Blocks.HAY_BLOCK) || s.is(BlockTags.DIRT)) return 6.0;
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.getSoundType() == SoundType.WOOD
                || s.is(Blocks.MUD_BRICKS) || s.is(Blocks.PACKED_MUD)) return 7.2;
        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (name.contains("cobble")) return 7.6;
        if (s.getBlock().defaultDestroyTime() >= 3.0f) return 8.8;
        return 8.2;
    }

    /** How much sooner a tall building is shaken down, per block over six. */
    private static final double TALL = 0.02;

    /**
     * Reads the buildings in one chunk shaken at {@code intensity}; what fails is brought down at a moment of the
     * shaking by {@link Collapse}.
     */
    static void chunk(ServerLevel level, LevelChunk chunk, double intensity, LongSet placed, List<BoundingBox> pieces,
                      long from, int spread) {
        if (intensity < ONSET || !GeyserConfig.SHAKING_LOOSENS_BUILDS.get()) return;
        ChunkPos cp = chunk.getPos();
        double left = left(intensity);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LongSet failing = new LongOpenHashSet();
        // The columns with anything built in them.
        LongSet columns = new LongOpenHashSet();
        for (long p : placed) columns.add(BlockPos.asLong(BlockPos.getX(p), 0, BlockPos.getZ(p)));
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
        // Load: the lowest block that cannot carry what is over it brings itself and all of that down.
        for (int y = ground + 1; y <= top; y++) {
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
            double at = stands[n / 2] - TALL * Math.max(0, top - ground - 6);
            double chance = Math.max(0.0, Math.min(1.0, 0.5 + (intensity - at) / 0.8));
            if (chance > 0 && level.random.nextDouble() < chance) {
                bringDown(level, x, lowest, top, z, from, spread, 0, 0, failing, m, placed, pieces);
                SHAKEN.increment();
                return;
            }
        }
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
                    if (!level.isLoaded(m.set(nx, ground, nz))) continue;
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
                if (seen.contains(k) || !level.isLoaded(m.set(nx, y, nz))) continue;
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
