package com.jeladastudios.ftsgeology.gas;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Every gas tracked by the simulation. Values are real physical data:
 * molar mass (g/mol), flammability limits in air (volume fraction), limiting oxygen
 * concentration, lower heating value (kJ/mol) and a relative flame-speed factor.
 */
public enum Gas {
    //  id, formula, molar mass, LFL, UFL, LOC, LHV, flame speed, violence, colour, trace level
    NITROGEN("nitrogen", "N\u2082", 28.014, 0, 0, 0, 0, 0, 0, 0x8899AA, 0),
    OXYGEN("oxygen", "O\u2082", 31.998, 0, 0, 0, 0, 0, 0, 0x55CCFF, 0.002),
    CARBON_DIOXIDE("carbon_dioxide", "CO\u2082", 44.010, 0, 0, 0, 0, 0, 0, 0x9A9A9A, 0.002),
    WATER_VAPOR("water_vapor", "H\u2082O", 18.015, 0, 0, 0, 0, 0, 0, 0xEEEEFF, 0.005),
    METHANE("methane", "CH\u2084", 16.043, 0.050, 0.150, 0.120, 802.3, 1.0, 1.0, 0xFFAA22, 0.001),
    HYDROGEN("hydrogen", "H\u2082", 2.016, 0.040, 0.750, 0.050, 241.8, 4.0, 1.5, 0xCCEEFF, 0.001),
    CARBON_MONOXIDE("carbon_monoxide", "CO", 28.010, 0.125, 0.740, 0.055, 283.0, 1.0, 0.8, 0xDD3333, 0.00001),
    HYDROGEN_SULFIDE("hydrogen_sulfide", "H\u2082S", 34.080, 0.040, 0.460, 0.075, 518.0, 1.0, 0.9, 0xC8D82A, 0.000002),
    SULFUR_DIOXIDE("sulfur_dioxide", "SO\u2082", 64.066, 0, 0, 0, 0, 0, 0, 0xB050E0, 0.000001);

    public static final Gas[] VALUES = values();
    public static final int COUNT = VALUES.length;

    public static final int N2 = NITROGEN.ordinal();
    public static final int O2 = OXYGEN.ordinal();
    public static final int CO2 = CARBON_DIOXIDE.ordinal();
    public static final int H2O = WATER_VAPOR.ordinal();
    public static final int CH4 = METHANE.ordinal();
    public static final int H2 = HYDROGEN.ordinal();
    public static final int CO = CARBON_MONOXIDE.ordinal();
    public static final int H2S = HYDROGEN_SULFIDE.ordinal();
    public static final int SO2 = SULFUR_DIOXIDE.ordinal();

    /** Gases that can burn. */
    public static final Gas[] FUELS = {METHANE, HYDROGEN, CARBON_MONOXIDE, HYDROGEN_SULFIDE};

    /** Oxygen / nitrogen fraction of normal dry air (argon lumped into nitrogen). */
    public static final double AIR_O2 = 0.2095;
    public static final double AIR_N2 = 1.0 - AIR_O2;
    public static final double AIR_MOLAR_MASS = AIR_O2 * 31.998 + AIR_N2 * 28.014;

    /** Moles of gas in one block (1 m\u00B3) at 1 atm and 20 \u00B0C: n = PV / RT. */
    public static final double MOL_PER_BLOCK = 101325.0 / (8.314462 * 293.15);

    public final String id;
    public final String formula;
    public final double molarMass;
    public final double lfl;
    public final double ufl;
    public final double loc;
    public final double lhv;
    public final double flameSpeed;
    public final double violence;
    public final int color;
    /** Below this fraction the gas is harmless and undetectable in practice; the cell may be dropped. */
    public final double trace;

    Gas(String id, String formula, double molarMass, double lfl, double ufl, double loc, double lhv,
        double flameSpeed, double violence, int color, double trace) {
        this.id = id;
        this.formula = formula;
        this.molarMass = molarMass;
        this.lfl = lfl;
        this.ufl = ufl;
        this.loc = loc;
        this.lhv = lhv;
        this.flameSpeed = flameSpeed;
        this.violence = violence;
        this.color = color;
        this.trace = trace;
    }

    public boolean isFuel() {
        return lhv > 0;
    }

    public boolean isAirComponent() {
        return this == NITROGEN || this == OXYGEN;
    }

    public Component displayName() {
        return Component.translatable("gas.fts_geology." + id);
    }

    public static Gas byId(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        for (Gas g : VALUES) {
            if (g.id.equals(s) || g.formula.equalsIgnoreCase(s) || g.name().equalsIgnoreCase(s)) return g;
        }
        // Accept ASCII formulas like "CH4", "H2S".
        for (Gas g : VALUES) {
            if (asciiFormula(g).equalsIgnoreCase(s)) return g;
        }
        return null;
    }

    public static String asciiFormula(Gas g) {
        return g.formula.replace('\u2082', '2').replace('\u2084', '4');
    }
}
