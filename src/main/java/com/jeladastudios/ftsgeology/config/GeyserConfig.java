package com.jeladastudios.ftsgeology.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Central, hot-reloadable configuration backed by {@code fts_geology.toml}.
 *
 * <p>All thermodynamic tuning constants live here so server admins can rebalance
 * eruption cadence and violence without recompiling. Values are read once per tick
 * from the cached {@code .get()} accessors, which is cheap.</p>
 */
public final class GeyserConfig {

    public static final ForgeConfigSpec SPEC;

    // --- Thermodynamics -----------------------------------------------------
    public static final ForgeConfigSpec.DoubleValue HEAT_PER_LAVA_NEIGHBOR;
    public static final ForgeConfigSpec.DoubleValue AMBIENT_COOLING_PER_TICK;
    public static final ForgeConfigSpec.DoubleValue BOILING_POINT_C;
    public static final ForgeConfigSpec.DoubleValue MAX_TEMPERATURE_C;
    public static final ForgeConfigSpec.IntValue STEAM_EXPANSION_RATIO; // water:steam volume, ~1:1600

    // --- Pressure / eruption thresholds ------------------------------------
    public static final ForgeConfigSpec.DoubleValue PRESSURE_ERUPTION_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue PRESSURE_SAFE_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue CRUST_EROSION_PRESSURE;
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_POWER;
    public static final ForgeConfigSpec.DoubleValue JET_UPWARD_VELOCITY;       // sustained flow
    public static final ForgeConfigSpec.DoubleValue JET_BURST_VELOCITY;        // extra kick at onset
    public static final ForgeConfigSpec.IntValue JET_BURST_DECAY_TICKS;        // how fast the burst fades
    public static final ForgeConfigSpec.DoubleValue ONSET_LAUNCH_VELOCITY;     // one-time blast at breakthrough
    public static final ForgeConfigSpec.IntValue ONSET_WATER_SCATTER;          // pool cells flung at onset
    public static final ForgeConfigSpec.BooleanValue VENT_BREAKS_OBSTRUCTIONS;  // blast through deliberate caps
    public static final ForgeConfigSpec.BooleanValue ERUPTIONS_START_FIRES;     // default ON
    public static final ForgeConfigSpec.DoubleValue VENT_FORCE_BREACH_PRESSURE; // P needed to blast a hard cap

    // --- Retrogen / worldgen ------------------------------------------------
    public static final ForgeConfigSpec.IntValue RETROGEN_MAX_Y;      // never touch above this
    public static final ForgeConfigSpec.IntValue RETROGEN_MIN_Y;      // deepest scan level
    public static final ForgeConfigSpec.IntValue CHAMBER_TARGET_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue CHAMBER_SPAWN_CHANCE; // per candidate column
    public static final ForgeConfigSpec.BooleanValue RETROGEN_ENABLED;
    public static final ForgeConfigSpec.IntValue RETROGEN_CHUNKS_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue CARVE_SURFACE_SHAFT; // connect vent to surface
    public static final ForgeConfigSpec.IntValue SHAFT_MAX_LENGTH;         // safety cap on shaft height
    public static final ForgeConfigSpec.IntValue IGNITER_DELAY_TICKS;      // placed igniter -> geyser delay

    // --- Basin water budget -------------------------------------------------
    public static final ForgeConfigSpec.IntValue CHAMBER_DRAIN_INTERVAL_TICKS; // 1 water cell lost per N ticks while erupting
    public static final ForgeConfigSpec.IntValue CHAMBER_REFILL_INTERVAL_TICKS; // 1 water cell regained per N ticks while recharging/cooling

    // --- Emergent (player-built) geysers -----------------------------------
    public static final ForgeConfigSpec.BooleanValue EMERGENT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue EMERGENT_DESTRUCTIVE;
    public static final ForgeConfigSpec.IntValue EMERGENT_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue EMERGENT_SCAN_RADIUS;
    public static final ForgeConfigSpec.IntValue EMERGENT_MIN_WATER;
    public static final ForgeConfigSpec.IntValue EMERGENT_LAVA_DEPTH;
    public static final ForgeConfigSpec.IntValue EMERGENT_MIN_SPACING;   // don't place cores this close together
    public static final ForgeConfigSpec.IntValue EMERGENT_DESTROY_RADIUS; // base block-break radius on eruption

    // --- Branching root-vents ----------------------------------------------
    public static final ForgeConfigSpec.BooleanValue BRANCHING_VENTS_ENABLED;
    public static final ForgeConfigSpec.IntValue BRANCH_MAX_COUNT;
    public static final ForgeConfigSpec.IntValue BRANCH_MAX_LENGTH;
    public static final ForgeConfigSpec.IntValue BRANCH_CARVE_BUDGET;  // total cells across all branches
    public static final ForgeConfigSpec.DoubleValue BRANCH_SPLIT_CHANCE;

    // --- Hot springs --------------------------------------------------------
    public static final ForgeConfigSpec.DoubleValue HOT_SPRING_SPAWN_CHANCE; // per-chunk surface pool
    public static final ForgeConfigSpec.IntValue HOT_SPRING_RADIUS;          // warmth / melt radius
    public static final ForgeConfigSpec.BooleanValue HOT_SPRING_REGEN;       // grant regeneration while soaking

    // --- Volcanoes ----------------------------------------------------------
    public static final ForgeConfigSpec.IntValue VOLCANO_DORMANT_MIN_TICKS; // quiet time between eruptions
    public static final ForgeConfigSpec.IntValue VOLCANO_DORMANT_MAX_TICKS;
    public static final ForgeConfigSpec.IntValue VOLCANO_RUMBLE_TICKS;      // black-smoke warning before it blows
    public static final ForgeConfigSpec.IntValue VOLCANO_ERUPT_TICKS;       // how long lava fountains
    public static final ForgeConfigSpec.IntValue VOLCANO_BOMBS_PER_ERUPTION;
    public static final ForgeConfigSpec.IntValue VOLCANO_RESERVOIR_RADIUS;  // deep magma-chamber radius
    public static final ForgeConfigSpec.IntValue VOLCANO_CRATER_RADIUS;     // summit crater/lava-pool radius
    public static final ForgeConfigSpec.IntValue VOLCANO_LAVA_BUDGET;       // lava cells per eruption

