package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.block.WeatherInstrumentBlock;
import com.jeladastudios.ftsgeology.block.WeatherInstrumentBlock.Instrument;
import com.jeladastudios.ftsgeology.hydrology.SoilWater;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.weather.Meteorology;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A weather station's terminal: it reads the instruments set round it (within {@link #REACH} blocks), keeps what they
 * read every half hour for a day, and works out the forecast and the advice for the fields. What it can tell depends on
 * what it has: without a barometer no forecast at all, with a barometer alone a short, rough one, and with the wind,
 * the humidity and the temperature as well the days ahead; the fields' advice wants a soil probe.
 */
public class WeatherTerminalBlockEntity extends BlockEntity {

    public static final int REACH = 12, SLOTS = 48;
    /** Game ticks in half an hour. */
    private static final int HALF_HOUR = 500;

    public enum Series { PRESSURE, TEMPERATURE, HUMIDITY, WIND, RAIN }

    private final float[][] history = new float[Series.values().length][SLOTS];
    private long lastSlot = Long.MIN_VALUE;

    public WeatherTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WEATHER_TERMINAL.get(), pos, state);
        for (float[] s : history) java.util.Arrays.fill(s, Float.NaN);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WeatherTerminalBlockEntity be) {
        if (level.getGameTime() % 20 != 0 || !(level instanceof ServerLevel sl)) return;
        long slot = Math.floorDiv(level.getGameTime(), HALF_HOUR);
        if (slot == be.lastSlot) return;
        // Half hours missed (the chunk unloaded) are left blank.
        long missed = be.lastSlot == Long.MIN_VALUE ? 1 : Math.min(SLOTS, slot - be.lastSlot);
        for (long i = 0; i < missed; i++) {
            for (float[] s : be.history) {
                System.arraycopy(s, 1, s, 0, SLOTS - 1);
                s[SLOTS - 1] = Float.NaN;
            }
        }
        be.lastSlot = slot;
        be.record(sl);
        be.setChanged();
    }

    /** The instruments within reach, the nearest of each kind. */
    public Map<Instrument, BlockPos> instruments(Level level) {
        Map<Instrument, BlockPos> found = new EnumMap<>(Instrument.class);
        Map<Instrument, Double> near = new EnumMap<>(Instrument.class);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    m.set(worldPosition.getX() + dx, worldPosition.getY() + dy, worldPosition.getZ() + dz);
                    if (!(level.getBlockState(m).getBlock() instanceof WeatherInstrumentBlock b)) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < near.getOrDefault(b.instrument(), Double.MAX_VALUE)) {
                        near.put(b.instrument(), d);
                        found.put(b.instrument(), m.immutable());
                    }
                }
            }
        }
        return found;
    }

    private void record(ServerLevel level) {
        Map<Instrument, BlockPos> at = instruments(level);
        BlockPos p;
        if ((p = at.get(Instrument.BAROMETER)) != null) history[Series.PRESSURE.ordinal()][SLOTS - 1] = (float) Meteorology.seaLevelPressure(level, p);
        if ((p = at.get(Instrument.THERMOMETER)) != null) history[Series.TEMPERATURE.ordinal()][SLOTS - 1] = (float) Meteorology.temperature(level, p);
        if ((p = at.get(Instrument.HYGROMETER)) != null) history[Series.HUMIDITY.ordinal()][SLOTS - 1] = (float) (Meteorology.humidity(level, p) * 100);
        if ((p = at.get(Instrument.ANEMOMETER)) != null) history[Series.WIND.ordinal()][SLOTS - 1] = (float) Meteorology.knots(Meteorology.wind(level, p)[0]);
        if ((p = at.get(Instrument.RAIN_GAUGE)) != null) history[Series.RAIN.ordinal()][SLOTS - 1] = (float) (Meteorology.rainRate(level, p) * 0.5);
    }

    /**
     * Everything the terminal's screen shows: the instruments it has, their readings now, the day's record, the
     * forecast and the advice for the fields.
     */
    public CompoundTag data(ServerLevel level) {
        CompoundTag t = new CompoundTag();
        Map<Instrument, BlockPos> at = instruments(level);
        int has = 0;
        for (Instrument i : at.keySet()) has |= 1 << i.ordinal();
        t.putInt("Has", has);
        t.putLong("Slot", lastSlot);
        t.putLong("DayTime", level.getDayTime());
        CompoundTag now = new CompoundTag();
        BlockPos p;
        if ((p = at.get(Instrument.BAROMETER)) != null) {
            now.putFloat("Pressure", (float) Meteorology.seaLevelPressure(level, p));
            now.putFloat("Station", (float) Meteorology.stationPressure(level, p));
            now.putFloat("Tendency", (float) Meteorology.tendency(level, p));
        }
        if ((p = at.get(Instrument.THERMOMETER)) != null) now.putFloat("Temperature", (float) Meteorology.temperature(level, p));
        if ((p = at.get(Instrument.HYGROMETER)) != null) now.putFloat("Humidity", (float) (Meteorology.humidity(level, p) * 100));
        if ((p = at.get(Instrument.ANEMOMETER)) != null) {
            double[] w = Meteorology.wind(level, p);
            now.putFloat("Wind", (float) Meteorology.knots(w[0]));
            now.putFloat("WindFrom", (float) w[1]);
        }
        if ((p = at.get(Instrument.RAIN_GAUGE)) != null) {
            now.putFloat("RainRate", (float) Meteorology.rainRate(level, p));
            if (level.getBlockEntity(p) instanceof InstrumentBlockEntity be) now.putFloat("RainToday", (float) be.rainToday());
        }
        SoilWater.Reading soil = null;
        if ((p = at.get(Instrument.SOIL_PROBE)) != null) {
            soil = SoilWater.at(level, p.getX(), p.getZ());
            if (soil != null) {
                now.putFloat("SoilWater", (float) soil.rootAvailable());
                now.putFloat("SoilSaturation", (float) soil.rootSat());
                now.putString("Soil", soil.soil().name().toLowerCase(Locale.ROOT));
                now.putInt("Table", Math.max(0, soil.depth()));
            }
        }
        t.put("Now", now);
        CompoundTag hist = new CompoundTag();
        for (Series s : Series.values()) {
            ListTag l = new ListTag();
            for (float v : history[s.ordinal()]) l.add(net.minecraft.nbt.FloatTag.valueOf(v));
            hist.put(s.name(), l);
        }
        t.put("History", hist);
        // The forecast: what the station sees depends on what it has.
        List<Meteorology.Period> fc = List.of();
        if (at.containsKey(Instrument.BAROMETER)) {
            int seen = (at.containsKey(Instrument.ANEMOMETER) ? 1 : 0) + (at.containsKey(Instrument.HYGROMETER) ? 1 : 0)
                    + (at.containsKey(Instrument.THERMOMETER) ? 1 : 0);
            double sight = 0.35 + 0.2 * seen;
            int count = seen == 3 ? 12 : seen >= 1 ? 8 : 4;
            fc = Meteorology.forecast(level, at.get(Instrument.BAROMETER), count, 6, sight);
            ListTag l = new ListTag();
            for (Meteorology.Period q : fc) {
                CompoundTag c = new CompoundTag();
                c.putInt("From", q.fromHour());
                c.putFloat("Rain", q.rain());
                c.putFloat("Heavy", q.heavy());
                c.putFloat("Thunder", q.thunder());
                c.putFloat("Cold", q.coldest());
                c.putFloat("Warm", q.warmest());
                c.putFloat("Wind", q.windKnots());
                c.putFloat("Pressure", q.pressure());
                l.add(c);
            }
            t.put("Forecast", l);
            t.putBoolean("Temps", at.containsKey(Instrument.THERMOMETER));
            t.putBoolean("Winds", at.containsKey(Instrument.ANEMOMETER));
        }
        t.put("Advice", advice(now, fc, soil != null, at.containsKey(Instrument.THERMOMETER)));
        return t;
    }

    /** The fields' advice: to water or to wait, frost and heat, a dry spell, a storm, a dry day for the harvest. */
    private static ListTag advice(CompoundTag now, List<Meteorology.Period> fc, boolean probe, boolean thermometer) {
        ListTag out = new ListTag();
        if (fc.isEmpty()) {
            out.add(StringTag.valueOf("no_barometer"));
            return out;
        }
        double dry24 = 1, dry72 = 1, heavy24 = 0, thunder12 = 0, cold24 = Double.MAX_VALUE, warm24 = -Double.MAX_VALUE;
        for (Meteorology.Period q : fc) {
            if (q.fromHour() < 24) {
                dry24 *= 1 - q.rain();
                heavy24 = Math.max(heavy24, q.heavy());
                cold24 = Math.min(cold24, q.coldest());
                warm24 = Math.max(warm24, q.warmest());
            }
            if (q.fromHour() < 12) thunder12 = Math.max(thunder12, q.thunder());
            dry72 *= 1 - q.rain();
        }
        double rain24 = 1 - dry24, rain72 = 1 - dry72;
        if (thunder12 > 0.4) out.add(StringTag.valueOf("storm"));
        if (probe && now.contains("SoilWater")) {
            double water = now.getFloat("SoilWater");
            if (water < 0.35 && rain24 < 0.4) out.add(StringTag.valueOf("irrigate"));
            else if (water < 0.5 && rain24 >= 0.6) out.add(StringTag.valueOf("wait_for_rain"));
            else if (water > 0.9 && heavy24 > 0.4) out.add(StringTag.valueOf("waterlogged"));
            else out.add(StringTag.valueOf("soil_fine"));
            if (rain72 < 0.2 && water < 0.5) out.add(StringTag.valueOf("drought"));
        } else {
            out.add(StringTag.valueOf("no_probe"));
        }
        if (thermometer && cold24 < 0.5) out.add(StringTag.valueOf("frost"));
        if (thermometer && warm24 > 32) out.add(StringTag.valueOf("heat"));
        if (rain24 < 0.25 && heavy24 < 0.1) out.add(StringTag.valueOf("dry_window"));
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("Slot", lastSlot);
        for (Series s : Series.values()) {
            int[] bits = new int[SLOTS];
            for (int i = 0; i < SLOTS; i++) bits[i] = Float.floatToIntBits(history[s.ordinal()][i]);
            tag.putIntArray(s.name(), bits);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        lastSlot = tag.contains("Slot") ? tag.getLong("Slot") : Long.MIN_VALUE;
        for (Series s : Series.values()) {
            int[] bits = tag.getIntArray(s.name());
            for (int i = 0; i < SLOTS; i++) history[s.ordinal()][i] = i < bits.length ? Float.intBitsToFloat(bits[i]) : Float.NaN;
        }
    }
}
