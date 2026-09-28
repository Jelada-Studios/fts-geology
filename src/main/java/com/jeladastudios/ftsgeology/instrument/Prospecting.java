package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Prospecting: what a mineral struck with the hammer says about the deposit it belongs to, and where the ore of it
 * lies. A prospector reads the rusty pyrite halo, the green and blue of oxidised copper, a quartz vein, for the system
 * that made them, and follows them in to the ore. Here the setting names the system -- a pyrite halo over a subduction
 * arc is a porphyry copper, the same pyrite at a rift a massive sulfide -- and the ground round the strike is gone
 * through for the richest ore of it within reach: which, how much of it, which way and how deep.
 */
public final class Prospecting {

    private Prospecting() {}

    /** How far round the strike, and how far down, the ground is gone through. */
    private static final int ACROSS = 16, UP = 8, DOWN = 48;

    /** Whether a struck block says anything to a prospector: an ore or an indicator mineral. */
    public static boolean readable(BlockState s) {
        return RockTypes.classify(s) == RockTypes.Rock.ORE;
    }

    /** The system this mineral belongs to in this setting, as a translation key. */
    static String system(BlockState s, FaultType setting) {
        if (s.is(ModBlocks.MALACHITE.get()) || s.is(ModBlocks.AZURITE.get())) return "prospect.fts_geology.system.oxide_cap";
        if (s.is(ModBlocks.CINNABAR.get())) return "prospect.fts_geology.system.epithermal";
        if (s.is(ModBlocks.GALENA.get())) {
            return setting == FaultType.TRANSFORM ? "prospect.fts_geology.system.gouge" : "prospect.fts_geology.system.vein";
        }
        if (s.is(ModBlocks.QUARTZ_VEIN.get())) {
            return setting == FaultType.CONVERGENT_COLLISION ? "prospect.fts_geology.system.orogenic_gold"
                    : setting == FaultType.CONVERGENT_SUBDUCTION ? "prospect.fts_geology.system.porphyry_stockwork"
                    : "prospect.fts_geology.system.vein";
        }
        if (s.is(ModBlocks.PYRITE.get()) || s.is(ModBlocks.CHALCOPYRITE.get())) {
            return switch (setting) {
                case CONVERGENT_SUBDUCTION -> s.is(ModBlocks.PYRITE.get()) ? "prospect.fts_geology.system.porphyry_halo"
                        : "prospect.fts_geology.system.porphyry_core";
                case DIVERGENT -> "prospect.fts_geology.system.massive_sulfide";
                case TRANSFORM -> "prospect.fts_geology.system.gouge";
                default -> "prospect.fts_geology.system.vein";
            };
        }
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.contains("diamond")) return "prospect.fts_geology.system.kimberlite";
        if (path.contains("coal")) return "prospect.fts_geology.system.coal";
        if (path.contains("iron")) return setting == FaultType.INTERIOR ? "prospect.fts_geology.system.ironstone"
                : "prospect.fts_geology.system.magnetite";
        if (path.contains("gold")) return "prospect.fts_geology.system.orogenic_gold";
        if (path.contains("emerald") || path.contains("lapis")) return "prospect.fts_geology.system.skarn";
        return "prospect.fts_geology.system.unknown";
    }

    /** How much an ore is worth going after; the richest within reach is the one pointed to. */
    static int value(Block b) {
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (path.contains("diamond")) return 10;
        if (path.contains("emerald")) return 9;
        if (path.contains("gold")) return 8;
        if (path.contains("chalcopyrite")) return 7;
        if (path.contains("copper")) return 6;
        if (path.contains("lapis") || path.contains("redstone")) return 5;
        if (path.contains("iron") || path.contains("galena") || path.contains("cinnabar")) return 4;
        if (path.contains("malachite") || path.contains("azurite")) return 3;
        if (path.contains("coal")) return 1;
        return 2;
    }

    /**
     * Reads a struck mineral: what system it belongs to, and the ore of it that lies nearest among the richest within
     * reach. Only loaded ground is gone through. Whether a richer ore was found is returned in {@code found[0]}.
     */
    public static List<Component> read(ServerLevel level, BlockPos at, BlockState struck, boolean[] found) {
        return read(level, at, struck, found, new BlockPos[1]);
    }

    /** As above; the nearest block of the ore pointed to is handed back in {@code target[0]}. */
    public static List<Component> read(ServerLevel level, BlockPos at, BlockState struck, boolean[] found, BlockPos[] target) {
        List<Component> out = new ArrayList<>();
        FaultType setting = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sample(level, at.getX(), at.getZ()).faultType();
        out.add(Component.translatable("prospect.fts_geology.header",
                Component.translatable(system(struck, setting))).withStyle(ChatFormatting.GOLD));

        // Every ore within reach, by kind: how much of it, and the nearest.
        Block self = struck.getBlock();
        java.util.Map<Block, long[]> kinds = new java.util.HashMap<>();   // count, nearest squared, x, y, z
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int floor = Math.max(level.getMinBuildHeight(), at.getY() - DOWN);
        for (int dx = -ACROSS; dx <= ACROSS; dx++) {
            for (int dz = -ACROSS; dz <= ACROSS; dz++) {
                m.set(at.getX() + dx, at.getY(), at.getZ() + dz);
                if (!com.jeladastudios.ftsgeology.util.Loaded.at(level, m)) continue;
                for (int y = Math.min(level.getMaxBuildHeight() - 1, at.getY() + UP); y >= floor; y--) {
                    BlockState s = level.getBlockState(m.setY(y));
                    if (s.isAir() || s.getBlock() == self || RockTypes.classify(s) != RockTypes.Rock.ORE) continue;
                    long d = (long) dx * dx + (long) dz * dz + (long) (y - at.getY()) * (y - at.getY());
                    long[] k = kinds.computeIfAbsent(s.getBlock(), x -> new long[]{0, Long.MAX_VALUE, 0, 0, 0});
                    k[0]++;
                    if (d < k[1]) {
                        k[1] = d;
                        k[2] = m.getX();
                        k[3] = y;
                        k[4] = m.getZ();
                    }
                }
            }
        }
        // The richest kind, and of two as rich the more plentiful; never poorer than what was struck.
        Block best = null;
        long[] bestK = null;
        for (var e : kinds.entrySet()) {
            int v = value(e.getKey());
            if (v < value(self)) continue;
            if (best == null || v > value(best) || v == value(best) && e.getValue()[0] > bestK[0]) {
                best = e.getKey();
                bestK = e.getValue();
            }
        }
        int bestCount = bestK == null ? 0 : (int) bestK[0];
        BlockPos bestAt = bestK == null ? null : new BlockPos((int) bestK[2], (int) bestK[3], (int) bestK[4]);
        if (best == null) {
            found[0] = false;
            out.add(Component.translatable("prospect.fts_geology.nothing", ACROSS, DOWN).withStyle(ChatFormatting.GRAY));
            return out;
        }
        found[0] = true;
        target[0] = bestAt;
        int dx = bestAt.getX() - at.getX(), dy = bestAt.getY() - at.getY(), dz = bestAt.getZ() - at.getZ();
        int across = (int) Math.round(Math.hypot(dx, dz));
        Component where = across <= 1
                ? Component.translatable("prospect.fts_geology.straight")
                : Component.translatable("prospect.fts_geology.across", across,
                        Component.translatable("prospect.fts_geology.dir." + bearing(dx, dz)));
        Component depth = dy < 0
                ? Component.translatable("prospect.fts_geology.below", DepthScale.format(-dy * DepthScale.metresPerBlock()), -dy)
                : dy > 0 ? Component.translatable("prospect.fts_geology.above", dy)
                : Component.translatable("prospect.fts_geology.level");
        out.add(Component.translatable("prospect.fts_geology.ore", best.getName(), bestCount, depth, where)
                .withStyle(ChatFormatting.YELLOW));
        return out;
    }

    /** One of eight compass points, north being negative z: n, ne, e... */
    public static String bearingOf(int dx, int dz) {
        return bearing(dx, dz);
    }

    /** One of eight compass points, north being negative z. */
    static String bearing(int dx, int dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        if (a < 0) a += 360;
        String[] points = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return points[(int) Math.round(a / 45.0) % 8];
    }
}