    // --- Cooldown / recharge cycle -----------------------------------------
    public static final ForgeConfigSpec.IntValue COOLDOWN_TICKS_MIN;  // e.g. 5 min = 6000
    public static final ForgeConfigSpec.IntValue COOLDOWN_TICKS_MAX;  // e.g. 10 min = 12000
    public static final ForgeConfigSpec.DoubleValue RECHARGE_INTAKE_FRACTION; // % of surface water sucked back (0.20)
    public static final ForgeConfigSpec.IntValue CONE_BUILD_ERUPTIONS; // eruptions before cone forms
    public static final ForgeConfigSpec.IntValue ERUPTION_TICKS_PER_MAGNITUDE; // spout duration per size unit
    public static final ForgeConfigSpec.IntValue WATER_SPOUT_MAX_TICKS;         // hard cap on the water phase
    public static final ForgeConfigSpec.BooleanValue TRAVERTINE_ENABLED;        // sinter terraces on runoff
    public static final ForgeConfigSpec.DoubleValue TRAVERTINE_DEPOSIT_CHANCE;  // per-attempt deposit odds


    // --- Tectonics (plate + fault-line model) -------------------------------
    public static final ForgeConfigSpec.BooleanValue TECTONICS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue PLATE_SCALE;  // blocks per plate cell
    public static final ForgeConfigSpec.DoubleValue PLATE_JITTER; // 0 = square grid, 1 = very irregular
    public static final ForgeConfigSpec.DoubleValue FAULT_WIDTH;  // blocks either side counted as "on the fault"

    // --- Hotspots (intraplate mantle plumes) --------------------------------
    public static final ForgeConfigSpec.BooleanValue HOTSPOTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue HOTSPOT_SCALE;        // grid cell size, blocks
    public static final ForgeConfigSpec.DoubleValue HOTSPOT_DENSITY;      // fraction of cells with a plume
    public static final ForgeConfigSpec.DoubleValue HOTSPOT_RADIUS;       // active dome radius, blocks
    public static final ForgeConfigSpec.DoubleValue HOTSPOT_TRAIL_LENGTH; // extinct chain length, blocks
    public static final ForgeConfigSpec.BooleanValue BIOME_ANCHORING;      // settle on thermal biomes
    public static final ForgeConfigSpec.DoubleValue HOTSPOT_FEATURE_BOOST; // geyser-basin density multiplier
    public static final ForgeConfigSpec.DoubleValue HOTSPOT_BASIN_SCALE;   // geyser basin grid, blocks
    public static final ForgeConfigSpec.DoubleValue HOTSPOT_BASIN_DENSITY; // fraction of cells that are basins

    // --- Depth scale (blocks to real metres) --------------------------------
    public static final ForgeConfigSpec.DoubleValue METRES_PER_BLOCK;
    public static final ForgeConfigSpec.DoubleValue METRES_PER_BLOCK_HORIZONTAL;

    // --- Tectonic feature placement ----------------------------------------
    public static final ForgeConfigSpec.BooleanValue TECTONIC_PLACEMENT;   // gate geysers/springs on geology
    public static final ForgeConfigSpec.DoubleValue VOLCANO_SPAWN_CHANCE;  // per-chunk, before suitability
    public static final ForgeConfigSpec.BooleanValue DEEP_STRUCTURE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue DEEP_SURFACE_OUTCROP;  // boundary rock reaches daylight
    public static final ForgeConfigSpec.IntValue DEEP_SOIL_DEPTH;           // topsoil left untouched
    public static final ForgeConfigSpec.BooleanValue OCEANIC_RIDGE_ENABLED;

    // --- Earthquakes --------------------------------------------------------
    public static final ForgeConfigSpec.BooleanValue QUAKES_ENABLED;
    public static final ForgeConfigSpec.BooleanValue QUAKES_BREAK_BUILDS;  // default OFF
    public static final ForgeConfigSpec.BooleanValue UNSUPPORTED_BLOCKS_FALL;   // default ON
    public static final ForgeConfigSpec.BooleanValue FALLING_INCLUDES_BUILDS;   // default ON
    public static final ForgeConfigSpec.IntValue QUAKE_BLOCKS_PER_TICK;    // main-thread apply budget
    public static final ForgeConfigSpec.IntValue QUAKE_WARNING_TICKS;      // alert window before the ground moves
    public static final ForgeConfigSpec.IntValue QUAKE_AMBIENT_INTERVAL;   // ticks between ambient rolls
    public static final ForgeConfigSpec.IntValue QUAKE_SEARCH_RADIUS;      // how far from a player to look
    public static final ForgeConfigSpec.DoubleValue QUAKE_RECURRENCE_DAYS; // mean interval between ruptures
    public static final ForgeConfigSpec.IntValue QUAKE_MAX_FISSURE_DEPTH;  // divergent rift depth cap
    public static final ForgeConfigSpec.IntValue QUAKE_MAX_RUPTURE;        // cap on rupture length, blocks
    public static final ForgeConfigSpec.IntValue QUAKE_PENDING_LIMIT;      // deferred edits held for unloaded chunks
    public static final ForgeConfigSpec.IntValue QUAKE_MAX_EDITS;          // hard cap on one quake
    public static final ForgeConfigSpec.IntValue TICK_BUDGET_MS;           // wall-clock brake, whole mod
    public static final ForgeConfigSpec.BooleanValue QUAKE_LAYERED;        // move the whole fault at once
    public static final ForgeConfigSpec.IntValue DEEP_STRUCTURE_BUDGET;    // block edits per chunk

    // --- Sulfur -------------------------------------------------------------
    public static final ForgeConfigSpec.BooleanValue SULFUR_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SULFUR_DEPOSIT_CHANCE;

    // --- Integration with other mods ----------------------------------------
    public static final ForgeConfigSpec.BooleanValue SUGGEST_OPTIONAL_MODS;   // default ON

    // --- Hydrology (groundwater) ---------------------------------------------
    public static final ForgeConfigSpec.BooleanValue WATER_TABLE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue WATER_TABLE_SUBDUAL;      // relief it copies, 0..1
    public static final ForgeConfigSpec.IntValue WATER_TABLE_DEPTH_TEMPERATE; // blocks below the land
    public static final ForgeConfigSpec.IntValue WATER_TABLE_DEPTH_ARID;
    public static final ForgeConfigSpec.IntValue WATER_TABLE_DEPTH_HUMID;
    public static final ForgeConfigSpec.BooleanValue SPRING_RENEWAL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue QUAKES_BREAK_STRUCTURES;
    public static final ForgeConfigSpec.DoubleValue SPRING_STAGE_ONE_DAYS;
    public static final ForgeConfigSpec.DoubleValue SPRING_STAGE_TWO_DAYS;
    public static final ForgeConfigSpec.DoubleValue SPRING_STAGE_THREE_DAYS;
    public static final ForgeConfigSpec.BooleanValue QUAKES_OPEN_NEW_SPRINGS;
    public static final ForgeConfigSpec.DoubleValue QUAKE_SPRING_CHANCE;   // per magnitude over 3

