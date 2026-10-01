package com.jeladastudios.ftsgeology.gas.world;

import com.jeladastudios.ftsgeology.gas.Gas;
import com.jeladastudios.ftsgeology.gas.GasConfig;
import com.jeladastudios.ftsgeology.gas.GasMix;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The gas the ground itself holds, put in the first time a chunk is loaded: firedamp, the methane coal gives off,
 * gathered under the roof of a cave its seam runs beside. Not by chance per chunk but where the coal is -- the geology
 * placed it -- and more of it and richer the deeper the seam, as in a coalfield, where shallow seams have breathed theirs
 * out and deep ones are gassy. A chunk saved before the gases came (an old world) is left as it was (see
 * {@code CommonEvents#onChunkDataLoad}).
 *
 * <p>The volcanoes' gas, the swamps', the springs' and the oil fields' come from where they are, while the game runs.</p>
 */
public final class GasWorldGen {

    private GasWorldGen() {}

    /** Firedamp only below this height, and this far under the ground over it. */
    private static final int FIREDAMP_TOP = 40, COVER = 10;
    /** How far from a coal block a cave's air takes its gas, and how many coal blocks are tried. */
    private static final int REACH = 4, TRIES = 24;

    static void seed(GasManager mgr, GasChunkData data) {
        data.seeded = true;
        data.markDirty();
        if (mgr.level.dimension() != Level.OVERWORLD) return;
        LevelChunk chunk = data.chunk;
        RandomSource r = RandomSource.create(mgr.level.getSeed() ^ (chunk.getPos().toLong() * 0x9E3779B97F4A7C15L) ^ 0x6A09E667L);
        if (r.nextDouble() < GasConfig.FIREDAMP_CHANCE.get()) firedamp(mgr, data, r);
    }

    /** Cave air beside coal, deep enough: a pocket of firedamp, layered under its roof. */
    private static void firedamp(GasManager mgr, GasChunkData data, RandomSource r) {
        LevelChunk chunk = data.chunk;
        LevelChunkSection[] sections = chunk.getSections();
        LongArrayList coal = new LongArrayList();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            if (y0 > FIREDAMP_TOP || sections[i].hasOnlyAir()) continue;
            if (!sections[i].getStates().maybeHas(s -> s.is(BlockTags.COAL_ORES))) continue;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (sections[i].getBlockState(lx, ly, lz).is(BlockTags.COAL_ORES)) coal.add(BlockPos.asLong(baseX + lx, y0 + ly, baseZ + lz));
                    }
                }
            }
        }
        if (coal.isEmpty()) return;
        for (int t = 0; t < TRIES; t++) {
            long c = coal.getLong(r.nextInt(coal.size()));
            int cx = BlockPos.getX(c), cy = BlockPos.getY(c), cz = BlockPos.getZ(c);
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, cx & 15, cz & 15);
            if (cy > surface - COVER) continue;
            BlockPos air = null;
            for (int k = 0; k < 12 && air == null; k++) {
                p.set(cx + r.nextInt(2 * REACH + 1) - REACH, cy + r.nextInt(2 * REACH + 1) - REACH, cz + r.nextInt(2 * REACH + 1) - REACH);
                if ((p.getX() >> 4) != chunk.getPos().x || (p.getZ() >> 4) != chunk.getPos().z || mgr.level.isOutsideBuildHeight(p)) continue;
                if (chunk.getBlockState(p).isAir()) air = p.immutable();
            }
            if (air == null) continue;
            // Deeper seams hold more: from a few per cent near the top to a rich roof layer far down.
            double depth = Math.max(0.0, Math.min(1.0, (FIREDAMP_TOP - air.getY()) / 104.0));
            if (fill(mgr, data, air, (0.04 + 0.30 * depth) * (0.6 + 0.4 * r.nextDouble()), r)) return;
        }
    }

    /** Floods the cave from {@code start} inside the chunk, up to a few hundred cells, methane richest at the roof. */
    private static boolean fill(GasManager mgr, GasChunkData data, BlockPos start, double base, RandomSource r) {
        LevelChunk chunk = data.chunk;
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        int maxCells = 60 + r.nextInt(300);
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongArrayList cells = new LongArrayList();
        queue.enqueue(start.asLong());
        seen.add(start.asLong());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int ymin = Integer.MAX_VALUE, ymax = Integer.MIN_VALUE;
        while (!queue.isEmpty() && cells.size() < maxCells) {
            long l = queue.dequeueLong();
            cells.add(l);
            int y = BlockPos.getY(l);
            ymin = Math.min(ymin, y);
            ymax = Math.max(ymax, y);
            for (Direction d : Direction.values()) {
                p.set(BlockPos.getX(l) + d.getStepX(), y + d.getStepY(), BlockPos.getZ(l) + d.getStepZ());
                if (mgr.level.isOutsideBuildHeight(p)) continue;
                long pl = p.asLong();
                if (seen.contains(pl)) continue;
                seen.add(pl);
                // The cave reaches the chunk's edge: it runs on, open, and long since aired (the next chunk is not
                // read, it may not be there yet). Firedamp next to lava would have burnt long ago. Neither holds a pocket.
                if ((p.getX() >> 4) != cx || (p.getZ() >> 4) != cz) return false;
                BlockState ns = chunk.getBlockState(p);
                if (ns.getFluidState().is(FluidTags.LAVA)) return false;
                if (ns.isAir()) queue.enqueue(pl);
            }
        }
        // Only a closed hollow keeps its gas: one still running on when the pocket was full is a gallery that airs.
        if (!queue.isEmpty()) return false;
        double span = Math.max(1, ymax - ymin + 1);
        for (int i = 0; i < cells.size(); i++) {
            long l = cells.getLong(i);
            double t = (BlockPos.getY(l) - ymin + 0.5) / span; // 0 = floor, 1 = roof
            double f = Math.min(0.95, base * (0.3 + 0.7 * t));
            GasMix mix = GasMix.air(GasManager.N0 * (1 - f));
            mix.add(Gas.METHANE, GasManager.N0 * f);
            int key = GasChunkData.key(BlockPos.getX(l), BlockPos.getY(l), BlockPos.getZ(l));
            data.cells.put(key, mix);
            data.awake.add(key);
        }
        pockets++;
        pocketCells += cells.size();
        return true;
    }

    static int pockets, pocketCells;
}
