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

    /**
     * How far under its own raw ground a channel may cut, in blocks.
     *
     * <p>Without it the cut ended in a cliff. {@code floorAt} shaves the hillside down to a couple of blocks over
     * the water and then stops dead, so the face left standing at the edge of the shave is as tall as whatever
     * happened to be there -- fifty-eight blocks where it was measured, running the whole length of the river,
     * and picked out in bare rock because the wall is a cliff by construction. The same subtraction is behind
     * the jump between two traces at a confluence and the quarter-wide stair down the wall, so one clamp binds
     * all three to the same number.</p>
     *
     * <p>It cannot close a channel: {@code cut} holds the water a block under the lower of the two banks, so at
     * least one side always stands about a block over the water and is cut to its full depth. The high side is
     * pinched instead, which is what a river against a hillside looks like.</p>
     */
    private static final double MAX_SHAVE = 12.0;

    /**
     * How far under its raw ground a lake may cut, in blocks. A lake fills the hollow it stands in; given a channel's
     * twelve blocks, every disc of it dug a round basin into the hillside beside the hollow and the lake came out as
     * a string of blobs.
     */
    private static final double LAKE_SHAVE = 2.0;

    /** Raw ground under this lies under the sea's water; a channel cuts no more than a block into it. */
    private static final double SEA_FLOOR = 62.0, SEA_SCOUR = 1.0;

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
                if (y == Double.MAX_VALUE) yield NONE_HIGH;
                // The raw ground is a flat cache the chunk filled when it was built, and the router hands the same
                // function to both this and the offset's min, so reading it here is an array lookup.
                double ground = 128.0 + 128.0 * raw.compute(ctx);
                // Where the trace runs through a hill it is allowed a gorge: the full cut down the middle, stepping
                // back to the ordinary shave up its walls, so the hill is cut through instead of left standing.
                double shave = RiverNetwork.shaveAt(x, z, MAX_SHAVE, LAKE_SHAVE);
                // Under the sea the water is there already: the channel only scours the floor, where a full cut left
                // a trench under the waves whose walls stood up out of the bed in steps.
                if (ground < SEA_FLOOR) shave = Math.min(shave, SEA_SCOUR);
                yield (Math.max(y, ground - shave) - 128.0) / 128.0;
            }
            case NEAR -> RiverNetwork.near(x, z);
        };
    }

    @Override
    public void fillArray(double[] out, ContextProvider ctx) {
        ctx.fillAllDirectly(out, this);
    }

    /**
     * The router is wired here. The first wiring of a server hands the raw ground over, so that a trace reads the
     * ground the chunks will be built from; every later one, a chunk binding its caches, only binds this copy's.
     */
    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Only the floor carries the ground: the near field reads nothing but the finished network, so walking the
        // whole raw offset tree for it would be a second copy of the same work on every chunk the generator builds.
        if (mode != Mode.FLOOR) return visitor.apply(this);
        DensityFunction wired = raw.mapAll(visitor);
        RawGround.offer(wired);
        return visitor.apply(new RiverDensity(wired, mode));
    }

    @Override
    public double minValue() {
        // The clamp can answer a shave under the raw ground, so the declared range has to reach there.
        return mode == Mode.NEAR ? 0.0 : Math.min(FLOOR_LOW, raw.minValue() - MAX_SHAVE / 128.0);
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