    // --- Instruments ---------------------------------------------------------
    public static final ForgeConfigSpec.IntValue SEISMOGRAPH_RANGE;   // blocks a station can hear
    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("thermodynamics");
        HEAT_PER_LAVA_NEIGHBOR = b
                .comment("Degrees Celsius added per tick for each adjacent lava/magma block.",
                        "Lowered so setups don't boil almost instantly — gives time to react in a base.")
                .defineInRange("heatPerLavaNeighbor", 0.30D, 0.0D, 100.0D);
        AMBIENT_COOLING_PER_TICK = b
                .comment("Passive heat loss per tick when no heat source is adjacent.")
                .defineInRange("ambientCoolingPerTick", 0.10D, 0.0D, 100.0D);
        BOILING_POINT_C = b
                .comment("Temperature at which water begins converting to steam pressure.")
                .defineInRange("boilingPointC", 100.0D, 0.0D, 1000.0D);
        MAX_TEMPERATURE_C = b
                .comment("Hard cap on chamber temperature.")
                .defineInRange("maxTemperatureC", 450.0D, 100.0D, 5000.0D);
        STEAM_EXPANSION_RATIO = b
                .comment("Abstract volume expansion of 1 water block flashing to steam (~1600).")
                .defineInRange("steamExpansionRatio", 1600, 1, 10000);
        b.pop();

        b.push("pressure");
        PRESSURE_ERUPTION_THRESHOLD = b
                .comment("Pressure P at which an eruption is triggered.")
                .defineInRange("eruptionThreshold", 4000.0D, 1.0D, 1.0E7D);
        PRESSURE_SAFE_THRESHOLD = b
                .comment("Pressure P below which the jet field is removed and venting stops.")
                .defineInRange("safeThreshold", 800.0D, 0.0D, 1.0E7D);
        CRUST_EROSION_PRESSURE = b
                .comment("Pressure at which the rock cap above the core starts cracking.")
                .defineInRange("crustErosionPressure", 2500.0D, 1.0D, 1.0E7D);
        EXPLOSION_POWER = b
                .comment("world.explode() power at the vent mouth. Kept low to spare builds.")
                .defineInRange("explosionPower", 1.5D, 0.0D, 20.0D);
        JET_UPWARD_VELOCITY = b
                .comment("Sustained updraft velocity in the active vent column (the gentle steady flow).")
                .defineInRange("jetUpwardVelocity", 0.5D, 0.0D, 10.0D);
        JET_BURST_VELOCITY = b
                .comment("Extra updraft at the very start of an eruption, decaying over time. The whole",
                        "jet is hard-capped so entities can't be flung to absurd heights even if several",
                        "vents overlap.")
                .defineInRange("jetBurstVelocity", 0.5D, 0.0D, 10.0D);
        JET_BURST_DECAY_TICKS = b
                .comment("Time constant (ticks) for the onset burst to fade. ~40 = burst lasts a couple seconds.")
                .defineInRange("jetBurstDecayTicks", 40, 1, 2000);
        ONSET_LAUNCH_VELOCITY = b
                .comment("One-time upward velocity given to entities caught in the initial breakthrough.",
                        "Scaled by size and hard-capped so it stays sane; ~1.2 throws a player ~8-10",
                        "blocks up. The violent first instant, distinct from the sustained flow.")
                .defineInRange("onsetLaunchVelocity", 1.2D, 0.0D, 10.0D);
        ONSET_WATER_SCATTER = b
                .comment("How many surrounding pool-water cells get flung/flashed at breakthrough",
                        "(scaled up by magnitude). This is the 'water in the pool sprays everywhere' effect.")
                .defineInRange("onsetWaterScatter", 6, 0, 128);
        VENT_BREAKS_OBSTRUCTIONS = b
                .comment("If you plug the vent with tough/placed blocks, let a high-pressure eruption",
                        "blast through them (small explosions) rather than being sealed forever. Natural",
                        "terrain in the path is always cleared; this governs deliberate caps. Bedrock is",
                        "never broken.")
                .define("ventBreaksObstructions", true);
        VENT_FORCE_BREACH_PRESSURE = b
                .comment("Pressure at which the vent force-breaches a tough cap it can't reroute around.",
                        "Higher = you can hold it down longer before it blows through.")
                .defineInRange("ventForceBreachPressure", 6000.0D, 1.0D, 1.0E7D);
        b.pop();

        b.push("worldgen");
        RETROGEN_CHUNKS_PER_TICK = b
                .comment("How many chunks receive their geology per server tick.",
                        "Features write well outside their own chunk, so running them straight from",
                        "the chunk-load event forces neighbours to load and cascades. Queueing instead",
                        "keeps the work bounded and means nothing runs during world creation at all.")
                .defineInRange("retrogenChunksPerTick", 16, 1, 256);
        RETROGEN_ENABLED = b
                .comment("Master switch for retroactive generation into existing chunks.")
                .define("retrogenEnabled", true);
        RETROGEN_MAX_Y = b
                .comment("SAFETY: generation never touches blocks at or above this Y. Protects builds.")
                .defineInRange("retrogenMaxY", -30, -64, 320);
        RETROGEN_MIN_Y = b
                .comment("Deepest Y scanned when carving a chamber.")
                .defineInRange("retrogenMinY", -60, -64, 0);
        CHAMBER_TARGET_HEIGHT = b
                .comment("Interior vertical size of a carved geyser chamber.")
                .defineInRange("chamberTargetHeight", 5, 3, 16);
        CHAMBER_SPAWN_CHANCE = b
                .comment("Per-chunk probability of a geyser BEFORE tectonic suitability is applied.",
                        "Suitability multiplies this down to near zero outside volcanic settings, so",
                        "the raw number is tuned for what you should see along an active arc or rift.")
                .defineInRange("chamberSpawnChance", 0.030D, 0.0D, 1.0D);
        CARVE_SURFACE_SHAFT = b
                .comment("Carve a thin vent from the deep chamber up to the surface so eruptions",
                        "reach daylight. Build-safe: the carve aborts (leaving the vent buried) the",
                        "moment it would touch anything that isn't natural terrain.")
                .define("carveSurfaceShaft", true);
        SHAFT_MAX_LENGTH = b
                .comment("Safety cap on how many blocks a surface shaft may rise from the cap. Must be",
                        "tall enough to reach big-mountain surfaces (Terralith peaks) — else the vent",
                        "gets stuck inside the mountain and never surfaces.")
                .defineInRange("shaftMaxLength", 384, 1, 512);
        IGNITER_DELAY_TICKS = b
                .comment("Ticks after a Geyser Igniter block is placed before it forms a geyser below",
                        "it and vanishes. 300 = 15 seconds.")
                .defineInRange("igniterDelayTicks", 300, 20, 24000);
        b.pop();

