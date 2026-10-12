package com.jeladastudios.ftsgeology.registry;

import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Vanilla's ores in the mod's own rocks: iron in gneiss, gold in quartzite, lapis in marble, the ore's grains on the
 * rock's own face, the way Spelunkery does it for granite, andesite and diorite. Only the pairs a geologist meets:
 * coal in slate and shale (anthracite and black shale), gold and copper in the metamorphic belts' schist and gneiss,
 * banded iron in quartzite and chert, lapis and emerald in marble, diamond in peridotite (kimberlite's mantle rock),
 * magnetite and copper in gabbro, chromite-stained iron and cinnabar-red ore in serpentinite, gold, copper and redstone
 * in rhyolite's epithermal veins. Any other vanilla ore that lands in these rocks stays the stone one.
 *
 * <p>An ore behaves as vanilla's of the same metal: the same drops, Fortune and Silk Touch, experience and tool; the
 * redstone ones glow when touched. A rock ore is a little harder than its rock, as vanilla's ores are than stone.</p>
 *
 * <p>Where they come from: vanilla's ore features place them in the rocks (OreFeatureMixin), the mod's own deposits
 * (OreGenesis) and its deep geology in other world types (DeepStructure) write them where they meet the rock.</p>
 */
public final class RockOreBlocks {

    private RockOreBlocks() {}

    public enum Ore {
        COAL("coal", UniformInt.of(0, 2)),
        IRON("iron", ConstantInt.of(0)),
        COPPER("copper", ConstantInt.of(0)),
        GOLD("gold", ConstantInt.of(0)),
        REDSTONE("redstone", ConstantInt.of(0)),
        LAPIS("lapis", UniformInt.of(2, 5)),
        DIAMOND("diamond", UniformInt.of(3, 7)),
        EMERALD("emerald", UniformInt.of(3, 7));

        public final String id;
        final IntProvider xp;

        Ore(String id, IntProvider xp) {
            this.id = id;
            this.xp = xp;
        }
    }

    /** A rock and the ores it holds, in the order the creative tab lists them. */
    private record Rock(String id, Supplier<Block> block, Ore... ores) {}

    private static final List<Rock> ROCKS = List.of(
            new Rock("schist", ModBlocks.SCHIST, Ore.GOLD, Ore.EMERALD, Ore.IRON, Ore.COPPER),
            new Rock("gneiss", ModBlocks.GNEISS, Ore.IRON, Ore.GOLD, Ore.LAPIS, Ore.COPPER),
            new Rock("slate", ModBlocks.SLATE, Ore.COAL, Ore.GOLD, Ore.IRON),
            new Rock("shale", ModBlocks.SHALE, Ore.COAL, Ore.IRON, Ore.COPPER),
            new Rock("quartzite", ModBlocks.QUARTZITE, Ore.GOLD, Ore.IRON),
            new Rock("marble", ModBlocks.MARBLE, Ore.LAPIS, Ore.EMERALD),
            new Rock("gabbro", ModBlocks.GABBRO, Ore.IRON, Ore.COPPER),
            new Rock("peridotite", ModBlocks.PERIDOTITE, Ore.DIAMOND, Ore.COPPER),
            new Rock("serpentinite", ModBlocks.SERPENTINITE, Ore.IRON, Ore.REDSTONE),
            new Rock("rhyolite", ModBlocks.RHYOLITE, Ore.GOLD, Ore.COPPER, Ore.REDSTONE),
            new Rock("chert", ModBlocks.CHERT, Ore.IRON));

    /** Every rock ore, by registry name ({@code schist_iron_ore}), in creative-tab order. */
    public static final Map<String, RegistryObject<Block>> BLOCKS = new LinkedHashMap<>();
    public static final Map<String, RegistryObject<Item>> ITEMS = new LinkedHashMap<>();

    /** Called at the end of ModBlocks' static set-up, after the rocks themselves. */
    static void registerBlocks() {
        for (Rock r : ROCKS) for (Ore o : r.ores) {
            String name = r.id + "_" + o.id + "_ore";
            BLOCKS.put(name, ModBlocks.BLOCKS.register(name, () -> make(r.block.get(), o)));
        }
    }

    /** Called at the end of ModItems' static set-up. */
    static void registerItems() {
        for (Map.Entry<String, RegistryObject<Block>> e : BLOCKS.entrySet()) {
            RegistryObject<Block> b = e.getValue();
            ITEMS.put(e.getKey(), ModItems.ITEMS.register(e.getKey(), () -> new BlockItem(b.get(), new Item.Properties())));
        }
    }

    private static Block make(Block rock, Ore ore) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.copy(rock)
                .strength(rock.defaultDestroyTime() + 1.5F, Math.max(3.0F, rock.getExplosionResistance()))
                .requiresCorrectToolForDrops();
        if (ore == Ore.REDSTONE) {
            return new RedStoneOreBlock(p.randomTicks().lightLevel(s -> s.getValue(RedStoneOreBlock.LIT) ? 9 : 0));
        }
        return new DropExperienceBlock(p, ore.xp);
    }

    // ---- which rock ore an ore becomes ----

    private static final class Maps {
        /** Rock block -> its ores by Ore ordinal, null where the rock has none of that ore. */
        static final Map<Block, Block[]> BY_ROCK = new IdentityHashMap<>();
        /** Vanilla ore block (stone and deepslate) -> the ore it is. */
        static final Map<Block, Ore> VANILLA = new IdentityHashMap<>();
        /** Rock ore block -> its rock. */
        static final Map<Block, Block> ROCK_OF = new IdentityHashMap<>();

        static {
            for (Rock r : ROCKS) {
                Block[] row = new Block[Ore.values().length];
                for (Ore o : r.ores) {
                    Block b = BLOCKS.get(r.id + "_" + o.id + "_ore").get();
                    row[o.ordinal()] = b;
                    ROCK_OF.put(b, r.block.get());
                }
                BY_ROCK.put(r.block.get(), row);
            }
            put(Ore.COAL, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
            put(Ore.IRON, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
            put(Ore.COPPER, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
            put(Ore.GOLD, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
            put(Ore.REDSTONE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
            put(Ore.LAPIS, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
            put(Ore.DIAMOND, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
            put(Ore.EMERALD, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        }

        private static void put(Ore o, Block... ores) {
            for (Block b : ores) VANILLA.put(b, o);
        }
    }

    /** True for the rocks that have ores of their own. */
    public static boolean holdsOres(BlockState s) {
        return Maps.BY_ROCK.containsKey(s.getBlock());
    }

    /**
     * What a vanilla ore becomes in this rock: its rock ore, or the ore as it is where the rock has none of that
     * metal or it is not one of the rocks.
     */
    public static BlockState inRock(BlockState rock, BlockState ore) {
        Block[] row = Maps.BY_ROCK.get(rock.getBlock());
        if (row == null) return ore;
        Ore o = Maps.VANILLA.get(ore.getBlock());
        if (o == null || row[o.ordinal()] == null) return ore;
        return row[o.ordinal()].defaultBlockState();
    }

    /** The rock a rock ore sits in, or null if the block is not one. */
    public static Block rockOf(Block ore) {
        return Maps.ROCK_OF.get(ore);
    }

    /** All rock ores, for the code that lists ore blocks. */
    public static List<Block> all() {
        List<Block> l = new ArrayList<>();
        for (RegistryObject<Block> b : BLOCKS.values()) l.add(b.get());
        return Collections.unmodifiableList(l);
    }
}
