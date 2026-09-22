package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.mojang.serialization.Codec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Geothermal ground and soil colour, painted while a chunk is generated: fumarole fields, basin
 * floors and the soil over named rock.
 *
 * <p>Runs in the top layer step, after trees and plants, so it sees the ground the retrogen pass
 * used to see. Every write stays in its own chunk and every die comes from the column, so the result
 * does not depend on which chunk generates first. Retrogen is told, and skips the painting.</p>
 */
public class GeologySurfaceFeature extends Feature<NoneFeatureConfiguration> {

    public GeologySurfaceFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!GeyserConfig.GEOLOGY_AT_GENERATION.get()) return false;
        WorldGenLevel level = context.level();
        ChunkPos cp = new ChunkPos(context.origin());
        try {
            long t0 = System.nanoTime();
            HotspotSigns.generate(level, cp);
            long t1 = System.nanoTime();
            GeothermalBasin.generate(level, cp);
            long t2 = System.nanoTime();
            SoilProfile.generate(level, cp);
            RiftSteps.generate(level, cp);
            SnowCover.generate(level, cp);
            long t3 = System.nanoTime();
            GenCost.add(GenCost.SIGNS, t1 - t0);
            GenCost.add(GenCost.BASIN, t2 - t1);
            GenCost.add(GenCost.SOIL, t3 - t2);
        } catch (RuntimeException e) {
            // Left unmarked, the chunk is painted by retrogen once it has loaded.
            GeysersMod.LOGGER.warn("Surface paint at generation failed for chunk {}: {}", cp, e.toString());
            return false;
        }
        RetrogenHandler.markPaintCurrent(level.getLevel().dimension(), cp);
        return true;
    }
}
