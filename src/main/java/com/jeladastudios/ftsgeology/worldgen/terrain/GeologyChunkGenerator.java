package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The noise generator, with one thing added: a village does not stand in a river.
 *
 * <p>A village is laid out before the river water goes in, from pieces that know nothing of the channel under them,
 * so houses and paths ended up in the middle of a river. Here, once a village has been laid out, every piece of it
 * that touches a channel or a lake is taken away before anything is built; where the piece it grows from -- the
 * square in the middle -- is itself in the water, there is no village at all. Only the mod's own world types use
 * this generator, and only worlds made after it: a world keeps the generator it was made with.</p>
 */
public final class GeologyChunkGenerator extends NoiseBasedChunkGenerator {

    public static final Codec<GeologyChunkGenerator> CODEC = RecordCodecBuilder.create(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings)
    ).apply(i, i.stable(GeologyChunkGenerator::new)));

    /** How far from a piece the water may come, in blocks: a path along a bank is fine, one on it is not. */
    private static final int MARGIN = 2;
    /** Blocks between the columns a piece is checked at. */
    private static final int STEP = 4;
    /** Ground under this lies under the sea's water: the ocean fills to the block under sea level. */
    private static final double SEA_DRY = 62.0;

    private static final LongAdder VILLAGES = new LongAdder(), PIECES = new LongAdder(), DROPPED = new LongAdder(),
            GONE = new LongAdder();

    public GeologyChunkGenerator(BiomeSource biomes, Holder<NoiseGeneratorSettings> settings) {
        super(biomes, settings);
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /**
     * Set up as the world opens, before a chunk or a biome is asked for: the river network is handed this generator
     * here, to read the sea off the terrain ({@link RawGround#wet}).
     */
    @Override
    public ChunkGeneratorStructureState createState(net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> sets,
                                                     net.minecraft.world.level.levelgen.RandomState state, long seed) {
        RawGround.bindTerrain(this, state);
        return super.createState(sets, state, seed);
    }

    /** How deep under a river's bed, and how far out past its edge, the carvers are kept off. */
    private static final int GUARD_UNDER = 12, GUARD_SIDE = 3;

    /**
     * Keeps the carvers out from under the rivers. A canyon carver cut across a river's channel left the water laid
     * over it with nothing under it: the river stopped at the canyon's lip on one side and started again on the other,
     * with water hanging down the wall between. Each column in or just beside a channel or a lake is marked as already
     * carved from a little under its bed to over its water, so a carver passing through leaves it standing; a canyon
     * meets the river as a bridge of rock under it, and a cave passes deeper.
     */
    @Override
    public void applyCarvers(net.minecraft.server.level.WorldGenRegion region, long seed,
                             net.minecraft.world.level.levelgen.RandomState random,
                             net.minecraft.world.level.biome.BiomeManager biomes, StructureManager structures,
                             ChunkAccess chunk, net.minecraft.world.level.levelgen.GenerationStep.Carving step) {
        if (chunk instanceof net.minecraft.world.level.chunk.ProtoChunk proto && RiverNetwork.ready()
                && com.jeladastudios.ftsgeology.config.GeyserConfig.RIVERS.get()
                && !com.jeladastudios.ftsgeology.compat.tfc.TfcCompat.active()) {
            net.minecraft.world.level.chunk.CarvingMask mask = proto.getOrCreateCarvingMask(step);
            int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
            int bottom = chunk.getMinBuildHeight(), top = chunk.getMaxBuildHeight() - 1;
            double side = GUARD_SIDE * RiverNetwork.horizontal();
            int under = (int) Math.round(GUARD_UNDER * RiverNetwork.horizontal());
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    RiverNetwork.At a = RiverNetwork.at(minX + dx, minZ + dz);
                    if (a.distance() == Double.MAX_VALUE) continue;
                    if (!a.lake() && a.distance() > a.halfWidth() + side) continue;
                    int from = Math.max(bottom, (int) Math.floor(a.bed()) - under);
                    int to = Math.min(top, (int) Math.ceil(a.water()) + 2 * under);
                    for (int y = from; y <= to; y++) mask.set(dx, y, dz);
                    GUARDED.increment();
                }
            }
        }
        super.applyCarvers(region, seed, random, biomes, structures, chunk, step);
    }

    private static final LongAdder GUARDED = new LongAdder();

    @Override
    public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state,
                                 StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) {
        super.createStructures(registries, state, structures, chunk, templates);
        if (!RiverNetwork.ready()) return;
        Registry<Structure> all = registries.registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, StructureStart> e : new ArrayList<>(chunk.getAllStarts().entrySet())) {
            Structure structure = e.getKey();
            StructureStart start = e.getValue();
            if (!start.isValid() || !settlement(all.wrapAsHolder(structure))) continue;
            List<StructurePiece> pieces = start.getPieces();
            if (pieces.isEmpty()) continue;
            VILLAGES.increment();
            if (wet(pieces.get(0).getBoundingBox())) {
                chunk.setStartForStructure(structure, StructureStart.INVALID_START);
                GONE.increment();
                tell(start, pieces.size(), pieces.size(), true);
                continue;
            }
            List<StructurePiece> keep = new ArrayList<>(pieces.size());
            for (StructurePiece piece : pieces) {
                PIECES.increment();
                if (wet(piece.getBoundingBox())) DROPPED.increment();
                else keep.add(piece);
            }
            if (keep.size() != pieces.size()) {
                chunk.setStartForStructure(structure, new StructureStart(structure, start.getChunkPos(),
                        start.getReferences(), new PiecesContainer(keep)));
                tell(start, pieces.size() - keep.size(), pieces.size(), false);
            }
        }
    }

    /**
     * Whether a structure is a settlement laid out over the ground: a village, or anything built the way a village is --
     * pieces joined on the surface, the ground banked up under them -- as an outpost or another mod's walled town is,
     * which the village tag does not name.
     */
    private static boolean settlement(Holder<Structure> holder) {
        if (holder.is(StructureTags.VILLAGE)) return true;
        if (holder.unwrapKey().map(k -> k.location().getPath().contains("village")).orElse(false)) return true;
        Structure s = holder.value();
        return s.type() == StructureType.JIGSAW && s.step() == GenerationStep.Decoration.SURFACE_STRUCTURES
                && s.terrainAdaptation() == TerrainAdjustment.BEARD_THIN;
    }

    /**
     * Whether water reaches a piece -- a channel, its banks or a lake within a couple of blocks of it, or the sea under it
     * -- or a sinkhole opens under it.
     */
    private static boolean wet(BoundingBox box) {
        int x0 = box.minX() - MARGIN, x1 = box.maxX() + MARGIN, z0 = box.minZ() - MARGIN, z1 = box.maxZ() + MARGIN;
        for (int x = x0; ; x = Math.min(x + STEP, x1)) {
            for (int z = z0; ; z = Math.min(z + STEP, z1)) {
                RiverNetwork.At a = RiverNetwork.at(x, z);
                // The water stands out to where the cut wall climbs through it, a block and a half past the bed.
                if (a.distance() != Double.MAX_VALUE && a.distance() <= a.halfWidth() + 1.5) return true;
                // Nor on the sea: a piece laid over ground the sea covers is built on a foundation the generator
                // raises out of the water, and at a river's mouth that foundation shut the river off from the sea.
                if (RawGround.heightAt(x, z) < SEA_DRY) return true;
                // Nor in a sinkhole, which is cut after the village is laid out.
                if (com.jeladastudios.ftsgeology.worldgen.Dolines.covering(x, z) != null) return true;
                if (z == z1) break;
            }
            if (x == x1) break;
        }
        return false;
    }

    /** How the villages have fared so far, for the river log line. */
    public static String summary() {
        return String.format(Locale.ROOT, "%d villages laid out, %d of their %d pieces in the water taken away, "
                + "%d not built; %d columns kept from the carvers", VILLAGES.sum(), DROPPED.sum(), PIECES.sum(), GONE.sum(), GUARDED.sum());
    }

    /** The first hundred villages the water changed, each with where it is, so one can be gone and looked at. */
    private static final AtomicInteger TOLD = new AtomicInteger();

    private static void tell(StructureStart start, int taken, int of, boolean gone) {
        if (TOLD.incrementAndGet() > 100) return;
        BoundingBox b = start.getBoundingBox();
        com.jeladastudios.ftsgeology.util.Diagnostics.info("Village at {} {}: {}; so far {}", (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2,
                gone ? "its middle is in the water, not built" : taken + " of its " + of + " pieces in the water taken away",
                summary());
    }
}
