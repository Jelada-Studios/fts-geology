package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.volcano.VolcanoBuilder;
import com.jeladastudios.ftsgeology.volcano.VolcanoField;
import com.mojang.serialization.Codec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Raises this chunk's share of any large volcano that reaches it, while the chunk is generated.
 *
 * <p>See {@link VolcanoField} for where they go and why this is not a structure. The body of the
 * mountain is written here, column by column, reading nothing outside the chunk; the summit, the core
 * and the plumbing need a live world and are finished once the centre chunk has loaded.</p>
 *
 * <p>Runs in the underground decoration step: after the deep geology and the ore, so those see the
 * ground as it was and not the inside of a mountain, and before trees and plants, so nothing grows
 * where the rock is about to go.</p>
 */
public class VolcanoFieldFeature extends Feature<NoneFeatureConfiguration> {

    public VolcanoFieldFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos cp = new ChunkPos(context.origin());
        try {
            if (!GeyserConfig.LARGE_VOLCANOES.get()) return false;
            boolean any = false;
            long t0 = System.nanoTime();
            for (VolcanoField.Site site : VolcanoField.sitesTouching(level.getLevel(), cp)) {
                any |= VolcanoBuilder.generateFieldChunk(level, context.chunkGenerator(), cp, site) > 0;
            }
            long t1 = System.nanoTime();
            if (GeyserConfig.OCEAN_VOLCANOES.get()) any |= SeamountField.generate(level, cp) > 0;
            GenCost.add(GenCost.VOLCANO, t1 - t0);
            GenCost.add(GenCost.SEAMOUNT, System.nanoTime() - t1);
            return any;
        } catch (RuntimeException e) {
            // Never take world generation down with it; the chunk just goes without its slope.
            GeysersMod.LOGGER.warn("Large volcano failed for chunk {}: {}", cp, e.toString());
            return false;
        }
    }
}
