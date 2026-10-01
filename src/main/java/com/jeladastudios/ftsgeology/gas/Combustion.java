package com.jeladastudios.ftsgeology.gas;

/**
 * Combustion chemistry.
 *
 * <pre>
 *  CH\u2084 + 2 O\u2082   \u2192 CO\u2082 + 2 H\u2082O     \u0394H = -802 kJ
 *  CH\u2084 + 1.5 O\u2082 \u2192 CO  + 2 H\u2082O     \u0394H = -519 kJ   (oxygen-starved)
 *  2 H\u2082 + O\u2082    \u2192 2 H\u2082O           \u0394H = -242 kJ / mol H\u2082
 *  2 CO + O\u2082    \u2192 2 CO\u2082           \u0394H = -283 kJ / mol CO
 *  2 H\u2082S + 3 O\u2082 \u2192 2 SO\u2082 + 2 H\u2082O   \u0394H = -518 kJ / mol H\u2082S
 * </pre>
 *
 * Mixture flammability limits use Le Chatelier's mixing rule.
 */
public final class Combustion {
    private Combustion() {
    }

    /** Oxygen needed for complete combustion, per mole of each fuel. */
    public static double o2Demand(Gas g) {
        return switch (g) {
            case METHANE -> 2.0;
            case HYDROGEN, CARBON_MONOXIDE -> 0.5;
            case HYDROGEN_SULFIDE -> 1.5;
            default -> 0;
        };
    }

    public static double fuelFraction(GasMix mix) {
        double t = mix.total();
        if (t <= 1e-12) return 0;
        double f = 0;
        for (Gas g : Gas.FUELS) f += mix.m[g.ordinal()];
        return f / t;
    }

    public static double fuelMoles(GasMix mix) {
        double f = 0;
        for (Gas g : Gas.FUELS) f += mix.m[g.ordinal()];
        return f;
    }

    /** Lower flammability limit of the fuel blend (Le Chatelier), or 1 if there is no fuel. */
    public static double lfl(GasMix mix) {
        double fuel = fuelMoles(mix);
        if (fuel <= 1e-12) return 1;
        double inv = 0;
        for (Gas g : Gas.FUELS) inv += (mix.m[g.ordinal()] / fuel) / g.lfl;
        return 1.0 / inv;
    }

    public static double ufl(GasMix mix) {
        double fuel = fuelMoles(mix);
        if (fuel <= 1e-12) return 0;
        double inv = 0;
        for (Gas g : Gas.FUELS) inv += (mix.m[g.ordinal()] / fuel) / g.ufl;
        return 1.0 / inv;
    }

    /** Limiting oxygen concentration of the fuel blend (mole-weighted). */
    public static double loc(GasMix mix) {
        double fuel = fuelMoles(mix);
        if (fuel <= 1e-12) return 1;
        double l = 0;
        for (Gas g : Gas.FUELS) l += (mix.m[g.ordinal()] / fuel) * g.loc;
        return l;
    }

    /** Detector-style reading: fuel concentration as percentage of the lower explosive limit. */
    public static double percentLel(GasMix mix) {
        double f = fuelFraction(mix);
        if (f <= 0) return 0;
        return f / lfl(mix) * 100.0;
    }

    /** True if a spark in this mixture starts a self-propagating flame (premixed, within limits). */
    public static boolean isFlammable(GasMix mix) {
        double t = mix.total();
        if (t <= 1e-9) return false;
        double f = fuelFraction(mix);
        if (f <= 0) return false;
        if (f < lfl(mix) || f > ufl(mix)) return false;
        return mix.m[Gas.O2] / t >= loc(mix);
    }

    /**
     * True if a stream of this gas leaving a nozzle into air keeps a (diffusion) flame alive:
     * it just has to be richer than the lower limit.
     */
    public static boolean canSustainJet(GasMix stream) {
        double f = fuelFraction(stream);
        return f > 0 && f >= lfl(stream);
    }

