package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * The overworld's own surface, where a mod has made it its own. A terrain pack that adds biomes brings the ground
 * they are meant to have in the {@code minecraft:overworld} noise settings' surface rule, and the mod's world types
 * read their own rule instead, so those biomes came out as plain grass and stone. This rule stands after the mod's
 * own (its biomes, bare rock) and hands every column to the overworld's rule when that rule is not vanilla's; with
 * vanilla's it does nothing, and the mod's copy of it carries on as before.
 */
public record OverworldSurfaceRule() implements SurfaceRules.RuleSource {

    public static final KeyDispatchDataCodec<OverworldSurfaceRule> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(new OverworldSurfaceRule()));

    /** The overworld's rule when a mod changed it, else null. Set as a server starts. */
    private static volatile SurfaceRules.RuleSource foreign;

    /** Reads the overworld's surface rule from the server's registries and keeps it if it is not vanilla's. */
    public static void open(MinecraftServer server) {
        NoiseGeneratorSettings overworld = server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS)
                .get(NoiseGeneratorSettings.OVERWORLD);
        SurfaceRules.RuleSource rule = overworld == null ? null : overworld.surfaceRule();
        foreign = rule == null || rule.equals(SurfaceRuleData.overworld()) ? null : rule;
        if (foreign != null) {
            com.jeladastudios.ftsgeology.GeysersMod.LOGGER.info(
                    "The overworld's surface rule is a mod's; FT's Geology's world types paint its biomes with it");
        }
    }

    public static void clear() {
        foreign = null;
    }

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return CODEC;
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        SurfaceRules.RuleSource rule = foreign;
        if (rule == null) return (x, y, z) -> null;
        return rule.apply(context);
    }
}
