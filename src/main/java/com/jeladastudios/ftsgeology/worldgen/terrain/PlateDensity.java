package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;

/**
 * The plate model as a density function, {@code fts_geology:plate} in the mod's noise settings: one instance
 * hands vanilla's noise router one of {@link TerrainFields}' four numbers. The model itself lives there; this is
 * only the adapter, so the terrain can be read without a world generator behind it.
 *
 * <p>JSON: {@code {"type": "fts_geology:plate", "field": "continents|erosion|ridges|relief|variety|belt|valley|crest|meander_x|meander_z",
 * "scale": 1.0}}.</p>
 */
public final class PlateDensity implements DensityFunction.SimpleFunction {

    public static final MapCodec<PlateDensity> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("field").forGetter(f -> f.field.name().toLowerCase(Locale.ROOT)),
            Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(f -> f.scale)
    ).apply(i, PlateDensity::new));
    public static final KeyDispatchDataCodec<PlateDensity> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final Field field;
    private final double scale;

    public PlateDensity(String field, double scale) {
        this.field = Field.valueOf(field.toUpperCase(Locale.ROOT));
        this.scale = scale;
    }

    @Override
    public double compute(FunctionContext ctx) {
        return TerrainFields.field(field, TerrainContext.seed(), TerrainContext.params(),
                ctx.blockX(), ctx.blockZ()) * scale;
    }

    @Override
    public double minValue() {
        return switch (field) {
            case CONTINENTS, EROSION -> -1.5 * Math.abs(scale);
            case RIDGES, VARIETY, BELT, VALLEY, CREST -> 0.0;
            case RELIEF -> -1.0 * Math.abs(scale);
            case MEANDER_X, MEANDER_Z -> -TerrainFields.MEANDER_REACH * Math.abs(scale);
        };
    }

    @Override
    public double maxValue() {
        return switch (field) {
            case CONTINENTS, EROSION -> 1.5 * Math.abs(scale);
            case RIDGES -> 2.0 * Math.abs(scale);
            case VARIETY, BELT, VALLEY, CREST -> 1.0 * Math.abs(scale);
            case RELIEF -> 1.5 * Math.abs(scale);
            case MEANDER_X, MEANDER_Z -> TerrainFields.MEANDER_REACH * Math.abs(scale);
        };
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
