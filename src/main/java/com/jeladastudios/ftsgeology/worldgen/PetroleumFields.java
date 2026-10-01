package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;

/**
 * Oil and gas where a basin holds them.
 *
 * <p>Oil comes from mud rich in the remains of sea life, buried in a sinking basin until it is cooked: a few kilometres
 * down and warm enough it gives oil, deeper and hotter gas. Both are lighter than the water in the rock and creep up
 * through it until something tight stops them -- a shale, a bed of salt or gypsum -- arched over a porous sandstone or
 * limestone: a trap. There they settle by weight, gas at the top, oil under it, the rock's water below, each with a flat
 * surface between them whatever the shape of the arch. The basins are the ones the coal is in (see
 * {@link OreGenesis#basin}): in front of a mountain belt (the Persian Gulf in front of the Zagros, Batman and Raman in
 * front of the Taurus), down a rift (the North Sea, the Gulf of Suez), behind an arc and in the slow sags inside a plate.
 * </p>
 *
 * <p>A field here is a dome: an elongated arch of reservoir sandstone under a few blocks of shale, its top highest at
 * its crest, with the gas cap, the oil and the water in it by height. Oil-soaked sandstone shows where the oil is; the
 * gas cap is in plain sandstone, and broken into it lets its gas out under the pressure of its depth (see
 * {@code GasFields}). Each field hangs off a coarse grid cell, worked out from the cell alone, so every chunk writes its
 * own part of the same dome.</p>
 */
public final class PetroleumFields {

    private PetroleumFields() {}

    /** The grid a field hangs off, and the most a dome reaches from its crest. */
    private static final int CELL = 640, REACH = 130;
    /** Seal over the reservoir, and the depth the crest keeps under the ground at the anchor. */
    private static final int SEAL = 3, COVER = 40;

    /** What a point of a field holds. */
    public enum Zone { NONE, SEAL, GAS, OIL, WATER }

    /**
     * One dome: crest at {@code (x, z, crest)}, the semi-axes {@code a} along {@code (cos, sin)} and {@code b} across,
     * {@code closure} how far the top of the reservoir falls from the crest to the dome's edge, {@code thick} the
     * reservoir's thickness; the gas-oil and oil-water contacts as heights; how sour its gas is; and how rich.
     */
    public record Field(long id, int x, int z, double cos, double sin, double a, double b, int crest, int closure,
                        int thick, int goc, int owc, double sour, double richness) {

        /** 0 at the crest, 1 at the dome's edge, more outside it. */
        public double r2(double px, double pz) {
            double dx = px - x, dz = pz - z;
            double u = (dx * cos + dz * sin) / a, v = (-dx * sin + dz * cos) / b;
            return u * u + v * v;
        }

        /** The top of the reservoir at a column. */
        public int top(double px, double pz) {
            return crest - (int) Math.round(closure * r2(px, pz));
        }

        public Zone zone(int px, int py, int pz) {
            double r2 = r2(px + 0.5, pz + 0.5);
            if (r2 > 1.0) return Zone.NONE;
            int top = top(px + 0.5, pz + 0.5);
            if (py > top && py <= top + SEAL) return Zone.SEAL;
            if (py > top || py <= top - thick) return Zone.NONE;
            return py > goc ? Zone.GAS : py > owc ? Zone.OIL : Zone.WATER;
        }

        /** Depth of the crest under the ground at the anchor, in blocks: the reservoir's pressure comes from it. */
        public int depth(ServerLevel world) {
            return Math.max(10, OreGenesis.anchorGroundAt(world, x, z) - crest);
        }
    }

    /** The field of a grid cell, or null: in a basin, by chance, under enough ground. */
    static Field of(ServerLevel world, long seed, int cellX, int cellZ) {
        long h = hash(seed, cellX, cellZ, 0x0115EEDL);
        if (rand01(hash(h, 1, 0, 0)) >= GeyserConfig.PETROLEUM_CHANCE.get()) return null;
        int x = cellX * CELL + REACH + (int) (rand01(hash(h, 2, 0, 0)) * (CELL - 2 * REACH));
        int z = cellZ * CELL + REACH + (int) (rand01(hash(h, 3, 0, 0)) * (CELL - 2 * REACH));
        if (!OreGenesis.basin(world, x, z)) return null;
        int ground = OreGenesis.anchorGroundAt(world, x, z);
        double angle = rand01(hash(h, 4, 0, 0)) * Math.PI;
        double a = 50 + 70 * rand01(hash(h, 5, 0, 0));
        double b = a * (0.35 + 0.4 * rand01(hash(h, 6, 0, 0)));
        int crest = Math.min(ground - COVER, 30) - (int) (rand01(hash(h, 7, 0, 0)) * 25);
        if (crest < world.getMinBuildHeight() + 30) return null;
        int closure = 8 + (int) (rand01(hash(h, 8, 0, 0)) * 8);
        int thick = 6 + (int) (rand01(hash(h, 9, 0, 0)) * 6);
        // The contacts: a gas cap a few blocks deep, the oil under it, and both above the spill point of the dome.
        int goc = crest - 2 - (int) (rand01(hash(h, 10, 0, 0)) * 4);
        int owc = Math.max(crest - closure + 1, goc - 3 - (int) (rand01(hash(h, 11, 0, 0)) * 6));
        double sour = rand01(hash(h, 12, 0, 0)) < 0.3 ? 0.01 + 0.03 * rand01(hash(h, 13, 0, 0)) : 0.0;
        double richness = 0.6 + 0.9 * rand01(hash(h, 14, 0, 0));
        return new Field(h, x, z, Math.cos(angle), Math.sin(angle), a, b, crest, closure, thick, goc, owc, sour, richness);
    }

