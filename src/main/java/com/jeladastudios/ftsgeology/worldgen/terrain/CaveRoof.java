package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A roof over the caverns: 1 where the ground's density first lets a cavern in, fading to 0 a little deeper, added ten
 * times over to the cheese caverns the way the rivers' and the summits' shields are. Vanilla lets a cavern start where
 * the ground's density reaches its cave threshold, a few blocks under the surface; on a steep mountainside that is a
 * thin shell, the cave entrances broke through it, and a cavern twenty-five blocks deep lay open to the sky, a
 * flat-floored pit walled by cliffs, as if a slab of the mountain had been cut out. The entrances and the tunnels still
 * open at the ground; the caverns keep a roof. Only in worlds made since {@link WorldgenRevision#CAVE_ROOFS}.
 */
public final class CaveRoof implements DensityFunction {

    public static final MapCodec<CaveRoof> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(f -> f.ground),
            Codec.DOUBLE.fieldOf("from").forGetter(f -> f.from),
            Codec.DOUBLE.fieldOf("to").forGetter(f -> f.to)
    ).apply(i, CaveRoof::new));
    public static final KeyDispatchDataCodec<CaveRoof> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    /** The ground's density, rising with depth under the surface (vanilla's sloped cheese). */
    private final DensityFunction ground;
    /** Where the caverns may start, and where the roof is gone. */
    private final double from, to;

    public CaveRoof(DensityFunction ground, double from, double to) {
        this.ground = ground;
        this.from = from;
        this.to = to;
    }

    @Override
    public double compute(FunctionContext ctx) {
        if (!WorldgenRevision.has(WorldgenRevision.CAVE_ROOFS)) return 0.0;
        return Mth.clamp((to - ground.compute(ctx)) / (to - from), 0.0, 1.0);
    }

    @Override
    public void fillArray(double[] out, ContextProvider ctx) {
        ctx.fillAllDirectly(out, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new CaveRoof(ground.mapAll(visitor), from, to));
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
