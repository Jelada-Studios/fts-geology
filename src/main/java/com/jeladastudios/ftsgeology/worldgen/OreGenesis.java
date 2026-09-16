package com.jeladastudios.ftsgeology.worldgen;

import static com.jeladastudios.ftsgeology.util.SeedHash.hash;
import static com.jeladastudios.ftsgeology.util.SeedHash.rand01;
import static com.jeladastudios.ftsgeology.util.ValueNoise.lattice3D;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise;
import static com.jeladastudios.ftsgeology.util.ValueNoise.noise3D;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Ore where the geology that makes ore actually is.
 *
 * <p>Ore is not scattered through rock at random. Every deposit is the residue of one particular
 * process, and each process belongs to one tectonic setting, so the plate model already knows where
 * each kind should be:</p>
 * <ul>
 *   <li><b>Porphyry copper</b> under a subduction arc - a stock with chalcopyrite scattered through its core, a
 *       halo of pyrite and a stockwork of quartz veinlets through both. Above it, at the old water table,
 *       groundwater has rotted the sulfides into a sheet of green malachite and blue azurite.</li>
 *   <li><b>Orogenic gold</b> in a collision belt - gently dipping quartz reefs with gold in shoots, and skarn
 *       pods of emerald or lapis in marble where limestone was cooked.</li>
 *   <li><b>Massive sulfides</b> at a rift - a flat lens of pyrite with a chalcopyrite core, over the stringer
 *       veinlets that fed it.</li>
 *   <li><b>Fault gouge</b> along a transform - galena, pyrite and quartz in lenses on the fault plane.</li>
 *   <li><b>Hydrothermal veins</b> wherever a fault or a geothermal field moves hot water: thin sheets of quartz
 *       with ore in shoots, zoned by depth, cinnabar only in the shallow, cooler part.</li>
 *   <li><b>Coal and ironstone</b> in the quiet basins between the belts, as seams in shale.</li>
 * </ul>
 *
 * <h2>Bodies, not dice</h2>
 * Each kind of deposit hangs off a coarse grid seeded from the world. A cell holds at most one vein, stock,
 * reef or lens, its shape worked out from the cell alone, so every chunk that asks gets the same deposit. A
 * chunk writes only the part inside itself, and the vein carries on in the next chunk. Inside a deposit the
 * ore is zoned the way it forms; only disseminated grains are scattered block by block.
 */
public final class OreGenesis {

    private OreGenesis() {}

    /** Places this chunk's part of every deposit that reaches it and returns how many blocks it wrote. */
    public static int generate(WorldGenLevel level, ChunkPos cp) {
        if (!GeyserConfig.ORE_GENESIS_ENABLED.get()) return 0;
        Deposit d = new Deposit(level, cp);
        sedimentaryBasin(d);
        hydrothermalVeins(d);
        porphyryCopper(d);
        orogenicGold(d);
        skarns(d);
        massiveSulfides(d);
        faultGouge(d);
        return d.placed;
    }

    /** One chunk's writing: its corner, its surface read once, and a running count. */
    private static final class Deposit {
        private static final int UNREAD = Integer.MIN_VALUE + 1;

        final WorldGenLevel level;
        final ServerLevel world;
        final long seed;
        final int x0, z0;
        final int[] ground = new int[256];
        int placed;
        /**
         * On the mod's own world type the rock under the ground comes from {@link Lithology}, and a deposit sits where
         * that rock says its geology is: a stock at the top of a pluton, a skarn in a marble band, a lens in the rift's
         * fill. On any other world type there is no such model and the deposits keep to the plates alone.
         */
        final boolean own;
        final long rockSeed;
        final com.jeladastudios.ftsgeology.tectonics.GeologyParams params;

        Deposit(WorldGenLevel level, ChunkPos cp) {
            this.level = level;
            this.world = level.getLevel();
            this.seed = level.getSeed();
            this.x0 = cp.getMinBlockX();
            this.z0 = cp.getMinBlockZ();
            Arrays.fill(ground, UNREAD);
            this.own = com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(world);
            this.rockSeed = own ? com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed() : 0L;
            this.params = own ? com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params() : null;
        }

