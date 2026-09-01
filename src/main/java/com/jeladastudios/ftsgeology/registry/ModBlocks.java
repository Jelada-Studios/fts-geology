package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.block.GeyserChamberBlock;
import com.jeladastudios.ftsgeology.block.GeyserCoreBlock;
import com.jeladastudios.ftsgeology.block.GeyserIgniterBlock;
import com.jeladastudios.ftsgeology.block.HotSpringBlock;
import com.jeladastudios.ftsgeology.block.VolcanoCoreBlock;
import com.jeladastudios.ftsgeology.block.VolcanoIgniterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** All blocks introduced by the mod. Both are technical blocks placed by worldgen/retrogen. */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, GeysersMod.MODID);

    /** The thermodynamic brain: holds temperature/pressure state and ticks. */
    public static final RegistryObject<Block> GEYSER_CORE = BLOCKS.register("geyser_core",
            () -> new GeyserCoreBlock(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE)
                    .mapColor(MapColor.COLOR_BLACK)
                    .lightLevel(s -> 6)          // faint glow so you can find it when digging down
                    .strength(-1.0F, 3600000.0F) // unbreakable by hand; technical block
                    .noLootTable()));

    /** Marks the water/steam accumulation chamber volume around the core. */
    public static final RegistryObject<Block> GEYSER_CHAMBER = BLOCKS.register("geyser_chamber",
            () -> new GeyserChamberBlock(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()));

    /** Player-placeable "seed" that forms a geyser below it after a short delay. */
    public static final RegistryObject<Block> GEYSER_IGNITER = BLOCKS.register("geyser_igniter",
            () -> new GeyserIgniterBlock(BlockBehaviour.Properties.copy(Blocks.MAGMA_BLOCK)
                    .lightLevel(s -> 7)
                    .strength(1.5F)));

    /** Warm bed of a hot spring — put water on top for a cosy, snow-melting, freeze-proof pool. */
    public static final RegistryObject<Block> HOT_SPRING = BLOCKS.register("hot_spring",
            () -> new HotSpringBlock(BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .lightLevel(s -> 4)
                    .strength(1.2F)));

    /** Technical block driving a volcano's eruption cycle. */
    public static final RegistryObject<Block> VOLCANO_CORE = BLOCKS.register("volcano_core",
            () -> new VolcanoCoreBlock(BlockBehaviour.Properties.copy(Blocks.BASALT)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()));

    /** Player-placeable seed that carves a whole volcano below it after a short delay. */
    public static final RegistryObject<Block> VOLCANO_IGNITER = BLOCKS.register("volcano_igniter",
            () -> new VolcanoIgniterBlock(BlockBehaviour.Properties.copy(Blocks.MAGMA_BLOCK)
                    .lightLevel(s -> 9)
                    .strength(2.0F)));

    /**
     * Native sulfur: the yellow crust that grows around volcanic fumaroles and vents as escaping
     * gases oxidise on contact with air. The acidic counterpart to the calcite/travertine this mod
     * deposits around alkaline geyser runoff - together they show that what a hot spring leaves
     * behind depends on its chemistry. Breakable and collectable, unlike the technical blocks.
     */
    public static final RegistryObject<Block> NATIVE_SULFUR = BLOCKS.register("native_sulfur",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(0.8F)));


    /**
     * Sinter: the pale silica shelf a hot spring builds at its own lip.
     *
     * <p>Water that has been down through hot rock comes back up carrying dissolved silica, and
     * drops it the moment it cools at the surface. Over time that armours the pool in a hard white
     * rim - the terraces at Mammoth and the shelf around every Yellowstone spring are this. It is
     * the alkaline counterpart to the sulfur that crusts an acidic fumarole, and having both means
     * the ground around a spring tells you its chemistry.</p>
     */
    public static final RegistryObject<Block> SINTER = BLOCKS.register("sinter",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .mapColor(MapColor.TERRACOTTA_WHITE)
                    .strength(0.9F)));

    // --- Microbial mats ------------------------------------------------------
    //
    // The colours ringing a hot spring are alive. Each band is a different community of heat-loving
    // microorganisms, and each community can only live in its own temperature range - so the rings
    // are a thermometer you can see from the air. Nearest the boiling centre almost nothing grows
    // and the water is clear blue; then orange, yellow, brown and finally green as it cools outward.
    // That is what makes Grand Prismatic look the way it does, and why these four are laid down in
    // order rather than at random.

    /** Hottest mat: the fierce orange ring closest to the boiling centre. */
    public static final RegistryObject<Block> MICROBIAL_MAT_ORANGE = BLOCKS.register("microbial_mat_orange",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(0.6F)));

    /** A little cooler: the yellow-green band outside the orange. */
    public static final RegistryObject<Block> MICROBIAL_MAT_YELLOW = BLOCKS.register("microbial_mat_yellow",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(0.6F)));

    /** Cooler still: the broad brown apron where the runoff has lost most of its heat. */
    public static final RegistryObject<Block> MICROBIAL_MAT_BROWN = BLOCKS.register("microbial_mat_brown",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .strength(0.6F)));

    /** The outermost, coolest band, where ordinary green algae can finally survive. */
    public static final RegistryObject<Block> MICROBIAL_MAT_GREEN = BLOCKS.register("microbial_mat_green",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(0.6F)));

    private ModBlocks() {}
}
