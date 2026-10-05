package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.tectonics.GeologyParams;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.util.ValueNoise;
import com.jeladastudios.ftsgeology.worldgen.lithology.Lithology;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Sea cliffs where the coast is basalt: the raw ground, lifted where it stands just over the sea.
 *
 * <p>A coast of basalt -- an ocean island's crust, a mantle plume's flood lavas -- is cut by the waves into a cliff, the
 * lava standing in columns in its face: Giant's Causeway, Staffa, Reynisfjara, the Faroes. The ground the plates give a
 * coast comes up out of the sea gently, and a basalt cliff biome laid on it stood its columns on a beach. Along about half
 * of such a coast (a slow noise picks the stretches) the land just over the sea is lifted: by the most at the water, by
 * nothing a few times that height inland, so the shore becomes a face of a few blocks with a plateau behind it rising on
 * into the hills, and the sea floor is left as it was. The step is at the waterline, where the ground crosses the sea's
 * level, and the generator's interpolation leans it over a few blocks. How much of the coast is basalt fades to nothing
 * where the rock changes, so no wall stands inland at a change of rock. Only in worlds made since.</p>
 *
 * <p>It wraps the raw offset, so everything that reads the raw ground -- the rivers, which cut a gorge or fall over the
 * face to the sea, the biome source, the far view -- sees the cliffs.</p>
 */
public final class CoastCliffs implements DensityFunction {

    public static final MapCodec<CoastCliffs> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(f -> f.raw)
    ).apply(i, CoastCliffs::new));
    public static final KeyDispatchDataCodec<CoastCliffs> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private static final double SEA = 63.0;
    /** The cliff's height at the water, blocks, at the normal world's layout and per unit of layout past it. */
    private static final double RISE = 4.0, RISE_PER_LAYOUT = 2.4;
    /** How far over the sea, as a multiple of the cliff's height, the lift has faded out: three keeps the ground rising. */
    private static final double FADE = 3.0;
    /** How long the stretches of cliff and of open coast are, blocks at the normal layout. */
    private static final double STRETCH = 400.0;
    /** How near a continent an ocean plate's ground is still a margin, in fault widths: no cliff on the line. */
    private static final double MARGIN = 0.3;

    private final DensityFunction raw;

    public CoastCliffs(DensityFunction raw) {
        this.raw = raw;
    }

    @Override
    public double compute(FunctionContext ctx) {
        double v = raw.compute(ctx);
        if (!WorldgenRevision.has(WorldgenRevision.BASALT_COASTS)) return v;
        double ground = 128.0 + 128.0 * v;
        if (ground <= SEA) return v;
        GeologyParams p = TerrainContext.params();
        double rise = RISE + RISE_PER_LAYOUT * p.horizontal();
        double over = (ground - SEA) / (FADE * rise);
        if (over >= 1.0) return v;
        int x = ctx.blockX(), z = ctx.blockZ();
        double lift = rise * basalt(TerrainContext.seed(), p, x, z) * (1.0 - smooth(over));
        return lift <= 0.0 ? v : v + lift / 128.0;
    }

    /**
     * How fully a column's coast is a basalt cliff, 0 to 1: how much of it is basalt (an ocean plate's crust away from a
     * continent and from an island arc, a plume's lavas), times the stretch of coast the noise gives a cliff.
     */
    static double basalt(long seed, GeologyParams p, int x, int z) {
        double h = p.horizontal();
        double n = ValueNoise.noise(x + (int) (seed & 0xFFFF), z + (int) ((seed >>> 16) & 0xFFFF), STRETCH * h);
        double stretch = smooth(Mth.clamp((n + 0.1) / 0.4, 0.0, 1.0));
        if (stretch <= 0.0) return 0.0;
        Lithology.Column c = Lithology.column(seed, p, x, z);
        double rock;
        PlateSample s = TerrainFields.sampleAt(seed, p, x, z);
        if (s.plateKind().isOceanic()) {
            rock = c.setting() == Lithology.Setting.ARC ? 1.0 - c.weight() : 1.0;
            if (!s.neighbourKind().isOceanic()) rock *= smooth(Mth.clamp(TerrainFields.across(s, p) / MARGIN, 0.0, 1.0));
        } else {
            rock = c.setting() == Lithology.Setting.HOTSPOT ? c.weight() : 0.0;
        }
        return rock * stretch;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    @Override
    public void fillArray(double[] out, ContextProvider ctx) {
        ctx.fillAllDirectly(out, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new CoastCliffs(raw.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return raw.minValue();
    }

    @Override
    public double maxValue() {
        return raw.maxValue() + (RISE + RISE_PER_LAYOUT * 4.0) / 128.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
