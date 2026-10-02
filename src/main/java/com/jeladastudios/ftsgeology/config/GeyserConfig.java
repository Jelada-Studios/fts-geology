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
    public static final ForgeConfigSpec.DoubleValue VOLCANO_BOMB_REACH;
    public static final ForgeConfigSpec.BooleanValue VOLCANO_BOMBS_MELT_IN_LAVA;
    public static final ForgeConfigSpec.IntValue VOLCANO_CRATER_RADIUS;     // summit crater/lava-pool radius
    public static final ForgeConfigSpec.IntValue VOLCANO_LAVA_BUDGET;       // lava cells per eruption
    public static final ForgeConfigSpec.BooleanValue QUAKE_TRIGGERS_ERUPTIONS; // big quakes set nearby volcanoes off
    public static final ForgeConfigSpec.DoubleValue QUAKE_ERUPTION_CHANCE;
    public static final ForgeConfigSpec.IntValue QUAKE_ERUPTION_DELAY_MIN_TICKS;
    public static final ForgeConfigSpec.IntValue QUAKE_ERUPTION_DELAY_MAX_TICKS;
    public static final ForgeConfigSpec.DoubleValue DORMANT_VOLCANO_QUIET_FACTOR; // how much longer a dormant one sleeps
    public static final ForgeConfigSpec.BooleanValue VOLCANIC_ASHFALL;      // default ON
    public static final ForgeConfigSpec.BooleanValue ASHFALL_BURIES_CROPS;  // default ON
    public static final ForgeConfigSpec.BooleanValue VOLCANO_UNREST;        // swarm, warming springs before an eruption
    public static final ForgeConfigSpec.IntValue VOLCANO_UNREST_TICKS;
    public static final ForgeConfigSpec.IntValue PYROCLASTIC_FLOWS;         // per eruption of an explosive volcano
    public static final ForgeConfigSpec.DoubleValue PYROCLASTIC_FLOW_REACH;
    public static final ForgeConfigSpec.BooleanValue VOLCANIC_GAS;          // CO2 in hollows while restless
    public static final ForgeConfigSpec.DoubleValue ASH_WEATHERING;         // how fast ash washes off, 0 never
    public static final ForgeConfigSpec.BooleanValue ASH_LOAD_COLLAPSES_ROOFS;
    public static final ForgeConfigSpec.BooleanValue SOIL_FROM_BEDROCK;     // default ON

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
    public static final ForgeConfigSpec.BooleanValue LARGE_VOLCANOES;      // raised with new terrain
    public static final ForgeConfigSpec.DoubleValue LARGE_VOLCANO_CHANCE;  // share of suitable sites
    public static final ForgeConfigSpec.BooleanValue OCEAN_VOLCANOES;      // islands, atolls, guyots

    public static final ForgeConfigSpec.DoubleValue OCEAN_TRAIL_LENGTH;    // a plume's track through the sea, blocks
    public static final ForgeConfigSpec.BooleanValue DEEP_STRUCTURE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue DEEP_SURFACE_OUTCROP;  // boundary rock reaches daylight
    public static final ForgeConfigSpec.IntValue DEEP_SOIL_DEPTH;           // topsoil left untouched
    public static final ForgeConfigSpec.BooleanValue OCEANIC_RIDGE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue GEOLOGY_AT_GENERATION; // deep geology written as chunks generate
    public static final ForgeConfigSpec.BooleanValue ORE_GENESIS_ENABLED;   // ore laid down by tectonic setting
    public static final ForgeConfigSpec.BooleanValue PETROLEUM;             // oil and gas fields in the basins
    public static final ForgeConfigSpec.DoubleValue PETROLEUM_CHANCE;       // share of basin grid cells with a field
    public static final ForgeConfigSpec.BooleanValue FOSSIL_BEDS;            // Jurassic Reborn fossils laid by the geology

    // --- Earthquakes --------------------------------------------------------
    public static final ForgeConfigSpec.BooleanValue QUAKES_ENABLED;
    public static final ForgeConfigSpec.BooleanValue QUAKES_BREAK_BUILDS;  // default OFF
    public static final ForgeConfigSpec.BooleanValue UNSUPPORTED_BLOCKS_FALL;   // default ON
    public static final ForgeConfigSpec.BooleanValue FALLING_INCLUDES_BUILDS;   // default ON
    public static final ForgeConfigSpec.BooleanValue QUAKE_CAVE_COLLAPSE;       // cave roofs come down
    public static final ForgeConfigSpec.BooleanValue QUAKE_PRESERVES_ORES;      // ore stays put in a quake
    public static final ForgeConfigSpec.IntValue CAVE_COLLAPSE_DEPTH;           // how deep a probe looks
    public static final ForgeConfigSpec.IntValue QUAKE_BLOCKS_PER_TICK;    // main-thread apply budget
    public static final ForgeConfigSpec.IntValue QUAKE_WARNING_TICKS;      // alert window before the ground moves
    public static final ForgeConfigSpec.BooleanValue QUAKE_CHAT;           // quake alerts in chat, not over the hotbar
    public static final ForgeConfigSpec.IntValue QUAKE_FELT_RANGE;         // farthest a quake's shaking is felt
    public static final ForgeConfigSpec.IntValue QUAKE_DAMAGE_RANGE;       // farthest the shaking loosens builds
    public static final ForgeConfigSpec.BooleanValue QUAKE_AFTERSHOCKS;
    public static final ForgeConfigSpec.BooleanValue QUAKE_LIQUEFACTION;
    public static final ForgeConfigSpec.BooleanValue QUAKE_LANDSLIDES;
    public static final ForgeConfigSpec.BooleanValue RAIN_LANDSLIDES;
    public static final ForgeConfigSpec.DoubleValue RAIN_LANDSLIDE_RATE;
    public static final ForgeConfigSpec.DoubleValue AFTERSHOCK_RATE;
    public static final ForgeConfigSpec.IntValue AFTERSHOCK_MAX;
    public static final ForgeConfigSpec.DoubleValue AFTERSHOCK_DAYS;
    public static final ForgeConfigSpec.DoubleValue FORESHOCK_SHARE;
    public static final ForgeConfigSpec.IntValue QUAKE_AMBIENT_INTERVAL;   // ticks between ambient rolls
    public static final ForgeConfigSpec.DoubleValue QUAKE_RECURRENCE_DAYS; // mean interval between ruptures
    public static final ForgeConfigSpec.BooleanValue SMALL_QUAKES;         // magnitude 2.5 to 5 on the faults
    public static final ForgeConfigSpec.DoubleValue SMALL_QUAKE_RATE;      // small quakes a day per stretch
    public static final ForgeConfigSpec.DoubleValue QUAKE_FELT_FLOOR;      // the faintest shaking felt
    public static final ForgeConfigSpec.BooleanValue QUAKE_INFO_NEEDS_INSTRUMENT; // size and place only off an instrument
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
    public static final ForgeConfigSpec.BooleanValue TFC_COMPAT;             // write TFC blocks on a TFC world
    public static final ForgeConfigSpec.IntValue STALL_REPORT_SECONDS;       // default 30, 0 = off
    public static final ForgeConfigSpec.BooleanValue UPDATE_NOTICE;
    public static final ForgeConfigSpec.BooleanValue OLDER_WORLD_NOTICE;
    public static final ForgeConfigSpec.BooleanValue DH_TERRAIN;
    public static final ForgeConfigSpec.BooleanValue REMOVED_MOD_BLOCKS;     // a removed mod's rock stays rock
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> REMOVED_MOD_BLOCK_RULES;
    public static final ForgeConfigSpec.BooleanValue UNIFY_ORES;                 // one ore of a kind in new chunks
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> UNIFY_ORES_ORDER;
    public static final ForgeConfigSpec.BooleanValue UNIFY_EXISTING_CHUNKS;
    public static final ForgeConfigSpec.BooleanValue GEOLOGICAL_MOD_ORES;         // mod ores in their deposits
    public static final ForgeConfigSpec.BooleanValue VERBOSE_LOG;            // default OFF

    // --- Hydrology (groundwater) ---------------------------------------------
    public static final ForgeConfigSpec.BooleanValue RIVERS;                 // default ON, experimental
    public static final ForgeConfigSpec.BooleanValue RIVERS_REFILL;          // lost river water comes back
    public static final ForgeConfigSpec.BooleanValue SOIL_WATER;             // the ground's water, kept per chunk
    public static final ForgeConfigSpec.BooleanValue SOIL_WATER_GROUND;      // grass colour, rain-fed farmland
    public static final ForgeConfigSpec.BooleanValue SEA_WATER_SALTY;        // sea water waters no field
    public static final ForgeConfigSpec.BooleanValue DAMS;                   // dammed rivers fill; weak dams break
    public static final ForgeConfigSpec.BooleanValue WATER_LOAD_COLLAPSE;    // cave roofs under new water, drained karst
    public static final ForgeConfigSpec.DoubleValue SINKHOLE_WARNING_DAYS;    // days a drawn-down karst roof takes to fall
    public static final ForgeConfigSpec.DoubleValue SUBSIDENCE;               // clay settling over ground the wells drew down
    public static final ForgeConfigSpec.BooleanValue REGIONAL_RAIN;           // storms instead of one weather for the world
    public static final ForgeConfigSpec.DoubleValue RAIN_AMOUNT;              // how much of the land the weather keeps under rain
    public static final ForgeConfigSpec.DoubleValue RAIN_STRENGTH;            // how hard the storms rain
    public static final ForgeConfigSpec.DoubleValue STORM_LENGTH;             // how long the storms last
    public static final ForgeConfigSpec.DoubleValue STORM_SIZE;               // how wide the storms are
    public static final ForgeConfigSpec.BooleanValue PUDDLES;                 // the mod's own puddles, without Puddles & Floods
    public static final ForgeConfigSpec.BooleanValue RIVER_SPILLS;            // a river runs out through a bank taken away
    public static final ForgeConfigSpec.BooleanValue STREAMS_DRY_UP;          // a well's cone dries the small streams over it
    public static final ForgeConfigSpec.BooleanValue RIVER_FLOODS;            // rivers over their banks in heavy rain on soaked ground
    public static final ForgeConfigSpec.BooleanValue HOLLOW_PONDS;            // rain running off into closed hollows stands there as ponds
    public static final ForgeConfigSpec.BooleanValue LAKE_LEVELS;             // lakes down a block in a long drought
    public static final ForgeConfigSpec.BooleanValue KARST;                  // rivers sink, caves, sinkholes
    public static final ForgeConfigSpec.BooleanValue WATER_TABLE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue WATER_TABLE_SUBDUAL;      // relief it copies, 0..1
    public static final ForgeConfigSpec.IntValue WATER_TABLE_DEPTH_TEMPERATE; // blocks below the land
    public static final ForgeConfigSpec.IntValue WATER_TABLE_DEPTH_ARID;
    public static final ForgeConfigSpec.IntValue WATER_TABLE_DEPTH_HUMID;
    public static final ForgeConfigSpec.BooleanValue SPRING_RENEWAL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue QUAKES_BREAK_STRUCTURES;
    public static final ForgeConfigSpec.BooleanValue SHAKING_LOOSENS_BUILDS;
    public static final ForgeConfigSpec.DoubleValue SHAKING_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue SHAKING_FELLS_TREES;
    public static final ForgeConfigSpec.DoubleValue SPRING_STAGE_ONE_DAYS;
    public static final ForgeConfigSpec.DoubleValue SPRING_STAGE_TWO_DAYS;
    public static final ForgeConfigSpec.DoubleValue SPRING_STAGE_THREE_DAYS;
    public static final ForgeConfigSpec.BooleanValue QUAKES_OPEN_NEW_SPRINGS;
    public static final ForgeConfigSpec.DoubleValue QUAKE_SPRING_CHANCE;   // per magnitude over 3
    public static final ForgeConfigSpec.DoubleValue OCEAN_SHARE;           // share of plates that are oceanic (own terrain)
    public static final ForgeConfigSpec.DoubleValue TERRAIN_BELT_FACTOR;   // mountain belt width, in fault widths
    public static final ForgeConfigSpec.DoubleValue TERRAIN_UPLIFT;        // blocks a collision lifts the ground
    public static final ForgeConfigSpec.DoubleValue DEM_HEIGHT_SCALE;      // how much the real mountain crops are exaggerated
    public static final ForgeConfigSpec.BooleanValue LITHOLOGY;           // rock sequences under the ground (own terrain)
    public static final ForgeConfigSpec.DoubleValue ALLUVIAL_CROP_GROWTH; // how much faster crops ripen on a floodplain

    // --- Instruments ---------------------------------------------------------
    public static final ForgeConfigSpec.IntValue SEISMOGRAPH_RANGE;   // blocks a station can hear
    public static final ForgeConfigSpec.DoubleValue SEISMOGRAPH_SIREN; // magnitude from which a station wails, not chimes
    public static final ForgeConfigSpec.IntValue TURBINE_MAX_FE;      // FE/t of a turbine over the hottest ground
    public static final ForgeConfigSpec.IntValue TURBINE_MIN_FE;      // FE/t over the faintest heat it runs on
    public static final ForgeConfigSpec.IntValue TURBINE_WELL_DEPTH;  // lengths of casing a well may run to
    public static final ForgeConfigSpec.IntValue WELL_PUMP_RATE;      // mB/t a well pump lifts at most
    public static final ForgeConfigSpec.IntValue WELL_PUMP_ENERGY;    // FE a bucket a block of lift
    public static final ForgeConfigSpec.IntValue CORE_DRILL_DEPTH;    // blocks the hand core drill bores down
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
        VOLCANO_BOMB_REACH = b
                .comment("How far volcanic bombs are thrown: 1 lands them over the crater rim and down the flanks,",
                        "a large volcano's out to some eighty blocks past its rim; 2 twice as far.")
                .defineInRange("volcanoBombReach", 1.0, 0.2, 4.0);
        VOLCANO_BOMBS_MELT_IN_LAVA = b
                .comment("A bomb that comes down in lava, such as the volcano's own crater lake, sinks into it",
                        "instead of setting there as a block of basalt.")
                .define("volcanoBombsMeltInLava", true);
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
        QUAKE_TRIGGERS_ERUPTIONS = b
                .comment("A large earthquake can set off volcanoes around it once the ground has settled, as the",
                        "1960 Chile earthquake did at Cordón Caulle. Only a volcano with magma to draw on erupts.")
                .define("quakeTriggersEruptions", true);
        QUAKE_ERUPTION_CHANCE = b
                .comment("Chance a volcano at the epicentre erupts after a magnitude 9 quake. Less for a smaller",
                        "quake (none under 6.5) and further away (none past twice the rupture length plus 200 blocks).")
                .defineInRange("quakeEruptionChance", 0.6, 0.0, 1.0);
        QUAKE_ERUPTION_DELAY_MIN_TICKS = b
                .comment("Shortest time from the quiet zone's release to the volcano starting to rumble (1200 = 1 min).")
                .defineInRange("quakeEruptionDelayMinTicks", 1200, 20, 720000);
        QUAKE_ERUPTION_DELAY_MAX_TICKS = b
                .comment("Longest time from the quiet zone's release to the volcano starting to rumble (6000 = 5 min).")
                .defineInRange("quakeEruptionDelayMaxTicks", 6000, 20, 720000);
        DORMANT_VOLCANO_QUIET_FACTOR = b
                .comment("How many times longer a dormant large volcano sleeps between eruptions than a live one. Its",
                        "crater is crusted over; it wakes on this long cycle, or after a big earthquake nearby.")
                .defineInRange("dormantVolcanoQuietFactor", 6.0, 1.0, 100.0);
        ASHFALL_BURIES_CROPS = b
                .comment("Let falling ash bury crops. A field downwind of an eruption loses its crop under",
                        "the ash, and under three layers or more its tilled soil goes back to dirt. Shovel",
                        "the ash off, till and sow again. Off, ash never settles on farmland.")
                .define("ashfallBuriesCrops", true);
        VOLCANO_UNREST = b
                .comment("Warning signs before an eruption, as magma rises under the volcano: a swarm of small",
                        "earthquakes under the mountain that seismographs record (and sound a warning for once it",
                        "comes thick and fast), hot springs and geysers round it warming, the ground swelling",
                        "(the geothermal probe measures it) and the fumaroles smoking harder.")
                .define("volcanoUnrest", true);
        VOLCANO_UNREST_TICKS = b
                .comment("How long before an eruption the unrest starts (3600 = 3 min). A dormant volcano, sealed",
                        "over, wakes twice as slowly.")
                .defineInRange("volcanoUnrestTicks", 3600, 200, 72000);
        PYROCLASTIC_FLOWS = b
                .comment("Pyroclastic flows an eruption of a stratovolcano or a caldera sends down its flanks: part",
                        "of the column falls back and runs down the valleys as a glowing avalanche of ash and gas,",
                        "faster than anyone can run. It burns whatever is caught in it, strips the plants and the",
                        "trees' leaves, shatters glass, sets wooden buildings alight and leaves a bed of ash along",
                        "the valley, deepest where it stops. Shields and fissures, with their runny lava, send none.",
                        "0 turns them off.")
                .defineInRange("pyroclasticFlows", 1, 0, 8);
        PYROCLASTIC_FLOW_REACH = b
                .comment("How far pyroclastic flows run: 1 takes a large volcano's some two hundred blocks out from",
                        "its crater down a valley; less on open slopes, where they spread and stop sooner.")
                .defineInRange("pyroclasticFlowReach", 1.0, 0.25, 3.0);
        VOLCANIC_GAS = b
                .comment("Carbon dioxide seeping out of a volcano collects, unseen, in its crater and round its vents,",
                        "and while it is restless or erupting in hollows on its flanks too. Breathing it makes you sick",
                        "and weak, and it suffocates whatever keeps its head down in it; torches, candles and campfires",
                        "go out in it, and the plants in it die.")
                .define("volcanicGas", true);
        ASH_WEATHERING = b
                .comment("How fast fallen ash wears away where the sky is open over it: the rain washes it off and the",
                        "wind takes it, a layer about every game day, twice as fast in the rain, and the ground and its",
                        "grass come back out from under it. A deep bed goes a layer at a time, so it lasts. 1 is the",
                        "default, 2 twice as fast, 0 keeps it for good.")
                .defineInRange("ashWeathering", 1.0, 0.0, 10.0);
        ASH_LOAD_COLLAPSES_ROOFS = b
                .comment("Ash piling up on a roof can bring it down, as it did round Pinatubo in 1991: glass and",
                        "wool give way under a couple of layers, wood under a few more, stone under most of a",
                        "block; less in the rain, since wet ash is twice as heavy. Obsidian and metal hold.")
                .define("ashLoadCollapsesRoofs", true);
        VOLCANIC_ASHFALL = b
                .comment("Let an eruption lay ash on the ground downwind of the volcano.",
                        "The eruption column is the thing you see from a distance, and what comes",
                        "down out of it is the thing you walk through afterwards: the ground greys",
                        "over in a lobe on the downwind side rather than a ring, so you can read",
                        "which way the wind was blowing. Each volcano keeps its own prevailing wind",
                        "for the life of the world.",
                        "The ash settles as a layer on top of whatever is there, like snow, so it",
                        "destroys nothing: the grass is underneath it, the volcano's own rock stays",
                        "rock, repeat eruptions deepen the layer up to a full block, and it can be",
                        "dug off. Turn it off if you would rather the countryside around a volcano",
                        "stayed green.")
                .define("volcanicAshfall", true);
        SOIL_FROM_BEDROCK = b
                .comment("Let the soil show what rock it weathered out of.",
                        "Soil is the rotted top of whatever is underneath it, and its colour is the",
                        "clearest single clue about what a place is made of: iron-rich rock like",
                        "basalt, gabbro and peridotite oxidises and the ground goes red, which is",
                        "why tropical soils are that colour; limestone and marble give a thin pale",
                        "alkaline soil; granite and its relatives give an acid, leached one.",
                        "Only NAMED rock counts. Ordinary vanilla stone is left alone deliberately -",
                        "it is the bulk of the crust rather than a particular rock, and acting on it",
                        "would repaint every hillside in the world. So this shows up where the mod",
                        "has actually put geology, and nowhere else.")
                .define("soilFromBedrock", true);
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
                        "Multiplies the placement chance, so 4.5 means roughly four and a half",
                        "times as many vents as an ordinary volcanic setting would give.")
                .defineInRange("hotspotFeatureBoost", 4.5, 1.0, 20.0);
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
        GEOLOGY_AT_GENERATION = b
                .comment("Write the geology while a chunk is being generated instead of afterwards.",
                        "A chunk made after the mod was installed then gets its boundary rock, ocean ridges,",
                        "ore, fumarole fields, basin floors and soil colour as part of world generation:",
                        "nothing on the server tick, nothing sent to players, and nothing for mods that hook",
                        "block changes to react to. Chunks that already existed still get it from the",
                        "background queue as before. Hot springs, geysers and small volcanoes always do.",
                        "Turn off only if another world generation mod misbehaves with it.")
                .define("geologyAtGeneration", true);
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
                        "collision root outcrops in mountainsides, a rift's dike swarm cuts the whole",
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
        ORE_GENESIS_ENABLED = b
                .comment("Lay ore down where the geology that makes it is: porphyry copper with a",
                        "malachite and azurite cap under subduction arcs, orogenic gold and skarn gems in",
                        "collision belts, massive sulfides at rifts, galena along transform faults,",
                        "hydrothermal veins through faults and geothermal fields, and coal and ironstone",
                        "seams in the quiet basins between. Applies to chunks that have not had their",
                        "deep geology yet; it never touches player blocks.")
                .define("oreGenesisEnabled", true);
        PETROLEUM = b
                .comment("Oil and gas fields in the sedimentary basins: a dome of reservoir sandstone under shale, gas at",
                        "its top, oil-soaked sandstone under it, water below. Broken into, its gas comes out under the",
                        "pressure of its depth. With Create Diesel Generators the pumpjack finds oil over the fields only.",
                        "New chunks only.")
                .define("petroleumFields", true);
        PETROLEUM_CHANCE = b
                .comment("Share of the 640-block grid cells in a basin that hold a field.")
                .defineInRange("petroleumFieldChance", 0.4, 0.0, 1.0);
        FOSSIL_BEDS = b
                .comment("With Jurassic Reborn, on the mod's own world type: its fossils, amber and gypsum are laid by the",
                        "geology instead of in every chunk -- bone beds of one age in the sedimentary rock of the basins (sea",
                        "creatures in shale and limestone, land animals in sandstone), rich in a few places, plant fossils over",
                        "the coal, amber with the young coal, gypsum in dry basins; the ages in order through the rock, oldest deepest.",
                        "New chunks only; the world must be restarted for a change.")
                .define("fossilBeds", true);
        VOLCANO_SPAWN_CHANCE = b
                .comment("Per-chunk chance of a natural volcano BEFORE tectonic suitability is applied.",
                        "Deliberately tiny: volcanoes are huge structures and should be landmarks.",
                        "The suitability gate then removes them entirely outside volcanic settings.")
                .defineInRange("volcanoSpawnChance", 0.0060D, 0.0D, 1.0D);
        LARGE_VOLCANOES = b
                .comment("Raise large volcanoes while new terrain generates: stratovolcanoes about 150 blocks",
                        "tall on subduction arcs, fissures some 400 blocks long on rifts, and shields and",
                        "calderas 400 blocks across over mantle plumes. Only chunks generated from now on get",
                        "them; nothing this size is ever built into terrain that already exists.")
                .define("largeVolcanoes", true);
        LARGE_VOLCANO_CHANCE = b
                .comment("Share of the suitable sites that get a large volcano. Set it before exploring, not",
                        "after: a volcano straddling explored and new ground is only built on the new side.")
                .defineInRange("largeVolcanoChance", 1.0D, 0.0D, 1.0D);
        OCEAN_VOLCANOES = b
                .comment("Raise large volcanoes from the sea floor too: islands over mantle plumes and on island arcs,",
                        "and along a plume's track through the sea older islands, atolls and guyots. Like",
                        "largeVolcanoes, only terrain generated from now on gets them.")
                .define("oceanVolcanoes", true);

        OCEAN_TRAIL_LENGTH = b
                .comment("How far a plume's track runs through the sea, in blocks. The plate carries its islands off the",
                        "plume, so along the track they are older, lower and more worn, then reefs round lagoons in warm",
                        "water and drowned flat tops in cold. 0 leaves only the live island over the plume.")
                .defineInRange("oceanTrailLength", 6000.0D, 0.0D, 60000.0D);
        b.pop();

        b.push("quakes");
        QUAKES_ENABLED = b
                .comment("Earthquakes along plate boundaries. Each fault type produces its own real",
                        "deformation: rifts open fissures, convergent faults thrust one side over the",
                        "other, strike-slip faults offset the ground sideways.")
                .define("quakesEnabled", true);
        QUAKES_BREAK_BUILDS = b
                .comment("Let the rupture itself take what players built: a build standing on ground the fault moves is",
                        "buried where the ground rises and comes down where it drops or slides. Off, a build on the fault",
                        "line is left standing and the ground under it does not move. The shaking further out is separate",
                        "(shakingLoosensBuilds).")
                .define("quakesBreakBuilds", true);
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
        SHAKING_LOOSENS_BUILDS = b
                .comment("Let the shaking knock loose blocks off buildings: what players built and the villages and",
                        "other structures the world made, never the ground or a tree. A block with a face in the open",
                        "may be shaken off and fall; the weaker its material the likelier -- sand and gravel first,",
                        "then glass, which breaks, then wool, planks, cobblestone and bricks, while obsidian and",
                        "metal blocks hold. A magnitude 5 takes the odd block off a sand house; a great earthquake",
                        "near its fault brings walls down. Blocks with contents, such as chests, are left alone.",
                        "Lanterns, torches, flower pots, candles, paintings and item frames come down first, and",
                        "dust sifts from the ceiling over a player indoors.",
                        "Separate from quakesBreakBuilds, which lets the rupture itself move a build.")
                .define("shakingLoosensBuilds", true);
        SHAKING_DAMAGE = b
                .comment("How hard the shaking is on buildings: 1 is the default, 2 about twice as many blocks,",
                        "0.5 half as many.")
                .defineInRange("shakingDamage", 1.0, 0.0, 10.0);
        SHAKING_FELLS_TREES = b
                .comment("With Dynamic Trees installed, strong shaking brings its trees down whole, falling over as",
                        "a felled tree does and leaving their logs. Only Dynamic Trees' trees: a vanilla tree stands.")
                .define("shakingFellsTrees", true);
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
        QUAKE_CAVE_COLLAPSE = b
                .comment("Let a strong earthquake bring cave roofs down along its rupture. The bottom of a",
                        "roof drops onto the cave floor as rubble and the void rises into the space it left;",
                        "where the rock above was thin it reaches the surface and a sinkhole opens. Never",
                        "moves a roof of player blocks, never buries anything standing in a cave, and leaves",
                        "flooded caves and ground under water alone.")
                .define("quakeCaveCollapse", true);
        QUAKE_PRESERVES_ORES = b
                .comment("Leave ore blocks where they are when a quake moves the ground round them. Off by",
                        "default: a fault really does offset an ore body, which is a known headache in mining.",
                        "For packs whose ore veins must stay whole.")
                .define("quakePreservesOres", false);
        CAVE_COLLAPSE_DEPTH = b
                .comment("How far below the surface, in blocks, a quake looks for caves to bring down.")
                .defineInRange("caveCollapseDepth", 48, 8, 128);
        QUAKE_BLOCKS_PER_TICK = b
                .comment("How many block edits a quake applies per tick on the server thread.",
                        "This is not just a performance budget: real ruptures travel along a fault at",
                        "kilometres per second and a large quake lasts tens of seconds, so letting the",
                        "deformation spread over a few seconds is more accurate than an instant snap.",
                        "The tick budget (tickBudgetMs) is the real brake; this only caps a fast machine.")
                .defineInRange("quakeBlocksPerTick", 400, 8, 20000);
        QUAKE_WARNING_TICKS = b
                .comment("Ticks between a quake being detected and the ground actually starting to",
                        "move. A seismograph is told the instant the rupture is triggered and sounds",
                        "its siren through this window, so a warning system has time to react before",
                        "the shaking arrives - which is exactly how real early warning works, the",
                        "alert travelling at the speed of light while the seismic waves do not.",
                        "20 = 1 second; 200 is ten seconds. Set 0 for no warning: the ground moves at",
                        "once and the siren and the shaking coincide.")
                .defineInRange("quakeWarningTicks", 200, 0, 1200);
        QUAKE_CHAT = b
                .comment("Where a quake is told to the players who feel it. Off: a few seconds over the hotbar -- the",
                        "alert, the P wave's countdown to the strong shaking, and how hard it shook here -- and nothing",
                        "in chat. On: the alert also goes to chat, as it used to. A seismograph's own report is chat",
                        "either way.")
                .define("quakeMessagesInChat", false);
        QUAKE_FELT_RANGE = b
                .comment("The farthest from the rupture, in blocks, a quake's shaking is felt. Each player feels",
                        "it as strongly as their distance allows: a quick jolt and a rumble as the P wave arrives,",
                        "then the S wave's heavy shaking, seconds later the further out they are. A magnitude 6",
                        "is felt some nine hundred blocks out, a great earthquake thousands.")
                .defineInRange("quakeFeltRange", 4000, 64, 50000);
        QUAKE_DAMAGE_RANGE = b
                .comment("The farthest from the rupture, in blocks, the shaking loosens blocks off buildings. Only",
                        "loaded chunks are gone through.")
                .defineInRange("quakeDamageRange", 1500, 64, 20000);
        QUAKE_LIQUEFACTION = b
                .comment("Strong shaking (about Mercalli VII and up) liquefies loose wet ground: sand, silt, gravel and",
                        "soil by a river, lake or sea, on a floodplain, or over a high water table. Sand boils open,",
                        "what players and villages built on it settles a block into it -- unevenly, so a house can",
                        "crack across -- and whoever stands on it is held fast for a few seconds.")
                .define("quakeLiquefaction", true);
        QUAKE_LANDSLIDES = b
                .comment("Strong shaking (about Mercalli VI and up) brings down the loose cover of steep natural slopes:",
                        "soil, scree, sand and snow slide down as one mass, trees and all, heap up at the foot and leave",
                        "a scar. Never on a slope a player or a village built on, never beside water.")
                .define("quakeLandslides", true);
        RAIN_LANDSLIDES = b
                .comment("Steep natural slopes whose ground days of rain have soaked through can come down in a downpour,",
                        "with no quake: stones roll and the ground creaks first, then the slope goes, the wet soil as mud",
                        "running on further. Seldom, only round players, never on built ground or beside water.")
                .define("rainLandslides", true);
        RAIN_LANDSLIDE_RATE = b
                .comment("How often a soaked slope gives way in rain, against the usual (1).")
                .defineInRange("rainLandslideRate", 1.0, 0.0, 20.0);
        QUAKE_AFTERSHOCKS = b
                .comment("After a quake of magnitude 5.5 or more the fault goes on slipping for days: the few aftershocks",
                        "worth feeling, three to five for most quakes, sooner rather than later (Omori's law), the largest",
                        "about 1.2 below the main shock. Those of 5.5 and up near a player break the ground too; the rest",
                        "are felt and recorded.",
                        "Also lets a great earthquake be announced by a foreshock minutes before it.")
                .define("quakeAftershocks", true);
        AFTERSHOCK_RATE = b
                .comment("How many aftershocks: 1 is the natural number for the main shock's size, 0.5 half as many.")
                .defineInRange("aftershockRate", 1.0, 0.0, 10.0);
        AFTERSHOCK_MAX = b
                .comment("The most aftershocks one quake has.")
                .defineInRange("aftershockMax", 8, 1, 500);
        AFTERSHOCK_DAYS = b
                .comment("Over how many in-game days a sequence dies away.")
                .defineInRange("aftershockDays", 3.0, 0.1, 100.0);
        FORESHOCK_SHARE = b
                .comment("Share of the great ambient earthquakes (magnitude 7 and up) that a foreshock, about two",
                        "magnitudes smaller, comes before by one to five minutes.")
                .defineInRange("foreshockShare", 0.3, 0.0, 1.0);
        QUAKE_AMBIENT_INTERVAL = b
                .comment("Ticks between rolls of the faults' clocks. Every stretch of boundary within quakeFeltRange of a",
                        "player or seismographRange of a seismograph is run on this far through its cycle at each roll;",
                        "the rate of quakes does not depend on it, only how finely their times are drawn. 0 disables",
                        "the world's own quakes entirely (the /geology quake command still works).")
                .defineInRange("quakeAmbientInterval", 1200, 0, 1728000);
        QUAKE_RECURRENCE_DAYS = b
                .comment("Average in-game days between quakes of magnitude 5 and up on each 256 blocks of a fully",
                        "stressed boundary; a sleepier boundary goes less often, by its stress. Every stretch keeps its",
                        "own clock (quiet after it breaks, more and more likely to go the longer it holds), and sizes",
                        "follow Gutenberg-Richter up to the boundary's largest, so small quakes are felt near the fault",
                        "and great ones far out. At 8: on a boundary one is felt every two or three days and one does",
                        "damage every month or so; a thousand blocks off one is felt every ten to fifteen days, and",
                        "a seismograph there records several a day. The /geology quake command fires one at once.")
                .defineInRange("quakeRecurrenceDays", 8.0, 0.01, 1000.0);
        SMALL_QUAKES = b
                .comment("Small quakes, magnitude 2.5 to 5, on the boundaries between the larger ones: nothing breaks,",
                        "near the fault they are felt as a faint tremor and a low rumble, and the seismographs record",
                        "them.")
                .define("smallQuakes", true);
        SMALL_QUAKE_RATE = b
                .comment("Small quakes a game day on each 256 blocks of a fully stressed boundary (a sleepier one has",
                        "fewer, by its stress); sizes follow Gutenberg-Richter, most of them small, felt only near the",
                        "fault. With the faint shaking of the large quakes far off, a player living on a boundary feels a",
                        "faint tremor about once or twice an hour of play, a thousand blocks off about once in two.")
                .defineInRange("smallQuakeRate", 1.0, 0.0, 50.0);
        QUAKE_FELT_FLOOR = b
                .comment("The faintest shaking felt, on the scale where 3 is a light quake felt indoors (Mercalli III):",
                        "between this and 3 a quake is only a faint tremor in the view and a far rumble, with nothing",
                        "written. 3 turns the faint ones off; 2 is felt twice as often as 2.5.")
                .defineInRange("quakeFeltFloor", 2.5, 1.0, 3.0);
        QUAKE_INFO_NEEDS_INSTRUMENT = b
                .comment("A quake's size, place and depth are told only to a player near a seismograph that recorded",
                        "it (or a station terminal on the network): without one a player only feels it -- the P wave",
                        "and then the S wave and how hard it shook. The instruments' screens list every quake heard.")
                .define("quakeInfoNeedsInstrument", true);
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
                .defineInRange("quakeMaxEdits", 1000000, 1000, 4000000);
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
        TFC_COMPAT = b
                .comment("With TerraFirmaCraft loaded, build volcanoes, veins and the rest out of TFC's rock, soil and sand",
                        "instead of vanilla blocks, and read TFC's seas and rivers for the plates. Off, the mod behaves as",
                        "it does anywhere else.")
                .define("tfcCompat", true);
        SUGGEST_OPTIONAL_MODS = b
                .comment("Mention the optional mods this one works better with, once, the first time",
                        "a player joins a world without them. The mod has no hard dependencies and",
                        "never will; but water is the one place where vanilla's own physics is the",
                        "limit rather than anything here, and a player has no way of knowing that.",
                        "Turn off if you are assembling a pack and would rather say it yourself.")
                .define("suggestOptionalMods", true);
        UPDATE_NOTICE = b
                .comment("Tell a world's owner, or an operator, when they join and a newer version of the mod is",
                        "out, as Forge's update check found it. Once a session. Forge's own check can be turned",
                        "off in fml.toml (versionCheck), which silences this too.")
                .define("updateNotice", true);
        OLDER_WORLD_NOTICE = b
                .comment("Tell a world's owner, or an operator, once, when the world was made with an older",
                        "version of the mod: what a new version changes in the terrain shows only in ground",
                        "generated from then on.")
                .define("olderWorldNotice", true);
        DH_TERRAIN = b
                .comment("With Distant Horizons installed, draw the far terrain of the mod's world types straight from",
                        "the plate model instead of generating chunks there: many times faster and lighter on memory,",
                        "without trees' shapes, villages or caves, which appear once the ground is really generated.",
                        "Off, Distant Horizons generates the far chunks its own way.")
                .define("distantHorizonsTerrain", true);
        REMOVED_MOD_BLOCKS = b
                .comment("When a mod is taken out of a world, the blocks it laid in the ground turn to air as their",
                        "chunks load, and the hole is saved: ore and fossil pockets, crystals and rock left empty in",
                        "cave walls. On, such a block becomes the rock round it instead (deepslate for a deepslate",
                        "one, the local rock in TerraFirmaCraft) as its chunk loads, where its name reads like",
                        "rock, ore, a crystal or soil, or where rock closes round it on every side. What reads as",
                        "a build - bricks, planks, glass, lamps, doors - is left to go, as before. Chunks already",
                        "loaded and saved without the mod have lost those blocks for good; /geology fillvoids",
                        "closes the small holes they left. It cannot tell them from a small sealed room or a",
                        "one-block tunnel end a player dug: run it where the mod's blocks were, not over your own.")
                .define("removedModBlocksToRock", true);
        REMOVED_MOD_BLOCK_RULES = b
                .comment("Your own choice for a removed mod's blocks, before the rules above: \"modid:block=replacement\"",
                        "for one block or \"modid:*=replacement\" for all of a mod's, the replacement a block id or",
                        "\"air\" (to let it go) or \"rock\" (the rock round it). For example",
                        "[\"jurassicreborn:*=rock\", \"somemod:glowing_egg=air\"].")
                .defineListAllowEmpty(java.util.List.of("removedModBlockRules"), java.util.List::of,
                        o -> o instanceof String s && s.indexOf(':') > 0 && s.indexOf('=') > s.indexOf(':'));
        UNIFY_ORES = b
                .comment("One ore of a kind: where several mods add the same ore (tin, lead, silver, uranium and so on, by the",
                        "forge:ores/ tags), new chunks have only one of them, the first mod's in oreUnifyOrder, in its stone and",
                        "deepslate forms, and the others' ore of it is not placed a second time where that mod places its own,",
                        "so there is as much of it as one mod would put. What is mined drops that mod's ore. Ingots and the",
                        "like are not made one: a mod such as Almost Unified does that. Not in TerraFirmaCraft.")
                .define("unifyOres", true);
        UNIFY_ORES_ORDER = b
                .comment("Which mod's ore is kept, first first; mods not named come after, by name.")
                .defineListAllowEmpty(java.util.List.of("oreUnifyOrder"), () -> java.util.List.of("minecraft", "alltheores", "create",
                        "immersiveengineering", "electrodynamics", "mekanism", "thermal"), o -> o instanceof String);
        GEOLOGICAL_MOD_ORES = b
                .comment("Other mods' ores where the geology would put them, in the mod's own world types: tin, tungsten and",
                        "lithium in greisen at the roofs of granite bodies; lead, zinc, silver and fluorite in pods in platform",
                        "limestone; bauxite (aluminium) and lateritic nickel under hot wet uplands; chromite with platinum,",
                        "osmium and iridium in serpentinite; titanium, monazite and thorium in old beach sands; salt, potash and",
                        "saltpetre in desert basins; uranium in roll fronts in sandstone. Those mods' own ore of these is not",
                        "placed then. Off by default: a pack that relies on finding them everywhere will find them rarer.")
                .define("geologicalModOres", false);
        UNIFY_EXISTING_CHUNKS = b
                .comment("Also make the ores one in chunks made before, as each is next loaded. Off: those keep what they had.")
                .define("unifyOresInExistingChunks", false);
        b.pop();

        b.push("diagnostics");
        STALL_REPORT_SECONDS = b
                .comment("If the server thread goes this many seconds without finishing a tick, write every",
                        "thread's stack to logs/fts_geology_stall.txt, once per stall. The stacks show what the",
                        "game was waiting on when it froze. Nothing else is read or written. 0 turns it off.")
                .defineInRange("stallReportSeconds", 30, 0, 3600);
        VERBOSE_LOG = b
                .comment("Write the mod's running counts and events to the log: rivers, villages and hot springs as",
                        "the world is made, retrogen, volcanoes finishing and waking, quakes, springs growing. For",
                        "testing and for bug reports; off, the log keeps only warnings and one-off notes.")
                .define("verboseLog", false);
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
        SEISMOGRAPH_SIREN = b
                .comment("The magnitude, as a station reads it, from which it sounds its siren. Smaller quakes it",
                        "records with a short chime of notes. 11 never wails.")
                .defineInRange("seismographSirenMagnitude", 6.0, 0.0, 11.0);
        TURBINE_MAX_FE = b
                .comment("Forge Energy per tick a geothermal turbine makes when its well reaches the hottest",
                        "ground: a hot spring's or geyser's deep chamber with its magma bed. A short well to a",
                        "pool's own bed makes less. Turbines within eight blocks of each other draw on the same",
                        "heat and share it, so a field of them makes little more than one well sited.")
                .defineInRange("turbineMaxFePerTick", 480, 1, 100000);
        TURBINE_MIN_FE = b
                .comment("Forge Energy per tick over the faintest heat a turbine will run on at all.")
                .defineInRange("turbineMinFePerTick", 80, 0, 100000);
        TURBINE_WELL_DEPTH = b
                .comment("How deep a turbine's well may run, in lengths of well casing. A hot spring's reservoir",
                        "sits thirty to sixty blocks under its pool; a natural geyser's chamber is down near the",
                        "bottom of the world, out of reach unless this is raised.")
                .defineInRange("turbineWellMaxDepth", 96, 1, 512);
        WELL_PUMP_RATE = b
                .comment("Millibuckets a tick a well pump lifts at most. What it lifts is also what the rock at its",
                        "well's foot lets through -- gravel and sand far more than this, clay a trickle -- and what",
                        "the water standing over the foot allows: drawn down to the foot, the well runs dry.")
                .defineInRange("wellPumpRate", 100, 1, 10000);
        WELL_PUMP_ENERGY = b
                .comment("Forge Energy a well pump spends on each bucket for each block it lifts the water.")
                .defineInRange("wellPumpEnergyPerBucketBlock", 40, 0, 100000);
        CORE_DRILL_DEPTH = b
                .comment("How many blocks down the core drill bores from the block it is set on. Its core logs every",
                        "bed it went through, any deposit named for what laid it, and the water table.")
                .defineInRange("coreDrillDepth", 64, 8, 512);
        b.pop();

        b.push("hydrology");
        RIVERS = b
                .comment("Trace rivers down the raw ground and fill them, in the mod's own world types only.",
                        "This is the newest and least settled thing in the mod. A river is cut by the",
                        "terrain itself, so turning this off changes the shape of the land as well as",
                        "taking the water away - in a world you have already played, the chunks you",
                        "have been to keep their channels and the ones past them do not, which leaves",
                        "a seam. Set it before a world is made, not after.",
                        "Off, the world keeps vanilla's own rivers and nothing else.")
                .define("rivers", true);
        RIVERS_REFILL = b
                .comment("The water of the mod's rivers and lakes comes back where it is lost. After a quake, a",
                        "landslide or a cave falling in, the rivers and lakes of the ground that moved are laid again",
                        "at their level, as far as their banks hold them. Near players, water taken out of a river or",
                        "drained away by another mod flows back in from the water beside it, a little at a time.",
                        "A stretch walled off from the rest of the river stays dry.")
                .define("riversRefill", true);
        SOIL_WATER = b
                .comment("Keep account of the water in the ground across the overworld: how wet the soil is at the top,",
                        "in the root zone and deep down, water standing on it, and how far the groundwater stands over",
                        "or under its usual level. Rain soaks in as the soil takes it, sun and plants dry it out, the",
                        "deep soil feeds the groundwater. A game day is a week of the ground's time: a shower wets the",
                        "top for half a day, a dry spell dries the root zone in three or four. Kept in the chunks' own",
                        "saves, a small share of the tick. On its own it changes no block; /geology water, the core",
                        "sample and the geothermal probe read it. Not in TerraFirmaCraft, which keeps its own.")
                .define("soilWater", true);
        SOIL_WATER_GROUND = b
                .comment("Let the water in the ground show (needs soilWater). Grass, ferns and the grass block turn",
                        "towards straw as the root zone dries -- from about the third dry day, straw by the seventh --",
                        "and green again after rain; farmland over soil wet enough stays moist without water beside",
                        "it, as rain-fed fields do. A long drought -- the roots at the wilting point for six game days",
                        "and more -- kills the grass back to bare soil in patches, and no grass spreads onto dry ground",
                        "meanwhile; it grows back after the rain comes. Grass within four blocks of water stays green,",
                        "and nothing a player placed is touched. A dry field's crops slow and stop; a watered one grows",
                        "as ever. Ground that stands in water two days and more, where the groundwater is up at the surface,",
                        "goes to mud in its hollows and dries out again. /geology water weather dry|wet <days>",
                        "shows what a drought or a wet spell does, without waiting. Off by default while it is new.")
                .define("soilWaterChangesGround", false);
        WATER_LOAD_COLLAPSE = b
                .comment("A cave roof may give way under water brought over it later -- a reservoir, a pond dug and filled --",
                        "or, in karst, once the wells round it have drawn the water down out of its cave: only a roof that",
                        "was nearly failing anyway, thin, wide and in soft or soluble rock. It drips and creaks a minute or",
                        "two first. The lakes and rivers the world was made with are the load the roofs grew up under, and",
                        "water that only passes (a flood, another mod's water finding its level) does not count.")
                .define("waterLoadCollapsesCaves", true);
        SINKHOLE_WARNING_DAYS = b
                .comment("Game days a cave roof in karst takes to come down once the wells round it have drawn its water out:",
                        "the ground over it cracks a third of the way, sinks a block in a ring round the middle at two thirds,",
                        "and then the sinkhole opens. A roof under water brought over it goes in a minute or two.")
                .defineInRange("sinkholeWarningDays", 1.5, 0.001, 30.0);
        SUBSIDENCE = b
                .comment("The ground settling over clay and soft soil the wells round it have drawn the water out of, as on the",
                        "Konya plain: slowly, over game days, a block at a time in rings round the wells, the soil cracking first,",
                        "and for good -- the water coming back does not lift it again. How far it settles follows the deepest the",
                        "water has been drawn and how much soft ground is under it; sand, gravel and rock hardly settle. What",
                        "a player built stays where it stands. 1 is the mod's rate, 2 twice as much, 0 none.")
                .defineInRange("groundSubsidence", 1.0, 0.0, 5.0);
        REGIONAL_RAIN = b
                .comment("Rain in storms that drift with the wind, not over the whole world at once: each region goes through",
                        "wet and dry spells lasting days to weeks, wet biomes get more rain than dry ones, a windward mountainside",
                        "more than its lee. It rains, snows, fills cauldrons and strikes lightning only under a storm, and each",
                        "player sees the weather where they stand. /weather still works: rain brings a storm over the players.",
                        "Not in TerraFirmaCraft, which keeps its own climate.")
                .define("regionalRain", true);
        RAIN_AMOUNT = b
                .comment("How much rain the storms bring, 1 as designed: about a fifth of the land under rain in a temperate",
                        "region on average, most of it in a wet spell, little in a dry one.")
                .defineInRange("rainAmount", 1.0, 0.0, 4.0);
        RAIN_STRENGTH = b
                .comment("How hard the storms rain, 1 as designed: under 1 more drizzle and fewer downpours, over 1 the other way.")
                .defineInRange("rainStrength", 1.0, 0.25, 2.0);
        STORM_LENGTH = b
                .comment("How long the storms last, 1 as designed: a shower an hour or so, a front most of a day, a wet spell's rain",
                        "days to weeks. Twice as long is half as many of them for the same amount of rain.")
                .defineInRange("stormLength", 1.0, 0.25, 4.0);
        STORM_SIZE = b
                .comment("How wide the storms are, 1 as designed: showers a few hundred blocks across, fronts a couple of thousand.",
                        "Where the land wants little rain a storm is kept small enough not to bring more than it wants.")
                .defineInRange("stormSize", 1.0, 0.25, 3.0);
        PUDDLES = b
                .comment("Puddles after rain where the ground holds water: in hollows of rock, clay, paths and soaked loam, never on",
                        "sand, in ragged shapes that shrink as the ground dries; a pixel of water a shader draws as water. Only",
                        "without Puddles & Floods, whose own puddles are told where the ground holds water instead. Needs soilWater.")
                .define("puddles", true);
        RIVER_SPILLS = b
                .comment("A river runs out through its bank where a block beside or under its water is taken away: ordinary running",
                        "water into the gap, kept coming from the river until the hole is full (with Flowing Fluids too).")
                .define("riverSpills", true);
        STREAMS_DRY_UP = b
                .comment("A small stream over ground the wells round it have drawn down two blocks and more runs dry there, and",
                        "fills back in from its ends once the water comes back under a block.")
                .define("streamsDryUp", true);
        RIVER_FLOODS = b
                .comment("A river over its banks where it rains hard over it on ground already soaked: a block over the low ground",
                        "beside it, two in the heaviest rain, for as long as the rain goes on, then down a layer at a time, leaving",
                        "some mud (with soilWaterChangesGround). In a narrow valley it comes as a flash flood and carries along",
                        "what stands in it. The ground is not moved. Needs soilWater.")
                .define("riverFloods", true);
        HOLLOW_PONDS = b
                .comment("Rain the soaked ground cannot take in runs off; what runs into a closed hollow stands there as a pond,",
                        "as deep as the hollow holds up to the lowest point of its rim and no deeper than the water that gathered,",
                        "until it soaks in and dries up after the rain, a layer at a time, leaving some mud. Water is laid only",
                        "there, never over the ground at large. Needs soilWater.")
                .define("rainPondsInHollows", true);
        LAKE_LEVELS = b
                .comment("A lake goes down a block in a region's long drought, its shallow shore drying out, and comes back when the",
                        "rains do, filling from the water still in it. Needs regionalRain for the region's wet and dry spells.")
                .define("lakesFallInDroughts", true);
        DAMS = b
                .comment("A river walled across fills up behind the wall, a layer at a time, as fast as the river brings water,",
                        "up to the top of the wall or the lowest gap in the valley; a wall too thin for the water it holds",
                        "(stone about as thick as the water is high, concrete a little over half, earth three times, an earth",
                        "bank not overtopped) leaks, creaks and breaks, and the reservoir runs out through the gap.")
                .define("dams", true);
        SEA_WATER_SALTY = b
                .comment("The sea is salt. Water at sea level in an ocean or on a beach waters no field, and a bucket dipped",
                        "in it comes up with sea water, which waters none wherever it is poured. River, lake, well and rain",
                        "water are fresh and water fields as ever.")
                .define("seaWaterSalty", true);
        KARST = b
                .comment("Karst, in the mod's own world types: where marble or calcite lies close under a plateau's",
                        "flat beds, a platform's or a foreland's, in the regions that have it, the ground takes its",
                        "water underground. It is pocked with sinkholes, and now and then a small river falls into a",
                        "swallow hole, runs on in a cave under its dry valley and comes out further down. Like the",
                        "rivers, it shapes the world as it is made: set it before a world is made.")
                .define("karst", true);
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

        b.comment("Terrain: the FT's Geology world type, where the plates shape the ground. Nothing here touches",
                "a world made with any other world type.").push("terrain");
        OCEAN_SHARE = b
                .comment("The share of plates that are oceanic crust, and so sea. Set when a world is made; the",
                        "plates never change afterwards.")
                .defineInRange("oceanShare", 0.4D, 0.0D, 1.0D);
        TERRAIN_BELT_FACTOR = b
                .comment("How far a mountain belt reaches from its plate boundary, in fault widths (faultWidth above).",
                        "A collision lifts a fold belt this far either side of the boundary; a subduction zone its arc",
                        "on the overriding side and a trench on the other; a rift its graben and shoulders. Everything",
                        "else the mod does is measured from the same boundary in the same units, so the mountains, the",
                        "volcanoes, the quake corridor and the ore belts line up.")
                .defineInRange("terrainBeltFactor", 2.5D, 0.5D, 10.0D);
        TERRAIN_UPLIFT = b
                .comment("How high a collision lifts the ground at the boundary, in blocks, before the peaks",
                        "the ridge noise adds on top. The crests of a belt get all of it, the passes between them",
                        "about half; too much and the highest crests reach the top of the terrain and flatten out.")
                .defineInRange("terrainUplift", 140.0D, 0.0D, 400.0D);
        DEM_HEIGHT_SCALE = b
                .comment("How tall the real mountains are laid out, against the ground they were measured on.",
                        "The mod carries crops of the Alps, the Caucasus, the Himalaya, the Karakoram and the",
                        "Appalachians, and lays a mountain belt out from them at true scale: twenty-five metres to",
                        "the block in the normal world, ten in the tall one. 1 is that true scale; higher makes the",
                        "ranges taller and steeper than the real ones, lower flattens them.")
                .defineInRange("demHeightScale", 1.0D, 0.1D, 4.0D);
        LITHOLOGY = b
                .comment("Lay the rock the plates imply under the ground: a platform's beds over its basement, a fold",
                        "belt's gneiss and marble, an arc's ash over its plutons. Off leaves plain stone, and chunks",
                        "generate a little faster.")
                .define("lithology", true);
        ALLUVIAL_CROP_GROWTH = b
                .comment("How many times as fast crops on tilled soil ripen, and melon and pumpkin stems fruit, on the",
                        "alluvial plain, where the rivers spread their silt in flood. 1 grows them as anywhere else.",
                        "Bone meal is not changed.")
                .defineInRange("alluvialCropGrowth", 1.5D, 1.0D, 4.0D);
        b.pop();

        SPEC = b.build();
    }

    private GeyserConfig() {}
}
