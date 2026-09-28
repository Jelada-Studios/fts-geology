package com.jeladastudios.ftsgeology.volcano;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.quake.Collapse;
import com.jeladastudios.ftsgeology.quake.PlayerBuilt;
import com.jeladastudios.ftsgeology.quake.ShakingDamage;
import com.jeladastudios.ftsgeology.quake.Structural;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Ash piling up on a roof until the roof gives way, which is how most of the people Pinatubo killed in 1991 died: the
 * typhoon that came with the eruption soaked the ash, and the roofs came down under it. A roof carries so many layers
 * of ash by what it is made of -- glass and wool a couple, wood a few, stone most of a block -- one fewer in the rain,
 * since wet ash weighs twice as much; obsidian and metal hold whatever falls on them. Only a roof: a built block with a
 * room under it. When one gives way, the stretch of roof round it that carries ash comes down into the room with its
 * ash on top of it.
 *
 * <p>Ash settles only where snow would, on a flat top, so a pitched roof of stairs sheds it -- which is what anyone
 * who builds near a volcano is told to do.</p>
 */
public final class AshLoad {

    private AshLoad() {}

    /** How much of a roof round the first cell to give way comes down with it, at most. */
    private static final int SPAN = 16;
    /** How far across from the first cell the roof that comes down reaches. */
    private static final int REACH = 3;
    /** How far under a roof its room's floor may lie. */
    private static final int ROOM = 8;

    private static int roofs, blocks;

    /**
     * Ash at {@code ash} is now {@code layers} deep. If what it lies on is a roof that cannot carry that much, the roof
     * comes down.
     */
    public static void layered(ServerLevel level, BlockPos ash, int layers) {
        if (!GeyserConfig.ASH_LOAD_COLLAPSES_ROOFS.get() || layers < 1) return;
        // A roof coming down updates its neighbours; at the edge of the loaded ground that would load the next chunk.
        if (!PyroclasticFlow.ticking(level, ash.getX(), ash.getZ())) return;
        BlockPos roof = ash.below();
        BlockState r = level.getBlockState(roof);
        if (r.isAir() || !r.getFluidState().isEmpty()) return;
        boolean wet = level.isRainingAt(ash.above());
        int holds = holds(r, wet);
        if (layers < holds || !overRoom(level, roof)) return;
        LongSet placed = PlayerBuilt.inChunk(level, roof.getX() >> 4, roof.getZ() >> 4);
        List<BoundingBox> pieces = ShakingDamage.structureBoxes(level, level.getChunkAt(roof), level.getMinBuildHeight());
        if (!Collapse.built(level, r, roof, placed, pieces)) return;
        giveWay(level, roof, layers, wet, placed, pieces);
    }

    /** How many layers of ash a roof of this carries before it gives way. */
    static int holds(BlockState s, boolean wet) {
        double st = Structural.strength(s);
        if (st >= 800) return Integer.MAX_VALUE;
        int t = st <= 6 ? 2 : st <= 48 ? 4 : st <= 120 ? 6 : 8;
        return Math.max(1, wet ? t - 1 : t);
    }

    /** Whether there is a room under a roof: open air under it and a floor further down. */
    private static boolean overRoom(ServerLevel level, BlockPos roof) {
        BlockPos.MutableBlockPos m = roof.mutable();
        m.move(Direction.DOWN);
        if (!level.getBlockState(m).isAir()) return false;
        for (int d = 2; d <= ROOM; d++) {
            m.move(Direction.DOWN);
            BlockState s = level.getBlockState(m);
            if (!s.isAir()) return true;
        }
        return false;
    }

    /**
     * Brings down the roof round a cell that gave way: the cells of it joined across the roof that carry ash and have
     * the room under them, as far as {@link #REACH} from the first and {@link #SPAN} in all, each with its ash.
     */
    private static void giveWay(ServerLevel level, BlockPos first, int layers, boolean wet, LongSet placed,
                                List<BoundingBox> pieces) {
        List<BlockPos> down = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        queue.add(first);
        seen.add(first.asLong());
        while (!queue.isEmpty() && down.size() < SPAN) {
            BlockPos p = queue.poll();
            down.add(p);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                if (!seen.add(q.asLong())) continue;
                if (Math.abs(q.getX() - first.getX()) > REACH || Math.abs(q.getZ() - first.getZ()) > REACH) continue;
                if ((q.getX() >> 4) != (first.getX() >> 4) || (q.getZ() >> 4) != (first.getZ() >> 4)) continue;
                BlockState s = level.getBlockState(q);
                if (s.isAir() || !level.getBlockState(q.above()).is(ModBlocks.VOLCANIC_ASH.get())) continue;
                if (!overRoom(level, q) || !Collapse.built(level, s, q, placed, pieces)) continue;
                queue.add(q);
            }
        }
        for (BlockPos p : down) {
            BlockState ash = level.getBlockState(p.above());
            if (ash.is(ModBlocks.VOLCANIC_ASH.get())) fall(level, p.above(), ash);
            BlockState roof = level.getBlockState(p);
            if (roof.hasBlockEntity() || !roof.isCollisionShapeFullBlock(level, p)) level.destroyBlock(p, true);
            else fall(level, p, roof);
            blocks++;
        }
        roofs++;
        level.playSound(null, first, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 2.0f, 0.5f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.VOLCANIC_ASH.get().defaultBlockState()),
                first.getX() + 0.5, first.getY(), first.getZ() + 0.5, 30, 1.5, 0.4, 1.5, 0.05);
        com.jeladastudios.ftsgeology.util.Diagnostics.info("Ash brought down a roof at {}: {} blocks under {} layers{}",
                first, down.size(), layers, wet ? ", wet" : "");
    }

    private static void fall(ServerLevel level, BlockPos p, BlockState s) {
        FallingBlockEntity f = FallingBlockEntity.fall(level, p, s);
        f.setDeltaMovement(new Vec3(0, -0.05, 0));
        f.setHurtsEntities(1.5f, 10);
        f.dropItem = true;
        f.hurtMarked = true;
    }

    public static String summary() {
        return String.format(java.util.Locale.ROOT, "ash load: %d roofs down, %d blocks", roofs, blocks);
    }
}
