package com.jeladastudios.ftsgeology.gas;

import net.minecraftforge.common.ForgeConfigSpec;

public final class GasConfig {
    public static final ForgeConfigSpec SPEC;

    // Simulation
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue CELL_BUDGET;
    public static final ForgeConfigSpec.IntValue SWEEP_INTERVAL;
    public static final ForgeConfigSpec.IntValue SIM_RADIUS_CHUNKS;
    public static final ForgeConfigSpec.IntValue MAX_CELLS;
    public static final ForgeConfigSpec.DoubleValue DIFFUSION;
    public static final ForgeConfigSpec.DoubleValue PRESSURE_FLOW;
    public static final ForgeConfigSpec.DoubleValue BUOYANCY;
    public static final ForgeConfigSpec.DoubleValue SKY_VENT;
    public static final ForgeConfigSpec.DoubleValue CONDENSATION;
    public static final ForgeConfigSpec.DoubleValue ROCK_EXCHANGE_DAYS;

    // Explosions
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_SCALE;
    public static final ForgeConfigSpec.DoubleValue MAX_EXPLOSION_POWER;
    public static final ForgeConfigSpec.IntValue MAX_EXPLOSIONS_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue EXPLOSIONS_BREAK_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue EXPLOSIONS_SPAWN_FIRE;

    // Ignition
    public static final ForgeConfigSpec.BooleanValue TORCHES_IGNITE;
    public static final ForgeConfigSpec.DoubleValue MINING_SPARK_CHANCE;
    public static final ForgeConfigSpec.DoubleValue FLASHBACK_CHANCE;

    // Health
    public static final ForgeConfigSpec.BooleanValue HEALTH_EFFECTS;
    public static final ForgeConfigSpec.BooleanValue SMELL_MESSAGES;

    // World
    public static final ForgeConfigSpec.DoubleValue FIREDAMP_CHANCE;
    public static final ForgeConfigSpec.DoubleValue KARST_CO2_CHANCE;
    public static final ForgeConfigSpec.BooleanValue VOLCANO_GAS;
    public static final ForgeConfigSpec.BooleanValue COAL_RELEASES_METHANE;
    public static final ForgeConfigSpec.DoubleValue OUTBURST_CHANCE;
    public static final ForgeConfigSpec.BooleanValue SWAMP_GAS;
    public static final ForgeConfigSpec.DoubleValue SEEP_CHANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Gas simulation").push("simulation");
        ENABLED = b.comment("The gases at all: off, nothing is simulated, breathed or lit, and the machines hold their gas.")
                .define("gases", true);
        CELL_BUDGET = b.comment("Maximum gas cells simulated per server tick. Lower = better TPS, slower gas.")
                .defineInRange("cellBudgetPerTick", 8000, 500, 200000);
        SWEEP_INTERVAL = b.comment("Minimum ticks between two full simulation sweeps.")
                .defineInRange("sweepIntervalTicks", 2, 1, 40);
        SIM_RADIUS_CHUNKS = b.comment("Gas is only simulated within this many chunks of a player.")
                .defineInRange("simulationRadiusChunks", 8, 2, 32);
        MAX_CELLS = b.comment("Safety cap: above this many gas cells in a dimension, gas stops spreading into new cells.")
                .defineInRange("maxCells", 200000, 1000, 5000000);
        DIFFUSION = b.comment("Molecular/turbulent mixing between neighbouring cells per sweep.")
                .defineInRange("diffusionRate", 0.03, 0.0, 0.16);
        PRESSURE_FLOW = b.comment("Bulk flow share of a pressure difference per sweep.")
                .defineInRange("pressureFlowRate", 0.15, 0.0, 0.16);
        BUOYANCY = b.comment("How fast light gases (CH4, H2) rise and heavy ones (CO2, H2S) sink.")
                .defineInRange("buoyancyRate", 0.3, 0.0, 0.5);
        SKY_VENT = b.comment("How fast gas under the open sky is carried away by wind per sweep.")
                .defineInRange("skyVentRate", 0.06, 0.0, 1.0);
        CONDENSATION = b.comment("Fraction of excess water vapour condensing per sweep.")
                .defineInRange("condensationRate", 0.02, 0.0, 1.0);
        ROCK_EXCHANGE_DAYS = b.comment("Game days over which gas under ground with nothing feeding it drifts back toward air, the rock",
                        "breathing with the surface (cave air). 0 keeps it for ever.")
                .defineInRange("caveAirExchangeDays", 3.0, 0.0, 365.0);
        b.pop();

