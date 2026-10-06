package com.jeladastudios.ftsgeology.worldgen.terrain;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;

/**
 * Which of the terrain's later changes a world is made with. A change to what the generator makes shows as a seam
 * where the new chunks meet the old ones, so some are kept for the worlds made after them, and a world made before
 * goes on as it always was.
 *
 * <p>It has to be known before the first biome is asked for, and a new world's spawn is looked for, and its
 * strongholds laid out, before a level has loaded its data. So a new world is told by its level data not being set
 * up yet, and an old one by its own small file in its data folder, read straight off the disk; the file is written
 * through the overworld's data once it loads.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class WorldgenRevision {

    private WorldgenRevision() {}

    /** Coasts the ground stands dry over are land, not the sea the climate called them (round 118). */
    public static final int LAND_COASTS = 1;
    /** A rift's axis sunk in its floor, deeper where it opens fast (round 118). */
    public static final int DEEP_RIFTS = 2;
    /** The mountains belted by height from the climate round them, forest to snow (round 128). */
    public static final int CLIMATE_BELTS = 3;
    /** No alluvial plain where only the ragging of its border put one, away from the body of a plain (round 129). */
    public static final int PLAIN_BODIES = 4;
    /** A river cut through a deposit another mod laid under its bed, as through any ground (round 129). */
    public static final int RIVER_DEPOSITS = 5;
    /**
     * Ground left green where the climate keeps it so: soil coloured under its grass rather than over it, steep slopes
     * and talus under the tree line clothed in a wet climate, volcanic and rift country grassed (Feel the Nature round).
     */
    public static final int GREENER_GROUND = 6;
    /** A volcanic arc belted by height as a fold belt is, forest at its foot to snow on its summits (FtN round). */
    public static final int VOLCANIC_BELTS = 7;
    /**
     * A large volcano's own ground (round 132): its crater, young lava and the fresh ash downwind of a live one the bare
     * volcanic highland, the rest of its body belted by height wherever it stands, a shield in a wet arc grassed.
     */
    public static final int VOLCANO_GROUND = 8;
    /** The ledges of a wet cliff under the tree line green, a shelf level with the ground beside it (round 132). */
    public static final int GREEN_LEDGES = 9;
    /** A beach only at the water: ground the climate called beach well over the sea is the land beside it (round 135). */
    public static final int HIGH_BEACHES = 10;
    /** A river ending on the shore short of the sea cut on through the strip of sand to the water (round 135). */
    public static final int MOUTH_BERMS = 11;
    /** A river's bed dug to where it was drawn where the noise left it standing higher under the water (round 136). */
    public static final int DRAWN_BEDS = 12;
    /** Terralith's basalt cliffs only on a coast whose rock is basalt; elsewhere the shore round it (round 137). */
    public static final int BASALT_COASTS = 13;
    /** A named mountain the near ring has no room for looked for on from ten kilometres, the tall world too (round 138). */
    public static final int NEAR_LANDMARKS = 14;
    /** What a world made now gets. */
    public static final int CURRENT = 14;

    private static final String DATA = "fts_geology_worldgen";
    private static volatile int revision = CURRENT;

    /** Whether the world running is made with this change. */
    public static boolean has(int change) {
        return revision >= change;
    }

    public static int revision() {
        return revision;
    }

    private static final class Stamp extends SavedData {
        int revision;

        static Stamp load(CompoundTag tag) {
            Stamp s = new Stamp();
            s.revision = tag.getInt("revision");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("revision", revision);
            return tag;
        }
    }

    /** Before the levels are made: from TerrainContext, with the rest of what the terrain needs to know. */
    static void open(MinecraftServer server) {
        if (!server.getWorldData().overworldData().isInitialized()) {
            revision = CURRENT;
            return;
        }
        int r = 0;
        File file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA + ".dat").toFile();
        if (file.isFile()) {
            try {
                r = NbtIo.readCompressed(file).getCompound("data").getInt("revision");
            } catch (Exception e) {
                GeysersMod.LOGGER.warn("Could not read {}; the world is generated as one made before it: {}", file, e.toString());
            }
        }
        revision = r;
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        level.getDataStorage().computeIfAbsent(Stamp::load, () -> {
            Stamp s = new Stamp();
            s.revision = revision;
            s.setDirty();
            return s;
        }, DATA);
    }
}
