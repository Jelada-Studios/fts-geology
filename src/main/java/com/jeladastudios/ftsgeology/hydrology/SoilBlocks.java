package com.jeladastudios.ftsgeology.hydrology;

import com.jeladastudios.ftsgeology.GeysersMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.common.Tags;

/**
 * The ground's blocks as the water in it sees them, other mods' soils among them.
 *
 * <p>Immersive Weathering (LGPLv3) lays its own soils -- loam, silt, earthen clay, sandy dirt, their grassy and tilled
 * forms -- and The Roads More Travelled (CC BY-NC) wears paths into the grass where things walk: eroded grass, dirt
 * and sand, each with the direction of the wear and how far it has gone. Both are met by their blocks' names and by
 * tags of this mod's own ({@code data/fts_geology/tags/blocks}), never by their code: which soil holds water how,
 * which blocks are ground and not a build, what dies back to what in a drought and grows back after it.</p>
 */
public final class SoilBlocks {

    private SoilBlocks() {}

    /** Ground no tag of the game's own names: worn paths, sand layers. Earthquakes and the settling take it for land. */
    public static final TagKey<Block> NATURAL_GROUND = tag("natural_ground");
    /** Soils that hold water as clay does, and as sand does, over what their other tags would make them. */
    public static final TagKey<Block> CLAY_SOIL = tag("soil/clay");
    public static final TagKey<Block> SANDY_SOIL = tag("soil/sand");
    /** Tilled ground: the game's farmland and other mods' like it, all with the game's moisture. */
    public static final TagKey<Block> FARMLAND = tag("farmland");
    /** Ground that goes to mud under water standing on it for days. */
    public static final TagKey<Block> TURNS_TO_MUD = tag("turns_to_mud");
    /** A mulch over the soil: the sun takes half as much out of the ground under it. */
    public static final TagKey<Block> MULCH = tag("mulch");

    private static TagKey<Block> tag(String path) {
        return BlockTags.create(new ResourceLocation(GeysersMod.MODID, path));
    }

    /** Tilled ground with the game's moisture on it. */
    public static boolean farmland(BlockState s) {
        return (s.is(Blocks.FARMLAND) || s.is(FARMLAND)) && s.hasProperty(FarmBlock.MOISTURE);
    }

    /** Immersive Weathering's grassy soils and what they are without their grass. */
    private static final String[][] WEATHERING_GRASS = {
            {"grassy_sandy_dirt", "sandy_dirt"}, {"grassy_earthen_clay", "earthen_clay"}, {"grassy_silt", "silt"}};

    /**
     * Grass and the soil it dies back to, or null for anything else: the game's grass to dirt, over sand or gravel to
     * coarse ground (Immersive Weathering's sandy dirt where it is there); that mod's grassy soils to their soils and
     * its rooted grass to rooted dirt; a worn path's grass to the path's dirt, worn the same way and as far.
     */
    public static BlockState deadOf(BlockState s, BlockState under) {
        if (s.is(Blocks.GRASS_BLOCK)) {
            if (under.is(BlockTags.SAND) || under.is(Tags.Blocks.GRAVEL)) {
                BlockState sandy = block("immersive_weathering", "sandy_dirt");
                return sandy != null ? sandy : Blocks.COARSE_DIRT.defaultBlockState();
            }
            return Blocks.DIRT.defaultBlockState();
        }
        String name = name(s);
        for (String[] pair : WEATHERING_GRASS) {
            if (name.equals("immersive_weathering:" + pair[0])) return block("immersive_weathering", pair[1]);
        }
        if (name.equals("immersive_weathering:rooted_grass_block")) return Blocks.ROOTED_DIRT.defaultBlockState();
        if (name.equals("trmt:eroded_grass_block")) return like(s, block("trmt", "eroded_dirt"));
        return null;
    }

    /**
     * The grass that grows again over soil that died back (see {@link #deadOf}), over {@code under}; null over anything
     * else. Sandy dirt over sand or gravel was the game's grass there; elsewhere it was Immersive Weathering's own.
     */
    public static BlockState grassOf(BlockState s, BlockState under) {
        if (s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)) return Blocks.GRASS_BLOCK.defaultBlockState();
        String name = name(s);
        if (name.equals("immersive_weathering:sandy_dirt") && (under.is(BlockTags.SAND) || under.is(Tags.Blocks.GRAVEL))) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        for (String[] pair : WEATHERING_GRASS) {
            if (name.equals("immersive_weathering:" + pair[1])) return block("immersive_weathering", pair[0]);
        }
        if (s.is(Blocks.ROOTED_DIRT)) return block("immersive_weathering", "rooted_grass_block");
        if (name.equals("trmt:eroded_dirt")) return like(s, block("trmt", "eroded_grass_block"));
        return null;
    }

    /** Mud dried out: Immersive Weathering's silt where it is there, the fine ground standing water leaves; else dirt. */
    public static BlockState driedMud() {
        BlockState silt = block("immersive_weathering", "silt");
        return silt != null ? silt : Blocks.DIRT.defaultBlockState();
    }

    /** Mud dried back to what was made of it (see {@link #driedMud}). */
    public static boolean isDriedMud(BlockState s) {
        return s.is(Blocks.DIRT) || name(s).equals("immersive_weathering:silt");
    }

    /**
     * {@code to} with the properties {@code from} shares with it by name -- a worn path's direction and its stage --
     * a stage past the last {@code to} has taken as its last.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState like(BlockState from, BlockState to) {
        if (to == null) return null;
        for (Property<?> p : to.getProperties()) {
            for (Property<?> q : from.getProperties()) {
                if (!q.getName().equals(p.getName())) continue;
                Comparable v = from.getValue(q);
                if (p.getPossibleValues().contains(v)) {
                    to = to.setValue((Property) p, v);
                } else if (p instanceof IntegerProperty ip && v instanceof Integer n) {
                    int best = ip.getPossibleValues().stream().mapToInt(Integer::intValue).filter(k -> k <= n).max()
                            .orElse(ip.getPossibleValues().iterator().next());
                    to = to.setValue(ip, best);
                }
            }
        }
        return to;
    }

    static BlockState block(String mod, String path) {
        Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(mod, path));
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }

    static String name(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }
}
