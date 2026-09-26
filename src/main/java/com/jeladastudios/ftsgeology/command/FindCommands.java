package com.jeladastudios.ftsgeology.command;

import com.mojang.brigadier.context.CommandContext;
import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.HotspotMap;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import com.jeladastudios.ftsgeology.volcano.VolcanoField;
import com.jeladastudios.ftsgeology.volcano.VolcanoType;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import static com.jeladastudios.ftsgeology.command.InspectCommands.*;

/** /geology find and field: locating settings and large volcanoes. */
public final class FindCommands {

    private FindCommands() {}

    // === /geology find ======================================================

    /**
     * Walks outward from the player in a coarse spiral until it finds the requested setting. This
     * is the command that makes the rest testable: hunting for a continental collision zone by
     * wandering could take thousands of blocks, and this answers in a moment because the whole model
     * is pure maths over the seed.
     */
    static int find(CommandContext<CommandSourceStack> ctx, String what, boolean teleport) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());

        source.sendSuccess(() -> Component.translatable("command.fts_geology.searching_for_the_nearest_s", what)
                .withStyle(ChatFormatting.GRAY), false);

        // Searching outward can mean a hundred thousand plate samples before a rare setting turns
        // up, and doing that on the server thread stalled the game for seconds. It is pure maths
        // over the seed, so it runs on a worker and only the answer comes back.
        CompletableFuture
                .supplyAsync(() -> {
                    Hit hit = search(level, at, what);
                    // The generator's own surface there. The chunk is usually not loaded, and reading it would give
                    // the bottom of the world or build the chunk inside the tick.
                    return hit == null ? null : new Located(hit, surfaceY(level, hit.x(), hit.z()));
                }, Util.backgroundExecutor())
                .thenAcceptAsync(found -> {
                    if (found == null) {
                        source.sendFailure(Component.translatable("command.fts_geology.no_s_found_within_about_21000_blocks_try", what));
                        return;
                    }
                    Hit hit = found.hit();
                    // Also to the log, for a console over RCON, which never sees the reply.
                    GeysersMod.LOGGER.info("Nearest {}: {} {} {}, {} blocks away", what, hit.x(), found.y(), hit.z(),
                            hit.distance());
                    source.sendSuccess(() -> Component.translatable("command.fts_geology.nearest_s_d_d_d_about_d_blocks_away", what, hit.x(), found.y(), hit.z(), hit.distance()).withStyle(ChatFormatting.GREEN), false);
                    if (teleport) SiteTeleport.request(source, level, hit.x(), hit.z());
                }, level.getServer())
                .exceptionally(t -> {
                    source.sendFailure(Component.translatable("command.fts_geology.search_failed_s", t));
                    return null;
                });
        return 1;
    }

    /** A located setting: where it is and roughly how far away. */
    record Hit(int x, int z, int distance) {}

    /** A located setting and the generator's surface height there. */
    record Located(Hit hit, int y) {}

    /** The generator's surface at a column, without loading or building its chunk. Safe off the server thread. */
    static int surfaceY(ServerLevel level, int x, int z) {
        return level.getChunkSource().getGenerator().getBaseHeight(x, z,
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level,
                level.getChunkSource().randomState());
    }

    /** Spirals outward looking for the requested setting. Pure maths - runs off the server thread. */
    static Hit search(ServerLevel level, BlockPos at, String what) {
        // A lava tube is a point on a seeded grid, not a setting to spiral for: the nearest start is worked out directly.
        if (what.equals("tube")) {
            int[] s = com.jeladastudios.ftsgeology.worldgen.LavaTubes.nearestStart(level, at.getX(), at.getZ());
            return s == null ? null : new Hit(s[0], s[1], (int) Math.round(Math.hypot(s[0] - at.getX(), s[1] - at.getZ())));
        }
        if (what.equals("valley")) return searchValley(level, at);
        // Karst stands on the seed too: a sinkhole on its grid, a swallow hole where the network takes a river into a hill.
        if (what.equals("sinkhole") || what.equals("swallowhole")) {
            if (!com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)) return null;
            double[] s = what.equals("sinkhole")
                    ? com.jeladastudios.ftsgeology.worldgen.Dolines.nearest(at.getX(), at.getZ(), 300)
                    : com.jeladastudios.ftsgeology.hydrology.RiverNetwork.nearestSwallow(at.getX(), at.getZ(), 24);
            return s == null ? null : new Hit((int) Math.floor(s[0]), (int) Math.floor(s[1]),
                    (int) Math.round(Math.hypot(s[0] - at.getX(), s[1] - at.getZ())));
        }
        // A named mountain stands in one place in a world, worked out from the seed: there is nothing to spiral for.
        for (String name : com.jeladastudios.ftsgeology.worldgen.terrain.DemLibrary.LANDMARKS) {
            if (!what.equals(name)) continue;
            // Only the mod's own world types raise them; anywhere else there is nothing at the seed's place.
            if (!com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)) return null;
            var site = com.jeladastudios.ftsgeology.worldgen.terrain.LandmarkSites.of(
                    com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                    com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params(), name);
            return site == null ? null : new Hit(site.x(), site.z(),
                    (int) Math.round(Math.hypot(site.x() - at.getX(), site.z() - at.getZ())));
        }
        int step = 96;
        int maxRings = 220;                 // reaches out about 21k blocks
        for (int ring = 1; ring <= maxRings; ring++) {
            int r = ring * step;
            for (int i = 0; i < ring * 8; i++) {
                double ang = (Math.PI * 2 * i) / (ring * 8);
                int x = at.getX() + (int) Math.round(Math.cos(ang) * r);
                int z = at.getZ() + (int) Math.round(Math.sin(ang) * r);
                if (matches(level, x, z, what)) return new Hit(x, z, r);
            }
        }
        return null;
    }

    /**
     * The nearest real valley: the plate test alone found the pass line of the crest noise, which is where a valley
     * runs, and sent the player to the first grid point on it, where the ground could be a saddle or a slope. Each
     * candidate's ground is read across the valley: the way the crest rises fastest, and both axes for a valley the
     * grid cuts on the skew. It is a valley when both flanks stand {@code VALLEY_FLANK} blocks over the floor at
     * {@code VALLEY_REACH}; the nearest ring with a valley on it is taken, and the deepest valley on that ring.
     */
    static Hit searchValley(ServerLevel level, BlockPos at) {
        int step = 96, maxRings = 220;
        Hit best = null;
        int bestDepth = 0;
        for (int ring = 1; ring <= maxRings && best == null; ring++) {
            int r = ring * step;
            for (int i = 0; i < ring * 8; i++) {
                double ang = (Math.PI * 2 * i) / (ring * 8);
                int x = at.getX() + (int) Math.round(Math.cos(ang) * r);
                int z = at.getZ() + (int) Math.round(Math.sin(ang) * r);
                if (!matches(level, x, z, "valley")) continue;
                int depth = valleyDepth(level, x, z);
                if (depth < VALLEY_FLANK) continue;
                if (depth > bestDepth) { bestDepth = depth; best = new Hit(x, z, r); }
            }
        }
        return best;
    }

    private static final int VALLEY_REACH = 128, VALLEY_FLANK = 30;

    /**
     * How far both flanks stand over the ground at a column, the lower of the two, along the best of three lines. A
     * line only counts when the ground along the valley, at right angles to it, does not fall away both ways: that
     * is a saddle between two ridges, which the pass line of the crest noise runs over as well as through valleys.
     */
    static int valleyDepth(ServerLevel level, int x, int z) {
        long seed = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed();
        var p = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params();
        var crest = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field.CREST;
        double gx = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(crest, seed, p, x + 64, z)
                - com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(crest, seed, p, x - 64, z);
        double gz = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(crest, seed, p, x, z + 64)
                - com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(crest, seed, p, x, z - 64);
        double g = Math.hypot(gx, gz);
        int floor = surfaceY(level, x, z);
        // The generator's ground, so a bay or a lake in the belt reads as its floor: the deepest "valley" found was
        // a fjord floor forty blocks under the sea. A valley for the player is dry.
        if (floor < level.getSeaLevel()) return 0;
        int depth = Math.max(flanks(level, x, z, 1, 0, floor), flanks(level, x, z, 0, 1, floor));
        if (g > 1e-6) {
            depth = Math.max(depth, flanks(level, x, z, gx / g, gz / g, floor));
        }
        return depth;
    }

    private static int flanks(ServerLevel level, int x, int z, double dx, double dz, int floor) {
        int a = surfaceY(level, x + (int) Math.round(dx * VALLEY_REACH), z + (int) Math.round(dz * VALLEY_REACH));
        int b = surfaceY(level, x - (int) Math.round(dx * VALLEY_REACH), z - (int) Math.round(dz * VALLEY_REACH));
        int c = surfaceY(level, x - (int) Math.round(dz * VALLEY_REACH), z + (int) Math.round(dx * VALLEY_REACH));
        int d = surfaceY(level, x + (int) Math.round(dz * VALLEY_REACH), z - (int) Math.round(dx * VALLEY_REACH));
        if (Math.max(c, d) < floor - SADDLE_DROP) return 0;
        return Math.min(a, b) - floor;
    }

    /** Ground this far below the floor both ways along the line makes it a saddle, not a valley. */
    private static final int SADDLE_DROP = 4;

    static boolean matches(ServerLevel level, int x, int z, String what) {
        if (what.equals("hotspot")) {
            return HotspotMap.sample(level, x, z).strength() > 0.35;
        }
        if (what.equals("valley")) {
            // A valley through a mountain belt is the terrain's own field, so only the mod's world type has one.
            if (!com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)) return false;
            long seed = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed();
            var p = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params();
            double belt = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(
                    com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field.BELT, seed, p, x, z);
            // A valley is a pass of the crest noise (0 in the pass, 1 on the ridge) in a convergent belt's core.
            double crest = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.field(
                    com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.Field.CREST, seed, p, x, z);
            FaultType k = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainFields.sampleAt(seed, p, x, z).boundaryType();
            boolean convergent = k == FaultType.CONVERGENT_COLLISION || k == FaultType.CONVERGENT_SUBDUCTION;
            return convergent && belt >= 0.45 && crest <= 0.05;
        }
        PlateSample s = TectonicMap.sampleCached(level, x, z);
        // Require decent stress so we land somewhere the setting is actually expressed, not on the
        // faint outer edge of the fault zone.
        if (s.stress() < 0.25) return false;
        return switch (what) {
            case "subduction" -> s.faultType() == FaultType.CONVERGENT_SUBDUCTION;
            case "collision" -> s.faultType() == FaultType.CONVERGENT_COLLISION;
            case "rift" -> s.faultType() == FaultType.DIVERGENT;
            case "transform" -> s.faultType() == FaultType.TRANSFORM;
            default -> false;
        };
    }

    // === /geology field =====================================================

    /** Finds the nearest large volcano, and with tp puts the player beside its summit. */
    static int field(CommandContext<CommandSourceStack> ctx, String typeName, boolean teleport) {
        // Unrecognised or absent means any type.
        final VolcanoType only = typeName == null ? null : switch (typeName) {
            case "strato" -> VolcanoType.STRATOVOLCANO;
            case "shield" -> VolcanoType.SHIELD;
            case "fissure" -> VolcanoType.FISSURE;
            case "caldera", "flooded" -> VolcanoType.CALDERA;
            default -> null;
        };
        if ("caldera".equals(typeName)) {   // the land caldera is switched off in VolcanoField
            ctx.getSource().sendFailure(Component.translatable("command.fts_geology.field.calderas_off"));
            return 0;
        }
        final com.jeladastudios.ftsgeology.volcano.VolcanoSetting onlySetting = typeName == null ? null : switch (typeName) {
            // A type on its own means the mountain on land; the islands have their own words.
            case "strato", "shield", "fissure", "caldera" -> com.jeladastudios.ftsgeology.volcano.VolcanoSetting.LAND;
            case "island", "flooded" -> com.jeladastudios.ftsgeology.volcano.VolcanoSetting.ISLAND;
            case "eroded" -> com.jeladastudios.ftsgeology.volcano.VolcanoSetting.ERODED;
            case "atoll" -> com.jeladastudios.ftsgeology.volcano.VolcanoSetting.ATOLL;
            case "guyot" -> com.jeladastudios.ftsgeology.volcano.VolcanoSetting.GUYOT;
            default -> null;
        };
        final com.jeladastudios.ftsgeology.volcano.VolcanoActivity onlyActivity = typeName == null ? null : switch (typeName) {
            case "active" -> com.jeladastudios.ftsgeology.volcano.VolcanoActivity.ACTIVE;
            case "dormant" -> com.jeladastudios.ftsgeology.volcano.VolcanoActivity.DORMANT;
            case "extinct" -> com.jeladastudios.ftsgeology.volcano.VolcanoActivity.EXTINCT;
            default -> null;
        };
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        final int rings = 8;
        source.sendSuccess(() -> Component.translatable("command.fts_geology.field.searching")
                .withStyle(ChatFormatting.GRAY), false);
        // A cell is worked out from the generator's own terrain the first time it is asked about,
        // which is slow; like find, it runs on a worker and only the answer comes back.
        CompletableFuture
                .supplyAsync(() -> VolcanoField.nearest(level, at.getX(), at.getZ(), rings, only, onlySetting, onlyActivity),
                        Util.backgroundExecutor())
                .thenAcceptAsync(found -> {
                    // What the search turned down, as water / relief / structure / other per type, so a
                    // type that never turns up can be told from one that is only rare.
                    int[] no = found.refused();
                    Object[] perType = new Object[VolcanoType.values().length];
                    for (VolcanoType type : VolcanoType.values()) {
                        int b = type.ordinal() * VolcanoField.REASONS;
                        perType[type.ordinal()] = no[b + VolcanoField.WATER] + "/" + no[b + VolcanoField.RELIEF]
                                + "/" + no[b + VolcanoField.STRUCTURE] + "/" + no[b + VolcanoField.OTHER];
                    }
                    GeysersMod.LOGGER.info("Large volcano candidates refused, water/relief/structure/other: {}",
                            java.util.Arrays.toString(perType));
                    Component refusedLine = Component.translatable("command.fts_geology.field.refused", perType)
                            .withStyle(ChatFormatting.GRAY);
                    if (found.site() == null) {
                        source.sendFailure(Component.translatable("command.fts_geology.field.none",
                                rings * 2560));
                        source.sendSuccess(() -> refusedLine, false);
                        return;
                    }
                    VolcanoField.Site s = found.site();
                    int distance = (int) Math.round(Math.hypot(s.x() - at.getX(), s.z() - at.getZ()));
                    // Also to the log: the reply arrives after the command has returned, which a
                    // console connected over RCON never sees.
                    GeysersMod.LOGGER.info("Nearest large volcano: {} at {} {} base {} summit {} reach {}, {} blocks away, {} in the search {}, setting {} {}, {}, activity on land and live islands {}",
                            s.type(), s.x(), s.z(), s.baseY(), s.summitY(), s.reach(), distance, found.count(),
                            java.util.Arrays.toString(found.byType()), s.setting(),
                            java.util.Arrays.toString(found.bySetting()), s.activity(),
                            java.util.Arrays.toString(found.byActivity()));
                    source.sendSuccess(() -> Component.translatable("command.fts_geology.field.found",
                            kindName(s), s.x(), s.z(),
                            s.baseY(), s.summitY(), distance, found.count()).withStyle(ChatFormatting.GREEN), false);
                    source.sendSuccess(() -> refusedLine, false);
                    // Beside the summit rather than on it: the crater is cut once the area loads.
                    if (teleport) SiteTeleport.request(source, level, s.x() + 40, s.z());
                }, level.getServer())
                .exceptionally(t -> {
                    source.sendFailure(Component.translatable("command.fts_geology.search_failed_s", t));
                    return null;
                });
        return 1;
    }

    /** What a site is, for chat: its kind, and on land or a live island how alive it is. */
    static Component kindName(VolcanoField.Site site) {
        Component kind = Component.translatable("volcano.fts_geology.kind." + site.kindKey());
        com.jeladastudios.ftsgeology.volcano.VolcanoActivity a = site.activity();
        boolean shown = site.setting() == com.jeladastudios.ftsgeology.volcano.VolcanoSetting.LAND
                || site.setting() == com.jeladastudios.ftsgeology.volcano.VolcanoSetting.ISLAND;
        return shown && a != com.jeladastudios.ftsgeology.volcano.VolcanoActivity.ACTIVE
                ? Component.translatable("volcano.fts_geology.activity." + a.name().toLowerCase(Locale.ROOT), kind)
                : kind;
    }

    /**
     * Measures the steps in the ground across the nearest generated large volcano, separately for
     * neighbouring columns inside one chunk and for neighbours either side of a chunk border.
     *
     * <p>The body is written a chunk at a time, in whatever order the chunks come, so this is the test
     * of whether it came out as one mountain. If any chunk worked something out differently from its
     * neighbour, the steps across borders come out larger than the ones inside chunks.</p>
     */
    static int fieldSeams(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        VolcanoField.Found found = VolcanoField.nearest(level, at.getX(), at.getZ(), 1, null);
        long[] inside = new long[3], border = new long[3];    // pairs, total step, steps of 4 or more
        // A digest of every height measured. The same seed generated in a different chunk order has
        // to give the same number, which is a stricter test than the steps: it would catch a chunk
        // that is wrong all over rather than only at its edges.
        long digest = 0;
        int columns = 0;
        // The same counts in four rings out from the summit, logged, so a seam can be traced to a part.
        long[][] bands = new long[4][4];   // pairs inside, steps of 4+ inside, pairs across, steps across
        if (found.site() != null) {
            VolcanoField.Site s = found.site();
            int r = Math.min(s.edificeReach(), 240);
            for (int x = s.x() - r; x < s.x() + r; x++) {
                for (int z = s.z() - r; z < s.z() + r; z++) {
                    double d = Math.hypot(x - s.x(), z - s.z());
                    // The summit, its vents and chimneys are cut live and are meant to be steep.
                    if (d > s.edificeReach() || d < 80) continue;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int here = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z);
                    if (here == Integer.MIN_VALUE) continue;
                    digest = digest * 0x100000001B3L ^ (x * 73856093L ^ z * 19349663L ^ here);
                    columns++;
                    int band = Math.min(3, (int) ((d - 80) / Math.max(1.0, s.edificeReach() - 80) * 4));
                    boolean acrossX = ((x + 1) & 15) == 0, acrossZ = ((z + 1) & 15) == 0;
                    tally(bands[band], seam(level, x + 1, z, here, acrossX ? border : inside), acrossX);
                    tally(bands[band], seam(level, x, z + 1, here, acrossZ ? border : inside), acrossZ);
                }
            }
            for (int b = 0; b < 4; b++) {
                long[] t = bands[b];
                com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info(
                        "Seams ring {} of 4: inside {} pairs, {} per thousand of 4+; across borders {} pairs, {} per thousand",
                        b + 1, t[0], dec(t[0] == 0 ? 0 : 1000.0 * t[1] / t[0], 1),
                        t[2], dec(t[2] == 0 ? 0 : 1000.0 * t[3] / t[2], 1));
            }
        }
        if (inside[0] == 0 || border[0] == 0) {
            source.sendFailure(Component.translatable("command.fts_geology.field.no_seams"));
            return 0;
        }
        VolcanoField.Site s = found.site();
        final String sum = Long.toHexString(digest);
        final int measured = columns;
        source.sendSuccess(() -> Component.translatable("command.fts_geology.field.seams",
                kindName(s), s.x(), s.z(),
                inside[0], dec((double) inside[1] / inside[0], 2), dec(1000.0 * inside[2] / inside[0], 1),
                border[0], dec((double) border[1] / border[0], 2), dec(1000.0 * border[2] / border[0], 1),
                sum, measured), false);
        return 1;
    }

    /** Counts one neighbouring pair into {@code into}; returns the step, or -1 if there was none to measure. */
    static int seam(ServerLevel level, int x, int z, int here, long[] into) {
        if (!level.hasChunk(x >> 4, z >> 4)) return -1;
        int there = com.jeladastudios.ftsgeology.worldgen.TerrainProbe.groundY(level, x, z);
        if (there == Integer.MIN_VALUE) return -1;
        int step = Math.abs(there - here);
        into[0]++;
        into[1] += step;
        if (step >= 4) into[2]++;
        return step;
    }

    private static void tally(long[] band, int step, boolean across) {
        if (step < 0) return;
        int i = across ? 2 : 0;
        band[i]++;
        if (step >= 4) band[i + 1]++;
    }
}