        /** The rock column the lithology model gives a point, or null off the own world type. */
        com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Column column(int x, int z) {
            return own ? com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.column(rockSeed, params, x, z) : null;
        }

        /**
         * The ground at a deposit's anchor, which may lie outside this chunk. Always the generator's ground, never the
         * chunk's own heightmap, so every chunk a deposit reaches puts it at the same height. Own world type only.
         */
        int base(int x, int z) {
            return anchorGround(world, x, z);
        }

        boolean inside(int x, int z) {
            return x - x0 >= 0 && x - x0 < 16 && z - z0 >= 0 && z - z0 < 16;
        }

        /** Ground height of a column in this chunk, read once; MIN_VALUE outside it or where there is none. */
        int ground(int x, int z) {
            if (!inside(x, z)) return Integer.MIN_VALUE;
            int i = ((x - x0) << 4) | (z - z0);
            if (ground[i] == UNREAD) ground[i] = TerrainProbe.groundY(level, x, z);
            return ground[i];
        }

        /** Replaces host rock with ore, inside this chunk and at least {@code cover} blocks under the column's ground. */
        void set(int x, int y, int z, Block block, int cover) {
            if (!inside(x, z) || y <= level.getMinBuildHeight()) return;
            int g = ground(x, z);
            if (g == Integer.MIN_VALUE || y > g - cover) return;
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.is(block) || s.hasBlockEntity() || !isHostRock(s)) return;
            level.setBlock(p, block.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }

        /** Visits every cell of a grid whose deposit, anchored anywhere in it and reaching {@code reach} blocks, can touch this chunk. */
        void cells(int size, int reach, long salt, CellVisitor visit) {
            int minCx = Math.floorDiv(x0 - reach, size), maxCx = Math.floorDiv(x0 + 15 + reach, size);
            int minCz = Math.floorDiv(z0 - reach, size), maxCz = Math.floorDiv(z0 + 15 + reach, size);
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    visit.at(cx * size, cz * size, hash(seed, cx, cz, salt));
                }
            }
        }

        int minX(int from) { return Math.max(x0, from); }
        int maxX(int to) { return Math.min(x0 + 15, to); }
        int minZ(int from) { return Math.max(z0, from); }
        int maxZ(int to) { return Math.min(z0 + 15, to); }
    }

    /**
     * The generator's ground at the deposits' anchors, kept for the world: every chunk a deposit reaches asks for it,
     * and on the own world type each answer is a whole noise column. Dropped when the world stops.
     */
    private static final com.jeladastudios.ftsgeology.util.ColumnCache<Integer> ANCHOR_GROUND =
            new com.jeladastudios.ftsgeology.util.ColumnCache<>(16);
    private static volatile ServerLevel anchorsFor;

    private static int anchorGround(ServerLevel world, int x, int z) {
        if (world != anchorsFor) {
            synchronized (ANCHOR_GROUND) {
                if (world != anchorsFor) {
                    ANCHOR_GROUND.clear();
                    anchorsFor = world;
                }
            }
        }
        long key = com.jeladastudios.ftsgeology.util.ColumnCache.key(x, z);
        Integer known = ANCHOR_GROUND.get(key);
        if (known != null) return known;
        GenCost.height();
        int g = world.getChunkSource().getGenerator().getBaseHeight(x, z,
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, world,
                world.getChunkSource().randomState());
        ANCHOR_GROUND.put(key, g);
        return g;
    }

    /** Drops the anchor grounds kept for a world that has stopped. */
    public static void clear() {
        synchronized (ANCHOR_GROUND) {
            ANCHOR_GROUND.clear();
            anchorsFor = null;
        }
    }

    @FunctionalInterface
    private interface CellVisitor {
        void at(int cellX, int cellZ, long h);
    }

    /** A 0..1 die for one property of a deposit, from its cell's hash. */
    private static double die(long h, int slot) {
        return rand01(hash(h, slot, 0, 0x9E3779B9L));
    }

    /** A 0..1 value for one block, for the grains scattered through a zone. */
    private static double grain(int x, int y, int z, int salt) {
        return (lattice3D(x + salt, y, z - salt) + 1.0) * 0.5;
    }

    /** A die for one position: the same whatever order anything else was rolled in. */
    private static int roll(int x, int y, int z, int sides) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xD1B54A32D192ED03L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) Math.floorMod(h, (long) sides);
    }

    // === Basins ================================================================

    /**
     * Coal and ironstone, as seams in shale, in the quiet country between the active belts.
     *
     * <p>Presence and height are decided per column, so neighbouring columns differ by at most one
     * block, border or not.</p>
     */
    private static void sedimentaryBasin(Deposit d) {
        // The heart of an active belt is not a basin. Most chunks are plainly one or the other; only those near
        // the edge ask each column, which gives the edge a ragged line instead of a chunk border.
        double centre = TectonicMap.sampleCached(d.world, d.x0 + 8, d.z0 + 8).stress();
        if (centre > 0.86) return;
        boolean perColumn = centre > 0.54;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = d.x0 + lx, z = d.z0 + lz;
                int ground = d.ground(x, z);
                if (ground == Integer.MIN_VALUE) continue;
                if (perColumn && TectonicMap.sampleCached(d.world, x, z).stress()
                        > 0.62 + 0.16 * noise(x + 1301, z - 1301, 48.0)) continue;
                if (!coalBasin(d, x, z)) continue;
                // On the own world type a seam belongs in the sedimentary cover: where the basement comes up under
                // its level the seam pinches out, as at the edge of a real coalfield.
                Lithology.Column col = d.column(x, z);
                int cover = col == null ? Integer.MAX_VALUE : Lithology.coverDepth(col);

                // The upper seam: a buried delta swamp around Y=32, under a shale roof.
                if (noise(x, z, 80.0) > -0.25) {
                    int y = (int) Math.round(32.0 + 8.0 * Math.sin(x * 0.0075) + 6.0 * Math.cos(z * 0.0069)
                            + 1.5 * Math.sin(x * 0.2) + 1.5 * Math.cos(z * 0.2));
                    if (y < ground - 5 && y > d.level.getMinBuildHeight() + 10 && ground - y < cover) {
                        d.set(x, y + 1, z, ModBlocks.SHALE.get(), 4);
                        d.set(x, y, z, Blocks.COAL_ORE, 4);
                        d.set(x, y - 1, z, roll(x, y, z, 3) == 0 ? Blocks.COAL_ORE : ModBlocks.SHALE.get(), 4);
                    }
                }

                // The deep seam: carbonaceous shale buried long enough to sit in deepslate country.
                if (noise(x + 7919, z - 7919, 64.0) > 0.30) {
                    int y = (int) Math.round(-8.0 + 5.0 * Math.sin(x * 0.0056 + 1.2) + Math.sin(z * 0.13));
                    d.set(x, y, z, roll(x, y, z, 3) == 0 ? ModBlocks.SHALE.get() : Blocks.DEEPSLATE_COAL_ORE, 4);
                }

                // Ironstone: a thin oolitic horizon around Y=18, with grains of ore scattered through it.
                if (noise(x - 4099, z + 4099, 48.0) > 0.35 && roll(x, 18, z, 3) == 0) {
                    int y = (int) Math.round(18.0 + 3.0 * Math.sin(z * 0.011) + 2.0 * Math.cos(x * 0.009));
                    if (ground > y + 6 && ground - y < cover) d.set(x, y, z, Blocks.IRON_ORE, 4);
                }
            }
        }
    }

    /**
     * True where coal could form: on continental crust, in a basin that sank and filled with swamp and delta mud.
     * In front of a mountain belt the crust is pressed down into a foreland basin, as under the Ruhr, the
     * Appalachians and Zonguldak; a rift fills its graben; a few broad sags inside a plate fill slowly. Old
     * shields and the ocean floor have none.
     */
    private static boolean coalBasin(Deposit d, int x, int z) {
        PlateSample s = TectonicMap.sampleCached(d.world, x, z);
        if (s.plateKind().isOceanic()) return false;
        double width = GeyserConfig.FAULT_WIDTH.get();
        // A ragged margin rather than a line at a fixed distance.
        double dist = s.faultDistance() + 0.25 * width * noise(x + 211, z - 211, 60.0);
        boolean headOn = Math.abs(s.convergence()) >= s.shear();
        if (headOn && s.convergence() > 0) {
            if (s.neighbourKind().isOceanic()) {
                // An arc: the fore-arc basin between trench and arc and the back-arc basin behind it, both on the
                // plate that rides over (the Great Valley, the shores of the Sea of Japan).
                if (dist > 0.15 * width && dist < 0.35 * width) return true;
                if (dist > 1.0 * width && dist < 2.0 * width) return true;
            } else if (s.downGoing()) {
                // Two continents: the foreland basin sags on the plate pushed under the mountains (the Ruhr, the
                // Appalachians, Zonguldak), not on the one riding over them.
                if (dist > width && dist < 3.0 * width) return true;
            }
        }
        if (headOn && s.convergence() < 0 && dist < 1.5 * width) return true;
        return noise(x - 7717, z + 7717, 1500.0) > 0.3;
    }

    // === Veins =================================================================

    /** Grid of hydrothermal veins, and how far one reaches from its anchor. */
    private static final int VEIN_CELL = 32, VEIN_REACH = 26;
    /** Highest a hydrothermal vein reaches. */
    private static final int VEIN_TOP = 34;

    /**
     * Veins along the strike of a fault, or through a geothermal field: the fractures carry the hot
     * water, and the water drops its load as it cools on the way up. Each is a sheet one block thick, standing
     * along its strike and leaning a little, with a slow wave in it.
     */
    private static void hydrothermalVeins(Deposit d) {
        d.cells(VEIN_CELL, VEIN_REACH, 0x7E1A5L, (cellX, cellZ, h) -> {
            int ax = cellX + (int) (die(h, 0) * VEIN_CELL), az = cellZ + (int) (die(h, 1) * VEIN_CELL);
            PlateSample s = TectonicMap.sampleCached(d.world, ax, az);
            double plume = HotspotMap.plumeStrength(d.world, ax, az);
            // The busier the fault or the field, the more of its cells carry a vein.
            double chance = 0.0;
            if (s.stress() >= 0.35) chance += s.stress() > 0.6 ? 0.6 : 0.35;
            if (plume > 0.45) chance += plume > 0.6 ? 0.5 : 0.3;
            if (die(h, 2) >= chance) return;

            // Along the fault's strike and turned a little off it; in a field away from a fault, any way at all.
            double sx = s.faultStrikeX(), sz = s.faultStrikeZ();
            if (s.stress() < 0.35 || (Math.abs(sx) < 0.01 && Math.abs(sz) < 0.01)) {
                double a = die(h, 3) * Math.PI * 2;
                sx = Math.cos(a);
                sz = Math.sin(a);
            }
            double turn = (die(h, 4) - 0.5) * 0.6;
            double vx = sx * Math.cos(turn) - sz * Math.sin(turn);
            double vz = sx * Math.sin(turn) + sz * Math.cos(turn);
            int half = 8 + (int) (die(h, 5) * 8);
            int height = 8 + (int) (die(h, 6) * 10);
            double lean = (die(h, 7) - 0.5) * 0.9;
            int bottom = d.level.getMinBuildHeight() + 12;
            int ay = bottom + (int) (die(h, 8) * (VEIN_TOP - height - bottom));
            double phase = die(h, 9) * Math.PI * 2;
            int shoots = (int) (h & 0xFFF);

            int ext = half + (int) Math.ceil(Math.abs(lean) * height) + 2;
            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    double along = (x - ax) * vx + (z - az) * vz;
                    if (Math.abs(along) > half) continue;
                    double across = -(x - ax) * vz + (z - az) * vx;
                    double wave = 1.2 * Math.sin(along * 0.35 + phase);
                    for (int dy = 0; dy < height; dy++) {
                        if (Math.abs(across - lean * dy - wave) > 0.5) continue;
                        int y = ay + dy;
                        d.set(x, y, z, veinBlock(x, y, z, along, shoots), 4);
                    }
                }
            }
        });
    }

    /**
     * What a vein holds at one block: quartz, with ore where a shoot runs through it, zoned by depth as the
     * water cooled on its way up - cinnabar in the shallow part, galena lower, chalcopyrite and gold deepest.
     */
    private static Block veinBlock(int x, int y, int z, double along, int shoots) {
        double r = grain(x, y, z, 0x51);
        boolean shoot = noise((int) Math.round(along * 2) + shoots, y * 2, 12.0) > 0.2;
        if (!shoot) return r < 0.12 ? ModBlocks.PYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
        if (y > 12) return r < 0.5 ? ModBlocks.CINNABAR.get() : ModBlocks.QUARTZ_VEIN.get();
        if (y > -12) {
            return r < 0.55 ? ModBlocks.GALENA.get() : r < 0.75 ? ModBlocks.PYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
        }
        if (r < 0.45) return ModBlocks.CHALCOPYRITE.get();
        if (r < 0.62) return y < 0 ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE;
        return r < 0.78 ? ModBlocks.PYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
    }

    // === Tectonic settings =====================================================

    private static final int PORPHYRY_CELL = 64, PORPHYRY_REACH = 24;

    /** Porphyry copper around an arc's plutons, and the oxidised sheet over it. */
    private static void porphyryCopper(Deposit d) {
        d.cells(PORPHYRY_CELL, PORPHYRY_REACH, 0x9C0FL, (cellX, cellZ, h) -> {
            if (die(h, 0) >= 0.7) return;
            int ax = cellX + (int) (die(h, 1) * PORPHYRY_CELL), az = cellZ + (int) (die(h, 2) * PORPHYRY_CELL);
            PlateSample s = TectonicMap.sampleCached(d.world, ax, az);
            if (s.faultType() != FaultType.CONVERGENT_SUBDUCTION || s.stress() < 0.2) return;
            // Around the arc root on the overriding plate, not out on the fore-arc and never on the plate going under.
            if (s.downGoing() || s.faultDistance() / GeyserConfig.FAULT_WIDTH.get() > 0.65) return;

            double core = 4.5 + die(h, 4) * 3.0;
            int ay = d.level.getMinBuildHeight() + 24 + (int) (die(h, 3) * 50);
            Lithology.Column col = d.column(ax, az);
            if (col != null) {
                // A stock is the apex of the pluton that fed it: no pluton under the arc here, no stock.
                if (col.setting() != Lithology.Setting.ARC || !Lithology.hasPluton(col)) return;
                ay = d.base(ax, az) - col.plutonTop() - (int) Math.ceil(core);
                if (ay < d.level.getMinBuildHeight() + 24) return;
            }
            double shell = core + 5.0 + die(h, 5) * 3.0;
            double stretch = 1.4;                       // a stock stands taller than it is wide
            int ext = (int) Math.ceil(shell) + 1;
            int up = (int) Math.ceil(shell * stretch);
            // The stockwork: three sets of thin veinlets crossing the stock.
            double[][] planes = new double[3][];
            for (int k = 0; k < 3; k++) {
                double a = die(h, 6 + k) * Math.PI, t = (die(h, 9 + k) - 0.5) * 1.2;
                planes[k] = new double[]{Math.cos(a) * Math.cos(t), Math.sin(t), Math.sin(a) * Math.cos(t)};
            }

            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    double hd2 = (double) (x - ax) * (x - ax) + (double) (z - az) * (z - az);
                    if (hd2 > shell * shell) continue;
                    for (int y = ay - up; y <= ay + up; y++) {
                        double dv = (y - ay) / stretch;
                        double r = Math.sqrt(hd2 + dv * dv);
                        if (r > shell) continue;
                        Block b = null;
                        if (r <= shell * 0.85) {
                            for (double[] n : planes) {
                                if (Math.abs((x - ax) * n[0] + (y - ay) * n[1] + (z - az) * n[2]) > 0.5) continue;
                                b = grain(x, y, z, 0x77) < 0.3 ? ModBlocks.CHALCOPYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
                                break;
                            }
                        }
                        if (b == null) {
                            double g = grain(x, y, z, 0x3C);
                            if (r <= core) {
                                // The core: copper sulfide scattered through the rock as grains.
                                if (g < 0.08) b = ModBlocks.CHALCOPYRITE.get();
                                else if (g < 0.12) b = y < 0 ? Blocks.DEEPSLATE_COPPER_ORE : Blocks.COPPER_ORE;
                            } else if (g < 0.07) {
                                b = ModBlocks.PYRITE.get();     // the pyrite halo
                            }
                        }
                        if (b != null) d.set(x, y, z, b, 8);
                    }
                }
            }

            // The supergene sheet over the stock, at the old water table: a patchy layer fourteen to eighteen
            // blocks under each column's ground, so it follows the land above.
            int sheet = (int) Math.ceil(shell) + 4;
            int patch = (int) (h & 1023);
            for (int x = d.minX(ax - sheet); x <= d.maxX(ax + sheet); x++) {
                for (int z = d.minZ(az - sheet); z <= d.maxZ(az + sheet); z++) {
                    if ((double) (x - ax) * (x - ax) + (double) (z - az) * (z - az) > (double) sheet * sheet) continue;
                    if (noise(x + patch, z, 7.0) < -0.1) continue;
                    int g = d.ground(x, z);
                    if (g == Integer.MIN_VALUE || g < 25) continue;
                    int y = g - 14 - (int) Math.round(2.0 + 2.0 * noise(x - 211, z + 211, 20.0));
                    double c = grain(x, y, z, 0x2E);
                    d.set(x, y, z, c < 0.5 ? ModBlocks.MALACHITE.get() : ModBlocks.AZURITE.get(), 12);
                    if (c > 0.8) d.set(x, y - 1, z, ModBlocks.MALACHITE.get(), 12);
                }
            }
        });
    }

    private static final int REEF_CELL = 48, REEF_REACH = 36;

    /** Orogenic gold: quartz reefs lying in the foliation of a collision root, gold in shoots along them. */
    private static void orogenicGold(Deposit d) {
        d.cells(REEF_CELL, REEF_REACH, 0x601DL, (cellX, cellZ, h) -> {
            if (die(h, 0) >= 0.6) return;
            int ax = cellX + (int) (die(h, 1) * REEF_CELL), az = cellZ + (int) (die(h, 2) * REEF_CELL);
            PlateSample s = TectonicMap.sampleCached(d.world, ax, az);
            if (s.faultType() != FaultType.CONVERGENT_COLLISION || s.stress() < 0.2) return;
            double sx = s.faultStrikeX(), sz = s.faultStrikeZ();
            if (Math.abs(sx) < 0.01 && Math.abs(sz) < 0.01) {
                sx = 1.0;
                sz = 0.0;
            }
            // Along the belt, dipping gently to one side of it.
            int half = 10 + (int) (die(h, 3) * 11);
            double run = 7.0 + die(h, 4) * 6.0;
            double slope = 0.45 + die(h, 5) * 0.4;
            double side = die(h, 6) < 0.5 ? -1.0 : 1.0;
            int ay = d.level.getMinBuildHeight() + 20 + (int) (die(h, 7) * 64);
            Lithology.Column col = d.column(ax, az);
            if (col != null) {
                // In the belt's metamorphic bands, above the granite core they wrap round.
                if (col.setting() != Lithology.Setting.FOLD_BELT) return;
                int ground = d.base(ax, az), top = ground - 24;
                int floor = Lithology.hasPluton(col) ? ground - col.plutonTop() + 4 : d.level.getMinBuildHeight() + 20;
                if (top - floor < 8) return;
                ay = floor + (int) (die(h, 7) * (top - floor));
            }
            int shoots = (int) (h & 0xFFF);

            int ext = half + (int) Math.ceil(run) + 2;
            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    double along = (x - ax) * sx + (z - az) * sz;
                    if (Math.abs(along) > half) continue;
                    double across = side * (-(x - ax) * sz + (z - az) * sx);
                    if (across < 0 || across > run) continue;
                    int y = ay - (int) Math.round(across * slope + 0.8 * Math.sin(along * 0.3 + shoots));
                    double g = grain(x, y, z, 0x60);
                    Block b;
                    if (noise((int) Math.round(along * 2) + shoots, (int) Math.round(across * 2), 10.0) > 0.4 && g < 0.6) {
                        b = y < 0 ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE;
                    } else {
                        b = g < 0.14 ? ModBlocks.PYRITE.get() : ModBlocks.QUARTZ_VEIN.get();
                    }
                    d.set(x, y, z, b, 6);
                }
            }
        });
    }

    private static final int SKARN_CELL = 64, SKARN_REACH = 5;

    /** Skarn: a pod of emerald or lapis in marble, where an intrusion cooked a limestone bed of the root. */
    private static void skarns(Deposit d) {
        d.cells(SKARN_CELL, SKARN_REACH, 0x5CA2L, (cellX, cellZ, h) -> {
            if (die(h, 0) >= 0.35) return;
            int ax = cellX + (int) (die(h, 1) * SKARN_CELL), az = cellZ + (int) (die(h, 2) * SKARN_CELL);
            PlateSample s = TectonicMap.sampleCached(d.world, ax, az);
            if (s.faultType() != FaultType.CONVERGENT_COLLISION || s.stress() < 0.2) return;
            int ay = d.level.getMinBuildHeight() + 12 + (int) (die(h, 3) * 60);
            Lithology.Column col = d.column(ax, az);
            if (col != null) {
                // A skarn is a marble band an intrusion cooked: the pod goes in the nearest one, or nowhere.
                if (col.setting() != Lithology.Setting.FOLD_BELT) return;
                int ground = d.base(ax, az), found = Integer.MIN_VALUE;
                for (int off = 0; off <= 30 && found == Integer.MIN_VALUE; off++) {
                    for (int y : new int[] {ay - off, ay + off}) {
                        if (y <= d.level.getMinBuildHeight() + 8 || y > ground - 8) continue;
                        if (Lithology.rockAt(d.rockSeed, col, ax, y, az, ground) == Lithology.Rock.MARBLE) {
                            found = y;
                            break;
                        }
                    }
                }
                if (found == Integer.MIN_VALUE) return;
                ay = found;
            }
            double r = 2.0 + die(h, 4) * 1.5;
            boolean emerald = die(h, 5) < 0.5;
            int ext = (int) Math.ceil(r) + 1;
            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    for (int y = ay - ext; y <= ay + ext; y++) {
                        double dist = Math.sqrt((double) (x - ax) * (x - ax) + (double) (y - ay) * (y - ay)
                                + (double) (z - az) * (z - az));
                        if (dist > r + 1) continue;
                        Block b = ModBlocks.MARBLE.get();
                        if (dist <= r && grain(x, y, z, 0x5C) < 0.35) {
                            b = emerald ? (y < 0 ? Blocks.DEEPSLATE_EMERALD_ORE : Blocks.EMERALD_ORE)
                                    : (y < 0 ? Blocks.DEEPSLATE_LAPIS_ORE : Blocks.LAPIS_ORE);
                        }
                        d.set(x, y, z, b, 5);
                    }
                }
            }
        });
    }

    private static final int VMS_CELL = 48, VMS_REACH = 12;

    /** Volcanogenic massive sulfide: a flat lens in the new crust of a rift, over the veinlets that fed it. */
    private static void massiveSulfides(Deposit d) {
        d.cells(VMS_CELL, VMS_REACH, 0x5F1DL, (cellX, cellZ, h) -> {
            if (die(h, 0) >= 0.7) return;
            int ax = cellX + (int) (die(h, 1) * VMS_CELL), az = cellZ + (int) (die(h, 2) * VMS_CELL);
            PlateSample s = TectonicMap.sampleCached(d.world, ax, az);
            if (s.faultType() != FaultType.DIVERGENT || s.stress() < 0.2) return;
            double sx = s.faultStrikeX(), sz = s.faultStrikeZ();
            if (Math.abs(sx) < 0.01 && Math.abs(sz) < 0.01) {
                sx = 1.0;
                sz = 0.0;
            }
            double a = 6.0 + die(h, 3) * 3.0, b = 4.0 + die(h, 4) * 2.0, c = 1.5 + die(h, 5);
            int ay = d.level.getMinBuildHeight() + 22 + (int) (die(h, 6) * 50);
            Lithology.Column col = d.column(ax, az);
            if (col != null) {
                // In the rift's fill, the new crust the ore fluid rose through, not the basement under it.
                if (col.setting() != Lithology.Setting.RIFT) return;
                int fill = Lithology.coverDepth(col);
                if (fill < 12) return;
                ay = d.base(ax, az) - 6 - (int) (die(h, 6) * (fill - 10));
            }
            int stringer = 8 + (int) (die(h, 7) * 5);
            int ext = (int) Math.ceil(a) + 1;
            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    double along = (x - ax) * sx + (z - az) * sz;
                    double across = -(x - ax) * sz + (z - az) * sx;
                    double flat = (along / a) * (along / a) + (across / b) * (across / b);
                    if (flat <= 1.0) {
                        int half = (int) Math.floor(c * Math.sqrt(1.0 - flat));
                        for (int dy = -half; dy <= half; dy++) {
                            int y = ay + dy;
                            double g = grain(x, y, z, 0x5F);
                            Block ore = flat < 0.3 && g < 0.55 ? ModBlocks.CHALCOPYRITE.get()
                                    : g < 0.88 ? ModBlocks.PYRITE.get() : null;
                            if (ore != null) d.set(x, y, z, ore, 5);
                        }
                    }
                    // The stringer zone under the lens: the veinlets the ore fluid rose through.
                    if ((double) (x - ax) * (x - ax) + (double) (z - az) * (z - az) <= 6.25) {
                        for (int dy = 1; dy <= stringer; dy++) {
                            int y = ay - (int) Math.ceil(c) - dy;
                            double g = grain(x, y, z, 0x57);
                            if (g < 0.25) d.set(x, y, z, g < 0.1 ? ModBlocks.CHALCOPYRITE.get() : ModBlocks.QUARTZ_VEIN.get(), 5);
                        }
                    }
                }
            }
        });
    }

    /** Ore in lenses on a transform's fault plane, where the fluids moving along it dropped their load. */
    private static void faultGouge(Deposit d) {
        PlateSample centre = TectonicMap.sampleCached(d.world, d.x0 + 8, d.z0 + 8);
        if (centre.faultType() != FaultType.TRANSFORM || centre.faultDistance() > 24) return;
        Map<Long, Double> corners = new HashMap<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = d.x0 + lx, z = d.z0 + lz;
                PlateSample col = TectonicMap.sampleCached(d.world, x, z);
                if (col.faultType() != FaultType.TRANSFORM || col.faultDistance() > 6) continue;
                if (DeepStructure.faultDistanceAt(d.world, x, z, corners) > 1.5) continue;
                int g = d.ground(x, z);
                if (g == Integer.MIN_VALUE) continue;
                for (int y = d.level.getMinBuildHeight() + 8; y <= Math.min(48, g - 4); y++) {
                    if (noise3D(x + 3331, y, z - 3331, 16.0, 8.0) < 0.5) continue;
                    double gr = grain(x, y, z, 0x6A);
                    d.set(x, y, z, gr < 0.45 ? ModBlocks.GALENA.get()
                            : gr < 0.8 ? ModBlocks.PYRITE.get() : ModBlocks.QUARTZ_VEIN.get(), 4);
                }
            }
        }
    }

    /** Natural rock a deposit may replace. Ore already there, the generator's or another deposit's, stays. */
    private static boolean isHostRock(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(Blocks.CALCITE) || s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(ModBlocks.GABBRO.get()) || s.is(ModBlocks.PERIDOTITE.get())
                || s.is(ModBlocks.SERPENTINITE.get()) || s.is(ModBlocks.SCHIST.get())
                || s.is(ModBlocks.GNEISS.get()) || s.is(ModBlocks.SLATE.get())
                || s.is(ModBlocks.MARBLE.get()) || s.is(ModBlocks.QUARTZITE.get())
                || s.is(ModBlocks.SHALE.get()) || s.is(ModBlocks.CHERT.get());
    }
}