        b.push("branching");
        BRANCHING_VENTS_ENABLED = b
                .comment("Grow thin root-like side vents branching upward from the chamber. Their tips",
                        "that reach caves/air become weaker secondary fumaroles.")
                .define("branchingVentsEnabled", true);
        BRANCH_MAX_COUNT = b
                .comment("Upper bound on simultaneous growth heads. Higher = bushier root network.")
                .defineInRange("branchMaxCount", 10, 1, 32);
        BRANCH_MAX_LENGTH = b
                .comment("Maximum length of a single branch before it terminates.")
                .defineInRange("branchMaxLength", 24, 2, 128);
        BRANCH_CARVE_BUDGET = b
                .comment("Total cells that may be carved across ALL branches of one system (TPS guard).")
                .defineInRange("branchCarveBudget", 320, 0, 4096);
        BRANCH_SPLIT_CHANCE = b
                .comment("Per-step probability that a growing branch splits into two.")
                .defineInRange("branchSplitChance", 0.12D, 0.0D, 1.0D);
        b.pop();

        b.push("hotsprings");
        HOT_SPRING_SPAWN_CHANCE = b
                .comment("Per-chunk probability of a hot spring BEFORE tectonic suitability.",
                        "Hot springs occur at every kind of fault, so this stays comparatively high.")
                .defineInRange("hotSpringSpawnChance", 0.040D, 0.0D, 1.0D);
        HOT_SPRING_RADIUS = b
                .comment("Radius over which a hot spring warms soakers and melts snow/ice.")
                .defineInRange("hotSpringRadius", 4, 1, 16);
        HOT_SPRING_REGEN = b
                .comment("Grant brief Regeneration while soaking in a hot spring. The pool also keeps",
                        "you from freezing, and its hidden lava heat source reads as warm to Tough As",
                        "Nails (so it raises your body temperature there with no hard dependency).")
                .define("hotSpringRegen", true);
        b.pop();

        b.push("volcano");
        VOLCANO_DORMANT_MIN_TICKS = b
                .comment("Minimum quiet time between eruptions (12000 = 10 min).")
                .defineInRange("volcanoDormantMinTicks", 12000, 200, 720000);
        VOLCANO_DORMANT_MAX_TICKS = b
                .comment("Maximum quiet time between eruptions (36000 = 30 min).")
                .defineInRange("volcanoDormantMaxTicks", 36000, 200, 720000);
        VOLCANO_RUMBLE_TICKS = b
                .comment("Warning phase length: heavy black smoke + rumble before it erupts (600 = 30s).")
                .defineInRange("volcanoRumbleTicks", 600, 20, 12000);
        VOLCANO_ERUPT_TICKS = b
                .comment("How long the summit fountains lava and hurls bombs (1200 = 1 min).")
                .defineInRange("volcanoEruptTicks", 1200, 20, 24000);
        VOLCANO_BOMBS_PER_ERUPTION = b
                .comment("Roughly how many volcanic bombs (basalt/magma) are thrown over an eruption.")
                .defineInRange("volcanoBombsPerEruption", 40, 0, 400);
        VOLCANO_RESERVOIR_RADIUS = b
                .comment("Radius of the deep magma chamber carved under a volcano.")
                .defineInRange("volcanoReservoirRadius", 10, 2, 24);
        VOLCANO_CRATER_RADIUS = b
                .comment("Radius of the summit crater / lava pool.")
                .defineInRange("volcanoCraterRadius", 3, 1, 12);
        VOLCANO_LAVA_BUDGET = b
                .comment("How many cells of lava one eruption may pour out of the summit.",
                        "A real flow chills against the ground and stops; left unbounded the crater",
                        "simply kept welling until the whole flank was molten and the forest was on",
                        "fire. With a budget you get a tongue of lava that advances down the slope",
                        "and turns to basalt behind its own front, which is both what happens and",
                        "what actually looks like an eruption.")
                .defineInRange("volcanoLavaBudget", 24, 0, 400);
        ERUPTIONS_START_FIRES = b
                .comment("Let a lava flow set the countryside alight. A flow reaching the tree line",
                        "really does start a fire, and the lava budget above already stops the flow",
                        "itself from running away, so the burn stays a consequence of where the",
                        "eruption went rather than something that spreads forever on its own.",
                        "Fire was previously put out as the flow cooled; turn this off to get that",
                        "behaviour back. Worth leaving on alongside mods that model fire spread",
                        "properly, such as Burnt.")
                .define("eruptionsStartFires", true);
        b.pop();

        b.push("cycle");
        COOLDOWN_TICKS_MIN = b
                .comment("Minimum recharge time after an eruption (20 ticks = 1s; 6000 = 5 min).")
                .defineInRange("cooldownTicksMin", 6000, 20, 720000);
        COOLDOWN_TICKS_MAX = b
                .comment("Maximum recharge time after an eruption (12000 = 10 min).")
                .defineInRange("cooldownTicksMax", 12000, 20, 720000);
        RECHARGE_INTAKE_FRACTION = b
                .comment("Fraction of erupted surface water that drains back into the vent (cone gates ~20%).")
                .defineInRange("rechargeIntakeFraction", 0.20D, 0.0D, 1.0D);
        CONE_BUILD_ERUPTIONS = b
                .comment("Deposit a mineral-cone ring every N eruptions (1 = every eruption, so the",
                        "cone grows fast and you see it build up quickly).")
                .defineInRange("coneBuildEruptions", 1, 1, 100);
        ERUPTION_TICKS_PER_MAGNITUDE = b
                .comment("Water-spout duration per size unit (capped by waterSpoutMaxTicks).",
                        "200 -> radius-5 spouts ~50s, radius-20 hits the ~2 min cap.")
                .defineInRange("eruptionTicksPerMagnitude", 200, 20, 720000);
        WATER_SPOUT_MAX_TICKS = b
                .comment("Hard cap on how long an eruption spouts water. 2400 = 2 minutes. After the",
                        "water stops, the vent still steams (weaker) through recharge/cooldown, then",
                        "the cooldown timer brings it back for the next burst.")
                .defineInRange("waterSpoutMaxTicks", 2400, 100, 72000);
        TRAVERTINE_ENABLED = b
                .comment("Precipitate Calcite/Tuff sinter terraces where erupted water pools on runoff.",
                        "Deposits only under settled (source) water at pool edges — never the fast",
                        "flowing channel — so it complements Water Erosion instead of fighting it.")
                .define("travertineEnabled", true);
        TRAVERTINE_DEPOSIT_CHANCE = b
                .comment("Per-second, per-column probability of a travertine deposit during eruption.",
                        "Raised so terraces are visible quickly.")
                .defineInRange("travertineDepositChance", 0.35D, 0.0D, 1.0D);
        CHAMBER_DRAIN_INTERVAL_TICKS = b
                .comment("While erupting, the basin loses one water cell every N ticks (it visibly",
                        "empties as it spouts). Lower = drains faster. 60 = one cell per second.")
                .defineInRange("chamberDrainIntervalTicks", 60, 1, 72000);
        CHAMBER_REFILL_INTERVAL_TICKS = b
                .comment("While recharging/cooling, the basin regains one water cell every N ticks",
                        "(groundwater + surface intake refilling it between eruptions).")
                .defineInRange("chamberRefillIntervalTicks", 100, 1, 72000);
        b.pop();

