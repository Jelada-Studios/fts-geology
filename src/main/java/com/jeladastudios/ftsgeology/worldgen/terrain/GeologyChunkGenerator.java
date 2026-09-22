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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
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

    @Override
    public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state,
                                 StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) {
        super.createStructures(registries, state, structures, chunk, templates);
        if (!RiverNetwork.ready()) return;
        Registry<Structure> all = registries.registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, StructureStart> e : new ArrayList<>(chunk.getAllStarts().entrySet())) {
            Structure structure = e.getKey();
            StructureStart start = e.getValue();
            if (!start.isValid() || !all.wrapAsHolder(structure).is(StructureTags.VILLAGE)) continue;
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

    /** Whether water reaches a piece: a channel, its banks or a lake within a couple of blocks of it, or the sea under it. */
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
                if (z == z1) break;
            }
            if (x == x1) break;
        }
        return false;
    }

    /** How the villages have fared so far, for the river log line. */
    public static String summary() {
        return String.format(Locale.ROOT, "%d villages laid out, %d of their %d pieces in the water taken away, "
                + "%d not built", VILLAGES.sum(), DROPPED.sum(), PIECES.sum(), GONE.sum());
    }

    /** The first hundred villages the water changed, each with where it is, so one can be gone and looked at. */
    private static final AtomicInteger TOLD = new AtomicInteger();

    private static void tell(StructureStart start, int taken, int of, boolean gone) {
        if (TOLD.incrementAndGet() > 100) return;
        BoundingBox b = start.getBoundingBox();
        GeysersMod.LOGGER.info("Village at {} {}: {}; so far {}", (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2,
                gone ? "its middle is in the water, not built" : taken + " of its " + of + " pieces in the water taken away",
                summary());
    }
}