    /** The fields whose domes can reach a box round a point. */
    public static List<Field> near(ServerLevel world, int x, int z, int reach) {
        List<Field> out = new ArrayList<>(1);
        if (!GeyserConfig.PETROLEUM.get()) return out;
        long seed = world.getSeed();
        int r = reach + REACH;
        for (int cx = Math.floorDiv(x - r, CELL); cx <= Math.floorDiv(x + r, CELL); cx++) {
            for (int cz = Math.floorDiv(z - r, CELL); cz <= Math.floorDiv(z + r, CELL); cz++) {
                Field f = of(world, seed, cx, cz);
                if (f != null && Math.abs(f.x - x) <= r && Math.abs(f.z - z) <= r) out.add(f);
            }
        }
        return out;
    }

    /** The field whose crest is nearest a point, within a reach, or null. */
    public static Field nearest(ServerLevel world, int x, int z, int reach) {
        Field best = null;
        double bd = Double.MAX_VALUE;
        for (Field f : near(world, x, z, reach)) {
            double d = Math.hypot(f.x() - x, f.z() - z);
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    /** What a block of the world holds, and of which field. */
    public record At(Field field, Zone zone) {}

    public static At at(ServerLevel world, BlockPos p) {
        for (Field f : near(world, p.getX(), p.getZ(), 0)) {
            Zone zone = f.zone(p.getX(), p.getY(), p.getZ());
            if (zone != Zone.NONE) return new At(f, zone);
        }
        return null;
    }

    /** Writes this chunk's part of every dome that reaches it; the number of blocks written. */
    static int generate(WorldGenLevel level, ChunkPos cp) {
        if (!GeyserConfig.PETROLEUM.get()) return 0;
        ServerLevel world = level.getLevel();
        List<Field> fields = near(world, cp.getMiddleBlockX(), cp.getMiddleBlockZ(), 8);
        if (fields.isEmpty()) return 0;
        int placed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Field f : fields) {
            Block seal = seal(f);
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                    if (f.r2(x + 0.5, z + 0.5) > 1.0) continue;
                    int ground = TerrainProbe.groundY(level, x, z);
                    if (ground == Integer.MIN_VALUE) continue;
                    int top = f.top(x + 0.5, z + 0.5);
                    for (int y = top + SEAL; y > top - f.thick(); y--) {
                        if (y > ground - 8 || y <= level.getMinBuildHeight() + 2) continue;
                        Zone zone = f.zone(x, y, z);
                        Block block = switch (zone) {
                            case SEAL -> seal;
                            case OIL -> ModBlocks.OIL_SANDSTONE.get();
                            case GAS, WATER -> Blocks.SANDSTONE;
                            default -> null;
                        };
                        if (block == null) continue;
                        m.set(x, y, z);
                        BlockState s = level.getBlockState(m);
                        if (s.is(block) || s.hasBlockEntity() || !OreGenesis.isHostRock(s)) continue;
                        level.setBlock(m, TfcCompat.translate(level, m, block.defaultBlockState()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        placed++;
                        if (zone == Zone.SEAL && block != ModBlocks.SHALE.get()) FossilBeds.GYPSUM.increment();
                    }
                }
            }
        }
        return placed;
    }

    /**
     * What seals a field: shale, or in some fields an evaporite -- Jurassic Reborn's gypsum, where the geology lays it
     * (see {@link FossilBeds}).
     */
    private static Block seal(Field f) {
        if (rand01(hash(f.id(), 15, 0, 0)) < 0.3 && FossilBeds.geological()) {
            Block gypsum = FossilBeds.block("gypsum_stone");
            if (gypsum != null) return gypsum;
        }
        return ModBlocks.SHALE.get();
    }

    /**
     * The oil Create Diesel Generators' pumpjack finds under a chunk, in millibuckets: as much of the chunk as lies over a
     * field's oil, its richness, on that mod's own scale (a few million to under ten million, past which it counts a
     * chunk as endless). None outside a field.
     */
    public static int dieselOil(ServerLevel world, ChunkPos cp) {
        double best = 0;
        for (Field f : near(world, cp.getMiddleBlockX(), cp.getMiddleBlockZ(), 8)) {
            int over = 0;
            for (int lx = 0; lx < 16; lx += 3) {
                for (int lz = 0; lz < 16; lz += 3) {
                    int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                    if (f.r2(x + 0.5, z + 0.5) > 1.0) continue;
                    // Oil under this column: the reservoir reaches below the gas cap and above the water.
                    int top = f.top(x + 0.5, z + 0.5);
                    if (top - f.thick() < f.goc() && top > f.owc()) over++;
                }
            }
            best = Math.max(best, over / 36.0 * f.richness());
        }
        return best <= 0 ? 0 : (int) Math.min(9_500_000, 4_200_000 + 5_000_000 * best);
    }
}