        b.push("emergent");
        EMERGENT_ENABLED = b
                .comment("Let PLAYER-BUILT water-over-rock-over-lava setups become live geysers.",
                        "ON by default. WARNING: with emergentDestructive=true this can blow up bases",
                        "you didn't intend as geysers — set false (or emergentDestructive=false) to disable.")
                .define("emergentEnabled", true);
        EMERGENT_DESTRUCTIVE = b
                .comment("If true, emergent eruptions use a block-breaking explosion (the 'it blows up",
                        "your house' behaviour). If false they vent harmlessly like natural geysers.")
                .define("emergentDestructive", true);
        EMERGENT_SCAN_INTERVAL_TICKS = b
                .comment("How often (ticks) each loaded player's surroundings are scanned for a setup.")
                .defineInRange("emergentScanIntervalTicks", 60, 20, 12000);
        EMERGENT_SCAN_RADIUS = b
                .comment("Horizontal/vertical radius around a player scanned for water+rock+lava columns.")
                .defineInRange("emergentScanRadius", 10, 2, 32);
        EMERGENT_MIN_WATER = b
                .comment("Minimum connected water cells above the rock before a setup ignites.")
                .defineInRange("emergentMinWater", 4, 1, 256);
        EMERGENT_LAVA_DEPTH = b
                .comment("How many blocks below the rock layer to look for lava (the heat source).")
                .defineInRange("emergentLavaDepth", 3, 1, 16);
        EMERGENT_MIN_SPACING = b
                .comment("Never ignite a new emergent core within this many blocks of an existing one.",
                        "Keeps one water-over-lava rig = ONE geyser instead of a swarm of overlapping",
                        "cores that fling you into orbit.")
                .defineInRange("emergentMinSpacing", 12, 1, 64);
        EMERGENT_DESTROY_RADIUS = b
                .comment("Half-width of the vertical throat an emergent eruption punches open through",
                        "its cap: 0 = 1-wide (just the plug), 1 = 3-wide, etc. It clears a column",
                        "UPWARD (not a sphere), directly (works underwater). Bedrock/fluids untouched.")
                .defineInRange("emergentDestroyRadius", 0, 0, 3);
        b.pop();

        b.push("tectonics");
        TECTONICS_ENABLED = b
                .comment("Master switch for the tectonic plate / fault-line model.",
                        "The model only READS the world (it never generates or edits blocks), so it is",
                        "safe with terrain mods like Terralith and Tectonic; plate crust types are read",
                        "from whatever biome source those mods install.")
                .define("tectonicsEnabled", true);
        PLATE_SCALE = b
                .comment("Average width of a tectonic plate, in blocks. Bigger means fewer, larger",
                        "plates and faults that are farther apart. 3000 gives continent-sized plates",
                        "that suit Terralith and Tectonic worlds.")
                .defineInRange("plateScale", 3000.0, 500.0, 100000.0);
        PLATE_JITTER = b
                .comment("How irregular the plate layout is. 0 makes a boring square grid; 1 makes",
                        "very ragged, natural-looking plates. Values above 1 are not supported because",
                        "plate centres could then leave their own cell.")
                .defineInRange("plateJitter", 0.8, 0.0, 1.0);
        FAULT_WIDTH = b
                .comment("How many blocks either side of a plate boundary count as fault zone. Inside",
                        "this band the fault type applies and tectonic stress rises toward the line;",
                        "outside it a column is plain plate interior.")
                .defineInRange("faultWidth", 220.0, 16.0, 4000.0);
        b.pop();

        b.push("hotspots");
        HOTSPOTS_ENABLED = b
                .comment("Mantle hotspots: intraplate volcanism that plate boundaries cannot explain.",
                        "Yellowstone, the richest geyser field on Earth, is a hotspot rather than a",
                        "boundary, so switching this off makes the model noticeably less realistic.")
                .define("hotspotsEnabled", true);
        HOTSPOT_SCALE = b
                .comment("Spacing of the hotspot grid, in blocks. Combined with hotspotDensity this",
                        "sets how far apart plumes end up.")
                .defineInRange("hotspotScale", 8500.0, 1000.0, 200000.0);
        HOTSPOT_DENSITY = b
                .comment("Fraction of hotspot grid cells that actually contain a plume. Low on purpose:",
                        "hotspots should be rare landmarks, not a second network of boundaries.")
                .defineInRange("hotspotDensity", 0.18, 0.0, 1.0);
        HOTSPOT_RADIUS = b
                .comment("Radius of the actively volcanic dome above a plume, in blocks.")
                .defineInRange("hotspotRadius", 700.0, 32.0, 20000.0);
        HOTSPOT_TRAIL_LENGTH = b
                .comment("Length of the extinct volcano chain the plate drags off the plume, in blocks.",
                        "This is the Hawaii-Emperor pattern: live volcano over the plume, dead cones",
                        "trailing back the way the plate came. Set 0 to disable trails.")
                .defineInRange("hotspotTrailLength", 2500.0, 0.0, 60000.0);
        BIOME_ANCHORING = b
                .comment("Let the model settle on geothermal ground the world generator has already",
                        "painted. Terralith ships biomes called yellowstone and caldera; they look",
                        "exactly like where geysers belong, but our plume grid is pure seed maths and",
                        "knew nothing about them, so the two landed in different places.",
                        "With this on, a column whose biome name contains yellowstone, geyser,",
                        "caldera, hot_spring, thermal, volcan or basalt is reported as a live mantle",
                        "hotspot - which is honest geology, since Yellowstone IS a hotspot and a",
                        "caldera is what hotspot volcanism leaves behind.",
                        "Read-only and matched by name, so it needs no dependency and works with any",
                        "terrain mod. With none installed nothing matches and behaviour is unchanged.")
                .define("biomeAnchoring", true);
        HOTSPOT_BASIN_SCALE = b
                .comment("Spacing of the geyser-basin grid INSIDE a hotspot dome, in blocks.",
                        "Real geyser fields are clustered, not evenly scattered: Yellowstone's",
                        "thousand-odd vents sit in a handful of basins with quiet country between",
                        "them. So the plume dome is subdivided into basins rather than being made",
                        "uniformly rich, which is both more accurate and far more striking to find.")
                .defineInRange("hotspotBasinScale", 320.0, 32.0, 4000.0);
        HOTSPOT_BASIN_DENSITY = b
                .comment("Fraction of those grid cells that actually are a geyser basin.")
                .defineInRange("hotspotBasinDensity", 0.30, 0.0, 1.0);
        HOTSPOT_FEATURE_BOOST = b
                .comment("How much denser geysers and hot springs get inside a geyser basin.",
                        "Multiplies the placement chance, so 3.0 means roughly three times as many",
                        "vents as an ordinary volcanic setting would give.")
                .defineInRange("hotspotFeatureBoost", 3.0, 1.0, 20.0);
        b.pop();

