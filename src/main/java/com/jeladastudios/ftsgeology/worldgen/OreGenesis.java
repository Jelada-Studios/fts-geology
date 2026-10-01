package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.compat.tfc.TfcCompat;

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
import java.util.List;
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
 *   <li><b>Kimberlite</b> in the old heart of a continent - a pipe of peridotite with diamonds in its root.</li>
 *   <li><b>Magnetite</b> in the sea floor's gabbro, as flat seams, and scattered through serpentinite.</li>
 *   <li><b>Native copper</b> at the top of old basalt flows in a rift or over a hot spot.</li>
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
        kimberlites(d);
        magnetite(d);
        nativeCopper(d);
        modOres(d);
        d.placed += PetroleumFields.generate(level, cp);
        fossilBeds(d);
        gypsumBeds(d);
        gasSeeps(d);
        return d.placed;
    }

    /** Soil and loose ground a seep's vent comes up through. */
    private static boolean thinCover(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL) || s.is(BlockTags.SAND) || s.is(Blocks.CLAY);
    }

    /** Grid of natural gas seeps, and how far a group of vents spreads from its middle. */
    private static final int SEEP_CELL = 96, SEEP_REACH = 6;

    /**
     * Burning methane seeps where an ophiolite's serpentinite comes to the surface: sea water working on the mantle rock
     * makes methane and hydrogen, which find their way up its cracks -- the Chimaera of Lycia (Yanartas), a hillside of
     * flames that have burned for thousands of years. A few vents together, lit, on the rock or burnt through a thin soil
     * over it.
     */
    private static void gasSeeps(Deposit d) {
        if (!d.own || !com.jeladastudios.ftsgeology.gas.GasConfig.ENABLED.get()) return;
        double chance = com.jeladastudios.ftsgeology.gas.GasConfig.SEEP_CHANCE.get();
        if (chance <= 0) return;
        d.cells(SEEP_CELL, SEEP_REACH, 0x5EE9C41L, (cellX, cellZ, h) -> {
            if (die(h, 0) >= chance) return;
            int ax = cellX + SEEP_REACH + (int) (die(h, 1) * (SEEP_CELL - 2 * SEEP_REACH));
            int az = cellZ + SEEP_REACH + (int) (die(h, 2) * (SEEP_CELL - 2 * SEEP_REACH));
            int vents = 2 + (int) (die(h, 3) * 5);
            for (int i = 0; i < vents; i++) {
                int x = ax + (int) Math.round((die(h, 10 + i) - 0.5) * 2 * SEEP_REACH);
                int z = az + (int) Math.round((die(h, 30 + i) - 0.5) * 2 * SEEP_REACH);
                if (!d.inside(x, z)) continue;
                int g = d.ground(x, z);
                if (g == Integer.MIN_VALUE) continue;
                BlockPos p = new BlockPos(x, g, z);
                // Bare rock, or rock under a thin soil (the vent burns its way through, and the plants over it).
                BlockPos.MutableBlockPos q = p.mutable();
                while (q.getY() > g - 4 && thinCover(d.level.getBlockState(q))) q.move(0, -1, 0);
                BlockState rock = d.level.getBlockState(q);
                if (!rock.is(ModBlocks.SERPENTINITE.get()) && !rock.is(ModBlocks.PERIDOTITE.get())) continue;
                BlockState over = d.level.getBlockState(p.above());
                if (!over.isAir() && !over.is(BlockTags.REPLACEABLE)) continue;
                if (!over.isAir()) d.level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                d.level.setBlock(p, com.jeladastudios.ftsgeology.gas.registry.GasBlocks.GAS_SEEP.get().defaultBlockState(),
                        Block.UPDATE_CLIENTS);
                d.placed++;
                com.jeladastudios.ftsgeology.util.Diagnostics.info("methane seep at {} {} {}", x, g, z);
            }
        });
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
            level.setBlock(p, TfcCompat.translate(level, p, block.defaultBlockState()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }

        /** Writes a state into a host the predicate takes, inside this chunk and {@code cover} blocks under its ground. */
        boolean setIn(int x, int y, int z, BlockState state, int cover, java.util.function.Predicate<BlockState> host) {
            if (!inside(x, z) || y <= level.getMinBuildHeight()) return false;
            int g = ground(x, z);
            if (g == Integer.MIN_VALUE || y > g - cover) return false;
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.is(state.getBlock()) || s.hasBlockEntity() || !host.test(s)) return false;
            level.setBlock(p, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
            return true;
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

    /** The generator's ground at a point, kept: for a deposit's anchor anywhere (see {@link #anchorGround}). */
    static int anchorGroundAt(ServerLevel world, int x, int z) {
        return anchorGround(world, x, z);
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
        boolean fossils = d.own && FossilBeds.geological();

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
                        if (fossils) coalRoof(d, x, y, z, false);
                    }
                }

                // The deep seam: carbonaceous shale buried long enough to sit in deepslate country.
                if (noise(x + 7919, z - 7919, 64.0) > 0.30) {
                    int y = (int) Math.round(-8.0 + 5.0 * Math.sin(x * 0.0056 + 1.2) + Math.sin(z * 0.13));
                    d.set(x, y, z, roll(x, y, z, 3) == 0 ? ModBlocks.SHALE.get() : Blocks.DEEPSLATE_COAL_ORE, 4);
                    if (fossils) coalRoof(d, x, y, z, true);
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
        return basin(d.world, x, z);
    }

    /** The same for any world: where a basin sank and filled (coal, oil and gas are in them; see {@link PetroleumFields}). */
    static boolean basin(ServerLevel world, int x, int z) {
        PlateSample s = TectonicMap.sampleCached(world, x, z);
        if (s.plateKind().isOceanic()) return false;
        double width = com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().faultWidth();
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

    // === Fossils (Jurassic Reborn) ==============================================

    /**
     * Plant fossils in the shale over a coal seam -- the leaves and bark of the swamp forest the coal was made of, as in
     * the roof of every coal mine -- and amber over the young seam, the resin of its trees.
     */
    private static void coalRoof(Deposit d, int x, int y, int z, boolean deep) {
        if (roll(x, y + 1, z, 9) == 0) {
            Block flora = FossilBeds.block(deep ? "deepslate_flora_fossil" : "flora_fossil");
            java.util.function.Predicate<BlockState> roof = deep
                    ? s -> s.is(Blocks.DEEPSLATE) || s.is(ModBlocks.SHALE.get()) : s -> s.is(ModBlocks.SHALE.get());
            if (flora != null && d.setIn(x, y + 1, z, variant(flora.defaultBlockState(), roll(x, y, z, 64)), 4, roof)) {
                FossilBeds.PLANTS.increment();
            }
        }
        if (!deep && roll(x, y + 2, z, 12) == 0) {
            Block amber = FossilBeds.block("amber_ore");
            if (amber != null && d.setIn(x, y + 2, z, amber.defaultBlockState(), 4, OreGenesis::sediment)) FossilBeds.AMBER.increment();
        }
    }

    /** A block's {@code variant}, if it has one, picked by a roll. */
    private static BlockState variant(BlockState s, int roll) {
        net.minecraft.world.level.block.state.properties.Property<?> p = s.getBlock().getStateDefinition().getProperty("variant");
        return p == null ? s : withValue(s, p, roll);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState s,
            net.minecraft.world.level.block.state.properties.Property<T> p, int roll) {
        List<T> values = new java.util.ArrayList<>(p.getPossibleValues());
        return s.setValue(p, values.get(Math.floorMod(roll, values.size())));
    }

    /** Grid of bone beds, and the most one reaches from its middle. */
    private static final int BED_CELL = 40, BED_REACH = 12;

    /** Rock a bone can lie in: what was laid down as sediment, deep or not. */
    private static boolean boneHost(BlockState s) {
        return s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.CALCITE) || s.is(ModBlocks.SHALE.get()) || s.is(ModBlocks.CHERT.get());
    }

    /** One bone bed: its middle and height, age, sea or land, how rich, its species, reach and bones, a nest, amber. */
    public record BoneBed(int x, int z, int y, FossilBeds.Age age, boolean sea, boolean rich, Block[] species, int r,
                          int bones, Block nest, Block amber, long h) {}

    /**
     * The bone bed of a grid cell, or null: worked out from the cell alone, so every chunk it reaches lays the same bed
     * and a search can find it without building anything.
     */
    static BoneBed boneBed(ServerLevel world, long seed, long rockSeed, com.jeladastudios.ftsgeology.tectonics.GeologyParams params,
                           int cellX, int cellZ, long h) {
        Map<FossilBeds.Age, FossilBeds.Stage> stages = FossilBeds.stages();
        int ax = cellX + (int) (die(h, 1) * BED_CELL), az = cellZ + (int) (die(h, 2) * BED_CELL);
        int ox = (int) (seed & 0xFFFFL), oz = (int) ((seed >>> 16) & 0xFFFFL);
        boolean rich = noise(ax + ox, az - oz, 1200.0) > 0.4;
        if (die(h, 0) >= (rich ? 0.8 : 0.15)) return null;
        if (!basin(world, ax, az)) return null;
        Lithology.Column col = Lithology.column(rockSeed, params, ax, az);
        // A bed lies in the basin's cover, the sediment on its crystalline basement, never in the basement itself. The
        // cover holds its ages in order, the oldest at its foot on the basement and the youngest under the soil: a
        // platform's from the Ordovician up, a foreland's and a rift's only the younger ones, laid since they sank.
        int g = anchorGround(world, ax, az), top = g - 6, bottom = g - Lithology.coverDepth(col);
        FossilBeds.Age[] all = FossilBeds.Age.values();
        int oldest = switch (col.setting()) {
            case PLATFORM -> 0;
            case FORELAND -> FossilBeds.Age.TRIASSIC.ordinal();
            default -> FossilBeds.Age.JURASSIC.ordinal();
        };
        int span = all.length - oldest;
        if (top - bottom + 1 < 2 * span) return null;
        List<FossilBeds.Age> ages = new java.util.ArrayList<>();
        for (int i = oldest; i < all.length; i++) if (stages.get(all[i]).has()) ages.add(all[i]);
        if (ages.isEmpty()) return null;
        FossilBeds.Age age = ages.get((int) (die(h, 3) * ages.size()));
        FossilBeds.Stage st = stages.get(age);
        double slice = (top - bottom + 1) / (double) span;
        int lo = bottom + (int) Math.floor((age.ordinal() - oldest) * slice);
        int hi = bottom + (int) Math.floor((age.ordinal() - oldest + 1) * slice) - 1;
        int y = lo + (int) (die(h, 4) * Math.max(1, hi - lo));
        Boolean sea;
        switch (Lithology.rockAt(rockSeed, col, ax, y, az, g)) {
            case CALCITE, CHERT -> sea = Boolean.TRUE;
            case SANDSTONE, RED_BEDS -> sea = Boolean.FALSE;
            case SHALE, KEEP, STONE -> sea = null;
            default -> {
                return null;                                                // no bones in marble, basalt or granite
            }
        }
        boolean canSea = !st.sea.isEmpty(), canLand = !st.land.isEmpty();
        if (sea == null) sea = canSea && (!canLand || die(h, 5) < (col.setting() == Lithology.Setting.RIFT ? 0.25 : 0.55));
        List<Block> pool = sea ? st.sea : st.land;
        if (pool.isEmpty()) return null;
        int kinds = Math.min(pool.size(), rich ? 2 + (int) (die(h, 6) * 3) : 1 + (int) (die(h, 6) * 2));
        Block[] species = new Block[kinds];
        int first = (int) (die(h, 7) * pool.size());
        for (int k = 0; k < kinds; k++) species[k] = pool.get((first + k * 7) % pool.size());
        int r = rich ? 5 + (int) (die(h, 8) * 5) : 3 + (int) (die(h, 8) * 2);
        int bones = rich ? 10 + (int) (die(h, 9) * 7) : 4 + (int) (die(h, 9) * 5);
        Block nest = !sea && age == FossilBeds.Age.CRETACEOUS && die(h, 10) < 0.25 ? FossilBeds.block("nest_fossil") : null;
        Block amber = !sea && (age == FossilBeds.Age.CRETACEOUS || age == FossilBeds.Age.CENOZOIC) && die(h, 11) < 0.35
                ? FossilBeds.block(y < 0 ? "deepslate_amber_ore" : "amber_ore") : null;
        return new BoneBed(ax, az, y, age, sea, rich, species, r, bones, nest, amber, h);
    }

    /** The salt of the bone beds' grid. */
    private static final long BED_SALT = 0xF055E1L;

    /**
     * Bone beds of Jurassic Reborn's animals in the sedimentary cover of a basin (see {@link FossilBeds}). A bed is a
     * lens a few blocks across of one age -- the age whose band of height it lies in -- with one to four species in
     * it: sea creatures where the rock is shale or limestone, land animals in sandstone and red beds, the rest by the
     * setting (a rift's lakes and rivers hold more land animals than a platform's sea). In a few stretches of country
     * the beds are many and rich (a Morrison, a Holzmaden, a Liaoning); elsewhere one turns up now and then. A Cretaceous
     * land bed may hold a nest; a young land bed, amber.
     */
    private static void fossilBeds(Deposit d) {
        if (!d.own || !FossilBeds.geological()) return;
        Block fauna = FossilBeds.block("fauna_fossil");
        d.cells(BED_CELL, BED_REACH, BED_SALT, (cellX, cellZ, h) -> {
            BoneBed bed = boneBed(d.world, d.seed, d.rockSeed, d.params, cellX, cellZ, h);
            if (bed == null) return;
            if (d.inside(bed.x(), bed.z())) FossilBeds.BEDS.increment();
            int parts = bed.bones() + 2 + (bed.nest() == null ? 0 : 1) + (bed.amber() == null ? 0 : 3);
            for (int i = 0; i < parts; i++) {
                double ang = die(h, 20 + i) * Math.PI * 2, dist = Math.sqrt(die(h, 60 + i)) * bed.r();
                int bx = bed.x() + (int) Math.round(Math.cos(ang) * dist), bz = bed.z() + (int) Math.round(Math.sin(ang) * dist);
                if (!d.inside(bx, bz)) continue;
                int by = bed.y() + (die(h, 100 + i) < 0.5 ? 0 : 1);
                Block put;
                if (i < bed.bones()) put = bed.species()[i % bed.species().length];
                else if (i < bed.bones() + 2) put = fauna;
                else if (bed.nest() != null && i == bed.bones() + 2) put = bed.nest();
                else put = bed.amber();
                if (put == null) continue;
                if (d.setIn(bx, by, bz, variant(put.defaultBlockState(), (int) (die(h, 140 + i) * 64)), 4, OreGenesis::boneHost)) {
                    if (i < bed.bones()) FossilBeds.BONES.increment();
                    else if (put == bed.amber()) FossilBeds.AMBER.increment();
                }
            }
        });
    }

    /** The bone bed nearest a point, within a reach, or null: for finding one. Pure; safe off the server thread. */
    public static BoneBed nearestBoneBed(ServerLevel world, int x, int z, int reach) {
        if (!com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(world) || !FossilBeds.geological()) return null;
        long seed = world.getSeed();
        long rockSeed = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed();
        var params = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params();
        BoneBed best = null;
        double bd = Double.MAX_VALUE;
        for (int cx = Math.floorDiv(x - reach, BED_CELL); cx <= Math.floorDiv(x + reach, BED_CELL); cx++) {
            for (int cz = Math.floorDiv(z - reach, BED_CELL); cz <= Math.floorDiv(z + reach, BED_CELL); cz++) {
                double far = Math.hypot((cx + 0.5) * BED_CELL - x, (cz + 0.5) * BED_CELL - z) - BED_CELL;
                if (far > bd) continue;
                BoneBed b = boneBed(world, seed, rockSeed, params, cx * BED_CELL, cz * BED_CELL, hash(seed, cx, cz, BED_SALT));
                if (b == null) continue;
                double dd = Math.hypot(b.x() - x, b.z() - z);
                if (dd < bd) {
                    bd = dd;
                    best = b;
                }
            }
        }
        return best;
    }

    /**
     * Jurassic Reborn's gypsum where a basin once dried out: the thin limestone beds of its cover turned gypsum, in
     * the stretches of a basin that lay under a desert sea (the Zechstein, the Messinian, the gypsum hills of Sivas).
     */
    private static void gypsumBeds(Deposit d) {
        if (!d.own || !FossilBeds.geological()) return;
        int ox = (int) ((d.seed >>> 8) & 0xFFFFL), oz = (int) ((d.seed >>> 24) & 0xFFFFL);
        if (noise(d.x0 + 8 + ox, d.z0 + 8 - oz, 700.0) < 0.3) return;
        Block gypsum = FossilBeds.block("gypsum_stone");
        if (gypsum == null || !basin(d.world, d.x0 + 8, d.z0 + 8)) return;
        Lithology.Column col = d.column(d.x0 + 8, d.z0 + 8);
        if (col == null) return;
        int cover = Lithology.coverDepth(col);
        if (cover <= 0) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = d.x0; x < d.x0 + 16; x++) {
            for (int z = d.z0; z < d.z0 + 16; z++) {
                if (noise(x + ox, z - oz, 700.0) < 0.4) continue;
                int g = d.ground(x, z);
                if (g == Integer.MIN_VALUE) continue;
                for (int y = g - 3; y > g - cover && y > d.level.getMinBuildHeight(); y--) {
                    if (!d.level.getBlockState(m.set(x, y, z)).is(Blocks.CALCITE)) continue;
                    if (d.setIn(x, y, z, gypsum.defaultBlockState(), 3, s -> s.is(Blocks.CALCITE))) FossilBeds.GYPSUM.increment();
                }
            }
        }
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
            if (s.downGoing() || s.faultDistance() / com.jeladastudios.ftsgeology.tectonics.GeologyParams.current().faultWidth() > 0.65) return;

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

    // === Ore in the mafic and ultramafic rocks ====================================
    //
    // The dark rocks of the sea floor and the mantle -- gabbro, peridotite, serpentinite, basalt -- are the hosts of
    // their own ores, and a player who knows it reads them as a sign. These three are placed only on the mod's own
    // world types, where the rock model says where those rocks are.

    /** The nearest height, from {@code from} downwards through {@code span} blocks, at which the column is one of these rocks. */
    private static int findHost(Deposit d, Lithology.Column col, int x, int z, int ground, int from, int span,
                                java.util.Set<Lithology.Rock> hosts) {
        for (int y = from; y > from - span; y--) {
            if (y <= d.level.getMinBuildHeight() + 6) return Integer.MIN_VALUE;
            if (hosts.contains(Lithology.rockAt(d.rockSeed, col, x, y, z, ground))) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static final int PIPE_CELL = 192, PIPE_REACH = 10;

    /**
     * Kimberlite: a carrot-shaped pipe of mantle rock punched up through the old heart of a continent, carrying
     * diamonds from deep down. Only in the plate interiors, far from any boundary, as the real ones are in the oldest
     * cratons. The pipe is peridotite, altered to serpentinite in its upper part; the diamonds are in its lower part,
     * scattered thin. It stops short of the surface: nothing of it shows but the rock a miner digs into.
     */
    private static void kimberlites(Deposit d) {
        if (!d.own) return;
        d.cells(PIPE_CELL, PIPE_REACH, 0x61B3L, (cellX, cellZ, h) -> {
            if (die(h, 0) >= 0.3) return;
            int ax = cellX + 12 + (int) (die(h, 1) * (PIPE_CELL - 24)), az = cellZ + 12 + (int) (die(h, 2) * (PIPE_CELL - 24));
            Lithology.Column col = d.column(ax, az);
            if (col == null || col.setting() != Lithology.Setting.PLATFORM || col.weight() < 1.0) return;
            if (TectonicMap.sampleCached(d.world, ax, az).faultType() != FaultType.INTERIOR) return;
            int ground = d.base(ax, az);
            int bottom = d.level.getMinBuildHeight() + 6, top = ground - 8;
            if (top - bottom < 40) return;
            double r0 = 3.5 + die(h, 3) * 2.5;
            int ext = (int) Math.ceil(r0) + 2;
            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    double dist = Math.hypot(x - ax, z - az);
                    if (dist > r0 + 1.5) continue;
                    for (int y = bottom; y <= top; y++) {
                        double f = (y - bottom) / (double) (top - bottom);
                        // Narrow at the root, widening upward, its wall wandering a block.
                        double r = r0 * (0.3 + 0.7 * Math.pow(f, 0.8)) + noise3D(x + 611, y, z - 611, 6.0, 6.0);
                        if (dist > r) continue;
                        Block b = f > 0.65 ? ModBlocks.SERPENTINITE.get() : ModBlocks.PERIDOTITE.get();
                        if (f < 0.6 && grain(x, y, z, 0x61) < 0.006) b = y < 0 ? Blocks.DEEPSLATE_DIAMOND_ORE : Blocks.DIAMOND_ORE;
                        d.set(x, y, z, b, 6);
                    }
                }
            }
        });
    }

    private static final int MAG_CELL = 40, MAG_REACH = 14;
    private static final java.util.Set<Lithology.Rock> MAFIC = java.util.EnumSet.of(Lithology.Rock.GABBRO,
            Lithology.Rock.SERPENTINITE, Lithology.Rock.PERIDOTITE);

    /**
     * Magnetite. In gabbro it settles out of the cooling magma in layers -- seams of iron ore a block or two thick lying
     * flat through the rock, as in the Bushveld. In peridotite and serpentinite it comes of the serpentinisation
     * itself, scattered through a pod. Either way the ore keeps to its host: a seam stops where the gabbro does.
     */
    private static void magnetite(Deposit d) {
        if (!d.own) return;
        d.cells(MAG_CELL, MAG_REACH, 0x3A6EL, (cellX, cellZ, h) -> {
            if (die(h, 0) >= 0.55) return;
            int ax = cellX + (int) (die(h, 1) * MAG_CELL), az = cellZ + (int) (die(h, 2) * MAG_CELL);
            Lithology.Column col = d.column(ax, az);
            if (col == null) return;
            Lithology.Setting st = col.setting();
            if (st != Lithology.Setting.OCEAN_FLOOR && st != Lithology.Setting.HOTSPOT && st != Lithology.Setting.RIFT
                    && st != Lithology.Setting.ARC && st != Lithology.Setting.PRISM) return;
            int ground = d.base(ax, az);
            int ay = findHost(d, col, ax, az, ground, ground - 12 - (int) (die(h, 3) * 20), 100, MAFIC);
            if (ay == Integer.MIN_VALUE) return;
            Lithology.Rock host = Lithology.rockAt(d.rockSeed, col, ax, ay, az, ground);
            boolean seam = host == Lithology.Rock.GABBRO;
            double a = seam ? 8.0 + die(h, 4) * 6.0 : 3.0 + die(h, 4) * 1.5;
            double b = seam ? 5.0 + die(h, 5) * 4.0 : a;
            int thick = seam ? 1 + (int) (die(h, 6) * 2) : (int) Math.ceil(a);
            double turn = die(h, 7) * Math.PI;
            double cx = Math.cos(turn), sz = Math.sin(turn);
            int ext = (int) Math.ceil(a) + 1;
            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    double along = (x - ax) * cx + (z - az) * sz, across = -(x - ax) * sz + (z - az) * cx;
                    double flat = (along / a) * (along / a) + (across / b) * (across / b);
                    if (flat > 1.0) continue;
                    for (int dy = -thick; dy <= thick; dy++) {
                        int y = ay + dy;
                        if (seam ? dy < 0 || dy >= thick : flat + (dy / a) * (dy / a) > 1.0) continue;
                        double g = grain(x, y, z, 0x3A);
                        if (g >= (seam ? 0.75 : 0.25)) continue;
                        if (!MAFIC.contains(Lithology.rockAt(d.rockSeed, col, x, y, z, d.ground(x, z)))) continue;
                        d.set(x, y, z, y < 0 ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE, 5);
                    }
                }
            }
        });
    }

    private static final int CU_CELL = 36, CU_REACH = 12;
    private static final java.util.Set<Lithology.Rock> BASALTS = java.util.EnumSet.of(Lithology.Rock.BASALT,
            Lithology.Rock.SMOOTH_BASALT);

    /**
     * Native copper, the metal itself, filling the gas holes and cracks at the top of old basalt flows where the
     * fluids moving through a rift or over a hot spot dropped it -- the Keweenaw's copper. A flow top is flat, so the
     * copper lies in a sheet, sparse through it, and only in the basalt.
     */
    private static void nativeCopper(Deposit d) {
        if (!d.own) return;
        d.cells(CU_CELL, CU_REACH, 0xC0C0L, (cellX, cellZ, h) -> {
            if (die(h, 0) >= 0.5) return;
            int ax = cellX + (int) (die(h, 1) * CU_CELL), az = cellZ + (int) (die(h, 2) * CU_CELL);
            Lithology.Column col = d.column(ax, az);
            if (col == null) return;
            if (col.setting() != Lithology.Setting.RIFT && col.setting() != Lithology.Setting.HOTSPOT) return;
            int ground = d.base(ax, az);
            int ay = findHost(d, col, ax, az, ground, ground - 6 - (int) (die(h, 3) * 25), 60, BASALTS);
            if (ay == Integer.MIN_VALUE) return;
            double a = 10.0 + die(h, 4) * 6.0, b = 6.0 + die(h, 5) * 4.0;
            double turn = die(h, 6) * Math.PI;
            double cx = Math.cos(turn), sz = Math.sin(turn);
            int ext = (int) Math.ceil(a) + 1;
            for (int x = d.minX(ax - ext); x <= d.maxX(ax + ext); x++) {
                for (int z = d.minZ(az - ext); z <= d.maxZ(az + ext); z++) {
                    double along = (x - ax) * cx + (z - az) * sz, across = -(x - ax) * sz + (z - az) * cx;
                    if ((along / a) * (along / a) + (across / b) * (across / b) > 1.0) continue;
                    for (int y = ay; y <= ay + 1; y++) {
                        if (grain(x, y, z, 0xC0) >= 0.14) continue;
                        if (!BASALTS.contains(Lithology.rockAt(d.rockSeed, col, x, y, z, d.ground(x, z)))) continue;
                        d.set(x, y, z, y < 0 ? Blocks.DEEPSLATE_COPPER_ORE : Blocks.COPPER_ORE, 4);
                    }
                }
            }
        });
    }

    /** Natural rock a deposit may replace. Ore already there, the generator's or another deposit's, stays. */
    // === Other mods' ores =========================================================

    /**
     * The metals other mods add that the geology lays itself when {@code geologicalModOres} is on (their own ore
     * features for these are taken out; see {@code OreUnification}), by the names of Forge's ore tags.
     */
    public static final java.util.Set<String> MOD_METALS = java.util.Set.of("tin", "tungsten", "lithium", "lead", "zinc",
            "silver", "fluorite", "nickel", "aluminum", "chromium", "platinum", "osmium", "iridium", "titanium", "monazite",
            "thorium", "salt", "potassiumchloride", "saltpeter", "nitrate", "uranium");

    /** A pack's ore of a metal for a depth, or null where no mod adds it. */
    private static Block modOre(String metal, int y) {
        return com.jeladastudios.ftsgeology.compat.OreUnification.oreOf(metal, y < 0);
    }

    /** One of weighted metals, by a 0..1 draw. */
    private static String pick(double u, String[] metals, double[] weights) {
        double sum = 0;
        for (double w : weights) sum += w;
        double at = u * sum;
        for (int i = 0; i < metals.length; i++) {
            at -= weights[i];
            if (at <= 0) return metals[i];
        }
        return metals[metals.length - 1];
    }

    /** A metal's ore set at a block whose rock passes {@code host}, at least {@code cover} blocks under the ground. */
    private static void setOre(Deposit d, int x, int y, int z, String metal, int cover, java.util.function.Predicate<BlockState> host) {
        Block ore = modOre(metal, y);
        if (ore == null || !d.inside(x, z) || y <= d.level.getMinBuildHeight()) return;
        int g = d.ground(x, z);
        if (g == Integer.MIN_VALUE || y > g - cover) return;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = d.level.getBlockState(p);
        if (s.is(ore) || s.hasBlockEntity() || !host.test(s)) return;
        d.level.setBlock(p, ore.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        d.placed++;
    }

    private static boolean granite(BlockState s) {
        return s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(ModBlocks.RHYOLITE.get()) || s.is(ModBlocks.QUARTZITE.get())
                || s.is(ModBlocks.GNEISS.get()) || s.is(ModBlocks.SCHIST.get()) || s.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private static boolean carbonate(BlockState s) {
        return s.is(Blocks.CALCITE) || s.is(ModBlocks.MARBLE.get()) || s.is(ModBlocks.TRAVERTINE.get());
    }

    private static boolean ultramafic(BlockState s) {
        return s.is(ModBlocks.SERPENTINITE.get()) || s.is(ModBlocks.PERIDOTITE.get()) || s.is(ModBlocks.GABBRO.get());
    }

    private static boolean sediment(BlockState s) {
        return s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.STONE)
                || s.is(ModBlocks.SHALE.get()) || s.is(BlockTags.SAND) || s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL);
    }

    /** Every deposit of other mods' metals the geology lays; on the own world type, with the setting on. */
    private static void modOres(Deposit d) {
        if (!d.own || !com.jeladastudios.ftsgeology.compat.OreUnification.geological()) return;
        for (int i = 0; i < MOD_DEPOSITS.size(); i++) {
            int was = d.placed;
            MOD_DEPOSITS.get(i).accept(d);
            MOD_PLACED[i].add(d.placed - was);
        }
    }

    private static final List<java.util.function.Consumer<Deposit>> MOD_DEPOSITS = List.of(OreGenesis::greisens,
            OreGenesis::mvtPods, OreGenesis::laterites, OreGenesis::chromitites, OreGenesis::placers, OreGenesis::evaporites,
            OreGenesis::rollFronts);
    private static final String[] MOD_DEPOSIT_NAMES = {"greisen", "MVT", "laterite", "chromitite", "placer", "evaporite",
            "roll front"};
    private static final java.util.concurrent.atomic.LongAdder[] MOD_PLACED = new java.util.concurrent.atomic.LongAdder[7];
    static {
        for (int i = 0; i < MOD_PLACED.length; i++) MOD_PLACED[i] = new java.util.concurrent.atomic.LongAdder();
    }

    /** Other mods' ore blocks the geology has laid since the server started, by deposit; null if none. */
    public static String modOresSummary() {
        StringBuilder out = new StringBuilder("Mod ores laid by the geology:");
        long all = 0;
        for (int i = 0; i < MOD_PLACED.length; i++) {
            long n = MOD_PLACED[i].sumThenReset();
            all += n;
            out.append(' ').append(MOD_DEPOSIT_NAMES[i]).append(' ').append(n).append(i + 1 < MOD_PLACED.length ? "," : "");
        }
        return all == 0 ? null : out.toString();
    }

    private static final int GREISEN_CELL = 96, GREISEN_REACH = 14;

    /**
     * Greisen at the roof of a granite body: the last of the magma's water, rich in tin, tungsten and lithium,
     * forcing its way up the cooling roof in sheets of veins -- Cornwall's tin, the Erzgebirge's. Where a column has a
     * granite pluton, a cupola at its top holds a stockwork of close-set parallel veins.
     */
    private static void greisens(Deposit d) {
        String[] metals = {"tin", "tungsten", "lithium"};
        double[] weights = {0.6, 0.25, 0.15};
        d.cells(GREISEN_CELL, GREISEN_REACH, 0x6E15L, (cellX, cellZ, h) -> {
            if (die(h, 0) > 0.55) return;
            int ax = cellX + (int) (die(h, 1) * GREISEN_CELL), az = cellZ + (int) (die(h, 2) * GREISEN_CELL);
            Lithology.Column col = d.column(ax, az);
            if (col == null || !Lithology.hasPluton(col) || col.pluton() != Lithology.Rock.GRANITE) return;
            int top = d.base(ax, az) - col.plutonTop();
            int r = 6 + (int) (die(h, 3) * 7);
            double nx = die(h, 4) - 0.5, nz = die(h, 5) - 0.5, ny = 0.3 * (die(h, 6) - 0.5);
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double ux = nx / len, uy = ny / len, uz = nz / len;
            for (int x = d.minX(ax - r); x <= d.maxX(ax + r); x++) {
                for (int z = d.minZ(az - r); z <= d.maxZ(az + r); z++) {
                    for (int y = top - 8; y <= top + 5; y++) {
                        double dx = x - ax, dy = (y - top) * 1.6, dz = z - az;
                        if (dx * dx + dy * dy + dz * dz > r * r) continue;
                        double along = dx * ux + dy * uy + dz * uz;
                        if (Math.floorMod((int) Math.floor(along), 3) != 0 || grain(x, y, z, 0x61) > 0.55) continue;
                        setOre(d, x, y, z, pick(grain(x, y, z, 0x62), metals, weights), 6, OreGenesis::granite);
                    }
                }
            }
        });
    }

    private static final int MVT_CELL = 80, MVT_REACH = 12;

    /**
     * Mississippi Valley-type deposits: basin brines that sank through platform limestone and dropped lead, zinc and
     * fluorite in the caverns and breccias they dissolved -- the Tri-State district, Pine Point. Pods in the
     * carbonate beds of platforms and forelands, some tens of blocks down.
     */
    private static void mvtPods(Deposit d) {
        String[] metals = {"zinc", "lead", "fluorite", "silver"};
        double[] weights = {0.45, 0.35, 0.15, 0.05};
        d.cells(MVT_CELL, MVT_REACH, 0x3171L, (cellX, cellZ, h) -> {
            if (die(h, 0) > 0.6) return;
            int ax = cellX + (int) (die(h, 1) * MVT_CELL), az = cellZ + (int) (die(h, 2) * MVT_CELL);
            Lithology.Column col = d.column(ax, az);
            if (col == null || (col.setting() != Lithology.Setting.PLATFORM && col.setting() != Lithology.Setting.FORELAND)) return;
            // In a carbonate bed: the first marble or limestone some way down, where the brines found it.
            int g = d.base(ax, az), found = Integer.MIN_VALUE;
            for (int y = g - 8; y > g - 90; y--) {
                Lithology.Rock r = Lithology.rockAt(d.rockSeed, col, ax, y, az, g);
                if (r == Lithology.Rock.MARBLE || r == Lithology.Rock.CALCITE) {
                    found = y;
                    break;
                }
            }
            if (found == Integer.MIN_VALUE) return;
            int cy = found - 2;
            int rx = 7 + (int) (die(h, 4) * 6), rz = 7 + (int) (die(h, 5) * 6), ry = 3 + (int) (die(h, 6) * 3);
            for (int x = d.minX(ax - rx); x <= d.maxX(ax + rx); x++) {
                for (int z = d.minZ(az - rz); z <= d.maxZ(az + rz); z++) {
                    for (int y = cy - ry; y <= cy + ry; y++) {
                        double ex = (x - ax) / (double) rx, ey = (y - cy) / (double) ry, ez = (z - az) / (double) rz;
                        if (ex * ex + ey * ey + ez * ez > 1.0 + 0.3 * noise3D(x, y, z, 5.0, 5.0) || grain(x, y, z, 0x71) > 0.35) continue;
                        setOre(d, x, y, z, pick(grain(x, y, z, 0x72), metals, weights), 8, OreGenesis::carbonate);
                    }
                }
            }
        });
    }

    /**
     * Laterite: under hot, wet uplands the rock rots to its least soluble part, and what is left a few blocks down is
     * bauxite, aluminium's ore -- or, over peridotite, the nickel of New Caledonia. A blanket two or three blocks
     * thick in patches under the soil, where the climate is hot and wet enough.
     */
    private static void laterites(Deposit d) {
        BlockPos mid = new BlockPos(d.x0 + 8, 64, d.z0 + 8);
        net.minecraft.world.level.biome.Biome b = d.level.getBiome(mid).value();
        if (b.getBaseTemperature() < 0.9 || b.getModifiedClimateSettings().downfall() < 0.6) return;
        for (int x = d.x0; x < d.x0 + 16; x++) {
            for (int z = d.z0; z < d.z0 + 16; z++) {
                if (noise(x + 5521, z - 5521, 40.0) < 0.5) continue;
                int g = d.ground(x, z);
                if (g == Integer.MIN_VALUE || g < d.level.getSeaLevel() + 4) continue;
                Lithology.Column col = d.column(x, z);
                Lithology.Rock under = col == null ? Lithology.Rock.STONE : Lithology.rockAt(d.rockSeed, col, x, g - 12, z, g);
                String metal = under == Lithology.Rock.PERIDOTITE || under == Lithology.Rock.SERPENTINITE ? "nickel" : "aluminum";
                for (int y = g - 4; y <= g - 2; y++) {
                    if (grain(x, y, z, 0x81) > 0.5) continue;
                    setOre(d, x, y, z, metal, 2, s -> sediment(s) || s.is(BlockTags.BASE_STONE_OVERWORLD) || ultramafic(s));
                }
            }
        }
    }

    private static final int CHROMITE_CELL = 64, CHROMITE_REACH = 8;

    /**
     * Podiform chromitite: lenses of chromite settled out in the mantle rock of an ocean floor now thrust onto land
     * (an ophiolite: Oman's, Cyprus's, Turkey's Guleman), with the platinum metals -- platinum, osmium, iridium --
     * caught in it. Pods in serpentinite and peridotite.
     */
    private static void chromitites(Deposit d) {
        String[] metals = {"chromium", "platinum", "osmium", "iridium"};
        double[] weights = {0.9, 0.05, 0.03, 0.02};
        d.cells(CHROMITE_CELL, CHROMITE_REACH, 0x4C12L, (cellX, cellZ, h) -> {
            int ax = cellX + (int) (die(h, 1) * CHROMITE_CELL), az = cellZ + (int) (die(h, 2) * CHROMITE_CELL);
            Lithology.Column col = d.column(ax, az);
            if (col == null) return;
            int g = d.base(ax, az), cy = g - 15 - (int) (die(h, 3) * 40);
            Lithology.Rock host = Lithology.rockAt(d.rockSeed, col, ax, cy, az, g);
            if (host != Lithology.Rock.SERPENTINITE && host != Lithology.Rock.PERIDOTITE) return;
            boolean alongX = die(h, 4) < 0.5;
            int rx = alongX ? 6 : 3, rz = alongX ? 3 : 6, ry = 2;
            for (int x = d.minX(ax - rx); x <= d.maxX(ax + rx); x++) {
                for (int z = d.minZ(az - rz); z <= d.maxZ(az + rz); z++) {
                    for (int y = cy - ry; y <= cy + ry; y++) {
                        double ex = (x - ax) / (double) rx, ey = (y - cy) / (double) ry, ez = (z - az) / (double) rz;
                        if (ex * ex + ey * ey + ez * ez > 1.0 || grain(x, y, z, 0x91) > 0.5) continue;
                        setOre(d, x, y, z, pick(grain(x, y, z, 0x92), metals, weights), 6, OreGenesis::ultramafic);
                    }
                }
            }
        });
    }

    /**
     * Placers: waves winnow a beach and leave its heaviest grains behind, dark bands of ilmenite and rutile
     * (titanium) and of monazite with its thorium -- Kerala's black sands, Western Australia's. In old beach sand now
     * sandstone, a couple of blocks under the shore.
     */
    private static void placers(Deposit d) {
        int sea = d.level.getSeaLevel();
        String[] metals = {"titanium", "monazite", "thorium", "tin"};
        double[] weights = {0.6, 0.25, 0.1, 0.05};
        for (int x = d.x0; x < d.x0 + 16; x++) {
            for (int z = d.z0; z < d.z0 + 16; z++) {
                if (noise(x - 3307, z + 3307, 24.0) < 0.6) continue;
                int g = d.ground(x, z);
                if (g == Integer.MIN_VALUE || g < sea - 3 || g > sea + 2) continue;
                BlockState top = d.level.getBlockState(new BlockPos(x, g, z));
                if (!top.is(BlockTags.SAND)) continue;
                for (int y = g - 3; y <= g - 2; y++) {
                    if (grain(x, y, z, 0xA1) > 0.3) continue;
                    setOre(d, x, y, z, pick(grain(x, y, z, 0xA2), metals, weights), 2,
                            s -> s.is(Blocks.SANDSTONE) || s.is(BlockTags.SAND) || s.is(Blocks.STONE));
                }
            }
        }
    }

    /**
     * Evaporites: in a basin with no way out under a desert sky, the water goes up and its salt stays -- halite in
     * thick beds, potash (sylvite) on top of it, the last to crystallise, and saltpetre in the crust near the
     * surface, as in the Atacama.
     */
    private static void evaporites(Deposit d) {
        BlockPos mid = new BlockPos(d.x0 + 8, 64, d.z0 + 8);
        net.minecraft.world.level.biome.Biome b = d.level.getBiome(mid).value();
        if (b.getBaseTemperature() < 1.5 || b.getModifiedClimateSettings().downfall() > 0.15) return;
        for (int x = d.x0; x < d.x0 + 16; x++) {
            for (int z = d.z0; z < d.z0 + 16; z++) {
                if (noise(x + 7703, z + 7703, 64.0) < 0.6) continue;
                int g = d.ground(x, z);
                if (g == Integer.MIN_VALUE) continue;
                for (int y = g - 9; y <= g - 1; y++) {
                    int depth = g - y;
                    String metal = depth >= 6 ? "salt" : depth == 5 ? "potassiumchloride" : depth <= 2 ? "saltpeter" : null;
                    if (metal == null) continue;
                    double keep = metal.equals("salt") ? 0.6 : metal.equals("potassiumchloride") ? 0.4 : 0.12;
                    if (grain(x, y, z, 0xB1) > keep) continue;
                    if (metal.equals("saltpeter") && modOre("saltpeter", y) == null) metal = "nitrate";
                    setOre(d, x, y, z, metal, 1, OreGenesis::sediment);
                }
            }
        }
    }

    private static final int ROLL_CELL = 96, ROLL_REACH = 16;

    /**
     * Roll fronts: oxygenated groundwater carries uranium down a sandstone bed until it meets reducing ground, where the
     * uranium drops out along a curved front -- the ores of Wyoming and Kazakhstan. A crescent in the sandstone beds
     * of a platform or foreland.
     */
    private static void rollFronts(Deposit d) {
        d.cells(ROLL_CELL, ROLL_REACH, 0x55A7L, (cellX, cellZ, h) -> {
            if (die(h, 0) > 0.5) return;
            int ax = cellX + (int) (die(h, 1) * ROLL_CELL), az = cellZ + (int) (die(h, 2) * ROLL_CELL);
            Lithology.Column col = d.column(ax, az);
            if (col == null || (col.setting() != Lithology.Setting.PLATFORM && col.setting() != Lithology.Setting.FORELAND)) return;
            int g = d.base(ax, az), found = Integer.MIN_VALUE;
            for (int y = g - 10; y > g - 60; y -= 2) {
                Lithology.Rock r = Lithology.rockAt(d.rockSeed, col, ax, y, az, g);
                if (r == Lithology.Rock.SANDSTONE || r == Lithology.Rock.RED_BEDS) {
                    found = y;
                    break;
                }
            }
            if (found == Integer.MIN_VALUE) return;
            int cy = found;
            double facing = die(h, 3) * Math.PI * 2;
            int radius = 8 + (int) (die(h, 4) * 7);
            for (int x = d.minX(ax - radius - 2); x <= d.maxX(ax + radius + 2); x++) {
                for (int z = d.minZ(az - radius - 2); z <= d.maxZ(az + radius + 2); z++) {
                    double dx = x - ax, dz = z - az, dist = Math.sqrt(dx * dx + dz * dz);
                    if (Math.abs(dist - radius) > 1.5) continue;
                    double a = Math.atan2(dz, dx) - facing;
                    if (Math.cos(a) < 0.1) continue;                       // the front's half, the side it rolls towards
                    for (int y = cy - 1; y <= cy + 1; y++) {
                        if (grain(x, y, z, 0xC1) > 0.4) continue;
                        setOre(d, x, y, z, "uranium", 8, s -> s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                                || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.STONE));
                    }
                }
            }
        });
    }

    static boolean isHostRock(BlockState s) {
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