    /** Relative flame speed of the fuel blend (CH\u2084 = 1, H\u2082 = 4). */
    public static double flameSpeed(GasMix mix) {
        double fuel = fuelMoles(mix);
        if (fuel <= 1e-12) return 1;
        double s = 0;
        for (Gas g : Gas.FUELS) s += (mix.m[g.ordinal()] / fuel) * g.flameSpeed;
        return s;
    }

    public static double violence(GasMix mix) {
        double fuel = fuelMoles(mix);
        if (fuel <= 1e-12) return 1;
        double s = 0;
        for (Gas g : Gas.FUELS) s += (mix.m[g.ordinal()] / fuel) * g.violence;
        return s;
    }

    /** O\u2082 needed to burn all fuel completely. */
    public static double o2Required(GasMix mix) {
        double r = 0;
        for (Gas g : Gas.FUELS) r += mix.m[g.ordinal()] * o2Demand(g);
        return r;
    }

    /** Full-combustion energy content (kJ). */
    public static double heatContent(GasMix mix) {
        double e = 0;
        for (Gas g : Gas.FUELS) e += mix.m[g.ordinal()] * g.lhv;
        return e;
    }

    /**
     * Burns the mixture in place using the oxygen it contains. Oxygen-starved mixtures burn
     * incompletely and leave carbon monoxide (and unburnt fuel).
     *
     * @return released heat in kJ
     */
    public static double burn(GasMix mix) {
        double[] m = mix.m;
        double o2 = m[Gas.O2];
        double energy = 0;

        // Stage A: fast oxidation of every fuel (methane only as far as CO).
        double ch4 = m[Gas.CH4], h2 = m[Gas.H2], h2s = m[Gas.H2S];
        double reqA = 1.5 * ch4 + 0.5 * h2 + 1.5 * h2s;
        if (reqA > 1e-12 && o2 > 1e-12) {
            double f = Math.min(1.0, o2 / reqA);
            double bCh4 = ch4 * f, bH2 = h2 * f, bH2s = h2s * f;
            m[Gas.CH4] -= bCh4;
            m[Gas.H2] -= bH2;
            m[Gas.H2S] -= bH2s;
            o2 -= reqA * f;
            m[Gas.CO] += bCh4;
            m[Gas.H2O] += 2 * bCh4 + bH2 + bH2s;
            m[Gas.SO2] += bH2s;
            energy += bCh4 * (Gas.METHANE.lhv - Gas.CARBON_MONOXIDE.lhv)
                    + bH2 * Gas.HYDROGEN.lhv
                    + bH2s * Gas.HYDROGEN_SULFIDE.lhv;
        }

        // Stage B: CO burnout with whatever oxygen is left.
        double co = m[Gas.CO];
        if (co > 1e-12 && o2 > 1e-12) {
            double b = Math.min(co, o2 * 2.0);
            m[Gas.CO] -= b;
            o2 -= b * 0.5;
            m[Gas.CO2] += b;
            energy += b * Gas.CARBON_MONOXIDE.lhv;
        }
        m[Gas.O2] = Math.max(0, o2);
        return energy;
    }

    /**
     * Burns {@code fuelStream} using oxygen taken from {@code air} (diffusion flame). Products
     * stay in {@code fuelStream}; consumed oxygen (and its nitrogen) is moved from {@code air}.
     *
     * @param maxAirMoles upper bound of air that can be entrained
     * @return released heat in kJ
     */
    public static double burnWithAir(GasMix fuelStream, GasMix air, double maxAirMoles) {
        double need = Math.max(0, o2Required(fuelStream) - fuelStream.m[Gas.O2]);
        double airO2 = air.fraction(Gas.O2);
        if (need > 0 && airO2 > 1e-6) {
            double airMoles = Math.min(maxAirMoles, need / airO2 * 1.05);
            air.moveTo(fuelStream, airMoles);
        }
        return burn(fuelStream);
    }
}
