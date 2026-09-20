package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.config.GeyserConfig;
import com.jeladastudios.ftsgeology.hydrology.RiverNetwork;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;

/**
 * The rivers, as the offset sees them. The function is given the raw ground — the offset with no river cut into it —
 * and hands it to {@link RiverNetwork}, which traces the rivers down it. Two things come back:
 *
 * <ul>
 *   <li>{@code floor}: the channel's floor, for the offset to take the lower of;</li>
 *   <li>{@code near}: 1 over a channel and its banks, for the caves to keep away from it.</li>
 * </ul>
 */
public final class RiverDensity implements DensityFunction {

    public enum Mode { FLOOR, NEAR }

    /**
     * Offset units for "no river here": beyond anything the terrain reaches.
     *
     * <p>Four was beyond the normal world, whose tallest crop lands at 1,23, and nowhere near beyond the tall one,
     * whose Manaslu crop reaches 4,72. The offset is {@code min(raw, river_floor)}, so four capped the tall world's
     * ground at y 640: a dead flat table with ninety blocks of summit sliced off it, and a knife edge round it
     * because {@code factor} takes a full ten wherever the cap bites. Eight is y 1152, over either ceiling.</p>
     */
    private static final double NONE_HIGH = 8.0;
    /** How far under the terrain a channel floor may ever lie, for the function to declare its range. */
    private static final double FLOOR_LOW = -4.0;

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
        if (!RiverNetwork.ready()) return mode == Mode.FLOOR ? NONE_HIGH : 0.0;
        int x = ctx.blockX(), z = ctx.blockZ();
        return switch (mode) {
            case FLOOR -> {
                double y = RiverNetwork.floorAt(x, z);
                yield y == Double.MAX_VALUE ? NONE_HIGH : (y - 128.0) / 128.0;
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
        // Only the floor carries the ground: the near field reads nothing but the finished network, so walking the
        // whole raw offset tree for it would be a second copy of the same work on every chunk the generator builds.
        if (mode != Mode.FLOOR) return visitor.apply(this);
        // Switched off, the ground is never handed over, so the network never opens: no channel is cut into the
        // offset, no cave is pushed away from one and no water is laid. The world keeps vanilla's rivers and
        // nothing else -- which is the point of the switch, since half a river is worse than none.
        if (!GeyserConfig.RIVERS.get()) return visitor.apply(new RiverDensity(raw.mapAll(visitor), mode));
        DensityFunction wired = raw.mapAll(visitor);
        RiverDensity made = new RiverDensity(wired, mode);
        RiverNetwork.useGround((x, z) -> 128.0 + 128.0 * wired.compute(new SinglePointContext(x, 0, z)),
                TerrainContext.seed(), TerrainContext.params().horizontal());
        return visitor.apply(made);
    }

    @Override
    public double minValue() {
        return mode == Mode.NEAR ? 0.0 : FLOOR_LOW;
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
