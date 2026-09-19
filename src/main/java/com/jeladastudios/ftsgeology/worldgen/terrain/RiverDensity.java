package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;

/**
 * The rivers, as the offset sees them. The function is given the raw ground — the offset with no river cut into it —
 * and hands it to {@link RiverNetwork}, which traces the rivers down it. Three things come back:
 *
 * <ul>
 *   <li>{@code floor}: the channel's floor, for the offset to take the lower of;</li>
 *   <li>{@code rib}: the top of the rock bar between two pools, for the offset to take the higher of;</li>
 *   <li>{@code near}: 1 over a channel and its banks, for the caves to keep away from it.</li>
 * </ul>
 */
public final class RiverDensity implements DensityFunction {

    public enum Mode { FLOOR, RIB, NEAR }

    /** Offset units for "no river here": beyond anything the terrain reaches, either way. */
    private static final double NONE_HIGH = 4.0, NONE_LOW = -4.0;

    public static final MapCodec<RiverDensity> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(f -> f.raw),
            Codec.STRING.fieldOf("mode").forGetter(f -> f.mode.name().toLowerCase(Locale.ROOT))
    ).apply(i, RiverDensity::new));
    public static final KeyDispatchDataCodec<RiverDensity> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final DensityFunction raw;
    private final Mode mode;

    public RiverDensity(DensityFunction raw, String mode) {
        this(raw, Mode.valueOf(mode.toUpperCase(Locale.ROOT)));
    }

    private RiverDensity(DensityFunction raw, Mode mode) {
        this.raw = raw;
        this.mode = mode;
    }

    @Override
    public double compute(FunctionContext ctx) {
        if (!RiverNetwork.ready()) return mode == Mode.FLOOR ? NONE_HIGH : mode == Mode.RIB ? NONE_LOW : 0.0;
        int x = ctx.blockX(), z = ctx.blockZ();
        return switch (mode) {
            case FLOOR -> {
                double y = RiverNetwork.floorAt(x, z);
                yield y == Double.MAX_VALUE ? NONE_HIGH : (y - 128.0) / 128.0;
            }
            case RIB -> {
                double y = RiverNetwork.ribAt(x, z);
                yield y == Double.MIN_VALUE ? NONE_LOW : (y - 128.0) / 128.0;
            }
            case NEAR -> RiverNetwork.near(x, z);
        };
    }

    @Override
    public void fillArray(double[] out, ContextProvider ctx) {
        ctx.fillAllDirectly(out, this);
    }

    /**
     * The router is wired here: the raw ground comes back with its noises bound, and the network is handed it, so
     * that a trace reads the same ground the chunk will be built from.
     */
    @Override
    public DensityFunction mapAll(Visitor visitor) {
        DensityFunction wired = raw.mapAll(visitor);
        RiverDensity made = new RiverDensity(wired, mode);
        RiverNetwork.useGround((x, z) -> 128.0 + 128.0 * wired.compute(new SinglePointContext(x, 0, z)),
                TerrainContext.seed(), TerrainContext.params().horizontal());
        return visitor.apply(made);
    }

    @Override
    public double minValue() {
        return mode == Mode.NEAR ? 0.0 : NONE_LOW;
    }

    @Override
    public double maxValue() {
        return mode == Mode.NEAR ? 1.0 : NONE_HIGH;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
