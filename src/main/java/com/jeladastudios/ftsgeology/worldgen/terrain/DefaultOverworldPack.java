package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.List;

/**
 * Makes "FT's Geology" the default overworld: a built-in data pack that replaces {@code minecraft:normal} with the
 * mod's world preset, so a new world and a dedicated server's {@code level-type=minecraft:normal} get the plate
 * terrain without anyone choosing it. The pack is only registered when no other terrain mod is present: a
 * conditional file in the mod's own data shadowed vanilla's whether its condition held or not, and with the
 * condition false the preset was simply gone. Terralith and Tectonic bring their own overworld; TerraBlender,
 * Biomes O' Plenty and BYG put their biomes into vanilla's biome source, which the mod's wraps, so the mod's
 * preset stays a choice under World Type beside theirs.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DefaultOverworldPack {

    private DefaultOverworldPack() {}

    private static final List<String> TERRAIN_MODS = List.of("terralith", "tectonic", "terrablender", "biomesoplenty", "byg");
    private static final String PACK_ID = GeysersMod.MODID + "/default_overworld";

    @SubscribeEvent
    public static void addPacks(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        for (String id : TERRAIN_MODS) {
            if (ModList.get().isLoaded(id)) {
                GeysersMod.LOGGER.info("{} is present: FT's Geology stays a world type of its own, not the default", id);
                return;
            }
        }
        Path root = ModList.get().getModFileById(GeysersMod.MODID).getFile().findResource("resourcepacks/default_overworld");
        event.addRepositorySource(consumer -> consumer.accept(Pack.readMetaAndCreate(PACK_ID,
                Component.literal("FT's Geology default overworld"), true,
                id -> new PathPackResources(id, root, true), PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN)));
    }
}