        b.comment("Gas explosions").push("explosions");
        EXPLOSION_SCALE = b.comment("Explosion power = scale * cbrt(released energy in MJ). TNT = 4.")
                .defineInRange("explosionPowerScale", 1.1, 0.0, 10.0);
        MAX_EXPLOSION_POWER = b.defineInRange("maxExplosionPower", 10.0, 1.0, 50.0);
        MAX_EXPLOSIONS_PER_TICK = b.defineInRange("maxExplosionsPerTick", 8, 1, 64);
        EXPLOSIONS_BREAK_BLOCKS = b.define("explosionsBreakBlocks", true);
        EXPLOSIONS_SPAWN_FIRE = b.define("explosionsSpawnFire", true);
        b.pop();

        b.comment("Ignition sources").push("ignition");
        TORCHES_IGNITE = b.comment("Torches, candles, fire, lava, campfires, lit furnaces and magma ignite flammable gas. Lanterns are enclosed (like a Davy lamp) and never do.")
                .define("openFlamesIgnite", true);
        MINING_SPARK_CHANCE = b.comment("Chance that mining stone/ore in a flammable atmosphere strikes an igniting spark.")
                .defineInRange("miningSparkChance", 0.005, 0.0, 1.0);
        FLASHBACK_CHANCE = b.comment("Per-second chance that a burner drawing a flammable (premixed) mixture flashes back into its source.")
                .defineInRange("burnerFlashbackChance", 0.25, 0.0, 1.0);
        b.pop();

        b.comment("Health").push("health");
        HEALTH_EFFECTS = b.define("healthEffects", true);
        SMELL_MESSAGES = b.comment("Show smell hints (H2S rotten eggs, SO2) in the action bar.")
                .define("smellMessages", true);
        b.pop();

        b.comment("Natural gas sources").push("world");
        FIREDAMP_CHANCE = b.comment("Chance, in a newly made chunk where a coal seam runs beside a cave deep enough, that the cave holds",
                        "firedamp (methane) under its roof, richer the deeper the seam.")
                .defineInRange("firedampChance", 0.15, 0.0, 1.0);
        KARST_CO2_CHANCE = b.comment("Chance, in a newly made chunk, that a closed cave hollow walled in calcite, marble or dripstone holds a few",
                        "per cent of carbon dioxide on its floor, as limestone caves do.")
                .defineInRange("karstCo2Chance", 0.3, 0.0, 1.0);
        VOLCANO_GAS = b.comment("A live volcano breathes its carbon dioxide, with a little sulphur dioxide and hydrogen sulphide, into",
                        "the air of its crater, vents and hollows, where it lies and spreads (with volcanicGas on in fts_geology.toml).")
                .define("volcanoGas", true);
        SEEP_CHANCE = b.comment("Share of the places where serpentinite of an ophiolite comes to the surface that have a burning methane",
                        "seep, as at the Chimaera of Lycia (Yanartas): methane the rock itself makes. New chunks of the mod's own world type.")
                .defineInRange("naturalSeepChance", 0.3, 0.0, 1.0);
        COAL_RELEASES_METHANE = b.comment("Mining coal ore releases trapped methane.")
                .define("coalReleasesMethane", true);
        OUTBURST_CHANCE = b.comment("Chance that mining deep coal triggers a large gas outburst.")
                .defineInRange("outburstChance", 0.03, 0.0, 1.0);
        SWAMP_GAS = b.comment("Swamps and mangroves bubble methane (marsh gas).")
                .define("swampGas", true);
        b.pop();

        SPEC = b.build();
    }

    private GasConfig() {
    }
}
