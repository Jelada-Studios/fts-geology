package com.jeladastudios.ftsgeology.gas;

import net.minecraft.nbt.CompoundTag;

/**
 * A closed, rigid gas volume (pipe segment, tank, machine buffer). Pressure follows the
 * ideal gas law at 20 \u00B0C: P = n / (V \u00B7 41.57 mol/m\u00B3\u00B7atm).
 */
public class GasTank {
    public final GasMix gas = new GasMix();
    public final double volume;
    public final double maxPressure;
    private Runnable onChange;

    public GasTank(double volume, double maxPressure) {
        this.volume = volume;
        this.maxPressure = maxPressure;
    }

    public GasTank onChange(Runnable r) {
        this.onChange = r;
        return this;
    }

    public void changed() {
        if (onChange != null) onChange.run();
    }

    public double pressure() {
        return gas.pressure(volume);
    }

    public double total() {
        return gas.total();
    }

    /** Moles this tank holds at the given pressure. */
    public double molesAt(double pressureAtm) {
        return pressureAtm * volume * Gas.MOL_PER_BLOCK;
    }

    /** How many moles can still be pushed in before reaching {@code pressureAtm}. */
    public double roomUntil(double pressureAtm) {
        return Math.max(0, molesAt(pressureAtm) - gas.total());
    }

    public double room() {
        return roomUntil(maxPressure);
    }

    /** Inserts as much of {@code mix} as fits under max pressure; the rest stays in {@code mix}. */
    public double insert(GasMix mix) {
        double moles = Math.min(mix.total(), room());
        if (moles > 0) {
            mix.moveTo(gas, moles);
            changed();
        }
        return moles;
    }

    public GasMix extract(double moles) {
        GasMix out = gas.take(moles);
        if (!out.isEmpty()) changed();
        return out;
    }

    /**
     * Pressure-driven flow between two connected volumes. {@code rate} (0..0.5) is the share of
     * the pressure imbalance removed per call; a small mixing term blends compositions too.
     */
    public static void equalize(GasTank a, GasTank b, double rate, double mixing) {
        double ta = a.gas.total(), tb = b.gas.total();
        double n = ta + tb;
        if (n <= 1e-9) return;
        double targetA = n * a.volume / (a.volume + b.volume);
        double delta = (ta - targetA) * rate;
        boolean moved = false;
        if (delta > 1e-9) {
            a.gas.moveTo(b.gas, delta);
            moved = true;
        } else if (delta < -1e-9) {
            b.gas.moveTo(a.gas, -delta);
            moved = true;
        }
        if (mixing > 0) {
            ta = a.gas.total();
            tb = b.gas.total();
            if (ta > 1e-6 && tb > 1e-6) {
                double k = mixing * Math.min(ta, tb);
                for (int i = 0; i < Gas.COUNT; i++) {
                    double d = k * (a.gas.m[i] / ta - b.gas.m[i] / tb);
                    if (d > 0) d = Math.min(d, a.gas.m[i]);
                    else d = -Math.min(-d, b.gas.m[i]);
                    if (Math.abs(d) > 1e-12) {
                        a.gas.m[i] -= d;
                        b.gas.m[i] += d;
                        moved = true;
                    }
                }
            }
        }
        if (moved) {
            a.changed();
            b.changed();
        }
    }

    public CompoundTag save() {
        return gas.save();
    }

    public void load(CompoundTag tag) {
        gas.read(tag);
    }
}
