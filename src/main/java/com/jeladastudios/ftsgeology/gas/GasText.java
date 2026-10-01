package com.jeladastudios.ftsgeology.gas;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** Formatting of gas readings for detectors, tooltips and commands. */
public final class GasText {
    // Alarm set points of a typical 4-gas monitor.
    public static final double ALARM_O2_LOW = 0.195;
    public static final double ALARM_O2_HIGH = 0.235;
    public static final double ALARM_LEL = 10;
    public static final double ALARM_CO_PPM = 35;
    public static final double ALARM_H2S_PPM = 10;
    public static final double ALARM_SO2_PPM = 2;
    public static final double ALARM_CO2 = 0.005;

    private GasText() {
    }

    public static String pct(double fraction) {
        double p = fraction * 100;
        if (p >= 10) return String.format(Locale.ROOT, "%.1f%%", p);
        if (p >= 0.1) return String.format(Locale.ROOT, "%.2f%%", p);
        return String.format(Locale.ROOT, "%.3f%%", p);
    }

    public static String ppm(double fraction) {
        double v = fraction * 1e6;
        if (v >= 100) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    public static String amount(double fraction) {
        return fraction < 0.001 ? ppm(fraction) + " ppm" : pct(fraction);
    }

    public static String mol(double moles) {
        if (moles >= 1000) return String.format(Locale.ROOT, "%.1fk mol", moles / 1000);
        if (moles >= 10) return String.format(Locale.ROOT, "%.0f mol", moles);
        return String.format(Locale.ROOT, "%.2f mol", moles);
    }

    public static String atm(double p) {
        return String.format(Locale.ROOT, "%.2f atm", p);
    }

    public static boolean isAlarm(GasMix g) {
        return g.fraction(Gas.O2) < ALARM_O2_LOW || g.fraction(Gas.O2) > ALARM_O2_HIGH
                || Combustion.percentLel(g) >= ALARM_LEL
                || g.fraction(Gas.CO) * 1e6 >= ALARM_CO_PPM
                || g.fraction(Gas.H2S) * 1e6 >= ALARM_H2S_PPM
                || g.fraction(Gas.SO2) * 1e6 >= ALARM_SO2_PPM
                || g.fraction(Gas.CO2) >= ALARM_CO2;
    }

    private static MutableComponent part(String label, String value, boolean alarm) {
        return Component.literal(label + " ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(alarm ? ChatFormatting.RED : ChatFormatting.WHITE));
    }

    /** Compact one-line reading in the style of a portable 4-gas monitor. */
    public static Component detectorLine(GasMix g) {
        double o2 = g.fraction(Gas.O2);
        double lel = Combustion.percentLel(g);
        double co = g.fraction(Gas.CO), h2s = g.fraction(Gas.H2S), co2 = g.fraction(Gas.CO2);
        MutableComponent c = Component.empty();
        c.append(part("O\u2082", pct(o2), o2 < ALARM_O2_LOW || o2 > ALARM_O2_HIGH));
        c.append("  ").append(part("LEL", String.format(Locale.ROOT, "%.0f%%", Math.min(lel, 999)), lel >= ALARM_LEL));
        c.append("  ").append(part("CO", ppm(co), co * 1e6 >= ALARM_CO_PPM));
        c.append("  ").append(part("H\u2082S", ppm(h2s), h2s * 1e6 >= ALARM_H2S_PPM));
        c.append("  ").append(part("CO\u2082", pct(co2), co2 >= ALARM_CO2));
        double so2 = g.fraction(Gas.SO2);
        if (so2 * 1e6 >= 0.5) c.append("  ").append(part("SO\u2082", ppm(so2), so2 * 1e6 >= ALARM_SO2_PPM));
        return c;
    }

    /** Flammability verdict for a mixture. */
    public static Component flammability(GasMix g) {
        double fuel = Combustion.fuelFraction(g);
        if (fuel <= 1e-6) return Component.translatable("fts_geology.gas.flammability.none").withStyle(ChatFormatting.GREEN);
        if (Combustion.isFlammable(g)) return Component.translatable("fts_geology.gas.flammability.explosive").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        if (fuel > Combustion.ufl(g)) return Component.translatable("fts_geology.gas.flammability.rich").withStyle(ChatFormatting.GOLD);
        if (fuel >= Combustion.lfl(g)) return Component.translatable("fts_geology.gas.flammability.inert").withStyle(ChatFormatting.YELLOW);
        return Component.translatable("fts_geology.gas.flammability.lean").withStyle(ChatFormatting.YELLOW);
    }

    /** Full composition, one line per gas that is present. */
    public static void appendComposition(GasMix g, java.util.function.Consumer<Component> out) {
        double t = g.total();
        for (Gas gas : Gas.VALUES) {
            double x = t > 0 ? g.m[gas.ordinal()] / t : 0;
            if (x < 1e-7) continue;
            out.accept(Component.literal("  ")
                    .append(Component.literal(gas.formula).withStyle(s -> s.withColor(gas.color)))
                    .append(Component.literal(" ").append(gas.displayName()).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(": " + amount(x)).withStyle(ChatFormatting.WHITE)));
        }
    }
}