        b.push("depth");
        METRES_PER_BLOCK = b
                .comment("How many real metres one block of depth represents.",
                        "The world is only 384 blocks tall but stands in for tens of kilometres of",
                        "crust, so geological output (quake depth, crust thickness) is reported using",
                        "this scale. At the default, the buildable world maps to roughly 35 km - real",
                        "continental crust. This changes REPORTED numbers only; it never moves blocks.")
                .defineInRange("metresPerBlock", 90.0, 1.0, 5000.0);
        METRES_PER_BLOCK_HORIZONTAL = b
                .comment("How many real metres one block of HORIZONTAL distance represents.",
                        "Deliberately separate from metresPerBlock: the world is squashed vertically",
                        "(384 blocks standing in for tens of kilometres of crust) but not horizontally",
                        "- a Minecraft biome is already kilometres across. Using the vertical scale for",
                        "both made an M5.5 rift rupture only 42 blocks long, which is why fault",
                        "ruptures felt far too short. Only surface-rupture length uses this.")
                .defineInRange("metresPerBlockHorizontal", 25.0, 1.0, 5000.0);
        b.pop();

        b.push("placement");
        TECTONIC_PLACEMENT = b
                .comment("Gate geothermal features on the tectonic model instead of scattering them",
                        "evenly. With this on, geysers and volcanoes only appear where real geology",
                        "would put them (subduction arcs, rifts, hotspots), collision and strike-slip",
                        "zones get hot springs but never geysers, and plate interiors stay quiet.",
                        "Only affects NEWLY generated chunks.")
                .define("tectonicPlacement", true);
        DEEP_STRUCTURE_ENABLED = b
                .comment("Generate the deep geology that defines each kind of plate boundary:",
                        "a descending slab and magma chambers under a subduction arc, thinned and",
                        "fractured crust under a rift, a thick magma-free root under a collision belt,",
                        "and a shattered vertical zone along a strike-slip fault.",
                        "All of it sits below retrogenMaxY and never replaces player blocks, so it is",
                        "invisible from the surface and safe on existing worlds.")
                .define("deepStructureEnabled", true);
        DEEP_SURFACE_OUTCROP = b
                .comment("Let boundary rock reach daylight instead of hiding below Y=-30.",
                        "Squeezed into a 28-block window it was easy to tunnel straight past and never",
                        "know the geology was there. Real boundary rock does not stop at a depth: a",
                        "collision root outcrops in mountainsides, a rift's dyke swarm cuts the whole",
                        "crust, a strike-slip damage zone is a scar you can walk along.",
                        "The topsoil is always left alone (see deepStructureSoilDepth), so meadows",
                        "still look like meadows and only the rock beneath them changes - which is",
                        "also what soil actually does. Player blocks are never touched either way.")
                .define("deepStructureSurfaceOutcrop", true);
        DEEP_SOIL_DEPTH = b
                .comment("How many blocks of a column's own surface are left untouched when boundary",
                        "rock reaches the top. Four keeps grass, dirt, sand and the terrain mod's own",
                        "surface texture intact while cliffs, ravines, caves and mines all show the",
                        "structure in section.")
                .defineInRange("deepStructureSoilDepth", 4, 0, 16);
        OCEANIC_RIDGE_ENABLED = b
                .comment("Build mid-ocean ridges where two oceanic plates pull apart: a basalt swell",
                        "along the boundary, an axial (median) rift down its crest, pillow lava on the",
                        "flanks and black-smoker chimneys in the valley. This is where most of the",
                        "planet's volcanism actually happens, so leaving the sea floor blank was the",
                        "biggest gap left in the model. Sea floor only; never touches player blocks.")
                .define("oceanicRidgeEnabled", true);
        VOLCANO_SPAWN_CHANCE = b
                .comment("Per-chunk chance of a natural volcano BEFORE tectonic suitability is applied.",
                        "Deliberately tiny: volcanoes are huge structures and should be landmarks.",
                        "The suitability gate then removes them entirely outside volcanic settings.")
                .defineInRange("volcanoSpawnChance", 0.0060D, 0.0D, 1.0D);
        b.pop();

