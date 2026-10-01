package com.jeladastudios.ftsgeology.block;

import com.jeladastudios.ftsgeology.blockentity.InstrumentBlockEntity;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.weather.Meteorology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * One instrument of a weather station, each its own block: a barometer, an anemometer, a hygrometer, a rain gauge, a
 * thermometer and a soil probe; or of a volcano observatory: a tiltmeter, a GPS station and a gas meter. Right-click one to read it; a comparator beside it reads it too, as a signal from 0 to
 * 15 over the instrument's range. Set round a station's terminal (see {@link WeatherTerminalBlock}), they are read and
 * kept by it. What each reads is {@link Meteorology}'s: an anemometer wants to stand clear and high, a rain gauge open to
 * the sky, a soil probe in the ground.
 */
public class WeatherInstrumentBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public enum Instrument {
        BAROMETER(Block.box(3, 0, 3, 13, 14, 13)),
        ANEMOMETER(Block.box(4, 0, 4, 12, 16, 12)),
        HYGROMETER(Block.box(4, 0, 4, 12, 12, 12)),
        RAIN_GAUGE(Block.box(4, 0, 4, 12, 14, 12)),
        THERMOMETER(Block.box(5, 0, 5, 11, 15, 11)),
        SOIL_PROBE(Block.box(6, 0, 6, 10, 8, 10)),
        // A volcano observatory's: see Observatory.
        TILTMETER(Block.box(4, 0, 4, 12, 6, 12)),
        GPS(Block.box(5, 0, 5, 11, 15, 11)),
        GAS_METER(Block.box(4, 0, 4, 12, 12, 12));

        final VoxelShape shape;

        Instrument(VoxelShape shape) {
            this.shape = shape;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Instrument instrument;

    public WeatherInstrumentBlock(Instrument instrument, Properties props) {
        super(props);
        this.instrument = instrument;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Instrument instrument() {
        return instrument;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext c) {
        return instrument.shape;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InstrumentBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide) {
            return createTickerHelper(type, ModBlockEntities.INSTRUMENT.get(), InstrumentBlockEntity::serverTick);
        }
        if (instrument == Instrument.ANEMOMETER && level.isClientSide) {
            return createTickerHelper(type, ModBlockEntities.INSTRUMENT.get(), InstrumentBlockEntity::clientTick);
        }
        return null;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        player.displayClientMessage(reading((ServerLevel) level, pos), true);
        return InteractionResult.CONSUME;
    }

    /** The instrument's reading, in a line. */
    public Component reading(ServerLevel level, BlockPos pos) {
        String k = "message.fts_geology.instrument." + instrument.key();
        return switch (instrument) {
            case BAROMETER -> {
                double t = Meteorology.tendency(level, pos);
                String trend = t < -1.5 ? "falling_fast" : t < -0.5 ? "falling" : t > 1.5 ? "rising_fast" : t > 0.5 ? "rising" : "steady";
                yield Component.translatable(k, fmt(Meteorology.stationPressure(level, pos)), fmt(Meteorology.seaLevelPressure(level, pos)),
                        Component.translatable("message.fts_geology.trend." + trend));
            }
            case ANEMOMETER -> {
                double[] w = Meteorology.wind(level, pos);
                yield Component.translatable(k, String.format(Locale.ROOT, "%.0f", Meteorology.knots(w[0])),
                        String.format(Locale.ROOT, "%.1f", w[0]), Component.translatable("message.fts_geology.compass." + compass(w[1])));
            }
            case HYGROMETER -> Component.translatable(k, String.format(Locale.ROOT, "%.0f", Meteorology.humidity(level, pos) * 100));
            case RAIN_GAUGE -> {
                double today = level.getBlockEntity(pos) instanceof InstrumentBlockEntity be ? be.rainToday() : 0;
                yield Component.translatable(k, String.format(Locale.ROOT, "%.1f", Meteorology.rainRate(level, pos)),
                        String.format(Locale.ROOT, "%.1f", today));
            }
            case THERMOMETER -> Component.translatable(k, String.format(Locale.ROOT, "%.1f", Meteorology.temperature(level, pos)));
            case SOIL_PROBE -> {
                var r = com.jeladastudios.ftsgeology.hydrology.SoilWater.at(level, pos.getX(), pos.getZ());
                yield r == null ? Component.translatable(k + ".none")
                        : Component.translatable(k, String.format(Locale.ROOT, "%.0f", r.rootAvailable() * 100),
                        Component.translatable("message.fts_geology.soil." + r.soil().name().toLowerCase(Locale.ROOT)),
                        String.format(Locale.ROOT, "%.1f", Math.max(0, r.depth())));
            }
            case TILTMETER, GPS, GAS_METER -> com.jeladastudios.ftsgeology.instrument.Observatory.reading(level, pos, instrument);
        };
    }

    /** The eight points of the compass, for a bearing in degrees from north. */
    public static String compass(double degrees) {
        String[] points = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return points[Math.floorMod((int) Math.round(degrees / 45.0), 8)];
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // --- Comparator -------------------------------------------------------------

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** The reading as a signal over the instrument's range: 970 to 1045 hPa, 0 to 45 knots, 0 to 100 %, and so on. */
    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return 0;
        double f = switch (instrument) {
            case BAROMETER -> (Meteorology.seaLevelPressure(sl, pos) - 970) / 75;
            case ANEMOMETER -> Meteorology.knots(Meteorology.wind(sl, pos)[0]) / 45;
            case HYGROMETER -> Meteorology.humidity(sl, pos);
            case RAIN_GAUGE -> Math.sqrt(Meteorology.rainRate(sl, pos) / 40);
            case THERMOMETER -> (Meteorology.temperature(sl, pos) + 20) / 60;
            case SOIL_PROBE -> {
                var r = com.jeladastudios.ftsgeology.hydrology.SoilWater.at(sl, pos.getX(), pos.getZ());
                yield r == null ? 0 : r.rootAvailable();
            }
            case TILTMETER, GPS, GAS_METER -> com.jeladastudios.ftsgeology.instrument.Observatory.signal(sl, pos, instrument);
        };
        return Mth.clamp((int) Math.round(f * 15), 0, 15);
    }
}
