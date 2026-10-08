package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The highest summits kept under the top of the world's ground, instead of cut flat against it.
 *
 * <p>The world's density fades to air over its last fifty blocks, so ground the offset put higher than that came out
 * as a table at the fade: Everest's top was a flat field of rock eighty blocks across at 870. The caves in the summit
 * had hidden it, eaten into a ring of spikes round a pit; with the summit kept solid ({@link SummitShield}) the table
 * stood bare. Two things keep a summit under it, both in the offset's own heights, in blocks:</p>
 * <ul>
 *   <li>A named mountain is laid on its ground smaller, all of it in the same measure, so its top comes to
 *   {@code landmark_top_y} at the most: its own shape, peak and ridges, only lower. Squeezed instead, as the rest is,
 *   Everest's top came out a dome, its last thirty blocks of ridge pressed into nine.</li>
 *   <li>Any other ground over {@code from_y} is drawn in towards {@code to_y}, which it never reaches: a hyperbolic
 *   tangent, level with the ground as it was at {@code from_y}, so nothing under it moves.</li>
 * </ul>
 * <p>Only in worlds made since {@link WorldgenRevision#SOFT_SUMMITS}.</p>
 */
public final class SummitCeiling implements DensityFunction {

    public static final MapCodec<SummitCeiling> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(f -> f.raw),
            Codec.INT.fieldOf("from_y").forGetter(f -> f.fromY),
            Codec.INT.fieldOf("to_y").forGetter(f -> f.toY),
            Codec.INT.fieldOf("landmark_top_y").forGetter(f -> f.landmarkTopY)
    ).apply(i, SummitCeiling::new));
    public static final KeyDispatchDataCodec<SummitCeiling> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final DensityFunction raw;
    private final int fromY, toY, landmarkTopY;

    public SummitCeiling(DensityFunction raw, int fromY, int toY, int landmarkTopY) {
        this.raw = raw;
        this.fromY = fromY;
        this.toY = toY;
        this.landmarkTopY = landmarkTopY;
    }

    @Override
    public double compute(FunctionContext ctx) {
        double v = raw.compute(ctx);
        if (!WorldgenRevision.has(WorldgenRevision.SOFT_SUMMITS)) return v;
        long seed = TerrainContext.seed();
        GeologyParams p = TerrainContext.params();
        int x = ctx.blockX(), z = ctx.blockZ();
        // The offset is the ground's height in the router's units: 128 blocks to one, the sea's level near 0.
        double named = TerrainFields.field(TerrainFields.Field.LANDMARK, seed, p, x, z) * 128.0;
        double ground = soften(128.0 + 128.0 * v - named);
        if (named > 0.0) {
            double top = TerrainFields.landmarkTop(seed, p, x, z);
            if (top > 0.0) named *= Math.max(0.0, Math.min(1.0, (landmarkTopY - ground) / top));
            ground += named;
        }
        return (ground - 128.0) / 128.0;
    }

    private double soften(double ground) {
        if (ground <= fromY) return ground;
        double room = toY - fromY;
        return fromY + room * Math.tanh((ground - fromY) / room);
    }

    @Override
    public void fillArray(double[] out, ContextProvider ctx) {
        ctx.fillAllDirectly(out, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new SummitCeiling(raw.mapAll(visitor), fromY, toY, landmarkTopY));
    }

    @Override
    public double minValue() {
        return raw.minValue();
    }

    @Override
    public double maxValue() {
        return raw.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
