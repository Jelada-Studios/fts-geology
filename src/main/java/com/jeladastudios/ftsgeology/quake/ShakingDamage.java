package com.jeladastudios.ftsgeology.quake;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.compat.DynamicTreesFelling;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.eruption.EruptionHandler;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the shaking does to buildings. The rupture moves the ground along the fault; the waves go out much further and
 * shake what stands on it. Here a block of a building with a face in the open may be shaken loose and fall, the weaker
 * its material the likelier, the stronger the shaking where it stands the likelier.
 *
 * <p>Buildings only: what players placed ({@link PlayerBuilt}), blocks of worked material, and the blocks of the
 * structures the world made -- villages, temples, outposts -- that are not ground. Never the ground itself, never a
 * tree, never a block with contents. Only from a little under the ground up, which is where people build and where
 * the shaking is felt; a mine is left alone.</p>
 *
 * <p>The chunks within reach of the rupture are gone through a few per tick while the ground shakes, and what is
 * shaken loose falls at a random moment of the shaking, so a wall sheds its blocks over tens of seconds.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class ShakingDamage {

    private ShakingDamage() {}

    /**
     * The chance a block of the weakest material with a face in the open is shaken loose at magnitude 5 on the fault.
     * A sand house of two hundred open faces loses about four blocks.
     */
    private static final double BASE = 0.02;
    /** How much likelier per unit of shaking intensity, in powers of ten: half a decade, about three times. */
    private static final double PER_INTENSITY = 0.5;
    /** The most that is ever shaken off: even a great earthquake leaves half of a wall standing. */
    private static final double CAP = 0.5;
    /** How far from the fault, in blocks, the shaking has lost a third of a unit of intensity. */
    private static final double FALLOFF = 30.0;
    /**
     * How far under the lowest ground of a chunk buildings are shaken: a cellar is, a mine is not. Read off the chunk,
     * not the sea: a flat world says its sea is at 63 with its ground at -61.
     */
    private static final int BELOW_GROUND = 16;
    /** The most blocks one quake shakes off. */
    private static final int MOST = 4000;
    /** How far up a log is followed to find the crown that makes it a tree's. */
    private static final int TRUNK = 24;
    /** The ways a loosened block can come away, sides before the underside. */
    private static final net.minecraft.core.Direction[] AWAY = {net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST,
            net.minecraft.core.Direction.DOWN};

    /**
     * How readily an ornament comes down: a lantern, a torch, a flower pot, a candle, a painting, an item frame. Hung or
     * stood rather than built in, and the first things to fall in a real earthquake whatever they are made of.
     */
    private static final double ORNAMENT = 0.7;

    /**
     * How readily a tree of Dynamic Trees comes down, as a weakness: about one in twenty near the fault of a magnitude 6,
     * one in five at 7, half at 8. How far down its crown a trunk is looked for.
     */
    private static final double TREE = 0.8;
    private static final int CROWN = 40;

    private static final LongAdder KNOCKED = new LongAdder(), SHATTERED = new LongAdder(), CHUNKS = new LongAdder(),
            OF_STRUCTURES = new LongAdder(), ORNAMENTS = new LongAdder(), TREES = new LongAdder();

    /** A block shaken loose, to come off at its moment if it is still what was there. */
    private record Loose(ResourceKey<Level> dimension, BlockPos pos, BlockState state, long due) {}

    /** A painting or an item frame shaken off its wall, to come down at its moment. */
    private record LooseHanging(ResourceKey<Level> dimension, java.util.UUID id, long due) {}

    /** One quake's shaking: the chunks still to go through. */
    private static final class Job {
        final ResourceKey<Level> dimension;
        final List<QuakePlanner.TracePoint> trace;
        final double magnitude;
        final int spread;
        final Deque<ChunkPos> chunks;
        int loosened;

        Job(ResourceKey<Level> dimension, List<QuakePlanner.TracePoint> trace, double magnitude, int spread,
            Deque<ChunkPos> chunks) {
            this.dimension = dimension;
            this.trace = trace;
            this.magnitude = magnitude;
            this.spread = spread;
            this.chunks = chunks;
        }
    }

    private static final Deque<Job> JOBS = new ArrayDeque<>();
    private static final PriorityQueue<Loose> DUE = new PriorityQueue<>(Comparator.comparingLong(Loose::due));
    private static final PriorityQueue<LooseHanging> HANGING =
            new PriorityQueue<>(Comparator.comparingLong(LooseHanging::due));

    /** How far from the rupture buildings are shaken: as far as a player feels it. */
    public static double reach(double magnitude) {
        return 40 + magnitude * 14;
    }

    /**
     * The shaking starts: every loaded chunk within reach of the rupture is queued. Server thread.
     *
     * @param ticks how long the ground shakes, over which what comes loose comes off
     */
    public static void start(ServerLevel level, List<QuakePlanner.TracePoint> trace, double magnitude, int ticks) {
        if (!(builds() || trees()) || GeyserConfig.SHAKING_DAMAGE.get() <= 0 || trace.isEmpty()) return;
        double reach = reach(magnitude);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (QuakePlanner.TracePoint t : trace) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
        }
        Deque<ChunkPos> chunks = new ArrayDeque<>();
        int r = (int) Math.ceil(reach);
        for (int cx = (minX - r) >> 4; cx <= (maxX + r) >> 4; cx++) {
            for (int cz = (minZ - r) >> 4; cz <= (maxZ + r) >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
                if (distance(trace, cx * 16 + 8, cz * 16 + 8) <= reach + 12) chunks.add(new ChunkPos(cx, cz));
            }
        }
        if (!chunks.isEmpty()) JOBS.add(new Job(level.dimension(), trace, magnitude, Math.max(20, ticks), chunks));
    }

    /** Goes through queued chunks and lets due blocks come off, for at most {@code nanos}. Server thread. */
    public static void drain(MinecraftServer server, long nanos) {
        if (JOBS.isEmpty() && DUE.isEmpty() && HANGING.isEmpty()) return;
        long deadline = System.nanoTime() + nanos;
        long now = server.getTickCount();
        while (!DUE.isEmpty() && DUE.peek().due() <= now && System.nanoTime() < deadline) {
            Loose l = DUE.poll();
            ServerLevel level = server.getLevel(l.dimension());
            if (level == null || !level.isLoaded(l.pos())) continue;
            if (level.getBlockState(l.pos()) != l.state()) continue;   // changed meanwhile: left as it now is
            fall(level, l.pos(), l.state());
        }
        while (!HANGING.isEmpty() && HANGING.peek().due() <= now && System.nanoTime() < deadline) {
            LooseHanging l = HANGING.poll();
            ServerLevel level = server.getLevel(l.dimension());
            if (level == null) continue;
            if (level.getEntity(l.id()) instanceof HangingEntity hung && hung.isAlive()) {
                hung.dropItem(null);                 // with its own sound, and what a frame held
                hung.discard();
                ORNAMENTS.increment();
            }
        }
        while (!JOBS.isEmpty() && System.nanoTime() < deadline) {
            Job job = JOBS.peek();
            ChunkPos cp = job.chunks.poll();
            if (cp == null || job.loosened >= MOST) {
                JOBS.poll();
                continue;
            }
            ServerLevel level = server.getLevel(job.dimension);
            if (level != null) shake(level, job, cp, now);
        }
    }

    private static boolean builds() {
        return GeyserConfig.SHAKING_LOOSENS_BUILDS.get();
    }

    private static boolean trees() {
        return GeyserConfig.SHAKING_FELLS_TREES.get() && DynamicTreesFelling.present();
    }

    private static void fall(ServerLevel level, BlockPos pos, BlockState state) {
        if (DynamicTreesFelling.isBranch(state)) {
            // Over the way the ground threw it: any side.
            Direction side = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
            if (DynamicTreesFelling.fell(level, pos, side)) TREES.increment();
            return;
        }
        if (ornament(state)) {
            level.destroyBlock(pos, true);           // comes down as what it drops: a lantern, a pot and its flower
            ORNAMENTS.increment();
            return;
        }
        if (state.getSoundType() == SoundType.GLASS) {
            level.destroyBlock(pos, false);          // glass breaks where it stands, with its own sound
            SHATTERED.increment();
            return;
        }
        // Shoved out through the open side and falling from there. Let fall where it stood, or pushed, a block of a
        // wall touched the one under it at once and was set straight back in its place.
        net.minecraft.core.Direction side = openSide(level, level.getChunkAt(pos), pos);
        if (side == null) return;                    // closed in since it was shaken loose
        level.levelEvent(2001, pos, Block.getId(state));   // the crack and dust of it coming away
        level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        FallingBlockEntity.fall(level, pos.relative(side), state);
        KNOCKED.increment();
    }

    private static void shake(ServerLevel level, Job job, ChunkPos cp, long now) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return;
        CHUNKS.increment();
        double d = distance(job.trace, cp.getMiddleBlockX(), cp.getMiddleBlockZ());
        double intensity = job.magnitude - 2.0 * Math.log10(1.0 + d / FALLOFF);
        double shaking = BASE * GeyserConfig.SHAKING_DAMAGE.get() * Math.pow(10.0, PER_INTENSITY * (intensity - 5.0));
        if (shaking < 1.0e-4) return;

        int floor = Integer.MAX_VALUE;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) floor = Math.min(floor, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz));
        }
        floor -= BELOW_GROUND;
        if (trees()) trees(level, job, chunk, cp, shaking, now);
        if (!builds()) return;
        LongSet placed = PlayerBuilt.inChunk(level, cp.x, cp.z);
        List<BoundingBox> built = structureBoxes(level, chunk, floor);
        boolean[] placedIn = new boolean[chunk.getSectionsCount()];
        for (long p : placed) {
            int i = chunk.getSectionIndex(BlockPos.getY(p));
            if (i >= 0 && i < placedIn.length) placedIn[i] = true;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            if (y0 + 15 < floor || section.hasOnlyAir()) continue;
            boolean inBox = false;
            for (BoundingBox b : built) {
                if (b.maxY() >= y0 && b.minY() <= y0 + 15) {
                    inBox = true;
                    break;
                }
            }
            if (!placedIn[i] && !inBox && !section.getStates().maybeHas(ShakingDamage::fitting)) continue;
            for (int ly = 0; ly < 16; ly++) {
                int y = y0 + ly;
                if (y < floor) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState s = section.getBlockState(lx, ly, lz);
                        if (s.isAir()) continue;
                        if (ornament(s)) {
                            // Hung or stood, not built in: no open face needed, and it comes down as what it drops.
                            if (level.random.nextDouble() >= Math.min(CAP, ORNAMENT * shaking)) continue;
                            int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                            m.set(x, y, z);
                            if (!(placed.contains(m.asLong()) || EruptionHandler.isPlayerPlaced(s)
                                    || (inBox && inside(built, x, y, z)))) continue;
                            if (job.loosened++ >= MOST) return;
                            DUE.add(new Loose(level.dimension(), m.immutable(), s, now + level.random.nextInt(job.spread)));
                            continue;
                        }
                        double weak = weakness(s);
                        if (weak <= 0) continue;
                        // The roll first: it is cheap, and only the few blocks that come up are looked at closely.
                        if (level.random.nextDouble() >= Math.min(CAP, weak * shaking)) continue;
                        int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                        m.set(x, y, z);
                        if (!s.getFluidState().isEmpty() || s.hasBlockEntity() || QuakePlanner.machinery(s)) continue;
                        if (!s.isCollisionShapeFullBlock(level, m)) continue;
                        boolean byHand = placed.contains(m.asLong()) || worked(s);
                        boolean made = !byHand && inBox && inside(built, x, y, z) && !ground(s);
                        if (!(byHand || made) || openSide(level, chunk, m) == null) continue;
                        if (s.is(BlockTags.LOGS) && trunk(level, m)) continue;
                        if (made) OF_STRUCTURES.increment();
                        if (job.loosened++ >= MOST) return;
                        DUE.add(new Loose(level.dimension(), m.immutable(), s, now + level.random.nextInt(job.spread)));
                    }
                }
            }
        }
        // Paintings and item frames: only players and structures hang them, so every one is a building's.
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(cp.getMinBlockX(), floor,
                cp.getMinBlockZ(), cp.getMaxBlockX() + 1, level.getMaxBuildHeight(), cp.getMaxBlockZ() + 1);
        for (HangingEntity hung : level.getEntitiesOfClass(HangingEntity.class, area,
                e -> e instanceof net.minecraft.world.entity.decoration.Painting
                        || e instanceof net.minecraft.world.entity.decoration.ItemFrame)) {
            if (level.random.nextDouble() >= Math.min(CAP, ORNAMENT * shaking)) continue;
            HANGING.add(new LooseHanging(level.dimension(), hung.getUUID(), now + level.random.nextInt(job.spread)));
        }
    }

    /**
     * Rolls for each Dynamic Trees tree in a chunk. A tree is found from its crown down: the first branch under the top
     * of a column, followed down the branch blocks to the rooted soil, is its trunk; a side branch ends in the air.
     */
    private static void trees(ServerLevel level, Job job, LevelChunk chunk, ChunkPos cp, double shaking, long now) {
        double chance = Math.min(CAP, TREE * shaking);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
                int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                int y = top;
                BlockState s = null;
                for (; y > top - CROWN; y--) {
                    s = chunk.getBlockState(m.set(x, y, z));
                    if (DynamicTreesFelling.isBranch(s) || !(s.isAir() || s.is(BlockTags.LEAVES))) break;
                }
                if (s == null || !DynamicTreesFelling.isBranch(s)) continue;
                BlockState under = chunk.getBlockState(m.set(x, y - 1, z));
                while (DynamicTreesFelling.isBranch(under) && y > top - CROWN) {
                    s = under;
                    under = chunk.getBlockState(m.set(x, --y - 1, z));
                }
                if (!DynamicTreesFelling.isRooty(under)) continue;
                if (level.random.nextDouble() >= chance) continue;
                DUE.add(new Loose(level.dimension(), new BlockPos(x, y, z), s, now + level.random.nextInt(job.spread)));
            }
        }
    }

    /** How far over a player a ceiling is looked for, and how often, one in so many times, it creaks. */
    private static final int CEILING = 8, CREAK = 8;

    /**
     * Dust sifting down from the ceiling over a player indoors while the ground shakes, of what the ceiling is made
     * of, and now and then the creak of it. Called a few times a second for each player the quake shakes.
     */
    public static void ceilingDust(ServerLevel level, net.minecraft.server.level.ServerPlayer player, double falloff) {
        if (!GeyserConfig.SHAKING_LOOSENS_BUILDS.get() || level.getGameTime() % 3L != 0L) return;
        BlockPos head = BlockPos.containing(player.getEyePosition());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int ceiling = Integer.MIN_VALUE;
        for (int dy = 1; dy <= CEILING; dy++) {
            if (!level.getBlockState(m.set(head.getX(), head.getY() + dy, head.getZ())).isAir()) {
                ceiling = head.getY() + dy;
                break;
            }
        }
        if (ceiling == Integer.MIN_VALUE) return;               // under the open sky
        int puffs = 1 + (int) Math.round(falloff * 3);
        for (int i = 0; i < puffs; i++) {
            int x = head.getX() + level.random.nextInt(7) - 3, z = head.getZ() + level.random.nextInt(7) - 3;
            if (!level.hasChunkAt(m.set(x, ceiling, z))) continue;
            BlockState s = level.getBlockState(m);
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            level.sendParticles(player, new net.minecraft.core.particles.BlockParticleOption(
                            net.minecraft.core.particles.ParticleTypes.FALLING_DUST, s), false,
                    x + 0.5, ceiling - 0.05, z + 0.5, 2, 0.35, 0.0, 0.35, 0.0);
        }
        if (level.random.nextInt(CREAK) == 0) {
            level.playSound(null, head.getX() + 0.5, ceiling, head.getZ() + 0.5,
                    net.minecraft.sounds.SoundEvents.GRAVEL_FALL, net.minecraft.sounds.SoundSource.BLOCKS,
                    0.35F, 0.55F + level.random.nextFloat() * 0.2F);
        }
    }

    /**
     * What a building has hung or stood about it rather than built into it: lanterns, torches, flower pots, candles.
     * Never a redstone torch.
     */
    private static boolean ornament(BlockState s) {
        Block b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.LanternBlock
                || (b instanceof net.minecraft.world.level.block.TorchBlock
                        && !(b instanceof net.minecraft.world.level.block.RedstoneTorchBlock))   // a circuit is not an ornament
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock
                || b instanceof net.minecraft.world.level.block.CandleBlock;
    }

    /** Anything in a section that can make it worth going through: a worked block or an ornament. */
    private static boolean fitting(BlockState s) {
        return worked(s) || (ornament(s) && EruptionHandler.isPlayerPlaced(s));
    }

    /**
     * How readily a block is shaken off, 0 to 1: loose material that falls anyway is the weakest, glass next, and
     * then by how hard it is to break -- wool, planks, bricks, stone -- with anything as hard as obsidian or a metal
     * block holding outright.
     */
    static double weakness(BlockState s) {
        float hard = s.getBlock().defaultDestroyTime();
        if (hard < 0) return 0;                                  // bedrock and the like
        if (s.getBlock() instanceof FallingBlock) return 1.0;    // sand, gravel, concrete powder
        if (s.getSoundType() == SoundType.GLASS) return 0.8;
        return Math.max(0.0, Math.min(0.9, 1.0 - hard / 3.0));
    }

    /** Made of something the ground is not: a build, whatever it stands in. */
    private static boolean worked(BlockState s) {
        return !s.isAir() && weakness(s) > 0 && EruptionHandler.isPlayerPlaced(s);
    }

    /**
     * Whether a log is a tree's: its logs run up into leaves. A sapling a player planted is a placed block, and the
     * trunk grown from it stood where it was; a log pillar of a house has a roof over it, not a crown.
     */
    private static boolean trunk(ServerLevel level, BlockPos log) {
        BlockPos.MutableBlockPos up = log.mutable();
        for (int i = 0; i < TRUNK; i++) {
            BlockState s = level.getBlockState(up.move(net.minecraft.core.Direction.UP));
            if (s.is(BlockTags.LEAVES)) return true;
            if (!s.is(BlockTags.LOGS)) return false;
        }
        return false;
    }

    /** The ground a structure is built on and into, which the shaking leaves to the rupture. */
    private static boolean ground(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND)
                || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVEL)
                || s.is(BlockTags.LEAVES) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)
                || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || TerrainProbe.isVegetation(s);
    }

    /**
     * The side a block can come away through: a side or its underside in the open. A block open only on top has
     * nowhere to go -- the one under it holds it up. Never loads a chunk to find out.
     */
    private static net.minecraft.core.Direction openSide(ServerLevel level, LevelChunk chunk, BlockPos p) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction dir : AWAY) {
            n.setWithOffset(p, dir);
            LevelChunk c = chunk;
            if ((n.getX() >> 4) != chunk.getPos().x || (n.getZ() >> 4) != chunk.getPos().z) {
                c = level.getChunkSource().getChunkNow(n.getX() >> 4, n.getZ() >> 4);
                if (c == null) continue;
            }
            if (n.getY() < level.getMinBuildHeight() || n.getY() >= level.getMaxBuildHeight()) continue;
            if (c.getBlockState(n).isAir()) return dir;
        }
        return null;
    }

    /** The boxes of the pieces of structures in a chunk that reach up to where buildings are shaken. */
    private static List<BoundingBox> structureBoxes(ServerLevel level, LevelChunk chunk, int floor) {
        List<BoundingBox> out = new ArrayList<>();
        if (chunk.getAllReferences().isEmpty()) return out;
        try {
            ChunkPos cp = chunk.getPos();
            for (StructureStart start : level.structureManager().startsForStructure(cp, st -> true)) {
                if (!start.isValid()) continue;
                for (StructurePiece piece : start.getPieces()) {
                    BoundingBox b = piece.getBoundingBox();
                    if (b.maxY() < floor || b.maxX() < cp.getMinBlockX() || b.minX() > cp.getMaxBlockX()
                            || b.maxZ() < cp.getMinBlockZ() || b.minZ() > cp.getMaxBlockZ()) continue;
                    out.add(b);
                }
            }
        } catch (RuntimeException e) {
            // A structure source that cannot answer leaves its buildings standing.
        }
        return out;
    }

    private static boolean inside(List<BoundingBox> boxes, int x, int y, int z) {
        for (BoundingBox b : boxes) if (b.isInside(x, y, z)) return true;
        return false;
    }

    /** Horizontal distance from a column to the nearest traced point of the rupture. */
    private static double distance(List<QuakePlanner.TracePoint> trace, double x, double z) {
        double best = Double.MAX_VALUE;
        for (QuakePlanner.TracePoint t : trace) {
            double dx = t.x() - x, dz = t.z() - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return Math.sqrt(best);
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "shaking: %d chunks gone through, %d blocks shaken off, %d panes broken, %d ornaments down, %d loosened in a structure, %d trees felled",
                CHUNKS.sum(), KNOCKED.sum(), SHATTERED.sum(), ORNAMENTS.sum(), OF_STRUCTURES.sum(), TREES.sum());
    }

    public static void clear() {
        JOBS.clear();
        DUE.clear();
        HANGING.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