        b.push("quakes");
        QUAKES_ENABLED = b
                .comment("Earthquakes along plate boundaries. Each fault type produces its own real",
                        "deformation: rifts open fissures, convergent faults thrust one side over the",
                        "other, strike-slip faults offset the ground sideways.")
                .define("quakesEnabled", true);
        QUAKES_BREAK_BUILDS = b
                .comment("Allow quakes to damage player-placed blocks. OFF by default, matching the",
                        "rest of the mod: only natural terrain is ever deformed, so a base on a fault",
                        "line is safe. Turn on for full realism at your own risk.")
                .define("quakesBreakBuilds", false);
        QUAKES_BREAK_STRUCTURES = b
                .comment("Let earthquakes move villages and other world-generated structures.",
                        "A village is planks and cobblestone, so the rule that protects what a",
                        "player built protected villages too - which is why one could stand",
                        "untouched in the middle of a rupture that had moved everything around it.",
                        "With this on, anything the world generated (villages, temples, outposts,",
                        "fortresses) is treated as ground; blocks a player placed are still safe.",
                        "The honest catch: the test is the structure, not who laid the block. Move",
                        "into a village and make one of its houses yours and the quake will still",
                        "take it, because as far as the world is concerned it is still the village.")
                .define("quakesBreakStructures", true);
        UNSUPPORTED_BLOCKS_FALL = b
                .comment("After the ground moves, bring down anything the quake left hanging in the",
                        "air. Nothing is destroyed: the stack is set back down on the new ground, so a",
                        "tree keeps its trunk and a wall keeps its blocks - they just end up lower.",
                        "The one exception is plant matter whose ground fell more than three blocks:",
                        "that slope did not subside, it failed, and a fresh landslide scarp is bare.")
                .define("unsupportedBlocksFall", true);
        FALLING_INCLUDES_BUILDS = b
                .comment("Whether player-placed material falls too. ON by default: a floating house is",
                        "a worse outcome than a house that settled, and falling is not breaking - every",
                        "block survives the drop. Turn OFF to leave builds hanging exactly where they",
                        "are. Independent of quakesBreakBuilds, which decides whether the quake may",
                        "deform a build in the first place; this only decides what happens to one that",
                        "has already been undermined.")
                .define("fallingIncludesPlayerBlocks", true);
        QUAKE_BLOCKS_PER_TICK = b
                .comment("How many block edits a quake applies per tick on the server thread.",
                        "This is not just a performance budget: real ruptures travel along a fault at",
                        "kilometres per second and a large quake lasts tens of seconds, so letting the",
                        "deformation spread over a few seconds is more accurate than an instant snap.")
                .defineInRange("quakeBlocksPerTick", 150, 8, 20000);
        QUAKE_WARNING_TICKS = b
                .comment("Ticks between a quake being detected and the ground actually starting to",
                        "move. A seismograph is told the instant the rupture is triggered and sounds",
                        "its siren through this window, so a warning system has time to react before",
                        "the shaking arrives - which is exactly how real early warning works, the",
                        "alert travelling at the speed of light while the seismic waves do not.",
                        "20 = 1 second; 200 is ten seconds. Set 0 for no warning: the ground moves at",
                        "once and the siren and the shaking coincide.")
                .defineInRange("quakeWarningTicks", 200, 0, 1200);
        QUAKE_AMBIENT_INTERVAL = b
                .comment("Ticks between ambient earthquake rolls. 0 disables ambient quakes entirely",
                        "(the /geology quake command still works).")
                .defineInRange("quakeAmbientInterval", 6000, 0, 1728000);
        QUAKE_SEARCH_RADIUS = b
                .comment("How far from a player an ambient quake looks for a fault, in blocks.",
                        "This is the knob that actually decides how often you feel one. Each roll",
                        "picks a single random point inside this radius, and if that point is plate",
                        "interior nothing happens at all - so a player who is not near a boundary can",
                        "go a very long time without a quake however low the recurrence is set.",
                        "Raise it to find faults further off; lower it to make quakes strictly local.")
                .defineInRange("quakeSearchRadius", 128, 16, 2048);
        QUAKE_RECURRENCE_DAYS = b
                .comment("Average in-game days between ruptures on a fully stressed fault.",
                        "Real faults rupture on a recurrence interval - decades to centuries - rather",
                        "than at random every few minutes, and a quake you have to travel to and wait",
                        "for is worth far more than one that happens constantly. Stress scales this:",
                        "a locked boundary goes off closer to this figure, a sleepy one much less",
                        "often. The /geology quake command still fires one instantly whenever you",
                        "want to demonstrate or test.")
                .defineInRange("quakeRecurrenceDays", 8.0, 0.05, 1000.0);
        TICK_BUDGET_MS = b
                .comment("Milliseconds the WHOLE MOD may spend on the server thread per tick.",
                        "A hard wall-clock brake shared by everything: the quake itself, deformation",
                        "replayed into chunks that just loaded, ground settling afterwards, volcano",
                        "construction and chunk geology. When it runs out, all of it stops until the",
                        "next tick however much work is queued, so the mod can only ever make the",
                        "game a little slower and never lock it up.",
                        "",
                        "It used to be per-system, and each of the five measured it from its own",
                        "start - so a bad tick could hand the mod 32 ms of a 20 ms tick. 5 is a",
                        "quarter of a tick and leaves room for a heavily modded server; raise it if",
                        "you want geology to appear faster and have the headroom to spare.")
                .defineInRange("tickBudgetMs", 5, 1, 40);
        QUAKE_LAYERED = b
                .comment("Move the whole boundary together instead of tearing along it from one end.",
                        "An earthquake really does nucleate at a point and rip outward, but the",
                        "cumulative motion of two plates - which is what you are watching here over a",
                        "minute or two - happens everywhere along the boundary at once.",
                        "With this on, the deformation is applied one BLOCK OF MOVEMENT at a time",
                        "across the whole rupture, spread over several fronts so a tick still only",
                        "touches a handful of chunks. Costs nothing extra: the same edits, reordered.",
                        "A quake cut short by the edit cap then leaves every part of the fault moved a",
                        "little rather than half of it finished and half untouched.")
                .define("quakeLayeredApplication", true);
        QUAKE_MAX_EDITS = b
                .comment("Hard cap on how many blocks one earthquake may change.",
                        "Deformation is planned outward from the epicentre, so hitting this cap simply",
                        "shortens the rupture at its far ends rather than leaving holes in it.",
                        "Together with quakeBlocksPerTick this sets how long a quake takes to play out:",
                        "150000 edits at 300 per tick is about twenty-five seconds of ground tearing.")
                .defineInRange("quakeMaxEdits", 400000, 1000, 4000000);
        DEEP_STRUCTURE_BUDGET = b
                .comment("Block edits the deep boundary geology may make per chunk.",
                        "This runs constantly while exploring a fault zone, so it is deliberately",
                        "sparse: the slab and the metamorphic banding stay obvious when you dig, but",
                        "the cost of travelling along a boundary stays low.")
                .defineInRange("deepStructureBudget", 2200, 0, 20000);
        QUAKE_PENDING_LIMIT = b
                .comment("How many block edits may wait for their chunk to load before being dropped.",
                        "A long rupture reaches well past the loaded area; rather than force-loading",
                        "chunks, the leftovers are parked and applied as you travel along the fault, so",
                        "the scarp does not simply stop at the edge of your render distance.")
                .defineInRange("quakePendingLimit", 400000, 0, 5000000);
        QUAKE_MAX_RUPTURE = b
                .comment("Longest a rupture may run along a fault, in blocks.",
                        "Length is derived from magnitude with the real surface-rupture scaling",
                        "log10(L km) = 0.69 M - 3.22, converted through the depth scale, so a M7",
                        "tears several hundred blocks. Genuine M9 events rupture around a thousand",
                        "kilometres, which no Minecraft world is built for, so this clamps the top end.")
                .defineInRange("quakeMaxRupture", 1000, 32, 20000);
        QUAKE_MAX_FISSURE_DEPTH = b
                .comment("Deepest a rift fissure may open, in blocks. Kept moderate so a quake is",
                        "dramatic without gutting the landscape.")
                .defineInRange("quakeMaxFissureDepth", 15, 2, 128);
        b.pop();

        b.push("sulfur");
        SULFUR_ENABLED = b
                .comment("Native sulfur crusts around volcanic fumaroles and vents.",
                        "Real fumaroles deposit sulfur as escaping gases oxidise (Kawah Ijen), which is",
                        "the acidic counterpart to the alkaline travertine this mod already lays down",
                        "around geyser runoff.")
                .define("sulfurEnabled", true);
        SULFUR_DEPOSIT_CHANCE = b
                .comment("Per-attempt chance that a fumarole or vent lays down a sulfur block.")
                .defineInRange("sulfurDepositChance", 0.25, 0.0, 1.0);
        b.pop();

