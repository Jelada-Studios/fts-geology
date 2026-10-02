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
        if (r.nextDouble() < GasConfig.KARST_CO2_CHANCE.get()) karstAir(mgr, data, r);
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
            if (fill(mgr, data, air, (0.04 + 0.30 * depth) * (0.6 + 0.4 * r.nextDouble()), Gas.METHANE, false, r)) return;
        }
    }

    /** Floods the cave from {@code start} inside the chunk, up to a few hundred cells: a light gas richest at the roof, a heavy one on the floor. */
    private static boolean fill(GasManager mgr, GasChunkData data, BlockPos start, double base, Gas gas, boolean heavy, RandomSource r) {
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
            if (heavy) t = 1.0 - t;
            double f = Math.min(0.95, base * (0.3 + 0.7 * t));
            GasMix mix = GasMix.air(GasManager.N0 * (1 - f));
            mix.add(gas, GasManager.N0 * f);
            int key = GasChunkData.key(BlockPos.getX(l), BlockPos.getY(l), BlockPos.getZ(l));
            data.cells.put(key, mix);
            data.awake.add(key);
        }
        if (gas == Gas.METHANE) {
            pockets++;
            pocketCells += cells.size();
        }
        return true;
    }

    /**
     * Cave air in limestone country: the water that dissolves the rock and the soil over it bring carbon dioxide down
     * into the caves, and a closed hollow walled in calcite, marble or dripstone holds a few per cent of it, sunk to its
     * floor -- enough to make breathing laboured, as in many real caves. New chunks only, as the firedamp.
     */
    private static void karstAir(GasManager mgr, GasChunkData data, RandomSource r) {
        LevelChunk chunk = data.chunk;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ(), bottom = mgr.level.getMinBuildHeight() + 8;
        for (int t = 0; t < 48; t++) {
            int x = baseX + 1 + r.nextInt(14), z = baseZ + 1 + r.nextInt(14);
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) - 12;
            if (surface <= bottom) continue;
            p.set(x, bottom + r.nextInt(surface - bottom), z);
            if (!chunk.getBlockState(p).isAir()) continue;
            boolean karst = false;
            for (Direction d : Direction.values()) {
                BlockState s = chunk.getBlockState(p.move(d));
                p.move(d.getOpposite());
                if (s.is(net.minecraft.world.level.block.Blocks.CALCITE) || s.is(net.minecraft.world.level.block.Blocks.DRIPSTONE_BLOCK)
                        || s.is(com.jeladastudios.ftsgeology.registry.ModBlocks.MARBLE.get())) {
                    karst = true;
                    break;
                }
            }
            if (!karst) continue;
            if (floorPool(mgr, data, p.immutable(), 0.02 + 0.04 * r.nextDouble(), r)) {
                karstPockets++;
                return;
            }
        }
    }

    static int karstPockets;

    /**
     * Carbon dioxide lying on a cave's floor: from {@code start}, the cave's air within this chunk taken in, the cells
     * on its floor and the one over them given the gas -- a few per cent on the floor, half that a block up. A big cave
     * runs on past the chunk; its floor holds the gas all the same, being the heaviest air in it.
     */
    private static boolean floorPool(GasManager mgr, GasChunkData data, BlockPos start, double base, RandomSource r) {
        LevelChunk chunk = data.chunk;
        int cx = chunk.getPos().x, cz = chunk.getPos().z, most = 40 + r.nextInt(80);
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start.asLong());
        seen.add(start.asLong());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int laid = 0, looked = 0;
        while (!queue.isEmpty() && laid < most && looked++ < 500) {
            long l = queue.dequeueLong();
            int x = BlockPos.getX(l), y = BlockPos.getY(l), z = BlockPos.getZ(l);
            // On the floor, or a block over it.
            int over = chunk.getBlockState(p.set(x, y - 1, z)).isAir() ? (chunk.getBlockState(p.set(x, y - 2, z)).isAir() ? 2 : 1) : 0;
            if (over < 2 && !mgr.level.isOutsideBuildHeight(y - 1)) {
                double f = base * (over == 0 ? 1.0 : 0.5);
                GasMix mix = GasMix.air(GasManager.N0 * (1 - f));
                mix.add(Gas.CARBON_DIOXIDE, GasManager.N0 * f);
                int key = GasChunkData.key(x, y, z);
                data.cells.put(key, mix);
                data.awake.add(key);
                laid++;
            }
            for (Direction d : Direction.values()) {
                p.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                if ((p.getX() >> 4) != cx || (p.getZ() >> 4) != cz || mgr.level.isOutsideBuildHeight(p)) continue;
                // The deep part of the cave: not up its passages towards daylight.
                if (p.getY() > start.getY() + 6) continue;
                long pl = p.asLong();
                if (!seen.add(pl)) continue;
                BlockState ns = chunk.getBlockState(p);
                if (ns.getFluidState().is(FluidTags.LAVA)) return laid > 0;
                if (ns.isAir()) queue.enqueue(pl);
            }
        }
        return laid > 0;
    }

    static int pockets, pocketCells;
}
