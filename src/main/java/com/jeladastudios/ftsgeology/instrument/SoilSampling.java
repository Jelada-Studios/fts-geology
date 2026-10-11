package com.jeladastudios.ftsgeology.instrument;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Soil sampling: the prospector's way of finding ore that does not crop out. Ore under the ground weathers, and its
 * metals move up into the soil over it and a little way round, so a sample of soil holds more copper over a copper
 * deposit than over barren rock. Nothing shows to the eye; the anomaly is a number against the normal level, and a line
 * of samples shows which way it rises.
 *
 * <p>Here the soil is read from the ore really lying under it: every ore block within reach adds to its metal, less the
 * deeper and the farther off it lies. The sample says the strongest metals against their background, and the side the
 * anomaly rises toward, but never the depth: soil cannot tell that, a core drill can.</p>
 */
public final class SoilSampling {

    private SoilSampling() {}

    /** How far round the sample, how far down under it, and the falloff length of a metal's halo, in blocks. */
    private static final int ACROSS = 24, DOWN = 36, UP = 4;
    private static final double HALO = 9.0;
    /** Most a metal rises over its background, and the summed weight at which it is halfway there. */
    private static final double MAX_RISE = 99, HALF = 100;
    /** What a scattered ore grain, not part of a deposit, adds: the trace every soil has. */
    private static final double SCATTERED = 0.015;
    /** Over these many times background a reading is an anomaly, weak and strong. */
    private static final double WEAK = 3, STRONG = 8;

    /** A metal a soil sample is assayed for, with its normal level in soil, the unit and how many decimals to show. */
    enum Metal {
        CU("cu", 30, 0, "ppm"), AU("au", 4, 0, "ppb"), PB_ZN("pbzn", 60, 0, "ppm"), HG("hg", 50, 0, "ppb"),
        AG("ag", 0.1, 1, "ppm"), SN("sn", 3, 0, "ppm"), NI("ni", 25, 0, "ppm"), U("u", 2, 1, "ppm"),
        KIMBERLITE("kimb", 0, 0, "");

        final String key;
        final double background;
        final int decimals;
        final String unit;

        Metal(String key, double background, int decimals, String unit) {
            this.key = key;
            this.background = background;
            this.decimals = decimals;
            this.unit = unit;
        }
    }

    /** Whether a struck block is ground a sample can be taken of: soil and loose sediment. */
    public static boolean sampleable(BlockState s) {
        RockTypes.Rock r = RockTypes.classify(s);
        return r == RockTypes.Rock.SOIL || r == RockTypes.Rock.SEDIMENT;
    }

    /** The metal an ore block sheds into the soil over it, and how much, or null. */
    static Metal metal(BlockState s, double[] weight) {
        boolean ore = RockTypes.classify(s) == RockTypes.Rock.ORE || s.is(Tags.Blocks.ORES);
        if (!ore) return null;
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        weight[0] = 1.0;
        if (path.contains("quartz_vein")) {
            weight[0] = 0.3;
            return Metal.AU;
        }
        if (path.contains("gold")) return Metal.AU;
        if (path.contains("copper") || path.contains("chalcopyrite") || path.contains("malachite")
                || path.contains("azurite")) return Metal.CU;
        if (path.contains("galena") || path.contains("lead") || path.contains("zinc") || path.contains("sphalerite"))
            return Metal.PB_ZN;
        if (path.contains("cinnabar")) return Metal.HG;
        if (path.contains("silver")) return Metal.AG;
        if (path.contains("tin") && !path.contains("tint")) return Metal.SN;
        if (path.contains("nickel")) return Metal.NI;
        if (path.contains("uranium")) return Metal.U;
        if (path.contains("diamond")) return Metal.KIMBERLITE;
        return null;   // iron, coal, pyrite, redstone, lapis, emerald: nothing a soil assay would single out
    }

