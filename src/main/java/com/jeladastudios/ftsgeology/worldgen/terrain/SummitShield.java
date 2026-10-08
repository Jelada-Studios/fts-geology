package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * How far up a mountain the caves are kept shut: 0 below {@code from_y}, rising to 1 at
 * {@code to_y}. Added, ten times over, to every cave the router carves -- the caverns, the entrances, the spaghetti
 * and the noodles -- the way the rivers' shield is, so above the snow line a mountain is solid rock: no shaft opens in a
 * summit, and no cavern hollows a peak into a shell under it. A summit is frozen rock and ice, not a sponge; a pit
 * sunk into the top of Everest, over a cavern the size of the peak, looked wrong. The caves go on below the line.
 * Only in worlds made since {@link WorldgenRevision#SOLID_SUMMITS}.
 */
public record SummitShield(int fromY, int toY) implements DensityFunction.SimpleFunction {

    public static final MapCodec<SummitShield> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.fieldOf("from_y").forGetter(SummitShield::fromY),
            Codec.INT.fieldOf("to_y").forGetter(SummitShield::toY)
    ).apply(i, SummitShield::new));
    public static final KeyDispatchDataCodec<SummitShield> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        if (!WorldgenRevision.has(WorldgenRevision.SOLID_SUMMITS)) return 0.0;
        return Mth.clamp((ctx.blockY() - fromY) / (double) (toY - fromY), 0.0, 1.0);
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
