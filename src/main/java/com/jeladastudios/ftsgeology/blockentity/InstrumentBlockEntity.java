package com.jeladastudios.ftsgeology.blockentity;

import com.jeladastudios.ftsgeology.block.WeatherInstrumentBlock;
import com.jeladastudios.ftsgeology.registry.ModBlockEntities;
import com.jeladastudios.ftsgeology.weather.LocalWeather;
import com.jeladastudios.ftsgeology.weather.Meteorology;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A weather instrument's workings: every five seconds its comparator signal is brought up to date; a rain gauge adds up
 * the day's rain (from dawn to dawn); and an anemometer's cups spin with the wind, on the client, for the eye.
 */
public class InstrumentBlockEntity extends BlockEntity {

    /** The day's rain in a gauge, millimetres, and which day it is for. */
    private double rainToday;
    private long day = -1;
    /** The anemometer's cups: their angle, and how fast they turn, degrees a tick. */
    public float spin, spinO, spinSpeed;

    public InstrumentBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INSTRUMENT.get(), pos, state);
    }

    public double rainToday() {
        return rainToday;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, InstrumentBlockEntity be) {
        if (level.getGameTime() % 100 != 0 || !(level instanceof ServerLevel sl)) return;
        if (state.getBlock() instanceof WeatherInstrumentBlock b && b.instrument() == WeatherInstrumentBlock.Instrument.RAIN_GAUGE) {
            // A day runs from dawn to dawn: the gauge is read and emptied then.
            long today = Math.floorDiv(sl.getDayTime(), 24000L);
            if (today != be.day) {
                be.day = today;
                be.rainToday = 0;
            }
            // Five seconds is a twelfth of a game hour.
            be.rainToday += Meteorology.rainRate(sl, pos) * 100 / 1000.0;
            be.setChanged();
        }
        level.updateNeighbourForOutputSignal(pos, state.getBlock());
    }

    /** The cups spin up with the wind the player feels, and run down slowly when it drops. */
    public static void clientTick(Level level, BlockPos pos, BlockState state, InstrumentBlockEntity be) {
        float wind = Mth.sqrt(LocalWeather.windX() * LocalWeather.windX() + LocalWeather.windZ() * LocalWeather.windZ());
        boolean open = level.canSeeSky(pos.above());
        float target = open ? Math.min(40f, wind * 6f) : 0f;
        be.spinSpeed += (target - be.spinSpeed) * (target > be.spinSpeed ? 0.05f : 0.02f);
        be.spinO = be.spin;
        be.spin = (be.spin + be.spinSpeed) % 360f;
        if (be.spinO > be.spin) be.spinO -= 360f;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("RainToday", rainToday);
        tag.putLong("Day", day);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        rainToday = tag.getDouble("RainToday");
        day = tag.contains("Day") ? tag.getLong("Day") : -1;
    }
}
