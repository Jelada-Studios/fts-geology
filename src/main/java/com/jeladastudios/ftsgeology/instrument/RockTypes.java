package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What kind of rock a block is, and how it got there.
 *
 * <h2>Why classify at all</h2>
 * Minecraft has about thirty stone-like blocks and treats them as textures. Geology treats them as
 * <em>evidence</em>: basalt means lava reached the surface here, sandstone means this was a beach or
 * a desert, deepslate means the rock has been buried deep enough and long enough to recrystallise.
 * A player who can read that off a cliff face is doing the thing the mod is about, and the
 * geologist's hammer is what puts it in their hands.
 *
 * <p>Rock <em>names</em> are deliberately not stored here - the block's own translated name is
 * already correct in every language Minecraft ships, and duplicating it would only mean it going
 * stale. What is stored is the part vanilla has no opinion about: the class, and the one-line
 * origin story that goes with it.</p>
 */
public final class RockTypes {

    private RockTypes() {}

    /** The three great classes, plus the categories a strict three-way split has no room for. */
    public enum Rock {
        /** Cooled from melt at the surface: lava flows, ash, volcanic glass. */
        VOLCANIC("volcanic"),
        /** Cooled from melt at depth, slowly enough to grow visible crystals. */
        PLUTONIC("plutonic"),
        /** Laid down grain by grain, or precipitated out of water. */
        SEDIMENTARY("sedimentary"),
        /** Cooked and squeezed until it recrystallised without ever melting. */
        METAMORPHIC("metamorphic"),
        /** Loose material that has not been turned to rock yet. */
        SEDIMENT("sediment"),
        /** Soil: the weathered skin on top of everything else. */
        SOIL("soil"),
        /** Laid down by living things, or by the chemistry they drive. */
        BIOGENIC("biogenic"),
        /** A mineral concentration worth mining. */
        ORE("ore"),
        /** Ice, water, air, or something the mod has no opinion about. */
        OTHER("other");

        private final String key;

        Rock(String key) {
            this.key = key;
        }

        /** Translation key for the class name. */
        public String nameKey() {
            return "rock.fts_geology.class." + key;
        }

        /** Translation key for the one-line origin. */
        public String originKey() {
            return "rock.fts_geology.origin." + key;
        }
    }

    /**
     * Which class a block belongs to.
     *
     * <p>Order matters: the specific cases come before the tag sweeps, because
     * {@code BlockTags.BASE_STONE_OVERWORLD} would otherwise swallow granite, diorite and
     * deepslate into one undifferentiated "stone" and throw away the whole point.</p>
     */
    public static Rock classify(BlockState s) {
        if (s.isAir()) return Rock.OTHER;

        // --- Igneous, surface -------------------------------------------------
        // Everything here is a lava flow or what an eruption threw out. Basalt and blackstone are
        // flow rock, tuff is welded ash, obsidian is lava that cooled too fast to crystallise at
        // all - which is exactly why it has no grain and breaks like glass.
        if (s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.POLISHED_BASALT)
                || s.is(Blocks.BLACKSTONE) || s.is(Blocks.POLISHED_BLACKSTONE)
                || s.is(Blocks.TUFF) || s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.NETHERRACK)) {
            return Rock.VOLCANIC;
        }

        // --- Igneous, at depth ------------------------------------------------
        // Granite and diorite are magma that never got out: it cooled underground over thousands of
        // years, which is the only way crystals get big enough to see. Andesite sits between the
        // two in composition and is put here with them.
        if (s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(Blocks.ANDESITE)
                || s.is(Blocks.POLISHED_GRANITE) || s.is(Blocks.POLISHED_DIORITE)
                || s.is(Blocks.POLISHED_ANDESITE)) {
            return Rock.PLUTONIC;
        }

        // --- Metamorphic ------------------------------------------------------
        // Deepslate is the mod's slate: mudstone buried deep enough and long enough to recrystallise
        // without ever melting. That it only appears below y=0 in vanilla is, for once, correct.
        if (s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE)
                || s.is(Blocks.POLISHED_DEEPSLATE) || s.is(Blocks.DEEPSLATE_BRICKS)
                || s.is(Blocks.DEEPSLATE_TILES)) {
            return Rock.METAMORPHIC;
        }

        // --- Ores -------------------------------------------------------------
        if (s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES)
                || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.DIAMOND_ORES)
                || s.is(BlockTags.EMERALD_ORES) || s.is(Blocks.ANCIENT_DEBRIS)
                || s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.NETHER_GOLD_ORE)) {
            return Rock.ORE;
        }

        // --- Laid down by life, or by the chemistry life drives ----------------
        // The mod's own hot-spring blocks belong here as much as coral does: a sinter terrace is
        // built by silica coming out of solution around the mats growing in it.
        if (s.is(ModBlocks.SINTER.get()) || s.is(ModBlocks.MICROBIAL_MAT_ORANGE.get())
                || s.is(ModBlocks.MICROBIAL_MAT_YELLOW.get())
                || s.is(ModBlocks.MICROBIAL_MAT_BROWN.get())
                || s.is(ModBlocks.MICROBIAL_MAT_GREEN.get())
                || s.is(BlockTags.CORAL_BLOCKS) || s.is(Blocks.DRIED_KELP_BLOCK)) {
            return Rock.BIOGENIC;
        }

        // --- Sedimentary rock -------------------------------------------------
        // Sandstone is a dune or a beach that got buried and cemented; calcite and dripstone came
        // out of solution. Native sulfur is a fumarole deposit - a chemical sediment laid straight
        // out of a gas.
        if (s.is(Blocks.SANDSTONE) || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.CUT_SANDSTONE)
                || s.is(Blocks.CHISELED_SANDSTONE)
                || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.SMOOTH_RED_SANDSTONE)
                || s.is(Blocks.CUT_RED_SANDSTONE) || s.is(Blocks.CHISELED_RED_SANDSTONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
                || s.is(Blocks.PACKED_MUD) || s.is(Blocks.MUD_BRICKS)
                || s.is(ModBlocks.NATIVE_SULFUR.get())) {
            return Rock.SEDIMENTARY;
        }

        // --- Loose sediment ---------------------------------------------------
        if (s.is(Blocks.SAND) || s.is(Blocks.RED_SAND) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.SOUL_SAND)
                || s.is(Blocks.SOUL_SOIL)) {
            return Rock.SEDIMENT;
        }

        // --- Soil -------------------------------------------------------------
        if (s.is(BlockTags.DIRT) || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH)) {
            return Rock.SOIL;
        }

        // --- Anything else stony ----------------------------------------------
        // Plain stone last, so it is the fallback rather than the answer. Vanilla's "stone" is not
        // a named rock, it is simply the bulk of the crust - and the bulk of the upper continental
        // crust really is granitic in composition, so putting it with the plutonic rocks is the
        // honest answer rather than a shrug.
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.STONE_BRICKS)) {
            return Rock.PLUTONIC;
        }

        return Rock.OTHER;
    }

    /** True where the hammer has something to say - i.e. it is looking at rock, not at a fence. */
    public static boolean isRock(BlockState s) {
        Rock r = classify(s);
        return r != Rock.OTHER;
    }
}
