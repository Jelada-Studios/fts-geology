package com.jeladastudios.ftsgeology.util;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.config.GeyserConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.VersionChecker;
import net.minecraftforge.fml.common.Mod;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two things said to whoever runs a world when they join it, each once.
 *
 * <p>That a newer version of the mod is out, as Forge's own update check found it: most players never open the mod
 * list, where Forge shows it. And that the world was made with an older version: a world keeps the terrain it was
 * made with, in new ground too (see {@code WorldgenRevision}), so a player who updates and walks out into new land
 * sees nothing new and takes it the update did nothing. The version a world was last opened with is kept in the
 * world's own data.</p>
 *
 * <p>Only to a world's owner in single player and to operators on a server; both can be turned off.</p>
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public final class JoinNotices {

    private JoinNotices() {}

    private static final String DATA = "fts_geology_version";
    /** How long a world has run before one with no stamp is taken for one made before the stamp was kept, in ticks. */
    private static final long NEW_WORLD = 1200;

    /** The version the world was made with when it is older than this one; "" when that is not known; null otherwise. */
    private static volatile String madeWith;
    /** Who has been told of a newer version since the server started. */
    private static final Set<UUID> TOLD = ConcurrentHashMap.newKeySet();

    /** The mod version a world was last opened with. */
    private static final class Stamp extends SavedData {
        String version = "";

        static Stamp load(CompoundTag tag) {
            Stamp s = new Stamp();
            s.version = tag.getString("version");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putString("version", version);
            return tag;
        }
    }

    private static String version() {
        return ModList.get().getModContainerById(GeysersMod.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("0");
    }

    private static Stamp stamp(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(Stamp::load, Stamp::new, DATA);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TOLD.clear();
        madeWith = null;
        ServerLevel overworld = event.getServer().overworld();
        Stamp stamp = stamp(overworld);
        String now = version();
        if (stamp.version.isEmpty()) {
            // A world without a stamp is new, or was made before the stamp was kept -- or without the mod.
            if (overworld.getGameTime() > NEW_WORLD) madeWith = "";
        } else if (new ComparableVersion(stamp.version).compareTo(new ComparableVersion(now)) < 0) {
            madeWith = stamp.version;
        }
        // Stamped now unless there is something to say, in which case once it has been said.
        if (madeWith == null) {
            stamp.version = now;
            stamp.setDirty();
        }
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!server.isSingleplayerOwner(player.getGameProfile()) && !player.hasPermissions(2)) return;

        String made = madeWith;
        if (made != null) {
            madeWith = null;
            if (GeyserConfig.OLDER_WORLD_NOTICE.get()) {
                player.sendSystemMessage((made.isEmpty()
                        ? Component.translatable("message.fts_geology.older_world_unknown")
                        : Component.translatable("message.fts_geology.older_world", made))
                        .withStyle(ChatFormatting.GRAY));
            }
            Stamp stamp = stamp(server.overworld());
            stamp.version = version();
            stamp.setDirty();
        }

        if (!GeyserConfig.UPDATE_NOTICE.get() || !TOLD.add(player.getUUID())) return;
        VersionChecker.CheckResult result = ModList.get().getModContainerById(GeysersMod.MODID)
                .map(c -> VersionChecker.getResult(c.getModInfo())).orElse(null);
        if (result == null || result.target() == null) return;
        if (result.status() != VersionChecker.Status.OUTDATED && result.status() != VersionChecker.Status.BETA_OUTDATED) {
            return;
        }
        Component message = Component.translatable("message.fts_geology.update_available",
                result.target().toString(), version()).withStyle(ChatFormatting.GOLD);
        if (result.url() != null && !result.url().isEmpty()) {
            message = message.copy().append(" ").append(Component.translatable("message.fts_geology.update_link")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, result.url()))));
        }
        player.sendSystemMessage(message);
    }
}
