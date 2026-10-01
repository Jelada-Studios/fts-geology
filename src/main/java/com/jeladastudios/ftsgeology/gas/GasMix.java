package com.jeladastudios.ftsgeology.gas;

import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;

/**
 * An amount of gas: moles of every {@link Gas} species. Used for world cells (1 m\u00B3 each),
 * pipe/tank contents and streams moving between them.
 */
public final class GasMix {
    public final double[] m = new double[Gas.COUNT];
    /** As a world cell: sweeps in a row it hardly changed (it sleeps after a few), and the game time it was last simulated. */
    public int quiet;
    public long simulatedAt;

    public GasMix() {
    }

    public GasMix(GasMix other) {
        System.arraycopy(other.m, 0, m, 0, Gas.COUNT);
    }

    /** Normal air, {@code moles} in total. */
    public static GasMix air(double moles) {
        GasMix g = new GasMix();
        g.setAir(moles);
        return g;
    }

    public void setAir(double moles) {
        Arrays.fill(m, 0);
        m[Gas.O2] = moles * Gas.AIR_O2;
        m[Gas.N2] = moles * Gas.AIR_N2;
    }

    public GasMix copy() {
        return new GasMix(this);
    }

    public void set(GasMix other) {
        System.arraycopy(other.m, 0, m, 0, Gas.COUNT);
    }

    public void clear() {
        Arrays.fill(m, 0);
    }

    public double get(Gas g) {
        return m[g.ordinal()];
    }

    public void add(Gas g, double moles) {
        int i = g.ordinal();
        m[i] = Math.max(0, m[i] + moles);
    }

    public double total() {
        double t = 0;
        for (double v : m) t += v;
        return t;
    }

    public boolean isEmpty() {
        return total() < 1e-9;
    }

    /** Mole (= volume) fraction of a gas, 0..1. */
    public double fraction(Gas g) {
        double t = total();
        return t <= 1e-12 ? 0 : m[g.ordinal()] / t;
    }

    public double fraction(int i) {
        double t = total();
        return t <= 1e-12 ? 0 : m[i] / t;
    }

    /** Pressure in atm when this gas occupies {@code volume} m\u00B3 at 20 \u00B0C. */
    public double pressure(double volume) {
        return total() / (Gas.MOL_PER_BLOCK * volume);
    }

    /** Mean molar mass (g/mol). */
    public double molarMass() {
        double t = 0, w = 0;
        for (int i = 0; i < Gas.COUNT; i++) {
            t += m[i];
            w += m[i] * Gas.VALUES[i].molarMass;
        }
        return t <= 1e-12 ? Gas.AIR_MOLAR_MASS : w / t;
    }

    public void add(GasMix other) {
        for (int i = 0; i < Gas.COUNT; i++) m[i] += other.m[i];
    }

    public void scale(double f) {
        for (int i = 0; i < Gas.COUNT; i++) m[i] *= f;
    }

    /** Removes {@code moles} of this mixture (same composition) and returns it. */
    public GasMix take(double moles) {
        GasMix out = new GasMix();
        double t = total();
        if (t <= 1e-12 || moles <= 0) return out;
        double f = Math.min(1.0, moles / t);
        for (int i = 0; i < Gas.COUNT; i++) {
            double d = m[i] * f;
            out.m[i] = d;
            m[i] -= d;
        }
        return out;
    }

    /** Moves {@code moles} of this mixture into {@code dst}. */
    public void moveTo(GasMix dst, double moles) {
        double t = total();
        if (t <= 1e-12 || moles <= 0) return;
        double f = Math.min(1.0, moles / t);
        for (int i = 0; i < Gas.COUNT; i++) {
            double d = m[i] * f;
            m[i] -= d;
            dst.m[i] += d;
        }
    }

    /** Removes only the given species. */
    public double takeSpecies(Gas g, double moles) {
        int i = g.ordinal();
        double d = Math.min(m[i], Math.max(0, moles));
        m[i] -= d;
        return d;
    }

    /**
     * True if this cell is indistinguishable from ambient air and can be dropped: every gas is
     * below {@code scale} \u00D7 its trace level, oxygen is normal and so is the pressure.
     */
    public boolean isNearAir(double nominal, double scale) {
        double t = total();
        if (Math.abs(t - nominal) > nominal * 0.004 * scale) return false;
        for (int i = 0; i < Gas.COUNT; i++) {
            if (i == Gas.O2 || i == Gas.N2) continue;
            if (m[i] > t * Gas.VALUES[i].trace * scale) return false;
        }
        return Math.abs(m[Gas.O2] / t - Gas.AIR_O2) < Gas.OXYGEN.trace * scale;
    }

    /** The species that deviates most from normal air (for colouring/visualisation). */
    public Gas dominantAnomaly() {
        double t = total();
        if (t <= 1e-12) return null;
        Gas best = null;
        double bestScore = 0;
        for (int i = 0; i < Gas.COUNT; i++) {
            if (i == Gas.N2) continue;
            double x = m[i] / t;
            double score;
            if (i == Gas.O2) score = Math.max(0, Gas.AIR_O2 - x) * 0.5;
            else score = x * weightForVisual(i);
            if (score > bestScore) {
                bestScore = score;
                best = Gas.VALUES[i];
            }
        }
        return best;
    }

    private static double weightForVisual(int i) {
        // Toxic gases matter at ppm levels, so boost them for visualisation.
        if (i == Gas.CO) return 40;
        if (i == Gas.H2S || i == Gas.SO2) return 200;
        if (i == Gas.H2O) return 0.3;
        return 1;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (Gas g : Gas.VALUES) {
            double v = m[g.ordinal()];
            if (v > 1e-9) tag.putDouble(g.id, v);
        }
        return tag;
    }

    public static GasMix load(CompoundTag tag) {
        GasMix g = new GasMix();
        g.read(tag);
        return g;
    }

    public void read(CompoundTag tag) {
        clear();
        for (Gas g : Gas.VALUES) {
            if (tag.contains(g.id)) m[g.ordinal()] = Math.max(0, tag.getDouble(g.id));
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GasMix[");
        double t = total();
        sb.append(String.format("%.2f mol", t));
        for (Gas g : Gas.VALUES) {
            double v = m[g.ordinal()];
            if (v > 1e-6) sb.append(", ").append(Gas.asciiFormula(g)).append('=').append(String.format("%.4f", v / t));
        }
        return sb.append(']').toString();
    }
}