    /**
     * Whether an ore block is part of a deposit rather than a grain scattered through ordinary rock. The mod's own
     * minerals only grow in deposits; a vanilla or other mod's ore counts as one where it touches such a mineral, and a
     * diamond where it lies in peridotite or serpentinite, the rock of a kimberlite pipe. Copper in basalt is left out:
     * the whole sea floor is basalt, and vanilla's scattered copper would read as native copper everywhere.
     */
    private static boolean inDeposit(ServerLevel level, BlockPos at, BlockState s, Metal metal) {
        if (BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace().equals("fts_geology")) return true;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            n.setWithOffset(at, d);
            if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, n)) continue;
            BlockState t = level.getBlockState(n);
            RockTypes.Rock r = RockTypes.classify(t);
            if (r == RockTypes.Rock.ORE && BuiltInRegistries.BLOCK.getKey(t.getBlock()).getNamespace().equals("fts_geology"))
                return true;
            if (metal == Metal.KIMBERLITE && (t.is(com.jeladastudios.ftsgeology.registry.ModBlocks.PERIDOTITE.get())
                    || t.is(com.jeladastudios.ftsgeology.registry.ModBlocks.SERPENTINITE.get()))) return true;
        }
        return false;
    }

    /** Assays the soil at a point. Only loaded ground is gone through. */
    public static List<Component> read(ServerLevel level, BlockPos at) {
        int n = Metal.values().length;
        double[] sum = new double[n], sx = new double[n], sz = new double[n];
        double[] w = new double[1];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int floor = Math.max(level.getMinBuildHeight(), at.getY() - DOWN);
        int top = Math.min(level.getMaxBuildHeight() - 1, at.getY() + UP);
        for (int dx = -ACROSS; dx <= ACROSS; dx++) {
            for (int dz = -ACROSS; dz <= ACROSS; dz++) {
                m.set(at.getX() + dx, at.getY(), at.getZ() + dz);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m)) continue;
                double h2 = dx * dx + dz * dz;
                for (int y = top; y >= floor; y--) {
                    BlockState s = level.getBlockState(m.setY(y));
                    if (s.isAir()) continue;
                    Metal metal = metal(s, w);
                    if (metal == null) continue;
                    if (!inDeposit(level, m, s, metal)) w[0] *= SCATTERED;
                    double depth = Math.max(0, at.getY() - y);
                    // Metal climbs through the weathered rock more readily than it spreads sideways through soil.
                    double f = w[0] / (1 + (h2 + 0.36 * depth * depth) / (HALO * HALO));
                    int i = metal.ordinal();
                    sum[i] += f;
                    sx[i] += f * dx;
                    sz[i] += f * dz;
                }
            }
        }

        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("soil.fts_geology.header").withStyle(ChatFormatting.GOLD));
        // The strongest two metals against their background; under twice background is noise.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(sum[b], sum[a]));
        int shown = 0;
        int lead = -1;
        for (int k = 0; k < n && shown < 2; k++) {
            int i = order[k];
            double ratio = 1 + MAX_RISE * sum[i] / (sum[i] + HALF);
            if (ratio < WEAK) break;
            Metal metal = Metal.values()[i];
            String grade = ratio >= STRONG ? "strong" : "weak";
            Component name = Component.translatable("soil.fts_geology.metal." + metal.key);
            if (metal == Metal.KIMBERLITE) {
                out.add(Component.translatable("soil.fts_geology.indicators", name,
                        Component.translatable("soil.fts_geology.level." + grade)).withStyle(ChatFormatting.YELLOW));
            } else {
                String ppm = String.format(Locale.ROOT, "%." + metal.decimals + "f", metal.background * ratio);
                String bg = String.format(Locale.ROOT, "%." + metal.decimals + "f", metal.background);
                out.add(Component.translatable("soil.fts_geology.reading", name, ppm, metal.unit, bg,
                        Component.translatable("soil.fts_geology.level." + grade)).withStyle(ChatFormatting.YELLOW));
            }
            if (lead < 0) lead = i;
            shown++;
        }
        if (lead < 0) {
            out.add(Component.translatable("soil.fts_geology.background").withStyle(ChatFormatting.GRAY));
            return out;
        }
        // Which way the anomaly rises: the weighted middle of what makes it.
        double cx = sx[lead] / sum[lead], cz = sz[lead] / sum[lead];
        if (Math.hypot(cx, cz) < 4) {
            out.add(Component.translatable("soil.fts_geology.here").withStyle(ChatFormatting.GRAY));
        } else {
            out.add(Component.translatable("soil.fts_geology.toward",
                    Component.translatable("prospect.fts_geology.dir."
                            + Prospecting.bearingOf((int) Math.round(cx), (int) Math.round(cz))))
                    .withStyle(ChatFormatting.GRAY));
        }
        return out;
    }
}
