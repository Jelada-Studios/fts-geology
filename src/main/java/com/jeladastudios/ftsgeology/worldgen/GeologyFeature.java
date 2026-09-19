package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.mojang.serialization.Codec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The deep geology and its ore, written while a chunk is being generated instead of afterwards.
 *
 * <h2>Why this exists</h2>
 * Everything the mod did to the ground used to happen after a chunk had finished generating, from
 * the retrogen queue. That is the only way to reach a world that existed before the mod did, but it
 * is the expensive way for every chunk made since: each write goes through the live chunk, so it pays
 * for lighting, for being sent to whoever can see it, for a share of the server tick, and for any
 * other mod that hooks block changes. The last one was GitHub #1 - a physics mod running a query on
 * every rock this swapped.
 *
 * <p>Here the same writes land in a chunk that is still being built, none of that applies, and the
 * work runs on a world generation thread instead of the server one. Retrogen is told the chunk is
 * done so it never goes over it again, and a chunk from before the mod was installed still gets its
 * geology the old way.</p>
 *
 * <p>Runs in the underground ores step. Caves are already carved by then, so the rock shows in their
 * walls, and a mineshaft's planks are already down, so the player-block guard leaves them alone.</p>
 */
public class GeologyFeature extends Feature<NoneFeatureConfiguration> {

    public GeologyFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos cp = new ChunkPos(context.origin());
        int placed = 0, ore = 0;
        try {
            if (!GeyserConfig.GEOLOGY_AT_GENERATION.get()) return false;
            long t0 = System.nanoTime();
            DeepStructure.Report report = new DeepStructure.Report();
            // In the mod's own world type the rock went down with the ground (LithologyRule); this is for worlds made
            // without it.
            if (!com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level.getLevel())) {
                DeepStructure.generate(level, cp, report);
            }
            placed = report.blocks;
            long t1 = System.nanoTime();
            // The seed retrogen uses, so a ridge comes out the same whichever path built it.
            RandomSource rng = RandomSource.create(
                    level.getSeed() ^ (((long) cp.x) << 32 | (cp.z & 0xFFFFFFFFL)));
            OceanicRidge.generate(level, cp, rng);
            long t2 = System.nanoTime();
            ore = OreGenesis.generate(level, cp);
            long t3 = System.nanoTime();
            placed += LavaTubes.generate(level, cp);
            long t4 = System.nanoTime();
            RiverWater.generate(level, cp);
            long t5 = System.nanoTime();
            GenCost.add(GenCost.DEEP, t1 - t0);
            GenCost.add(GenCost.RIDGE, t2 - t1);
            GenCost.add(GenCost.ORE, t3 - t2);
            GenCost.add(GenCost.TUBES, t4 - t3);
            GenCost.add(GenCost.RIVER, t5 - t4);
            GenCost.chunkDone();
        } catch (RuntimeException e) {
            // Never take world generation down with it. Left unmarked, the chunk simply gets its
            // geology from retrogen once it has loaded.
            GeysersMod.LOGGER.warn("Geology at generation failed for chunk {}: {}", cp, e.toString());
            return false;
        }
        RetrogenHandler.markDeepCurrent(level.getLevel().dimension(), cp, placed, ore);
        return true;
    }
}
