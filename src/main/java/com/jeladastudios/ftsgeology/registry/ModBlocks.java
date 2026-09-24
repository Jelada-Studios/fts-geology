package com.jeladastudios.ftsgeology.registry;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.block.GeyserChamberBlock;
import com.jeladastudios.ftsgeology.block.GeyserCoreBlock;
import com.jeladastudios.ftsgeology.block.GeyserIgniterBlock;
import com.jeladastudios.ftsgeology.block.HotSpringBlock;
import com.jeladastudios.ftsgeology.block.SeismographBlock;
import com.jeladastudios.ftsgeology.block.SpringSourceBlock;
import com.jeladastudios.ftsgeology.block.VolcanoCoreBlock;
import com.jeladastudios.ftsgeology.block.VolcanoIgniterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.SandBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** All blocks introduced by the mod. */
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

    /** The deep, unreachable end of a hot spring. Unbreakable and blast-proof, so a quake cannot take it. */
    /**
     * The water standing in a traced river. Water in every way a player can feel, but it never moves, so a river's
     * surface may come down a valley a block at a time; see {@link com.jeladastudios.ftsgeology.registry.ModFluids}.
     */
    public static final RegistryObject<Block> RIVER_WATER = BLOCKS.register("river_water",
            () -> new com.jeladastudios.ftsgeology.block.RiverWaterBlock(
                    () -> (net.minecraft.world.level.material.FlowingFluid) ModFluids.RIVER_WATER.get(),
                    BlockBehaviour.Properties.copy(Blocks.WATER).noLootTable()));

    public static final RegistryObject<Block> SPRING_SOURCE = BLOCKS.register("spring_source",
            () -> new SpringSourceBlock(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()));

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

    /** A seismograph station: one gives distance but not direction; see {@code SeismographBlockEntity}. */
    public static final RegistryObject<Block> SEISMOGRAPH = BLOCKS.register("seismograph",
            () -> new SeismographBlock(BlockBehaviour.Properties.copy(Blocks.LODESTONE)
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(2.5F)
                    .noOcclusion()));

    /** A geothermal turbine: Forge Energy from the heat a well under it reaches. */
    public static final RegistryObject<Block> GEOTHERMAL_TURBINE = BLOCKS.register("geothermal_turbine",
            () -> new com.jeladastudios.ftsgeology.block.GeothermalTurbineBlock(BlockBehaviour.Properties.copy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    /** Well casing: the steel pipe a turbine's well is lined with, down to the heat. */
    public static final RegistryObject<Block> WELL_CASING = BLOCKS.register("well_casing",
            () -> new com.jeladastudios.ftsgeology.block.WellCasingBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BARS)
                    .mapColor(MapColor.METAL)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    /** Native sulfur: the yellow crust around fumaroles, the acidic counterpart to calcite and travertine. */
    public static final RegistryObject<Block> NATIVE_SULFUR = BLOCKS.register("native_sulfur",
            () -> new com.jeladastudios.ftsgeology.block.NativeSulfurBlock(
                    BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(0.8F)
                    .requiresCorrectToolForDrops()));

    /** Sinter: the pale silica shelf a hot spring builds at its own lip as its water cools. */
    public static final RegistryObject<Block> SINTER = BLOCKS.register("sinter",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .mapColor(MapColor.TERRACOTTA_WHITE)
                    .strength(0.9F)));

    // --- Geothermal basin blocks ---------------------------------------------
    //
    // Terrain blocks forming the floor of active geothermal fields and thermal basins.

    /** Sinter crust: pale, cracked, thin mineral crust over dead geothermal ground. */
    public static final RegistryObject<Block> SINTER_CRUST = BLOCKS.register("sinter_crust",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .mapColor(MapColor.TERRACOTTA_WHITE)
                    .strength(1.0F, 2.0F)
                    .requiresCorrectToolForDrops()));

    /** Mud pot: viscous grey-brown bubbling geothermal mud slurry. */
    public static final RegistryObject<Block> MUD_POT = BLOCKS.register("mud_pot",
            () -> new com.jeladastudios.ftsgeology.block.MudPotBlock(
                    BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.DIRT)
                    .strength(0.8F)));

    /** Volcanic ash, laid in layers like snow: soft, dug by hand, and holds no torch. */
    public static final RegistryObject<Block> VOLCANIC_ASH = BLOCKS.register("volcanic_ash",
            () -> new com.jeladastudios.ftsgeology.block.VolcanicAshBlock(
                    BlockBehaviour.Properties.copy(Blocks.SNOW)
                            .mapColor(MapColor.COLOR_GRAY)
                            .strength(0.4F)
                            .sound(net.minecraft.world.level.block.SoundType.SAND)
                            .noOcclusion()));

    /** Steam vent: dark hydrothermal rock with a degassing orifice, mineral staining, and faint glow. */
    public static final RegistryObject<Block> STEAM_VENT = BLOCKS.register("steam_vent",
            () -> new com.jeladastudios.ftsgeology.block.SteamVentBlock(
                    BlockBehaviour.Properties.copy(Blocks.BASALT)
                            .mapColor(MapColor.COLOR_BLACK)
                            .lightLevel(s -> 3)
                            .strength(2.0F, 6.0F)
                            // A chimney is not a solid cube, so it must not be asked to act like
                            // one: without this the narrower parts darken everything under them.
                            .noOcclusion()
                            .requiresCorrectToolForDrops()));

    // --- Geological rock blocks ---------------------------------------------

    /** Travertine: banded carbonate deposited around hot spring terraces. */
    public static final RegistryObject<Block> TRAVERTINE = BLOCKS.register("travertine",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .mapColor(MapColor.TERRACOTTA_LIGHT_GRAY)
                    .strength(1.0F, 3.0F)));

    /** Rhyolite: pale pinkish-grey fine-grained silicic volcanic rock. */
    public static final RegistryObject<Block> RHYOLITE = BLOCKS.register("rhyolite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.TERRACOTTA_PINK)
                    .strength(1.5F, 6.0F)));

    /** Gabbro: coarse dark intrusive ocean-crust rock, speckled black-green. */
    public static final RegistryObject<Block> GABBRO = BLOCKS.register("gabbro",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.2F, 6.0F)));

    /** Peridotite: dense dark olive-green granular mantle rock. */
    public static final RegistryObject<Block> PERIDOTITE = BLOCKS.register("peridotite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.TERRACOTTA_GREEN)
                    .strength(2.2F, 6.0F)));

    /** Serpentinite: altered peridotite; waxy mottled green. */
    public static final RegistryObject<Block> SERPENTINITE = BLOCKS.register("serpentinite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(1.8F, 6.0F)));

    /** Schist: foliated metamorphic rock with glittering layers. */
    public static final RegistryObject<Block> SCHIST = BLOCKS.register("schist",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(2.0F, 6.0F)));

    /** Gneiss: high-grade banded metamorphic rock with light and dark bands. */
    public static final RegistryObject<Block> GNEISS = BLOCKS.register("gneiss",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.STONE)
                    .strength(2.4F, 6.0F)));

    /** Slate: fine dark cleaved metamorphic rock. */
    public static final RegistryObject<Block> SLATE = BLOCKS.register("slate",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE)
                    .mapColor(MapColor.DEEPSLATE)
                    .strength(2.0F, 6.0F)));

    /** Marble: metamorphosed limestone, white with subtle grey veins. */
    public static final RegistryObject<Block> MARBLE = BLOCKS.register("marble",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.CALCITE)
                    .mapColor(MapColor.QUARTZ)
                    .strength(1.8F, 6.0F)));

    /** Quartzite: hard pale recrystallised quartz sandstone. */
    public static final RegistryObject<Block> QUARTZITE = BLOCKS.register("quartzite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.TERRACOTTA_WHITE)
                    .strength(2.5F, 6.0F)));

    /** Shale: soft fine dark sedimentary rock with thin fissile laminations. */
    public static final RegistryObject<Block> SHALE = BLOCKS.register("shale",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.TUFF)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(0.8F, 3.0F)));

    /** Chert: brittle, fine-grained nodular cryptocrystalline silica. */
    public static final RegistryObject<Block> CHERT = BLOCKS.register("chert",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .strength(1.0F, 3.0F)));

    /** Cooling lava crust: basalt-dark crust with glowing orange-red fissures, like a skinned-over lava flow still hot underneath. */
    public static final RegistryObject<Block> COOLING_LAVA_CRUST = BLOCKS.register("cooling_lava_crust",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.BASALT)
                    .mapColor(MapColor.COLOR_BLACK)
                    .lightLevel(s -> 7)
                    .strength(2.0F, 6.0F)
                    .requiresCorrectToolForDrops()));

    /** Volcanic black sand: basalt shattered by the sea and ground down by waves, as on Hawaiian beaches. */
    public static final RegistryObject<Block> VOLCANIC_BLACK_SAND = BLOCKS.register("volcanic_black_sand",
            () -> new SandBlock(0x141414, BlockBehaviour.Properties.copy(Blocks.SAND)
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(0.5F)
                    .sound(SoundType.SAND)));

    // --- Mineral blocks ------------------------------------------------------
    //
    // Real geological minerals and hydrothermal vein deposits.

    /** Pyrite: brassy metallic iron sulfide cubes in dark rock. */
    public static final RegistryObject<Block> PYRITE = BLOCKS.register("pyrite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_ORE)
                    .strength(3.0F, 3.0F)
                    .requiresCorrectToolForDrops()));

    /** Chalcopyrite: copper-iron sulfide displaying vibrant iridescent blue-gold tarnish. */
    public static final RegistryObject<Block> CHALCOPYRITE = BLOCKS.register("chalcopyrite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.COPPER_ORE)
                    .strength(3.0F, 3.0F)
                    .requiresCorrectToolForDrops()));

    /** Malachite: hydrated copper carbonate with rich concentric green banding. */
    public static final RegistryObject<Block> MALACHITE = BLOCKS.register("malachite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(2.5F, 3.0F)
                    .requiresCorrectToolForDrops()));

    /** Azurite: deep royal-blue basic copper carbonate, commonly associated with malachite. */
    public static final RegistryObject<Block> AZURITE = BLOCKS.register("azurite",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_BLUE)
                    .strength(2.5F, 3.0F)
                    .requiresCorrectToolForDrops()));

    /** Quartz vein: white crystalline hydrothermal quartz seam cutting through grey host stone. */
    public static final RegistryObject<Block> QUARTZ_VEIN = BLOCKS.register("quartz_vein",
            () -> new DropExperienceBlock(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.QUARTZ)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops(), UniformInt.of(2, 5)));

    /** Cinnabar: hydrothermal vermilion-red mercury sulfide ore. */
    public static final RegistryObject<Block> CINNABAR = BLOCKS.register("cinnabar",
            () -> new DropExperienceBlock(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.5F, 3.0F)
                    .requiresCorrectToolForDrops(), UniformInt.of(1, 5)));

    /** Galena: primary lead sulfide ore with metallic lustre and stepped cubic cleavage. */
    public static final RegistryObject<Block> GALENA = BLOCKS.register("galena",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.0F, 3.0F)
                    .requiresCorrectToolForDrops()));

    // --- Worked rock forms (polished, slab, stairs, wall) -------------------

    public static final RegistryObject<Block> POLISHED_TRAVERTINE = BLOCKS.register("polished_travertine",
            () -> new Block(BlockBehaviour.Properties.copy(TRAVERTINE.get())));
    public static final RegistryObject<Block> TRAVERTINE_SLAB = BLOCKS.register("travertine_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(TRAVERTINE.get())));
    public static final RegistryObject<Block> TRAVERTINE_STAIRS = BLOCKS.register("travertine_stairs",
            () -> new StairBlock(() -> TRAVERTINE.get().defaultBlockState(), BlockBehaviour.Properties.copy(TRAVERTINE.get())));
    public static final RegistryObject<Block> TRAVERTINE_WALL = BLOCKS.register("travertine_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(TRAVERTINE.get())));

    public static final RegistryObject<Block> POLISHED_RHYOLITE = BLOCKS.register("polished_rhyolite",
            () -> new Block(BlockBehaviour.Properties.copy(RHYOLITE.get())));
    public static final RegistryObject<Block> RHYOLITE_SLAB = BLOCKS.register("rhyolite_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(RHYOLITE.get())));
    public static final RegistryObject<Block> RHYOLITE_STAIRS = BLOCKS.register("rhyolite_stairs",
            () -> new StairBlock(() -> RHYOLITE.get().defaultBlockState(), BlockBehaviour.Properties.copy(RHYOLITE.get())));
    public static final RegistryObject<Block> RHYOLITE_WALL = BLOCKS.register("rhyolite_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(RHYOLITE.get())));

    public static final RegistryObject<Block> POLISHED_GABBRO = BLOCKS.register("polished_gabbro",
            () -> new Block(BlockBehaviour.Properties.copy(GABBRO.get())));
    public static final RegistryObject<Block> GABBRO_SLAB = BLOCKS.register("gabbro_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(GABBRO.get())));
    public static final RegistryObject<Block> GABBRO_STAIRS = BLOCKS.register("gabbro_stairs",
            () -> new StairBlock(() -> GABBRO.get().defaultBlockState(), BlockBehaviour.Properties.copy(GABBRO.get())));
    public static final RegistryObject<Block> GABBRO_WALL = BLOCKS.register("gabbro_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(GABBRO.get())));

    public static final RegistryObject<Block> POLISHED_PERIDOTITE = BLOCKS.register("polished_peridotite",
            () -> new Block(BlockBehaviour.Properties.copy(PERIDOTITE.get())));
    public static final RegistryObject<Block> PERIDOTITE_SLAB = BLOCKS.register("peridotite_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(PERIDOTITE.get())));
    public static final RegistryObject<Block> PERIDOTITE_STAIRS = BLOCKS.register("peridotite_stairs",
            () -> new StairBlock(() -> PERIDOTITE.get().defaultBlockState(), BlockBehaviour.Properties.copy(PERIDOTITE.get())));
    public static final RegistryObject<Block> PERIDOTITE_WALL = BLOCKS.register("peridotite_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(PERIDOTITE.get())));

    public static final RegistryObject<Block> POLISHED_SERPENTINITE = BLOCKS.register("polished_serpentinite",
            () -> new Block(BlockBehaviour.Properties.copy(SERPENTINITE.get())));
    public static final RegistryObject<Block> SERPENTINITE_SLAB = BLOCKS.register("serpentinite_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(SERPENTINITE.get())));
    public static final RegistryObject<Block> SERPENTINITE_STAIRS = BLOCKS.register("serpentinite_stairs",
            () -> new StairBlock(() -> SERPENTINITE.get().defaultBlockState(), BlockBehaviour.Properties.copy(SERPENTINITE.get())));
    public static final RegistryObject<Block> SERPENTINITE_WALL = BLOCKS.register("serpentinite_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(SERPENTINITE.get())));

    public static final RegistryObject<Block> POLISHED_SCHIST = BLOCKS.register("polished_schist",
            () -> new Block(BlockBehaviour.Properties.copy(SCHIST.get())));
    public static final RegistryObject<Block> SCHIST_SLAB = BLOCKS.register("schist_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(SCHIST.get())));
    public static final RegistryObject<Block> SCHIST_STAIRS = BLOCKS.register("schist_stairs",
            () -> new StairBlock(() -> SCHIST.get().defaultBlockState(), BlockBehaviour.Properties.copy(SCHIST.get())));
    public static final RegistryObject<Block> SCHIST_WALL = BLOCKS.register("schist_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(SCHIST.get())));

    public static final RegistryObject<Block> POLISHED_GNEISS = BLOCKS.register("polished_gneiss",
            () -> new Block(BlockBehaviour.Properties.copy(GNEISS.get())));
    public static final RegistryObject<Block> GNEISS_SLAB = BLOCKS.register("gneiss_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(GNEISS.get())));
    public static final RegistryObject<Block> GNEISS_STAIRS = BLOCKS.register("gneiss_stairs",
            () -> new StairBlock(() -> GNEISS.get().defaultBlockState(), BlockBehaviour.Properties.copy(GNEISS.get())));
    public static final RegistryObject<Block> GNEISS_WALL = BLOCKS.register("gneiss_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(GNEISS.get())));

    public static final RegistryObject<Block> POLISHED_SLATE = BLOCKS.register("polished_slate",
            () -> new Block(BlockBehaviour.Properties.copy(SLATE.get())));
    public static final RegistryObject<Block> SLATE_SLAB = BLOCKS.register("slate_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(SLATE.get())));
    public static final RegistryObject<Block> SLATE_STAIRS = BLOCKS.register("slate_stairs",
            () -> new StairBlock(() -> SLATE.get().defaultBlockState(), BlockBehaviour.Properties.copy(SLATE.get())));
    public static final RegistryObject<Block> SLATE_WALL = BLOCKS.register("slate_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(SLATE.get())));

    public static final RegistryObject<Block> POLISHED_MARBLE = BLOCKS.register("polished_marble",
            () -> new Block(BlockBehaviour.Properties.copy(MARBLE.get())));
    public static final RegistryObject<Block> MARBLE_SLAB = BLOCKS.register("marble_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(MARBLE.get())));
    public static final RegistryObject<Block> MARBLE_STAIRS = BLOCKS.register("marble_stairs",
            () -> new StairBlock(() -> MARBLE.get().defaultBlockState(), BlockBehaviour.Properties.copy(MARBLE.get())));
    public static final RegistryObject<Block> MARBLE_WALL = BLOCKS.register("marble_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(MARBLE.get())));

    public static final RegistryObject<Block> POLISHED_QUARTZITE = BLOCKS.register("polished_quartzite",
            () -> new Block(BlockBehaviour.Properties.copy(QUARTZITE.get())));
    public static final RegistryObject<Block> QUARTZITE_SLAB = BLOCKS.register("quartzite_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(QUARTZITE.get())));
    public static final RegistryObject<Block> QUARTZITE_STAIRS = BLOCKS.register("quartzite_stairs",
            () -> new StairBlock(() -> QUARTZITE.get().defaultBlockState(), BlockBehaviour.Properties.copy(QUARTZITE.get())));
    public static final RegistryObject<Block> QUARTZITE_WALL = BLOCKS.register("quartzite_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(QUARTZITE.get())));

    public static final RegistryObject<Block> POLISHED_SHALE = BLOCKS.register("polished_shale",
            () -> new Block(BlockBehaviour.Properties.copy(SHALE.get())));
    public static final RegistryObject<Block> SHALE_SLAB = BLOCKS.register("shale_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(SHALE.get())));
    public static final RegistryObject<Block> SHALE_STAIRS = BLOCKS.register("shale_stairs",
            () -> new StairBlock(() -> SHALE.get().defaultBlockState(), BlockBehaviour.Properties.copy(SHALE.get())));
    public static final RegistryObject<Block> SHALE_WALL = BLOCKS.register("shale_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(SHALE.get())));

    public static final RegistryObject<Block> POLISHED_CHERT = BLOCKS.register("polished_chert",
            () -> new Block(BlockBehaviour.Properties.copy(CHERT.get())));
    public static final RegistryObject<Block> CHERT_SLAB = BLOCKS.register("chert_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.copy(CHERT.get())));
    public static final RegistryObject<Block> CHERT_STAIRS = BLOCKS.register("chert_stairs",
            () -> new StairBlock(() -> CHERT.get().defaultBlockState(), BlockBehaviour.Properties.copy(CHERT.get())));
    public static final RegistryObject<Block> CHERT_WALL = BLOCKS.register("chert_wall",
            () -> new WallBlock(BlockBehaviour.Properties.copy(CHERT.get())));

    // --- Microbial mats ------------------------------------------------------
    //
    // Each colour band is a microbial community living in its own temperature range, so the rings
    // around a pool read as a thermometer.

    /** Orange microbial mat. */
    public static final RegistryObject<Block> MICROBIAL_MAT_ORANGE = BLOCKS.register("microbial_mat_orange",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(0.6F)));

    /** Yellow microbial mat. */
    public static final RegistryObject<Block> MICROBIAL_MAT_YELLOW = BLOCKS.register("microbial_mat_yellow",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(0.6F)));

    /** Brown microbial mat, at the dry outer edge. */
    public static final RegistryObject<Block> MICROBIAL_MAT_BROWN = BLOCKS.register("microbial_mat_brown",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .strength(0.6F)));

    /** Green microbial mat, the narrow fringe at the water's edge. */
    public static final RegistryObject<Block> MICROBIAL_MAT_GREEN = BLOCKS.register("microbial_mat_green",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.MUD)
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(0.6F)));

    private ModBlocks() {}
}
