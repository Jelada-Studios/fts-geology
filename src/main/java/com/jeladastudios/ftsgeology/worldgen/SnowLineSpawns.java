package com.jeladastudios.ftsgeology.worldgen;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;

import java.util.concurrent.atomic.LongAdder;

/**
 * Animals by height as well as by biome, in the mod's own world types.
 *
 * <p>A biome says what lives in it, not how high it is, and the mod's mountains carry one biome from the valley floor
 * to the summit: geladas were born on a five-thousand-metre peak because the range around it is theirs. Terralith's
 * highlands in the tall world stand that high too. So above the snow line, where the ground is cold enough for snow
 * to lie, only the animals of the high snow are born -- goats, hares, the snow leopard, the eagle -- and the rest
 * keep to the country below. Only births the world makes on its own are held to it: a spawn egg, a spawner or a
 * breeding pair is the player's business.</p>
 */
public final class SnowLineSpawns {

    private SnowLineSpawns() {}

    /** The animals that live above the snow line. Another mod's are named as optional entries. */
    public static final TagKey<EntityType<?>> ABOVE_SNOW_LINE =
            TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(GeysersMod.MODID, "above_snow_line"));

    private static final LongAdder KEPT_DOWN = new LongAdder();

    public static void check(MobSpawnEvent.SpawnPlacementCheck e) {
        MobSpawnType why = e.getSpawnType();
        if (why != MobSpawnType.NATURAL && why != MobSpawnType.CHUNK_GENERATION) return;
        EntityType<?> type = e.getEntityType();
        if (type.getCategory() != MobCategory.CREATURE || type.is(ABOVE_SNOW_LINE)) return;
        ServerLevelAccessor level = e.getLevel();
        if (!GeologyWorld.isOwn(level.getLevel())) return;
        BlockPos pos = e.getPos();
        if (pos.getY() < SnowCover.line() || !level.getBiome(pos).value().coldEnoughToSnow(pos)) return;
        e.setResult(Event.Result.DENY);
        KEPT_DOWN.increment();
    }

    /** How many births the snow line has turned away, for a test to read. */
    public static String summary() {
        return "snow line: " + KEPT_DOWN.sum() + " animals kept below it";
    }
}