        b.push("integration");
        SUGGEST_OPTIONAL_MODS = b
                .comment("Mention the optional mods this one works better with, once, the first time",
                        "a player joins a world without them. The mod has no hard dependencies and",
                        "never will; but water is the one place where vanilla's own physics is the",
                        "limit rather than anything here, and a player has no way of knowing that.",
                        "Turn off if you are assembling a pack and would rather say it yourself.")
                .define("suggestOptionalMods", true);
        b.pop();

        b.push("instruments");
        SEISMOGRAPH_RANGE = b
                .comment("How far away, in blocks, a seismograph will look at an earthquake at all.",
                        "This is a housekeeping bound, not the physics: what really decides whether",
                        "a station records something is whether the trace clears the drum's noise",
                        "floor, and by that rule a large quake is detectable from absurdly far away",
                        "- which is true of real stations and not much use in a game. 4000 blocks is",
                        "100 km at the default horizontal scale, comfortably wider than anywhere a",
                        "player is likely to have built a second station.")
                .defineInRange("seismographRange", 4000, 64, 100000);
        b.pop();

        b.push("hydrology");
        WATER_TABLE_ENABLED = b
                .comment("Model a groundwater table under the world.",
                        "Nothing here places or edits a block on its own. It answers one question -",
                        "how deep is the water under this column - and the features that need an",
                        "answer (springs, wells, recovery after a quake) read it. Turned off, those",
                        "fall back to their older placement rules.")
                .define("waterTableEnabled", true);
        WATER_TABLE_SUBDUAL = b
                .comment("How much of the land's relief the water table climbs, from 0 to 1.",
                        "Groundwater is a SUBDUED replica of the topography: it rises from the",
                        "valley it drains into towards the high ground that recharges it, but",
                        "nothing like as steeply as the land does. At 0 the table is flat at the",
                        "valley floor and no water ever reaches the surface elsewhere. At 1 it",
                        "copies the land and springs break out all over the slopes. Measured on",
                        "upland terrain, the default puts spring lines on about 1% of land columns",
                        "- valley floors and breaks of slope, which is where they belong.")
                .defineInRange("waterTableSubdual", 0.50D, 0.0D, 1.0D);
        WATER_TABLE_DEPTH_TEMPERATE = b
                .comment("Blocks of dry ground above the water table in ordinary rainy country.",
                        "This is how deep a well has to be dug in a plains or forest biome.")
                .defineInRange("waterTableDepthTemperate", 8, 0, 64);
        WATER_TABLE_DEPTH_ARID = b
                .comment("Depth to water in hot biomes that get no rain at all.",
                        "Nothing recharges a desert aquifer from above, so the table sits far down",
                        "and desert wells are deep. Note that this effectively rules out desert",
                        "springs, which is correct: a real oasis is not fed by local rainfall but",
                        "by a regional aquifer recharged in mountains hundreds of kilometres away.",
                        "That is a geological exception, not a climate one, so it does not belong",
                        "in this number.")
                .defineInRange("waterTableDepthArid", 24, 0, 128);
        WATER_TABLE_DEPTH_HUMID = b
                .comment("Depth to water in hot, wet biomes - jungle, swamp, mangrove.",
                        "More rain goes in than drains away again, so the water is at your ankles.")
                .defineInRange("waterTableDepthHumid", 3, 0, 64);
        SPRING_RENEWAL_ENABLED = b
                .comment("Let a buried hot spring work its way back to the surface.",
                        "Every spring has a source seated far below the depth an earthquake reaches",
                        "(24 blocks). A quake therefore cannot destroy a spring, only block its",
                        "outlet, and an outlet still being pushed from below does not stay blocked:",
                        "the source bores a conduit back up, lining it with sinter as it climbs, and",
                        "cuts a fresh pool wherever it gets out. That is how the 1959 Hebgen Lake",
                        "quake left Yellowstone - outlets moved, deposits left behind at the old",
                        "ones, and not one system switched off.",
                        "Turning this off freezes every source where it is. A column with anything",
                        "player-built in it is never bored through, whatever this is set to.")
                .define("springRenewalEnabled", true);
        SPRING_STAGE_ONE_DAYS = b
                .comment("In-game days a stage 1 spring takes to reach stage 2.",
                        "A hot spring has four ages, 5, 9, 15 and 21 blocks across. Stage 1 appears",
                        "the moment the water reaches daylight; these three keys are how long the",
                        "steps after it take.",
                        "Only affects springs that are GROWING - one recovering after an earthquake,",
                        "or one a quake has newly opened. Springs made with the world start at",
                        "stage 4, so a new world has old springs in it rather than day-old puddles.",
                        "Set these very low to watch the whole sequence while testing, or use",
                        "/geology place hotspring <1-4> to stand all four side by side.")
                .defineInRange("springStageOneDays", 1.0D, 0.01D, 400.0D);
        SPRING_STAGE_TWO_DAYS = b
                .comment("Days from stage 2 to stage 3, where the pool reaches 15 blocks across.",
                        "At this step the spring breaks out past the rim it built earlier and",
                        "spreads to whatever shape the ground allows.")
                .defineInRange("springStageTwoDays", 5.0D, 0.01D, 400.0D);
        SPRING_STAGE_THREE_DAYS = b
                .comment("Days from stage 3 to a finished spring with its colour bands.",
                        "The microbial mats come last for a reason: they need a big, warm, stable",
                        "pool to live in, and a spring that opened a few days ago has not got one.")
                .defineInRange("springStageThreeDays", 12.0D, 0.01D, 400.0D);
        QUAKES_OPEN_NEW_SPRINGS = b
                .comment("Let an earthquake open a hot spring where there was not one.",
                        "Shaking the crust changes how easily water moves through it: some cracks",
                        "close, others open, and water that had no way to the surface finds one.",
                        "The 1959 Hebgen Lake quake did this at Yellowstone within days.",
                        "Three things still have to line up - heat below, groundwater reaching the",
                        "surface, and no spring already there - so most ruptures produce nothing,",
                        "and at most one new spring is opened per quake.")
                .define("quakesOpenNewSprings", true);
        QUAKE_SPRING_CHANCE = b
                .comment("Chance of a new spring per point of magnitude above 3.",
                        "At the default an M4 quake has roughly a 6% chance and an M8 about 30%,",
                        "before the geological conditions above are even checked. Raise it if you",
                        "want a world that visibly rearranges itself; set quakesOpenNewSprings to",
                        "false to switch the whole thing off.")
                .defineInRange("quakeSpringChance", 0.06D, 0.0D, 1.0D);
        b.pop();

        SPEC = b.build();
    }

    private GeyserConfig() {}
}
