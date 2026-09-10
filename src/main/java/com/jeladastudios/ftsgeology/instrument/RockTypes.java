package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.registry.ModBlocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What kind of rock a block is, and how it got there: basalt means lava reached the surface,
 * sandstone a beach or desert, deepslate deep burial. Names come from the block's own translation;
 * what is stored here is the class and a one-line origin.
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
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.NETHERRACK)
                || isRhyolite(s) || s.is(ModBlocks.COOLING_LAVA_CRUST.get()) || s.is(ModBlocks.VOLCANIC_ASH.get())) {
            return Rock.VOLCANIC;
        }

        // --- Igneous, at depth ------------------------------------------------
        // Granite and diorite are magma that never got out: it cooled underground over thousands of
        // years, which is the only way crystals get big enough to see. Andesite sits between the
        // two in composition and is put here with them. Gabbro is the same story for basalt, and
        // peridotite and serpentinite come up from the mantle itself.
        if (s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(Blocks.ANDESITE)
                || s.is(Blocks.POLISHED_GRANITE) || s.is(Blocks.POLISHED_DIORITE)
                || s.is(Blocks.POLISHED_ANDESITE)
                || isGabbro(s) || isPeridotite(s) || isSerpentinite(s)) {
            return Rock.PLUTONIC;
        }

        // --- Metamorphic ------------------------------------------------------
        // Deepslate is the mod's slate: mudstone buried deep enough and long enough to recrystallise
        // without ever melting. That it only appears below y=0 in vanilla is, for once, correct.
        // Slate, schist, gneiss, marble and quartzite are the rest of that sequence, from barely
        // cooked mudstone up to the roots of a collision belt.
        if (s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE)
                || s.is(Blocks.POLISHED_DEEPSLATE) || s.is(Blocks.DEEPSLATE_BRICKS)
                || s.is(Blocks.DEEPSLATE_TILES)
                || isSlate(s) || isSchist(s) || isGneiss(s) || isMarble(s) || isQuartzite(s)) {
            return Rock.METAMORPHIC;
        }

        // --- Ores -------------------------------------------------------------
        if (s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES)
                || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.DIAMOND_ORES)
                || s.is(BlockTags.EMERALD_ORES) || s.is(Blocks.ANCIENT_DEBRIS)
                || s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.NETHER_GOLD_ORE)
                || s.is(ModBlocks.PYRITE.get()) || s.is(ModBlocks.CHALCOPYRITE.get())
                || s.is(ModBlocks.AZURITE.get()) || s.is(ModBlocks.CINNABAR.get())
                || s.is(ModBlocks.GALENA.get()) || s.is(ModBlocks.MALACHITE.get())
                || s.is(ModBlocks.QUARTZ_VEIN.get())) {
            return Rock.ORE;
        }

        // --- Laid down by life, or by the chemistry life drives ----------------
        // The mod's own hot-spring blocks belong here as much as coral does: a sinter terrace is
        // built by silica coming out of solution around the mats growing in it.
        if (s.is(ModBlocks.SINTER.get()) || s.is(ModBlocks.SINTER_CRUST.get())
                || s.is(ModBlocks.MICROBIAL_MAT_ORANGE.get())
                || s.is(ModBlocks.MICROBIAL_MAT_YELLOW.get())
                || s.is(ModBlocks.MICROBIAL_MAT_BROWN.get())
                || s.is(ModBlocks.MICROBIAL_MAT_GREEN.get())
                || s.is(BlockTags.CORAL_BLOCKS) || s.is(Blocks.DRIED_KELP_BLOCK)) {
            return Rock.BIOGENIC;
        }

        // --- Sedimentary rock -------------------------------------------------
        // Sandstone is a dune or a beach that got buried and cemented; calcite and dripstone came
        // out of solution. Native sulfur is a fumarole deposit - a chemical sediment laid straight
        // out of a gas. Shale and chert are bedded sediments, travertine a hot spring's precipitate.
        if (s.is(Blocks.SANDSTONE) || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.CUT_SANDSTONE)
                || s.is(Blocks.CHISELED_SANDSTONE)
                || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.SMOOTH_RED_SANDSTONE)
                || s.is(Blocks.CUT_RED_SANDSTONE) || s.is(Blocks.CHISELED_RED_SANDSTONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
                || s.is(Blocks.PACKED_MUD) || s.is(Blocks.MUD_BRICKS)
                || s.is(ModBlocks.NATIVE_SULFUR.get())
                || isShale(s) || isChert(s) || isTravertine(s)) {
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
        // Plain stone last, as the fallback: the upper continental crust is granitic on average.
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.STONE_BRICKS)) {
            return Rock.PLUTONIC;
        }

        return Rock.OTHER;
    }

    // A rock counts in every worked form - a polished slab of gabbro is still gabbro to the hammer.

    private static boolean isRhyolite(BlockState s) {
        return s.is(ModBlocks.RHYOLITE.get()) || s.is(ModBlocks.POLISHED_RHYOLITE.get())
                || s.is(ModBlocks.RHYOLITE_SLAB.get()) || s.is(ModBlocks.RHYOLITE_STAIRS.get())
                || s.is(ModBlocks.RHYOLITE_WALL.get());
    }

    private static boolean isGabbro(BlockState s) {
        return s.is(ModBlocks.GABBRO.get()) || s.is(ModBlocks.POLISHED_GABBRO.get())
                || s.is(ModBlocks.GABBRO_SLAB.get()) || s.is(ModBlocks.GABBRO_STAIRS.get())
                || s.is(ModBlocks.GABBRO_WALL.get());
    }

    private static boolean isPeridotite(BlockState s) {
        return s.is(ModBlocks.PERIDOTITE.get()) || s.is(ModBlocks.POLISHED_PERIDOTITE.get())
                || s.is(ModBlocks.PERIDOTITE_SLAB.get()) || s.is(ModBlocks.PERIDOTITE_STAIRS.get())
                || s.is(ModBlocks.PERIDOTITE_WALL.get());
    }

    private static boolean isSerpentinite(BlockState s) {
        return s.is(ModBlocks.SERPENTINITE.get()) || s.is(ModBlocks.POLISHED_SERPENTINITE.get())
                || s.is(ModBlocks.SERPENTINITE_SLAB.get()) || s.is(ModBlocks.SERPENTINITE_STAIRS.get())
                || s.is(ModBlocks.SERPENTINITE_WALL.get());
    }

    private static boolean isSlate(BlockState s) {
        return s.is(ModBlocks.SLATE.get()) || s.is(ModBlocks.POLISHED_SLATE.get())
                || s.is(ModBlocks.SLATE_SLAB.get()) || s.is(ModBlocks.SLATE_STAIRS.get())
                || s.is(ModBlocks.SLATE_WALL.get());
    }

    private static boolean isSchist(BlockState s) {
        return s.is(ModBlocks.SCHIST.get()) || s.is(ModBlocks.POLISHED_SCHIST.get())
                || s.is(ModBlocks.SCHIST_SLAB.get()) || s.is(ModBlocks.SCHIST_STAIRS.get())
                || s.is(ModBlocks.SCHIST_WALL.get());
    }

    private static boolean isGneiss(BlockState s) {
        return s.is(ModBlocks.GNEISS.get()) || s.is(ModBlocks.POLISHED_GNEISS.get())
                || s.is(ModBlocks.GNEISS_SLAB.get()) || s.is(ModBlocks.GNEISS_STAIRS.get())
                || s.is(ModBlocks.GNEISS_WALL.get());
    }

    private static boolean isMarble(BlockState s) {
        return s.is(ModBlocks.MARBLE.get()) || s.is(ModBlocks.POLISHED_MARBLE.get())
                || s.is(ModBlocks.MARBLE_SLAB.get()) || s.is(ModBlocks.MARBLE_STAIRS.get())
                || s.is(ModBlocks.MARBLE_WALL.get());
    }

    private static boolean isQuartzite(BlockState s) {
        return s.is(ModBlocks.QUARTZITE.get()) || s.is(ModBlocks.POLISHED_QUARTZITE.get())
                || s.is(ModBlocks.QUARTZITE_SLAB.get()) || s.is(ModBlocks.QUARTZITE_STAIRS.get())
                || s.is(ModBlocks.QUARTZITE_WALL.get());
    }

    private static boolean isShale(BlockState s) {
        return s.is(ModBlocks.SHALE.get()) || s.is(ModBlocks.POLISHED_SHALE.get())
                || s.is(ModBlocks.SHALE_SLAB.get()) || s.is(ModBlocks.SHALE_STAIRS.get())
                || s.is(ModBlocks.SHALE_WALL.get());
    }

    private static boolean isChert(BlockState s) {
        return s.is(ModBlocks.CHERT.get()) || s.is(ModBlocks.POLISHED_CHERT.get())
                || s.is(ModBlocks.CHERT_SLAB.get()) || s.is(ModBlocks.CHERT_STAIRS.get())
                || s.is(ModBlocks.CHERT_WALL.get());
    }

    private static boolean isTravertine(BlockState s) {
        return s.is(ModBlocks.TRAVERTINE.get()) || s.is(ModBlocks.POLISHED_TRAVERTINE.get())
                || s.is(ModBlocks.TRAVERTINE_SLAB.get()) || s.is(ModBlocks.TRAVERTINE_STAIRS.get())
                || s.is(ModBlocks.TRAVERTINE_WALL.get());
    }

    /** True where the hammer has something to say - i.e. it is looking at rock, not at a fence. */
    public static boolean isRock(BlockState s) {
        Rock r = classify(s);
        return r != Rock.OTHER;
    }

    /** How readily this rock gives way, from 0.2 (granite, gabbro) to 1 (loose sediment and soil). */
    public static double erodibility(BlockState s) {
        return switch (classify(s)) {
            case SEDIMENT, SOIL -> 1.0;
            case SEDIMENTARY -> 0.7;
            case BIOGENIC -> 0.6;
            case METAMORPHIC -> 0.35;
            case VOLCANIC -> 0.3;
            case PLUTONIC -> 0.2;
            default -> 0.5;
        };
    }
}
